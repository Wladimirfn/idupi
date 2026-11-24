// Tests for lib/terminal-web-routes.mjs (live-shared-terminals, T2).
//
// Two harnesses: a real http.Server on 127.0.0.1 for the served bytes and
// headers, and fake req/res objects for the cases a real socket cannot
// produce here (a non-loopback remoteAddress, and raw traversal strings that
// fetch/URL would normalize before they ever reach the handler).

import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { createTerminalWebRoutes } from "../lib/terminal-web-routes.mjs";

const SERVER_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const WEB_ROOT = join(SERVER_ROOT, "web", "terminal");
const VENDOR_ROOT = join(
    SERVER_ROOT,
    "..",
    "app",
    "src",
    "main",
    "assets",
    "terminal",
    "vendor",
);

const CSP = [
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

const silentLog = { warn() {}, error() {} };

function makeHandler() {
    return createTerminalWebRoutes({
        webRoot: WEB_ROOT,
        vendorRoot: VENDOR_ROOT,
        log: silentLog,
    });
}

const handle = makeHandler();

async function startServer(t) {
    const server = http.createServer(async (req, res) => {
        try {
            const pathname = new URL(req.url, "http://localhost").pathname;
            if (await handle(req, res, pathname)) return;
            // Anything the handler does not own stands in for the rest of the
            // route chain: a distinct status proves it was not answered here.
            res.writeHead(418);
            res.end();
        } catch (err) {
            if (!res.headersSent) res.writeHead(500);
            res.end(String((err && err.message) || err));
        }
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    t.after(async () => {
        server.closeAllConnections?.();
        await new Promise((resolve) => server.close(resolve));
    });
    return `http://127.0.0.1:${server.address().port}`;
}

function fakeExchange({ method = "GET", address = "127.0.0.1" } = {}) {
    // `address: null` means the socket carries no address at all, which must
    // fail closed exactly like a LAN address.
    const req = { method, socket: address === null ? null : { remoteAddress: address } };
    const res = {
        status: null,
        headers: null,
        body: "",
        ended: false,
        writeHead(status, headers) {
            res.status = status;
            res.headers = headers || {};
            return res;
        },
        end(chunk) {
            if (chunk) res.body += chunk;
            res.ended = true;
        },
    };
    return { req, res };
}

function assertSecurityHeaders(res) {
    assert.equal(res.headers["Cache-Control"], "no-store");
    assert.equal(res.headers["X-Content-Type-Options"], "nosniff");
    assert.equal(res.headers["Content-Security-Policy"], CSP);
    assert.equal(res.headers["Referrer-Policy"], "no-referrer");
}

test("serves the page and the vendored assets from loopback with security headers", async (t) => {
    const base = await startServer(t);
    const cases = [
        ["/terminal/", "text/html; charset=utf-8", "<div id=\"terminal\""],
        ["/terminal/app.js", "text/javascript; charset=utf-8", "Authorization"],
        ["/terminal/app.css", "text/css; charset=utf-8", ".xterm"],
        ["/terminal/vendor/xterm.js", "text/javascript; charset=utf-8", "Terminal"],
        ["/terminal/vendor/xterm.css", "text/css; charset=utf-8", ".xterm"],
        ["/terminal/vendor/addon-fit.js", "text/javascript; charset=utf-8", "FitAddon"],
    ];

    for (const [path, contentType, marker] of cases) {
        const res = await fetch(base + path);
        assert.equal(res.status, 200, path);
        assert.equal(res.headers.get("content-type"), contentType, path);
        assert.equal(res.headers.get("cache-control"), "no-store", path);
        assert.equal(res.headers.get("x-content-type-options"), "nosniff", path);
        assert.equal(res.headers.get("content-security-policy"), CSP, path);
        assert.equal(res.headers.get("referrer-policy"), "no-referrer", path);
        const body = await res.text();
        assert.ok(body.includes(marker), `${path} body must contain ${marker}`);
        assert.ok(Number(res.headers.get("content-length")) > 0, path);
    }
});

test("HEAD returns the same headers and no body", async (t) => {
    const base = await startServer(t);
    const res = await fetch(base + "/terminal/", { method: "HEAD" });
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("content-type"), "text/html; charset=utf-8");
    assert.equal(res.headers.get("content-security-policy"), CSP);
    assert.ok(Number(res.headers.get("content-length")) > 0);
    assert.equal(await res.text(), "");
});

test("/terminal redirects to /terminal/", async (t) => {
    const base = await startServer(t);
    const res = await fetch(base + "/terminal", { redirect: "manual" });
    assert.equal(res.status, 301);
    assert.equal(res.headers.get("location"), "/terminal/");
});

test("non-loopback clients are refused with 403 and never see the files", async () => {
    for (const address of ["192.168.1.50", "10.0.0.7", "203.0.113.9", null]) {
        const { req, res } = fakeExchange({ address });
        assert.equal(await handle(req, res, "/terminal/"), true, String(address));
        assert.equal(res.status, 403, String(address));
        assertSecurityHeaders(res);
        assert.equal(res.headers["Content-Type"], "text/plain; charset=utf-8");
        assert.match(res.body, /loopback/);
        assert.ok(!res.body.includes("<!DOCTYPE html>"));
    }
});

test("every loopback spelling is accepted", async () => {
    for (const address of ["127.0.0.1", "::1", "::ffff:127.0.0.1"]) {
        const { req, res } = fakeExchange({ address });
        assert.equal(await handle(req, res, "/terminal/"), true, address);
        assert.equal(res.status, 200, address);
        assertSecurityHeaders(res);
    }
});

test("unknown paths under /terminal answer 404, including traversal attempts", async () => {
    const paths = [
        "/terminal/vendor/other.js",
        "/terminal/vendor",
        "/terminal/nope.js",
        "/terminal/../index.mjs",
        "/terminal/%2e%2e/index.mjs",
        "/terminal/..%2f..%2fidupi-server/index.mjs",
    ];
    for (const pathname of paths) {
        const { req, res } = fakeExchange();
        assert.equal(await handle(req, res, pathname), true, pathname);
        assert.equal(res.status, 404, pathname);
        assertSecurityHeaders(res);
        assert.equal(res.body, "not found\n", pathname);
    }
});

test("non-GET/HEAD methods on a whitelisted path answer 405", async () => {
    const { req, res } = fakeExchange({ method: "POST" });
    assert.equal(await handle(req, res, "/terminal/app.js"), true);
    assert.equal(res.status, 405);
    assert.equal(res.headers.Allow, "GET, HEAD");
});

test("paths outside /terminal return false so the route chain continues", async () => {
    const paths = [
        "/",
        "/index.mjs",
        "/api/v1/pty",
        "/api/v1/pty/pty-abc/stream",
        "/terminalx",
        "/terminal-app.js",
    ];
    for (const pathname of paths) {
        const { req, res } = fakeExchange();
        assert.equal(await handle(req, res, pathname), false, pathname);
        assert.equal(res.status, null, `${pathname} must not be answered`);
        assert.equal(res.ended, false, pathname);
    }
});
