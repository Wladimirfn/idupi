// Interactive PTY terminal routes (task T2), split out of index.mjs for the
// same reason screen-routes.mjs exists: the monolith's route chain is not the
// place for a new lifecycle. Dispatch contract mirrors handleScreenRoute:
// index.mjs calls the handler right after requireAuth and the handler answers
// true when the path is ours. Nothing here may run before the bearer token is
// verified upstream.
//
// No WebSocket on purpose: the existing chunked-stream + POST pattern
// (screen-routes.mjs) already carries binary bytes in one direction and small
// control messages in the other, over the same authenticated port.

import { MAX_BODY_BYTES } from "./request-limits.mjs";

const PREFIX = "/api/v1/pty";
const MAX_INPUT_BYTES = 256 * 1024;
// A slow consumer (mobile link, paused client) must not grow the server heap
// without bound: once one stream response has this many bytes queued unsent,
// the response is ended and the client reconnects to the ring replay.
const DEFAULT_MAX_BUFFERED_BYTES = 1024 * 1024;

function typedError(code, message) {
    const err = new Error(message);
    err.code = code;
    return err;
}

function sendJson(res, status, payload) {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(payload));
}

function notFound(res) {
    sendJson(res, 404, { status: "error", message: "not found" });
}

function sendBodyError(res, err) {
    const status = err && err.code === "TOO_LARGE" ? 413 : 400;
    sendJson(res, status, {
        status: "error",
        message: (err && err.message) || "invalid body",
    });
}

/** Reads a JSON object body with the same 1 MiB cap the server enforces. */
function readJsonBody(req, maxBytes = MAX_BODY_BYTES) {
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

/**
 * @param {import("./pty-manager.mjs").PtyManager} manager
 * @param {{ maxBufferedBytes?: number }} [options]
 * @returns {(req: import("node:http").IncomingMessage, res: import("node:http").ServerResponse, pathname: string) => Promise<boolean>}
 */
export function createPtyRoutes(
    manager,
    { maxBufferedBytes = DEFAULT_MAX_BUFFERED_BYTES } = {},
) {
    return async function handlePtyRoute(req, res, pathname) {
        if (pathname !== PREFIX && !pathname.startsWith(PREFIX + "/")) {
            return false;
        }
        const parts = pathname.slice(PREFIX.length).split("/").filter(Boolean);
        if (parts.length > 2) return false;
        const id = parts[0] || null;
        const action = parts[1] || null;

        // Collection: create and list.
        if (!id) {
            if (req.method === "GET") {
                sendJson(res, 200, { terminals: manager.list() });
                return true;
            }
            if (req.method === "POST") {
                let body;
                try {
                    body = await readJsonBody(req);
                } catch (err) {
                    sendBodyError(res, err);
                    return true;
                }
                if (!body || typeof body !== "object" || Array.isArray(body)) {
                    sendJson(res, 400, {
                        status: "error",
                        message: "body must be a JSON object",
                    });
                    return true;
                }
                try {
                    // Missing cols/rows fall back to the manager defaults; an
                    // invalid value is rejected there with BAD_REQUEST.
                    const info = await manager.create({
                        cols: body.cols,
                        rows: body.rows,
                        cwd: body.cwd,
                    });
                    sendJson(res, 201, info);
                } catch (err) {
                    if (err && err.code === "BAD_REQUEST") {
                        sendJson(res, 400, { status: "error", message: err.message });
                    } else if (err && err.code === "LIMIT") {
                        sendJson(res, 429, { status: "error", message: err.message });
                    } else {
                        sendJson(res, 500, {
                            status: "error",
                            message: (err && err.message) || "failed to start terminal",
                        });
                    }
                }
                return true;
            }
            return false;
        }

        // Chunked raw byte stream: replay buffer first, then live output.
        if (action === "stream" && req.method === "GET") {
            if (!manager.get(id)) {
                notFound(res);
                return true;
            }
            res.writeHead(200, {
                "Content-Type": "application/octet-stream",
                "Cache-Control": "no-store",
                "X-Accel-Buffering": "no",
            });
            let unsubscribe = null;
            let closed = false;
            const cleanup = () => {
                if (closed) return;
                closed = true;
                unsubscribe?.();
            };
            // Either side closing (client hung up, response finished) must drop
            // the subscriber; otherwise every reconnect leaks one. A socket
            // reset surfaces as an async 'error' on res, so it needs a listener
            // too: an unhandled one would take the whole server down.
            req.on("close", cleanup);
            res.on("close", cleanup);
            res.on("error", cleanup);
            unsubscribe = manager.subscribe(
                id,
                (chunk) => {
                    if (res.writableEnded || res.destroyed) return;
                    res.write(chunk);
                    // Backpressure: the response is buffering more than the cap
                    // for a consumer that cannot keep up (slow mobile link).
                    // Drop this response instead of buffering the whole
                    // terminal firehose in server memory; cleanup() unsubscribes
                    // first and the client reconnects into the ring replay.
                    if (res.writableLength > maxBufferedBytes) {
                        cleanup();
                        if (!res.writableEnded && !res.destroyed) res.end();
                    }
                },
                () => {
                    cleanup();
                    if (!res.writableEnded && !res.destroyed) res.end();
                },
            );
            // The replay runs synchronously inside subscribe(): if it already
            // closed this response (backpressure cap), the unsubscribe returned
            // just now never took effect, so run it here to drop the
            // subscriber that subscribe registered after the replay.
            if (closed) unsubscribe();
            return true;
        }

        // Keystrokes / pasted text, as a UTF-8 string.
        if (action === "input" && req.method === "POST") {
            let body;
            try {
                body = await readJsonBody(req);
            } catch (err) {
                sendBodyError(res, err);
                return true;
            }
            if (
                !body || typeof body !== "object" || Array.isArray(body) ||
                typeof body.data !== "string"
            ) {
                sendJson(res, 400, {
                    status: "error",
                    message: "body needs a string data field",
                });
                return true;
            }
            if (Buffer.byteLength(body.data, "utf8") > MAX_INPUT_BYTES) {
                sendJson(res, 413, {
                    status: "error",
                    message: `input exceeds ${MAX_INPUT_BYTES} bytes`,
                });
                return true;
            }
            if (!manager.get(id)) {
                notFound(res);
                return true;
            }
            // A host that died between the check and the write drops the bytes:
            // the stream already told the client the terminal exited.
            manager.write(id, body.data);
            res.writeHead(204);
            res.end();
            return true;
        }

        if (action === "resize" && req.method === "POST") {
            let body;
            try {
                body = await readJsonBody(req);
            } catch (err) {
                sendBodyError(res, err);
                return true;
            }
            if (!body || typeof body !== "object" || Array.isArray(body)) {
                sendJson(res, 400, {
                    status: "error",
                    message: "body must be a JSON object",
                });
                return true;
            }
            if (!manager.get(id)) {
                notFound(res);
                return true;
            }
            try {
                manager.resize(id, body.cols, body.rows);
            } catch (err) {
                if (err && err.code === "BAD_REQUEST") {
                    sendJson(res, 400, { status: "error", message: err.message });
                    return true;
                }
                sendJson(res, 500, {
                    status: "error",
                    message: (err && err.message) || "resize failed",
                });
                return true;
            }
            res.writeHead(204);
            res.end();
            return true;
        }

        if (action === null && req.method === "DELETE") {
            const killed = await manager.kill(id);
            if (!killed) {
                notFound(res);
                return true;
            }
            sendJson(res, 200, { status: "ok" });
            return true;
        }

        return false;
    };
}
