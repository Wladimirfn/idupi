// Remote screen routes (docs/remote-screen-module.md), moved out of index.mjs
// so this module owns the capture-helper lifecycle and the active-stream
// registry instead of growing the monolith's route chain.
//
// Dispatch contract: index.mjs calls handleScreenRoute(req, res, pathname)
// right after requireAuth; it returns true when it answered the request and
// false when the path belongs to someone else. Nothing here may run before
// the bearer token is verified upstream.

import { ScreenHelper, ensureHelperBuilt } from "./screen-helper.mjs";
import { createScreenStream } from "./screen-stream.mjs";
import { encodeControl, encodeFrame } from "./screen-protocol.mjs";

const screenHelper = new ScreenHelper();
// Active receiver-paced streams keyed by the client-chosen session id.
const screenSessions = new Map();

export async function handleScreenRoute(req, res, pathname) {
    // Remote screen: enumerate monitors through the Go capture helper.
    if (pathname === "/api/v1/screen/monitors" && req.method === "GET") {
        try {
            await ensureHelperBuilt();
            const monitors = await screenHelper.list();
            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify(monitors));
        } catch (err) {
            res.writeHead(503, { "Content-Type": "application/json" });
            res.end(
                JSON.stringify({
                    error: "screen helper unavailable: " + err.message,
                }),
            );
        }
        return true;
    }

    // Binary chunked frame stream paced by acks (brief §4.1). Deliberately
    // NOT SSE: SSE text lines would force base64 (+33%) onto the single
    // hottest path in the system.
    if (pathname === "/api/v1/screen/stream" && req.method === "GET") {
        const params = new URL(req.url || "/", "http://localhost").searchParams;
        const sid = params.get("sid");
        const monitor = Number(params.get("monitor") ?? 0);
        const width = Number(params.get("viewportW"));
        const height = Number(params.get("viewportH"));
        const qualityParam = params.get("quality") ?? "55";
        if (
            !sid ||
            !Number.isFinite(width) ||
            !Number.isFinite(height) ||
            width < 1 ||
            height < 1
        ) {
            res.writeHead(400, { "Content-Type": "application/json" });
            res.end(
                JSON.stringify({
                    error: "stream needs sid, viewportW and viewportH",
                }),
            );
            return true;
        }
        try {
            await ensureHelperBuilt();
            res.writeHead(200, {
                "Content-Type": "application/octet-stream",
                "Cache-Control": "no-cache, no-transform",
                Connection: "keep-alive",
            });
            const quality = qualityParam === "auto" ? 55 : Number(qualityParam);
            const stream = createScreenStream({
                helper: screenHelper,
                monitor: monitor,
                width: Math.round(width),
                height: Math.round(height),
                quality: Number.isFinite(quality) ? quality : 55,
            });
            screenSessions.set(sid, { stream: stream, res: res });
            stream.onFrame((frame) => {
                if (!res.writableEnded)
                    res.write(encodeFrame(frame.meta, frame.jpeg));
            });
            stream.onControl((control) => {
                if (!res.writableEnded) res.write(encodeControl(control));
            });
            req.on("close", () => {
                stream.stop();
                screenSessions.delete(sid);
            });
            await stream.start();
        } catch (err) {
            if (!res.headersSent) {
                res.writeHead(503, { "Content-Type": "application/json" });
            }
            res.end(
                JSON.stringify({
                    error: "screen stream unavailable: " + err.message,
                }),
            );
        }
        return true;
    }

    // Receiver finished rendering a frame: unlock the next fresh capture.
    if (pathname === "/api/v1/screen/ack" && req.method === "POST") {
        let body = "";
        req.on("data", (chunk) => {
            body += chunk;
        });
        req.on("end", async () => {
            try {
                const parsed = JSON.parse(body || "{}");
                const session = parsed.sid && screenSessions.get(parsed.sid);
                if (!session) {
                    res.writeHead(404, { "Content-Type": "application/json" });
                    res.end(
                        JSON.stringify({
                            error: "unknown or closed stream session",
                        }),
                    );
                    return;
                }
                await session.stream.onAck({ frameId: parsed.frameId });
                res.writeHead(204);
                res.end();
            } catch (err) {
                res.writeHead(410, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return true;
    }

    return false;
}

/** Kills the capture helper when the server process exits so it never outlives us. */
export function shutdownScreen() {
    if (screenHelper.child) screenHelper.child.kill();
}
