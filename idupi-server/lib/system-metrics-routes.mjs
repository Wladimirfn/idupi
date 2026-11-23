// GET /api/v1/system/metrics (app-redesign, T3), split out of index.mjs for
// the same reason delivery-routes.mjs exists: the monolith's route chain is
// not the place for a new lifecycle. Dispatch contract mirrors
// handleDeliveryRoute: index.mjs calls the handler right after requireAuth and
// the handler answers true when the path is ours. Nothing here may run before
// the bearer token is verified upstream.

const PREFIX = "/api/v1/system/metrics";

function sendJson(res, status, payload) {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(payload));
}

/**
 * @param {ReturnType<import("./system-metrics.mjs").createSystemMetrics>} metrics
 * @returns {(req: import("node:http").IncomingMessage, res: import("node:http").ServerResponse, pathname: string) => Promise<boolean>}
 */
export function createSystemMetricsRoutes(metrics) {
    return async function handleSystemMetricsRoute(req, res, pathname) {
        if (pathname !== PREFIX) return false;

        // Read-only surface: the dashboard polls it, so anything mutating the
        // connection is rejected instead of silently treated as a GET.
        if (req.method !== "GET" && req.method !== "HEAD") {
            res.writeHead(405, {
                "Content-Type": "application/json",
                "Allow": "GET, HEAD",
            });
            res.end(JSON.stringify({ error: "method not allowed", code: "METHOD_NOT_ALLOWED" }));
            return true;
        }

        let payload;
        try {
            payload = await metrics.sample();
        } catch (err) {
            sendJson(res, 500, {
                error: (err && err.message) || "failed to sample system metrics",
                code: "INTERNAL",
            });
            return true;
        }

        const body = JSON.stringify(payload);
        res.writeHead(200, {
            "Content-Type": "application/json",
            "Content-Length": Buffer.byteLength(body),
            // Live sample: a cached dashboard reading must not outlive the
            // ~1.5 s server-side sample window.
            "Cache-Control": "no-store",
        });
        res.end(req.method === "HEAD" ? undefined : body);
        return true;
    };
}
