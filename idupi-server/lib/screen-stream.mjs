// Screen streaming session -- PHASE D PACING (owner-approved redesign).
//
// The original design was strictly receiver-paced: one frame in flight, next
// capture only after its ack. That pays the network round trip TWICE per
// frame, which on a 157ms-ping link capped sessions at ~5fps no matter how
// fast the helper, the encoder or the phone were.
//
// Captures now ride a TIMER at the active preset's fps and are sent as soon
// as they exist. Acks no longer gate anything: they feed the quality ladder
// (congestion telemetry) and bound an unacked WINDOW -- when K frames have
// been sent without any ack coming back, captures SKIP instead of queueing,
// so what reaches the phone is always fresh content, never a stale backlog.
// Frames whose ack never arrives expire after STALE_ACK_TIMEOUT_MS, so a lost
// ack can never wedge the window shut. The client keeps acking exactly as
// before; nothing changes on the wire format or the app side.

import { EventEmitter } from "node:events";

import { createLadderController, QUALITY_LADDER } from "./screen-quality.mjs";

/** Max frames sent without any ack returning before captures skip. */
const UNACKED_WINDOW = 4;

/**
 * Unacked frames older than this are assumed lost and their slot is
 * reclaimed. Acks can be dropped or delayed arbitrarily on a bad link; with
 * an entry that never expires, the window would sit full forever and
 * freeze capture for the rest of the session. Age, not just an ack, retires
 * a frame -- so a lost ack costs at most this timeout, never the stream.
 */
const STALE_ACK_TIMEOUT_MS = 1000;

export function createScreenStream({
  helper,
  monitor = 0,
  width,
  height,
  quality = 55,
  /** Test hook: overrides the preset-derived pacing interval. */
  paceIntervalMs = null,
}) {
  const events = new EventEmitter();
  let stopped = false;
  let capturing = false;
  let timerHandle = null;

  // Quality: "auto" hands the ladder controller the wheel; a preset NAME or
  // a number pins that choice manually (the human owns it).
  const baseWidth = width;
  const baseHeight = height;
  let auto = quality === "auto";
  const initialPreset = QUALITY_LADDER.find((p) => p.name === quality);
  let ladder = auto ? createLadderController() : null;
  // Auto starts at MEDIA (index 1): good default, room to climb and to fall.
  let currentQuality = auto
    ? QUALITY_LADDER[1].jpegQuality
    : (initialPreset ? initialPreset.jpegQuality : quality);
  let capW = width;
  let capH = height;
  // Manual mode gets a sane 30fps ceiling too: unbounded pacing would flood
  // the socket with duplicate static-screen frames for zero benefit. A named
  // preset paces at ITS OWN fps ceiling instead.
  let minIntervalMs = paceIntervalMs ??
    (initialPreset ? 1000 / initialPreset.maxFps : 33);
  if (initialPreset) {
    capW = Math.round(baseWidth * initialPreset.scale);
    capH = Math.round(baseHeight * initialPreset.scale);
  }
  let lastCaptureStartedAt = 0;

  // frame ids sent but not yet acked -> the ms timestamp they were sent at.
  // The age lets [pruneStale] reclaim slots whose ack never came back.
  const outstanding = new Map();

  // Instrumentation (optimization phase B): where do the milliseconds go?
  let framesEmitted = 0;
  let helperMsTotal = 0;

  function applyPreset() {
    const p = ladder.preset();
    currentQuality = p.jpegQuality;
    capW = Math.round(baseWidth * p.scale);
    capH = Math.round(baseHeight * p.scale);
    if (paceIntervalMs === null) minIntervalMs = 1000 / p.maxFps;
    events.emit("control", { type: "quality_changed", ...p });
  }

  if (auto) applyPreset();

  async function captureOnce() {
    if (stopped || capturing) return;
    capturing = true;
    try {
      lastCaptureStartedAt = Date.now();
      const frame = await helper.capture({
        monitor,
        width: capW,
        height: capH,
        quality: currentQuality,
      });
      if (stopped) return; // receiver left mid-capture: discard, never queue
      lastFrameId = frame.meta.id;
      // Per-frame latency of the Go helper round trip (capture+diff+encode),
      // surfaced in the frame meta and rolled into stats().
      frame.meta.helperMs = Date.now() - lastCaptureStartedAt;
      framesEmitted += 1;
      helperMsTotal += frame.meta.helperMs;
      outstanding.set(frame.meta.id, Date.now());
      events.emit("frame", frame);
    } catch (err) {
      // One failed capture costs one frame; the timer brings the next one.
      console.error("[screen-stream] capture failed:", err.message);
    } finally {
      capturing = false;
    }
  }

  let lastFrameId = null;

  function scheduleNext() {
    if (stopped) return;
    const elapsed = Date.now() - lastCaptureStartedAt;
    const wait = Math.max(15, minIntervalMs - elapsed);
    timerHandle = setTimeout(tick, wait);
  }

  /**
   * Reclaims window slots for frames whose ack never arrived. Without this,
   * a dropped ack pins its id in [outstanding] forever and, once the window
   * is full, tick() stops capturing for the rest of the session -- the
   * freeze only a monitor switch could clear.
   */
  function pruneStale(now = Date.now()) {
    const cutoff = now - STALE_ACK_TIMEOUT_MS;
    for (const [id, sentAt] of outstanding) {
      if (sentAt <= cutoff) outstanding.delete(id);
    }
  }

  async function tick() {
    if (stopped) return;
    // Congestion brake: too many frames without a single ack back means the
    // link cannot sustain this rate -- skipping keeps content FRESH (the next
    // successful tick captures the screen as it is THEN).
    pruneStale();
    if (outstanding.size < UNACKED_WINDOW) {
      await captureOnce();
    }
    scheduleNext();
  }

  return {
    onFrame(fn) {
      events.on("frame", fn);
    },
    onControl(fn) {
      events.on("control", fn);
    },

    /** Sends the first frame immediately, then starts the pace timer. */
    async start() {
      if (stopped) throw new Error("screen stream is stopped");
      await captureOnce();
      scheduleNext();
    },

    /**
     * Receiver telemetry, no longer a gate: stale/duplicate ids are no-ops,
     * every real ack feeds the ladder so congestion changes what we capture
     * (quality/scale/fps), and leaves the unacked window.
     */
    async onAck({ frameId, renderMs }) {
      if (stopped) throw new Error("screen stream is stopped");
      if (!outstanding.delete(frameId)) return; // stale, duplicate or unknown
      if (ladder) {
        const decision = ladder.observe({ renderMs });
        if (decision.direction !== "stay") applyPreset();
      }
    },

    /**
     * Live quality change. Three shapes:
     *  - "auto"      hands the ladder back the wheel (announces the preset)
     *  - a preset    "baja"|"media"|"alta"|"ultra" pins that preset manually
     *    name        (scale + jpeg quality + fps ceiling all follow it)
     *  - a number    legacy manual: fixed jpeg quality at full scale
     * Anything else throws -- the route surfaces it as 400.
     */
    setQuality(q) {
      if (q === "auto") {
        if (!auto) {
          auto = true;
          ladder = createLadderController();
          applyPreset(); // fresh ladder starts at MEDIA and says so
        }
        return;
      }
      const n = Number(q);
      const preset = QUALITY_LADDER.find((p) => p.name === q);
      if (preset) {
        auto = false;
        ladder = null;
        currentQuality = preset.jpegQuality;
        capW = Math.round(baseWidth * preset.scale);
        capH = Math.round(baseHeight * preset.scale);
        if (paceIntervalMs === null) minIntervalMs = 1000 / preset.maxFps;
        events.emit("control", { type: "quality_changed", ...preset });
        return;
      }
      if (Number.isFinite(n) && q !== "" && q !== null) {
        auto = false;
        ladder = null;
        currentQuality = n;
        capW = baseWidth;
        capH = baseHeight;
        if (paceIntervalMs === null) minIntervalMs = 33;
        events.emit("control", {
          type: "quality_changed",
          name: "manual",
          scale: 1,
          jpegQuality: n,
          maxFps: Math.round(1000 / minIntervalMs),
        });
        return;
      }
      throw new Error(`unknown quality: ${q}`);
    },
    /** Rolling pipeline telemetry (optimization phase B). */
    stats() {
      return {
        frames: framesEmitted,
        avgHelperMs: framesEmitted > 0 ? helperMsTotal / framesEmitted : 0,
      };
    },
    stop() {
      stopped = true;
      if (timerHandle) clearTimeout(timerHandle);
      events.removeAllListeners();
    },
  };
}
