// Tests for the `idupi-term` CLI (live-shared-terminals, T3).
//
// The pure logic in lib/term-cli.mjs is covered directly. The attach contract
// (resize on attach, input order, stream bytes to stdout, exit message) is
// covered end to end: a fake PTY API on an ephemeral port, and the real bin
// spawned with IDUPI_PORT/IDUPI_TOKEN and a piped (non-TTY) stdin.
//
// Every token here is fake; no test reads the real .idupi-token file.

import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import http from "node:http";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import {
    DEFAULT_PORT,
    MAX_INPUT_CHUNK_BYTES,
    chunkUtf8,
    createInputDecoder,
    formatAge,
    formatExitMessage,
    formatTerminalList,
    nextBackoff,
    parseArgs,
    parseSelection,
    resolvePort,
    resolveRef,
    resolveToken,
    shellName,
    shortId,
    splitDetachKey,
} from "../lib/term-cli.mjs";

const BIN = join(
    dirname(fileURLToPath(import.meta.url)),
    "..",
    "bin",
    "idupi-term.mjs",
);
const FAKE_TOKEN = "fake-token-for-tests-123";

// --- pure logic ------------------------------------------------------------

test("parseArgs maps every command and rejects malformed ones", () => {
    assert.deepEqual(parseArgs([]), { command: "list", interactive: true });
    assert.equal(parseArgs(["ls"]).command, "ls");
    assert.equal(parseArgs(["new"]).cwd, null);
    assert.equal(parseArgs(["new", "--cwd", "C:\\work"]).cwd, "C:\\work");
    assert.match(parseArgs(["new", "--cwd"]).error, /Uso/);
    assert.equal(parseArgs(["attach", "abc"]).ref, "abc");
    assert.match(parseArgs(["attach"]).error, /Uso/);
    assert.equal(parseArgs(["web"]).command, "web");
    assert.equal(parseArgs(["help"]).command, "help");
    assert.equal(parseArgs(["--help"]).command, "help");
    assert.equal(parseArgs(["wat"]).command, "help");
    assert.equal(parseArgs(["wat"]).unknown, "wat");
});

test("resolvePort prefers IDUPI_PORT and refuses malformed values", () => {
    assert.equal(resolvePort({}), DEFAULT_PORT);
    assert.equal(resolvePort({ IDUPI_PORT: "9123" }), 9123);
    assert.equal(resolvePort({ IDUPI_PORT: " 9123 " }), 9123);
    assert.throws(() => resolvePort({ IDUPI_PORT: "nope" }));
    assert.throws(() => resolvePort({ IDUPI_PORT: "70000" }));
    assert.throws(() => resolvePort({ IDUPI_PORT: "0" }));
});

test("resolveToken uses a fake token file then the environment", (t) => {
    const dir = mkdtempSync(join(tmpdir(), "idupi-term-"));
    const tokenPath = join(dir, ".idupi-token");
    writeFileSync(tokenPath, "  file-token-abc  \n");
    t.after(() => rmSync(dir, { recursive: true, force: true }));

    assert.equal(
        resolveToken({ env: {}, tokenPath, readFile: readFileSync }),
        "file-token-abc",
    );
    assert.equal(
        resolveToken({
            env: { IDUPI_TOKEN: " env-token-xyz " },
            tokenPath,
            readFile: readFileSync,
        }),
        "env-token-xyz",
    );
    assert.equal(
        resolveToken({ env: {}, tokenPath: join(dir, "missing"), readFile: readFileSync }),
        null,
    );
    assert.equal(resolveToken({ env: {} }), null);
});

test("shortId and shellName keep the list compact", () => {
    assert.equal(shortId("pty-abcdef123456"), "abcdef");
    assert.equal(shortId(""), "");
    assert.equal(shellName("powershell.exe -NoLogo"), "powershell.exe");
    assert.equal(shellName("C:\\Windows\\System32\\cmd.exe /k"), "cmd.exe");
    assert.equal(shellName(""), "shell");
});

test("formatAge renders seconds, minutes, hours and days", () => {
    const now = Date.parse("2026-01-01T12:00:00Z");
    assert.equal(formatAge("2026-01-01T11:59:30Z", now), "30s");
    assert.equal(formatAge("2026-01-01T11:58:00Z", now), "2m");
    assert.equal(formatAge("2026-01-01T09:00:00Z", now), "3h");
    assert.equal(formatAge("2025-12-30T12:00:00Z", now), "2d");
    assert.equal(formatAge("not-a-date", now), "?");
});

test("formatTerminalList numbers rows with id, shell, size, state and age", () => {
    const now = Date.parse("2026-01-01T00:10:00Z");
    const terminals = [
        {
            id: "pty-abcdef123456",
            shell: "powershell.exe -NoLogo",
            cols: 120,
            rows: 30,
            alive: true,
            createdAt: "2026-01-01T00:08:00Z",
        },
        {
            id: "pty-1234567890ab",
            shell: "C:\\Windows\\System32\\cmd.exe",
            cols: 80,
            rows: 24,
            alive: false,
            createdAt: "2026-01-01T00:00:00Z",
        },
    ];
    const lines = formatTerminalList(terminals, now).split("\n");
    assert.equal(lines.length, 3);
    assert.match(lines[1], /^ +\d+\) /);
    for (const expected of ["abcdef", "powershell.exe", "120x30", "viva", "2m"]) {
        assert.ok(lines[1].includes(expected), `missing ${expected}`);
    }
    for (const expected of ["cmd.exe", "80x24", "finalizada", "10m"]) {
        assert.ok(lines[2].includes(expected), `missing ${expected}`);
    }
    assert.equal(formatTerminalList([]), "No hay terminales.");
});

test("resolveRef accepts full ids, unique prefixes and 1-based indexes", () => {
    const list = [
        { id: "pty-aaaa1111" },
        { id: "pty-aabb2222" },
        { id: "pty-cccc3333" },
    ];
    assert.equal(resolveRef("pty-aabb2222", list).id, "pty-aabb2222");
    assert.equal(resolveRef("pty-aab", list).id, "pty-aabb2222");
    assert.equal(resolveRef("1", list).id, "pty-aaaa1111");
    assert.equal(resolveRef("3", list).id, "pty-cccc3333");
    assert.equal(resolveRef("4", list).error, "missing");
    assert.equal(resolveRef("0", list).error, "missing");
    assert.equal(resolveRef("pty-aa", list).error, "ambiguous");
    assert.equal(resolveRef("pty-zzz", list).error, "missing");
    assert.equal(resolveRef("", list).error, "missing");
});

test("parseSelection treats Enter as 1 and n as a new terminal", () => {
    assert.deepEqual(parseSelection("", 3), { kind: "attach", index: 1 });
    assert.deepEqual(parseSelection(" 2 ", 3), { kind: "attach", index: 2 });
    assert.deepEqual(parseSelection("n", 3), { kind: "new" });
    assert.deepEqual(parseSelection("N", 3), { kind: "new" });
    assert.equal(parseSelection("0", 3).kind, "error");
    assert.equal(parseSelection("4", 3).kind, "error");
    assert.equal(parseSelection("x", 3).kind, "error");
});

test("splitDetachKey cuts at Ctrl+] and drops what follows", () => {
    const plain = Buffer.from("abc");
    const kept = splitDetachKey(plain);
    assert.equal(kept.detached, false);
    assert.deepEqual([...kept.data], [0x61, 0x62, 0x63]);

    const withKey = splitDetachKey(Buffer.from([0x61, 0x62, 0x1d, 0x63]));
    assert.equal(withKey.detached, true);
    assert.deepEqual([...withKey.data], [0x61, 0x62]);

    const atStart = splitDetachKey(Buffer.from([0x1d, 0x63]));
    assert.equal(atStart.detached, true);
    assert.equal(atStart.data.length, 0);
});

test("chunkUtf8 never splits a character and stays under the byte cap", () => {
    const text = "aá😀b".repeat(10);
    const chunks = chunkUtf8(text, 5);
    assert.ok(chunks.length > 1);
    assert.equal(chunks.join(""), text);
    for (const chunk of chunks) {
        assert.ok(Buffer.byteLength(chunk, "utf8") <= 5, chunk);
    }

    const long = "ñ".repeat(40_000); // 80 KB of two-byte characters
    const wide = chunkUtf8(long);
    assert.equal(wide.join(""), long);
    assert.ok(wide.length >= 2);
    for (const chunk of wide) {
        assert.ok(Buffer.byteLength(chunk, "utf8") <= MAX_INPUT_CHUNK_BYTES);
    }
});

test("the streaming decoder keeps a split multi-byte character whole", () => {
    const decoder = createInputDecoder();
    const enye = Buffer.from("ñ", "utf8");
    assert.equal(decoder.write(enye.subarray(0, 1)), "");
    assert.equal(decoder.write(enye.subarray(1)), "ñ");

    const emoji = Buffer.from("😀", "utf8");
    assert.equal(decoder.write(emoji.subarray(0, 2)), "");
    assert.equal(decoder.write(emoji.subarray(2)), "😀");
});

test("nextBackoff caps at four seconds", () => {
    assert.equal(nextBackoff(500), 1000);
    assert.equal(nextBackoff(1000), 2000);
    assert.equal(nextBackoff(2000), 4000);
    assert.equal(nextBackoff(4000), 4000);
    assert.equal(nextBackoff(Number.NaN), 1000);
});

test("formatExitMessage prints the code when it is known", () => {
    assert.equal(formatExitMessage(7), "Terminal finalizada (código 7).");
    assert.equal(formatExitMessage(0), "Terminal finalizada (código 0).");
    assert.equal(formatExitMessage(null), "Terminal finalizada.");
    assert.equal(formatExitMessage(undefined), "Terminal finalizada.");
});

// --- attach integration ----------------------------------------------------

function waitFor(predicate, timeoutMs = 5_000) {
    const deadline = Date.now() + timeoutMs;
    return new Promise((resolve, reject) => {
        const tick = () => {
            if (predicate()) {
                resolve();
                return;
            }
            if (Date.now() > deadline) {
                reject(new Error("condition not met in time"));
                return;
            }
            setTimeout(tick, 20);
        };
        tick();
    });
}

function startFakePtyServer(t, { expectedToken = FAKE_TOKEN, replay = "\x1b[32mREPLAY-OK\x1b[0m" } = {}) {
    const state = {
        terminals: [{
            id: "pty-inttest01",
            pid: 4242,
            cols: 120,
            rows: 30,
            cwd: "C:\\work",
            shell: "powershell.exe -NoLogo",
            createdAt: new Date().toISOString(),
            alive: true,
            exitCode: null,
            error: null,
        }],
        resizes: [],
        inputs: [],
        streamRes: null,
    };
    const server = http.createServer((req, res) => {
        if (req.headers.authorization !== `Bearer ${expectedToken}`) {
            res.writeHead(401, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: "No autorizado" }));
            return;
        }
        const pathname = new URL(req.url, "http://localhost").pathname;
        if (pathname === "/api/v1/pty" && req.method === "GET") {
            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ terminals: state.terminals }));
            return;
        }
        const match = /^\/api\/v1\/pty\/([^/]+)\/(stream|input|resize)$/.exec(pathname);
        if (!match) {
            res.writeHead(404, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: "not found" }));
            return;
        }
        const action = match[2];
        if (action === "stream" && req.method === "GET") {
            res.writeHead(200, { "Content-Type": "application/octet-stream" });
            res.write(Buffer.from(replay));
            state.streamRes = res;
            return;
        }
        const chunks = [];
        req.on("data", (chunk) => chunks.push(chunk));
        req.on("end", () => {
            const body = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
            if (action === "resize") state.resizes.push(body);
            if (action === "input") state.inputs.push(body.data);
            res.writeHead(204);
            res.end();
        });
    });
    return new Promise((resolve) => {
        server.listen(0, "127.0.0.1", () => {
            t.after(async () => {
                server.closeAllConnections?.();
                await new Promise((done) => server.close(done));
            });
            resolve({ state, port: server.address().port });
        });
    });
}

function spawnCli(t, args, port, { token = FAKE_TOKEN } = {}) {
    const child = spawn(process.execPath, [BIN, ...args], {
        env: { ...process.env, IDUPI_PORT: String(port), IDUPI_TOKEN: token },
        stdio: ["pipe", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk.toString("utf8"); });
    child.stderr.on("data", (chunk) => { stderr += chunk.toString("utf8"); });
    const exited = new Promise((resolve) => {
        child.on("close", (code, signal) => resolve({ code, signal }));
    });
    t.after(() => {
        if (child.exitCode === null && child.signalCode === null) child.kill();
    });
    return {
        child,
        exited,
        get stdout() { return stdout; },
        get stderr() { return stderr; },
    };
}

test("attach posts resize, forwards input in order, prints replay and the exit", async (t) => {
    const { state, port } = await startFakePtyServer(t);
    const cli = spawnCli(t, ["attach", "pty-inttest01"], port);

    await waitFor(() => state.resizes.length > 0 && state.streamRes !== null);
    assert.deepEqual(state.resizes[0], { cols: 120, rows: 30 });
    assert.ok(cli.stdout.includes("Conectado a inttes"));

    cli.child.stdin.write("a");
    await waitFor(() => state.inputs.join("").includes("a"));
    cli.child.stdin.write("b");
    await waitFor(() => state.inputs.join("") === "ab");
    assert.ok(cli.stdout.includes("REPLAY-OK"));

    state.terminals[0].alive = false;
    state.terminals[0].exitCode = 7;
    state.streamRes.end();

    const { code } = await cli.exited;
    assert.equal(code, 0);
    assert.ok(
        cli.stdout.includes("Terminal finalizada (código 7)."),
        cli.stdout,
    );
    assert.ok(!cli.stdout.includes(FAKE_TOKEN), "the token must never reach stdout");
    assert.ok(!cli.stderr.includes(FAKE_TOKEN), "the token must never reach stderr");
});

test("Ctrl+] detaches without killing the terminal", async (t) => {
    const { state, port } = await startFakePtyServer(t);
    const cli = spawnCli(t, ["attach", "1"], port);

    await waitFor(() => state.streamRes !== null && state.resizes.length > 0);
    cli.child.stdin.write(Buffer.from([0x78, 0x1d])); // "x" then Ctrl+]

    const { code } = await cli.exited;
    assert.equal(code, 0);
    assert.ok(
        cli.stdout.includes("Desconectado. La terminal sigue viva en la PC."),
        cli.stdout,
    );
    assert.ok(state.inputs.join("").includes("x"));
    assert.equal(state.terminals[0].alive, true);
});

test("a rejected token exits 1 with the token-file hint", async (t) => {
    const { port } = await startFakePtyServer(t);
    const cli = spawnCli(t, ["ls"], port, { token: "wrong-token" });

    const { code } = await cli.exited;
    assert.equal(code, 1);
    assert.ok(cli.stderr.includes("Token rechazado: revisá .idupi-token."), cli.stderr);
});

test("an unreachable server exits 1 with the start-server hint", async (t) => {
    // A server that accepts and immediately destroys every socket stands in
    // for "nothing is listening": the connection cannot carry a request.
    const server = http.createServer();
    server.on("connection", (socket) => socket.destroy());
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    t.after(async () => {
        server.closeAllConnections?.();
        await new Promise((done) => server.close(done));
    });
    const port = server.address().port;

    const cli = spawnCli(t, ["ls"], port);
    const { code } = await cli.exited;
    assert.equal(code, 1);
    assert.ok(
        cli.stderr.includes(`No se pudo conectar con IDUPI en 127.0.0.1:${port}.`),
        cli.stderr,
    );
    assert.ok(cli.stderr.includes("start-server.ps1"), cli.stderr);
});
