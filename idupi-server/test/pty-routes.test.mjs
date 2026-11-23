// HTTP-level tests for lib/pty-routes.mjs. A real http.Server on an ephemeral
// port runs the route handler exactly as index.mjs wires it (after auth), with
// a fake PTY host so no Go binary or real shell is involved.

import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { EventEmitter } from "node:events";
import { PassThrough } from "node:stream";

import { PtyManager } from "../lib/pty-manager.mjs";
import { createPtyRoutes } from "../lib/pty-routes.mjs";

const silentLog = { log() {}, info() {}, warn() {}, error() {} };

class FakeChild extends EventEmitter {
    constructor(pid = 20_000) {
        super();
        this.pid = pid;
        this.stdin = new PassThrough();
        this.stdout = new PassThrough();
        this.stderr = new PassThrough();
        this.killed = false;
        this.frames = [];
        const write = this.stdin.write.bind(this.stdin);
        this.stdin.write = (chunk, ...rest) => {
            this.frames.push(Buffer.from(chunk));
            return write(chunk, ...rest);
        };
    }

    emitStdout(data) {
        this.stdout.emit("data", Buffer.isBuffer(data) ? data : Buffer.from(data));
    }

    emitStderr(text) {
        this.stderr.emit("data", Buffer.from(text));
    }

    exit(code = 0) {
        this.emit("close", code, null);
    }
}

function decodeFrames(frames) {
    return frames.map((buf) => ({
        kind: buf[4],
        body: buf.subarray(5),
        total: buf.readUInt32BE(0),
    }));
}

async function startHarness(t) {
    const hosts = [];
    const manager = new PtyManager({
        spawnHost: (args) => {
            const child = new FakeChild(20_000 + hosts.length);
            child.args = args;
            hosts.push(child);
            return child;
        },
        log: silentLog,
        killTree: (child) => child.exit(0),
        settleMs: 5,
    });
    const handle = createPtyRoutes(manager);
    const server = http.createServer(async (req, res) => {
        try {
            const pathname = new URL(req.url, "http://localhost").pathname;
            if (await handle(req, res, pathname)) return;
            res.writeHead(404, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: "unhandled" }));
        } catch (err) {
            if (!res.headersSent) {
                res.writeHead(500, { "Content-Type": "application/json" });
            }
            res.end(JSON.stringify({ error: err.message }));
        }
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const base = `http://127.0.0.1:${server.address().port}`;
    t.after(async () => {
        server.closeAllConnections?.();
        await new Promise((resolve) => server.close(resolve));
    });
    return { manager, hosts, base };
}

function post(base, path, body) {
    return fetch(base + path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
}

async function createViaApi(base, body = { cols: 90, rows: 25 }) {
    const res = await post(base, "/api/v1/pty", body);
    assert.equal(res.status, 201);
    return res.json();
}

async function waitFor(predicate, timeoutMs = 5_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (predicate()) return;
        await new Promise((resolve) => setTimeout(resolve, 20));
    }
    throw new Error("condition not met in time");
}

test("POST /api/v1/pty creates a terminal and GET lists it", async (t) => {
    const { base } = await startHarness(t);

    let res = await post(base, "/api/v1/pty", { cols: 90, rows: 25 });
    assert.equal(res.status, 201);
    const info = await res.json();
    assert.match(info.id, /^pty-/);
    assert.equal(info.cols, 90);
    assert.equal(info.rows, 25);
    assert.equal(info.alive, true);

    res = await fetch(base + "/api/v1/pty");
    assert.equal(res.status, 200);
    const list = await res.json();
    assert.equal(list.terminals.length, 1);
    assert.equal(list.terminals[0].id, info.id);
    assert.equal(list.terminals[0].alive, true);
});

test("POST /api/v1/pty maps BAD_REQUEST to 400 and LIMIT to 429", async (t) => {
    const { base } = await startHarness(t);

    let res = await post(base, "/api/v1/pty", { cols: 0, rows: 24 });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).status, "error");

    for (let i = 0; i < 8; i += 1) {
        res = await post(base, "/api/v1/pty", { cols: 80, rows: 24 });
        assert.equal(res.status, 201, `create #${i + 1} must succeed`);
        await res.arrayBuffer();
    }
    res = await post(base, "/api/v1/pty", { cols: 80, rows: 24 });
    assert.equal(res.status, 429);
    await res.arrayBuffer();
});

test("POST /api/v1/pty rejects a UNC cwd with 400 before spawning", async (t) => {
    const { base, hosts } = await startHarness(t);

    const res = await post(base, "/api/v1/pty", {
        cwd: "\\\\server\\share\\dir",
    });
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.equal(body.status, "error");
    assert.equal(hosts.length, 0);
});

test("stream ends the response when the socket backlog exceeds the cap", async () => {
    const hosts = [];
    const manager = new PtyManager({
        spawnHost: (args) => {
            const child = new FakeChild(40_000 + hosts.length);
            child.args = args;
            hosts.push(child);
            return child;
        },
        log: silentLog,
        killTree: (child) => child.exit(0),
        settleMs: 5,
    });
    const handle = createPtyRoutes(manager, { maxBufferedBytes: 8 });
    const info = await manager.create({
        cols: 80,
        rows: 24,
        cwd: process.cwd(),
        shell: ["fake-shell"],
    });

    // Bytes already in the ring make the replay run synchronously inside
    // subscribe() and cross the cap before the handler returns: this also
    // pins the post-subscribe unsubscribe for a response closed during replay.
    hosts[0].emitStdout("0123456789ABCDEF");

    const writes = [];
    const req = new EventEmitter();
    req.method = "GET";
    const res = new EventEmitter();
    res.writableEnded = false;
    res.destroyed = false;
    res.writableLength = 0;
    res.writeHead = () => {};
    res.write = (chunk) => {
        writes.push(Buffer.from(chunk));
        res.writableLength += chunk.length;
        return true;
    };
    res.end = () => {
        res.writableEnded = true;
        res.emit("close");
    };

    assert.equal(await handle(req, res, `/api/v1/pty/${info.id}/stream`), true);
    assert.equal(writes.length, 1);
    assert.equal(res.writableEnded, true);
    assert.equal(
        manager.entries.get(info.id).subscribers.size,
        0,
        "a response closed during replay must still unsubscribe",
    );

    hosts[0].emitStdout("after-cap");
    assert.equal(writes.length, 1, "nothing is written after the cap ends the stream");
});

test("POST input writes bytes, rejects bad bodies and oversized payloads", async (t) => {
    const { base, hosts } = await startHarness(t);
    const info = await createViaApi(base);

    let res = await post(base, `/api/v1/pty/${info.id}/input`, { data: "ls\r" });
    assert.equal(res.status, 204);
    let frames = decodeFrames(hosts[0].frames);
    assert.equal(frames.length, 1);
    assert.equal(frames[0].kind, 0x49);
    assert.equal(frames[0].body.toString("utf8"), "ls\r");

    res = await post(base, `/api/v1/pty/${info.id}/input`, {});
    assert.equal(res.status, 400);
    await res.arrayBuffer();

    res = await post(base, `/api/v1/pty/${info.id}/input`, { data: 42 });
    assert.equal(res.status, 400);
    await res.arrayBuffer();

    res = await post(base, `/api/v1/pty/${info.id}/input`, {
        data: "x".repeat(256 * 1024 + 1),
    });
    assert.equal(res.status, 413);
    await res.arrayBuffer();

    res = await post(base, "/api/v1/pty/pty-nope/input", { data: "x" });
    assert.equal(res.status, 404);
    await res.arrayBuffer();
});

test("POST resize applies dimensions and rejects bad or unknown ids", async (t) => {
    const { base, hosts, manager } = await startHarness(t);
    const info = await createViaApi(base);

    let res = await post(base, `/api/v1/pty/${info.id}/resize`, { cols: 132, rows: 43 });
    assert.equal(res.status, 204);
    const frames = decodeFrames(hosts[0].frames);
    assert.equal(frames[0].kind, 0x52);
    assert.deepEqual(JSON.parse(frames[0].body.toString("utf8")), { cols: 132, rows: 43 });
    assert.equal(manager.get(info.id).cols, 132);

    res = await post(base, `/api/v1/pty/${info.id}/resize`, { cols: -1, rows: 43 });
    assert.equal(res.status, 400);
    await res.arrayBuffer();

    res = await post(base, "/api/v1/pty/pty-nope/resize", { cols: 80, rows: 24 });
    assert.equal(res.status, 404);
    await res.arrayBuffer();
});

test("DELETE /api/v1/pty/{id} kills once, then answers 404", async (t) => {
    const { base, manager } = await startHarness(t);
    const info = await createViaApi(base);

    let res = await fetch(`${base}/api/v1/pty/${info.id}`, { method: "DELETE" });
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { status: "ok" });
    assert.equal(manager.get(info.id), null);

    res = await fetch(`${base}/api/v1/pty/${info.id}`, { method: "DELETE" });
    assert.equal(res.status, 404);
    assert.deepEqual(await res.json(), { status: "error", message: "not found" });

    res = await fetch(`${base}/api/v1/pty/pty-nope`, { method: "DELETE" });
    assert.equal(res.status, 404);
    await res.arrayBuffer();
});

test("GET stream replays, streams live bytes and ends on exit", async (t) => {
    const { base, hosts } = await startHarness(t);
    const info = await createViaApi(base);

    hosts[0].emitStdout("replay-");

    const res = await fetch(`${base}/api/v1/pty/${info.id}/stream`);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("content-type"), "application/octet-stream");
    assert.equal(res.headers.get("cache-control"), "no-store");
    assert.equal(res.headers.get("x-accel-buffering"), "no");

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let text = "";
    const pump = (async () => {
        for (;;) {
            const { value, done } = await reader.read();
            if (done) return;
            text += decoder.decode(value, { stream: true });
        }
    })();

    await waitFor(() => text.includes("replay-"));
    hosts[0].emitStdout("live");
    await waitFor(() => text.includes("live"));

    hosts[0].emitStderr('{"exit":0}\n');
    hosts[0].exit(0);
    await pump; // resolves only because the route ended the response on exit
    assert.match(text, /replay-live/);
});

test("stream on a recently exited terminal replays the tail then ends", async (t) => {
    const { base, hosts } = await startHarness(t);
    const info = await createViaApi(base);

    hosts[0].emitStdout("tail-bytes");
    hosts[0].exit(2);

    const res = await fetch(`${base}/api/v1/pty/${info.id}/stream`);
    assert.equal(res.status, 200);
    assert.equal(await res.text(), "tail-bytes");
});

test("stream for an unknown id answers 404", async (t) => {
    const { base } = await startHarness(t);
    const res = await fetch(`${base}/api/v1/pty/pty-nope/stream`);
    assert.equal(res.status, 404);
    assert.deepEqual(await res.json(), { status: "error", message: "not found" });
});
