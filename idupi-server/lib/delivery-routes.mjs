// APK delivery routes (apk-delivery, T1), split out of index.mjs for the same
// reason pty-routes.mjs exists: the monolith's route chain is not the place for
// a new lifecycle. Dispatch contract mirrors handlePtyRoute: index.mjs calls the
// handler right after requireAuth and the handler answers true when the path is
// ours. Nothing here may run before the bearer token is verified upstream.

import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";

import {
    DELIVERY_ERROR_CODES,
    ID_PATTERN,
} from "./delivery-registry.mjs";

const PREFIX = "/api/v1/deliveries";
// The POST body is only `{ "path": "..." }`; 16 KiB leaves room for every
// Windows path without letting a client buffer an arbitrary payload here.
const DEFAULT_MAX_JSON_BYTES = 16 * 1024;

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

function notFound(res) {
    sendError(res, 404, "delivery not found", "NOT_FOUND");
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

/**
 * Filenames reach the Content-Disposition header, so quotes, backslashes and
 * control characters (header injection) are stripped rather than escaped.
 * Code points above U+00FF are replaced too: Node refuses them in a header and
 * would turn the download into a 500. Latin-1 names (e.g. "versión.apk") keep
 * their accents.
 */
function safeFilename(name, id) {
    const cleaned = String(name ?? "")
        .replace(/[\u0000-\u001f\u007f-\u009f"\\\u0100-\uffff]/g, "_")
        .trim();
    return cleaned || `${id}.apk`;
}

async function sendDownload(registry, res, id, { head }) {
    const entry = registry.get(id);
    if (!entry) {
        notFound(res);
        return;
    }
    const filePath = registry.filePath(id);
    let info;
    try {
        info = filePath ? await stat(filePath) : null;
    } catch {
        info = null;
    }
    if (!info || !info.isFile()) {
        notFound(res);
        return;
    }

    const headers = {
        "Content-Type": "application/vnd.android.package-archive",
        "Content-Length": String(info.size),
        "Content-Disposition": `attachment; filename="${safeFilename(entry.name, entry.id)}"`,
        "Cache-Control": "no-store",
        "X-Content-SHA256": entry.sha256,
    };
    res.writeHead(200, headers);
    if (head) {
        res.end();
        return;
    }

    const stream = createReadStream(filePath);
    let cleanedUp = false;
    const cleanup = () => {
        if (cleanedUp) return;
        cleanedUp = true;
        stream.destroy();
    };
    // A client that hangs up mid-download must not leave the read stream (and
    // its fd) open: destroy it as soon as the response is gone.
    res.on("close", cleanup);
    res.on("error", cleanup);
    stream.on("error", (err) => {
        if (!res.writableEnded) {
            res.destroy(err);
        }
    });
    stream.pipe(res);
}

/**
 * @param {ReturnType<import("./delivery-registry.mjs").createDeliveryRegistry>} registry
 * @param {{ maxJsonBytes?: number }} [options]
 * @returns {(req: import("node:http").IncomingMessage, res: import("node:http").ServerResponse, pathname: string) => Promise<boolean>}
 */
export function createDeliveryRoutes(
    registry,
    { maxJsonBytes = DEFAULT_MAX_JSON_BYTES } = {},
) {
    return async function handleDeliveryRoute(req, res, pathname) {
        if (pathname !== PREFIX && !pathname.startsWith(PREFIX + "/")) {
            return false;
        }
        const parts = pathname.slice(PREFIX.length).split("/").filter(Boolean);
        if (parts.length > 2) return false;

        // Collection: register and list.
        if (parts.length === 0) {
            if (req.method === "GET") {
                sendJson(res, 200, { deliveries: registry.list() });
                return true;
            }
            if (req.method === "POST") {
                let body;
                try {
                    body = await readJsonBody(req, maxJsonBytes);
                } catch (err) {
                    if (err && err.code === "TOO_LARGE") {
                        sendError(res, 413, err.message, "TOO_LARGE");
                    } else {
                        sendError(
                            res,
                            400,
                            (err && err.message) || "invalid body",
                            "BAD_REQUEST",
                        );
                    }
                    return true;
                }
                if (
                    !body || typeof body !== "object" || Array.isArray(body) ||
                    typeof body.path !== "string" || body.path.trim().length === 0
                ) {
                    sendError(
                        res,
                        400,
                        "body needs a non-empty string path",
                        "INVALID_PATH",
                    );
                    return true;
                }
                try {
                    const entry = await registry.register(body.path);
                    sendJson(res, 201, entry);
                } catch (err) {
                    if (err && DELIVERY_ERROR_CODES.has(err.code)) {
                        sendError(res, 400, err.message, err.code);
                    } else {
                        sendError(
                            res,
                            500,
                            (err && err.message) || "failed to register delivery",
                            "INTERNAL",
                        );
                    }
                }
                return true;
            }
            return false;
        }

        // Download: GET streams the snapshot, HEAD answers the same headers.
        if (parts.length === 2) {
            const [id, action] = parts;
            if (action !== "download") return false;
            if (!ID_PATTERN.test(id)) {
                notFound(res);
                return true;
            }
            if (req.method === "GET") {
                await sendDownload(registry, res, id, { head: false });
                return true;
            }
            if (req.method === "HEAD") {
                await sendDownload(registry, res, id, { head: true });
                return true;
            }
            return false;
        }

        // Item: delete.
        const [id] = parts;
        if (req.method === "DELETE") {
            if (!ID_PATTERN.test(id)) {
                notFound(res);
                return true;
            }
            let removed;
            try {
                removed = await registry.remove(id);
            } catch (err) {
                sendError(
                    res,
                    500,
                    (err && err.message) || "failed to remove delivery",
                    "INTERNAL",
                );
                return true;
            }
            if (!removed) {
                notFound(res);
                return true;
            }
            sendJson(res, 200, { ok: true });
            return true;
        }
        return false;
    };
}
