// Tests for the phone agent store (phone-agent, T1).
//
// Run (from repo root):
//   node --test idupi-server/test/phone-store.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
    COMMAND_ID_PATTERN,
    COMMAND_TYPES,
    createPhoneStore,
    normalizeCommandArgs,
} from "../lib/phone-store.mjs";
import {
    APPROVAL_ID_PATTERN,
    createPhoneApprovalStore,
} from "../lib/phone-approval-store.mjs";

const silentLog = { log() {}, info() {}, warn() {}, error() {} };

async function makeDir(t) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-phone-store-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    return dir;
}

function makeClock(start = "2026-11-24T12:00:00.000Z") {
    let current = new Date(start);
    return {
        now: () => current,
        advance(ms) {
            current = new Date(current.getTime() + ms);
        },
    };
}

function snapshot(overrides = {}) {
    return {
        battery: { percent: 87, charging: true },
        signal: { level: 4, dbm: -78, operator: "Claro", networkType: "LTE" },
        ...overrides,
    };
}

test("recordStatus keeps the latest snapshot plus a server receivedAt", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneStore({ dataDir: dir, now: clock.now, log: silentLog });

    const latest = store.recordStatus(snapshot());

    assert.equal(latest.battery.percent, 87);
    assert.equal(latest.receivedAt, "2026-11-24T12:00:00.000Z");
    const info = store.getStatusInfo();
    assert.deepEqual(info.latest, latest);
    assert.equal(info.online, true);
    assert.equal(info.lastSeenAt, "2026-11-24T12:00:00.000Z");
    assert.equal(info.history.length, 1);
});

test("recordStatus persists and a new store reloads latest + history", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const first = createPhoneStore({ dataDir: dir, now: clock.now, log: silentLog });
    first.recordStatus(snapshot());

    const second = createPhoneStore({ dataDir: dir, now: clock.now, log: silentLog });
    const info = second.getStatusInfo();

    assert.equal(info.latest.battery.percent, 87);
    assert.equal(info.history.length, 1);
    // A recent snapshot still counts as a live phone after a restart.
    assert.equal(info.online, true);

    const raw = JSON.parse(await readFile(join(dir, "phone-status.json"), "utf8"));
    assert.equal(raw.version, 1);
    assert.equal(raw.latest.battery.percent, 87);
    assert.equal(raw.history.length, 1);
});

test("history samples at most once per interval and prunes by age and cap", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneStore({
        dataDir: dir,
        now: clock.now,
        log: silentLog,
        historyIntervalMs: 60_000,
        historyLimit: 2,
        historyTtlMs: 5 * 60_000,
    });

    store.recordStatus(snapshot({ battery: { percent: 1 } }));
    clock.advance(30_000);
    store.recordStatus(snapshot({ battery: { percent: 2 } }));
    // Within the sampling interval: latest changes, history does not.
    assert.equal(store.getStatusInfo().latest.battery.percent, 2);
    assert.equal(store.getStatusInfo().history.length, 1);

    clock.advance(30_000);
    store.recordStatus(snapshot({ battery: { percent: 3 } }));
    assert.equal(store.getStatusInfo().history.length, 2);

    clock.advance(60_000);
    store.recordStatus(snapshot({ battery: { percent: 4 } }));
    // Cap at 2: the oldest sample is dropped.
    assert.deepEqual(
        store.getStatusInfo().history.map((entry) => entry.battery.percent),
        [3, 4],
    );

    // Older than the 5 min TTL: everything but the newest is gone.
    clock.advance(10 * 60_000);
    store.recordStatus(snapshot({ battery: { percent: 5 } }));
    assert.deepEqual(
        store.getStatusInfo().history.map((entry) => entry.battery.percent),
        [5],
    );
});

test("recordStatus rejects non-objects and oversized payloads", async (t) => {
    const dir = await makeDir(t);
    const store = createPhoneStore({ dataDir: dir, now: makeClock().now, log: silentLog });

    assert.throws(() => store.recordStatus([]), /JSON object/);
    assert.throws(() => store.recordStatus("x"), /JSON object/);

    const tiny = createPhoneStore({
        dataDir: dir,
        now: makeClock().now,
        log: silentLog,
        maxStatusBytes: 64,
    });
    assert.throws(() => tiny.recordStatus({ blob: "x".repeat(200) }), /exceeds 64 bytes/);
});

test("online follows the 2 min window and every poll marks the phone seen", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneStore({ dataDir: dir, now: clock.now, log: silentLog });

    store.recordStatus(snapshot());
    clock.advance(3 * 60_000);
    assert.equal(store.getStatusInfo().online, false);

    store.markSeen();
    assert.equal(store.getStatusInfo().online, true);
    assert.equal(store.getStatusInfo().lastSeenAt, "2026-11-24T12:03:00.000Z");
});

test("rejects an invalid status file instead of crashing", async (t) => {
    const dir = await makeDir(t);
    await writeFile(join(dir, "phone-status.json"), "{ not json", "utf8");
    const store = createPhoneStore({ dataDir: dir, now: makeClock().now, log: silentLog });
    assert.equal(store.getStatusInfo().latest, null);
    assert.equal(store.getStatusInfo().online, false);
});

test("normalizeCommandArgs validates every command type", () => {
    assert.deepEqual(normalizeCommandArgs("screen", undefined), {});
    assert.deepEqual(normalizeCommandArgs("status", {}), {});
    assert.deepEqual(normalizeCommandArgs("tap", { text: "Iniciar sesión" }), {
        text: "Iniciar sesión",
    });
    assert.deepEqual(normalizeCommandArgs("tap", { id: "com.app:id/ok" }), {
        id: "com.app:id/ok",
    });
    assert.deepEqual(normalizeCommandArgs("long_press", { x: 10, y: 20 }), { x: 10, y: 20 });
    assert.deepEqual(normalizeCommandArgs("type", { text: "hola", submit: true }), {
        text: "hola",
        submit: true,
    });
    assert.deepEqual(normalizeCommandArgs("type", { text: "hola", submit: false }), {
        text: "hola",
    });
    assert.deepEqual(normalizeCommandArgs("scroll", { direction: "up" }), { direction: "up" });
    assert.deepEqual(normalizeCommandArgs("global", { action: "quick_settings" }), {
        action: "quick_settings",
    });
    assert.deepEqual(normalizeCommandArgs("open", { app: "com.whatsapp" }), {
        app: "com.whatsapp",
    });
    assert.deepEqual(normalizeCommandArgs("wait", { ms: 0 }), { ms: 0 });

    const invalid = [
        ["nope", {}],
        ["screen", { extra: 1 }],
        ["tap", {}],
        ["tap", { text: "a", id: "b" }],
        ["tap", { x: 1 }],
        ["tap", { x: -1, y: 2 }],
        ["tap", { text: "   " }],
        ["long_press", { y: 2 }],
        ["type", { text: "" }],
        ["type", { text: "hi", submit: "yes" }],
        ["scroll", { direction: "sideways" }],
        ["global", { action: "unlock" }],
        ["open", { app: "" }],
        ["open", { app: "x", extra: true }],
        ["wait", { ms: 10_001 }],
        ["wait", { ms: 1.5 }],
    ];
    for (const [type, args] of invalid) {
        assert.throws(
            () => normalizeCommandArgs(type, args),
            (err) => err.code === "INVALID_COMMAND",
            `${type} ${JSON.stringify(args)} should be rejected`,
        );
    }
});

test("createCommand builds a pending command that expires after 60 s", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneStore({ dataDir: dir, now: clock.now, log: silentLog });

    const command = store.createCommand({ type: "tap", args: { text: "OK" } });

    assert.match(command.id, COMMAND_ID_PATTERN);
    assert.ok(COMMAND_TYPES.includes(command.type));
    assert.deepEqual(command.args, { text: "OK" });
    assert.equal(command.state, "pending");
    assert.equal(command.createdAt, "2026-11-24T12:00:00.000Z");
    assert.equal(command.expiresAt, "2026-11-24T12:01:00.000Z");
    assert.equal(command.deliveredAt, null);
    assert.equal(command.finishedAt, null);
    assert.equal(command.result, null);
    assert.equal(command.error, null);
});

test("takeNext delivers the oldest pending command and skips nothing silently", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneStore({ dataDir: dir, now: clock.now, log: silentLog });

    assert.equal(store.takeNext(), null);
    const first = store.createCommand({ type: "screen" });
    clock.advance(1000);
    const second = store.createCommand({ type: "global", args: { action: "home" } });

    const delivered = store.takeNext();
    assert.equal(delivered.id, first.id);
    assert.equal(delivered.state, "delivered");
    assert.equal(delivered.deliveredAt, "2026-11-24T12:00:01.000Z");

    const next = store.takeNext();
    assert.equal(next.id, second.id);
    assert.equal(store.takeNext(), null);
});

test("pending commands expire after 60 s and are never delivered", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneStore({ dataDir: dir, now: clock.now, log: silentLog });

    const command = store.createCommand({ type: "tap", args: { text: "OK" } });
    clock.advance(61_000);

    assert.equal(store.takeNext(), null);
    assert.equal(store.getCommand(command.id).state, "expired");
});

test("finishCommand records done and failed, then refuses a second report", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneStore({ dataDir: dir, now: clock.now, log: silentLog });

    const doneCommand = store.createCommand({ type: "screen" });
    store.takeNext();
    clock.advance(500);
    const done = store.finishCommand(doneCommand.id, {
        ok: true,
        result: { text: "[0] TextView \"Hola\"" },
    });
    assert.equal(done.ok, true);
    assert.equal(done.command.state, "done");
    assert.equal(done.command.finishedAt, "2026-11-24T12:00:00.500Z");
    assert.deepEqual(done.command.result, { text: "[0] TextView \"Hola\"" });
    assert.equal(done.command.error, null);

    const repeated = store.finishCommand(doneCommand.id, { ok: true });
    assert.deepEqual(repeated, { ok: false, reason: "finished" });

    const failedCommand = store.createCommand({ type: "tap", args: { text: "OK" } });
    store.finishCommand(failedCommand.id, { ok: false, error: "no node found" });
    const failed = store.getCommand(failedCommand.id);
    assert.equal(failed.state, "failed");
    assert.equal(failed.error, "no node found");
    assert.equal(failed.result, null);

    assert.deepEqual(store.finishCommand("phc-000000000000", { ok: true }), {
        ok: false,
        reason: "not_found",
    });
});

test("finishCommand refuses expired commands and bad bodies", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneStore({ dataDir: dir, now: clock.now, log: silentLog });

    const command = store.createCommand({ type: "screen" });
    clock.advance(61_000);
    assert.deepEqual(store.finishCommand(command.id, { ok: true }), {
        ok: false,
        reason: "expired",
    });

    const fresh = store.createCommand({ type: "screen" });
    assert.throws(() => store.finishCommand(fresh.id, { ok: "yes" }), /boolean/);

    const tiny = createPhoneStore({
        dataDir: dir,
        now: clock.now,
        log: silentLog,
        maxResultBytes: 64,
    });
    const tinyCommand = tiny.createCommand({ type: "screen" });
    assert.throws(
        () => tiny.finishCommand(tinyCommand.id, { ok: true, result: { blob: "x".repeat(200) } }),
        /exceeds/,
    );
});

test("the queue keeps at most the last N finished commands", async (t) => {
    const dir = await makeDir(t);
    const store = createPhoneStore({
        dataDir: dir,
        now: makeClock().now,
        log: silentLog,
        maxFinished: 3,
    });

    const ids = [];
    for (let index = 0; index < 6; index += 1) {
        const command = store.createCommand({ type: "wait", args: { ms: 0 } });
        ids.push(command.id);
        store.finishCommand(command.id, { ok: true });
    }

    const listed = store.listCommands({ limit: 100 });
    assert.equal(listed.length, 3);
    assert.deepEqual(listed.map((command) => command.id), [ids[5], ids[4], ids[3]]);
});

test("listCommands is newest first and caps the limit", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneStore({ dataDir: dir, now: clock.now, log: silentLog });

    store.createCommand({ type: "screen" });
    clock.advance(1000);
    store.createCommand({ type: "global", args: { action: "back" } });

    const listed = store.listCommands({ limit: 1 });
    assert.equal(listed.length, 1);
    assert.equal(listed[0].type, "global");
    assert.equal(store.listCommands({ limit: 100 }).length, 2);
});

test("subscribers hear about enqueue, delivery and result", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneStore({ dataDir: dir, now: clock.now, log: silentLog });
    const events = [];
    const unsubscribe = store.subscribe(() => events.push(events.length + 1));

    const command = store.createCommand({ type: "screen" });
    store.takeNext();
    store.finishCommand(command.id, { ok: true });
    unsubscribe();
    store.createCommand({ type: "screen" });

    assert.equal(events.length, 3);
});

// --- approvals (phone-approvals, T1) --------------------------------------

function approvalHarness(dir, clock, overrides = {}) {
    const approvals = createPhoneApprovalStore({
        dataDir: dir,
        now: clock.now,
        log: silentLog,
    });
    const store = createPhoneStore({
        dataDir: dir,
        now: clock.now,
        log: silentLog,
        approvalStore: approvals,
        ...overrides,
    });
    return { store, approvals };
}

test("a risky command parks in awaiting_approval and is never delivered", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store, approvals } = approvalHarness(dir, clock);
    store.recordStatus({ foregroundApp: { packageName: "com.whatsapp", label: "WhatsApp" } });

    const command = store.createCommand({ type: "tap", args: { text: "Enviar" } });

    assert.equal(command.state, "awaiting_approval");
    assert.equal(command.expiresAt, null);
    assert.match(command.approvalId, APPROVAL_ID_PATTERN);
    assert.equal(command.approvalReason, "risky_text");
    assert.equal(command.approvalTarget, "Enviar");
    assert.equal(store.takeNext(), null);

    const pending = approvals.listApprovals();
    assert.equal(pending.length, 1);
    assert.equal(pending[0].id, command.approvalId);
    assert.equal(pending[0].commandId, command.id);
    assert.deepEqual(pending[0].command, { type: "tap", args: { text: "Enviar" } });
    assert.equal(pending[0].target, "Enviar");
    assert.deepEqual(pending[0].foregroundApp, {
        packageName: "com.whatsapp",
        label: "WhatsApp",
    });
    assert.equal(pending[0].requestedBy, "cli");

    // The 60 s command TTL does not apply while the owner decides.
    clock.advance(5 * 60_000);
    assert.equal(store.takeNext(), null);
    assert.equal(store.getCommand(command.id).state, "awaiting_approval");
});

test("approve requeues the command with a fresh TTL, approvalId and approvedTarget", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store, approvals } = approvalHarness(dir, clock);

    const command = store.createCommand({ type: "tap", args: { text: "Pagar" } });
    clock.advance(30_000);

    const outcome = store.decideApproval(command.approvalId, "approve");
    assert.equal(outcome.ok, true);
    assert.equal(outcome.approval.state, "approved");
    assert.equal(outcome.approval.resultCommandId, command.id);
    assert.equal(approvals.getApproval(command.approvalId).resultCommandId, command.id);

    const requeued = store.getCommand(command.id);
    assert.equal(requeued.state, "pending");
    assert.equal(requeued.expiresAt, "2026-11-24T12:01:30.000Z");
    assert.equal(requeued.approvalId, command.approvalId);
    assert.equal(requeued.args.approvalId, command.approvalId);
    assert.equal(requeued.args.approvedTarget, "Pagar");

    const delivered = store.takeNext();
    assert.equal(delivered.id, command.id);
    assert.equal(delivered.args.approvedTarget, "Pagar");
});

test("a rejected command fails with APPROVAL_REJECTED and never runs", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store, approvals } = approvalHarness(dir, clock);

    const command = store.createCommand({ type: "type", args: { text: "hola", submit: true } });
    assert.equal(command.state, "awaiting_approval");

    const outcome = store.decideApproval(command.approvalId, "reject");
    assert.equal(outcome.ok, true);
    assert.equal(outcome.approval.state, "rejected");

    const failed = store.getCommand(command.id);
    assert.equal(failed.state, "failed");
    assert.equal(failed.error, "APPROVAL_REJECTED");
    assert.equal(failed.finishedAt, "2026-11-24T12:00:00.000Z");
    assert.equal(store.takeNext(), null);
    assert.equal(approvals.getApproval(command.approvalId).state, "rejected");
});

test("an expired approval fails the command with APPROVAL_EXPIRED and never approves", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store, approvals } = approvalHarness(dir, clock);

    const command = store.createCommand({ type: "tap", args: { text: "Enviar" } });
    clock.advance(10 * 60_000);

    // Reading the command expires the approval and fails the command.
    const failed = store.getCommand(command.id);
    assert.equal(failed.state, "failed");
    assert.equal(failed.error, "APPROVAL_EXPIRED");
    assert.equal(approvals.getApproval(command.approvalId).state, "expired");
    assert.equal(store.takeNext(), null);

    assert.deepEqual(store.decideApproval(command.approvalId, "approve"), {
        ok: false,
        reason: "expired",
    });
    assert.equal(store.takeNext(), null);
});

test("subscribers hear every approval transition", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store } = approvalHarness(dir, clock);
    const events = [];
    const unsubscribe = store.subscribe(() => events.push(events.length + 1));

    const command = store.createCommand({ type: "tap", args: { text: "Enviar" } });
    store.decideApproval(command.approvalId, "approve");
    unsubscribe();

    assert.equal(events.length, 2);
});

test("APPROVAL_REQUIRED from the phone becomes a new approval for the same command", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store, approvals } = approvalHarness(dir, clock);

    const command = store.createCommand({ type: "tap", args: { x: 900, y: 2100 } });
    assert.equal(command.state, "pending");
    store.takeNext();

    const outcome = store.finishCommand(command.id, {
        ok: false,
        error: "APPROVAL_REQUIRED",
        result: { nodeText: "Enviar mensaje" },
    });
    assert.equal(outcome.ok, true);
    assert.equal(outcome.command.state, "awaiting_approval");
    assert.equal(outcome.command.error, null);

    const pending = approvals.listApprovals();
    assert.equal(pending.length, 1);
    assert.equal(pending[0].commandId, command.id);
    assert.equal(pending[0].reason, "phone_detected_risky_target");
    assert.equal(pending[0].target, "Enviar mensaje");

    store.decideApproval(pending[0].id, "approve");
    const requeued = store.getCommand(command.id);
    assert.equal(requeued.state, "pending");
    assert.equal(requeued.args.approvedTarget, "Enviar mensaje");
    assert.equal(requeued.args.approvalId, pending[0].id);
    assert.equal(store.takeNext().id, command.id);
});

test("the bypass option exists for tests only and no grant auto-approves", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store, approvals } = approvalHarness(dir, clock);

    const gated = store.createCommand({ type: "tap", args: { text: "Enviar" } });
    assert.equal(gated.state, "awaiting_approval");
    assert.equal(approvals.getApproval(gated.approvalId).state, "pending");

    const bypassed = createPhoneStore({
        dataDir: dir,
        now: clock.now,
        log: silentLog,
        approvalStore: approvals,
        bypassApprovals: true,
    });
    assert.equal(bypassed.createCommand({ type: "tap", args: { text: "Enviar" } }).state, "pending");

    // Without an approval store there is nothing to decide against, so the
    // gate is inert; production always wires one in index.mjs.
    const bare = createPhoneStore({ dataDir: dir, now: clock.now, log: silentLog });
    assert.equal(bare.createCommand({ type: "tap", args: { text: "Enviar" } }).state, "pending");
});

test("requestedBy defaults to cli and can be overridden", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store, approvals } = approvalHarness(dir, clock);

    store.createCommand({ type: "tap", args: { text: "Enviar" } });
    store.createCommand({ type: "tap", args: { text: "Enviar" }, requestedBy: "agent" });

    const listed = approvals.listApprovals();
    assert.deepEqual(listed.map((entry) => entry.requestedBy), ["agent", "cli"]);
});

// --- owner bubble turns (phone-bubble, T13) -------------------------------

const OWNER_TURN = "pat-0123456789ab";

test("an owner turn approves a message submit without a pending card", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store, approvals } = approvalHarness(dir, clock);

    const command = store.createCommand({
        type: "type",
        args: { text: "hola Ana", submit: true },
        ownerTurnId: OWNER_TURN,
    });

    // Deliverable at once: no awaiting_approval state and no pending card.
    assert.equal(command.state, "pending");
    assert.match(command.approvalId, APPROVAL_ID_PATTERN);
    assert.equal(command.args.approvalId, command.approvalId);
    assert.equal(command.args.approvedTarget, null);
    assert.equal(command.approvalReason, "type_submit");
    assert.deepEqual(approvals.listApprovals(), []);

    // The approval exists, approved, with the owner's turn as the decision.
    const approval = approvals.getApproval(command.approvalId);
    assert.equal(approval.state, "approved");
    assert.equal(approval.commandId, command.id);
    assert.equal(approval.resultCommandId, command.id);
    assert.equal(approval.decidedBy, `owner-request:${OWNER_TURN}`);
    assert.equal(approval.requestedBy, "cli");

    const delivered = store.takeNext();
    assert.equal(delivered.id, command.id);
    assert.equal(delivered.args.approvalId, command.approvalId);
    // The phone needs to know WHICH bubble turn approved the step, so it can
    // bind the grant to a turn it started itself (phone-bubble, T18).
    assert.equal(delivered.args.ownerTurnId, OWNER_TURN);
    assert.equal(delivered.args.approvalCategory, "message");
});

test("an owner turn approves a messaging tap inside a messaging app", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store, approvals } = approvalHarness(dir, clock);
    store.recordStatus({ foregroundApp: { packageName: "com.whatsapp", label: "WhatsApp" } });

    const byText = store.createCommand({
        type: "tap",
        args: { text: "Enviar" },
        ownerTurnId: OWNER_TURN,
    });
    assert.equal(byText.state, "pending");
    assert.equal(byText.args.approvedTarget, "Enviar");
    assert.equal(approvals.getApproval(byText.approvalId).state, "approved");

    const byId = store.createCommand({
        type: "tap",
        args: { id: "com.whatsapp:id/send" },
        ownerTurnId: OWNER_TURN,
    });
    assert.equal(byId.state, "pending");
    assert.equal(byId.approvalReason, "sensitive_app");
    assert.equal(byId.args.approvedTarget, "com.whatsapp:id/send");
    assert.equal(approvals.getApproval(byId.approvalId).state, "approved");

    assert.equal(approvals.listApprovals().length, 0, "nothing stays pending");
});

test("money, destructive, confirmation and bank steps still wait in an owner turn", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store, approvals } = approvalHarness(dir, clock);

    const gated = [
        ["tap", { text: "Pagar" }],
        ["tap", { text: "Comprar ahora" }],
        ["tap", { text: "Transferir" }],
        ["tap", { text: "Eliminar chat" }],
        ["tap", { text: "Borrar" }],
        ["tap", { text: "Confirmar" }],
    ];
    for (const [type, args] of gated) {
        const command = store.createCommand({ type, args, ownerTurnId: OWNER_TURN });
        assert.equal(command.state, "awaiting_approval", JSON.stringify(args));
    }

    // A bank app is money even when the target has no risky word.
    store.recordStatus({
        foregroundApp: { packageName: "cl.bancoestado.app", label: "Banco Estado" },
    });
    const banking = store.createCommand({
        type: "tap",
        args: { id: "cl.bancoestado.app:id/transfer" },
        ownerTurnId: OWNER_TURN,
    });
    assert.equal(banking.state, "awaiting_approval");
    assert.equal(banking.approvalReason, "sensitive_app");

    // Nothing was auto-approved; every one of them is still pending.
    assert.equal(approvals.listApprovals().length, gated.length + 1);
    assert.equal(store.takeNext(), null);
});

test("requestedBy from the body never authorizes an owner turn", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store, approvals } = approvalHarness(dir, clock);

    const command = store.createCommand({
        type: "type",
        args: { text: "hola", submit: true },
        requestedBy: `owner-request:${OWNER_TURN}`,
    });

    assert.equal(command.state, "awaiting_approval");
    assert.equal(approvals.getApproval(command.approvalId).state, "pending");
    assert.equal(approvals.getApproval(command.approvalId).decidedBy ?? null, null);
});

test("manual approvals and free commands never claim an owner turn", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store, approvals } = approvalHarness(dir, clock);

    const manual = store.createCommand({ type: "tap", args: { text: "Enviar" } });
    store.decideApproval(manual.approvalId, "approve");
    const manualDelivered = store.takeNext();
    assert.equal(manualDelivered.id, manual.id);
    assert.equal(manualDelivered.state, "delivered");
    assert.equal(manualDelivered.args.ownerTurnId, undefined);
    assert.equal(manualDelivered.args.approvalCategory, undefined);

    // A money step created inside an owner turn and approved by hand is a
    // manual grant: the command must not claim the turn approximated it.
    const gated = store.createCommand({
        type: "tap",
        args: { text: "Pagar" },
        ownerTurnId: OWNER_TURN,
    });
    assert.equal(gated.state, "awaiting_approval");
    store.decideApproval(gated.approvalId, "approve");
    const gatedDelivered = store.takeNext();
    assert.equal(gatedDelivered.id, gated.id);
    assert.equal(gatedDelivered.args.ownerTurnId, undefined);
    assert.equal(gatedDelivered.args.approvalCategory, undefined);

    // A free step never creates an approval, so it carries nothing either.
    const free = store.createCommand({
        type: "tap",
        args: { text: "Chats" },
        ownerTurnId: OWNER_TURN,
    });
    assert.equal(free.state, "pending");
    assert.equal(free.args.ownerTurnId, undefined);
    assert.equal(free.args.approvalCategory, undefined);
    assert.equal(approvals.listApprovals().length, 0);
});

test("a manual decision leaves decidedBy empty and still requeues", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const { store, approvals } = approvalHarness(dir, clock);

    const command = store.createCommand({ type: "tap", args: { text: "Enviar" } });
    const outcome = store.decideApproval(command.approvalId, "approve");

    assert.equal(outcome.ok, true);
    assert.equal(approvals.getApproval(command.approvalId).decidedBy ?? null, null);
    assert.equal(store.getCommand(command.id).state, "pending");
});
