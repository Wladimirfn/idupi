// Interactive PTY terminals (task T2): one ConPTY host per terminal.
//
// The Go helper (`idupi-screen.exe pty ...`) owns the pseudo console and the
// child's job object; this module owns lifecycle, the replay ring buffer,
// subscribers and frame encoding. Nothing here spawns a shell string: every
// child argv travels as an array, so a path with spaces never splits.
//
// Wire contract (screen-helper/pty_frames.go):
//   stdin frame: u32be totalLen || kind(1) || body, totalLen = 1 + body.length
//     kind 'I' (0x49) = raw input bytes
//     kind 'R' (0x52) = {"cols":N,"rows":M}
//   stdout: raw VT bytes
//   stderr: one JSON line {"exit":code} when the child exits, or
//     {"error":"..."} + exit 1 when the host cannot start.
//
// Closing the host's stdin (EOF) or killing the host tears the whole tree down:
// the child runs inside a job object with KILL_ON_JOB_CLOSE.

import { execFile, spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { statSync } from "node:fs";
import os from "node:os";
import { resolve as resolvePath } from "node:path";

import { guardChildStreams } from "./child-guards.mjs";
import { ensureHelperBuilt } from "./screen-helper.mjs";

const KIND_INPUT = 0x49; // 'I'
const KIND_RESIZE = 0x52; // 'R'
// The helper refuses frames over 1 MiB. 64 KiB bodies leave a wide margin and
// keep one paste from becoming a single giant allocation on either side.
const MAX_FRAME_BODY = 64 * 1024;
const MAX_TERMINALS = 8;
const DEFAULT_COLS = 120;
const DEFAULT_ROWS = 30;
const MIN_DIM = 1;
const MAX_DIM = 1000;
const KILL_TIMEOUT_MS = 3_000;
const RETENTION_MS = 60_000;
const SETTLE_MS = 300;

async function defaultSpawnHost(args) {
    const exe = await ensureHelperBuilt();
    return spawn(exe, ["pty", ...args], {
        stdio: ["pipe", "pipe", "pipe"],
        windowsHide: true,
    });
}

// Second guarantee behind stdin EOF: /T walks the tree (host -> conhost ->
// shell -> grandchildren) and /F stops a wedged child from surviving. argv,
// never a shell string, because a shell would re-parse it on a machine that is
// already suspected of misbehaving.
function defaultTreeKill(child) {
    if (!child) return;
    // Kill the Go host synchronously FIRST: the child lives inside a job
    // object with KILL_ON_JOB_CLOSE held by that host, so terminating it makes
    // the kernel close the handle and tear the tree down even when our event
    // loop is already stopped inside a process 'exit' handler and taskkill can
    // never run. It also marks child.killed immediately.
    try {
        child.kill();
    } catch {
        /* already gone */
    }
    if (process.platform === "win32" && child.pid) {
        try {
            execFile("taskkill", ["/PID", String(child.pid), "/T", "/F"], () => {});
        } catch {
            /* best effort: the synchronous kill above already closed the job */
        }
    }
}

function typedError(code, message) {
    const err = new Error(message);
    err.code = code;
    return err;
}

function badRequest(message) {
    return typedError("BAD_REQUEST", message);
}

function validateDims(cols, rows) {
    if (
        !Number.isInteger(cols) || !Number.isInteger(rows) ||
        cols < MIN_DIM || cols > MAX_DIM ||
        rows < MIN_DIM || rows > MAX_DIM
    ) {
        throw badRequest(
            `cols and rows must be integers in [${MIN_DIM}, ${MAX_DIM}]`,
        );
    }
}

function normalizeShell(shell) {
    if (typeof shell === "string" && shell.trim()) return [shell];
    if (
        Array.isArray(shell) && shell.length > 0 &&
        shell.every((arg) => typeof arg === "string")
    ) {
        return [...shell];
    }
    if (shell === undefined || shell === null) {
        return process.platform === "win32"
            ? ["powershell.exe", "-NoLogo"]
            : [process.env.SHELL || "/bin/bash"];
    }
    throw badRequest("shell must be a non-empty string or a string array");
}

// Keeps the last `limit` bytes of raw output for reconnect replay. Chunks are
// kept whole and trimmed from the front, so replay is one Buffer.concat and a
// 200 KB paste never becomes one Buffer per keystroke.
class RingBuffer {
    constructor(limit) {
        this.limit = Math.max(0, limit | 0);
        this.chunks = [];
        this.length = 0;
    }

    append(chunk) {
        if (!chunk || chunk.length === 0) return;
        this.chunks.push(chunk);
        this.length += chunk.length;
        while (this.length > this.limit && this.chunks.length > 0) {
            const excess = this.length - this.limit;
            const first = this.chunks[0];
            if (first.length <= excess) {
                this.chunks.shift();
                this.length -= first.length;
            } else {
                this.chunks[0] = first.subarray(excess);
                this.length -= excess;
            }
        }
    }

    snapshot() {
        return Buffer.concat(this.chunks, this.length);
    }
}

export class PtyManager {
    /**
     * @param {{
     *   spawnHost?: (args: string[]) => any,
     *   ringBytes?: number,
     *   log?: { info?: Function, log?: Function, warn?: Function, error?: Function },
     *   killTree?: (child: any) => void,
     *   reapMs?: number,
     *   settleMs?: number,
     * }} [options]
     * `spawnHost(args)` receives everything after the `pty` word (the default
     * prepends `pty` and resolves the helper exe); it is injectable so tests
     * never boot a Go binary. `killTree`, `reapMs` and `settleMs` are test
     * seams too.
     */
    constructor({
        spawnHost,
        ringBytes = 256 * 1024,
        log = console,
        killTree,
        reapMs = RETENTION_MS,
        settleMs = SETTLE_MS,
    } = {}) {
        this.spawnHost = spawnHost || defaultSpawnHost;
        this.ringBytes = ringBytes;
        this.log = log;
        this.killTree = killTree || defaultTreeKill;
        this.reapMs = reapMs;
        this.settleMs = settleMs;
        this.maxTerminals = MAX_TERMINALS;
        this.pendingCreates = 0;
        this.entries = new Map();
    }

    async create({ cols = DEFAULT_COLS, rows = DEFAULT_ROWS, cwd, shell } = {}) {
        validateDims(cols, rows);
        const rawCwd = cwd === undefined || cwd === null || cwd === ""
            ? os.homedir()
            : String(cwd);
        const targetCwd = resolvePath(rawCwd);
        // UNC (\\server\share) and device (\\?\, \\.\) paths name another
        // machine or a device namespace, not a directory this server owns.
        // statSync on them can block or reach somewhere the caller never
        // meant, so they are refused before any filesystem call.
        if (targetCwd.startsWith("\\\\") || targetCwd.startsWith("//")) {
            throw badRequest(
                `cwd must be a local path, not a UNC/device path: ${rawCwd}`,
            );
        }
        let stat = null;
        try {
            stat = statSync(targetCwd);
        } catch {
            throw badRequest(`cwd is not an existing directory: ${targetCwd}`);
        }
        if (!stat.isDirectory()) {
            throw badRequest(`cwd is not an existing directory: ${targetCwd}`);
        }

        // In-flight creations count as occupied: create() awaits spawnHost,
        // and without the pending counter concurrent POSTs would each read the
        // same live count and all pass the limit.
        const alive = [...this.entries.values()].filter((entry) => entry.alive).length;
        if (alive + this.pendingCreates >= this.maxTerminals) {
            throw typedError(
                "LIMIT",
                `too many concurrent terminals (max ${this.maxTerminals})`,
            );
        }

        const argv = normalizeShell(shell);
        const id = "pty-" + randomBytes(8).toString("hex");
        const args = [
            "--cols", String(cols),
            "--rows", String(rows),
            "--cwd", targetCwd,
            "--",
            ...argv,
        ];
        this.pendingCreates += 1;
        let child;
        try {
            child = await this.spawnHost(args);
        } finally {
            this.pendingCreates -= 1;
        }
        if (!child || typeof child.on !== "function") {
            throw new Error("pty host failed to start");
        }

        const entry = {
            id,
            pid: child.pid,
            cols,
            rows,
            cwd: targetCwd,
            shell: argv.join(" "),
            createdAt: new Date().toISOString(),
            alive: true,
            exitCode: null,
            error: null,
            settled: false,
            child,
            ring: new RingBuffer(this.ringBytes),
            subscribers: new Set(),
            stderrBuf: "",
            resolveExit: null,
            settlePromise: null,
            resolveSettle: null,
        };
        entry.exited = new Promise((resolve) => {
            entry.resolveExit = resolve;
        });
        entry.settlePromise = new Promise((resolve) => {
            entry.resolveSettle = resolve;
        });
        this.entries.set(id, entry);
        this.#wire(entry);
        // A host that cannot start reports it on stderr and exits almost
        // immediately. One short window before answering lets create() and
        // list() say `alive:false` + `error` instead of advertising a dead
        // terminal; a healthy host's first output ends the wait in one event.
        await this.#settle(entry);
        return this.#info(entry);
    }

    /** Public info for one terminal, or null when the id is unknown. */
    get(id) {
        const entry = this.entries.get(id);
        return entry ? this.#info(entry) : null;
    }

    list() {
        return [...this.entries.values()].map((entry) => this.#info(entry));
    }

    /**
     * Replays the buffered tail, then streams live chunks.
     * @param {(chunk: Buffer) => void} onData
     * @param {(exitCode: number | null) => void} onExit
     * @returns {() => void} unsubscribe
     */
    subscribe(id, onData, onExit) {
        const entry = this.entries.get(id);
        if (!entry) {
            // The response must still terminate: an unknown id is a client
            // error, not a stream that stays open forever.
            queueMicrotask(() => {
                try {
                    onExit?.(null);
                } catch {
                    /* subscriber errors are never ours to propagate */
                }
            });
            return () => {};
        }
        const subscriber = {
            onData: typeof onData === "function" ? onData : () => {},
            onExit: typeof onExit === "function" ? onExit : () => {},
        };
        // Replay BEFORE registering live delivery: JS is single-threaded, so no
        // stdout event can interleave between the snapshot and the add, and a
        // reconnecting client sees the tail exactly once, in order.
        const replay = entry.ring.snapshot();
        if (replay.length > 0) {
            try {
                subscriber.onData(replay);
            } catch (err) {
                this.log.warn(
                    `[pty ${entry.id}] replay subscriber error: ${err && err.message}`,
                );
            }
        }
        if (!entry.alive) {
            // Replayed tail is already on its way; the stream ends next tick.
            queueMicrotask(() => {
                try {
                    subscriber.onExit(entry.exitCode);
                } catch {
                    /* subscriber errors are never ours to propagate */
                }
            });
            return () => {};
        }
        entry.subscribers.add(subscriber);
        return () => {
            entry.subscribers.delete(subscriber);
        };
    }

    /** @param {string | Buffer} data raw bytes for the child's stdin */
    write(id, data) {
        const entry = this.entries.get(id);
        if (!entry || !entry.alive) return false;
        const buf = Buffer.isBuffer(data)
            ? data
            : Buffer.from(String(data ?? ""), "utf8");
        if (buf.length === 0) return true;
        for (let offset = 0; offset < buf.length; offset += MAX_FRAME_BODY) {
            const body = buf.subarray(
                offset,
                Math.min(offset + MAX_FRAME_BODY, buf.length),
            );
            const frame = Buffer.allocUnsafe(5 + body.length);
            frame.writeUInt32BE(1 + body.length, 0);
            frame[4] = KIND_INPUT;
            body.copy(frame, 5);
            try {
                entry.child.stdin.write(frame);
            } catch (err) {
                this.log.warn(
                    `[pty ${entry.id}] stdin write failed: ${err && (err.code || err.message)}`,
                );
                return false;
            }
        }
        return true;
    }

    resize(id, cols, rows) {
        validateDims(cols, rows);
        const entry = this.entries.get(id);
        if (!entry || !entry.alive) return false;
        const body = Buffer.from(JSON.stringify({ cols, rows }), "utf8");
        const frame = Buffer.allocUnsafe(5 + body.length);
        frame.writeUInt32BE(1 + body.length, 0);
        frame[4] = KIND_RESIZE;
        body.copy(frame, 5);
        try {
            entry.child.stdin.write(frame);
        } catch (err) {
            this.log.warn(
                `[pty ${entry.id}] resize write failed: ${err && (err.code || err.message)}`,
            );
            return false;
        }
        entry.cols = cols;
        entry.rows = rows;
        return true;
    }

    /** Tree-kills one terminal. Resolves true when the id was known. */
    async kill(id) {
        const entry = this.entries.get(id);
        if (!entry) return false;
        if (entry.alive) {
            // EOF on stdin is the helper's shutdown contract; taskkill /T /F
            // is the second guarantee when the host itself is wedged.
            try {
                entry.child.stdin.end();
            } catch {
                /* already closed */
            }
            try {
                this.killTree(entry.child);
            } catch (err) {
                this.log.warn(
                    `[pty ${entry.id}] tree kill failed: ${err && (err.code || err.message)}`,
                );
            }
            let timer;
            await Promise.race([
                entry.exited,
                new Promise((resolve) => {
                    timer = setTimeout(resolve, KILL_TIMEOUT_MS);
                    timer.unref?.();
                }),
            ]);
            clearTimeout(timer);
            // A host that ignored both requests must not linger in our map.
            this.#finish(entry, null);
        }
        this.entries.delete(id);
        return true;
    }

    /** Kills every PTY; used when the server itself shuts down. */
    async shutdownAll() {
        const ids = [...this.entries.keys()];
        await Promise.allSettled(ids.map((id) => this.kill(id)));
    }

    #info(entry) {
        return {
            id: entry.id,
            pid: entry.pid,
            cols: entry.cols,
            rows: entry.rows,
            cwd: entry.cwd,
            shell: entry.shell,
            createdAt: entry.createdAt,
            alive: entry.alive,
            exitCode: entry.exitCode,
            error: entry.error,
        };
    }

    // Resolves at most `settleMs` after spawn, or as soon as the host proves
    // it started (first stdout byte) or failed (stderr error line / exit).
    async #settle(entry) {
        if (entry.settled) return;
        let timer;
        const timeout = new Promise((resolve) => {
            timer = setTimeout(resolve, this.settleMs);
        });
        await Promise.race([entry.settlePromise, timeout]);
        clearTimeout(timer);
        // The helper contract for a startup failure is {"error":"..."} + exit
        // 1. Once that line landed, the terminal is dead even if the exit event
        // has not been processed yet; a later exit is a no-op (#finish guards).
        if (entry.alive && entry.error) {
            this.#finish(entry, null, entry.error);
        }
    }

    #settleNow(entry) {
        if (!entry.resolveSettle) return;
        const resolve = entry.resolveSettle;
        entry.resolveSettle = null;
        resolve();
    }

    #wire(entry) {
        const child = entry.child;
        guardChildStreams(child, `pty ${entry.id}`, this.log);
        child.stdout.on("data", (chunk) => this.#onData(entry, chunk));
        child.stderr.on("data", (chunk) => this.#onStderr(entry, chunk));
        // Spawn failures (ENOENT/EACCES) are emitted on the child itself; with
        // no listener Node throws them and takes the whole server down.
        child.on("error", (err) => {
            this.log.warn(
                `[pty ${entry.id}] host error: ${err && (err.code || err.message)}`,
            );
            this.#finish(entry, null, err && err.message);
        });
        child.on("close", (code) => this.#finish(entry, code));
    }

    #onData(entry, chunk) {
        if (!entry.alive || !chunk || chunk.length === 0) return;
        // First output byte proves the host started; create() may stop waiting.
        this.#settleNow(entry);
        const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
        entry.ring.append(buf);
        for (const subscriber of entry.subscribers) {
            try {
                subscriber.onData(buf);
            } catch (err) {
                this.log.warn(
                    `[pty ${entry.id}] subscriber error: ${err && err.message}`,
                );
            }
        }
    }

    #onStderr(entry, chunk) {
        entry.stderrBuf += chunk.toString("utf8");
        // A host that floods stderr without a newline must not grow forever.
        if (entry.stderrBuf.length > 64 * 1024) {
            entry.stderrBuf = entry.stderrBuf.slice(-1024);
        }
        let newline;
        while ((newline = entry.stderrBuf.indexOf("\n")) >= 0) {
            const line = entry.stderrBuf.slice(0, newline).trim();
            entry.stderrBuf = entry.stderrBuf.slice(newline + 1);
            if (!line) continue;
            let parsed;
            try {
                parsed = JSON.parse(line);
            } catch {
                this.log.warn(`[pty ${entry.id}] stderr: ${line.slice(0, 200)}`);
                continue;
            }
            if (Number.isInteger(parsed.exit)) {
                entry.exitCode = parsed.exit;
            } else if (typeof parsed.error === "string") {
                entry.error = parsed.error;
                this.log.warn(`[pty ${entry.id}] host error: ${parsed.error}`);
                // A startup failure must not leave create() waiting the whole
                // settle window: the error line is already the verdict.
                this.#settleNow(entry);
            }
        }
    }

    #finish(entry, code, error) {
        if (entry.settled) return;
        entry.settled = true;
        entry.alive = false;
        this.#settleNow(entry);
        if (entry.exitCode === null && Number.isInteger(code)) {
            entry.exitCode = code;
        }
        if (error) entry.error = error;
        entry.resolveExit?.();
        const subscribers = [...entry.subscribers];
        entry.subscribers.clear();
        for (const subscriber of subscribers) {
            try {
                subscriber.onExit(entry.exitCode);
            } catch (err) {
                this.log.warn(
                    `[pty ${entry.id}] exit subscriber error: ${err && err.message}`,
                );
            }
        }
        // Keep the entry around briefly: a client reconnecting right after the
        // child exited must still receive the buffered tail and the exit code.
        const timer = setTimeout(() => {
            if (this.entries.get(entry.id) === entry) this.entries.delete(entry.id);
        }, this.reapMs);
        timer.unref?.();
    }
}
