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
import { QUALITY_LADDER } from "./screen-quality.mjs";
import { encodeControl, encodeFrame } from "./screen-protocol.mjs";

const screenHelper = new ScreenHelper();
// Input rides a DEDICATED helper instance: mouse moves must never queue
// behind capture work sharing one stdin, because input latency is what this
// feature is judged on (brief §4.5).
let inputHelper = null;
function ensureInputHelper() {
    if (!inputHelper) inputHelper = new ScreenHelper();
    return inputHelper;
}
// Remote input ships ON (owner decision, overrides the brief's safe-off):
// the machine is a working remote the moment the server starts. Only an
// explicit IDUPI_REMOTE_INPUT=0 turns it off -- seeing a screen and
// controlling the PC are still two different permissions behind one token,
// so the kill switch stays one env var away.
const REMOTE_INPUT_ENABLED = process.env.IDUPI_REMOTE_INPUT !== "0";
// Active receiver-paced streams keyed by the client-chosen session id.
const screenSessions = new Map();

/** Test seam: inject fake sessions without booting helpers or ports. */
export function _sessionsForTest() {
    return screenSessions;
}

export async function handleScreenRoute(req, res, pathname) {
    // What the server allows over this bridge; the app hides its controls
    // accordingly. Behind requireAuth like everything else.
    if (pathname === "/api/v1/screen/config" && req.method === "GET") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ remoteInputEnabled: REMOTE_INPUT_ENABLED }));
        return true;
    }

    // Remote input: normalised coordinates against ONE monitor, resolved to
    // absolute virtual-desktop units inside the Go helper. Ships enabled --
    // IDUPI_REMOTE_INPUT=0 downgrades it to a 403 even for a valid token.
    if (pathname === "/api/v1/screen/input" && req.method === "POST") {
        if (!REMOTE_INPUT_ENABLED) {
            res.writeHead(403, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: "remote input is disabled on this server (IDUPI_REMOTE_INPUT=0 disables it)" }));
            return true;
        }
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", async () => {
            try {
                const parsed = JSON.parse(body || "{}");
                await ensureHelperBuilt();
                const helper = ensureInputHelper();
                const response = await helper.request({
                    cmd: "input",
                    action: parsed.type,
                    button: parsed.button || "",
                    monitor: parsed.monitor ?? 0,
                    x: parsed.x,
                    y: parsed.y,
                    // Pad-mode relative travel, realtime keyboard codes and
                    // scroll axis ride the same dedicated input helper.
                    dx: parsed.dx,
                    dy: parsed.dy,
                    code: parsed.code,
                    axis: parsed.axis,
                    delta: parsed.delta,
                });
                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify(response));
            } catch (err) {
                res.writeHead(502, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return true;
    }
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
            // "auto" hands the ladder the wheel; a preset name or a number
            // pins that choice manually. Anything else falls back to 55.
            const quality = qualityParam === "auto" || QUALITY_LADDER.some((p) => p.name === qualityParam)
                ? qualityParam
                : Number(qualityParam);
            const stream = createScreenStream({
                helper: screenHelper,
                monitor: monitor,
                width: Math.round(width),
                height: Math.round(height),
                quality: Number.isFinite(quality) || typeof quality === "string"
                    ? quality
                    : 55,
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
                // renderMs is the ladder's fuel: dropping it here made auto
                // mode see every frame as fast and pin itself at ultra.
                await session.stream.onAck({
                    frameId: parsed.frameId,
                    renderMs: parsed.renderMs,
                });
                res.writeHead(204);
                res.end();
            } catch (err) {
                res.writeHead(410, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return true;
    }

    // Live quality change (owner request): switch presets -- or hand the
    // ladder back the wheel -- WITHOUT tearing the stream down. The stream
    // validates the value and announces the result as a quality_changed
    // control, so the app's pill updates from server truth.
    if (pathname === "/api/v1/screen/quality" && req.method === "POST") {
        let body = "";
        req.on("data", (chunk) => {
            body += chunk;
        });
        req.on("end", async () => {
            let parsed;
            try {
                parsed = JSON.parse(body || "{}");
            } catch {
                res.writeHead(400, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: "invalid JSON body" }));
                return;
            }
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
            try {
                await session.stream.setQuality(parsed.quality);
                res.writeHead(204);
                res.end();
            } catch (err) {
                res.writeHead(400, { "Content-Type": "application/json" });
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
