// Ack-paced screen streaming session (brief §4.1): the receiver paces, the
// sender never pushes. At most one frame is in flight per session; every
// frame the client renders is the newest screen state, never a queued stale
// one. Frames travel to the client as chunked binary using the same wire
// framing as the helper pipe — NOT SSE, because SSE's text lines would force
// base64 (+33%) on the single hottest path in the system.

import { EventEmitter } from "node:events";

import { createLadderController } from "./screen-quality.mjs";

export function createScreenStream({
  helper,
  monitor = 0,
  width,
  height,
  quality = 55,
}) {
  const events = new EventEmitter();
  let stopped = false;
  let capturing = false;
  let ackedFrameId = null;
  let lastFrameId = null;

  // Quality: a number is MANUAL (fixed, human-owned). "auto" hands the ladder
  // controller the wheel -- it picks jpeg quality AND capture scale from the
  // preset, and paces captures to the preset's max fps.
  const auto = quality === "auto";
  const baseWidth = width;
  const baseHeight = height;
  const ladder = auto
    ? createLadderController()
    : null;
  let currentQuality = auto ? ladder.preset().jpegQuality : quality;
  let capW = width;
  let capH = height;
  let minIntervalMs = 0;
  let lastCaptureStartedAt = 0;
  // Instrumentation (optimization phase B): where do the milliseconds go?
  // helperMs = capture+diff+encode round trip inside the Go helper.
  let framesEmitted = 0;
  let helperMsTotal = 0;

  function applyPreset() {
    const p = ladder.preset();
    currentQuality = p.jpegQuality;
    capW = Math.round(baseWidth * p.scale);
    capH = Math.round(baseHeight * p.scale);
    minIntervalMs = 1000 / p.maxFps;
    events.emit("control", { type: "quality_changed", ...p });
  }

  if (auto) applyPreset();

  async function captureOnce() {
    if (stopped || capturing) return;
    capturing = true;
    try {
      // Receiver-paced (brief §4.1), but never faster than the preset's fps:
      // pacing waits BEFORE capturing so latency lands between frames, not on
      // the wire.
      const elapsed = Date.now() - lastCaptureStartedAt;
      if (elapsed < minIntervalMs) {
        await new Promise((r) => setTimeout(r, minIntervalMs - elapsed));
      }
      lastCaptureStartedAt = Date.now();
      const captureStartedAt = lastCaptureStartedAt;
      const frame = await helper.capture({
        monitor,
        width: capW,
        height: capH,
        quality: currentQuality,
      });
      if (stopped) return; // receiver left mid-capture: discard, never queue
      lastFrameId = frame.meta.id;
      // Per-frame latency of the Go helper round trip (capture+diff+encode),
      // surfaced in the frame meta and rolled into stats() -- optimization
      // phase B decides with data, not guesses.
      frame.meta.helperMs = Date.now() - captureStartedAt;
      framesEmitted += 1;
      helperMsTotal += frame.meta.helperMs;
      events.emit("frame", frame);
    } finally {
      capturing = false;
    }
  }

  return {
    onFrame(fn) {
      events.on("frame", fn);
    },
    onControl(fn) {
      events.on("control", fn);
    },

    /** Sends the first fresh frame. Nothing else flows until an ack arrives. */
    async start() {
      if (stopped) throw new Error("screen stream is stopped");
      await captureOnce();
    },

    /**
     * The receiver finished rendering a frame: only now may we capture one
     * fresh frame. Duplicate or stale acks are idempotent no-ops.
     */
    async onAck({ frameId, renderMs }) {
      if (stopped) throw new Error("screen stream is stopped");
      // Only the most recent frame's ack matters; replays of older ids and
      // double-acks for the current one must not queue extra captures.
      if (frameId !== lastFrameId || ackedFrameId === frameId) return;
      ackedFrameId = frameId;
      // Auto feeds every real ack to the ladder BEFORE scheduling the next
      // capture, so congestion changes what we capture, not just when.
      if (ladder) {
        const decision = ladder.observe({ renderMs });
        if (decision.direction !== "stay") applyPreset();
      }
      try {
        await captureOnce();
      } catch (err) {
        // Nothing was produced, so the acknowledgement was never spent. Give
        // it back: with no timer underneath, a receiver whose retry we reject
        // as a duplicate has no other way to ask, and the session hangs until
        // its socket times out. One failed capture costs one frame.
        ackedFrameId = null;
        throw err;
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
    setMonitor(id) {
      monitor = id;
      ackedFrameId = null; // next ack after a switch gets a full keyframe
    },
    get quality() {
      return currentQuality;
    },
    stop() {
      stopped = true;
      events.removeAllListeners();
    },
  };
}
