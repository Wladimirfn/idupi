// Tests for the phone inbox HTTP routes (phone-inbox, T1).
//
// A real http.Server on an ephemeral port wires the handler exactly as
// index.mjs does (minus auth, which index.mjs owns upstream), and the agent
// runner is a fake so the tests never spawn a CLI.
//
// Run (from repo root):
//   node --test idupi-server/test/inbox-routes.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { createInboxStore } from "../lib/inbox-store.mjs";
import { createInboxRoutes } from "../lib/inbox-routes.mjs";

const silentLog = { log() {}, info() {}, warn() {}, error() {} };

const RESULT = {
    importance: "high",
    category: "Trabajo",
    summary: "Ana pregunta si llegaste.",
    suggestedReply: "Sí, ya estoy.",
};

function message(overrides = {}) {
    return {
        app: "whatsapp",
        appLabel: "WhatsApp",
        packageName: "com.whatsapp",
        sender: "Ana",
        conversation: "Equipo",
        text: "Hola, ¿llegaste?",
        postedAt: "2026-11-24T09:59:00.000Z",
        notificationKey: "key-1",
        ...overrides,
    };
}

async function waitFor(check, timeoutMs = 3000) {
    const started = Date.now();
    for (;;) {
        const value = await check();
        if (value) return value;
        if (Date.now() - started > timeoutMs) throw new Error("timed out waiting for condition");
        await new Promise((resolve) => setTimeout(resolve, 10));
    }
}

async function startHarness(t, { runner = null, openCodeModels = () => [] } = {}) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-inbox-routes-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const store = createInboxStore({ dataDir: dir, log: silentLog });
    const fakeRunner = runner || { run: async () => ({ ...RESULT }) };
    const handle = createInboxRoutes({
        store,
        runner: fakeRunner,
        now: () => new Date("2026-11-24T12:00:00.000Z"),
        openCodeModels,
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
    return { base, store, runner: fakeRunner };
}

function postJson(base, path, body) {
    return fetch(base + path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: typeof body === "string" ? body : JSON.stringify(body),
    });
}

function putJson(base, path, body) {
    return fetch(base + path, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
}

async function postMessage(base, overrides = {}) {
    const res = await postJson(base, "/api/v1/inbox/messages", message(overrides));
    return { res, body: await res.json() };
}

async function waitReady(base, id) {
    return waitFor(async () => {
        const res = await fetch(base + "/api/v1/inbox?limit=200");
        const { items } = await res.json();
        const item = items.find((candidate) => candidate.id === id);
        return item && item.status !== "analyzing" ? item : null;
    });
}

test("POST returns 202 and the serialized analysis later lands as ready", async (t) => {
    const { base } = await startHarness(t);

    const { res, body } = await postMessage(base);
    assert.equal(res.status, 202);
    assert.equal(body.item.status, "analyzing");
    assert.equal(body.item.state, "open");

    const item = await waitReady(base, body.item.id);
    assert.equal(item.status, "ready");
    assert.equal(item.importance, "high");
    assert.equal(item.category, "Trabajo");
    assert.equal(item.summary, RESULT.summary);
    assert.equal(item.suggestedReply, RESULT.suggestedReply);
    assert.equal(item.error, null);
});

test("a failing analysis marks the item failed with a short single-line error", async (t) => {
    const runner = {
        run: async () => {
            throw new Error("provider rejected the request\nstack line that should not leak");
        },
    };
    const { base } = await startHarness(t, { runner });

    const { body } = await postMessage(base);
    const item = await waitReady(base, body.item.id);

    assert.equal(item.status, "failed");
    assert.match(item.error, /provider rejected the request/);
    assert.equal(item.error.includes("\n"), false);
    assert.ok(item.error.length <= 300);
});

test("dedup returns 200 with the existing item and never analyzes it twice", async (t) => {
    let calls = 0;
    const runner = {
        run: async () => {
            calls += 1;
            return { ...RESULT };
        },
    };
    const { base } = await startHarness(t, { runner });

    const first = await postMessage(base);
    await waitReady(base, first.body.item.id);

    const second = await postMessage(base);

    assert.equal(second.res.status, 200);
    assert.equal(second.body.item.id, first.body.item.id);
    assert.equal(calls, 1);
});

test("the analysis queue never runs two agent processes at once", async (t) => {
    const releases = [];
    let active = 0;
    let maxActive = 0;
    const runner = {
        run: async () => {
            active += 1;
            maxActive = Math.max(maxActive, active);
            await new Promise((resolve) => releases.push(resolve));
            active -= 1;
            return { ...RESULT };
        },
    };
    const { base } = await startHarness(t, { runner });

    const first = await postMessage(base);
    const second = await postMessage(base, { notificationKey: "key-2" });

    await waitFor(() => releases.length === 1);
    assert.equal(maxActive, 1);
    releases[0]();
    await waitFor(() => releases.length === 2);
    assert.equal(maxActive, 1);
    releases[1]();

    await waitReady(base, first.body.item.id);
    await waitReady(base, second.body.item.id);
});

test("a followed package name is accepted and its label round-trips through GET", async (t) => {
    const { base } = await startHarness(t);

    const { res, body } = await postMessage(base, {
        app: "org.telegram.messenger",
        appLabel: "  Telegram  ",
        packageName: "org.telegram.messenger",
        notificationKey: "tg-1",
    });

    assert.equal(res.status, 202);
    assert.equal(body.item.app, "org.telegram.messenger");
    assert.equal(body.item.appLabel, "Telegram");

    const listed = await (await fetch(base + "/api/v1/inbox")).json();
    assert.equal(listed.items[0].app, "org.telegram.messenger");
    assert.equal(listed.items[0].appLabel, "Telegram");
});

test("missing or malformed app ids are rejected; appLabel is optional and clipped", async (t) => {
    const { base } = await startHarness(t);

    for (const app of ["", "tele gram", "telegram!", "x".repeat(121)]) {
        const { res, body } = await postMessage(base, {
            app,
            notificationKey: `bad-${app.length}`,
        });
        assert.equal(res.status, 400, `app=${JSON.stringify(app)} must be rejected`);
        assert.equal(body.code, "INVALID_APP");
    }

    const missing = await postJson(base, "/api/v1/inbox/messages", {
        text: "hola",
        notificationKey: "no-app",
    });
    assert.equal(missing.status, 400);
    assert.equal((await missing.json()).code, "INVALID_APP");

    const noLabel = await postMessage(base, { appLabel: "", notificationKey: "no-label" });
    assert.equal(noLabel.res.status, 202);
    assert.equal(noLabel.body.item.appLabel, "");

    const longLabel = await postMessage(base, {
        appLabel: "L".repeat(90),
        notificationKey: "long-label",
    });
    assert.equal(longLabel.res.status, 202);
    assert.equal(longLabel.body.item.appLabel.length, 60);

    const badLabel = await postJson(base, "/api/v1/inbox/messages", {
        ...message({ notificationKey: "bad-label" }),
        appLabel: 42,
    });
    assert.equal(badLabel.status, 400);
    assert.equal((await badLabel.json()).code, "INVALID_MESSAGE");
});

test("state changes persist, validate and 404 unknown ids", async (t) => {
    const { base } = await startHarness(t);
    const { body } = await postMessage(base);
    await waitReady(base, body.item.id);

    let res = await postJson(base, `/api/v1/inbox/${body.item.id}/state`, { state: "handled" });
    assert.equal(res.status, 200);
    assert.equal((await res.json()).item.state, "handled");

    res = await postJson(base, `/api/v1/inbox/${body.item.id}/state`, { state: "done" });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_STATE");

    res = await postJson(base, "/api/v1/inbox/inb-ffffffffffff/state", { state: "handled" });
    assert.equal(res.status, 404);
    assert.equal((await res.json()).code, "NOT_FOUND");

    res = await postJson(base, "/api/v1/inbox/not-an-id/state", { state: "handled" });
    assert.equal(res.status, 404);
});

test("retry re-queues an item and returns it as analyzing", async (t) => {
    let calls = 0;
    const runner = {
        run: async () => {
            calls += 1;
            return { ...RESULT };
        },
    };
    const { base } = await startHarness(t, { runner });

    const { body } = await postMessage(base);
    await waitReady(base, body.item.id);
    assert.equal(calls, 1);

    const res = await postJson(base, `/api/v1/inbox/${body.item.id}/retry`, {});
    assert.equal(res.status, 202);
    assert.equal((await res.json()).item.status, "analyzing");

    await waitReady(base, body.item.id);
    assert.equal(calls, 2);

    const missing = await postJson(base, "/api/v1/inbox/inb-ffffffffffff/retry", {});
    assert.equal(missing.status, 404);
});

test("config GET returns the defaults plus the catalogs and PUT validates and persists", async (t) => {
    const { base } = await startHarness(t);

    let res = await fetch(base + "/api/v1/inbox/config");
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), {
        agent: "opencode",
        model: "opencode-go/deepseek-v4.1-flash",
        effort: "max",
        agents: ["opencode", "claude", "codex", "pi"],
        efforts: ["low", "medium", "high", "max"],
    });

    res = await putJson(base, "/api/v1/inbox/config", {
        agent: "claude",
        model: "claude-haiku-4-5",
        effort: "low",
    });
    assert.equal(res.status, 200);
    assert.equal((await res.json()).agent, "claude");

    res = await fetch(base + "/api/v1/inbox/config");
    assert.equal((await res.json()).model, "claude-haiku-4-5");

    res = await putJson(base, "/api/v1/inbox/config", { agent: "aider", model: "x", effort: "low" });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_CONFIG");

    res = await putJson(base, "/api/v1/inbox/config", { agent: "opencode", model: "x", effort: "ultra" });
    assert.equal(res.status, 400);

    res = await putJson(base, "/api/v1/inbox/config", { agent: "opencode", model: " ", effort: "low" });
    assert.equal(res.status, 400);
});

test("models reuses the injected opencode catalog and returns [] for the others", async (t) => {
    const { base } = await startHarness(t, {
        openCodeModels: () => ["opencode-go/deepseek-v4.1-flash", 42, ""],
    });

    let res = await fetch(base + "/api/v1/inbox/models?agent=opencode");
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { models: ["opencode-go/deepseek-v4.1-flash"] });

    res = await fetch(base + "/api/v1/inbox/models?agent=claude");
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { models: [] });

    res = await fetch(base + "/api/v1/inbox/models?agent=aider");
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_AGENT");
});

test("models degrades to an empty list when the catalog call fails", async (t) => {
    const { base } = await startHarness(t, {
        openCodeModels: () => {
            throw new Error("cli missing");
        },
    });

    const res = await fetch(base + "/api/v1/inbox/models?agent=opencode");
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { models: [] });
});

test("GET inbox validates the limit and returns newest first", async (t) => {
    const { base } = await startHarness(t);
    await postMessage(base, { notificationKey: "one", text: "one" });
    await postMessage(base, { notificationKey: "two", text: "two" });
    await postMessage(base, { notificationKey: "three", text: "three" });

    let res = await fetch(base + "/api/v1/inbox");
    assert.equal(res.status, 200);
    assert.equal((await res.json()).items.length, 3);

    res = await fetch(base + "/api/v1/inbox?limit=2");
    const limited = await res.json();
    assert.equal(limited.items.length, 2);
    assert.equal(limited.items[0].text, "three");

    for (const bad of ["0", "201", "abc", "1.5"]) {
        res = await fetch(base + `/api/v1/inbox?limit=${bad}`);
        assert.equal(res.status, 400, `limit=${bad} must be rejected`);
        assert.equal((await res.json()).code, "INVALID_LIMIT");
    }
});

test("wrong methods answer 405 with an Allow header", async (t) => {
    const { base } = await startHarness(t);

    let res = await fetch(base + "/api/v1/inbox/messages");
    assert.equal(res.status, 405);
    assert.equal(res.headers.get("allow"), "POST");

    res = await putJson(base, "/api/v1/inbox/messages", {});
    assert.equal(res.status, 405);

    res = await fetch(base + "/api/v1/inbox", { method: "DELETE" });
    assert.equal(res.status, 405);
    assert.equal(res.headers.get("allow"), "GET, HEAD");
});
