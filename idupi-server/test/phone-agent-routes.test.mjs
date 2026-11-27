// Tests for the phone bubble turn routes (phone-bubble, T1).
//
// A real http.Server on an ephemeral port wires the handler exactly as
// index.mjs does (minus auth, which index.mjs owns upstream). The agent runner
// is a controllable fake: each run registers its deferred promise, so the tests
// decide when a turn finishes and can observe supersede/abort.
//
// Run (from repo root):
//   node --test idupi-server/test/phone-agent-routes.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { createPhoneAgentTurnStore } from "../lib/phone-agent-turns.mjs";
import { createPhoneAgentRoutes } from "../lib/phone-agent-routes.mjs";
import { createPhoneTurnGrants } from "../lib/phone-turn-grants.mjs";

const silentLog = { log() {}, info() {}, warn() {}, error() {} };

function makeClock(start = "2026-11-26T12:00:00.000Z") {
    let current = new Date(start);
    return {
        now: () => current,
        advance(ms) {
            current = new Date(current.getTime() + ms);
        },
    };
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(predicate, { timeoutMs = 2000 } = {}) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (predicate()) return;
        await sleep(10);
    }
    throw new Error("condition was not met in time");
}

/** Runner whose runs never finish until the test resolves them. */
function controlledRunner({ supported = true } = {}) {
    const calls = [];
    return {
        calls,
        supportsPhoneTurn: () => supported,
        runPhoneTurn: (config, input) => new Promise((resolve, reject) => {
            calls.push({ config, input, resolve, reject });
        }),
    };
}

async function startHarness(t, { runner = controlledRunner(), getConfig = () => ({ agent: "claude" }), maxJsonBytes, log, turnGrants = createPhoneTurnGrants() } = {}) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-phone-agent-routes-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const clock = makeClock();
    const store = createPhoneAgentTurnStore({ dataDir: dir, now: clock.now, log: silentLog });
    const handle = createPhoneAgentRoutes({
        store,
        runner,
        getConfig,
        log: log ?? silentLog,
        turnGrants,
        ...(maxJsonBytes ? { maxJsonBytes } : {}),
    });

    const server = http.createServer(async (req, res) => {
        try {
            const pathname = new URL(req.url, "http://localhost").pathname;
            if (await handle(req, res, pathname)) return;
            res.writeHead(418);
            res.end();
        } catch (err) {
            if (!res.headersSent) res.writeHead(500);
            res.end(String((err && err.message) || err));
        }
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const base = `http://127.0.0.1:${server.address().port}`;
    t.after(async () => {
        server.closeAllConnections?.();
        await new Promise((resolve) => server.close(resolve));
    });
    return { base, store, clock, runner, dir, turnGrants };
}

function postJson(base, path, body) {
    return fetch(base + path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: typeof body === "string" ? body : JSON.stringify(body),
    });
}

test("POST creates a queued turn and GET returns it", async (t) => {
    const { base, runner } = await startHarness(t);

    const res = await postJson(base, "/api/v1/phone/agent/turns", { text: "  revisa mi correo  " });
    const body = await res.json();

    assert.equal(res.status, 202);
    assert.match(body.turn.id, /^pat-[0-9a-f]{12}$/);
    assert.equal(body.turn.text, "revisa mi correo");
    assert.equal(body.turn.status, "queued");
    assert.equal(body.turn.agent, "claude");

    await waitFor(() => runner.calls.length === 1);
    const pollRes = await fetch(`${base}/api/v1/phone/agent/turns/${body.turn.id}?wait=0`);
    const poll = await pollRes.json();
    assert.equal(pollRes.status, 200);
    assert.equal(poll.turn.status, "running");
});

test("POST rejects invalid text, bad JSON and oversized bodies", async (t) => {
    const small = await startHarness(t, { maxJsonBytes: 64 * 1024 });
    for (const body of [{}, { text: "" }, { text: "   " }, { text: 42 }, { text: "x".repeat(2001) }]) {
        const res = await postJson(small.base, "/api/v1/phone/agent/turns", body);
        assert.equal(res.status, 400);
        assert.equal((await res.json()).code, "INVALID_TEXT");
    }

    const badJson = await postJson(small.base, "/api/v1/phone/agent/turns", "{ nope");
    assert.equal(badJson.status, 400);
    assert.equal((await badJson.json()).code, "BAD_REQUEST");

    const arrayBody = await postJson(small.base, "/api/v1/phone/agent/turns", [1, 2, 3]);
    assert.equal(arrayBody.status, 400);

    const tiny = await startHarness(t, { maxJsonBytes: 128 });
    const tooLarge = await postJson(tiny.base, "/api/v1/phone/agent/turns", { text: "x".repeat(200) });
    assert.equal(tooLarge.status, 413);
    assert.equal((await tooLarge.json()).code, "TOO_LARGE");
});

test("POST answers 503 when no agent can run phone turns", async (t) => {
    const noRunner = await startHarness(t, { runner: null });
    const first = await postJson(noRunner.base, "/api/v1/phone/agent/turns", { text: "hola" });
    assert.equal(first.status, 503);
    assert.equal((await first.json()).code, "AGENT_UNAVAILABLE");

    const unsupported = await startHarness(t, {
        runner: controlledRunner({ supported: false }),
        getConfig: () => ({ agent: "opencode" }),
    });
    const second = await postJson(unsupported.base, "/api/v1/phone/agent/turns", { text: "hola" });
    assert.equal(second.status, 503);
    assert.equal((await second.json()).code, "AGENT_UNAVAILABLE");
    assert.equal(unsupported.runner.calls.length, 0);
});

test("the long poll answers as soon as the turn changes", async (t) => {
    const { base, runner } = await startHarness(t);
    const res = await postJson(base, "/api/v1/phone/agent/turns", { text: "dime la batería" });
    const { turn } = await res.json();
    await waitFor(() => runner.calls.length === 1);

    const started = Date.now();
    const pollPromise = fetch(`${base}/api/v1/phone/agent/turns/${turn.id}?wait=5`);
    await sleep(50);
    runner.calls[0].resolve("Batería al 87%.");
    const pollRes = await pollPromise;
    const poll = await pollRes.json();

    assert.equal(pollRes.status, 200);
    assert.equal(poll.turn.status, "done");
    assert.equal(poll.turn.answer, "Batería al 87%.");
    assert.ok(Date.now() - started < 4000, "the poll woke before its timeout");
});

test("the long poll returns the current turn when the wait expires", async (t) => {
    const { base, runner } = await startHarness(t);
    const res = await postJson(base, "/api/v1/phone/agent/turns", { text: "algo lento" });
    const { turn } = await res.json();
    await waitFor(() => runner.calls.length === 1);

    const started = Date.now();
    const pollRes = await fetch(`${base}/api/v1/phone/agent/turns/${turn.id}?wait=1`);
    const poll = await pollRes.json();
    const elapsed = Date.now() - started;

    assert.equal(pollRes.status, 200);
    assert.equal(poll.turn.status, "running");
    assert.ok(elapsed >= 900, `the poll waited (elapsed ${elapsed} ms)`);
    assert.ok(elapsed < 4000, `the poll did not wait for the default (elapsed ${elapsed} ms)`);

    // wait=0 answers immediately, even for a turn in progress.
    const nowRes = await fetch(`${base}/api/v1/phone/agent/turns/${turn.id}?wait=0`);
    const now = await nowRes.json();
    assert.equal(now.turn.status, "running");
});

test("a new turn cancels the running one and aborts its signal", async (t) => {
    const { base, runner } = await startHarness(t);
    const firstRes = await postJson(base, "/api/v1/phone/agent/turns", { text: "primero" });
    const first = (await firstRes.json()).turn;
    await waitFor(() => runner.calls.length === 1);
    assert.equal(runner.calls[0].input.signal.aborted, false);

    const secondRes = await postJson(base, "/api/v1/phone/agent/turns", { text: "segundo" });
    const second = (await secondRes.json()).turn;
    assert.equal(secondRes.status, 202);

    const cancelledRes = await fetch(`${base}/api/v1/phone/agent/turns/${first.id}?wait=0`);
    const cancelled = await cancelledRes.json();
    assert.equal(cancelled.turn.status, "cancelled");
    assert.equal(runner.calls[0].input.signal.aborted, true);

    // The superseded runner rejecting must not overwrite the cancelled turn,
    // and the chain moves on to the new turn.
    runner.calls[0].reject(Object.assign(new Error("aborted"), { code: "PHONE_TURN_ABORTED" }));
    await waitFor(() => runner.calls.length === 2);

    const stillCancelled = await (await fetch(`${base}/api/v1/phone/agent/turns/${first.id}?wait=0`)).json();
    assert.equal(stillCancelled.turn.status, "cancelled");
    assert.equal(stillCancelled.turn.answer, "");

    runner.calls[1].resolve("Segundo listo.");
    const secondPoll = await (await fetch(`${base}/api/v1/phone/agent/turns/${second.id}?wait=5`)).json();
    assert.equal(secondPoll.turn.status, "done");
    assert.equal(secondPoll.turn.answer, "Segundo listo.");
    assert.ok(secondPoll.turn.steps.some((step) => step.kind === "started"));
    assert.ok(secondPoll.turn.steps.some((step) => step.kind === "answer"));
});

test("a turn cancelled while queued never reaches the runner", async (t) => {
    const { base, runner } = await startHarness(t);
    const a = (await (await postJson(base, "/api/v1/phone/agent/turns", { text: "a" })).json()).turn;
    await waitFor(() => runner.calls.length === 1);
    const b = (await (await postJson(base, "/api/v1/phone/agent/turns", { text: "b" })).json()).turn;
    const c = (await (await postJson(base, "/api/v1/phone/agent/turns", { text: "c" })).json()).turn;

    const bTurn = await (await fetch(`${base}/api/v1/phone/agent/turns/${b.id}?wait=0`)).json();
    assert.equal(bTurn.turn.status, "cancelled");

    // Let the chain drain: a aborts, b is skipped, c runs.
    runner.calls[0].reject(Object.assign(new Error("aborted"), { code: "PHONE_TURN_ABORTED" }));
    await waitFor(() => runner.calls.length === 2);
    assert.equal(runner.calls[1].input.text, "c");
    runner.calls[1].resolve("c listo");

    const cTurn = await (await fetch(`${base}/api/v1/phone/agent/turns/${c.id}?wait=5`)).json();
    assert.equal(cTurn.turn.status, "done");
    assert.equal(runner.calls.length, 2, "the queued, cancelled turn never spawned");
    assert.equal(a.id.length > 0, true);
});

test("a failed turn stores a short error and no answer", async (t) => {
    const { base, runner } = await startHarness(t);
    const res = await postJson(base, "/api/v1/phone/agent/turns", { text: "algo" });
    const { turn } = await res.json();
    await waitFor(() => runner.calls.length === 1);

    runner.calls[0].reject(new Error("El agente no terminó a tiempo."));
    const poll = await (await fetch(`${base}/api/v1/phone/agent/turns/${turn.id}?wait=5`)).json();

    assert.equal(poll.turn.status, "failed");
    assert.equal(poll.turn.answer, "");
    assert.equal(poll.turn.error, "El agente no terminó a tiempo.");
});

test("GET ?limit lists the newest turns first", async (t) => {
    const { base, store } = await startHarness(t);
    for (let index = 0; index < 3; index += 1) {
        const created = store.create({ text: `pedido ${index}`, agent: "claude" });
        store.update(created.id, { status: "done", answer: `respuesta ${index}` });
    }

    const res = await fetch(`${base}/api/v1/phone/agent/turns?limit=2`);
    const body = await res.json();
    assert.equal(res.status, 200);
    assert.equal(body.turns.length, 2);
    assert.equal(body.turns[0].text, "pedido 2");
    assert.equal(body.turns[1].text, "pedido 1");

    const invalid = await fetch(`${base}/api/v1/phone/agent/turns?limit=21`);
    assert.equal(invalid.status, 400);
    assert.equal((await invalid.json()).code, "INVALID_LIMIT");
});

test("logs carry ids and statuses only, never request text or answers", async (t) => {
    const lines = [];
    const log = {
        log() {},
        info(line) { lines.push(String(line)); },
        warn() {},
        error() {},
    };
    const { base, runner } = await startHarness(t, { log });

    const secretText = "revisa el mensaje de Juan sobre la cuenta secreta";
    const secretAnswer = "Juan dijo que la cuenta es 1234.";
    const firstRes = await postJson(base, "/api/v1/phone/agent/turns", { text: secretText });
    const first = (await firstRes.json()).turn;
    await waitFor(() => runner.calls.length === 1);

    // A second request supersedes the first, which must also log by id only.
    const secondRes = await postJson(base, "/api/v1/phone/agent/turns", { text: secretText });
    const second = (await secondRes.json()).turn;
    runner.calls[0].reject(Object.assign(new Error("aborted"), { code: "PHONE_TURN_ABORTED" }));
    await waitFor(() => runner.calls.length === 2);
    runner.calls[1].resolve(secretAnswer);
    await (await fetch(`${base}/api/v1/phone/agent/turns/${second.id}?wait=5`)).json();

    const joined = lines.join("\n");
    assert.equal(joined.includes(secretText), false);
    assert.equal(joined.includes("cuenta secreta"), false);
    assert.equal(joined.includes(secretAnswer), false);
    assert.equal(joined.includes("1234"), false);
    assert.ok(lines.some((line) => line.includes(first.id) && line.includes("created")));
    assert.ok(lines.some((line) => line.includes(first.id) && line.includes("cancelled")));
    assert.ok(lines.some((line) => line.includes(second.id) && line.includes("created")));
    assert.ok(lines.some((line) => line.includes(second.id) && line.includes("done")));
});

test("unknown ids answer 404 and wrong methods answer 405", async (t) => {
    const { base } = await startHarness(t);

    const unknown = await fetch(`${base}/api/v1/phone/agent/turns/pat-ffffffffffff?wait=0`);
    assert.equal(unknown.status, 404);
    assert.equal((await unknown.json()).code, "NOT_FOUND");

    const malformed = await fetch(`${base}/api/v1/phone/agent/turns/nope?wait=0`);
    assert.equal(malformed.status, 404);

    const invalidWait = await fetch(`${base}/api/v1/phone/agent/turns/pat-ffffffffffff?wait=31`);
    assert.equal(invalidWait.status, 400);
    assert.equal((await invalidWait.json()).code, "INVALID_WAIT");

    const put = await fetch(`${base}/api/v1/phone/agent/turns`, { method: "PUT" });
    assert.equal(put.status, 405);
    assert.equal(put.headers.get("allow"), "GET, POST");

    const postItem = await postJson(base, "/api/v1/phone/agent/turns/pat-ffffffffffff", { text: "x" });
    assert.equal(postItem.status, 405);
    assert.equal(postItem.headers.get("allow"), "GET");
});

// --- owner turn grants (phone-bubble, T13) --------------------------------

test("a running turn hands a secret grant to the runner and revokes it on done", async (t) => {
    const { base, runner, dir, turnGrants } = await startHarness(t);
    const res = await postJson(base, "/api/v1/phone/agent/turns", { text: "mándale un mensaje a Ana" });
    const { turn } = await res.json();
    await waitFor(() => runner.calls.length === 1);

    const grant = runner.calls[0].input.grant;
    assert.match(grant, /^[0-9a-f]{64}$/);
    assert.equal(turnGrants.turnFor(grant), turn.id);

    runner.calls[0].resolve("Mensaje enviado.");
    const poll = await (await fetch(`${base}/api/v1/phone/agent/turns/${turn.id}?wait=5`)).json();
    assert.equal(poll.turn.status, "done");

    // The secret dies with the turn and never reaches any persisted record.
    assert.equal(turnGrants.turnFor(grant), null);
    const stored = await readFile(join(dir, "phone-agent-turns.json"), "utf8");
    assert.equal(stored.includes(grant), false);
    assert.equal(stored.includes(turn.id), true);
});

test("a failed turn also revokes its grant", async (t) => {
    const { base, runner, turnGrants } = await startHarness(t);
    const res = await postJson(base, "/api/v1/phone/agent/turns", { text: "algo" });
    const { turn } = await res.json();
    await waitFor(() => runner.calls.length === 1);
    const grant = runner.calls[0].input.grant;

    runner.calls[0].reject(new Error("El agente no terminó a tiempo."));
    const poll = await (await fetch(`${base}/api/v1/phone/agent/turns/${turn.id}?wait=5`)).json();
    assert.equal(poll.turn.status, "failed");
    assert.equal(turnGrants.turnFor(grant), null);
});

test("a superseded turn loses its grant before its child even exits", async (t) => {
    const { base, runner, turnGrants } = await startHarness(t);
    const first = (await (await postJson(base, "/api/v1/phone/agent/turns", { text: "primero" })).json()).turn;
    await waitFor(() => runner.calls.length === 1);
    const firstGrant = runner.calls[0].input.grant;
    assert.equal(turnGrants.turnFor(firstGrant), first.id);

    const second = (await (await postJson(base, "/api/v1/phone/agent/turns", { text: "segundo" })).json()).turn;
    // The first turn is cancelled the moment the new request arrives.
    assert.equal(turnGrants.turnFor(firstGrant), null);

    runner.calls[0].reject(Object.assign(new Error("aborted"), { code: "PHONE_TURN_ABORTED" }));
    await waitFor(() => runner.calls.length === 2);
    const secondGrant = runner.calls[1].input.grant;
    assert.match(secondGrant, /^[0-9a-f]{64}$/);
    assert.notEqual(secondGrant, firstGrant);
    assert.equal(turnGrants.turnFor(secondGrant), second.id);

    runner.calls[1].resolve("Segundo listo.");
    const poll = await (await fetch(`${base}/api/v1/phone/agent/turns/${second.id}?wait=5`)).json();
    assert.equal(poll.turn.status, "done");
    assert.equal(turnGrants.turnFor(secondGrant), null);
});

test("a turn cancelled while queued never keeps a grant", async (t) => {
    const { base, runner, turnGrants } = await startHarness(t);
    await postJson(base, "/api/v1/phone/agent/turns", { text: "a" });
    await waitFor(() => runner.calls.length === 1);

    const b = (await (await postJson(base, "/api/v1/phone/agent/turns", { text: "b" })).json()).turn;
    const c = (await (await postJson(base, "/api/v1/phone/agent/turns", { text: "c" })).json()).turn;
    const bGrant = turnGrants.secretFor(b.id);
    assert.equal(bGrant, null, "the queued turn was cancelled and its grant revoked");

    // Let the chain drain: a aborts, the cancelled b is skipped, c runs.
    runner.calls[0].reject(Object.assign(new Error("aborted"), { code: "PHONE_TURN_ABORTED" }));
    await waitFor(() => runner.calls.length === 2);
    assert.equal(runner.calls[1].input.text, "c");

    runner.calls[1].resolve("c listo");
    await (await fetch(`${base}/api/v1/phone/agent/turns/${c.id}?wait=5`)).json();
    assert.equal(turnGrants.secretFor(c.id), null);
});

test("the grant never appears in turn steps or logs", async (t) => {
    const lines = [];
    const log = {
        log() {},
        info(line) { lines.push(String(line)); },
        warn(line) { lines.push(String(line)); },
        error() {},
    };
    const { base, runner } = await startHarness(t, { log });
    const res = await postJson(base, "/api/v1/phone/agent/turns", { text: "envía hola" });
    const { turn } = await res.json();
    await waitFor(() => runner.calls.length === 1);
    const grant = runner.calls[0].input.grant;

    runner.calls[0].resolve("Listo.");
    const poll = await (await fetch(`${base}/api/v1/phone/agent/turns/${turn.id}?wait=5`)).json();
    assert.equal(poll.turn.status, "done");

    const serialized = JSON.stringify(poll.turn);
    assert.equal(serialized.includes(grant), false);
    assert.equal(lines.join("\n").includes(grant), false);
});

test("a later turn receives the earlier turns as context (T4c)", async (t) => {
    const { base, runner } = await startHarness(t);

    const first = (await (await postJson(base, "/api/v1/phone/agent/turns", {
        text: "propone un plan de limpieza",
    })).json()).turn;
    await waitFor(() => runner.calls.length === 1);
    const firstInput = runner.calls[0].input;
    assert.equal(firstInput.currentTurnId, first.id);
    // The store list includes the current turn; the prompt excludes it by id.
    assert.equal(firstInput.recentTurns.some((turn) => turn.id === first.id), true);

    runner.calls[0].resolve("1. Borrar caché 2. Desinstalar GameHub");
    await (await fetch(`${base}/api/v1/phone/agent/turns/${first.id}?wait=5`)).json();

    const second = (await (await postJson(base, "/api/v1/phone/agent/turns", {
        text: "sí, todas menos MiniMax",
    })).json()).turn;
    await waitFor(() => runner.calls.length === 2);

    const input = runner.calls[1].input;
    assert.equal(input.currentTurnId, second.id);
    const earlier = input.recentTurns.find((turn) => turn.id === first.id);
    assert.equal(earlier.text, "propone un plan de limpieza");
    assert.equal(earlier.answer, "1. Borrar caché 2. Desinstalar GameHub");
    // The store list includes the current turn too; the prompt filters it by
    // id. Passing it here keeps the "recent turns" input complete.
    assert.equal(input.recentTurns.some((turn) => turn.id === second.id), true);

    runner.calls[1].resolve("Listo.");
});
