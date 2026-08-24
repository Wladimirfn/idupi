# IDUPI Remote Screen — Implementation Brief

Hand-off spec for building the remote screen module. Everything under
"Established by measurement" was verified on the target machine; do not
re-litigate it, and do not re-attempt what is listed as ruled out.

---

## 1. What IDUPI is

- `app/` — Android client (Kotlin, Jetpack Compose, MVVM, Ktor client).
- `idupi-server/` — companion Node server on the user's PC. **Zero npm
  dependencies, by deliberate design.** HTTP + SSE on `0.0.0.0:8788`, bearer
  token auth (`server-auth.mjs`).
- Existing surfaces: chat with three CLI engines (Pi, Claude, OpenCode),
  session browsing, live tool/subagent activity over SSE.

The new module adds: see a monitor, pick which monitor, move the mouse, type.

---

## 2. Established by measurement — do not re-derive

### 2.1 PowerShell screen capture is ruled out

Windows Defender's AMSI **blocks it**, inline and from a `.ps1` file alike:

```
Este script contiene elementos malintencionados y ha sido bloqueado
por el software antivirus.
```

`Graphics.CopyFromScreen` + `MemoryStream` + `Bitmap` is a standard infostealer
signature. Monitor *enumeration* via `System.Windows.Forms.Screen` passes;
*capture* does not. Do not spend time trying to word around it, and do not ask
the user to add a Defender exclusion.

### 2.2 A compiled Go helper works

Go is installed on the target machine. A compiled binary is not subject to
AMSI script scanning. Go's standard library covers the whole job with **no
external modules**, matching the server's zero-dependency rule:

- `syscall` + `user32.dll` / `gdi32.dll` — `EnumDisplayMonitors`,
  `GetMonitorInfoW`, `GetDC`, `CreateDIBSection`, `BitBlt`, `SendInput`.
- `image/jpeg` — encoding.
- `encoding/json` — the protocol.

A working spike exists (monitor enumeration + BitBlt capture + JPEG encode).
It is scratch code, not production code — read it for the Win32 call
signatures and struct layouts, then write the real thing:

```
%TEMP%/claude/<project-sandbox>/<session>/scratchpad/spike/main.go
```

If it is gone, the call sequence is: `GetDC(0)` →
`CreateCompatibleDC` → `CreateDIBSection` (negative `biHeight` for top-down,
`biBitCount = 32`) → `SelectObject` → `BitBlt(SRCCOPY)` → read the DIB bits as
BGRA → convert → encode. Release every handle.

### 2.3 Measured baseline (target machine)

```
monitor[0]  \\.\DISPLAY1   x=-1920 y=0  1920x1080
monitor[1]  \\.\DISPLAY2   x=0     y=0  1920x1080  (primary)

full-scale capture + JPEG q55:  ~57-59 ms/frame   ~17 fps   ~160 KB/frame
```

Two things follow:

- **Negative monitor origins are real.** The left monitor starts at `x=-1920`.
  Any coordinate mapping that assumes a `(0,0)` origin is wrong on this
  machine and on most multi-monitor setups. Test against a negative origin.
- 160 KB/frame at 17 fps is ~2.7 MB/s for ONE client at full scale. That is
  the number the adaptive design has to beat, and §4 explains how.

Not measured: the split between BitBlt, the BGRA→RGBA conversion, and JPEG
encode. The per-pixel conversion loop over 2M pixels is a suspected hot spot —
encoding directly from a custom `image.Image` that reads BGRA in place would
remove it. Measure before optimising.

---

## 3. Architecture

```
Android app  ──HTTP/SSE──▶  idupi-server (Node, zero deps)  ──stdin/stdout──▶  idupi-screen.exe (Go)
             ◀────────────                                  ◀────────────
```

The Go helper is a **long-lived child process**, spawned like the CLI engines
already are. One process, JSON lines in on stdin, framed responses out on
stdout. Not one process per frame.

Frames are binary. Do not base64 them into the JSON line: use a length-prefixed
binary frame on stdout, or a second pipe. Base64 costs 33% on the single
hottest path in the system.

---

## 4. Adaptive quality — the actual design

The user's requirement: presets **Baja / Media / Alta / Automático**, and the
recognition that the sender's capacity and the receiver's are different things.
That is correct, and it is what real remote desktop does. Four mechanisms, in
order of how much work they do:

### 4.1 The receiver paces. The sender never pushes. (Most important)

The server does **not** capture on a timer and push. The client requests the
next frame (or acknowledges the last), and only then does the server capture a
**fresh** one.

Everything else follows from this one rule:

- A slow client simply receives fewer frames. No measurement required.
- **There is never a queue.** Every frame the client renders is the newest
  state of the screen, never a stale one from three seconds ago.
- A fast PC and a slow phone reconcile without negotiating anything.

This is why RDP/VNC feel "behind but current" rather than "smooth but late".
Timer-push is what produces the sensation of the screen arriving late.

**Never buffer frames waiting for a slow client.** If a frame is still in
flight, do not capture another.

### 4.2 Never send more pixels than the receiver can display

The client sends its viewport size in device pixels with the stream request.
A phone displaying a 1920x1080 desktop at ~800px wide gets **800px wide**.

This is the single largest saving and it costs nothing: it shrinks the
conversion loop, the JPEG encode, and the bytes on the wire together. Server
scales during capture; it must never send more than `min(viewport, native)`.

### 4.3 Send only what changed

A desktop is mostly static. Split the frame into tiles (64x64 or 128x128),
hash each against the previous frame, send only dirty tiles. Typing moves a
caret and one line — not 2 megapixels.

Send a full keyframe on: first frame, monitor switch, quality change, and on
client request (recovery from a dropped tile).

### 4.4 "Automático" is a control loop over a ladder

With 4.1–4.3 in place, Auto only needs per-frame telemetry from the client:
bytes received, and time from request to fully rendered.

Ladder (indicative — tune with real numbers, do not treat as gospel):

| Preset | Scale        | JPEG q | Max fps |
|--------|--------------|--------|---------|
| Baja   | 0.4 viewport | 40     | 10      |
| Media  | 0.7 viewport | 55     | 15      |
| Alta   | 1.0 viewport | 75     | 24      |

Auto walks it: **step down fast on congestion, step up slowly after sustained
headroom.** Same shape as adaptive-bitrate video and TCP congestion control.
Reacting late ruins the session; climbing eagerly re-breaks it. Require several
consecutive good frames before stepping up; step down on a single bad one.

### 4.5 Input never queues behind video

A mouse move must not wait behind a 160 KB frame. Separate endpoint, separate
priority, no shared buffer with the frame path. This is not an optimisation —
input latency is what the whole feature is judged on.

---

## 5. Protocol sketch

Follow the existing conventions in `idupi-server/index.mjs` and
`chat-events.mjs`. All endpoints sit behind `requireAuth`.

```
GET  /api/v1/screen/monitors
     -> [{ id, name, primary, x, y, width, height, scaleFactor }]

GET  /api/v1/screen/stream?monitor=<id>&viewportW=<px>&viewportH=<px>&quality=auto|low|medium|high
     -> SSE. Frames are the payload; see §3 on not base64-ing them.
        Emits: frame (keyframe|tiles), quality_changed, monitor_lost

POST /api/v1/screen/ack        { frameId, bytes, renderMs }
POST /api/v1/screen/input      { type: "move"|"down"|"up"|"scroll"|"key"|"text", ... }
```

Input coordinates travel **normalised (0..1) against the selected monitor**,
not in pixels. The client does not know the monitor's true resolution or its
origin, and §2.3's negative origin means raw pixel maths on the client is a
bug waiting to happen. The server maps normalised → virtual-desktop absolute
for `SendInput`.

---

## 6. Android client

Two layouts, as requested:

- **Portrait**: screen on top, keyboard/controls below. Pan/zoom the screen.
- **Landscape**: screen fills, controls overlay.

Requirements:

- **Real-time typing.** Do not wait for an IME commit. Send each keystroke as
  it happens. An `OutlinedTextField` that only reports on submit is the wrong
  primitive — capture key events directly.
- Tap → move + click. Drag → move. Two-finger → scroll. Long-press → right
  click.
- Show the current preset and, in Auto, what it actually settled on. A user
  who cannot see that Auto dropped to Baja thinks the app is broken.

---

## 7. Non-negotiables

1. **Zero npm dependencies in `idupi-server/`.** Stated deliberately in
   `chat-events.mjs`. The Go helper is a spawned process, not a dependency.
2. **Go standard library only** in the helper. No external modules.
3. **Tests before the fix, proven RED.** This repo's discipline: write the
   test, run it, show it failing, then implement. Server tests are
   `node --test idupi-server/test/*.test.mjs` (run from the repo root — at
   least one test resolves paths relative to it). App tests are
   `./gradlew :app:testDebugUnitTest`.
4. **Pure logic goes in its own module and gets unit tests.** Coordinate
   mapping, the quality ladder, and dirty-tile selection are all pure
   functions. Test them without a screen.
5. **Conventional commits. No AI attribution, no `Co-Authored-By`.**
6. **Never commit, push, or open a PR without the user asking.**
7. Comments explain **why**, not what. Match the density already in the repo.

---

## 8. Suggested order

1. Go helper: enumerate monitors, JSON on stdout. Wire
   `GET /api/v1/screen/monitors`. Prove it end to end before touching frames.
2. Go helper: capture one monitor at a requested size, JPEG, length-prefixed
   binary on stdout.
3. Server: spawn and supervise the helper (mirror the CLI-engine lifecycle —
   restart on crash, refuse work while shutting down).
4. SSE stream, ack-paced (§4.1). Full keyframes only.
5. Android: portrait view, monitor picker, render frames. **First milestone
   worth demoing.**
6. Input: `SendInput` in the helper, normalised coordinates, separate path.
7. Keyboard, real-time.
8. Dirty tiles (§4.3).
9. Quality ladder + Auto (§4.4).
10. Landscape layout, pan/zoom.

---

## 9. Open decisions for the user

1. **Binary distribution.** Compile on the user's machine at install time
   (requires Go), or ship a prebuilt `idupi-screen.exe` in the repo so other
   users need nothing? Asked, not yet answered.
2. **Security.** This turns the bridge into full keyboard and mouse control of
   the machine, and it listens on `0.0.0.0:8788`. Today a bearer token is the
   only gate. For the user's own machine that was already serious; as a feature
   other people will run, it needs a deliberate decision — at minimum, whether
   remote input requires a separate opt-in from remote *viewing*. Raise it
   before shipping, with concrete options.
