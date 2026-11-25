// Phone calendar store (phone-calendar, T1).
//
// Two JSON files live in idupi-server/data/ (gitignored):
//
//   phone-calendar.json           the latest snapshot the phone reported
//   phone-calendar-proposals.json  PC proposals awaiting the owner's decision
//
// The snapshot is a machine-to-machine contract, so validation is strict: a
// malformed field rejects the whole upload with a 400 instead of storing a
// half-valid agenda. Free text is clipped (title, location, description),
// because one long subject must not fail an otherwise valid report.
//
// Everything that decides something is pure and exported: snapshot
// normalization, conflict pairs, event conflicts and free slots. The store
// only owns persistence and the proposal lifecycle. Times are computed in UTC
// and day boundaries use the phone's tzOffsetMinutes, so no local-time math
// leaks into the server.

import { randomBytes } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const MAX_EVENTS = 1000;
export const MAX_CALENDARS = 50;
export const MAX_EVENT_TITLE_CHARS = 200;
export const MAX_EVENT_LOCATION_CHARS = 200;
export const MAX_EVENT_DESCRIPTION_CHARS = 500;
export const MAX_CALENDAR_NAME_CHARS = 120;
export const MAX_ACCOUNT_TYPE_CHARS = 120;
export const MAX_ACCOUNT_NAME_CHARS = 120;
export const MAX_COLOR_CHARS = 32;
export const MAX_ID_CHARS = 200;
export const MAX_TZ_OFFSET_MINUTES = 14 * 60;

export const DEFAULT_FREE_DAY_START = "08:00";
export const DEFAULT_FREE_DAY_END = "20:00";
export const DEFAULT_FREE_MIN_MINUTES = 30;
export const MAX_FREE_MIN_MINUTES = 24 * 60;
export const MAX_FREE_DAYS = 62;

export const PROPOSAL_ID_PATTERN = /^phr-[0-9a-f]{12}$/;
export const PROPOSAL_STATUSES = ["pending", "approved", "rejected", "expired"];
export const DEFAULT_PROPOSAL_TTL_MS = 7 * 24 * 60 * 60 * 1000;
export const MAX_PROPOSAL_TITLE_CHARS = 200;
export const MAX_PROPOSAL_LOCATION_CHARS = 200;
export const MAX_PROPOSAL_NOTES_CHARS = 1000;
export const MAX_PROPOSAL_SOURCE_CHARS = 100;
export const DEFAULT_PROPOSAL_LIST_LIMIT = 50;
export const MAX_PROPOSAL_LIST_LIMIT = 200;

const SNAPSHOT_FILE = "phone-calendar.json";
const PROPOSALS_FILE = "phone-calendar-proposals.json";
const DAY_MS = 24 * 60 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;

function typedError(code, message) {
    const err = new Error(message);
    err.code = code;
    return err;
}

function isPlainObject(value) {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function clip(value, maxChars) {
    const text = typeof value === "string" ? value : String(value ?? "");
    return text.length > maxChars ? text.slice(0, maxChars) : text;
}

function singleLine(value, maxChars) {
    return clip(String(value ?? "").replace(/\s+/g, " ").trim(), maxChars);
}

function requirePlainObject(value, field) {
    if (!isPlainObject(value)) {
        throw typedError("INVALID_SNAPSHOT", `${field} must be a JSON object`);
    }
    return value;
}

function requireText(value, field, maxChars) {
    if (typeof value !== "string") {
        throw typedError("INVALID_SNAPSHOT", `${field} must be a string`);
    }
    const text = value.trim();
    if (text.length === 0) {
        throw typedError("INVALID_SNAPSHOT", `${field} is required`);
    }
    if (text.length > maxChars) {
        throw typedError("INVALID_SNAPSHOT", `${field} exceeds ${maxChars} characters`);
    }
    return text;
}

/**
 * Booleans whose default is false. The Android client serializes with
 * kotlinx.serialization defaults (encodeDefaults = false), so a false value is
 * omitted from the JSON; absent means false, any other non-boolean is refused.
 */
function optionalBoolean(value, field) {
    if (value === undefined || value === null) return false;
    return requireBoolean(value, field);
}

function requireBoolean(value, field) {
    if (typeof value !== "boolean") {
        throw typedError("INVALID_SNAPSHOT", `${field} must be a boolean`);
    }
    return value;
}

/** Epoch ms of an ISO-8601 string, or null when it does not parse. */
export function parseInstant(value) {
    if (typeof value !== "string" || value.trim().length === 0) return null;
    const ms = Date.parse(value);
    return Number.isFinite(ms) ? ms : null;
}

function requireInstant(value, field, code = "INVALID_SNAPSHOT") {
    const ms = parseInstant(value);
    if (ms === null) {
        throw typedError(code, `${field} must be an ISO-8601 date`);
    }
    return ms;
}

function requireOptionalText(value, field, maxChars) {
    if (value === undefined || value === null) return "";
    if (typeof value !== "string") {
        throw typedError("INVALID_SNAPSHOT", `${field} must be a string`);
    }
    return clip(value, maxChars);
}

function normalizeCalendar(raw, index) {
    const field = `calendars[${index}]`;
    requirePlainObject(raw, field);
    return {
        id: requireText(raw.id, `${field}.id`, MAX_ID_CHARS),
        name: singleLine(raw.name, MAX_CALENDAR_NAME_CHARS),
        accountType: singleLine(raw.accountType, MAX_ACCOUNT_TYPE_CHARS),
        accountName: singleLine(raw.accountName, MAX_ACCOUNT_NAME_CHARS),
        color: raw.color === undefined || raw.color === null
            ? null
            : singleLine(raw.color, MAX_COLOR_CHARS),
        writable: optionalBoolean(raw.writable, `${field}.writable`),
    };
}

function normalizeEvent(raw, index) {
    const field = `events[${index}]`;
    requirePlainObject(raw, field);
    const startMs = requireInstant(raw.start, `${field}.start`);
    const endMs = requireInstant(raw.end, `${field}.end`);
    if (endMs < startMs) {
        throw typedError("INVALID_SNAPSHOT", `${field}.end is before start`);
    }
    return {
        id: requireText(raw.id, `${field}.id`, MAX_ID_CHARS),
        calendarId: requireText(raw.calendarId, `${field}.calendarId`, MAX_ID_CHARS),
        title: singleLine(raw.title, MAX_EVENT_TITLE_CHARS),
        start: new Date(startMs).toISOString(),
        end: new Date(endMs).toISOString(),
        allDay: optionalBoolean(raw.allDay, `${field}.allDay`),
        location: requireOptionalText(raw.location, `${field}.location`, MAX_EVENT_LOCATION_CHARS),
        description: requireOptionalText(
            raw.description,
            `${field}.description`,
            MAX_EVENT_DESCRIPTION_CHARS,
        ),
    };
}

/**
 * Validates a POST /phone/calendar body and returns the normalized snapshot
 * (without `storedAt`, which the store adds). Throws a typed INVALID_SNAPSHOT
 * error the route maps to 400.
 */
export function normalizeSnapshot(input) {
    const body = requirePlainObject(input, "body");
    const capturedAt = requireInstant(body.capturedAt, "capturedAt");
    const rangeStart = requireInstant(body.rangeStart, "rangeStart");
    const rangeEnd = requireInstant(body.rangeEnd, "rangeEnd");
    if (rangeEnd < rangeStart) {
        throw typedError("INVALID_SNAPSHOT", "rangeEnd is before rangeStart");
    }
    if (!Array.isArray(body.calendars)) {
        throw typedError("INVALID_SNAPSHOT", "calendars must be an array");
    }
    if (body.calendars.length > MAX_CALENDARS) {
        throw typedError("INVALID_SNAPSHOT", `calendars exceeds ${MAX_CALENDARS} entries`);
    }
    if (!Array.isArray(body.events)) {
        throw typedError("INVALID_SNAPSHOT", "events must be an array");
    }
    if (body.events.length > MAX_EVENTS) {
        throw typedError("INVALID_SNAPSHOT", `events exceeds ${MAX_EVENTS} entries`);
    }

    let tzOffsetMinutes = 0;
    if (body.tzOffsetMinutes !== undefined && body.tzOffsetMinutes !== null) {
        if (
            !Number.isInteger(body.tzOffsetMinutes) ||
            Math.abs(body.tzOffsetMinutes) > MAX_TZ_OFFSET_MINUTES
        ) {
            throw typedError(
                "INVALID_SNAPSHOT",
                `tzOffsetMinutes must be an integer between -${MAX_TZ_OFFSET_MINUTES} and ${MAX_TZ_OFFSET_MINUTES}`,
            );
        }
        tzOffsetMinutes = body.tzOffsetMinutes;
    }

    return {
        capturedAt: new Date(capturedAt).toISOString(),
        rangeStart: new Date(rangeStart).toISOString(),
        rangeEnd: new Date(rangeEnd).toISOString(),
        tzOffsetMinutes,
        calendars: body.calendars.map(normalizeCalendar),
        // Validate every event first, then drop meetings the organizer
        // cancelled: Outlook keeps them with a "Cancelada:" title prefix.
        events: body.events.map(normalizeEvent).filter((entry) => !isCancelledTitle(entry.title)),
    };
}

const CANCELLED_TITLE = /^\s*(?:cancelad[oa]s?|cancell?ed|annul[ée]e?)\s*:/i;

/**
 * True for titles of meetings the organizer cancelled. Outlook (and Exchange
 * through the Android calendar provider) keeps such a meeting as a normal
 * event whose title starts with "Cancelada:" / "Canceled:" instead of
 * setting STATUS_CANCELED, so it would otherwise count as busy time.
 */
export function isCancelledTitle(title) {
    return CANCELLED_TITLE.test(String(title ?? ""));
}

/** Ordered event reference used in conflicts and proposal warnings. */
function eventRef(event) {
    return {
        id: event.id,
        title: event.title,
        start: event.start,
        end: event.end,
    };
}

/** True when two timed ranges overlap; touching endpoints never overlap. */
function overlaps(a, b) {
    return a.startMs < b.endMs && b.startMs < a.endMs;
}

function timedIntervals(events) {
    return (Array.isArray(events) ? events : [])
        .filter((event) => event && event.allDay !== true)
        .map((event) => ({
            event,
            startMs: parseInstant(event.start),
            endMs: parseInstant(event.end),
        }))
        .filter((entry) => entry.startMs !== null && entry.endMs !== null && entry.endMs > entry.startMs);
}

/**
 * Pairs of non-all-day events whose time ranges overlap. Deterministic:
 * sorted by the earlier start, then by the pair's ids.
 */
export function findOverlapPairs(events) {
    const timed = timedIntervals(events)
        .sort((a, b) => (
            a.startMs - b.startMs ||
            String(a.event.id).localeCompare(String(b.event.id))
        ));
    const pairs = [];
    for (let i = 0; i < timed.length; i += 1) {
        for (let j = i + 1; j < timed.length; j += 1) {
            if (timed[j].startMs >= timed[i].endMs) break;
            if (!overlaps(timed[i], timed[j])) continue;
            pairs.push({
                a: eventRef(timed[i].event),
                b: eventRef(timed[j].event),
                start: new Date(Math.max(timed[i].startMs, timed[j].startMs)).toISOString(),
                end: new Date(Math.min(timed[i].endMs, timed[j].endMs)).toISOString(),
            });
        }
    }
    return pairs;
}

/**
 * Existing events that overlap one event, sorted by start. All-day events
 * never conflict on either side.
 */
export function conflictsWith(event, events) {
    if (!event || event.allDay === true) return [];
    const startMs = parseInstant(event.start);
    const endMs = parseInstant(event.end);
    if (startMs === null || endMs === null || endMs <= startMs) return [];
    const candidate = { startMs, endMs };
    return timedIntervals(events)
        .filter((entry) => overlaps(candidate, entry))
        .sort((a, b) => a.startMs - b.startMs)
        .map((entry) => ({
            id: entry.event.id,
            title: entry.event.title,
            start: new Date(entry.startMs).toISOString(),
            end: new Date(entry.endMs).toISOString(),
        }));
}

function parseClock(value, fallback) {
    const match = /^(\d{1,2}):(\d{2})$/.exec(String(value ?? ""));
    if (!match) return fallback;
    const hours = Number(match[1]);
    const minutes = Number(match[2]);
    if (hours > 23 || minutes > 59) return fallback;
    return (hours * 60 + minutes) * 60 * 1000;
}

/**
 * Free time between `dayStart` and `dayEnd` (local wall clock) for every local
 * day touched by [from, to]. All-day events never block time. Overlapping
 * events merge into one busy interval, so an overlap never hides a slot.
 *
 * `tzOffsetMinutes` is the phone's offset from UTC (UTC-3 -> -180): local wall
 * time = UTC + offset. The offset is fixed for the whole range (no per-day DST
 * switch); the phone reports the offset in effect.
 */
export function freeSlots({
    events = [],
    from,
    to,
    dayStart = DEFAULT_FREE_DAY_START,
    dayEnd = DEFAULT_FREE_DAY_END,
    minMinutes = DEFAULT_FREE_MIN_MINUTES,
    tzOffsetMinutes = 0,
} = {}) {
    const fromMs = parseInstant(from);
    const toMs = parseInstant(to);
    if (fromMs === null || toMs === null || toMs <= fromMs) return [];
    const offset = Number.isInteger(tzOffsetMinutes) &&
        Math.abs(tzOffsetMinutes) <= MAX_TZ_OFFSET_MINUTES
        ? tzOffsetMinutes
        : 0;
    const offsetMs = offset * 60 * 1000;
    const startOfDay = parseClock(dayStart, parseClock(DEFAULT_FREE_DAY_START, 8 * HOUR_MS));
    const endOfDay = parseClock(dayEnd, parseClock(DEFAULT_FREE_DAY_END, 20 * HOUR_MS));
    if (endOfDay <= startOfDay) return [];
    const minMs = Math.max(0, Number(minMinutes) || 0) * 60 * 1000;

    const busy = timedIntervals(events)
        .map((entry) => ({ startMs: entry.startMs, endMs: entry.endMs }))
        .sort((a, b) => a.startMs - b.startMs);

    const firstDay = Math.floor((fromMs + offsetMs) / DAY_MS);
    const lastDay = Math.floor((toMs + offsetMs - 1) / DAY_MS);
    const slots = [];
    const days = Math.min(lastDay - firstDay + 1, MAX_FREE_DAYS);

    for (let index = 0; index < days; index += 1) {
        const dayBaseLocal = (firstDay + index) * DAY_MS;
        const windowStart = Math.max(dayBaseLocal + startOfDay - offsetMs, fromMs);
        const windowEnd = Math.min(dayBaseLocal + endOfDay - offsetMs, toMs);
        if (windowEnd <= windowStart) continue;

        let cursor = windowStart;
        for (const entry of busy) {
            if (entry.endMs <= cursor) continue;
            if (entry.startMs >= windowEnd) break;
            const busyStart = Math.max(entry.startMs, windowStart);
            const busyEnd = Math.min(entry.endMs, windowEnd);
            if (busyEnd <= busyStart) continue;
            if (busyStart > cursor) {
                pushSlot(slots, cursor, busyStart, minMs);
            }
            cursor = Math.max(cursor, busyEnd);
            if (cursor >= windowEnd) break;
        }
        if (cursor < windowEnd) pushSlot(slots, cursor, windowEnd, minMs);
    }
    return slots;
}

function pushSlot(slots, startMs, endMs, minMs) {
    const duration = endMs - startMs;
    if (duration <= 0 || duration < minMs) return;
    slots.push({
        start: new Date(startMs).toISOString(),
        end: new Date(endMs).toISOString(),
        minutes: Math.round(duration / 60_000),
    });
}

function emptyAgenda() {
    return {
        capturedAt: null,
        storedAt: null,
        rangeStart: null,
        rangeEnd: null,
        tzOffsetMinutes: null,
        calendars: [],
        events: [],
        conflicts: [],
    };
}

function isValidSnapshot(candidate) {
    if (!isPlainObject(candidate)) return false;
    if (parseInstant(candidate.capturedAt) === null) return false;
    if (!Array.isArray(candidate.calendars) || !Array.isArray(candidate.events)) return false;
    return true;
}

/**
 * @param {{
 *   dataDir: string,
 *   now?: (() => Date | number | string) | Date | number | string,
 *   proposalTtlMs?: number,
 *   log?: { warn?: Function, info?: Function },
 * }} options
 */
export function createPhoneCalendarStore({
    dataDir,
    now = () => new Date(),
    proposalTtlMs = DEFAULT_PROPOSAL_TTL_MS,
    log = console,
} = {}) {
    if (typeof dataDir !== "string" || dataDir.length === 0) {
        throw new Error("dataDir is required");
    }

    const snapshotFile = join(dataDir, SNAPSHOT_FILE);
    const proposalsFile = join(dataDir, PROPOSALS_FILE);
    const ttl = Number.isFinite(proposalTtlMs) && proposalTtlMs > 0
        ? proposalTtlMs
        : DEFAULT_PROPOSAL_TTL_MS;

    /** @type {object | null} */
    let snapshot = null;
    /** @type {object[]} insertion order: oldest first */
    const proposals = [];

    let tempCounter = 0;

    function clockMs() {
        const value = typeof now === "function" ? now() : now;
        const date = value instanceof Date ? value : new Date(value);
        const ms = date.getTime();
        return Number.isFinite(ms) ? ms : Date.now();
    }

    function clockIso() {
        return new Date(clockMs()).toISOString();
    }

    function atomicWrite(filePath, payload) {
        mkdirSync(dataDir, { recursive: true });
        tempCounter += 1;
        const tempPath = `${filePath}.${process.pid}-${tempCounter}.tmp`;
        writeFileSync(tempPath, payload, "utf8");
        try {
            renameSync(tempPath, filePath);
        } catch (err) {
            try {
                unlinkSync(tempPath);
            } catch {
                // The rename already failed; a missing temp file is fine.
            }
            throw err;
        }
    }

    function readJsonFile(filePath) {
        let raw;
        try {
            raw = readFileSync(filePath, "utf8");
        } catch (err) {
            if (err && err.code !== "ENOENT") {
                log?.warn?.(`[calendar] no se pudo leer ${filePath}: ${err.message}`);
            }
            return null;
        }
        try {
            return JSON.parse(raw);
        } catch {
            log?.warn?.(`[calendar] archivo corrupto, se ignora: ${filePath}`);
            return null;
        }
    }

    function persistSnapshot() {
        atomicWrite(
            snapshotFile,
            JSON.stringify({ version: 1, snapshot }, null, 2) + "\n",
        );
    }

    function persistProposals() {
        atomicWrite(
            proposalsFile,
            JSON.stringify({ version: 1, proposals }, null, 2) + "\n",
        );
    }

    function loadSnapshot() {
        const parsed = readJsonFile(snapshotFile);
        if (!parsed) return;
        if (isValidSnapshot(parsed.snapshot)) {
            snapshot = parsed.snapshot;
            return;
        }
        log?.warn?.("[calendar] snapshot inválido, se ignora");
    }

    function loadProposals() {
        const parsed = readJsonFile(proposalsFile);
        if (!parsed) return;
        if (!Array.isArray(parsed.proposals)) {
            log?.warn?.("[calendar] archivo sin propuestas, se ignora");
            return;
        }
        for (const candidate of parsed.proposals) {
            if (isValidProposal(candidate)) proposals.push(candidate);
        }
    }

    function isValidProposal(candidate) {
        if (!isPlainObject(candidate)) return false;
        if (!PROPOSAL_ID_PATTERN.test(String(candidate.id ?? ""))) return false;
        if (!PROPOSAL_STATUSES.includes(candidate.status)) return false;
        if (parseInstant(candidate.start) === null) return false;
        return typeof candidate.title === "string";
    }

    /**
     * Stores the normalized snapshot the phone reported. `storedAt` is the
     * server clock; everything else comes from the validated body.
     */
    function recordSnapshot(input) {
        const normalized = normalizeSnapshot(input);
        const storedAt = clockIso();
        snapshot = { ...normalized, storedAt };
        persistSnapshot();
        return {
            capturedAt: snapshot.capturedAt,
            rangeStart: snapshot.rangeStart,
            rangeEnd: snapshot.rangeEnd,
            tzOffsetMinutes: snapshot.tzOffsetMinutes,
            storedAt,
            calendarCount: snapshot.calendars.length,
            eventCount: snapshot.events.length,
        };
    }

    function getSnapshot() {
        return snapshot
            ? {
                ...snapshot,
                calendars: snapshot.calendars.map((entry) => ({ ...entry })),
                events: snapshot.events.map((entry) => ({ ...entry })),
            }
            : null;
    }

    /**
     * Events overlapping [from, to] (either bound may be null), sorted by
     * start, with deterministic conflicts among exactly those events.
     */
    function getAgenda({ from = null, to = null } = {}) {
        if (!snapshot) return emptyAgenda();
        const fromMs = from === null ? null : parseInstant(from);
        const toMs = to === null ? null : parseInstant(to);
        const events = snapshot.events
            .filter((event) => {
                const startMs = parseInstant(event.start);
                const endMs = parseInstant(event.end);
                if (startMs === null || endMs === null) return false;
                if (fromMs !== null && endMs <= fromMs) return false;
                if (toMs !== null && startMs >= toMs) return false;
                return true;
            })
            .map((event) => ({ ...event }))
            .sort((a, b) => (
                parseInstant(a.start) - parseInstant(b.start) ||
                String(a.id).localeCompare(String(b.id))
            ));
        return {
            capturedAt: snapshot.capturedAt,
            storedAt: snapshot.storedAt,
            rangeStart: snapshot.rangeStart,
            rangeEnd: snapshot.rangeEnd,
            tzOffsetMinutes: snapshot.tzOffsetMinutes,
            calendars: snapshot.calendars.map((entry) => ({ ...entry })),
            events,
            conflicts: findOverlapPairs(events),
        };
    }

    /** Free slots in [from, to] using the snapshot's offset and events. */
    function getFreeSlots({
        from,
        to,
        minMinutes = DEFAULT_FREE_MIN_MINUTES,
        dayStart = DEFAULT_FREE_DAY_START,
        dayEnd = DEFAULT_FREE_DAY_END,
    } = {}) {
        const offset = snapshot ? snapshot.tzOffsetMinutes : 0;
        return {
            from,
            to,
            minMinutes,
            dayStart,
            dayEnd,
            tzOffsetMinutes: offset,
            hasSnapshot: Boolean(snapshot),
            slots: snapshot
                ? freeSlots({
                    events: snapshot.events,
                    from,
                    to,
                    dayStart,
                    dayEnd,
                    minMinutes,
                    tzOffsetMinutes: offset,
                })
                : [],
        };
    }

    function expireProposals(referenceMs = clockMs()) {
        let changed = false;
        for (const proposal of proposals) {
            if (proposal.status !== "pending") continue;
            if (Date.parse(proposal.expiresAt) > referenceMs) continue;
            proposal.status = "expired";
            proposal.updatedAt = new Date(referenceMs).toISOString();
            changed = true;
        }
        if (changed) persistProposals();
        return changed;
    }

    function proposalConflicts(start, end, allDay) {
        if (allDay) return [];
        return conflictsWith({ start, end, allDay: false }, snapshot ? snapshot.events : []);
    }

    /**
     * Stores one PC proposal as `pending`, with the conflicts against the
     * current snapshot computed now (the phone re-checks before writing).
     */
    function createProposal(input = {}) {
        if (!isPlainObject(input)) {
            throw typedError("INVALID_PROPOSAL", "body must be a JSON object");
        }
        const title = singleLine(input.title, MAX_PROPOSAL_TITLE_CHARS);
        if (title.length === 0) {
            throw typedError("INVALID_PROPOSAL", "title is required");
        }
        const source = singleLine(input.source, MAX_PROPOSAL_SOURCE_CHARS);
        if (source.length === 0) {
            throw typedError("INVALID_PROPOSAL", "source is required");
        }
        const startMs = requireInstant(input.start, "start", "INVALID_PROPOSAL");
        let allDay = false;
        if (input.allDay !== undefined) {
            if (typeof input.allDay !== "boolean") {
                throw typedError("INVALID_PROPOSAL", "allDay must be a boolean");
            }
            allDay = input.allDay;
        }
        let endMs;
        if (allDay) {
            endMs = startMs + DAY_MS;
        } else if (input.end === undefined || input.end === null) {
            endMs = startMs + HOUR_MS;
        } else {
            endMs = requireInstant(input.end, "end", "INVALID_PROPOSAL");
            if (endMs < startMs) {
                throw typedError("INVALID_PROPOSAL", "end is before start");
            }
        }
        if (input.location !== undefined && input.location !== null &&
            typeof input.location !== "string") {
            throw typedError("INVALID_PROPOSAL", "location must be a string");
        }
        if (input.notes !== undefined && input.notes !== null && typeof input.notes !== "string") {
            throw typedError("INVALID_PROPOSAL", "notes must be a string");
        }
        if (input.calendarId !== undefined && input.calendarId !== null &&
            typeof input.calendarId !== "string") {
            throw typedError("INVALID_PROPOSAL", "calendarId must be a string");
        }

        const createdAt = clockIso();
        const start = new Date(startMs).toISOString();
        const end = new Date(endMs).toISOString();
        const proposal = {
            id: `phr-${randomBytes(6).toString("hex")}`,
            title,
            start,
            end,
            allDay,
            location: clip(input.location ?? "", MAX_PROPOSAL_LOCATION_CHARS),
            notes: clip(input.notes ?? "", MAX_PROPOSAL_NOTES_CHARS),
            calendarId: input.calendarId
                ? clip(input.calendarId.trim(), MAX_ID_CHARS)
                : null,
            source,
            status: "pending",
            conflicts: proposalConflicts(start, end, allDay),
            eventId: null,
            createdAt,
            updatedAt: createdAt,
            expiresAt: new Date(Date.parse(createdAt) + ttl).toISOString(),
        };
        proposals.push(proposal);
        persistProposals();
        return { ...proposal, conflicts: proposal.conflicts.map((entry) => ({ ...entry })) };
    }

    /** Newest first. `status` filters one state; omitted/"all" returns all. */
    function listProposals({ status = null, limit = DEFAULT_PROPOSAL_LIST_LIMIT } = {}) {
        expireProposals();
        if (status !== null && status !== undefined && status !== "all" &&
            !PROPOSAL_STATUSES.includes(status)) {
            throw typedError("INVALID_STATUS", "status must be one of: " + PROPOSAL_STATUSES.join(", "));
        }
        const safeLimit = Number.isInteger(limit) && limit > 0
            ? Math.min(limit, MAX_PROPOSAL_LIST_LIMIT)
            : DEFAULT_PROPOSAL_LIST_LIMIT;
        return proposals
            .map((proposal, index) => ({ proposal, index }))
            .filter(({ proposal }) => (
                status === null || status === undefined || status === "all" ||
                proposal.status === status
            ))
            .sort((a, b) => {
                const delta = Date.parse(b.proposal.createdAt) - Date.parse(a.proposal.createdAt);
                return delta !== 0 ? delta : b.index - a.index;
            })
            .slice(0, safeLimit)
            .map(({ proposal }) => ({
                ...proposal,
                conflicts: proposal.conflicts.map((entry) => ({ ...entry })),
            }));
    }

    /**
     * Phone reports its decision after writing (or after the owner rejected).
     * Only a pending proposal can be decided; an expired one answers 409.
     */
    function decideProposal(id, { decision, eventId = null } = {}) {
        expireProposals();
        const proposal = proposals.find((candidate) => candidate.id === id);
        if (!proposal) return { ok: false, reason: "not_found" };
        if (proposal.status === "expired") return { ok: false, reason: "expired" };
        if (proposal.status !== "pending") return { ok: false, reason: "already_decided" };
        if (decision !== "approved" && decision !== "rejected") {
            throw typedError("INVALID_DECISION", "decision must be approved or rejected");
        }
        if (eventId !== null && eventId !== undefined) {
            if (typeof eventId !== "string" || eventId.trim().length === 0) {
                throw typedError("INVALID_DECISION", "eventId must be a non-empty string");
            }
        }

        proposal.status = decision === "approved" ? "approved" : "rejected";
        proposal.eventId = decision === "approved" && eventId
            ? clip(eventId.trim(), MAX_ID_CHARS)
            : null;
        proposal.updatedAt = clockIso();
        persistProposals();
        return {
            ok: true,
            proposal: { ...proposal, conflicts: proposal.conflicts.map((entry) => ({ ...entry })) },
        };
    }

    loadSnapshot();
    loadProposals();
    expireProposals();

    return {
        recordSnapshot,
        getSnapshot,
        getAgenda,
        getFreeSlots,
        createProposal,
        listProposals,
        decideProposal,
    };
}
