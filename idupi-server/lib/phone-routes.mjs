// Phone agent routes (phone-agent, T1), split out of index.mjs for the same
// reason inbox-routes.mjs exists: index.mjs calls the handler right after
// requireAuth and the handler answers true when the path is ours. Nothing here
// may run before the bearer token is verified upstream.
//
// Two long-poll endpoints share one rule: a timer and a subscription are
// always cleared together, so a disconnected client or a superseded phone poll
// never leaves a live timer behind.

import {
    COMMAND_ID_PATTERN,
    DEFAULT_COMMAND_LIST_LIMIT,
    DEFAULT_MAX_RESULT_BYTES,
    DEFAULT_MAX_STATUS_BYTES,
    MAX_COMMAND_LIST_LIMIT,
} from "./phone-store.mjs";
import {
    APPROVAL_ID_PATTERN,
    APPROVAL_STATES,
    DEFAULT_APPROVAL_LIST_LIMIT,
    MAX_APPROVAL_LIST_LIMIT,
} from "./phone-approval-store.mjs";
import { TURN_GRANT_HEADER } from "./phone-turn-grants.mjs";

const PREFIX = "/api/v1/phone";

const DEFAULT_POLL_WAIT_SECONDS = 25;
const DEFAULT_RESULT_WAIT_SECONDS = 30;
const MAX_WAIT_SECONDS = 30;

function typedError(code, message) {
    const err = new Error(message);
    err.code = code;
    return err;
}

function sendJson(res, status, payload) {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(payload));
}

function sendNoContent(res) {
    res.writeHead(204);
    res.end();
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

/**
 * Seconds a long poll may stay open. `minSeconds` is 1 for the phone poll and
 * 0 for the PC result wait, where 0 means "answer right now".
 */
function parseWait(raw, fallback, minSeconds = 1) {
    if (raw === null || raw === "") return fallback;
    if (!/^\d+$/.test(raw)) return null;
    const value = Number(raw);
    if (!Number.isInteger(value) || value < minSeconds || value > MAX_WAIT_SECONDS) return null;
    return value;
}

function parseLimit(raw) {
    if (raw === null || raw === "") return DEFAULT_COMMAND_LIST_LIMIT;
    if (!/^\d+$/.test(raw)) return null;
    const value = Number(raw);
    if (!Number.isInteger(value) || value < 1 || value > MAX_COMMAND_LIST_LIMIT) return null;
    return value;
}

function parseApprovalState(raw) {
    if (raw === null || raw === "") return "pending";
    if (raw === "all") return "all";
    return APPROVAL_STATES.includes(raw) ? raw : null;
}

function parseApprovalLimit(raw) {
    if (raw === null || raw === "") return DEFAULT_APPROVAL_LIST_LIMIT;
    if (!/^\d+$/.test(raw)) return null;
    const value = Number(raw);
    if (!Number.isInteger(value) || value < 1 || value > MAX_APPROVAL_LIST_LIMIT) return null;
    return value;
}

function isSettled(command) {
    return command.state === "done" || command.state === "failed" || command.state === "expired";
}

function bodyError(res, err) {
    if (err && err.code === "TOO_LARGE") {
        sendError(res, 413, err.message, "TOO_LARGE");
        return;
    }
    sendError(res, 400, (err && err.message) || "invalid body", "BAD_REQUEST");
}

/**
 * @param {{
 *   store: ReturnType<import("./phone-store.mjs").createPhoneStore>,
 *   turnGrants?: ReturnType<import("./phone-turn-grants.mjs").createPhoneTurnGrants> | null,
 *   maxStatusBytes?: number,
 *   maxResultBytes?: number,
 *   log?: { info?: Function, warn?: Function },
 * }} options
 * @returns {(req: import("node:http").IncomingMessage, res: import("node:http").ServerResponse, pathname: string) => Promise<boolean>}
 */
export function createPhoneRoutes({
    store,
    turnGrants = null,
    maxStatusBytes = DEFAULT_MAX_STATUS_BYTES,
    maxResultBytes = DEFAULT_MAX_RESULT_BYTES,
    log = console,
} = {}) {
    // Only one phone poller is expected. A new poll supersedes the parked one
    // and resolves it with 204, including its timer and subscription.
    let activePoll = null;

    async function postStatus(req, res) {
        let body;
        try {
            body = await readJsonBody(req, maxStatusBytes);
        } catch (err) {
            bodyError(res, err);
            return;
        }
        try {
            const latest = store.recordStatus(body);
            sendJson(res, 200, { ok: true, latest });
        } catch (err) {
            if (err && err.code === "TOO_LARGE") {
                sendError(res, 413, err.message, "TOO_LARGE");
                return;
            }
            sendError(res, 400, (err && err.message) || "invalid status", "INVALID_STATUS");
        }
    }

    function getStatus(res) {
        sendJson(res, 200, store.getStatusInfo());
    }

    function listCommands(req, res) {
        const limit = parseLimit(queryValue(req, "limit"));
        if (limit === null) {
            sendError(
                res,
                400,
                `limit must be an integer between 1 and ${MAX_COMMAND_LIST_LIMIT}`,
                "INVALID_LIMIT",
            );
            return;
        }
        sendJson(res, 200, { commands: store.listCommands({ limit }) });
    }

    async function createCommand(req, res) {
        let body;
        try {
            body = await readJsonBody(req, maxStatusBytes);
        } catch (err) {
            bodyError(res, err);
            return;
        }
        if (!body || typeof body !== "object" || Array.isArray(body)) {
            sendError(res, 400, "body must be a JSON object", "INVALID_COMMAND");
            return;
        }
        let command;
        try {
            // The only source of an owner turn is the per-turn secret the
            // runner gave the CLI child. Resolve it server-side against the
            // in-memory grants of queued/running turns; the body's requestedBy
            // is a label, never authority, and an unknown/revoked secret falls
            // back to the normal approval gate.
            const ownerTurnId = turnGrants
                ? turnGrants.turnFor(req.headers[TURN_GRANT_HEADER])
                : null;
            command = store.createCommand({
                type: body.type,
                args: body.args,
                requestedBy: body.requestedBy,
                ownerTurnId,
            });
        } catch (err) {
            sendError(
                res,
                400,
                (err && err.message) || "invalid command",
                (err && err.code) || "INVALID_COMMAND",
            );
            return;
        }
        sendJson(res, 201, { command });
    }

    /**
     * GET /commands/next: deliver the oldest pending command or hold the
     * connection for up to `wait` seconds and answer 204.
     */
    function pollNext(req, res, waitSeconds) {
        // Supersede the parked poll before touching the queue: the new poller
        // replaces the old one instead of competing with it.
        const previous = activePoll;
        activePoll = null;
        if (previous) previous.settle(null);

        const first = store.takeNext();
        if (first) {
            sendJson(res, 200, { command: first });
            return;
        }

        let settled = false;
        let timer = null;
        let unsubscribe = () => {};
        const waiter = { settle: null };

        const settle = (next) => {
            if (settled) return;
            settled = true;
            if (timer) {
                clearTimeout(timer);
                timer = null;
            }
            unsubscribe();
            if (activePoll === waiter) activePoll = null;
            if (next) {
                sendJson(res, 200, { command: next });
                return;
            }
            if (!res.writableEnded && !res.destroyed) sendNoContent(res);
        };
        waiter.settle = settle;

        const onClose = () => {
            if (settled) return;
            settled = true;
            if (timer) {
                clearTimeout(timer);
                timer = null;
            }
            unsubscribe();
            if (activePoll === waiter) activePoll = null;
        };

        timer = setTimeout(() => {
            settle(store.takeNext());
        }, waitSeconds * 1000);
        unsubscribe = store.subscribe(() => {
            if (settled) return;
            const next = store.takeNext();
            if (next) settle(next);
        });
        activePoll = waiter;

        // A vanished client must not keep the timer alive until it fires.
        req.on("close", onClose);
        res.on("close", onClose);
    }

    /** GET /commands/{id}: answer now, or wait until a terminal state. */
    function waitForCommand(req, res, id, waitSeconds) {
        const current = store.getCommand(id);
        if (!current) {
            sendError(res, 404, "command not found", "NOT_FOUND");
            return;
        }
        if (isSettled(current) || waitSeconds === 0) {
            sendJson(res, 200, { command: current });
            return;
        }

        let settled = false;
        let timer = null;
        let unsubscribe = () => {};

        const finish = (command) => {
            if (settled) return;
            settled = true;
            if (timer) {
                clearTimeout(timer);
                timer = null;
            }
            unsubscribe();
            sendJson(res, 200, { command });
        };

        const check = () => {
            if (settled) return;
            const next = store.getCommand(id);
            if (next && isSettled(next)) finish(next);
        };

        const onClose = () => {
            if (settled) return;
            settled = true;
            if (timer) {
                clearTimeout(timer);
                timer = null;
            }
            unsubscribe();
        };

        timer = setTimeout(() => {
            const next = store.getCommand(id);
            if (next) {
                finish(next);
                return;
            }
            if (settled) return;
            settled = true;
            unsubscribe();
            sendError(res, 404, "command not found", "NOT_FOUND");
        }, waitSeconds * 1000);
        unsubscribe = store.subscribe(check);

        req.on("close", onClose);
        res.on("close", onClose);
    }

    async function postResult(req, res, id) {
        let body;
        try {
            body = await readJsonBody(req, maxResultBytes);
        } catch (err) {
            bodyError(res, err);
            return;
        }
        if (!body || typeof body !== "object" || Array.isArray(body)) {
            sendError(res, 400, "body must be a JSON object", "INVALID_RESULT");
            return;
        }

        let outcome;
        try {
            outcome = store.finishCommand(id, {
                ok: body.ok,
                result: body.result,
                error: body.error,
            });
        } catch (err) {
            if (err && err.code === "TOO_LARGE") {
                sendError(res, 413, err.message, "TOO_LARGE");
                return;
            }
            sendError(res, 400, (err && err.message) || "invalid result", "INVALID_RESULT");
            return;
        }

        if (outcome.ok) {
            sendJson(res, 200, { command: outcome.command });
            return;
        }
        switch (outcome.reason) {
            case "not_found":
                sendError(res, 404, "command not found", "NOT_FOUND");
                return;
            case "finished":
                sendError(res, 409, "command already finished", "ALREADY_FINISHED");
                return;
            case "expired":
                sendError(res, 409, "command expired", "EXPIRED");
                return;
            default:
                sendError(res, 409, "command is not finishable", "CONFLICT");
        }
    }

    function listApprovals(req, res) {
        const state = parseApprovalState(queryValue(req, "state"));
        if (state === null) {
            sendError(
                res,
                400,
                "state must be one of: " + APPROVAL_STATES.join(", "),
                "INVALID_STATUS",
            );
            return;
        }
        const limit = parseApprovalLimit(queryValue(req, "limit"));
        if (limit === null) {
            sendError(
                res,
                400,
                `limit must be an integer between 1 and ${MAX_APPROVAL_LIST_LIMIT}`,
                "INVALID_LIMIT",
            );
            return;
        }
        sendJson(res, 200, { approvals: store.listApprovals({ state, limit }) });
    }

    function getApproval(res, id) {
        const approval = store.getApproval(id);
        if (!approval) {
            sendError(res, 404, "approval not found", "NOT_FOUND");
            return;
        }
        sendJson(res, 200, { approval });
    }

    async function postApprovalDecision(req, res, id) {
        if (!APPROVAL_ID_PATTERN.test(id)) {
            sendError(res, 404, "approval not found", "NOT_FOUND");
            return;
        }
        let body;
        try {
            body = await readJsonBody(req, maxStatusBytes);
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
            outcome = store.decideApproval(id, body.decision);
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
            // Ids only: the target text and the command never enter a log line.
            log?.info?.(`[phone] approval ${id} ${outcome.approval.state}`);
            sendJson(res, 200, { approval: outcome.approval });
            return;
        }
        switch (outcome.reason) {
            case "not_found":
                sendError(res, 404, "approval not found", "NOT_FOUND");
                return;
            case "expired":
                sendError(res, 409, "approval expired", "EXPIRED");
                return;
            default:
                sendError(res, 409, "approval already decided", "ALREADY_DECIDED");
        }
    }

    return async function handlePhoneRoute(req, res, pathname) {
        if (pathname !== PREFIX && !pathname.startsWith(PREFIX + "/")) return false;
        const parts = pathname.slice(PREFIX.length).split("/").filter(Boolean);
        if (parts.length > 3) return false;

        // Root alias: the status summary.
        if (parts.length === 0) {
            if (req.method !== "GET") {
                methodNotAllowed(res, "GET");
                return true;
            }
            getStatus(res);
            return true;
        }

        if (parts[0] === "status" && parts.length === 1) {
            if (req.method === "GET") {
                getStatus(res);
                return true;
            }
            if (req.method === "POST") {
                await postStatus(req, res);
                return true;
            }
            methodNotAllowed(res, "GET, POST");
            return true;
        }

        if (parts[0] === "approvals") {
            if (parts.length === 1) {
                if (req.method === "GET") {
                    listApprovals(req, res);
                    return true;
                }
                methodNotAllowed(res, "GET");
                return true;
            }
            const approvalId = parts[1];
            if (!APPROVAL_ID_PATTERN.test(approvalId)) {
                sendError(res, 404, "approval not found", "NOT_FOUND");
                return true;
            }
            if (parts.length === 2) {
                if (req.method !== "GET") {
                    methodNotAllowed(res, "GET");
                    return true;
                }
                getApproval(res, approvalId);
                return true;
            }
            if (parts.length === 3 && parts[2] === "decision") {
                if (req.method !== "POST") {
                    methodNotAllowed(res, "POST");
                    return true;
                }
                await postApprovalDecision(req, res, approvalId);
                return true;
            }
        }

        if (parts[0] === "commands") {
            if (parts.length === 1) {
                if (req.method === "GET") {
                    listCommands(req, res);
                    return true;
                }
                if (req.method === "POST") {
                    await createCommand(req, res);
                    return true;
                }
                methodNotAllowed(res, "GET, POST");
                return true;
            }

            if (parts.length === 2 && parts[1] === "next") {
                if (req.method !== "GET") {
                    methodNotAllowed(res, "GET");
                    return true;
                }
                const wait = parseWait(
                    queryValue(req, "wait"),
                    DEFAULT_POLL_WAIT_SECONDS,
                );
                if (wait === null) {
                    sendError(
                        res,
                        400,
                        `wait must be an integer between 1 and ${MAX_WAIT_SECONDS}`,
                        "INVALID_WAIT",
                    );
                    return true;
                }
                pollNext(req, res, wait);
                return true;
            }

            const id = parts[1];
            if (!COMMAND_ID_PATTERN.test(id)) {
                sendError(res, 404, "command not found", "NOT_FOUND");
                return true;
            }

            if (parts.length === 2) {
                if (req.method !== "GET") {
                    methodNotAllowed(res, "GET");
                    return true;
                }
                const wait = parseWait(
                    queryValue(req, "wait"),
                    DEFAULT_RESULT_WAIT_SECONDS,
                    0,
                );
                if (wait === null) {
                    sendError(
                        res,
                        400,
                        `wait must be an integer between 0 and ${MAX_WAIT_SECONDS}`,
                        "INVALID_WAIT",
                    );
                    return true;
                }
                waitForCommand(req, res, id, wait);
                return true;
            }

            if (parts.length === 3 && parts[2] === "result") {
                if (req.method !== "POST") {
                    methodNotAllowed(res, "POST");
                    return true;
                }
                await postResult(req, res, id);
                return true;
            }
        }

        return false;
    };
}
