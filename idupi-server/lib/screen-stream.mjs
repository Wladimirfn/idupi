// Ack-paced screen streaming session (brief §4.1): the receiver paces, the
// sender never pushes. At most one frame is in flight per session; every
// frame the client renders is the newest screen state, never a queued stale
// one. Frames travel to the client as chunked binary using the same wire
// framing as the helper pipe — NOT SSE, because SSE's text lines would force
// base64 (+33%) on the single hottest path in the system.

import { EventEmitter } from "node:events";

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
  let currentQuality = quality;

  async function captureOnce() {
    if (stopped || capturing) return;
    capturing = true;
    try {
      const frame = await helper.capture({
        monitor,
        width,
        height,
        quality: currentQuality,
      });
      if (stopped) return; // receiver left mid-capture: discard, never queue
      lastFrameId = frame.meta.id;
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
    async onAck({ frameId }) {
      if (stopped) throw new Error("screen stream is stopped");
      // Only the most recent frame's ack matters; replays of older ids and
      // double-acks for the current one must not queue extra captures.
      if (frameId !== lastFrameId || ackedFrameId === frameId) return;
      ackedFrameId = frameId;
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
