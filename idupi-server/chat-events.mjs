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
// ============================================================================

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

/** Open SSE responses. */
const subscribers = new Set();

let heartbeat = null;

function startHeartbeatIfNeeded() {
    if (heartbeat || subscribers.size === 0) return;
    // A comment line keeps idle connections alive through proxies and Tailscale
    // without the app having to interpret anything.
    heartbeat = setInterval(() => {
        for (const res of subscribers) {
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
    try { res.end(); } catch { /* already closed */ }
    stopHeartbeatIfIdle();
}

/**
 * Attaches an HTTP response as an SSE subscriber. Call only after the request
 * has passed the auth guard.
 */
export function subscribe(req, res) {
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

    subscribers.add(res);
    startHeartbeatIfNeeded();
    console.log(`[chat-stream] Cliente conectado (${subscribers.size} activo(s))`);

    req.on("close", () => {
        subscribers.delete(res);
        stopHeartbeatIfIdle();
        console.log(`[chat-stream] Cliente desconectado (${subscribers.size} activo(s))`);
    });
}

/**
 * Broadcasts one event to every connected app. Never throws: a chat stream
 * failing must not take down the request that produced the event.
 */
export function publish(type, data = {}) {
    if (subscribers.size === 0) return;

    let frame;
    try {
        frame = `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
    } catch (err) {
        console.warn(`[chat-stream] Evento '${type}' no serializable: ${err.message}`);
        return;
    }

    for (const res of subscribers) {
        try { res.write(frame); } catch { drop(res); }
    }
}

export function subscriberCount() {
    return subscribers.size;
}
