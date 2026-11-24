// Tests for the phone calendar HTTP routes (phone-calendar, T1).
//
// A real http.Server on an ephemeral port wires the handler exactly as
// index.mjs does (minus auth, which index.mjs owns upstream: the handler only
// runs after requireAuth). The store shares the test clock.
//
// Run (from repo root):
//   node --test idupi-server/test/phone-calendar-routes.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { createPhoneCalendarStore } from "../lib/phone-calendar-store.mjs";
import { createPhoneCalendarRoutes } from "../lib/phone-calendar-routes.mjs";

const silentLog = { log() {}, info() {}, warn() {}, error() {} };
const NOW = "2026-11-24T12:00:00.000Z";

const CALENDAR = {
    id: "cal-1",
    name: "Personal",
    accountType: "com.google",
    accountName: "owner@gmail.com",
    writable: true,
};

function snapshotEvent(overrides = {}) {
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
        capturedAt: NOW,
        rangeStart: "2026-11-24T00:00:00.000Z",
        rangeEnd: "2026-12-08T00:00:00.000Z",
        tzOffsetMinutes: -180,
        calendars: [CALENDAR],
        events: [
            snapshotEvent(),
            snapshotEvent({
                id: "ev-2",
                title: "Almuerzo",
                start: "2026-11-24T13:30:00.000Z",
                end: "2026-11-24T14:30:00.000Z",
            }),
        ],
        ...overrides,
    };
}

async function startHarness(t) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-calendar-routes-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const store = createPhoneCalendarStore({
        dataDir: dir,
        now: () => new Date(NOW),
        log: silentLog,
    });
    const handle = createPhoneCalendarRoutes({
        store,
        now: () => new Date(NOW),
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
    return { base, store };
}

function postJson(base, path, body) {
    return fetch(base + path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: typeof body === "string" ? body : JSON.stringify(body),
    });
}

test("POST snapshot stores it and GET agenda returns sorted events with conflicts", async (t) => {
    const { base } = await startHarness(t);

    const posted = await postJson(base, "/api/v1/phone/calendar", snapshot());
    const body = await posted.json();
    assert.equal(posted.status, 200);
    assert.equal(body.ok, true);
    assert.equal(body.snapshot.eventCount, 2);
    assert.equal(body.snapshot.calendarCount, 1);
    assert.equal(body.snapshot.tzOffsetMinutes, -180);

    const res = await fetch(
        base + "/api/v1/phone/calendar" +
        "?from=2026-11-24T00:00:00.000Z&to=2026-11-25T00:00:00.000Z",
    );
    const agenda = await res.json();
    assert.equal(res.status, 200);
    assert.equal(agenda.capturedAt, NOW);
    assert.deepEqual(agenda.events.map((entry) => entry.id), ["ev-1", "ev-2"]);
    assert.equal(agenda.conflicts.length, 1);
    assert.equal(agenda.conflicts[0].a.id, "ev-1");
    assert.equal(agenda.conflicts[0].b.id, "ev-2");
    assert.equal(agenda.calendars[0].name, "Personal");
});

test("GET agenda without a snapshot answers an empty envelope", async (t) => {
    const { base } = await startHarness(t);
    const res = await fetch(base + "/api/v1/phone/calendar");
    const agenda = await res.json();
    assert.equal(res.status, 200);
    assert.equal(agenda.capturedAt, null);
    assert.deepEqual(agenda.events, []);
    assert.deepEqual(agenda.conflicts, []);
});

test("POST snapshot rejects malformed bodies with 400 and creates nothing", async (t) => {
    const { base, store } = await startHarness(t);

    const badJson = await postJson(base, "/api/v1/phone/calendar", "{ nope");
    assert.equal(badJson.status, 400);
    assert.equal((await badJson.json()).code, "BAD_REQUEST");

    const badTime = await postJson(base, "/api/v1/phone/calendar", snapshot({
        events: [snapshotEvent({ start: "tomorrow" })],
    }));
    assert.equal(badTime.status, 400);
    assert.equal((await badTime.json()).code, "INVALID_SNAPSHOT");

    const array = await postJson(base, "/api/v1/phone/calendar", [1, 2]);
    assert.equal(array.status, 400);

    assert.equal(store.getSnapshot(), null);
});

test("GET agenda and free reject invalid ranges and parameters", async (t) => {
    const { base } = await startHarness(t);

    const badFrom = await fetch(base + "/api/v1/phone/calendar?from=ayer");
    assert.equal(badFrom.status, 400);
    assert.equal((await badFrom.json()).code, "INVALID_RANGE");

    const inverted = await fetch(
        base + "/api/v1/phone/calendar" +
        "?from=2026-11-25T00:00:00.000Z&to=2026-11-24T00:00:00.000Z",
    );
    assert.equal(inverted.status, 400);

    const badMin = await fetch(base + "/api/v1/phone/calendar/free?min=0");
    assert.equal(badMin.status, 400);
    assert.equal((await badMin.json()).code, "INVALID_MIN");

    const badDays = await fetch(base + "/api/v1/phone/calendar/free?days=99");
    assert.equal(badDays.status, 400);
    assert.equal((await badDays.json()).code, "INVALID_DAYS");
});

test("GET free computes slots from the stored snapshot", async (t) => {
    const { base } = await startHarness(t);
    await postJson(base, "/api/v1/phone/calendar", snapshot());

    const res = await fetch(
        base + "/api/v1/phone/calendar/free" +
        "?from=2026-11-24T11:00:00.000Z&to=2026-11-24T23:00:00.000Z&min=30",
    );
    const free = await res.json();
    assert.equal(res.status, 200);
    assert.equal(free.hasSnapshot, true);
    assert.equal(free.tzOffsetMinutes, -180);
    assert.deepEqual(free.slots, [
        { start: "2026-11-24T11:00:00.000Z", end: "2026-11-24T13:00:00.000Z", minutes: 120 },
        { start: "2026-11-24T14:30:00.000Z", end: "2026-11-24T23:00:00.000Z", minutes: 510 },
    ]);
});

test("GET free without a snapshot reports hasSnapshot false and no slots", async (t) => {
    const { base } = await startHarness(t);
    const res = await fetch(base + "/api/v1/phone/calendar/free?days=1&min=30");
    const free = await res.json();
    assert.equal(res.status, 200);
    assert.equal(free.hasSnapshot, false);
    assert.deepEqual(free.slots, []);
});

test("proposal lifecycle: create, list, approve, reject and re-decide", async (t) => {
    const { base } = await startHarness(t);
    await postJson(base, "/api/v1/phone/calendar", snapshot());

    const created = await postJson(base, "/api/v1/phone/calendar/proposals", {
        title: "Almuerzo con Ana",
        start: "2026-11-24T13:30:00.000Z",
        end: "2026-11-24T14:30:00.000Z",
        location: "Centro",
        notes: "Llevar el informe",
        source: "agent",
    });
    const body = await created.json();
    assert.equal(created.status, 201);
    assert.equal(body.tzOffsetMinutes, -180);
    assert.equal(body.proposal.status, "pending");
    assert.equal(body.proposal.conflicts.length, 2);
    assert.deepEqual(
        body.proposal.conflicts.map((entry) => entry.title),
        ["Reunión de equipo", "Almuerzo"],
    );
    const id = body.proposal.id;

    const listed = await fetch(
        base + "/api/v1/phone/calendar/proposals?status=pending",
    );
    const queue = await listed.json();
    assert.equal(listed.status, 200);
    assert.equal(queue.proposals.length, 1);
    assert.equal(queue.tzOffsetMinutes, -180);

    const approved = await postJson(
        base,
        `/api/v1/phone/calendar/proposals/${id}/decision`,
        { decision: "approved", eventId: "device-7" },
    );
    const decided = await approved.json();
    assert.equal(approved.status, 200);
    assert.equal(decided.proposal.status, "approved");
    assert.equal(decided.proposal.eventId, "device-7");

    const again = await postJson(
        base,
        `/api/v1/phone/calendar/proposals/${id}/decision`,
        { decision: "rejected" },
    );
    assert.equal(again.status, 409);
    assert.equal((await again.json()).code, "ALREADY_DECIDED");
});

test("proposal routes reject bad input, unknown ids and bad filters", async (t) => {
    const { base } = await startHarness(t);

    const noTitle = await postJson(base, "/api/v1/phone/calendar/proposals", {
        start: "2026-11-24T13:00:00.000Z",
        source: "agent",
    });
    assert.equal(noTitle.status, 400);
    assert.equal((await noTitle.json()).code, "INVALID_PROPOSAL");

    const badDecision = await postJson(
        base,
        "/api/v1/phone/calendar/proposals/phr-000000000000/decision",
        { decision: "maybe" },
    );
    assert.equal(badDecision.status, 404);

    const unknown = await postJson(
        base,
        "/api/v1/phone/calendar/proposals/phr-111111111111/decision",
        { decision: "approved" },
    );
    assert.equal(unknown.status, 404);
    assert.equal((await unknown.json()).code, "NOT_FOUND");

    const badStatus = await fetch(base + "/api/v1/phone/calendar/proposals?status=maybe");
    assert.equal(badStatus.status, 400);
    assert.equal((await badStatus.json()).code, "INVALID_STATUS");
});

test("the calendar subtree answers 405 for wrong methods and is otherwise not claimed", async (t) => {
    const { base } = await startHarness(t);

    const del = await fetch(base + "/api/v1/phone/calendar", { method: "DELETE" });
    assert.equal(del.status, 405);
    assert.equal(del.headers.get("allow"), "GET, HEAD, POST");

    const putFree = await fetch(base + "/api/v1/phone/calendar/free", { method: "PUT" });
    assert.equal(putFree.status, 405);

    const getDecision = await fetch(
        base + "/api/v1/phone/calendar/proposals/phr-000000000000/decision",
    );
    assert.equal(getDecision.status, 405);

    // Unknown subpaths fall through to the rest of the route chain.
    const unknown = await fetch(base + "/api/v1/phone/calendar/unknown");
    assert.equal(unknown.status, 418);
});
