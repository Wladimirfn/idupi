// ============================================================================
// idupi-server/test/request-guard.test.mjs
//
// http.createServer was given an `async` handler directly. A throw inside it
// becomes an unhandled promise rejection, which Node 24 treats as fatal: the
// process exits and takes every SSE stream and the persistent Pi subprocess
// with it. Two routes had no try/catch at all, and resolveProject returns
// registeredProjects[0] -- `undefined` once the last project is deleted, so
// `proj.path` throws and the whole server dies.
//
// Reproduced before the fix: a throwing async handler exits the process with
// code 1 and never answers the request.
//
// Run (from repo root):
//   node --test idupi-server/test/request-guard.test.mjs
// ============================================================================

process.env.IDUPI_NO_LISTEN = "1";

import test from "node:test";
import assert from "node:assert/strict";

const { guardedRequest } = await import("../index.mjs");

/** Minimal res double recording what the handler wrote. */
function fakeRes(overrides = {}) {
    const res = {
        statusCode: null,
        headersSent: false,
        body: "",
        writeHead(code) { res.statusCode = code; res.headersSent = true; return res; },
        end(chunk) { if (chunk) res.body += chunk; return res; },
        setHeader() {},
        write() { return true; },
        on() {},
        removeListener() {},
        ...overrides,
    };
    return res;
}

test("a request that throws is answered with 500 instead of killing the process", async () => {
    // Fault injection at the very first thing the handler touches: reading the
    // auth header. Any throw anywhere inside must land the same way.
    const req = {
        method: "GET",
        url: "/api/v1/status",
        get headers() { throw new Error("boom reading headers"); },
        on() {},
    };
    const res = fakeRes();

    await guardedRequest(req, res); // must RESOLVE, not reject

    assert.equal(res.statusCode, 500);
    assert.match(res.body, /boom reading headers/);
});

test("guardedRequest never rejects, even when writing the error response also fails", async () => {
    // The pathological case: the socket is already broken, so the 500 write
    // throws too. A rejection here would be just as fatal as the original one.
    const req = {
        method: "GET",
        url: "/api/v1/status",
        get headers() { throw new Error("primary failure"); },
        on() {},
    };
    const res = fakeRes({
        writeHead() { throw new Error("socket already gone"); },
        end() { throw new Error("socket already gone"); },
    });

    await guardedRequest(req, res); // resolving at all is the assertion
    assert.ok(true);
});

test("a normal request still flows through untouched", async () => {
    // requireAuth reads req.socket.remoteAddress when it rejects; a real
    // http.IncomingMessage always has one.
    const req = {
        method: "GET", url: "/api/v1/status", headers: {},
        socket: { remoteAddress: "127.0.0.1" }, on() {},
    };
    const res = fakeRes();

    await guardedRequest(req, res);

    // Either the real 200 status payload, or 401 if auth rejects the empty
    // header -- both prove the request was handled rather than crashing.
    assert.ok(res.statusCode === 200 || res.statusCode === 401, `got ${res.statusCode}`);
});
