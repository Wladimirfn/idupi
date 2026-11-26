// Tests for the phone bubble turn store (phone-bubble, T1).
//
// The store is the only persisted thing the bubble agent needs: one record per
// owner request, the last 20 kept, atomic writes in idupi-server/data.
//
// Run (from repo root):
//   node --test idupi-server/test/phone-agent-turns.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
    MAX_STEP_SUMMARY_CHARS,
    MAX_TURN_ANSWER_CHARS,
    MAX_TURN_ERROR_CHARS,
    MAX_TURN_STEPS,
    MAX_TURN_TEXT_CHARS,
    TURN_ID_PATTERN,
    createPhoneAgentTurnStore,
} from "../lib/phone-agent-turns.mjs";

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

async function makeStore(t, options = {}) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-phone-turns-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const clock = makeClock();
    const store = createPhoneAgentTurnStore({
        dataDir: dir,
        now: clock.now,
        log: silentLog,
        ...options,
    });
    return { dir, clock, store };
}

test("create trims the text, starts queued and persists the turn", async (t) => {
    const { dir, store } = await makeStore(t);

    const turn = store.create({ text: "  abre WhatsApp  ", agent: "claude" });

    assert.match(turn.id, TURN_ID_PATTERN);
    assert.equal(turn.text, "abre WhatsApp");
    assert.equal(turn.status, "queued");
    assert.equal(turn.answer, "");
    assert.deepEqual(turn.steps, []);
    assert.equal(turn.error, null);
    assert.equal(turn.agent, "claude");
    assert.equal(turn.createdAt, "2026-11-26T12:00:00.000Z");
    assert.equal(turn.updatedAt, turn.createdAt);

    assert.deepEqual(store.get(turn.id), turn);

    const file = JSON.parse(await readFile(join(dir, "phone-agent-turns.json"), "utf8"));
    assert.equal(file.version, 1);
    assert.equal(file.turns.length, 1);
    assert.equal(file.turns[0].id, turn.id);
    assert.equal(file.turns[0].text, "abre WhatsApp");
});

test("create rejects empty, non-string and oversized text", async (t) => {
    const { store } = await makeStore(t);

    for (const text of [undefined, null, 42, "", "   "]) {
        assert.throws(
            () => store.create({ text, agent: "claude" }),
            (error) => error.code === "INVALID_TEXT",
        );
    }
    assert.throws(
        () => store.create({ text: "x".repeat(MAX_TURN_TEXT_CHARS + 1), agent: "claude" }),
        (error) => error.code === "INVALID_TEXT",
    );

    // Exactly at the cap is valid; the stored text is trimmed first.
    const atCap = store.create({ text: "x".repeat(MAX_TURN_TEXT_CHARS), agent: "claude" });
    assert.equal(atCap.text.length, MAX_TURN_TEXT_CHARS);
    const padded = store.create({ text: `  ${"y".repeat(MAX_TURN_TEXT_CHARS - 2)}  `, agent: "claude" });
    assert.equal(padded.text.length, MAX_TURN_TEXT_CHARS - 2);
});

test("the store caps the answer, the error, the steps and each summary", async (t) => {
    const { store } = await makeStore(t);
    const turn = store.create({ text: "resume mi correo", agent: "claude" });

    const answered = store.update(turn.id, {
        status: "done",
        answer: "a".repeat(MAX_TURN_ANSWER_CHARS + 500),
    });
    assert.equal(answered.answer.length, MAX_TURN_ANSWER_CHARS);

    const failed = store.update(turn.id, {
        status: "failed",
        error: "e".repeat(MAX_TURN_ERROR_CHARS + 100),
    });
    assert.equal(failed.error.length, MAX_TURN_ERROR_CHARS);

    const longStep = store.addStep(turn.id, {
        kind: "cli",
        summary: "s".repeat(MAX_STEP_SUMMARY_CHARS + 50),
    });
    assert.equal(longStep.steps[0].summary.length, MAX_STEP_SUMMARY_CHARS);

    for (let index = 0; index < MAX_TURN_STEPS + 5; index += 1) {
        store.addStep(turn.id, { kind: "cli", summary: `paso ${index}` });
    }
    const stepped = store.addStep(turn.id, { kind: "cli", summary: "extra" });
    assert.equal(stepped.steps.length, MAX_TURN_STEPS);

    // Every step carries its own timestamp for the bubble timeline.
    for (const step of stepped.steps) {
        assert.equal(step.at, "2026-11-26T12:00:00.000Z");
    }
});

test("a new store loads the persisted turns and fails unfinished ones", async (t) => {
    const { dir, store } = await makeStore(t);
    const done = store.create({ text: "dime la batería", agent: "claude" });
    store.update(done.id, { status: "done", answer: "87%" });
    store.addStep(done.id, { kind: "cli", summary: "status" });
    const running = store.create({ text: "revisa el correo", agent: "claude" });
    store.update(running.id, { status: "running" });
    const queued = store.create({ text: "abre WhatsApp", agent: "claude" });

    const reloaded = createPhoneAgentTurnStore({ dataDir: dir, log: silentLog });

    const loadedDone = reloaded.get(done.id);
    assert.equal(loadedDone.status, "done");
    assert.equal(loadedDone.answer, "87%");
    assert.equal(loadedDone.steps.length, 1);
    assert.equal(loadedDone.steps[0].summary, "status");

    for (const id of [running.id, queued.id]) {
        const turn = reloaded.get(id);
        assert.equal(turn.status, "failed");
        assert.match(turn.error, /reinici/i);
    }
});

test("the store keeps the last 20 turns, newest first", async (t) => {
    const { dir, store } = await makeStore(t);
    const ids = [];
    for (let index = 0; index < 25; index += 1) {
        ids.push(store.create({ text: `pedido ${index}`, agent: "claude" }).id);
    }

    const listed = store.list({ limit: 20 });
    assert.equal(listed.length, 20);
    assert.equal(listed[0].id, ids[24]);
    assert.equal(listed[19].id, ids[5]);

    const file = JSON.parse(await readFile(join(dir, "phone-agent-turns.json"), "utf8"));
    assert.equal(file.turns.length, 20);
    assert.equal(file.turns[0].id, ids[5]);
    assert.equal(file.turns[19].id, ids[24]);
});

test("cancel only affects queued or running turns", async (t) => {
    const { store } = await makeStore(t);
    const queued = store.create({ text: "primero", agent: "claude" });
    assert.equal(store.cancel(queued.id).status, "cancelled");

    const running = store.create({ text: "segundo", agent: "claude" });
    store.update(running.id, { status: "running" });
    assert.equal(store.cancel(running.id).status, "cancelled");

    const done = store.create({ text: "tercero", agent: "claude" });
    store.update(done.id, { status: "done", answer: "listo" });
    assert.equal(store.cancel(done.id), null);
    assert.equal(store.get(done.id).status, "done");
    assert.equal(store.cancel("pat-ffffffffffff"), null);

    assert.equal(store.findActive(), null);
});

test("findActive returns the newest unfinished turn", async (t) => {
    const { store } = await makeStore(t);
    assert.equal(store.findActive(), null);

    const first = store.create({ text: "uno", agent: "claude" });
    store.update(first.id, { status: "done", answer: "ok" });
    const second = store.create({ text: "dos", agent: "claude" });

    assert.equal(store.findActive().id, second.id);
    store.update(second.id, { status: "cancelled" });
    assert.equal(store.findActive(), null);
});

test("update validates the status and ignores unknown turns", async (t) => {
    const { store } = await makeStore(t);
    const turn = store.create({ text: "algo", agent: "claude" });

    assert.throws(
        () => store.update(turn.id, { status: "exploded" }),
        (error) => error.code === "INVALID_STATUS",
    );
    assert.equal(store.update("pat-ffffffffffff", { status: "done" }), null);
    assert.equal(store.addStep("pat-ffffffffffff", { summary: "x" }), null);
});

test("subscribers are notified on changes and can unsubscribe", async (t) => {
    const { store } = await makeStore(t);
    let calls = 0;
    const unsubscribe = store.subscribe(() => { calls += 1; });

    const turn = store.create({ text: "algo", agent: "claude" });
    assert.equal(calls, 1);
    store.update(turn.id, { status: "running" });
    assert.equal(calls, 2);
    store.addStep(turn.id, { kind: "cli", summary: "status" });
    assert.equal(calls, 3);
    store.cancel(turn.id);
    assert.equal(calls, 4);

    unsubscribe();
    store.create({ text: "otra", agent: "claude" });
    assert.equal(calls, 4);
});
