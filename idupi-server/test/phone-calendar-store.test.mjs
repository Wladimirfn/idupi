// Tests for the phone calendar store (phone-calendar, T1): snapshot
// validation and caps, persistence, deterministic conflicts, free slots and
// the proposal lifecycle.
//
// Run (from repo root):
//   node --test idupi-server/test/phone-calendar-store.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
    MAX_EVENTS,
    createPhoneCalendarStore,
    findOverlapPairs,
    freeSlots,
    normalizeSnapshot,
} from "../lib/phone-calendar-store.mjs";

const silentLog = { log() {}, info() {}, warn() {}, error() {} };

const CALENDAR = {
    id: "cal-1",
    name: "Personal",
    accountType: "com.google",
    accountName: "owner@gmail.com",
    writable: true,
};

function event(overrides = {}) {
    return {
        id: "ev-1",
        calendarId: "cal-1",
        title: "Reunión de equipo",
        start: "2026-11-24T13:00:00.000Z",
        end: "2026-11-24T14:00:00.000Z",
        allDay: false,
        location: "Oficina",
        ...overrides,
    };
}

function snapshot(overrides = {}) {
    return {
        capturedAt: "2026-11-24T12:00:00.000Z",
        rangeStart: "2026-11-24T00:00:00.000Z",
        rangeEnd: "2026-12-08T00:00:00.000Z",
        tzOffsetMinutes: -180,
        calendars: [CALENDAR],
        events: [event()],
        ...overrides,
    };
}

async function makeDir(t) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-calendar-store-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    return dir;
}

// --- validation ------------------------------------------------------------

test("normalizeSnapshot accepts the wire shape and normalizes ISO times", () => {
    const normalized = normalizeSnapshot(snapshot());
    assert.equal(normalized.tzOffsetMinutes, -180);
    assert.equal(normalized.events[0].start, "2026-11-24T13:00:00.000Z");
    assert.equal(normalized.events[0].description, "");
    assert.equal(normalized.calendars[0].name, "Personal");
});

test("normalizeSnapshot rejects bad types, bad ISO times and oversized lists", () => {
    assert.throws(() => normalizeSnapshot(null), /must be a JSON object/);
    assert.throws(() => normalizeSnapshot(snapshot({ capturedAt: "ayer" })), /ISO-8601/);
    assert.throws(
        () => normalizeSnapshot(snapshot({ events: [event({ start: "nope" })] })),
        /ISO-8601/,
    );
    assert.throws(
        () => normalizeSnapshot(snapshot({ events: [event({ end: "2026-11-24T12:00:00.000Z" })] })),
        /before start/,
    );
    assert.throws(
        () => normalizeSnapshot(snapshot({ events: [event({ allDay: "no" })] })),
        /allDay must be a boolean/,
    );
    assert.throws(
        () => normalizeSnapshot(snapshot({ calendars: [{ ...CALENDAR, writable: "yes" }] })),
        /writable must be a boolean/,
    );

    // The Android client omits false booleans (kotlinx encodeDefaults = false).
    const { writable: _w, ...calendarWithoutWritable } = CALENDAR;
    const { allDay: _a, ...eventWithoutAllDay } = event();
    const omitted = normalizeSnapshot(snapshot({
        calendars: [calendarWithoutWritable],
        events: [eventWithoutAllDay],
    }));
    assert.equal(omitted.calendars[0].writable, false);
    assert.equal(omitted.events[0].allDay, false);

    assert.throws(
        () => normalizeSnapshot(snapshot({ tzOffsetMinutes: 9000 })),
        /tzOffsetMinutes/,
    );

    const many = Array.from({ length: MAX_EVENTS + 1 }, (_, index) => (
        event({ id: `ev-${index}` })
    ));
    assert.throws(() => normalizeSnapshot(snapshot({ events: many })), /exceeds 1000/);
});

test("normalizeSnapshot clips free text instead of rejecting the snapshot", () => {
    const normalized = normalizeSnapshot(snapshot({
        events: [event({
            title: "t".repeat(300),
            location: "l".repeat(300),
            description: "d".repeat(900),
        })],
    }));
    const stored = normalized.events[0];
    assert.equal(stored.title.length, 200);
    assert.equal(stored.location.length, 200);
    assert.equal(stored.description.length, 500);
});

// --- snapshot persistence --------------------------------------------------

test("recordSnapshot persists the latest snapshot and reloads it", async (t) => {
    const dir = await makeDir(t);
    const store = createPhoneCalendarStore({ dataDir: dir, log: silentLog });
    const result = store.recordSnapshot(snapshot());

    assert.equal(result.eventCount, 1);
    assert.equal(result.calendarCount, 1);
    assert.ok(Number.isFinite(Date.parse(result.storedAt)));

    const reloaded = createPhoneCalendarStore({ dataDir: dir, log: silentLog });
    const agenda = reloaded.getAgenda();
    assert.equal(agenda.capturedAt, "2026-11-24T12:00:00.000Z");
    assert.equal(agenda.events.length, 1);
    assert.equal(agenda.tzOffsetMinutes, -180);
});

test("getAgenda filters by range, sorts by start and reports no snapshot as empty", async (t) => {
    const dir = await makeDir(t);
    const store = createPhoneCalendarStore({ dataDir: dir, log: silentLog });

    const empty = store.getAgenda();
    assert.equal(empty.capturedAt, null);
    assert.deepEqual(empty.events, []);

    store.recordSnapshot(snapshot({
        events: [
            event({ id: "late", start: "2026-11-26T15:00:00.000Z", end: "2026-11-26T16:00:00.000Z" }),
            event({ id: "early", start: "2026-11-24T13:00:00.000Z", end: "2026-11-24T14:00:00.000Z" }),
            event({
                id: "all-day",
                allDay: true,
                start: "2026-11-25T03:00:00.000Z",
                end: "2026-11-26T03:00:00.000Z",
            }),
        ],
    }));

    const all = store.getAgenda();
    assert.deepEqual(all.events.map((entry) => entry.id), ["early", "all-day", "late"]);

    const narrow = store.getAgenda({
        from: "2026-11-25T00:00:00.000Z",
        to: "2026-11-26T00:00:00.000Z",
    });
    assert.deepEqual(narrow.events.map((entry) => entry.id), ["all-day"]);
    assert.deepEqual(store.getSnapshot().events.length, 3);
});

// --- conflicts -------------------------------------------------------------

test("findOverlapPairs marks real overlaps only and never all-day events", () => {
    const pairs = findOverlapPairs([
        event({ id: "a", start: "2026-11-24T13:00:00.000Z", end: "2026-11-24T14:00:00.000Z" }),
        event({ id: "b", start: "2026-11-24T13:30:00.000Z", end: "2026-11-24T14:30:00.000Z" }),
        // Touches a's end exactly: a-c is not a pair, but c overlaps b.
        event({ id: "c", start: "2026-11-24T14:00:00.000Z", end: "2026-11-24T15:00:00.000Z" }),
        // All-day spanning everything: never conflicts.
        event({
            id: "d",
            allDay: true,
            start: "2026-11-24T03:00:00.000Z",
            end: "2026-11-25T03:00:00.000Z",
        }),
    ]);

    assert.deepEqual(
        pairs.map((pair) => `${pair.a.id}-${pair.b.id}`),
        ["a-b", "b-c"],
    );
    assert.equal(pairs[0].start, "2026-11-24T13:30:00.000Z");
    assert.equal(pairs[0].end, "2026-11-24T14:00:00.000Z");
});

test("findOverlapPairs finds every chain and is deterministic", () => {
    const events = [
        event({ id: "a", start: "2026-11-24T13:00:00.000Z", end: "2026-11-24T15:00:00.000Z" }),
        event({ id: "b", start: "2026-11-24T14:00:00.000Z", end: "2026-11-24T16:00:00.000Z" }),
        event({ id: "c", start: "2026-11-24T14:30:00.000Z", end: "2026-11-24T15:30:00.000Z" }),
    ];
    const first = findOverlapPairs(events);
    const second = findOverlapPairs([...events].reverse());
    assert.deepEqual(first, second);
    assert.equal(first.length, 3);
    assert.deepEqual(first.map((pair) => `${pair.a.id}-${pair.b.id}`), ["a-b", "a-c", "b-c"]);
});

// --- free slots ------------------------------------------------------------

test("freeSlots returns the whole local day when nothing blocks it", () => {
    const slots = freeSlots({
        events: [],
        from: "2026-11-24T11:00:00.000Z", // local 08:00, offset -180
        to: "2026-11-24T23:00:00.000Z", // local 20:00
        tzOffsetMinutes: -180,
    });
    assert.deepEqual(slots, [{
        start: "2026-11-24T11:00:00.000Z",
        end: "2026-11-24T23:00:00.000Z",
        minutes: 720,
    }]);
});

test("freeSlots subtracts events, merges overlap chains and ignores all-day", () => {
    const slots = freeSlots({
        events: [
            event({ id: "a", start: "2026-11-24T13:00:00.000Z", end: "2026-11-24T14:00:00.000Z" }),
            event({ id: "b", start: "2026-11-24T13:30:00.000Z", end: "2026-11-24T14:30:00.000Z" }),
            // All-day: never blocks free time.
            event({
                id: "all-day",
                allDay: true,
                start: "2026-11-24T03:00:00.000Z",
                end: "2026-11-25T03:00:00.000Z",
            }),
        ],
        from: "2026-11-24T11:00:00.000Z",
        to: "2026-11-24T23:00:00.000Z",
        tzOffsetMinutes: -180,
    });
    assert.deepEqual(slots, [
        { start: "2026-11-24T11:00:00.000Z", end: "2026-11-24T13:00:00.000Z", minutes: 120 },
        { start: "2026-11-24T14:30:00.000Z", end: "2026-11-24T23:00:00.000Z", minutes: 510 },
    ]);
});

test("freeSlots honors day boundaries, minMinutes and the offset", () => {
    const events = [
        event({ id: "a", start: "2026-11-24T13:00:00.000Z", end: "2026-11-24T14:00:00.000Z" }),
    ];
    const withGap = freeSlots({
        events,
        from: "2026-11-24T11:00:00.000Z",
        to: "2026-11-24T23:00:00.000Z",
        minMinutes: 90,
        tzOffsetMinutes: -180,
    });
    // The 120-min morning gap survives; the 540-min afternoon gap too.
    assert.equal(withGap.length, 2);
    assert.equal(withGap[0].minutes, 120);

    const filtered = freeSlots({
        events,
        from: "2026-11-24T11:00:00.000Z",
        to: "2026-11-24T23:00:00.000Z",
        minMinutes: 130,
        tzOffsetMinutes: -180,
    });
    assert.equal(filtered.length, 1);
    assert.equal(filtered[0].minutes, 540);

    // Same UTC instants with a zero offset: the local window shifts to 08:00Z,
    // so the event at 13:00Z cuts the middle of it.
    const utc = freeSlots({
        events,
        from: "2026-11-24T08:00:00.000Z",
        to: "2026-11-24T20:00:00.000Z",
        tzOffsetMinutes: 0,
    });
    assert.deepEqual(utc, [
        { start: "2026-11-24T08:00:00.000Z", end: "2026-11-24T13:00:00.000Z", minutes: 300 },
        { start: "2026-11-24T14:00:00.000Z", end: "2026-11-24T20:00:00.000Z", minutes: 360 },
    ]);
});

test("freeSlots clamps to the requested range and spans multiple local days", () => {
    const slots = freeSlots({
        events: [],
        from: "2026-11-24T12:00:00.000Z", // local 09:00
        to: "2026-11-25T12:00:00.000Z", // local 09:00 next day
        tzOffsetMinutes: -180,
    });
    assert.deepEqual(slots, [
        { start: "2026-11-24T12:00:00.000Z", end: "2026-11-24T23:00:00.000Z", minutes: 660 },
        { start: "2026-11-25T11:00:00.000Z", end: "2026-11-25T12:00:00.000Z", minutes: 60 },
    ]);
});

// --- proposals -------------------------------------------------------------

test("proposals are created pending with conflicts and can be approved", async (t) => {
    const dir = await makeDir(t);
    const store = createPhoneCalendarStore({ dataDir: dir, log: silentLog });
    store.recordSnapshot(snapshot());

    const proposal = store.createProposal({
        title: "Almuerzo",
        start: "2026-11-24T13:30:00.000Z",
        end: "2026-11-24T14:30:00.000Z",
        location: "Centro",
        notes: "Con Ana",
        source: "test",
    });
    assert.match(proposal.id, /^phr-[0-9a-f]{12}$/);
    assert.equal(proposal.status, "pending");
    assert.equal(proposal.conflicts.length, 1);
    assert.equal(proposal.conflicts[0].title, "Reunión de equipo");

    const approved = store.decideProposal(proposal.id, {
        decision: "approved",
        eventId: "device-event-42",
    });
    assert.equal(approved.ok, true);
    assert.equal(approved.proposal.status, "approved");
    assert.equal(approved.proposal.eventId, "device-event-42");

    const again = store.decideProposal(proposal.id, { decision: "rejected" });
    assert.deepEqual(again, { ok: false, reason: "already_decided" });

    const reloaded = createPhoneCalendarStore({ dataDir: dir, log: silentLog });
    const listed = reloaded.listProposals({ status: "approved" });
    assert.equal(listed.length, 1);
    assert.equal(listed[0].eventId, "device-event-42");
});

test("proposals reject bad input and unknown ids", async (t) => {
    const dir = await makeDir(t);
    const store = createPhoneCalendarStore({ dataDir: dir, log: silentLog });

    assert.throws(() => store.createProposal({ start: "2026-11-24T13:00:00.000Z", source: "x" }), /title/);
    assert.throws(() => store.createProposal({ title: "x", start: "nope", source: "x" }), /ISO-8601/);
    assert.throws(
        () => store.createProposal({ title: "x", start: "2026-11-24T13:00:00.000Z", source: "x", allDay: "no" }),
        /allDay/,
    );
    assert.deepEqual(store.decideProposal("phr-000000000000", { decision: "approved" }), {
        ok: false,
        reason: "not_found",
    });
    assert.throws(
        () => store.listProposals({ status: "maybe" }),
        /status must be one of/,
    );
});

test("pending proposals expire after seven days and cannot be decided", async (t) => {
    const dir = await makeDir(t);
    let current = new Date("2026-11-24T12:00:00.000Z");
    const store = createPhoneCalendarStore({
        dataDir: dir,
        now: () => current,
        log: silentLog,
    });

    const proposal = store.createProposal({
        title: "Revisión",
        start: "2026-11-30T13:00:00.000Z",
        source: "test",
    });
    assert.equal(store.listProposals({ status: "pending" }).length, 1);

    current = new Date("2026-12-02T12:00:00.000Z"); // eight days later
    const pending = store.listProposals({ status: "pending" });
    assert.equal(pending.length, 0);
    const expired = store.listProposals({ status: "expired" });
    assert.equal(expired.length, 1);

    const decision = store.decideProposal(proposal.id, { decision: "approved" });
    assert.deepEqual(decision, { ok: false, reason: "expired" });
});
