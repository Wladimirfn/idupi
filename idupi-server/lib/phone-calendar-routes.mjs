// Phone calendar routes (phone-calendar, T1), split out of index.mjs for the
// same reason phone-routes.mjs exists: index.mjs calls the handler right after
// requireAuth and the handler answers true when the path is ours. Nothing here
// may run before the bearer token is verified upstream.
//
// Three flows share one store:
//   - the phone posts its calendar snapshot and the PC reads the agenda;
//   - the PC asks for free slots;
//   - the PC proposes events and the phone reports the owner's decision.

import {
    DEFAULT_FREE_DAY_END,
    DEFAULT_FREE_DAY_START,
    DEFAULT_FREE_MIN_MINUTES,
    DEFAULT_PROPOSAL_LIST_LIMIT,
    MAX_FREE_MIN_MINUTES,
    MAX_PROPOSAL_LIST_LIMIT,
    PROPOSAL_ID_PATTERN,
    PROPOSAL_STATUSES,
    parseInstant,
} from "./phone-calendar-store.mjs";

const PREFIX = "/api/v1/phone/calendar";

// The global request guard allows 1 MiB; a snapshot with 1000 events fits in
// this cap with room for descriptions, and nothing larger is expected.
const DEFAULT_MAX_JSON_BYTES = 512 * 1024;
const DAY_MS = 24 * 60 * 60 * 1000;
const DEFAULT_FREE_DAYS = 7;

function typedError(code, message) {
    const err = new Error(message);
    err.code = code;
    return err;
}

function sendJson(res, status, payload) {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(payload));
}

function sendError(res, status, message, code) {
    sendJson(res, status, { error: message, code });
}

function methodNotAllowed(res, allow) {
    res.writeHead(405, { "Content-Type": "application/json", "Allow": allow });
    res.end(JSON.stringify({ error: "method not allowed", code: "METHOD_NOT_ALLOWED" }));
}

/** Reads a JSON body, rejecting as soon as the byte cap is crossed. */
function readJsonBody(req, maxBytes) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        let size = 0;
        let settled = false;
        req.on("data", (chunk) => {
            if (settled) return;
            const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
            size += buf.length;
            if (size > maxBytes) {
                settled = true;
                reject(typedError("TOO_LARGE", `body exceeds ${maxBytes} bytes`));
                return;
            }
            chunks.push(buf);
        });
        req.on("end", () => {
            if (settled) return;
            settled = true;
            try {
                resolve(JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}"));
            } catch {
                reject(typedError("BAD_REQUEST", "invalid JSON body"));
            }
        });
        req.on("error", (err) => {
            if (settled) return;
            settled = true;
            reject(err);
        });
    });
}

function queryValue(req, key) {
    try {
        return new URL(req.url || "", "http://localhost").searchParams.get(key);
    } catch {
        return null;
    }
}

function bodyError(res, err) {
    if (err && err.code === "TOO_LARGE") {
        sendError(res, 413, err.message, "TOO_LARGE");
        return;
    }
    sendError(res, 400, (err && err.message) || "invalid body", "BAD_REQUEST");
}

/** Optional ISO query bound; `undefined` stays absent and a bad value is null. */
function optionalInstant(raw) {
    if (raw === null || raw === "") return undefined;
    return parseInstant(raw) === null ? null : raw;
}

function parseDays(raw) {
    if (raw === null || raw === "") return DEFAULT_FREE_DAYS;
    if (!/^\d+$/.test(raw)) return null;
    const value = Number(raw);
    if (!Number.isInteger(value) || value < 1 || value > 62) return null;
    return value;
}

function parseMinutes(raw) {
    if (raw === null || raw === "") return DEFAULT_FREE_MIN_MINUTES;
    if (!/^\d+$/.test(raw)) return null;
    const value = Number(raw);
    if (!Number.isInteger(value) || value < 1 || value > MAX_FREE_MIN_MINUTES) return null;
    return value;
}

function parseProposalStatus(raw) {
    if (raw === null || raw === "" || raw === "all") return null;
    return PROPOSAL_STATUSES.includes(raw) ? raw : null;
}

/**
 * @param {{
 *   store: ReturnType<import("./phone-calendar-store.mjs").createPhoneCalendarStore>,
 *   maxJsonBytes?: number,
 *   now?: () => Date | number | string,
 *   log?: { info?: Function, warn?: Function },
 * }} options
 * @returns {(req: import("node:http").IncomingMessage, res: import("node:http").ServerResponse, pathname: string) => Promise<boolean>}
 */
export function createPhoneCalendarRoutes({
    store,
    maxJsonBytes = DEFAULT_MAX_JSON_BYTES,
    now = () => new Date(),
    log = console,
} = {}) {
    function nowMs() {
        const value = now();
        const date = value instanceof Date ? value : new Date(value);
        const ms = date.getTime();
        return Number.isFinite(ms) ? ms : Date.now();
    }

    async function postSnapshot(req, res) {
        let body;
        try {
            body = await readJsonBody(req, maxJsonBytes);
        } catch (err) {
            // Only the reason is logged, never calendar content.
            log?.warn?.(`[calendar] snapshot body rejected: ${(err && err.code) || "BAD_REQUEST"}`);
            bodyError(res, err);
            return;
        }
        try {
            const snapshot = store.recordSnapshot(body);
            log?.info?.(`[calendar] snapshot stored: ${snapshot.calendarCount} calendars, ${snapshot.eventCount} events`);
            sendJson(res, 200, { ok: true, snapshot });
        } catch (err) {
            // Validation messages name the field path (events[3].end), not its value.
            log?.warn?.(`[calendar] snapshot rejected: ${(err && err.message) || "invalid snapshot"}`);
            sendError(
                res,
                400,
                (err && err.message) || "invalid snapshot",
                (err && err.code) || "INVALID_SNAPSHOT",
            );
        }
    }

    function getAgenda(req, res) {
        const from = optionalInstant(queryValue(req, "from"));
        const to = optionalInstant(queryValue(req, "to"));
        if (from === null || to === null) {
            sendError(res, 400, "from and to must be ISO-8601 dates", "INVALID_RANGE");
            return;
        }
        if (from !== undefined && to !== undefined && parseInstant(from) >= parseInstant(to)) {
            sendError(res, 400, "from must be before to", "INVALID_RANGE");
            return;
        }
        sendJson(res, 200, store.getAgenda({ from: from ?? null, to: to ?? null }));
    }

    function getFree(req, res) {
        const days = parseDays(queryValue(req, "days"));
        if (days === null) {
            sendError(res, 400, "days must be an integer between 1 and 62", "INVALID_DAYS");
            return;
        }
        const min = parseMinutes(queryValue(req, "min"));
        if (min === null) {
            sendError(
                res,
                400,
                `min must be an integer between 1 and ${MAX_FREE_MIN_MINUTES}`,
                "INVALID_MIN",
            );
            return;
        }
        const from = optionalInstant(queryValue(req, "from"));
        const to = optionalInstant(queryValue(req, "to"));
        if (from === null || to === null) {
            sendError(res, 400, "from and to must be ISO-8601 dates", "INVALID_RANGE");
            return;
        }
        // Default range: now for `days` days. The CLI asks for days; a caller
        // with an explicit range sends both bounds.
        const fromIso = from ?? new Date(nowMs()).toISOString();
        const toIso = to ?? new Date(parseInstant(fromIso) + days * DAY_MS).toISOString();
        if (parseInstant(toIso) <= parseInstant(fromIso)) {
            sendError(res, 400, "from must be before to", "INVALID_RANGE");
            return;
        }
        sendJson(res, 200, store.getFreeSlots({
            from: fromIso,
            to: toIso,
            minMinutes: min,
            dayStart: DEFAULT_FREE_DAY_START,
            dayEnd: DEFAULT_FREE_DAY_END,
        }));
    }

    async function postProposal(req, res) {
        let body;
        try {
            body = await readJsonBody(req, maxJsonBytes);
        } catch (err) {
            bodyError(res, err);
            return;
        }
        let proposal;
        try {
            proposal = store.createProposal(body);
        } catch (err) {
            sendError(
                res,
                400,
                (err && err.message) || "invalid proposal",
                (err && err.code) || "INVALID_PROPOSAL",
            );
            return;
        }
        const snapshot = store.getSnapshot();
        sendJson(res, 201, {
            proposal,
            tzOffsetMinutes: snapshot ? snapshot.tzOffsetMinutes : null,
        });
    }

    function listProposals(req, res) {
        const rawStatus = queryValue(req, "status");
        const status = parseProposalStatus(rawStatus);
        if (status === null && rawStatus !== null && rawStatus !== "" && rawStatus !== "all") {
            sendError(
                res,
                400,
                "status must be one of: " + PROPOSAL_STATUSES.join(", "),
                "INVALID_STATUS",
            );
            return;
        }
        const rawLimit = queryValue(req, "limit");
        let limit = DEFAULT_PROPOSAL_LIST_LIMIT;
        if (rawLimit !== null && rawLimit !== "") {
            if (!/^\d+$/.test(rawLimit)) {
                sendError(res, 400, `limit must be between 1 and ${MAX_PROPOSAL_LIST_LIMIT}`, "INVALID_LIMIT");
                return;
            }
            limit = Number(rawLimit);
            if (!Number.isInteger(limit) || limit < 1 || limit > MAX_PROPOSAL_LIST_LIMIT) {
                sendError(res, 400, `limit must be between 1 and ${MAX_PROPOSAL_LIST_LIMIT}`, "INVALID_LIMIT");
                return;
            }
        }
        const snapshot = store.getSnapshot();
        sendJson(res, 200, {
            proposals: store.listProposals({ status, limit }),
            tzOffsetMinutes: snapshot ? snapshot.tzOffsetMinutes : null,
        });
    }

    async function postDecision(req, res, id) {
        if (!PROPOSAL_ID_PATTERN.test(id)) {
            sendError(res, 404, "proposal not found", "NOT_FOUND");
            return;
        }
        let body;
        try {
            body = await readJsonBody(req, maxJsonBytes);
        } catch (err) {
            bodyError(res, err);
            return;
        }
        if (!body || typeof body !== "object" || Array.isArray(body)) {
            sendError(res, 400, "body must be a JSON object", "INVALID_DECISION");
            return;
        }
        let outcome;
        try {
            outcome = store.decideProposal(id, {
                decision: body.decision,
                eventId: body.eventId,
            });
        } catch (err) {
            sendError(
                res,
                400,
                (err && err.message) || "invalid decision",
                (err && err.code) || "INVALID_DECISION",
            );
            return;
        }
        if (outcome.ok) {
            sendJson(res, 200, { proposal: outcome.proposal });
            return;
        }
        switch (outcome.reason) {
            case "not_found":
                sendError(res, 404, "proposal not found", "NOT_FOUND");
                return;
            case "expired":
                sendError(res, 409, "proposal expired", "EXPIRED");
                return;
            default:
                sendError(res, 409, "proposal already decided", "ALREADY_DECIDED");
        }
    }

    return async function handlePhoneCalendarRoute(req, res, pathname) {
        if (pathname !== PREFIX && !pathname.startsWith(PREFIX + "/")) return false;
        const parts = pathname.slice(PREFIX.length).split("/").filter(Boolean);
        if (parts.length > 3) return false;

        if (parts.length === 0) {
            if (req.method === "POST") {
                await postSnapshot(req, res);
                return true;
            }
            if (req.method === "GET" || req.method === "HEAD") {
                getAgenda(req, res);
                return true;
            }
            methodNotAllowed(res, "GET, HEAD, POST");
            return true;
        }

        if (parts.length === 1 && parts[0] === "free") {
            if (req.method !== "GET" && req.method !== "HEAD") {
                methodNotAllowed(res, "GET, HEAD");
                return true;
            }
            getFree(req, res);
            return true;
        }

        if (parts[0] === "proposals") {
            if (parts.length === 1) {
                if (req.method === "POST") {
                    await postProposal(req, res);
                    return true;
                }
                if (req.method === "GET" || req.method === "HEAD") {
                    listProposals(req, res);
                    return true;
                }
                methodNotAllowed(res, "GET, HEAD, POST");
                return true;
            }
            if (parts.length === 3 && parts[2] === "decision") {
                if (req.method !== "POST") {
                    methodNotAllowed(res, "POST");
                    return true;
                }
                await postDecision(req, res, parts[1]);
                return true;
            }
        }

        return false;
    };
}
