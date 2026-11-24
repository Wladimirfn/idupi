// Tests for the phone agent HTTP routes (phone-agent, T1).
//
// A real http.Server on an ephemeral port wires the handler exactly as
// index.mjs does (minus auth, which index.mjs owns upstream). The store shares
// a fake clock with the test, so 60 s expiry and history sampling do not need
// real waiting; the long-poll waits themselves run on real timers with the
// smallest allowed wait (1 s).
//
// Run (from repo root):
//   node --test idupi-server/test/phone-routes.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { createPhoneStore } from "../lib/phone-store.mjs";
import { createPhoneRoutes } from "../lib/phone-routes.mjs";

const silentLog = { log() {}, info() {}, warn() {}, error() {} };

function makeClock(start = "2026-11-24T12:00:00.000Z") {
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

async function startHarness(t) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-phone-routes-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const clock = makeClock();
    const store = createPhoneStore({ dataDir: dir, now: clock.now, log: silentLog });
    const handle = createPhoneRoutes({ store });

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
    return { base, store, clock };
}

function postJson(base, path, body) {
    return fetch(base + path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: typeof body === "string" ? body : JSON.stringify(body),
    });
}

async function postCommand(base, type, args) {
    const res = await postJson(base, "/api/v1/phone/commands", { type, args });
    const body = await res.json();
    return { res, body, command: body.command };
}

test("POST status stores the snapshot and GET reports the summary", async (t) => {
    const { base, clock } = await startHarness(t);

    const res = await postJson(base, "/api/v1/phone/status", {
        battery: { percent: 87, charging: true },
        signal: { level: 4, dbm: -78, operator: "Claro", networkType: "LTE" },
    });
    const body = await res.json();
    assert.equal(res.status, 200);
    assert.equal(body.ok, true);
    assert.equal(body.latest.battery.percent, 87);
    assert.equal(body.latest.receivedAt, "2026-11-24T12:00:00.000Z");

    const infoRes = await fetch(base + "/api/v1/phone/status");
    const info = await infoRes.json();
    assert.equal(infoRes.status, 200);
    assert.equal(info.latest.battery.percent, 87);
    assert.equal(info.history.length, 1);
    assert.equal(info.online, true);
    assert.equal(info.lastSeenAt, "2026-11-24T12:00:00.000Z");

    // The root path is an alias of /status.
    const alias = await (await fetch(base + "/api/v1/phone")).json();
    assert.deepEqual(alias, info);

    // Offline once neither a status nor a poll arrived for 2 min.
    clock.advance(3 * 60_000);
    const offline = await (await fetch(base + "/api/v1/phone/status")).json();
    assert.equal(offline.online, false);
});

test("POST status rejects arrays and malformed JSON", async (t) => {
    const { base } = await startHarness(t);

    const arrayRes = await postJson(base, "/api/v1/phone/status", [1, 2, 3]);
    assert.equal(arrayRes.status, 400);
    assert.equal((await arrayRes.json()).code, "INVALID_STATUS");

    const badRes = await postJson(base, "/api/v1/phone/status", "{ nope");
    assert.equal(badRes.status, 400);
    assert.equal((await badRes.json()).code, "BAD_REQUEST");
});

test("POST commands validates every type and returns 201", async (t) => {
    const { base } = await startHarness(t);

    const valid = [
        ["screen", undefined],
        ["status", undefined],
        ["tap", { text: "Iniciar sesión" }],
        ["tap", { id: "com.app:id/ok" }],
        ["tap", { x: 10, y: 20 }],
        ["long_press", { text: "Mensaje" }],
        ["type", { text: "hola", submit: true }],
        ["scroll", { direction: "down" }],
        ["global", { action: "back" }],
        ["open", { app: "com.whatsapp" }],
        ["wait", { ms: 0 }],
    ];
    for (const [type, args] of valid) {
        const { res, command } = await postCommand(base, type, args);
        assert.equal(res.status, 201, `${type} should be accepted`);
        assert.equal(command.type, type);
        assert.equal(command.state, "pending");
        if (args === undefined) assert.deepEqual(command.args, {});
    }

    const invalid = [
        ["nope", {}],
        ["tap", {}],
        ["tap", { text: "a", id: "b" }],
        ["tap", { x: 1 }],
        ["type", {}],
        ["scroll", { direction: "sideways" }],
        ["global", { action: "unlock" }],
        ["open", {}],
        ["wait", { ms: 60_000 }],
        ["screen", { extra: true }],
    ];
    for (const [type, args] of invalid) {
        const { res, body } = await postCommand(base, type, args);
        assert.equal(res.status, 400, `${type} ${JSON.stringify(args)} should be rejected`);
        assert.equal(body.code, "INVALID_COMMAND");
    }

    const malformed = await postJson(base, "/api/v1/phone/commands", []);
    assert.equal(malformed.status, 400);
});

test("phone long-poll delivers a queued command immediately", async (t) => {
    const { base } = await startHarness(t);

    const created = await postCommand(base, "global", { action: "home" });
    const res = await fetch(base + "/api/v1/phone/commands/next?wait=1");
    const body = await res.json();

    assert.equal(res.status, 200);
    assert.equal(body.command.id, created.command.id);
    assert.equal(body.command.state, "delivered");
    assert.equal(body.command.deliveredAt, "2026-11-24T12:00:00.000Z");

    // Already delivered: the next poll times out.
    const empty = await fetch(base + "/api/v1/phone/commands/next?wait=1");
    assert.equal(empty.status, 204);
});

test("phone long-poll answers 204 after wait seconds", async (t) => {
    const { base } = await startHarness(t);

    const started = Date.now();
    const res = await fetch(base + "/api/v1/phone/commands/next?wait=1");
    const elapsed = Date.now() - started;

    assert.equal(res.status, 204);
    assert.equal(await res.text(), "");
    assert.ok(elapsed >= 900, `expected ~1 s hold, got ${elapsed} ms`);
});

test("a new phone poll supersedes the parked one with 204", async (t) => {
    const { base } = await startHarness(t);

    const first = fetch(base + "/api/v1/phone/commands/next?wait=5");
    await sleep(100);
    const second = fetch(base + "/api/v1/phone/commands/next?wait=5");

    const firstRes = await first;
    assert.equal(firstRes.status, 204);
    await sleep(50);

    const created = await postCommand(base, "screen");
    const secondRes = await second;
    const body = await secondRes.json();
    assert.equal(secondRes.status, 200);
    assert.equal(body.command.id, created.command.id);
});

test("an aborted phone poll leaves no waiter behind", async (t) => {
    const { base } = await startHarness(t);

    const controller = new AbortController();
    const pending = fetch(base + "/api/v1/phone/commands/next?wait=5", {
        signal: controller.signal,
    }).catch((err) => err);
    await sleep(100);
    controller.abort();
    await pending;
    await sleep(50);

    const created = await postCommand(base, "screen");
    const res = await fetch(base + "/api/v1/phone/commands/next?wait=1");
    const body = await res.json();
    assert.equal(res.status, 200);
    assert.equal(body.command.id, created.command.id);
});

test("expired commands are never delivered", async (t) => {
    const { base, clock } = await startHarness(t);

    const created = await postCommand(base, "tap", { text: "OK" });
    clock.advance(61_000);

    const res = await fetch(base + "/api/v1/phone/commands/next?wait=1");
    assert.equal(res.status, 204);

    const commandRes = await fetch(
        base + `/api/v1/phone/commands/${created.command.id}?wait=0`,
    );
    const body = await commandRes.json();
    assert.equal(body.command.state, "expired");
});

test("the phone reports a result and the PC sees done", async (t) => {
    const { base } = await startHarness(t);

    const created = await postCommand(base, "screen");
    await fetch(base + "/api/v1/phone/commands/next?wait=1");

    const res = await postJson(
        base,
        `/api/v1/phone/commands/${created.command.id}/result`,
        { ok: true, result: { text: "[0] TextView \"Hola\"" } },
    );
    const body = await res.json();
    assert.equal(res.status, 200);
    assert.equal(body.command.state, "done");
    assert.deepEqual(body.command.result, { text: "[0] TextView \"Hola\"" });
    assert.equal(body.command.finishedAt, "2026-11-24T12:00:00.000Z");

    const read = await (await fetch(
        base + `/api/v1/phone/commands/${created.command.id}?wait=0`,
    )).json();
    assert.equal(read.command.state, "done");

    // A second report for the same command is a conflict, not a silent rewrite.
    const again = await postJson(
        base,
        `/api/v1/phone/commands/${created.command.id}/result`,
        { ok: false, error: "late" },
    );
    assert.equal(again.status, 409);
    assert.equal((await again.json()).code, "ALREADY_FINISHED");
});

test("a failed result stores the error, and unknown or expired ids are refused", async (t) => {
    const { base, clock } = await startHarness(t);

    const created = await postCommand(base, "tap", { text: "OK" });
    const res = await postJson(
        base,
        `/api/v1/phone/commands/${created.command.id}/result`,
        { ok: false, error: "no node matched" },
    );
    const body = await res.json();
    assert.equal(body.command.state, "failed");
    assert.equal(body.command.error, "no node matched");
    assert.equal(body.command.result, null);

    const unknown = await postJson(
        base,
        "/api/v1/phone/commands/phc-000000000000/result",
        { ok: true },
    );
    assert.equal(unknown.status, 404);

    const late = await postCommand(base, "screen");
    clock.advance(61_000);
    const expired = await postJson(
        base,
        `/api/v1/phone/commands/${late.command.id}/result`,
        { ok: true },
    );
    assert.equal(expired.status, 409);
    assert.equal((await expired.json()).code, "EXPIRED");

    const badOkCommand = await postCommand(base, "screen");
    const badOk = await postJson(
        base,
        `/api/v1/phone/commands/${badOkCommand.command.id}/result`,
        { ok: "yes" },
    );
    assert.equal(badOk.status, 400);
    assert.equal((await badOk.json()).code, "INVALID_RESULT");
});

test("the PC wait resolves as soon as the result lands", async (t) => {
    const { base } = await startHarness(t);

    const created = await postCommand(base, "open", { app: "com.whatsapp" });
    const waiting = fetch(
        base + `/api/v1/phone/commands/${created.command.id}?wait=5`,
    );
    await sleep(100);

    await fetch(base + "/api/v1/phone/commands/next?wait=1");
    await postJson(
        base,
        `/api/v1/phone/commands/${created.command.id}/result`,
        { ok: true, result: { message: "WhatsApp abierto" } },
    );

    const res = await waiting;
    const body = await res.json();
    assert.equal(res.status, 200);
    assert.equal(body.command.state, "done");
    assert.equal(body.command.result.message, "WhatsApp abierto");
});

test("the PC wait times out with the current non-terminal command", async (t) => {
    const { base } = await startHarness(t);

    const created = await postCommand(base, "wait", { ms: 100 });
    await fetch(base + "/api/v1/phone/commands/next?wait=1");

    const started = Date.now();
    const res = await fetch(base + `/api/v1/phone/commands/${created.command.id}?wait=1`);
    const elapsed = Date.now() - started;
    const body = await res.json();

    assert.equal(res.status, 200);
    assert.equal(body.command.state, "delivered");
    assert.ok(elapsed >= 900, `expected ~1 s wait, got ${elapsed} ms`);
});

test("GET commands lists recent commands newest first", async (t) => {
    const { base, clock } = await startHarness(t);

    await postCommand(base, "screen");
    clock.advance(1000);
    await postCommand(base, "global", { action: "recents" });

    const res = await fetch(base + "/api/v1/phone/commands?limit=20");
    const body = await res.json();
    assert.equal(res.status, 200);
    assert.equal(body.commands.length, 2);
    assert.equal(body.commands[0].type, "global");
    assert.equal(body.commands[1].type, "screen");

    const bad = await fetch(base + "/api/v1/phone/commands?limit=0");
    assert.equal(bad.status, 400);
    assert.equal((await bad.json()).code, "INVALID_LIMIT");
});

test("invalid wait values are 400 and unsupported methods are 405", async (t) => {
    const { base } = await startHarness(t);

    const badPoll = await fetch(base + "/api/v1/phone/commands/next?wait=0");
    assert.equal(badPoll.status, 400);
    assert.equal((await badPoll.json()).code, "INVALID_WAIT");

    const huge = await fetch(base + "/api/v1/phone/commands/next?wait=31");
    assert.equal(huge.status, 400);

    const badWait = await fetch(
        base + "/api/v1/phone/commands/phc-000000000000?wait=31",
    );
    assert.equal(badWait.status, 400);

    const put = await fetch(base + "/api/v1/phone/commands", { method: "PUT" });
    assert.equal(put.status, 405);
    assert.equal(put.headers.get("allow"), "GET, POST");

    const missing = await fetch(base + "/api/v1/phone/commands/phc-000000000000");
    assert.equal(missing.status, 404);
});
