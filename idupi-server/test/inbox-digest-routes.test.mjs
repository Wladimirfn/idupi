// Tests for the inbox digest and bulk conversation-state routes
// (inbox-management, T4).
//
// Same harness shape as inbox-routes.test.mjs: a real http.Server on an
// ephemeral port and a fake runner, so no CLI is ever spawned.
//
// Run (from repo root):
//   node --test idupi-server/test/inbox-digest-routes.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { createInboxDigestStore } from "../lib/inbox-digest.mjs";
import { createInboxRoutes } from "../lib/inbox-routes.mjs";
import { createInboxStore } from "../lib/inbox-store.mjs";

const silentLog = { log() {}, info() {}, warn() {}, error() {} };

const RESULT = {
    importance: "high",
    category: "Trabajo",
    summary: "Ana pregunta si llegaste.",
    suggestedReply: "Sí, ya estoy.",
};

const DIGEST_RESULT = {
    summary: "Acordaron la reunión del martes; Ana lleva el informe.",
    pendingForOwner: ["¿Puedes enviar el informe el lunes?"],
    suggestedReply: "Sí, lo envío el lunes.",
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

async function startHarness(t, { runner = null } = {}) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-inbox-digest-routes-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const store = createInboxStore({ dataDir: dir, log: silentLog });
    const digestStore = createInboxDigestStore({
        dataDir: dir,
        log: silentLog,
        now: () => new Date("2026-11-24T12:00:00.000Z"),
    });
    const fakeRunner = runner || {
        run: async () => ({ ...RESULT }),
        runPrompt: async () => JSON.stringify(DIGEST_RESULT),
    };
    const handle = createInboxRoutes({
        store,
        digestStore,
        runner: fakeRunner,
        now: () => new Date("2026-11-24T12:00:00.000Z"),
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
    return { base, store, digestStore, runner: fakeRunner };
}

function postJson(base, path, body) {
    return fetch(base + path, {
        method: "POST",
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

async function getDigest(base, app, conversation) {
    const query = new URLSearchParams({ app, conversation }).toString();
    const res = await fetch(`${base}/api/v1/inbox/digest?${query}`);
    return { res, body: await res.json() };
}

async function waitDigest(base, app, conversation) {
    return waitFor(async () => {
        const { res, body } = await getDigest(base, app, conversation);
        if (res.status !== 200) return null;
        return body.digest && body.digest.status !== "analyzing" ? body.digest : null;
    });
}

test("a digestOnly upload is stored ready without running the per-message agent", async (t) => {
    let runs = 0;
    const runner = {
        run: async () => {
            runs += 1;
            return { ...RESULT };
        },
        runPrompt: async () => JSON.stringify(DIGEST_RESULT),
    };
    const { base } = await startHarness(t, { runner });

    const { res, body } = await postMessage(base, { digestOnly: true });

    assert.equal(res.status, 202);
    assert.equal(body.item.status, "ready");
    assert.equal(body.item.importance, "low");
    assert.equal(body.item.category, "Silenciado");
    assert.equal(body.item.summary, "");
    assert.equal(body.item.suggestedReply, "");

    await new Promise((resolve) => setTimeout(resolve, 50));
    assert.equal(runs, 0);
});

test("digestOnly is validated as a boolean", async (t) => {
    const { base } = await startHarness(t);

    for (const digestOnly of ["yes", 1, null, {}]) {
        const res = await postJson(base, "/api/v1/inbox/messages", {
            ...message({ notificationKey: `bad-${JSON.stringify(digestOnly)}` }),
            digestOnly,
        });
        assert.equal(res.status, 400, `digestOnly=${JSON.stringify(digestOnly)}`);
        assert.equal((await res.json()).code, "INVALID_MESSAGE");
    }
});

test("POST digest summarizes the open items and GET returns the same record", async (t) => {
    const prompts = [];
    const runner = {
        run: async () => ({ ...RESULT }),
        runPrompt: async (config, prompt) => {
            prompts.push({ config, prompt });
            return JSON.stringify(DIGEST_RESULT);
        },
    };
    const { base } = await startHarness(t, { runner });

    await postMessage(base, { notificationKey: "g1", text: "Hola equipo" });
    await postMessage(base, {
        notificationKey: "g2",
        sender: "Beto",
        text: "La reunión es el martes a las 9",
    });
    await postMessage(base, {
        notificationKey: "g3",
        sender: "Ana",
        text: "¿Puedes enviar el informe el lunes?",
    });

    let res = await postJson(base, "/api/v1/inbox/digest", {
        app: "whatsapp",
        conversation: "Equipo",
    });
    assert.equal(res.status, 202);
    assert.equal((await res.json()).digest.status, "analyzing");

    const digest = await waitDigest(base, "whatsapp", "Equipo");
    assert.equal(digest.status, "ready");
    assert.equal(digest.app, "whatsapp");
    assert.equal(digest.conversation, "Equipo");
    assert.equal(digest.itemCount, 3);
    assert.equal(digest.summary, DIGEST_RESULT.summary);
    assert.deepEqual(digest.pendingForOwner, DIGEST_RESULT.pendingForOwner);
    assert.equal(digest.suggestedReply, DIGEST_RESULT.suggestedReply);
    assert.equal(digest.error, null);
    assert.equal(digest.inputHash, undefined);

    assert.equal(prompts.length, 1);
    assert.equal(prompts[0].config.agent, "opencode");
    assert.ok(prompts[0].prompt.includes('<untrusted_message index="1">'));
    assert.ok(prompts[0].prompt.includes('<untrusted_message index="3">'));
    assert.ok(prompts[0].prompt.includes("Sender: Beto"));
    assert.ok(prompts[0].prompt.includes("La reunión es el martes a las 9"));
});

test("the digest is cached until the conversation's open items change", async (t) => {
    let calls = 0;
    const runner = {
        run: async () => ({ ...RESULT }),
        runPrompt: async () => {
            calls += 1;
            return JSON.stringify(DIGEST_RESULT);
        },
    };
    const { base } = await startHarness(t, { runner });

    await postMessage(base, { notificationKey: "c1", text: "uno" });
    await postMessage(base, { notificationKey: "c2", text: "dos" });

    let res = await postJson(base, "/api/v1/inbox/digest", {
        app: "whatsapp",
        conversation: "Equipo",
    });
    assert.equal(res.status, 202);
    await waitDigest(base, "whatsapp", "Equipo");
    assert.equal(calls, 1);

    // Same open items: the cache answers 200 and never spawns an agent.
    res = await postJson(base, "/api/v1/inbox/digest", {
        app: "whatsapp",
        conversation: "Equipo",
    });
    assert.equal(res.status, 200);
    assert.equal((await res.json()).digest.summary, DIGEST_RESULT.summary);
    assert.equal(calls, 1);

    // A new message invalidates the hash.
    await postMessage(base, { notificationKey: "c3", text: "tres" });
    res = await postJson(base, "/api/v1/inbox/digest", {
        app: "whatsapp",
        conversation: "Equipo",
    });
    assert.equal(res.status, 202);
    await waitDigest(base, "whatsapp", "Equipo");
    assert.equal(calls, 2);
});

test("the digest prompt is capped and keeps the newest messages", async (t) => {
    let prompt = "";
    const runner = {
        run: async () => ({ ...RESULT }),
        runPrompt: async (_config, text) => {
            prompt = text;
            return JSON.stringify(DIGEST_RESULT);
        },
    };
    const { base } = await startHarness(t, { runner });

    const big = (label) => `${label} ${"x".repeat(3990)}`;
    await postMessage(base, { notificationKey: "cap1", text: big("OLDEST") });
    await postMessage(base, { notificationKey: "cap2", text: big("SECOND") });
    await postMessage(base, { notificationKey: "cap3", text: big("THIRD") });
    await postMessage(base, { notificationKey: "cap4", text: "NEWEST question" });

    await postJson(base, "/api/v1/inbox/digest", {
        app: "whatsapp",
        conversation: "Equipo",
    });
    await waitDigest(base, "whatsapp", "Equipo");

    assert.ok(prompt.length < 20000, `prompt was ${prompt.length} chars`);
    assert.equal(prompt.includes("OLDEST"), false);
    assert.ok(prompt.includes("SECOND"));
    assert.ok(prompt.includes("THIRD"));
    assert.ok(prompt.includes("NEWEST question"));
});

test("a failing digest agent marks the record failed with a short error", async (t) => {
    const runner = {
        run: async () => ({ ...RESULT }),
        runPrompt: async () => {
            throw new Error("provider rejected the digest\nstack line");
        },
    };
    const { base } = await startHarness(t, { runner });

    await postMessage(base, { notificationKey: "f1", text: "uno" });
    const res = await postJson(base, "/api/v1/inbox/digest", {
        app: "whatsapp",
        conversation: "Equipo",
    });
    assert.equal(res.status, 202);

    const digest = await waitDigest(base, "whatsapp", "Equipo");
    assert.equal(digest.status, "failed");
    assert.match(digest.error, /provider rejected the digest/);
    assert.equal(digest.error.includes("\n"), false);
    assert.ok(digest.error.length <= 300);
});

test("POST conversation/state closes every open item of one chat", async (t) => {
    const { base, store } = await startHarness(t);

    const a = await postMessage(base, { notificationKey: "b1", text: "uno" });
    const b = await postMessage(base, { notificationKey: "b2", text: "dos" });
    const other = await postMessage(base, {
        notificationKey: "b3",
        conversation: "Otro",
        text: "tres",
    });
    await waitReady(base, a.body.item.id);

    // A digest exists for the chat that is about to be closed.
    await postJson(base, "/api/v1/inbox/digest", {
        app: "whatsapp",
        conversation: "Equipo",
    });
    await waitDigest(base, "whatsapp", "Equipo");

    const res = await postJson(base, "/api/v1/inbox/conversation/state", {
        app: "whatsapp",
        conversation: "Equipo",
        state: "handled",
    });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.closed, 2);
    assert.deepEqual(body.items.map((item) => item.id).sort(), [
        a.body.item.id,
        b.body.item.id,
    ].sort());

    const listed = await (await fetch(base + "/api/v1/inbox?limit=200")).json();
    assert.equal(listed.items.find((item) => item.id === a.body.item.id).state, "handled");
    assert.equal(listed.items.find((item) => item.id === b.body.item.id).state, "handled");
    assert.equal(listed.items.find((item) => item.id === other.body.item.id).state, "open");
    assert.equal(store.openByConversation("whatsapp", "Equipo").length, 0);

    const { res: digestRes, body: digestBody } = await getDigest(base, "whatsapp", "Equipo");
    assert.equal(digestRes.status, 200);
    assert.equal(digestBody.digest.itemCount, 0);
    assert.equal(digestBody.digest.summary, "");
});

test("conversation state and digest validate their inputs", async (t) => {
    const { base } = await startHarness(t);

    for (const state of ["open", "replied", "auto_replied", "done"]) {
        const res = await postJson(base, "/api/v1/inbox/conversation/state", {
            app: "whatsapp",
            conversation: "Equipo",
            state,
        });
        assert.equal(res.status, 400, `state=${state}`);
        assert.equal((await res.json()).code, "INVALID_STATE");
    }

    let res = await postJson(base, "/api/v1/inbox/conversation/state", {
        app: "whatsapp",
        state: "handled",
    });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_CONVERSATION");

    res = await postJson(base, "/api/v1/inbox/digest", { app: "bad app", conversation: "x" });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_APP");

    res = await postJson(base, "/api/v1/inbox/digest", { app: "whatsapp", conversation: "  " });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_CONVERSATION");

    res = await fetch(base + "/api/v1/inbox/digest?app=whatsapp");
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_CONVERSATION");

    res = await fetch(base + "/api/v1/inbox/digest");
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_APP");
});

test("a digest request with no open items answers the empty ready record", async (t) => {
    const { base } = await startHarness(t);

    const res = await postJson(base, "/api/v1/inbox/digest", {
        app: "telegram",
        conversation: "Grupo",
    });
    assert.equal(res.status, 200);
    const { digest } = await res.json();
    assert.equal(digest.itemCount, 0);
    assert.equal(digest.status, "ready");
    assert.equal(digest.summary, "");
    assert.deepEqual(digest.pendingForOwner, []);
});
