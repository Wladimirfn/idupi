// Phone bubble turn routes (phone-bubble, T1).
//
// The bubble posts one free-form request and long-polls its turn. Requests are
// serialized: one agent process at a time, and a new request supersedes the
// previous queued/running one (cancelled, child process killed) so the owner
// never waits behind a request they already replaced.
//
// Split out of index.mjs for the same reason inbox-routes.mjs exists: index.mjs
// calls the handler right after requireAuth and the handler answers true when
// the path is ours. Nothing here may run before the bearer token is verified
// upstream.
//
// The agent itself is injected: T2 provides runPhoneTurn, and a build without
// it answers 503 instead of pretending to work.

import {
    DEFAULT_TURN_LIST_LIMIT,
    MAX_TURN_LIST_LIMIT,
    MAX_TURN_TEXT_CHARS,
    TURN_ID_PATTERN,
    TURN_TERMINAL_STATUSES,
} from "./phone-agent-turns.mjs";

const PREFIX = "/api/v1/phone/agent/turns";
const DEFAULT_MAX_JSON_BYTES = 64 * 1024;
const DEFAULT_TURN_WAIT_SECONDS = 25;
const MAX_TURN_WAIT_SECONDS = 30;
const MAX_ERROR_CHARS = 300;

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

/** Reads a JSON object body, rejecting as soon as the byte cap is crossed. */
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

function parseWait(raw, fallback) {
    if (raw === null || raw === "") return fallback;
    if (!/^\d+$/.test(raw)) return null;
    const value = Number(raw);
    if (!Number.isInteger(value) || value < 0 || value > MAX_TURN_WAIT_SECONDS) return null;
    return value;
}

function parseLimit(raw) {
    if (raw === null || raw === "") return DEFAULT_TURN_LIST_LIMIT;
    if (!/^\d+$/.test(raw)) return null;
    const value = Number(raw);
    if (!Number.isInteger(value) || value < 1 || value > MAX_TURN_LIST_LIMIT) return null;
    return value;
}

function shortError(err) {
    const text = String(err?.message || err || "agent failed").replace(/\s+/g, " ").trim();
    return (text || "agent failed").slice(0, MAX_ERROR_CHARS);
}

/** Anything that changes what the phone should render wakes the long poll. */
function pollMarker(turn) {
    return `${turn.updatedAt}|${turn.status}|${turn.steps.length}`;
}

/**
 * @param {{
 *   store: ReturnType<import("./phone-agent-turns.mjs").createPhoneAgentTurnStore>,
 *   runner?: { runPhoneTurn: Function, supportsPhoneTurn?: Function } | null,
 *   getConfig?: () => object,
 *   maxJsonBytes?: number,
 *   log?: { info?: Function, warn?: Function },
 * }} options
 * @returns {(req: import("node:http").IncomingMessage, res: import("node:http").ServerResponse, pathname: string) => Promise<boolean>}
 */
export function createPhoneAgentRoutes({
    store,
    runner = null,
    getConfig = () => ({}),
    maxJsonBytes = DEFAULT_MAX_JSON_BYTES,
    log = console,
} = {}) {
    // One agent process at a time. The chain never rejects: runTurnJob owns its
    // failures and a persistence error must not become an unhandled rejection.
    let queueTail = Promise.resolve();

    // Live AbortControllers by turn id, so a supersede can kill the child.
    const controllers = new Map();

    function runnerConfig() {
        try {
            const config = getConfig();
            return config && typeof config === "object" ? config : {};
        } catch {
            return {};
        }
    }

    function runnerSupports(config) {
        if (!runner || typeof runner.runPhoneTurn !== "function") return false;
        if (typeof runner.supportsPhoneTurn !== "function") return false;
        try {
            return runner.supportsPhoneTurn(config?.agent) === true;
        } catch {
            return false;
        }
    }

    function enqueueTurn(id) {
        queueTail = queueTail
            .then(() => runTurnJob(id), () => runTurnJob(id))
            .catch(() => {});
    }

    async function runTurnJob(id) {
        try {
            const queued = store.get(id);
            // Cancelled while waiting behind another turn: never spawn.
            if (!queued || queued.status !== "queued") return;

            const controller = new AbortController();
            controllers.set(id, controller);
            try {
                store.update(id, { status: "running" });
                store.addStep(id, { kind: "started", summary: "Pedido iniciado" });

                const answer = await runner.runPhoneTurn(runnerConfig(), {
                    text: queued.text,
                    signal: controller.signal,
                    onStep: (step) => {
                        try {
                            store.addStep(id, step);
                        } catch {
                            // A failed step write must not abort the turn.
                        }
                    },
                });

                const current = store.get(id);
                if (!current || current.status === "cancelled") return;
                store.update(id, { status: "done", answer: String(answer ?? ""), error: null });
                store.addStep(id, { kind: "answer", summary: "Respuesta lista" });
                log?.info?.(`[phone] turn ${id} done`);
            } catch (err) {
                const current = store.get(id);
                if (!current || current.status === "cancelled" || controller.signal.aborted) return;
                try {
                    store.update(id, { status: "failed", answer: "", error: shortError(err) });
                } catch {
                    // The file could not be written; the in-memory turn stays
                    // "running" and a restart marks it failed.
                }
                log?.info?.(`[phone] turn ${id} failed`);
            } finally {
                controllers.delete(id);
            }
        } catch {
            // Fire-and-forget: one broken turn must never stall the queue.
        }
    }

    async function createTurn(req, res) {
        let body;
        try {
            body = await readJsonBody(req, maxJsonBytes);
        } catch (err) {
            if (err && err.code === "TOO_LARGE") {
                sendError(res, 413, err.message, "TOO_LARGE");
            } else {
                sendError(res, 400, (err && err.message) || "invalid body", "BAD_REQUEST");
            }
            return;
        }
        if (!body || typeof body !== "object" || Array.isArray(body)) {
            sendError(res, 400, "body must be a JSON object", "INVALID_TEXT");
            return;
        }
        if (typeof body.text !== "string") {
            sendError(res, 400, "text must be a string", "INVALID_TEXT");
            return;
        }
        const text = body.text.trim();
        if (text.length === 0) {
            sendError(res, 400, "text is required", "INVALID_TEXT");
            return;
        }
        if (text.length > MAX_TURN_TEXT_CHARS) {
            sendError(res, 400, `text exceeds ${MAX_TURN_TEXT_CHARS} characters`, "INVALID_TEXT");
            return;
        }

        const config = runnerConfig();
        if (!runnerSupports(config)) {
            sendError(res, 503, "phone agent is not available", "AGENT_UNAVAILABLE");
            return;
        }

        // Supersede before creating: the owner replaced the request, so the old
        // one is cancelled and its child killed instead of finishing later.
        const active = store.findActive();
        if (active) {
            const wasRunning = active.status === "running";
            store.cancel(active.id);
            if (wasRunning) controllers.get(active.id)?.abort();
            log?.info?.(`[phone] turn ${active.id} cancelled`);
        }

        let turn;
        try {
            turn = store.create({ text, agent: String(config.agent ?? "") });
        } catch (err) {
            sendError(
                res,
                400,
                (err && err.message) || "invalid turn",
                (err && err.code) || "INVALID_TEXT",
            );
            return;
        }

        enqueueTurn(turn.id);
        log?.info?.(`[phone] turn ${turn.id} created`);
        sendJson(res, 202, { turn });
    }

    function listTurns(req, res) {
        const limit = parseLimit(queryValue(req, "limit"));
        if (limit === null) {
            sendError(
                res,
                400,
                `limit must be an integer between 1 and ${MAX_TURN_LIST_LIMIT}`,
                "INVALID_LIMIT",
            );
            return;
        }
        sendJson(res, 200, { turns: store.list({ limit }) });
    }

    function waitForTurn(req, res, id, waitSeconds) {
        const first = store.get(id);
        if (!first) {
            sendError(res, 404, "turn not found", "NOT_FOUND");
            return;
        }
        if (waitSeconds === 0 || TURN_TERMINAL_STATUSES.includes(first.status)) {
            sendJson(res, 200, { turn: first });
            return;
        }

        const marker = pollMarker(first);
        let settled = false;
        let timer = null;
        let unsubscribe = () => {};

        const finish = () => {
            if (settled) return;
            settled = true;
            if (timer) {
                clearTimeout(timer);
                timer = null;
            }
            unsubscribe();
            const next = store.get(id);
            if (!next) {
                sendError(res, 404, "turn not found", "NOT_FOUND");
                return;
            }
            sendJson(res, 200, { turn: next });
        };

        const check = () => {
            if (settled) return;
            const next = store.get(id);
            if (!next) {
                finish();
                return;
            }
            if (pollMarker(next) !== marker) finish();
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

        timer = setTimeout(finish, waitSeconds * 1000);
        unsubscribe = store.subscribe(check);

        // A vanished client must not keep the timer alive until it fires.
        req.on("close", onClose);
        res.on("close", onClose);
    }

    return async function handlePhoneAgentRoute(req, res, pathname) {
        if (pathname !== PREFIX && !pathname.startsWith(PREFIX + "/")) return false;
        const parts = pathname.slice(PREFIX.length).split("/").filter(Boolean);
        if (parts.length > 1) return false;

        if (parts.length === 0) {
            if (req.method === "POST") {
                await createTurn(req, res);
                return true;
            }
            if (req.method === "GET") {
                listTurns(req, res);
                return true;
            }
            methodNotAllowed(res, "GET, POST");
            return true;
        }

        const id = parts[0];
        if (!TURN_ID_PATTERN.test(id)) {
            sendError(res, 404, "turn not found", "NOT_FOUND");
            return true;
        }
        if (req.method !== "GET") {
            methodNotAllowed(res, "GET");
            return true;
        }
        const wait = parseWait(queryValue(req, "wait"), DEFAULT_TURN_WAIT_SECONDS);
        if (wait === null) {
            sendError(
                res,
                400,
                `wait must be an integer between 0 and ${MAX_TURN_WAIT_SECONDS}`,
                "INVALID_WAIT",
            );
            return true;
        }
        waitForTurn(req, res, id, wait);
        return true;
    };
}
