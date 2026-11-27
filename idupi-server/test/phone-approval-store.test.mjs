// Tests for the phone approval store (phone-approvals, T1).
//
// A pending approval is the only thing standing between the PC and an
// outbound phone action, so the state machine is covered here in isolation:
// create, list, decide, expire, reload from disk and the retention cap.
// Expiry must NEVER approve.
//
// Run (from repo root):
//   node --test idupi-server/test/phone-approval-store.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
    APPROVAL_ID_PATTERN,
    APPROVAL_STATES,
    createPhoneApprovalStore,
} from "../lib/phone-approval-store.mjs";

const silentLog = { log() {}, info() {}, warn() {}, error() {} };

async function makeDir(t) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-approvals-"));
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

function approvalInput(overrides = {}) {
    return {
        command: { type: "tap", args: { text: "Enviar" } },
        reason: "risky_text",
        target: "Enviar",
        foregroundApp: { packageName: "com.whatsapp", label: "WhatsApp" },
        requestedBy: "cli",
        commandId: "phc-0123456789ab",
        ...overrides,
    };
}

test("createApproval stores a pending approval with a 10 min TTL", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneApprovalStore({ dataDir: dir, now: clock.now, log: silentLog });

    const approval = store.createApproval(approvalInput());

    assert.match(approval.id, APPROVAL_ID_PATTERN);
    assert.equal(approval.state, "pending");
    assert.deepEqual(approval.command, { type: "tap", args: { text: "Enviar" } });
    assert.equal(approval.reason, "risky_text");
    assert.equal(approval.target, "Enviar");
    assert.deepEqual(approval.foregroundApp, { packageName: "com.whatsapp", label: "WhatsApp" });
    assert.equal(approval.requestedBy, "cli");
    assert.equal(approval.createdAt, "2026-11-24T12:00:00.000Z");
    assert.equal(approval.expiresAt, "2026-11-24T12:10:00.000Z");
    assert.equal(approval.decidedAt, null);
    assert.equal(approval.commandId, "phc-0123456789ab");
    assert.equal(approval.resultCommandId, null);
    assert.ok(APPROVAL_STATES.includes(approval.state));

    const raw = JSON.parse(await readFile(join(dir, "phone-approvals.json"), "utf8"));
    assert.equal(raw.version, 1);
    assert.equal(raw.approvals.length, 1);
    assert.equal(raw.approvals[0].id, approval.id);
});

test("riskKind is optional provenance the phone uses to word a destructive step", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneApprovalStore({ dataDir: dir, now: clock.now, log: silentLog });

    const plain = store.createApproval(approvalInput());
    assert.equal(plain.riskKind, null);

    const wipe = store.createApproval(approvalInput({
        command: { type: "tap", args: { text: "Borrar datos" } },
        target: "Borrar datos",
        riskKind: "data_wipe",
        commandId: "phc-000000000002",
    }));
    assert.equal(wipe.riskKind, "data_wipe");

    // Additive and durable: an older server without the field still decodes.
    const reloaded = createPhoneApprovalStore({ dataDir: dir, now: clock.now, log: silentLog });
    assert.equal(reloaded.getApproval(wipe.id).riskKind, "data_wipe");
    assert.equal(reloaded.getApproval(plain.id).riskKind, null);
});

test("listApprovals defaults to pending, newest first, with state and limit filters", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneApprovalStore({ dataDir: dir, now: clock.now, log: silentLog });

    const first = store.createApproval(approvalInput({ commandId: "phc-000000000001" }));
    clock.advance(1000);
    const second = store.createApproval(approvalInput({ commandId: "phc-000000000002" }));
    store.decideApproval(first.id, "reject");

    assert.deepEqual(
        store.listApprovals().map((entry) => entry.id),
        [second.id],
    );
    assert.deepEqual(
        store.listApprovals({ state: "all" }).map((entry) => entry.id),
        [second.id, first.id],
    );
    assert.deepEqual(
        store.listApprovals({ state: "rejected" }).map((entry) => entry.id),
        [first.id],
    );
    assert.equal(store.listApprovals({ state: "pending", limit: 1 }).length, 1);
    assert.throws(
        () => store.listApprovals({ state: "sleeping" }),
        (err) => err.code === "INVALID_STATUS",
    );
});

test("decideApproval approves or rejects once and refuses a second decision", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneApprovalStore({ dataDir: dir, now: clock.now, log: silentLog });

    const approved = store.createApproval(approvalInput({ commandId: "phc-000000000001" }));
    clock.advance(500);
    const approvalOutcome = store.decideApproval(approved.id, "approve");
    assert.equal(approvalOutcome.ok, true);
    assert.equal(approvalOutcome.approval.state, "approved");
    assert.equal(approvalOutcome.approval.decidedAt, "2026-11-24T12:00:00.500Z");

    const again = store.decideApproval(approved.id, "reject");
    assert.deepEqual(again, { ok: false, reason: "already_decided" });

    const rejected = store.createApproval(approvalInput({ commandId: "phc-000000000002" }));
    const rejectedOutcome = store.decideApproval(rejected.id, "reject");
    assert.equal(rejectedOutcome.ok, true);
    assert.equal(rejectedOutcome.approval.state, "rejected");

    assert.deepEqual(store.decideApproval("apr-000000000000", "approve"), {
        ok: false,
        reason: "not_found",
    });
    assert.throws(
        () => store.decideApproval(approved.id, "maybe"),
        (err) => err.code === "INVALID_DECISION",
    );
});

test("expiry never approves: an expired approval stays expired and is refused", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneApprovalStore({ dataDir: dir, now: clock.now, log: silentLog });

    const approval = store.createApproval(approvalInput());
    clock.advance(10 * 60_000);

    const listed = store.listApprovals({ state: "all" });
    assert.equal(listed[0].state, "expired");
    assert.equal(listed[0].decidedAt, "2026-11-24T12:10:00.000Z");

    const outcome = store.decideApproval(approval.id, "approve");
    assert.deepEqual(outcome, { ok: false, reason: "expired" });
    assert.equal(store.getApproval(approval.id).state, "expired");
});

test("approvals reload from disk and survive a new store instance", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const first = createPhoneApprovalStore({ dataDir: dir, now: clock.now, log: silentLog });
    const created = first.createApproval(approvalInput());

    const second = createPhoneApprovalStore({ dataDir: dir, now: clock.now, log: silentLog });
    const pending = second.listApprovals();
    assert.equal(pending.length, 1);
    assert.equal(pending[0].id, created.id);

    second.decideApproval(created.id, "approve");
    const third = createPhoneApprovalStore({ dataDir: dir, now: clock.now, log: silentLog });
    assert.equal(third.getApproval(created.id).state, "approved");
});

test("attachResultCommand records the command that will actually run", async (t) => {
    const dir = await makeDir(t);
    const store = createPhoneApprovalStore({ dataDir: dir, now: makeClock().now, log: silentLog });

    const approval = store.createApproval(approvalInput());
    store.attachResultCommand(approval.id, "phc-ffffffffffff");

    assert.equal(store.getApproval(approval.id).resultCommandId, "phc-ffffffffffff");
});

test("retention keeps pending approvals and prunes old decided ones", async (t) => {
    const dir = await makeDir(t);
    const clock = makeClock();
    const store = createPhoneApprovalStore({
        dataDir: dir,
        now: clock.now,
        log: silentLog,
        maxApprovals: 2,
        decidedTtlMs: 24 * 60 * 60 * 1000,
    });

    const first = store.createApproval(approvalInput({ commandId: "phc-000000000001" }));
    const second = store.createApproval(approvalInput({ commandId: "phc-000000000002" }));
    const third = store.createApproval(approvalInput({ commandId: "phc-000000000003" }));
    assert.deepEqual(
        store.listApprovals({ state: "all" }).map((entry) => entry.id),
        [third.id, second.id],
    );

    store.decideApproval(second.id, "reject");
    store.decideApproval(third.id, "reject");
    clock.advance(25 * 60 * 60 * 1000);
    const fresh = store.createApproval(approvalInput({ commandId: "phc-000000000004" }));

    const all = store.listApprovals({ state: "all" });
    assert.deepEqual(all.map((entry) => entry.id), [fresh.id]);
    assert.equal(store.getApproval(first.id), null);
    assert.equal(store.getApproval(second.id), null);
});

test("a corrupt file or invalid entries are ignored instead of crashing", async (t) => {
    const dir = await makeDir(t);
    const store = createPhoneApprovalStore({ dataDir: dir, now: makeClock().now, log: silentLog });
    store.createApproval(approvalInput());

    await writeFile(join(dir, "phone-approvals.json"), "{ nope", "utf8");
    const broken = createPhoneApprovalStore({ dataDir: dir, now: makeClock().now, log: silentLog });
    assert.deepEqual(broken.listApprovals({ state: "all" }), []);

    await writeFile(
        join(dir, "phone-approvals.json"),
        JSON.stringify({ version: 1, approvals: [{ id: "nope" }] }),
        "utf8",
    );
    const junk = createPhoneApprovalStore({ dataDir: dir, now: makeClock().now, log: silentLog });
    assert.deepEqual(junk.listApprovals({ state: "all" }), []);
});
