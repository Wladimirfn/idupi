// ============================================================================
// idupi-server/test/undelivered-replay.test.mjs
//
// The final answer reached nobody. publish() ended with
//
//     if (subscribers.size === 0) return;
//
// so a `message_end` produced while the app's SSE stream was momentarily down
// -- which happens on every screen navigation and on reconnect backoff -- was
// discarded forever. Slice 1 gave activity frames a replay buffer; the one
// event that actually carries the answer had none.
//
// Only frames that reached ZERO subscribers are buffered, so replay can never
// duplicate something already delivered live.
//
// Run (from repo root):
//   node --test idupi-server/test/undelivered-replay.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";

import { CHAT_EVENTS, publish, subscribe, subscriberCount } from "../chat-events.mjs";

/** Minimal SSE res double that records what it was written. */
/**
 * Runs `body` and always closes the subscribers it was given. Without this an
 * assertion failure leaks the subscriber, the SSE keepalive interval stays
 * armed, and the test process hangs instead of reporting the failure.
 */
function withSubscribers(subs, body) {
    try { body(); } finally { for (const s of subs) s.close(); }
}

function fakeSubscriber() {
    const written = [];
    let closeHandler = null;
    const res = {
        writeHead() { return res; },
        write(chunk) { written.push(chunk); return true; },
        end() {},
        setHeader() {},
        on() {},
        removeListener() {},
        flushHeaders() {},
    };
    const req = { on(evt, cb) { if (evt === "close") closeHandler = cb; }, headers: {}, url: "/api/v1/chat/stream" };
    return { req, res, written, close: () => closeHandler && closeHandler() };
}

test("a message_end published with nobody listening is replayed to the next subscriber", () => {
    assert.equal(subscriberCount(), 0, "precondition: no subscribers");

    publish(CHAT_EVENTS.MESSAGE_END, { text: "la respuesta de Claude" });

    const sub = fakeSubscriber();
    subscribe(sub.req, sub.res, { engine: "claude", project: "p", sessionId: "s" });

    withSubscribers([sub], () => {
        const all = sub.written.join("");
        assert.match(all, /event: message_end/);
        assert.match(all, /la respuesta de Claude/);
    });
});

test("a frame delivered live is never replayed to a later subscriber", () => {
    const first = fakeSubscriber();
    subscribe(first.req, first.res, { engine: "claude", project: "p", sessionId: "s" });
    first.written.length = 0;

    withSubscribers([first], () => {
        publish(CHAT_EVENTS.MESSAGE_END, { text: "entregado en vivo" });
        assert.match(first.written.join(""), /entregado en vivo/);
    });

    const second = fakeSubscriber();
    subscribe(second.req, second.res, { engine: "claude", project: "p", sessionId: "s" });
    withSubscribers([second], () => {
        assert.doesNotMatch(second.written.join(""), /entregado en vivo/);
    });
});

test("replay happens once, not on every later reconnect", () => {
    publish(CHAT_EVENTS.MESSAGE_END, { text: "solo una vez" });

    const a = fakeSubscriber();
    subscribe(a.req, a.res, { engine: "claude", project: "p", sessionId: "s" });
    withSubscribers([a], () => assert.match(a.written.join(""), /solo una vez/));

    const b = fakeSubscriber();
    subscribe(b.req, b.res, { engine: "claude", project: "p", sessionId: "s" });
    withSubscribers([b], () => assert.doesNotMatch(b.written.join(""), /solo una vez/));
});

test("only events worth recovering are buffered", () => {
    // A thinking flag or a text delta from a finished turn is stale noise on
    // reconnect; the terminal answer and a hard error are not.
    publish(CHAT_EVENTS.THINKING, { active: true });
    publish(CHAT_EVENTS.TEXT_DELTA, { text: "fragmento a medio camino" });
    publish(CHAT_EVENTS.ERROR, { message: "algo falló" });

    const sub = fakeSubscriber();
    subscribe(sub.req, sub.res, { engine: "claude", project: "p", sessionId: "s" });
    withSubscribers([sub], () => {
        const all = sub.written.join("");
        assert.doesNotMatch(all, /fragmento a medio camino/);
        assert.doesNotMatch(all, /event: thinking/);
        assert.match(all, /algo falló/);
    });
});

test("the buffer is bounded so a long offline stretch cannot grow without limit", () => {
    for (let i = 0; i < 200; i++) publish(CHAT_EVENTS.MESSAGE_END, { text: `respuesta ${i}` });

    const sub = fakeSubscriber();
    subscribe(sub.req, sub.res, { engine: "claude", project: "p", sessionId: "s" });
    withSubscribers([sub], () => {
        const all = sub.written.join("");
        const replayed = (all.match(/event: message_end/g) || []).length;
        assert.ok(replayed > 0, "something was replayed");
        assert.ok(replayed <= 20, `buffer bounded, replayed ${replayed}`);
        // The newest answer is the one that must survive the cap.
        assert.match(all, /respuesta 199/);
    });
});

test("an async subagent's answer survives arriving while nobody is listening", () => {
    // The whole point of the async path: the child reports back minutes after
    // the turn that launched it ended, which is exactly when the app is most
    // likely to be between SSE connections.
    assert.equal(subscriberCount(), 0, "precondition: no subscribers");

    publish(CHAT_EVENTS.SUBAGENT_END, {
        id: "card-1",
        name: "gentle-ai-explore",
        summary: "la revisión terminada",
        ok: true,
    });

    const sub = fakeSubscriber();
    subscribe(sub.req, sub.res, { engine: "pi-cli", project: "p", sessionId: "s" });

    withSubscribers([sub], () => {
        const all = sub.written.join("");
        assert.match(all, /event: subagent_end/);
        assert.match(all, /la revisión terminada/);
    });
});
