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
// The client keeps acking exactly as before; nothing changes on the wire
// format or the app side.

import { EventEmitter } from "node:events";

import { createLadderController, QUALITY_LADDER } from "./screen-quality.mjs";

/** Max frames sent without any ack returning before captures skip. */
const UNACKED_WINDOW = 4;

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

  // Quality: a number is MANUAL (fixed, human-owned). "auto" hands the ladder
  // controller the wheel -- it picks jpeg quality AND capture scale from the
  // preset, and paces captures to the preset's max fps.
  const auto = quality === "auto";
  const baseWidth = width;
  const baseHeight = height;
  const ladder = auto ? createLadderController() : null;
  let currentQuality = auto ? QUALITY_LADDER[1].jpegQuality : quality;
  let capW = width;
  let capH = height;
  // Manual mode gets a sane 30fps ceiling too: unbounded pacing would flood
  // the socket with duplicate static-screen frames for zero benefit.
  let minIntervalMs = paceIntervalMs ?? 33;
  let lastCaptureStartedAt = 0;

  const outstanding = new Set(); // frame ids sent but not yet acked

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
      outstanding.add(frame.meta.id);
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

  async function tick() {
    if (stopped) return;
    // Congestion brake: too many frames without a single ack back means the
    // link cannot sustain this rate -- skipping keeps content FRESH (the next
    // successful tick captures the screen as it is THEN).
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

    /** Applies to subsequent captures; never triggers an unsolicited frame. */
    setQuality(q) {
      currentQuality = q;
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
