// Phone inbox routes (phone-inbox, T1), split out of index.mjs for the same
// reason delivery-routes.mjs exists: index.mjs calls the handler right after
// requireAuth and the handler answers true when the path is ours. Nothing here
// may run before the bearer token is verified upstream.
//
// Analysis is asynchronous and serialized: POST returns 202 while one agent
// process at a time works through the queue, so a burst of notifications never
// spawns a burst of CLIs.

import {
    AGENTS,
    EFFORTS,
} from "./inbox-agent.mjs";
import {
    DEFAULT_MAX_TEXT_CHARS,
    INBOX_APPS,
    INBOX_STATES,
    ITEM_ID_PATTERN,
} from "./inbox-store.mjs";

const PREFIX = "/api/v1/inbox";
// One notification is a few hundred bytes; 64 KiB covers long mail bodies and
// every field without letting a client buffer an arbitrary payload.
const DEFAULT_MAX_JSON_BYTES = 64 * 1024;
const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 200;
const MAX_ERROR_CHARS = 300;
const RELATED_CONTEXT_LIMIT = 5;
// App-facing counts: the first argument of str.slice is a JS index.
const MAX_TEXT_CHARS = DEFAULT_MAX_TEXT_CHARS;

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

function parseLimit(raw) {
    if (raw === null || raw === "") return DEFAULT_LIMIT;
    if (!/^\d+$/.test(raw)) return null;
    const value = Number(raw);
    if (!Number.isInteger(value) || value < 1 || value > MAX_LIMIT) return null;
    return value;
}

function shortError(err) {
    const text = String(err?.message || err || "analysis failed").replace(/\s+/g, " ").trim();
    return (text || "analysis failed").slice(0, MAX_ERROR_CHARS);
}

function optionalString(value, field) {
    if (value === undefined || value === null || value === "") return "";
    if (typeof value !== "string") {
        throw typedError("INVALID_MESSAGE", `${field} must be a string`);
    }
    return value;
}

function isoFrom(value) {
    const date = value instanceof Date ? value : new Date(value);
    return Number.isNaN(date.getTime()) ? new Date().toISOString() : date.toISOString();
}

/**
 * @param {{
 *   store: ReturnType<import("./inbox-store.mjs").createInboxStore>,
 *   runner: { run: Function },
 *   now?: () => Date | number | string,
 *   openCodeModels?: () => string[] | Promise<string[]>,
 *   maxJsonBytes?: number,
 * }} options
 * @returns {(req: import("node:http").IncomingMessage, res: import("node:http").ServerResponse, pathname: string) => Promise<boolean>}
 */
export function createInboxRoutes({
    store,
    runner,
    now = () => new Date(),
    openCodeModels = () => [],
    maxJsonBytes = DEFAULT_MAX_JSON_BYTES,
} = {}) {
    // One agent process at a time. The chain never rejects: analyze() owns its
    // failures and stores them on the item, and a persistence failure must not
    // become an unhandled rejection (Node exits on those by default).
    let queueTail = Promise.resolve();

    function enqueueAnalysis(id) {
        queueTail = queueTail
            .then(() => analyze(id), () => analyze(id))
            .catch(() => {});
    }

    function stamp() {
        return isoFrom(now());
    }

    async function analyze(id) {
        try {
            const item = store.get(id);
            if (!item) return;
            const related = store.related(item, RELATED_CONTEXT_LIMIT);
            const config = store.getConfig();
            try {
                const result = await runner.run(config, item, related);
                store.update(id, {
                    status: "ready",
                    importance: result?.importance ?? null,
                    category: result?.category ?? "",
                    summary: result?.summary ?? "",
                    suggestedReply: result?.suggestedReply ?? "",
                    error: null,
                    updatedAt: stamp(),
                });
            } catch (err) {
                try {
                    store.update(id, {
                        status: "failed",
                        error: shortError(err),
                        updatedAt: stamp(),
                    });
                } catch {
                    // The JSON file could not be written; the in-memory item
                    // still shows "analyzing" and the phone can retry later.
                }
            }
        } catch {
            // Fire-and-forget: one broken item must never stall the queue.
        }
    }

    async function createMessage(req, res) {
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
            sendError(res, 400, "body must be a JSON object", "INVALID_MESSAGE");
            return;
        }

        const app = String(body.app ?? "");
        if (!INBOX_APPS.includes(app)) {
            sendError(res, 400, "app must be one of: " + INBOX_APPS.join(", "), "INVALID_APP");
            return;
        }
        const text = typeof body.text === "string" ? body.text : "";
        if (text.trim().length === 0) {
            sendError(res, 400, "text is required", "INVALID_TEXT");
            return;
        }
        if (text.length > MAX_TEXT_CHARS) {
            sendError(res, 400, `text exceeds ${MAX_TEXT_CHARS} characters`, "INVALID_TEXT");
            return;
        }
        const notificationKey = typeof body.notificationKey === "string"
            ? body.notificationKey.trim()
            : "";
        if (notificationKey.length === 0) {
            sendError(res, 400, "notificationKey is required", "INVALID_NOTIFICATION_KEY");
            return;
        }
        if (body.postedAt !== undefined && body.postedAt !== null) {
            if (typeof body.postedAt !== "string" || Number.isNaN(Date.parse(body.postedAt))) {
                sendError(res, 400, "postedAt must be an ISO date", "INVALID_POSTED_AT");
                return;
            }
        }

        let input;
        try {
            input = {
                app,
                packageName: optionalString(body.packageName, "packageName"),
                sender: optionalString(body.sender, "sender"),
                conversation: optionalString(body.conversation, "conversation"),
                text,
                postedAt: body.postedAt ?? null,
                notificationKey,
            };
        } catch (err) {
            sendError(res, 400, (err && err.message) || "invalid message", "INVALID_MESSAGE");
            return;
        }

        let stored;
        try {
            stored = store.add(input);
        } catch (err) {
            sendError(res, 500, shortError(err), "INTERNAL");
            return;
        }

        if (stored.created) {
            enqueueAnalysis(stored.item.id);
            sendJson(res, 202, { item: stored.item });
            return;
        }
        // Dedup: same notificationKey and same text already exists.
        sendJson(res, 200, { item: stored.item });
    }

    function listMessages(req, res) {
        const limit = parseLimit(queryValue(req, "limit"));
        if (limit === null) {
            sendError(res, 400, `limit must be an integer between 1 and ${MAX_LIMIT}`, "INVALID_LIMIT");
            return;
        }
        sendJson(res, 200, { items: store.list({ limit }) });
    }

    async function changeState(req, res, id) {
        if (!ITEM_ID_PATTERN.test(id)) {
            sendError(res, 404, "inbox item not found", "NOT_FOUND");
            return;
        }
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
        const state = body && typeof body === "object" ? body.state : undefined;
        if (!INBOX_STATES.includes(state)) {
            sendError(res, 400, "state must be one of: " + INBOX_STATES.join(", "), "INVALID_STATE");
            return;
        }

        let item;
        try {
            item = store.update(id, { state, updatedAt: stamp() });
        } catch (err) {
            sendError(res, 400, (err && err.message) || "invalid state", "INVALID_STATE");
            return;
        }
        if (!item) {
            sendError(res, 404, "inbox item not found", "NOT_FOUND");
            return;
        }
        sendJson(res, 200, { item });
    }

    function retryAnalysis(req, res, id) {
        if (!ITEM_ID_PATTERN.test(id)) {
            sendError(res, 404, "inbox item not found", "NOT_FOUND");
            return;
        }
        const existing = store.get(id);
        if (!existing) {
            sendError(res, 404, "inbox item not found", "NOT_FOUND");
            return;
        }
        let item;
        try {
            item = store.update(id, {
                status: "analyzing",
                error: null,
                updatedAt: stamp(),
            });
        } catch (err) {
            sendError(res, 500, shortError(err), "INTERNAL");
            return;
        }
        enqueueAnalysis(id);
        sendJson(res, 202, { item });
    }

    function getConfig(res) {
        const config = store.getConfig();
        sendJson(res, 200, {
            agent: config.agent,
            model: config.model,
            effort: config.effort,
            agents: AGENTS,
            efforts: EFFORTS,
        });
    }

    async function putConfig(req, res) {
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
            sendError(res, 400, "body must be a JSON object", "INVALID_CONFIG");
            return;
        }
        let config;
        try {
            config = store.setConfig(body);
        } catch (err) {
            sendError(res, 400, (err && err.message) || "invalid config", "INVALID_CONFIG");
            return;
        }
        sendJson(res, 200, {
            agent: config.agent,
            model: config.model,
            effort: config.effort,
            agents: AGENTS,
            efforts: EFFORTS,
        });
    }

    async function listModels(req, res) {
        const agent = queryValue(req, "agent") || "opencode";
        if (!AGENTS.includes(agent)) {
            sendError(res, 400, "unknown agent", "INVALID_AGENT");
            return;
        }
        if (agent !== "opencode") {
            // Only the OpenCode catalog is cached by this server today.
            sendJson(res, 200, { models: [] });
            return;
        }
        let models = [];
        try {
            const lines = await openCodeModels();
            models = Array.isArray(lines)
                ? lines.filter((line) => typeof line === "string" && line.length > 0)
                : [];
        } catch {
            models = [];
        }
        sendJson(res, 200, { models });
    }

    return async function handleInboxRoute(req, res, pathname) {
        if (pathname !== PREFIX && !pathname.startsWith(PREFIX + "/")) return false;
        const parts = pathname.slice(PREFIX.length).split("/").filter(Boolean);
        if (parts.length > 2) return false;

        // Collection: list.
        if (parts.length === 0) {
            if (req.method !== "GET" && req.method !== "HEAD") {
                methodNotAllowed(res, "GET, HEAD");
                return true;
            }
            listMessages(req, res);
            return true;
        }

        // POST /inbox/messages
        if (parts.length === 1 && parts[0] === "messages") {
            if (req.method !== "POST") {
                methodNotAllowed(res, "POST");
                return true;
            }
            await createMessage(req, res);
            return true;
        }

        // GET/PUT /inbox/config
        if (parts.length === 1 && parts[0] === "config") {
            if (req.method === "GET") {
                getConfig(res);
                return true;
            }
            if (req.method === "PUT") {
                await putConfig(req, res);
                return true;
            }
            methodNotAllowed(res, "GET, PUT");
            return true;
        }

        // GET /inbox/models?agent=...
        if (parts.length === 1 && parts[0] === "models") {
            if (req.method !== "GET") {
                methodNotAllowed(res, "GET");
                return true;
            }
            await listModels(req, res);
            return true;
        }

        // POST /inbox/{id}/state and /inbox/{id}/retry
        if (parts.length === 2) {
            const [id, action] = parts;
            if (action === "state") {
                if (req.method !== "POST") {
                    methodNotAllowed(res, "POST");
                    return true;
                }
                await changeState(req, res, id);
                return true;
            }
            if (action === "retry") {
                if (req.method !== "POST") {
                    methodNotAllowed(res, "POST");
                    return true;
                }
                retryAnalysis(req, res, id);
                return true;
            }
        }

        return false;
    };
}
