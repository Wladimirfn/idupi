// GET /api/v1/os/processes and POST /api/v1/os/processes/{pid}/kill
// (app-redesign, T10). Split out of index.mjs for the same reason
// delivery-routes.mjs exists: index.mjs calls the handler right after
// requireAuth and the handler answers true when the path is ours. Nothing here
// may run before the bearer token is verified upstream.

const PREFIX = "/api/v1/os/processes";

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

/**
 * @param {ReturnType<import("./os-processes.mjs").createOsProcesses>} processes
 * @returns {(req: import("node:http").IncomingMessage, res: import("node:http").ServerResponse, pathname: string) => Promise<boolean>}
 */
export function createOsProcessesRoutes(processes) {
    return async function handleOsProcessesRoute(req, res, pathname) {
        if (pathname !== PREFIX && !pathname.startsWith(PREFIX + "/")) return false;
        const parts = pathname.slice(PREFIX.length).split("/").filter(Boolean);
        if (parts.length > 2) return false;

        // Collection: live process list.
        if (parts.length === 0) {
            if (req.method !== "GET" && req.method !== "HEAD") {
                methodNotAllowed(res, "GET, HEAD");
                return true;
            }
            let payload;
            try {
                payload = await processes.sample();
            } catch (err) {
                sendError(
                    res,
                    500,
                    (err && err.message) || "failed to sample processes",
                    "INTERNAL",
                );
                return true;
            }
            const body = JSON.stringify(payload);
            res.writeHead(200, {
                "Content-Type": "application/json",
                "Content-Length": Buffer.byteLength(body),
                // Live sample behind a 2 s server-side cache: no client cache.
                "Cache-Control": "no-store",
            });
            res.end(req.method === "HEAD" ? undefined : body);
            return true;
        }

        // Item action: kill.
        if (parts.length === 2 && parts[1] === "kill") {
            if (req.method !== "POST") {
                methodNotAllowed(res, "POST");
                return true;
            }
            if (!/^\d+$/.test(parts[0])) {
                sendError(res, 400, "pid must be a positive integer", "INVALID_PID");
                return true;
            }
            const pid = Number(parts[0]);
            if (!Number.isSafeInteger(pid) || pid <= 0) {
                sendError(res, 400, "pid must be a positive integer", "INVALID_PID");
                return true;
            }

            let result;
            try {
                result = await processes.kill(pid);
            } catch (err) {
                sendError(res, 500, (err && err.message) || "failed to end process", "INTERNAL");
                return true;
            }

            switch (result?.status) {
                case "killed":
                    sendJson(res, 200, { ok: true });
                    return true;
                case "protected":
                    sendError(res, 409, "protected process", "PROTECTED");
                    return true;
                case "gone":
                    sendError(res, 404, "process not found", "NOT_FOUND");
                    return true;
                default:
                    sendError(
                        res,
                        500,
                        (result && result.error) || "failed to end process",
                        "INTERNAL",
                    );
                    return true;
            }
        }

        return false;
    };
}
