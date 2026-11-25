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
    CLOSED_STATES,
    DEFAULT_AGENT,
    DEFAULT_EFFORT,
    DEFAULT_MAX_ITEMS,
    DEFAULT_MAX_TEXT_CHARS,
    DEFAULT_MODEL,
    DEFAULT_PRUNE_AFTER_MS,
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
        appLabel: "WhatsApp",
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
            appLabel: item.appLabel,
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
            appLabel: "WhatsApp",
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

test("any followed package name is accepted and its label is persisted", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });

    const { item } = store.add(message({
        app: "org.telegram.messenger",
        appLabel: "  Telegram  ",
        packageName: "org.telegram.messenger",
    }));

    assert.equal(item.app, "org.telegram.messenger");
    assert.equal(item.appLabel, "Telegram");

    const reloaded = createInboxStore({ dataDir: dir, log: silentLog });
    assert.equal(reloaded.get(item.id).appLabel, "Telegram");
});

test("app ids outside the id/package alphabet are rejected", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog });

    // Android package names may contain uppercase letters.
    assert.doesNotThrow(() => store.add(message({ app: "com.Example.App", notificationKey: "upper" })));
    assert.throws(() => store.add(message({ app: "tele gram" })), /invalid app/);
    assert.throws(() => store.add(message({ app: "telegram!" })), /invalid app/);
    assert.throws(() => store.add(message({ app: "" })), /invalid app/);
    assert.throws(() => store.add(message({ app: "x".repeat(121) })), /invalid app/);
});

test("an appLabel is clipped to 60 characters on write and on load", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });

    const { item } = store.add(message({ appLabel: "L".repeat(100) }));
    assert.equal(item.appLabel.length, 60);

    await writeItemsFile(dir, [
        storedItem("inb-000000000009", "open", "2026-11-24T10:00:00.000Z", {
            appLabel: "  Etiqueta   con   espacios  ",
        }),
    ]);
    const loaded = createInboxStore({ dataDir: dir, log: silentLog })
        .get("inb-000000000009");
    assert.equal(loaded.appLabel, "Etiqueta con espacios");
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

// --- pruning closed items --------------------------------------------------

const HOUR_MS = 60 * 60 * 1000;
const NOW_MS = Date.UTC(2026, 10, 24, 12, 0, 0);

function storedItem(id, state, updatedAt, overrides = {}) {
    return {
        id,
        app: "whatsapp",
        packageName: "com.whatsapp",
        sender: "Ana",
        conversation: "Equipo",
        text: `text-${id}`,
        postedAt: updatedAt,
        receivedAt: updatedAt,
        notificationKey: `key-${id}`,
        status: "ready",
        importance: "medium",
        category: "Trabajo",
        summary: "resumen",
        suggestedReply: "",
        error: null,
        state,
        updatedAt,
        ...overrides,
    };
}

async function writeItemsFile(dir, items) {
    await writeFile(join(dir, "inbox.json"), JSON.stringify({ version: 1, items }), "utf8");
}

test("closed items older than 24 h are pruned on load; open items are never pruned", async (t) => {
    const dir = await makeDir(t);
    const stamp = (offsetMs) => new Date(NOW_MS + offsetMs).toISOString();

    await writeItemsFile(dir, [
        storedItem("inb-000000000001", "handled", stamp(-25 * HOUR_MS)),
        storedItem("inb-000000000002", "replied", stamp(-48 * HOUR_MS)),
        storedItem("inb-000000000003", "auto_replied", stamp(-30 * HOUR_MS)),
        storedItem("inb-000000000004", "dismissed", stamp(-23 * HOUR_MS)),
        storedItem("inb-000000000005", "open", stamp(-100 * HOUR_MS)),
    ]);

    const store = createInboxStore({ dataDir: dir, log: silentLog, now: () => new Date(NOW_MS) });
    const ids = store.list().map((item) => item.id).sort();

    assert.deepEqual(ids, ["inb-000000000004", "inb-000000000005"]);
    assert.deepEqual(DEFAULT_PRUNE_AFTER_MS, 24 * HOUR_MS);
    assert.deepEqual(CLOSED_STATES, ["handled", "replied", "auto_replied", "dismissed"]);

    const onDisk = JSON.parse(await readFile(join(dir, "inbox.json"), "utf8"));
    assert.deepEqual(onDisk.items.map((item) => item.id).sort(), [
        "inb-000000000004",
        "inb-000000000005",
    ]);
});

test("exactly 24 h old is kept; older than 24 h is pruned", async (t) => {
    const dir = await makeDir(t);
    const stamp = (offsetMs) => new Date(NOW_MS + offsetMs).toISOString();

    await writeItemsFile(dir, [
        storedItem("inb-000000000001", "handled", stamp(-24 * HOUR_MS)),
        storedItem("inb-000000000002", "handled", stamp(-24 * HOUR_MS - 1)),
    ]);

    const store = createInboxStore({ dataDir: dir, log: silentLog, now: () => new Date(NOW_MS) });

    assert.deepEqual(store.list().map((item) => item.id), ["inb-000000000001"]);
});

test("list prunes expired closed items and persists the smaller file", async (t) => {
    const dir = await makeDir(t);
    let nowMs = NOW_MS;
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: () => new Date(nowMs) });

    const open = store.add(message({ notificationKey: "open", text: "open" })).item;
    const handled = store.add(message({ notificationKey: "handled", text: "handled" })).item;
    store.update(handled.id, { state: "handled" });

    nowMs += 24 * HOUR_MS + 1;

    assert.deepEqual(store.list().map((item) => item.id), [open.id]);
    assert.equal(store.get(handled.id), null);

    const reloaded = createInboxStore({
        dataDir: dir,
        log: silentLog,
        now: () => new Date(nowMs),
    });
    assert.deepEqual(reloaded.list().map((item) => item.id), [open.id]);
});

test("writes prune expired closed items; open items survive any age", async (t) => {
    const dir = await makeDir(t);
    let nowMs = NOW_MS;
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: () => new Date(nowMs) });

    const open = store.add(message({ notificationKey: "open", text: "open" })).item;
    const dismissed = store.add(message({ notificationKey: "dismissed", text: "dismissed" })).item;
    store.update(dismissed.id, { state: "dismissed" });

    nowMs += 30 * HOUR_MS;
    store.add(message({ notificationKey: "later", text: "later" }));

    assert.equal(store.get(dismissed.id), null);
    assert.notEqual(store.get(open.id), null);
});

// --- inbox-management T4: digest-only items and conversation helpers --------

test("a digestOnly item is stored ready, low and categorized Silenciado", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });

    const { item } = store.add(message({ digestOnly: true }));

    assert.equal(item.status, "ready");
    assert.equal(item.importance, "low");
    assert.equal(item.category, "Silenciado");
    assert.equal(item.summary, "");
    assert.equal(item.suggestedReply, "");
    assert.equal(item.error, null);
    assert.equal(item.state, "open");

    const plain = store.add(message({ notificationKey: "plain" })).item;
    assert.equal(plain.status, "analyzing");
    assert.equal(plain.importance, null);
    assert.equal(plain.category, "");
});

test("openByConversation returns only that chat, oldest first, newest tail bounded", async (t) => {
    const dir = await makeDir(t);
    let tick = 0;
    const store = createInboxStore({
        dataDir: dir,
        log: silentLog,
        now: () => new Date(Date.UTC(2026, 10, 24, 10, 0, tick++)),
    });

    const first = store.add(message({ text: "uno", notificationKey: "k1" })).item;
    const other = store.add(message({ conversation: "Otro", text: "otro", notificationKey: "k2" })).item;
    const second = store.add(message({ text: "dos", notificationKey: "k3" })).item;
    const third = store.add(message({ text: "tres", notificationKey: "k4" })).item;
    store.update(second.id, { state: "handled" });

    const open = store.openByConversation("whatsapp", "Equipo");
    assert.deepEqual(open.map((item) => item.id), [first.id, third.id]);

    const tail = store.openByConversation("whatsapp", "Equipo", 1);
    assert.deepEqual(tail.map((item) => item.id), [third.id]);

    assert.deepEqual(store.openByConversation("telegram", "Equipo"), []);
    assert.notEqual(other.id, first.id);
});

test("closeConversation closes every open item of one chat in one call", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });

    const a = store.add(message({ text: "a", notificationKey: "a" })).item;
    const b = store.add(message({ text: "b", notificationKey: "b" })).item;
    const other = store.add(message({ conversation: "Otro", text: "c", notificationKey: "c" })).item;

    const closed = store.closeConversation("whatsapp", "Equipo", "handled", "2026-11-24T13:00:00.000Z");

    assert.deepEqual(closed.map((item) => item.id).sort(), [a.id, b.id].sort());
    assert.ok(closed.every((item) => item.state === "handled"));
    assert.equal(store.get(a.id).state, "handled");
    assert.equal(store.get(other.id).state, "open");

    assert.deepEqual(store.closeConversation("whatsapp", "Equipo", "dismissed"), []);
    assert.throws(
        () => store.closeConversation("whatsapp", "Equipo", "open"),
        /invalid state/,
    );
});

test("openConversationKeys lists only conversations with an open item", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });

    store.add(message({ text: "a", notificationKey: "a" }));
    const done = store.add(message({ conversation: "Otro", text: "b", notificationKey: "b" })).item;
    store.update(done.id, { state: "handled" });

    const keys = [...store.openConversationKeys()];
    assert.deepEqual(keys, ["whatsapp\u0000Equipo"]);
});

test("promotion defaults to false, persists and reloads", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });

    const { item } = store.add(message());
    assert.equal(item.promotion, false);

    const updated = store.update(item.id, { promotion: true, importance: "low" });
    assert.equal(updated.promotion, true);
    assert.throws(() => store.update(item.id, { promotion: "yes" }), /invalid promotion/);

    const reloaded = createInboxStore({ dataDir: dir, log: silentLog });
    assert.equal(reloaded.get(item.id).promotion, true);
});

test("items stored before the promotion flag decode as non-promotions", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });
    const { item } = store.add(message());

    // Simulate a file written by an older server: no promotion key at all.
    const file = join(dir, "inbox.json");
    const parsed = JSON.parse(await readFile(file, "utf8"));
    delete parsed.items[0].promotion;
    await writeFile(file, JSON.stringify(parsed), "utf8");

    const reloaded = createInboxStore({ dataDir: dir, log: silentLog });
    assert.equal(reloaded.get(item.id).promotion, false);
});

test("openPromotions and closePromotions work across apps", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });

    const a = store.add(message({ text: "oferta", notificationKey: "a" })).item;
    const b = store.add(message({
        app: "gmail",
        conversation: "Newsletter",
        text: "newsletter",
        notificationKey: "b",
    })).item;
    const normal = store.add(message({ text: "normal", notificationKey: "n" })).item;
    store.update(a.id, { promotion: true, state: "open" });
    store.update(b.id, { promotion: true, state: "open" });

    assert.deepEqual(store.openPromotions().map((item) => item.id), [a.id, b.id]);

    const closed = store.closePromotions("handled", "2026-11-24T13:00:00.000Z");
    assert.deepEqual(closed.map((item) => item.id).sort(), [a.id, b.id].sort());
    assert.ok(closed.every((item) => item.state === "handled"));
    assert.equal(store.get(normal.id).state, "open");
    assert.deepEqual(store.openPromotions(), []);
    assert.throws(() => store.closePromotions("open"), /invalid state/);
});

// --- Interactuar (alerts-flow A3) -----------------------------------------

function interaction(overrides = {}) {
    return {
        status: "done",
        instruction: "revisa si este error afecta mis proyectos",
        projectId: "idupi",
        projectName: "IDUPI",
        answer: "No te afecta: revisé package.json.",
        startedAt: "2026-11-24T10:00:00.000Z",
        finishedAt: "2026-11-24T10:02:00.000Z",
        error: null,
        ...overrides,
    };
}

test("a new item starts without an interaction and keeps old files compatible", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });

    const { item } = store.add(message());
    assert.equal(item.interaction, null);

    const reloaded = createInboxStore({ dataDir: dir, log: silentLog });
    assert.equal(reloaded.get(item.id).interaction, null);
});

test("the interaction round-trips through a store reload and can be replaced", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });
    const { item } = store.add(message());

    const running = store.update(item.id, {
        interaction: interaction({ status: "running", answer: "", finishedAt: null }),
    });
    assert.equal(running.interaction.status, "running");

    const done = store.update(item.id, { interaction: interaction() });
    assert.deepEqual(done.interaction, interaction());

    const reloaded = createInboxStore({ dataDir: dir, log: silentLog });
    assert.deepEqual(reloaded.get(item.id).interaction, interaction());

    const cleared = reloaded.update(item.id, { interaction: null });
    assert.equal(cleared.interaction, null);
});

test("a malformed stored interaction is dropped instead of breaking the item", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });
    const { item } = store.add(message());

    // An older or newer file may carry a shape this build cannot trust.
    const raw = JSON.parse(await readFile(join(dir, "inbox.json"), "utf8"));
    raw.items[0].interaction = { status: "teleported", instruction: 42 };
    await writeFile(join(dir, "inbox.json"), JSON.stringify(raw), "utf8");

    const reloaded = createInboxStore({ dataDir: dir, log: silentLog });
    assert.equal(reloaded.get(item.id).interaction, null);
});

test("interaction fields are clipped to the wire caps", async (t) => {
    const dir = await makeDir(t);
    const store = createInboxStore({ dataDir: dir, log: silentLog, now: tickingClock() });
    const { item } = store.add(message());

    const updated = store.update(item.id, {
        interaction: interaction({
            instruction: "i".repeat(5000),
            answer: "a".repeat(20000),
            error: "e".repeat(1000),
        }),
    });

    assert.equal(updated.interaction.instruction.length, 2000);
    assert.equal(updated.interaction.answer.length, 6000);
    assert.equal(updated.interaction.error.length, 300);
});
