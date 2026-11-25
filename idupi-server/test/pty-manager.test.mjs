// Unit tests for lib/pty-manager.mjs. A fake host (EventEmitter with
// PassThrough stdio) exercises framing, the replay ring, subscribers, exit
// parsing and lifecycle without booting the Go helper. One integration test
// at the end uses the real helper on Windows.

import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { PassThrough } from "node:stream";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { isAbsolute, join, relative, resolve } from "node:path";

import { PtyManager } from "../lib/pty-manager.mjs";

const silentLog = { log() {}, info() {}, warn() {}, error() {} };

// Polls instead of sleeping a fixed time: timer-driven state (reap, queued
// exit notifications) may land at any point inside the old magic windows.
async function waitFor(predicate, timeoutMs = 2_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (predicate()) return;
        await new Promise((resolve) => setTimeout(resolve, 10));
    }
    throw new Error("condition not met in time");
}

class FakeChild extends EventEmitter {
    constructor(pid = 10_000) {
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

function makeHarness({ ringBytes, reapMs, settleMs = 10 } = {}) {
    const hosts = [];
    const killed = [];
    const manager = new PtyManager({
        spawnHost: (args) => {
            const child = new FakeChild(10_000 + hosts.length);
            child.args = args;
            hosts.push(child);
            return child;
        },
        ringBytes,
        reapMs,
        settleMs,
        log: silentLog,
        killTree: (child) => {
            killed.push(child);
            child.exit(0);
        },
    });
    return { manager, hosts, killed };
}

function decodeFrames(frames) {
    return frames.map((buf) => {
        assert.ok(buf.length >= 5, "frame needs a 4-byte length and a kind byte");
        const total = buf.readUInt32BE(0);
        assert.equal(total, buf.length - 4, "totalLen must be 1 + body length");
        return { kind: buf[4], body: buf.subarray(5) };
    });
}

function tempDir(t) {
    const dir = mkdtempSync(join(tmpdir(), "idupi-pty-"));
    t.after(() => rmSync(dir, { recursive: true, force: true }));
    return dir;
}

async function createTerminal(manager, overrides = {}) {
    return manager.create({
        cols: 80,
        rows: 24,
        cwd: process.cwd(),
        shell: ["fake-shell"],
        ...overrides,
    });
}

test("create rejects invalid dimensions with BAD_REQUEST", async () => {
    const { manager } = makeHarness();
    const cases = [
        [0, 30],
        [120, 0],
        [1001, 30],
        [120, 1001],
        [120, 1.5],
        ["120", 30],
    ];
    for (const [cols, rows] of cases) {
        await assert.rejects(
            () => manager.create({ cols, rows, cwd: process.cwd() }),
            (err) => err.code === "BAD_REQUEST",
            `cols=${cols} rows=${rows} must be refused`,
        );
    }
});

test("create rejects a cwd that is not an existing directory", async () => {
    const { manager } = makeHarness();
    await assert.rejects(
        () => manager.create({ cwd: join(tmpdir(), "idupi-pty-does-not-exist-xyz") }),
        (err) => err.code === "BAD_REQUEST",
    );
});

test("create spawns the host with the pty argv contract and returns info", async (t) => {
    const dir = tempDir(t);
    const { manager, hosts } = makeHarness();
    const info = await manager.create({
        cols: 100,
        rows: 40,
        cwd: dir,
        shell: ["fake-shell", "-x"],
    });

    assert.match(info.id, /^pty-[0-9a-f]{16}$/);
    assert.deepEqual(hosts[0].args, [
        "--cols", "100",
        "--rows", "40",
        "--cwd", dir,
        "--",
        "fake-shell", "-x",
    ]);
    assert.equal(info.pid, 10_000);
    assert.equal(info.cols, 100);
    assert.equal(info.rows, 40);
    assert.equal(info.cwd, dir);
    assert.equal(info.shell, "fake-shell -x");
    assert.equal(info.alive, true);
    assert.equal(info.exitCode, null);
    assert.ok(Number.isFinite(Date.parse(info.createdAt)));
    assert.equal(manager.list().length, 1);
    assert.equal(manager.get(info.id).id, info.id);
    assert.equal(manager.get("pty-unknown"), null);
});

test("create defaults cwd to the home directory and shell per platform", async () => {
    const { manager } = makeHarness();
    const info = await manager.create({ cols: 80, rows: 24 });
    const os = await import("node:os");
    assert.equal(info.cwd, os.homedir());
    if (process.platform === "win32") {
        assert.equal(info.shell, "powershell.exe -NoLogo");
    } else {
        assert.ok(info.shell.length > 0);
    }
});

test("default Windows shell switches the console to UTF-8 so accents render", async () => {
    if (process.platform !== "win32") return;
    const { manager, hosts } = makeHarness();
    await manager.create({ cols: 80, rows: 24 });
    const args = hosts.at(-1).args;
    const command = args.slice(args.indexOf("--") + 1);
    assert.deepEqual(command.slice(0, 4), ["powershell.exe", "-NoLogo", "-NoExit", "-Command"]);
    assert.match(command[4], /\[Console\]::OutputEncoding\s*=.*UTF8/);
    assert.match(command[4], /\[Console\]::InputEncoding\s*=/);
});

test("an explicit shell is spawned exactly as given", async () => {
    const { manager, hosts } = makeHarness();
    await manager.create({ cols: 80, rows: 24, shell: ["pwsh.exe", "-NoLogo"] });
    const args = hosts.at(-1).args;
    assert.deepEqual(args.slice(args.indexOf("--") + 1), ["pwsh.exe", "-NoLogo"]);
});

test("create resolves a relative cwd and stores the absolute path", async (t) => {
    // The fixture lives under the working directory, not the OS temp dir: on CI
    // the checkout (D:) and %TEMP% (C:) are on different drives, where no
    // relative path exists between them.
    const dir = mkdtempSync(join(process.cwd(), ".idupi-pty-test-"));
    t.after(() => rmSync(dir, { recursive: true, force: true }));
    const rel = relative(process.cwd(), dir);
    assert.ok(rel && !isAbsolute(rel), "fixture must be reachable relatively");

    const { manager, hosts } = makeHarness();
    const info = await manager.create({
        cols: 80,
        rows: 24,
        cwd: rel,
        shell: ["fake-shell"],
    });

    assert.equal(info.cwd, resolve(dir));
    assert.equal(hosts[0].args[5], resolve(dir));
});

test("create rejects UNC and device cwd paths before any filesystem access", async () => {
    const { manager, hosts } = makeHarness();
    for (const cwd of [
        "\\\\server\\share\\dir",
        "//server/share/dir",
        "\\\\?\\C:\\Windows",
    ]) {
        await assert.rejects(
            () => manager.create({ cwd }),
            (err) => err.code === "BAD_REQUEST",
            `cwd=${cwd} must be refused`,
        );
    }
    assert.equal(hosts.length, 0, "no host was spawned");
});

test("create reflects a startup error from stderr as dead info", async () => {
    const hosts = [];
    const manager = new PtyManager({
        spawnHost: () => {
            const child = new FakeChild(30_000);
            hosts.push(child);
            // setImmediate runs after create() has wired the stream listeners
            // and entered its settle wait, so the failure is never emitted into
            // the void before anyone is listening.
            setImmediate(() => {
                child.emitStderr('{"error":"boom"}\n');
                child.exit(1);
            });
            return child;
        },
        log: silentLog,
        killTree: (child) => child.exit(0),
        settleMs: 1_000,
    });

    const info = await manager.create({
        cols: 80,
        rows: 24,
        cwd: process.cwd(),
        shell: ["fake-shell"],
    });

    assert.equal(hosts.length, 1);
    assert.equal(info.alive, false);
    assert.equal(info.error, "boom");
    assert.equal(manager.get(info.id).error, "boom");
    assert.equal(manager.get(info.id).alive, false);
    assert.equal(manager.list()[0].alive, false);
});

test("write encodes input frames and chunks bodies at 64 KiB", async () => {
    const { manager, hosts } = makeHarness();
    const info = await createTerminal(manager);

    const payload = "A".repeat(70_000);
    assert.equal(manager.write(info.id, payload), true);

    const frames = decodeFrames(hosts[0].frames);
    assert.equal(frames.length, 2);
    for (const frame of frames) {
        assert.equal(frame.kind, 0x49);
        assert.ok(frame.body.length <= 64 * 1024);
    }
    assert.equal(Buffer.concat(frames.map((f) => f.body)).toString("utf8"), payload);
});

test("write encodes multibyte UTF-8 and reports unknown ids", async () => {
    const { manager, hosts } = makeHarness();
    const info = await createTerminal(manager);

    assert.equal(manager.write(info.id, "ñandú"), true);
    const [frame] = decodeFrames(hosts[0].frames);
    assert.equal(frame.body.toString("utf8"), "ñandú");
    assert.equal(manager.write("pty-nope", "x"), false);
});

test("resize sends a resize frame, updates info and validates input", async () => {
    const { manager, hosts } = makeHarness();
    const info = await createTerminal(manager);

    assert.equal(manager.resize(info.id, 132, 43), true);
    const [frame] = decodeFrames(hosts[0].frames);
    assert.equal(frame.kind, 0x52);
    assert.deepEqual(JSON.parse(frame.body.toString("utf8")), { cols: 132, rows: 43 });
    assert.equal(manager.get(info.id).cols, 132);
    assert.equal(manager.get(info.id).rows, 43);

    assert.throws(() => manager.resize(info.id, 0, 43), (err) => err.code === "BAD_REQUEST");
    assert.equal(manager.resize("pty-nope", 80, 24), false);
});

test("subscribe replays the ring tail in order before live chunks", async () => {
    const { manager, hosts } = makeHarness({ ringBytes: 10 });
    const info = await createTerminal(manager);

    hosts[0].emitStdout("0123456789");
    hosts[0].emitStdout("ABCDEF"); // 16 bytes total; only the last 10 survive

    const seen = [];
    const unsubscribe = manager.subscribe(
        info.id,
        (chunk) => seen.push(chunk.toString("utf8")),
        () => {},
    );
    assert.equal(seen.join(""), "6789ABCDEF");

    hosts[0].emitStdout("live");
    assert.equal(seen.join(""), "6789ABCDEFlive");

    unsubscribe();
    hosts[0].emitStdout("after-unsubscribe");
    assert.equal(seen.join(""), "6789ABCDEFlive");
});

test("a host exit parses stderr, marks the entry dead and notifies once", async () => {
    const { manager, hosts } = makeHarness();
    const info = await createTerminal(manager);

    const exits = [];
    manager.subscribe(info.id, () => {}, (code) => exits.push(code));

    // Split across chunks on purpose: stderr is line-buffered, not chunk-safe.
    hosts[0].emitStderr('{"exit":');
    hosts[0].emitStderr("7}\n");
    hosts[0].exit(0); // the host process itself exits 0; the child code rode stderr

    assert.deepEqual(exits, [7]);
    const after = manager.get(info.id);
    assert.equal(after.alive, false);
    assert.equal(after.exitCode, 7);
    assert.equal(manager.list().length, 1, "dead entry stays for replay");

    // Late output must not crash or reach subscribers.
    assert.doesNotThrow(() => hosts[0].emitStdout("late"));
});

test("subscribing after exit replays the tail and reports the exit code", async () => {
    const { manager, hosts } = makeHarness();
    const info = await createTerminal(manager);

    hosts[0].emitStdout("tail-bytes");
    hosts[0].exit(3);

    const seen = [];
    let exitCode = "unset";
    manager.subscribe(
        info.id,
        (chunk) => seen.push(chunk.toString("utf8")),
        (code) => { exitCode = code; },
    );
    assert.equal(seen.join(""), "tail-bytes");
    await waitFor(() => exitCode === 3);
    assert.equal(exitCode, 3);
});

test("subscribing to an unknown id reports exit so the stream terminates", async () => {
    const { manager } = makeHarness();
    let exitCode = "unset";
    const unsubscribe = manager.subscribe("pty-nope", () => {}, (code) => { exitCode = code; });
    await waitFor(() => exitCode === null);
    assert.equal(exitCode, null);
    assert.equal(typeof unsubscribe, "function");
    unsubscribe();
});

test("kill returns false for unknown ids and true for known ones", async () => {
    const { manager, hosts, killed } = makeHarness();
    const info = await createTerminal(manager);

    assert.equal(await manager.kill("pty-nope"), false);

    assert.equal(await manager.kill(info.id), true);
    assert.equal(killed.length, 1, "tree kill is the second guarantee");
    assert.equal(hosts[0].stdin.writableEnded, true, "stdin EOF requests shutdown");
    assert.equal(manager.get(info.id), null);
    assert.equal(await manager.kill(info.id), false);
});

test("create refuses the ninth concurrent terminal with LIMIT", async () => {
    const { manager } = makeHarness();
    for (let i = 0; i < 8; i += 1) {
        await createTerminal(manager);
    }
    await assert.rejects(
        () => createTerminal(manager),
        (err) => err.code === "LIMIT",
    );
});

test("concurrent creates cannot exceed the terminal limit", async () => {
    const hosts = [];
    const manager = new PtyManager({
        // Slow host startup widens the window the old count-then-spawn code
        // used to leak through: all requests read the same live count.
        spawnHost: async (args) => {
            await new Promise((resolve) => setTimeout(resolve, 30));
            const child = new FakeChild(30_000 + hosts.length);
            child.args = args;
            hosts.push(child);
            return child;
        },
        log: silentLog,
        killTree: (child) => child.exit(0),
        settleMs: 5,
    });

    const results = await Promise.allSettled(
        Array.from({ length: 12 }, () => createTerminal(manager)),
    );

    const created = results.filter((result) => result.status === "fulfilled");
    const refused = results.filter(
        (result) =>
            result.status === "rejected" && result.reason?.code === "LIMIT",
    );
    assert.equal(created.length, 8);
    assert.equal(refused.length, 4);
    assert.equal(manager.list().length, 8);
});

test("an exited terminal is retained for replay, then reaped", async () => {
    const { manager, hosts } = makeHarness({ reapMs: 20 });
    const info = await createTerminal(manager);

    hosts[0].exit(0);
    assert.equal(manager.get(info.id).alive, false);
    assert.equal(manager.list().length, 1);

    await waitFor(() => manager.list().length === 0);
});

test("shutdownAll kills every tracked terminal", async () => {
    const { manager, killed } = makeHarness();
    await createTerminal(manager);
    await createTerminal(manager);

    await manager.shutdownAll();

    assert.equal(killed.length, 2);
    assert.deepEqual(manager.list(), []);
});

test("non-JSON stderr noise is logged, not thrown", async () => {
    const warnings = [];
    const child = new FakeChild();
    const manager = new PtyManager({
        spawnHost: () => child,
        log: { ...silentLog, warn: (msg) => warnings.push(msg) },
        killTree: () => {},
        settleMs: 10,
    });
    await manager.create({ cwd: process.cwd(), shell: ["fake-shell"] });

    assert.doesNotThrow(() => child.stderr.emit("data", Buffer.from("noise without json\n")));
    assert.equal(warnings.length, 1);
    assert.match(warnings[0], /noise without json/);
});

test(
    "real helper: cmd.exe output reaches the subscriber",
    { skip: process.platform !== "win32", timeout: 30_000 },
    async (t) => {
        const manager = new PtyManager({ log: silentLog });
        let info;
        try {
            info = await manager.create({
                cols: 80,
                rows: 24,
                cwd: process.cwd(),
                shell: ["cmd.exe", "/c", "echo pty-ok"],
            });
        } catch (err) {
            t.skip(`real helper unavailable: ${err.message}`);
            return;
        }
        try {
            const chunks = [];
            const code = await new Promise((resolve) => {
                manager.subscribe(
                    info.id,
                    (chunk) => chunks.push(chunk),
                    (exitCode) => resolve(exitCode),
                );
            });
            assert.equal(code, 0);
            assert.match(Buffer.concat(chunks).toString("utf8"), /pty-ok/);
        } finally {
            await manager.shutdownAll();
        }
    },
);
