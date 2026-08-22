// ============================================================================
// Server-Sent Events hub for the chat stream.
//
// The CLIs already emit structured progress (tool calls, deltas, delegation).
// Until now that detail died in a console.log on the host; this hub carries it
// to the phone so the app can render what the CLI is actually doing.
//
// SSE rather than WebSocket on purpose: Node ships no WebSocket server, and
// this server intentionally has zero dependencies. SSE is plain res.write()
// over the HTTP that already carries the Bearer auth check.
//
// Change A (live-cli-activity-visibility) extends this hub with:
//   - activity_* event types, filtered per subscriber context (no broadcast),
//   - per-subscriber bounded queue (drop-oldest backpressure),
//   - isolated replay of recent activity for the bound context,
//   - a close handler that clears ONLY subscriber-owned resources and the
//     SSE keepalive. The operation-owned ActivityRegistry 15s heartbeat
//     (lib/activity.mjs) is NEVER touched here.
// ============================================================================

import { ACTIVITY_TYPES } from "./lib/activity.mjs";

/** Every event type the app is allowed to receive. Keep in sync with ChatEvent.kt. */
export const CHAT_EVENTS = Object.freeze({
    THINKING: "thinking",              // { active: boolean }
    TEXT_DELTA: "text_delta",          // { text }
    TOOL_START: "tool_start",          // { id, name, detail? }
    TOOL_END: "tool_end",              // { id, name, ok, detail? }
    SUBAGENT_START: "subagent_start",  // { id, name, task? }
    SUBAGENT_UPDATE: "subagent_update",// { id, name, delta }
    SUBAGENT_END: "subagent_end",      // { id, name, summary? }
    MESSAGE_END: "message_end",        // { text }
    ENGINE_CHANGED: "engine_changed",  // { engine, model, provider? }
    ERROR: "error"                     // { message }
});

const HEARTBEAT_MS = 20000;
const RECENT_CAP = 64;          // per-context replay ring buffer
const QUEUE_CAP = 256;          // per-subscriber bounded queue
const ACTIVITY_FRAME_TYPES = new Set(Object.values(ACTIVITY_TYPES));

/** Open SSE responses mapped to their per-subscriber state. */
const subscribers = new Map();

/**
 * Frames that reached NOBODY because no subscriber was connected when they were
 * published. Only events whose loss the user would actually notice go here: the
 * final answer and a hard error. A `thinking` flag or a half-finished
 * `text_delta` from a turn that already ended is stale noise on reconnect.
 *
 * Buffering only the zero-subscriber case is what makes replay safe: a frame in
 * here was delivered to no one, so replaying it cannot duplicate anything.
 */
const UNDELIVERED_CAP = 20;
const REPLAY_WHEN_UNDELIVERED = new Set([
    "message_end",
    "error",
    // An async subagent reports back minutes after the turn that launched it
    // ended, which is exactly when nobody is likely to be listening. Losing
    // that frame leaves its card open forever with no answer in it.
    "subagent_start",
    "subagent_end",
]);
let undelivered = [];

/** Per-context ring buffer of recent activity frames, for isolated replay. */
const recentActivity = new Map();

let heartbeat = null;

function startHeartbeatIfNeeded() {
    if (heartbeat || subscribers.size === 0) return;
    // A comment line keeps idle connections alive through proxies and Tailscale
    // without the app having to interpret anything.
    heartbeat = setInterval(() => {
        for (const res of subscribers.keys()) {
            try { res.write(": ping\n\n"); } catch { drop(res); }
        }
    }, HEARTBEAT_MS);
}

function stopHeartbeatIfIdle() {
    if (heartbeat && subscribers.size === 0) {
        clearInterval(heartbeat);
        heartbeat = null;
    }
}

function drop(res) {
    subscribers.delete(res);
    stopHeartbeatIfIdle();
}

/**
 * Pure, deterministic bounded enqueue: returns a NEW queue with `item` appended,
 * dropping the OLDEST entries when over `cap`. Preserves newest + order.
 * Used for per-subscriber backpressure and the per-context ring buffer.
 */
export function enqueueBounded(queue, item, cap = QUEUE_CAP) {
    const next = queue.concat([item]);
    while (next.length > cap) next.shift();
    return next;
}

/**
 * Derives the opaque, server-side context key from the subscriber identity.
 * Returns null when any required field is missing (such a subscriber receives
 * no activity). NEVER derived from req.query — the server supplies it after
 * requireAuth.
 */
function contextKeyOf(context) {
    if (!context) return null;
    const { engine, project, sessionId } = context;
    if (!engine || !project || !sessionId) return null;
    return `${engine} ${project} ${sessionId}`;
}

/** Deliver a frame, queueing on genuine backpressure (res.write === false). */
function deliver(res, sub, frame) {
    let ok = true;
    try { ok = res.write(frame); } catch { ok = false; }
    if (ok === false) {
        sub.queue = enqueueBounded(sub.queue, frame, QUEUE_CAP);
        if (!sub.drainHandler) {
            sub.drainHandler = () => flush(res, sub);
            res.once("drain", sub.drainHandler);
        }
    }
}

/** Flush a backpressured subscriber queue once the socket drains. */
function flush(res, sub) {
    while (sub.queue.length > 0) {
        const frame = sub.queue[0];
        let ok = true;
        try { ok = res.write(frame); } catch { ok = false; }
        if (ok === false) return; // still backpressured; 'drain' will fire again
        sub.queue.shift();
    }
    if (sub.drainHandler) {
        res.removeListener?.("drain", sub.drainHandler);
        sub.drainHandler = null;
    }
}

/**
 * Attaches an HTTP response as an SSE subscriber. Call only after the request
 * has passed the auth guard. `context` (engine/project/sessionId) is the
 * server-derived identity — never client-supplied query params.
 */
export function subscribe(req, res, context) {
    res.writeHead(200, {
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-cache, no-transform",
        "Connection": "keep-alive",
        // Disables response buffering in any reverse proxy sitting in between.
        "X-Accel-Buffering": "no"
    });
    // Flush the header block immediately so the client knows it is connected
    // even before the first event arrives.
    res.write(": connected\n\n");

    const key = contextKeyOf(context);
    const sub = { contextKey: key, queue: [], drainHandler: null };
    subscribers.set(res, sub);
    startHeartbeatIfNeeded();
    console.log(`[chat-stream] Cliente conectado (${subscribers.size} activo(s))`);

    // Isolated replay: deliver only this context's recent activity, in order.
    for (const frame of (recentActivity.get(key) || [])) {
        deliver(res, sub, frame);
    }

    // Hand over whatever was produced while nobody was listening, then forget
    // it: this is a one-time handover, not a log to be re-read on every
    // reconnect. Cleared before delivering so a throw cannot replay it twice.
    if (undelivered.length) {
        const pending = undelivered;
        undelivered = [];
        for (const frame of pending) deliver(res, sub, frame);
    }

    req.on("close", () => {
        // Clear ONLY subscriber-owned resources: the queue and the drain
        // listener. The operation-owned ActivityRegistry 15s heartbeat lives in
        // lib/activity.mjs and ends solely via terminalize(id); a subscriber
        // disconnect must never stop it. stopHeartbeatIfIdle() here clears only
        // the SSE keepalive, which is chat-events-owned.
        subscribers.delete(res);
        sub.queue = [];
        if (sub.drainHandler) {
            res.removeListener?.("drain", sub.drainHandler);
            sub.drainHandler = null;
        }
        stopHeartbeatIfIdle();
        console.log(`[chat-stream] Cliente desconectado (${subscribers.size} activo(s))`);
    });
}

/**
 * Broadcasts one event. Non-activity events go to every subscriber (existing
 * behavior). Activity events are filtered by context: only subscribers bound to
 * the matching engine/project/sessionId receive them, and the frame is recorded
 * in that context's ring buffer for later isolated replay. An activity event
 * without a full identity reaches NOBODY (never broadcast).
 */
export function publish(type, data = {}) {
    let frame;
    try {
        frame = `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
    } catch (err) {
        console.warn(`[chat-stream] Evento '${type}' no serializable: ${err.message}`);
        return;
    }

    if (ACTIVITY_FRAME_TYPES.has(type)) {
        if (!data || !data.engine || !data.project || !data.sessionId) return;
        const key = contextKeyOf({ engine: data.engine, project: data.project, sessionId: data.sessionId });
        const buf = enqueueBounded(recentActivity.get(key) || [], frame, RECENT_CAP);
        recentActivity.set(key, buf);
        for (const [res, sub] of subscribers) {
            if (sub.contextKey === key) deliver(res, sub, frame);
        }
        return;
    }

    if (subscribers.size === 0) {
        // Previously this just returned, so an answer that finished while the
        // app was between SSE connections -- every screen navigation, every
        // reconnect backoff -- was discarded and never seen again.
        if (REPLAY_WHEN_UNDELIVERED.has(type)) {
            undelivered = enqueueBounded(undelivered, frame, UNDELIVERED_CAP);
        }
        return;
    }
    for (const [res, sub] of subscribers) deliver(res, sub, frame);
}

export function subscriberCount() {
    return subscribers.size;
}
