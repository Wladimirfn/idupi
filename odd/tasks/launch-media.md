# Launch media (README screens + launch video)

Locator: `odd/tasks/launch-media.md` · Engram mirror: `odd/launch-media/tasks`
Branch: `docs/launch-media` (from main 0967429)

## Objective

Refresh the README screenshots (the current six are outdated and the new
Alertas, Agenda and Salud screens are missing) and produce a 5–10 minute launch
video that explains what IDUPI is, its features, how to use it and what it
needs. The owner publishes it on YouTube, LinkedIn, X and Instagram.

## Constraints (owner decisions, 2026-11-25)

- Real screens, not a demo dataset.
- Alertas is the most sensitive screen: message content must be removed.
- Salud and Huawei data may stay visible.
- Blur IPs, ports, tokens and passwords anywhere they appear.
- The finished video goes to the owner's phone for review BEFORE any upload.
  Claude never uploads to social networks.
- Video work runs through the IDUPI fleet: `design-2` (antigravity +
  gemini-4-neon-xhigh) is the video designer, `design-1` (antigravity +
  gemini-4-hydrogen-xhigh) the IDUPI designer, `voice-1` narration,
  `music-1` soundtrack.
- Narration and on-screen text in neutral Latin American Spanish (no voseo).
- Video project lives outside the repo (large binaries):
  `C:\Users\elmas\Videos\idupi-launch`.

## Tasks

- [x] T1 Capture current screens from the phone (hidden virtual display),
      redact Alertas messages and any IP/token, save to
      `docs/assets/screenshots/`, update the README gallery (add Alertas,
      Agenda, Salud). Commit.
- [x] T2 Video preproduction: intent (presentation), lane, style brief,
      art direction, script (`guion.md`), `e00` styleboard. Owner approves.
- [x] T3 Capture real UI footage (screen recordings of each feature) with the
      same redaction rules.
- [x] T4 Narration (voice-1) + music (music-1) + audio gate.
- [ ] T5 Composition and keyframes (design-2), independent audit.
- [ ] T6 Delivery render + motion gate; send the MP4 to the owner's phone for
      review.
- [ ] T7 After owner approval: platform cuts (vertical teaser for Instagram/X)
      if requested.

## Acceptance criteria

- README shows 9 current screens with no legible message content, IP or token.
- Video 5–10 min, 16:9, neutral Spanish narration, every feature claim shown
  with a real capture.
- Motion and audio gates pass; independent audit has no `block`.
- Owner reviewed the MP4 on the phone.

## Checks

- Visual inspection of every published image/frame for private data.
- `idu-pi video validate-audio`, `idu-pi video audit`, `idu-pi video render`
  (motion gate).

TDD: not applicable (media and docs only).

## Progress

- 2026-11-25: fleet `design-1`/`design-2` switched to antigravity
  gemini-4-hydrogen-xhigh / gemini-4-neon-xhigh (`~/.idu/profiles.json`,
  backup `.bak-20261125`); agy updated 1.5.15 → 1.5.17.
- T1 done: 10 screens captured on a hidden scrcpy display (id 95, screencap needs the
  SurfaceFlinger id), redacted (paths/username, hostname, IP, agenda events) and
  resized to 720 px; README gallery now has two rows incl. Alertas, Agenda, Salud,
  Pantalla remota. Alertas showed only the Huawei sleep report (allowed). Found and
  fixed: default PowerShell PTY printed UTF-8 as mojibake -> e6ba50f (856 tests
  pass, code page 65001 verified). Takes effect at the next server restart.
- PR #26 opened (screens + UTF-8 fix). guion.md written (12 scenes, ~7:35).
- T2 (design-2, IDU-20261125115733-design-2-yqtv, 16.5 min): style-brief.md,
  art-direction.md, composition/index.html, keyframes/e00-styleboard.png (e03
  terminals hero). Proof gaps flagged: e04 idupi-push PC output, e06 idle stream,
  e07 app picker, e08 conflict banner, e09 AI summary card. Voicebox started
  (profiles: Alex, Dora, Qwen*, cloned Eric). Wireless ADB went offline afterwards.
- 2026-11-25: owner approved e00 (style-brief flipped). T3 extra captures redacted into
  assets/: push.jpg (real idupi-push ls), alertas-ajustes/recordatorios/analista.jpg
  (followed-apps list skipped: it shows banking apps), salud-resumen.jpg. Note: the
  virtual display now shows Samsung nav buttons at the bottom; crop to 2200 px.
- Narration (T4, voice part): owner chose cloned Eric variant A (exag 0.7/cfg 0.4/temp 0.8
  + mastering). tools/narrate.py = tts_es validated chunks + Whisper turbo STT +
  must-hear acronyms (PC as pe-se, IDUPI, APK, CPU, Node.js) + glossary.json. All 12
  scenes verdict pass (audit/narration-report.json); 5:00 with 1.2 s gaps. Script
  tweaks: e04 "Desde la PC, tú escribes", e12 "Esto es IDUPI: ...".
- 2026-11-25 owner approved narration. Music (music-1, 37 min): "Senal suave al amanecer",
  84 BPM D major, 320 s, no drums, AI score (engine arrangement arc disabled at
  render time; engine source untouched). Composition (design-2, 30 min): 12 scenes,
  12 skeletons, 13 audio tags, keyframes deterministic. e03/e11 re-narrated
  (Claude, Tailscale); tools/sync_timeline.mjs keeps scene cuts fixed. Mix
  (tools/mix.mjs): music -4 dB + sidechain duck under voice, -14 LUFS, peak -2.3.
  Audio gate PASS with Whisper turbo. Skill change: voz_validator.py honors
  IDU_STT_MODEL and knows pc/tailscale/claude(cloud) renderings (3 copies,
  incl. cross cli/skills-bundle, uncommitted there).

## Next step

Independent visual audit (consult-1, opencode) -> delivery render with audio/mix-hq.wav -> motion gate -> send MP4 to the phone.
