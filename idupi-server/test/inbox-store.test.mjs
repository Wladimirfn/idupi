// Tests for the phone inbox store (phone-inbox, T1).
//
// Run (from repo root):
//   node --test idupi-server/test/inbox-store.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
    DEFAULT_AGENT,
    DEFAULT_EFFORT,
    DEFAULT_MAX_ITEMS,
    DEFAULT_MAX_TEXT_CHARS,
    DEFAULT_MODEL,
    ITEM_ID_PATTERN,
    createInboxStore,
} from "../lib/inbox-store.mjs";

const silentLog = { log() {}, info() {}, warn() {}, error() {} };

async function makeDir(t) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-inbox-store-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    return dir;
}

function tickingClock() {
    let tick = 0;
    return () => new Date(Date.UTC(2026, 10, 24, 10, 0, tick++));
}

function message(overrides = {}) {
    return {
        app: "whatsapp",
        packageName: "com.whatsapp",
        sender: "Ana",
        conversation: "Equipo",
        text: "Hola",
        postedAt: "2026-11-24T09:59:00.000Z",
        notificationKey: "key-1",
        ...overrides,
    };
}

test("add persists the full item shape and a new store reloads it", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });

    const { item, created } = store.add(message());

    assert.equal(created, true);
    assert.match(item.id, ITEM_ID_PATTERN);
    assert.deepEqual(
        {
            app: item.app,
            packageName: item.packageName,
            sender: item.sender,
            conversation: item.conversation,
            text: item.text,
            postedAt: item.postedAt,
            notificationKey: item.notificationKey,
            status: item.status,
            importance: item.importance,
            category: item.category,
            summary: item.summary,
            suggestedReply: item.suggestedReply,
            error: item.error,
            state: item.state,
        },
        {
            app: "whatsapp",
            packageName: "com.whatsapp",
            sender: "Ana",
            conversation: "Equipo",
            text: "Hola",
            postedAt: "2026-11-24T09:59:00.000Z",
            notificationKey: "key-1",
            status: "analyzing",
            importance: null,
            category: "",
            summary: "",
            suggestedReply: "",
            error: null,
            state: "open",
        },
    );
    assert.equal(item.receivedAt, item.updatedAt);

    const onDisk = JSON.parse(await readFile(join(dir, "inbox.json"), "utf8"));
    assert.equal(onDisk.version, 1);
    assert.equal(onDisk.items.length, 1);
    assert.equal(onDisk.items[0].id, item.id);

    const reloaded = createInboxStore({ dataDir: dir, log: silentLog });
    assert.deepEqual(reloaded.get(item.id), item);
});

test("dedup returns the existing item for the same notificationKey and text", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });

    const first = store.add(message());
    const second = store.add(message());

    assert.equal(first.created, true);
    assert.equal(second.created, false);
    assert.equal(second.item.id, first.item.id);
    assert.equal(store.list().length, 1);

    // Same key with different text is a genuinely new notification.
    const edited = store.add(message({ text: "Hola (editado)" }));
    assert.equal(edited.created, true);
    assert.notEqual(edited.item.id, first.item.id);
    assert.equal(store.list().length, 2);
});

test("text is capped at 4000 characters", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });

    const { item } = store.add(message({ text: "x".repeat(5000) }));

    assert.equal(item.text.length, DEFAULT_MAX_TEXT_CHARS);
    assert.equal(item.text.length, 4000);
});

test("the store keeps only the newest maxItems rows", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({
        dataDir: dir,
        log: silentLog,
        now: tickingClock(),
        maxItems: 3,
    });

    const ids = [];
    for (let index = 0; index < 4; index += 1) {
        ids.push(store.add(message({ notificationKey: `key-${index}`, text: `text-${index}` })).item.id);
    }

    assert.deepEqual(store.list().map((item) => item.id), [ids[3], ids[2], ids[1]]);
    assert.equal(store.get(ids[0]), null);
    assert.equal(DEFAULT_MAX_ITEMS, 500);
    const onDisk = JSON.parse(await readFile(join(dir, "inbox.json"), "utf8"));
    assert.equal(onDisk.items.length, 3);
});

test("writes are atomic and leave no temp files behind", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });

    const { item } = store.add(message());
    store.update(item.id, { status: "ready", summary: "ok" });
    store.setConfig({ agent: "pi", model: "opencode-go/deepseek-v4.1-flash", effort: "low" });

    const entries = await readdir(dir);
    assert.deepEqual(entries.filter((name) => name.endsWith(".tmp")), []);
    assert.deepEqual(entries.sort(), ["inbox-config.json", "inbox.json"]);

    // Both files parse as JSON after every replace.
    JSON.parse(await readFile(join(dir, "inbox.json"), "utf8"));
    JSON.parse(await readFile(join(dir, "inbox-config.json"), "utf8"));
});

test("a corrupt items file degrades to an empty inbox instead of throwing", async (t) => {
    const dir = await makeDir(t);
    await writeFile(join(dir, "inbox.json"), "{not json", "utf8");
    await writeFile(join(dir, "inbox-config.json"), "{not json", "utf8");

    const store = createInboxStore({ dataDir: dir, log: silentLog });

    assert.deepEqual(store.list(), []);
    assert.deepEqual(store.getConfig(), {
        agent: DEFAULT_AGENT,
        model: DEFAULT_MODEL,
        effort: DEFAULT_EFFORT,
    });
});

test("config defaults are the verified opencode model and persist through setConfig", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog });

    assert.deepEqual(store.getConfig(), {
        agent: "opencode",
        model: "opencode-go/deepseek-v4.1-flash",
        effort: "max",
    });

    const saved = store.setConfig({ agent: "claude", model: "claude-haiku-4-5", effort: "low" });
    assert.deepEqual(saved, { agent: "claude", model: "claude-haiku-4-5", effort: "low" });

    const reloaded = createInboxStore({ dataDir: dir, log: silentLog });
    assert.deepEqual(reloaded.getConfig(), saved);
});

test("setConfig rejects unknown agents, efforts and empty models", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog });

    assert.throws(() => store.setConfig({ agent: "aider", model: DEFAULT_MODEL, effort: "low" }), /unknown agent/);
    assert.throws(() => store.setConfig({ agent: "opencode", model: DEFAULT_MODEL, effort: "ultra" }), /unknown effort/);
    assert.throws(() => store.setConfig({ agent: "opencode", model: "  ", effort: "low" }), /model is required/);
    assert.deepEqual(store.getConfig(), { agent: DEFAULT_AGENT, model: DEFAULT_MODEL, effort: DEFAULT_EFFORT });
});

test("update writes analysis fields, states and a fresh updatedAt; unknown ids return null", async (t) => {
    const dir = await makeDir(t);
    const clock = tickingClock();
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: clock });

    const { item } = store.add(message());
    const ready = store.update(item.id, {
        status: "ready",
        importance: "high",
        category: "Trabajo",
        summary: "Ana pregunta si llegaste.",
        suggestedReply: "Sí, ya estoy.",
        updatedAt: "2026-11-24T11:00:00.000Z",
    });

    assert.equal(ready.status, "ready");
    assert.equal(ready.importance, "high");
    assert.equal(ready.category, "Trabajo");
    assert.equal(ready.updatedAt, "2026-11-24T11:00:00.000Z");

    const handled = store.update(item.id, {
        state: "handled",
        updatedAt: "2026-11-24T11:05:00.000Z",
    });
    assert.equal(handled.state, "handled");

    assert.equal(store.update("inb-ffffffffffff", { state: "handled" }), null);
    assert.throws(() => store.update(item.id, { state: "done" }), /invalid state/);
    assert.throws(() => store.update(item.id, { status: "queued" }), /invalid status/);
});

test("related returns up to five earlier items of the same app and conversation", async (t) => {
    const dir = await makeDir(t);
    const clock = tickingClock();
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: clock });

    const sameConversation = [];
    for (let index = 0; index < 7; index += 1) {
        sameConversation.push(store.add(message({
            notificationKey: `same-${index}`,
            text: `message ${index}`,
        })).item);
    }
    const otherApp = store.add(message({
        app: "gmail",
        notificationKey: "mail-1",
        text: "un correo",
    })).item;
    const newest = store.add(message({
        notificationKey: "same-newest",
        text: "newest",
    })).item;

    const related = store.related(newest, 10);

    assert.deepEqual(
        related.map((entry) => entry.text),
        ["message 6", "message 5", "message 4", "message 3", "message 2"],
    );
    assert.equal(related.some((entry) => entry.id === newest.id), false);
    assert.equal(related.some((entry) => entry.id === otherApp.id), false);
    assert.equal(store.related(newest, 2).length, 2);
});

test("list is newest first and honours the limit", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });

    const first = store.add(message({ notificationKey: "one", text: "one" })).item;
    const second = store.add(message({ notificationKey: "two", text: "two" })).item;

    assert.deepEqual(store.list().map((item) => item.id), [second.id, first.id]);
    assert.deepEqual(store.list({ limit: 1 }).map((item) => item.id), [second.id]);
});
