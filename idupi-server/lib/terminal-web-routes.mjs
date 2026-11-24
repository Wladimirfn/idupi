// Browser view of the shared PTY terminals (live-shared-terminals, T2).
//
// This handler serves the static page plus the vendored xterm assets. Those
// bytes are not secrets, so it runs BEFORE requireAuth in index.mjs; the
// loopback check is what keeps it from becoming an open page on the LAN, and
// every API call the page makes still carries the bearer token.
//
// The route table is a strict whitelist of exact URL paths. A request path is
// used as a MAP KEY, never joined into a filesystem path, so `..`, `%2e%2e`,
// or anything else a client invents can never select another file. Any other
// path under /terminal answers 404; anything outside /terminal returns false
// so the rest of the route chain continues untouched.

import { readFile } from "node:fs/promises";
import { join } from "node:path";

const PREFIX = "/terminal";
// Client addresses a browser page on this machine can arrive from. `::ffff:`
// is the same v4 loopback as seen through a v6 socket.
const LOOPBACK_ADDRESSES = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);

const CONTENT_SECURITY_POLICY = [
    "default-src 'none'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "connect-src 'self'",
    "img-src 'self' data:",
    "font-src 'self' data:",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
].join("; ");

const TEXT_HTML = "text/html; charset=utf-8";
const TEXT_JAVASCRIPT = "text/javascript; charset=utf-8";
const TEXT_CSS = "text/css; charset=utf-8";
const TEXT_PLAIN = "text/plain; charset=utf-8";

function responseHeaders(extra = {}) {
    return {
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
        "Content-Security-Policy": CONTENT_SECURITY_POLICY,
        "Referrer-Policy": "no-referrer",
        ...extra,
    };
}

function sendText(res, status, text) {
    res.writeHead(status, responseHeaders({ "Content-Type": TEXT_PLAIN }));
    res.end(text);
}

/**
 * @param {{ webRoot: string, vendorRoot: string, log?: { warn?: Function, error?: Function } }} options
 *   webRoot serves the page itself; vendorRoot is the Android app's vendored
 *   xterm 5.5.0 directory, reused instead of keeping a second copy in sync.
 * @returns {(req: import("node:http").IncomingMessage, res: import("node:http").ServerResponse, pathname: string) => Promise<boolean>}
 */
export function createTerminalWebRoutes({ webRoot, vendorRoot, log = console } = {}) {
    const files = new Map([
        ["/terminal/", { path: join(webRoot, "index.html"), type: TEXT_HTML }],
        ["/terminal/app.js", { path: join(webRoot, "app.js"), type: TEXT_JAVASCRIPT }],
        ["/terminal/app.css", { path: join(webRoot, "app.css"), type: TEXT_CSS }],
        ["/terminal/vendor/xterm.js", { path: join(vendorRoot, "xterm.js"), type: TEXT_JAVASCRIPT }],
        ["/terminal/vendor/xterm.css", { path: join(vendorRoot, "xterm.css"), type: TEXT_CSS }],
        ["/terminal/vendor/addon-fit.js", { path: join(vendorRoot, "addon-fit.js"), type: TEXT_JAVASCRIPT }],
    ]);

    return async function handleTerminalWebRoute(req, res, pathname) {
        if (pathname !== PREFIX && !pathname.startsWith(PREFIX + "/")) {
            return false;
        }

        // Fail closed: an unknown address is not loopback. This runs before
        // anything else so a LAN client never learns which files exist here.
        const remote = req.socket && req.socket.remoteAddress;
        if (!LOOPBACK_ADDRESSES.has(remote)) {
            log.warn(
                `[terminal-web] refused ${req.method} ${pathname} from ${remote || "unknown"}: loopback clients only`,
            );
            sendText(res, 403, "forbidden: loopback clients only\n");
            return true;
        }

        // Canonical directory URL: relative asset paths and sessionStorage
        // both behave predictably only under /terminal/.
        if (pathname === PREFIX) {
            res.writeHead(301, responseHeaders({ Location: PREFIX + "/" }));
            res.end();
            return true;
        }

        const entry = files.get(pathname);
        if (!entry) {
            sendText(res, 404, "not found\n");
            return true;
        }

        if (req.method !== "GET" && req.method !== "HEAD") {
            res.writeHead(
                405,
                responseHeaders({ "Content-Type": TEXT_PLAIN, Allow: "GET, HEAD" }),
            );
            res.end("method not allowed\n");
            return true;
        }

        let body;
        try {
            body = await readFile(entry.path);
        } catch (err) {
            // A whitelisted path that cannot be read is an installation
            // problem, not a client error.
            log.error(
                `[terminal-web] cannot read ${entry.path}: ${(err && err.code) || (err && err.message)}`,
            );
            sendText(res, 500, "internal error\n");
            return true;
        }

        res.writeHead(
            200,
            responseHeaders({
                "Content-Type": entry.type,
                "Content-Length": String(body.length),
            }),
        );
        res.end(req.method === "HEAD" ? undefined : body);
        return true;
    };
}
