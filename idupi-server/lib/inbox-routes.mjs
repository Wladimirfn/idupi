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
    INBOX_STATES,
    ITEM_ID_PATTERN,
    MAX_APP_LABEL_CHARS,
    isValidAppId,
} from "./inbox-store.mjs";
import {
    buildDigestPrompt,
    digestInputHash,
    digestKey,
    emptyDigest,
    parseDigestResult,
    selectDigestItems,
} from "./inbox-digest.mjs";
import { conflictsWith } from "./phone-calendar-store.mjs";

const PREFIX = "/api/v1/inbox";
// One notification is a few hundred bytes; 64 KiB covers long mail bodies and
// every field without letting a client buffer an arbitrary payload.
const DEFAULT_MAX_JSON_BYTES = 64 * 1024;
const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 200;
const MAX_ERROR_CHARS = 300;
const MAX_CONVERSATION_CHARS = 200;
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

/** Required, trimmed conversation name for the digest and bulk-state routes. */
function requiredConversation(body) {
    if (!body || typeof body !== "object" || Array.isArray(body)) {
        throw typedError("INVALID_MESSAGE", "body must be a JSON object");
    }
    const raw = typeof body.conversation === "string" ? body.conversation.trim() : "";
    if (raw.length === 0) {
        throw typedError("INVALID_CONVERSATION", "conversation is required");
    }
    return raw.slice(0, MAX_CONVERSATION_CHARS);
}

/** App id shared by the digest and bulk-state routes. */
function requiredApp(body) {
    if (!body || typeof body !== "object" || Array.isArray(body)) {
        throw typedError("INVALID_MESSAGE", "body must be a JSON object");
    }
    const app = typeof body.app === "string" ? body.app : "";
    if (!isValidAppId(app)) {
        throw typedError(
            "INVALID_APP",
            "app must be an id or package name (letters, digits, _ and ., at most 120)",
        );
    }
    return app;
}

/** Wire shape of a digest record; the cache hash stays server-side. */
function publicDigest(record) {
    return {
        app: record.app,
        conversation: record.conversation,
        itemCount: record.itemCount,
        summary: record.summary,
        pendingForOwner: record.pendingForOwner,
        suggestedReply: record.suggestedReply,
        status: record.status,
        error: record.error,
        updatedAt: record.updatedAt,
    };
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
    digestStore = null,
    calendarStore = null,
    now = () => new Date(),
    openCodeModels = () => [],
    maxJsonBytes = DEFAULT_MAX_JSON_BYTES,
} = {}) {
    // One agent process at a time. The chain never rejects: analyze() owns its
    // failures and stores them on the item, and a persistence failure must not
    // become an unhandled rejection (Node exits on those by default).
    let queueTail = Promise.resolve();

    /** Conversation keys with a digest queued or running; avoids a second run. */
    const digestJobs = new Set();

    /**
     * Existing events the extracted event overlaps, computed from the latest
     * phone snapshot. No snapshot (or no event) means no warnings, never an
     * error: a missing agenda cannot fail the analysis.
     */
    function calendarEventConflicts(event) {
        if (!event || !calendarStore) return [];
        let snapshot = null;
        try {
            snapshot = calendarStore.getSnapshot();
        } catch {
            return [];
        }
        if (!snapshot) return [];
        try {
            return conflictsWith(event, snapshot.events)
                .map(({ title, start, end }) => ({ title, start, end }))
                .slice(0, 20);
        } catch {
            return [];
        }
    }

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
                const event = result?.event ?? null;
                store.update(id, {
                    status: "ready",
                    importance: result?.importance ?? null,
                    category: result?.category ?? "",
                    summary: result?.summary ?? "",
                    suggestedReply: result?.suggestedReply ?? "",
                    event,
                    eventConflicts: calendarEventConflicts(event),
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

        const app = typeof body.app === "string" ? body.app : "";
        if (!isValidAppId(app)) {
            sendError(
                res,
                400,
                "app must be an id or package name (letters, digits, _ and ., at most 120)",
                "INVALID_APP",
            );
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
        // inbox-management T4: a silenced conversation uploads with
        // digestOnly so the per-message agent never runs for it.
        if (body.digestOnly !== undefined && typeof body.digestOnly !== "boolean") {
            sendError(res, 400, "digestOnly must be a boolean", "INVALID_MESSAGE");
            return;
        }

        let input;
        try {
            input = {
                app,
                appLabel: optionalString(body.appLabel, "appLabel")
                    .trim()
                    .slice(0, MAX_APP_LABEL_CHARS),
                packageName: optionalString(body.packageName, "packageName"),
                sender: optionalString(body.sender, "sender"),
                conversation: optionalString(body.conversation, "conversation"),
                text,
                postedAt: body.postedAt ?? null,
                notificationKey,
                digestOnly: body.digestOnly === true,
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
            // A digest-only item is already "ready"; the per-message agent is
            // skipped on purpose (conversation digest runs only when asked).
            if (input.digestOnly !== true) enqueueAnalysis(stored.item.id);
            sendJson(res, 202, { item: stored.item });
            return;
        }
        // Dedup: same notificationKey and same text already exists.
        sendJson(res, 200, { item: stored.item });
    }

    // --- Conversation digest (inbox-management T4) ---

    /** Sends one JSON body back, mapping transport/shape errors to 4xx. */
    async function readDigestBody(req, res) {
        try {
            const body = await readJsonBody(req, maxJsonBytes);
            if (!body || typeof body !== "object" || Array.isArray(body)) {
                sendError(res, 400, "body must be a JSON object", "INVALID_MESSAGE");
                return null;
            }
            return body;
        } catch (err) {
            if (err && err.code === "TOO_LARGE") {
                sendError(res, 413, err.message, "TOO_LARGE");
            } else {
                sendError(res, 400, (err && err.message) || "invalid body", "BAD_REQUEST");
            }
            return null;
        }
    }

    async function runDigest(app, conversation, inputHash, items) {
        const key = digestKey(app, conversation);
        try {
            const config = store.getConfig();
            const prompt = buildDigestPrompt({ app, conversation, items });
            try {
                if (typeof runner?.runPrompt !== "function") {
                    throw new Error("agent runner cannot run digests");
                }
                const stdout = await runner.runPrompt(config, prompt);
                const parsed = parseDigestResult(config.agent, stdout);
                digestStore.set(app, conversation, {
                    itemCount: items.length,
                    summary: parsed.summary,
                    pendingForOwner: parsed.pendingForOwner,
                    suggestedReply: parsed.suggestedReply,
                    status: "ready",
                    error: null,
                    updatedAt: stamp(),
                    inputHash,
                });
            } catch (err) {
                digestStore.set(app, conversation, {
                    itemCount: items.length,
                    summary: "",
                    pendingForOwner: [],
                    suggestedReply: "",
                    status: "failed",
                    error: shortError(err),
                    updatedAt: stamp(),
                    inputHash,
                });
            }
        } catch {
            // A persistence failure must not stall the serial queue.
        } finally {
            digestJobs.delete(key);
        }
    }

    function enqueueDigest(app, conversation, inputHash, items) {
        queueTail = queueTail
            .then(
                () => runDigest(app, conversation, inputHash, items),
                () => runDigest(app, conversation, inputHash, items),
            )
            .catch(() => {});
    }

    async function postDigest(req, res) {
        if (!digestStore) {
            sendError(res, 503, "digest is not configured", "DIGEST_UNAVAILABLE");
            return;
        }
        const body = await readDigestBody(req, res);
        if (!body) return;

        let app;
        let conversation;
        try {
            app = requiredApp(body);
            conversation = requiredConversation(body);
        } catch (err) {
            sendError(res, 400, err.message, err.code || "INVALID_MESSAGE");
            return;
        }

        const items = store.openByConversation(app, conversation);
        if (items.length === 0) {
            try {
                digestStore.remove(app, conversation);
            } catch (err) {
                sendError(res, 500, shortError(err), "INTERNAL");
                return;
            }
            sendJson(res, 200, { digest: emptyDigest(app, conversation) });
            return;
        }

        const selected = selectDigestItems(items);
        const inputHash = digestInputHash(selected);
        const key = digestKey(app, conversation);

        let cached = null;
        try {
            cached = digestStore.get(app, conversation);
        } catch {
            cached = null;
        }
        if (cached && cached.inputHash === inputHash && cached.status === "ready") {
            sendJson(res, 200, { digest: publicDigest(cached) });
            return;
        }
        if (cached && cached.inputHash === inputHash && cached.status === "analyzing") {
            sendJson(res, 202, { digest: publicDigest(cached) });
            return;
        }
        // A job for this exact snapshot is already queued behind the analysis
        // queue: report analyzing instead of stacking a second run.
        if (cached && cached.inputHash === inputHash && digestJobs.has(key)) {
            sendJson(res, 202, { digest: publicDigest(cached) });
            return;
        }

        const analyzing = {
            app,
            conversation,
            itemCount: selected.length,
            summary: "",
            pendingForOwner: [],
            suggestedReply: "",
            status: "analyzing",
            error: null,
            updatedAt: stamp(),
            inputHash,
        };
        try {
            digestStore.set(app, conversation, analyzing);
        } catch (err) {
            sendError(res, 500, shortError(err), "INTERNAL");
            return;
        }
        digestJobs.add(key);
        enqueueDigest(app, conversation, inputHash, selected);
        sendJson(res, 202, { digest: publicDigest(analyzing) });
    }

    function getDigest(req, res) {
        if (!digestStore) {
            sendError(res, 503, "digest is not configured", "DIGEST_UNAVAILABLE");
            return;
        }
        const rawApp = queryValue(req, "app") ?? "";
        if (!isValidAppId(rawApp)) {
            sendError(res, 400, "app must be an id or package name", "INVALID_APP");
            return;
        }
        const conversation = (queryValue(req, "conversation") ?? "").trim()
            .slice(0, MAX_CONVERSATION_CHARS);
        if (conversation.length === 0) {
            sendError(res, 400, "conversation is required", "INVALID_CONVERSATION");
            return;
        }

        let stored = null;
        try {
            stored = digestStore.get(rawApp, conversation);
        } catch {
            stored = null;
        }
        if (stored) {
            sendJson(res, 200, { digest: publicDigest(stored) });
            return;
        }
        const openCount = store.openByConversation(rawApp, conversation).length;
        sendJson(res, 200, { digest: emptyDigest(rawApp, conversation, openCount) });
    }

    async function postConversationState(req, res) {
        const body = await readDigestBody(req, res);
        if (!body) return;

        let app;
        let conversation;
        try {
            app = requiredApp(body);
            conversation = requiredConversation(body);
        } catch (err) {
            sendError(res, 400, err.message, err.code || "INVALID_MESSAGE");
            return;
        }
        const state = body.state;
        if (state !== "handled" && state !== "dismissed") {
            sendError(res, 400, "state must be handled or dismissed", "INVALID_STATE");
            return;
        }

        let closed;
        try {
            closed = store.closeConversation(app, conversation, state, stamp());
        } catch (err) {
            sendError(res, 400, (err && err.message) || "invalid state", "INVALID_STATE");
            return;
        }
        // Closing every open item leaves nothing to summarize: the cached
        // digest (and any prompt text it may hold) is dropped.
        if (digestStore) {
            try {
                digestStore.remove(app, conversation);
            } catch {
                // The state change already happened; a stale digest is pruned
                // on the next request or restart.
            }
        }
        sendJson(res, 200, { closed: closed.length, items: closed });
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

    // A restart keeps the digest file but the inbox may have changed while the
    // server was off (closed items, pruned items). Drop records that no longer
    // have an open conversation.
    if (digestStore) {
        try {
            digestStore.pruneExcept(store.openConversationKeys());
        } catch {
            // A corrupt digest file is already ignored by the store.
        }
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

        // GET/POST /inbox/digest (inbox-management T4)
        if (parts.length === 1 && parts[0] === "digest") {
            if (req.method === "POST") {
                await postDigest(req, res);
                return true;
            }
            if (req.method === "GET" || req.method === "HEAD") {
                getDigest(req, res);
                return true;
            }
            methodNotAllowed(res, "GET, HEAD, POST");
            return true;
        }

        // POST /inbox/conversation/state: close every open item of one chat.
        if (parts.length === 2 && parts[0] === "conversation" && parts[1] === "state") {
            if (req.method !== "POST") {
                methodNotAllowed(res, "POST");
                return true;
            }
            await postConversationState(req, res);
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
