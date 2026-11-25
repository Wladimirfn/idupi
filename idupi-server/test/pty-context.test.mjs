// Tests for lib/pty-context.mjs: the process tree walk, agent recognition,
// cwd fallback chain, git parsing, caching and failure degradation. Every
// dependency is injected, so no PowerShell, Go helper or git runs here.

import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { readFile } from "node:fs/promises";

import {
    agentFromCommandLine,
    createPtyContext,
    matchAgent,
    parseProcCwdOutput,
    parseProcessSample,
} from "../lib/pty-context.mjs";
import { createPtyRoutes } from "../lib/pty-routes.mjs";

function row(pid, parentPid, name, commandLine = null) {
    return { pid, parentPid, name, commandLine };
}

function sample(rows) {
    return async () => rows;
}

function cwdReader(map) {
    const calls = [];
    const read = async (pids) => {
        calls.push([...pids]);
        const out = new Map();
        for (const pid of pids) {
            out.set(Number(pid), Object.hasOwn(map, pid) ? map[pid] : null);
        }
        return out;
    };
    read.calls = calls;
    return read;
}

function gitFake({
    ref = "main",
    short = "abc1234",
    toplevel = () => "C:\\projects\\alpha",
    fail = false,
} = {}) {
    const calls = [];
    const exec = async (file, args) => {
        calls.push([file, ...args]);
        if (fail) throw new Error("git: not a repository");
        const cwd = args[1];
        if (args.includes("--show-toplevel")) return { stdout: `${toplevel(cwd)}\n${ref}\n` };
        if (args.includes("--short")) return { stdout: `${short}\n` };
        throw new Error(`unexpected git args: ${args.join(" ")}`);
    };
    exec.calls = calls;
    return exec;
}

const stdout = (text) => async () => ({ stdout: text });

// --- agent recognition ------------------------------------------------------

test("agent matcher recognises names, node packages and skips the shell", () => {
    const cases = [
        [{ name: "claude.exe", commandLine: null }, "claude"],
        [{ name: "opencode.exe", commandLine: null }, "opencode"],
        [{ name: "cmdc.exe", commandLine: null }, "cmdc"],
        [{ name: "node", commandLine: "node C:\\npm\\node_modules\\@openai\\codex\\bin\\codex.js" }, "codex"],
        [{ name: "node", commandLine: "node C:\\npm\\node_modules\\@anthropic-ai\\claude-code\\cli.js" }, "claude"],
        [{ name: "node", commandLine: "node C:\\npm\\node_modules\\opencode-ai\\bin\\opencode" }, "opencode"],
        [{ name: "bun", commandLine: "bun C:\\tools\\gemini" }, "gemini"],
        [{ name: "node", commandLine: "node C:\\tools\\mcp-server.js" }, null],
        [{ name: "cmd.exe", commandLine: "cmd /c claude" }, null],
        [{ name: "conhost.exe", commandLine: null }, null],
        [{ name: "ollama.exe", commandLine: null }, null],
    ];
    for (const [entry, want] of cases) {
        assert.equal(matchAgent(entry), want, `matchAgent(${JSON.stringify(entry)})`);
    }
    assert.equal(agentFromCommandLine(""), null);
});

test("parses the PowerShell process sample and helper JSON", () => {
    const rows = parseProcessSample(JSON.stringify([
        { ProcessId: 10, ParentProcessId: 4, Name: "node.exe", CommandLine: "node x.js" },
        { ProcessId: 0, ParentProcessId: 4, Name: "bad" },
        { ProcessId: 11, ParentProcessId: null, Name: "explorer.exe", CommandLine: null },
    ]));
    assert.deepEqual(rows, [
        { pid: 10, parentPid: 4, name: "node.exe", commandLine: "node x.js" },
        { pid: 11, parentPid: null, name: "explorer.exe", commandLine: null },
    ]);

    const cwds = parseProcCwdOutput(String.raw`{"10":"C:\\work\\alpha","20":null,"x":"C:\\nope"}`);
    assert.equal(cwds.get(10), "C:\\work\\alpha");
    assert.equal(cwds.get(20), null);
    assert.equal(cwds.has(Number.NaN), false);
});

// --- tree walking -----------------------------------------------------------

test("walks the tree, skips console hosts and picks the agent the user launched", async () => {
    // Real case: Claude Code started in the shell delegates to an OpenCode
    // worker. The terminal is Claude's; the nested worker must not relabel it.
    const rows = [
        row(500, 1, "idupi-screen.exe"),
        row(600, 500, "conhost.exe"),
        row(700, 500, "powershell.exe", "powershell.exe -NoLogo"),
        row(800, 700, "node.exe", "node C:\\npm\\node_modules\\opencode-ai\\bin\\opencode"),
        row(810, 800, "node.exe", "node C:\\npm\\node_modules\\@openai\\codex\\bin\\codex.js"),
        row(820, 810, "conhost.exe"),
    ];
    const readCwds = cwdReader({ 800: "C:\\work\\alpha", 700: "C:\\Users\\owner" });
    const provider = createPtyContext({
        sampleProcesses: sample(rows),
        readCwds,
        execFile: gitFake(),
        now: () => 1_000,
    });

    const context = (await provider.get([{ id: "pty-1", pid: 500, cwd: "C:\\Users\\owner" }]))
        .get("pty-1");

    assert.equal(context.cwd, "C:\\work\\alpha");
    assert.equal(context.project, "alpha");
    assert.equal(context.branch, "main");
    assert.equal(context.detached, false);
    assert.deepEqual(context.agent, { id: "opencode", name: "OpenCode", pid: 800 });
    assert.deepEqual([...readCwds.calls[0]].sort((a, b) => a - b), [700, 800]);
});

test("no agent: the shell pid provides the cwd, never conhost", async () => {
    const rows = [
        row(500, 1, "idupi-screen.exe"),
        row(510, 500, "conhost.exe"),
        row(520, 500, "powershell.exe"),
        row(530, 520, "node.exe", "node C:\\tools\\dev.js"),
    ];
    const readCwds = cwdReader({ 520: "C:\\Users\\owner" });
    const provider = createPtyContext({
        sampleProcesses: sample(rows),
        readCwds,
        execFile: gitFake({ toplevel: () => "C:\\users\\owner" }),
        now: () => 1_000,
    });

    const context = (await provider.get([{ id: "pty-1", pid: 500, cwd: "C:\\launch" }])).get("pty-1");

    assert.deepEqual(context.agent, null);
    assert.equal(context.cwd, "C:\\Users\\owner");
    assert.deepEqual(readCwds.calls[0], [520]);
});

test("cwd falls back from the agent to the shell and then to the launch cwd", async () => {
    const rows = [
        row(500, 1, "idupi-screen.exe"),
        row(510, 500, "conhost.exe"),
        row(520, 500, "powershell.exe"),
        row(530, 520, "claude.exe"),
        row(600, 1, "idupi-screen.exe"),
        row(620, 600, "powershell.exe"),
    ];
    const readCwds = cwdReader({ 530: null, 520: "C:\\from\\shell", 620: null });
    const provider = createPtyContext({
        sampleProcesses: sample(rows),
        readCwds,
        execFile: gitFake(),
        now: () => 1_000,
    });

    const contexts = await provider.get([
        { id: "pty-agent", pid: 500, cwd: "C:\\launch\\one" },
        { id: "pty-shell", pid: 600, cwd: "C:\\launch\\two" },
    ]);

    assert.equal(contexts.get("pty-agent").cwd, "C:\\from\\shell");
    assert.deepEqual(contexts.get("pty-agent").agent, { id: "claude", name: "Claude Code", pid: 530 });
    assert.equal(contexts.get("pty-shell").cwd, "C:\\launch\\two");
    assert.deepEqual(contexts.get("pty-shell").agent, null);
});

// --- git --------------------------------------------------------------------

async function contextForTerminal(gitExec, cwd = "C:\\work\\alpha") {
    const provider = createPtyContext({
        sampleProcesses: sample([row(500, 1, "idupi-screen.exe"), row(700, 500, "powershell.exe")]),
        readCwds: cwdReader({ 700: cwd }),
        execFile: gitExec,
        now: () => 1_000,
    });
    return (await provider.get([{ id: "pty-1", pid: 500, cwd }])).get("pty-1");
}

test("git: resolves the project from the toplevel and the branch", async () => {
    const context = await contextForTerminal(
        gitFake({ toplevel: () => "C:/work/alpha", ref: "feature/terminal-context" }),
    );
    assert.equal(context.project, "alpha");
    assert.equal(context.branch, "feature/terminal-context");
    assert.equal(context.detached, false);
});

test("git: a detached HEAD answers the short sha and is flagged", async () => {
    const git = gitFake({ toplevel: () => "C:\\work\\alpha", ref: "HEAD", short: "abc1234" });
    const context = await contextForTerminal(git);

    assert.equal(context.project, "alpha");
    assert.equal(context.branch, "abc1234");
    assert.equal(context.detached, true);
    assert.ok(
        git.calls.some((call) => call.includes("--short")),
        "a detached HEAD needs the short-sha fallback",
    );
});

test("git: outside a repository the folder is the project and branch is null", async () => {
    const context = await contextForTerminal(gitFake({ fail: true }));
    assert.equal(context.project, "alpha");
    assert.equal(context.branch, null);
    assert.equal(context.detached, false);
});

test("git results are cached per cwd across terminals and samples", async () => {
    const git = gitFake();
    const rows = [
        row(500, 1, "idupi-screen.exe"),
        row(520, 500, "powershell.exe"),
        row(600, 1, "idupi-screen.exe"),
        row(620, 600, "powershell.exe"),
    ];
    const provider = createPtyContext({
        sampleProcesses: sample(rows),
        readCwds: cwdReader({ 520: "C:\\work\\alpha", 620: "C:\\work\\alpha" }),
        execFile: git,
        now: () => 1_000,
        cacheMs: 0,
    });

    await provider.get([
        { id: "pty-1", pid: 500, cwd: "C:\\work\\alpha" },
        { id: "pty-2", pid: 600, cwd: "C:\\work\\alpha" },
    ]);
    assert.equal(git.calls.length, 1, "one git call per unique cwd, not per terminal");

    await provider.get([
        { id: "pty-1", pid: 500, cwd: "C:\\work\\alpha" },
        { id: "pty-2", pid: 600, cwd: "C:\\work\\alpha" },
    ]);
    assert.equal(git.calls.length, 1, "the per-cwd cache serves the next sample");
});

// --- caching and failures ---------------------------------------------------

test("caches the context and never samples without terminals", async () => {
    let clock = 1_000;
    let samples = 0;
    const provider = createPtyContext({
        sampleProcesses: async () => {
            samples += 1;
            return [row(500, 1, "idupi-screen.exe"), row(700, 500, "powershell.exe")];
        },
        readCwds: cwdReader({ 700: "C:\\work\\alpha" }),
        execFile: gitFake(),
        now: () => clock,
        cacheMs: 3_000,
    });
    const terminals = [{ id: "pty-1", pid: 500, cwd: "C:\\work\\alpha" }];

    assert.equal((await provider.get([])).size, 0);
    assert.equal(samples, 0, "no terminals, no PowerShell");

    await provider.get(terminals);
    await provider.get(terminals);
    assert.equal(samples, 1, "the second call inside the window is cached");

    clock += 3_001;
    await provider.get(terminals);
    assert.equal(samples, 2, "the window expiry recomputes");

    await provider.get([{ id: "pty-2", pid: 600, cwd: "C:\\work\\alpha" }]);
    assert.equal(samples, 3, "a different terminal set is never served stale");
});

test("every failure degrades to nulls instead of throwing", async () => {
    const provider = createPtyContext({
        sampleProcesses: async () => {
            throw new Error("sample down");
        },
        readCwds: async () => {
            throw new Error("helper down");
        },
        execFile: async () => {
            throw new Error("git down");
        },
        now: () => 1,
    });

    const contexts = await provider.get([{ id: "pty-x", pid: 4_242 }]);
    assert.deepEqual(contexts.get("pty-x"), {
        cwd: null,
        project: null,
        branch: null,
        detached: false,
        agent: null,
    });
});

// --- route wiring -----------------------------------------------------------

const silentLog = { log() {}, info() {}, warn() {}, error() {} };

async function startRouteHarness(t, manager, options = {}) {
    const handle = createPtyRoutes(manager, options);
    const server = http.createServer(async (req, res) => {
        try {
            const pathname = new URL(req.url, "http://localhost").pathname;
            if (await handle(req, res, pathname)) return;
            res.writeHead(404, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: "unhandled" }));
        } catch (err) {
            if (!res.headersSent) res.writeHead(500);
            res.end(JSON.stringify({ error: err.message }));
        }
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    t.after(async () => {
        server.closeAllConnections?.();
        await new Promise((resolve) => server.close(resolve));
    });
    return `http://127.0.0.1:${server.address().port}`;
}

test("GET /api/v1/pty adds context and keeps every existing field", async (t) => {
    const terminal = {
        id: "pty-1",
        pid: 500,
        cols: 80,
        rows: 24,
        cwd: "C:\\Users\\owner",
        shell: "powershell.exe -NoLogo",
        createdAt: "2026-01-01T00:00:00.000Z",
        alive: true,
        exitCode: null,
        error: null,
    };
    const context = {
        get: async (terminals) => {
            assert.equal(terminals.length, 1);
            return new Map([["pty-1", {
                cwd: "C:\\work\\alpha",
                project: "alpha",
                branch: "main",
                detached: false,
                agent: { id: "claude", name: "Claude Code", pid: 530 },
            }]]);
        },
    };
    const base = await startRouteHarness(
        t,
        { list: () => [{ ...terminal }], get: () => null },
        { context, log: silentLog },
    );

    const res = await fetch(`${base}/api/v1/pty`);
    assert.equal(res.status, 200);
    const body = await res.json();

    assert.deepEqual(body.terminals[0].context, {
        cwd: "C:\\work\\alpha",
        project: "alpha",
        branch: "main",
        detached: false,
        agent: { id: "claude", name: "Claude Code", pid: 530 },
    });
    for (const [key, value] of Object.entries(terminal)) {
        assert.deepEqual(body.terminals[0][key], value, `existing field ${key} changed`);
    }
});

test("GET /api/v1/pty survives a context failure and stays behind auth in index.mjs", async (t) => {
    const terminal = { id: "pty-1", pid: 500, cols: 80, rows: 24, cwd: "C:\\x", shell: "s", alive: true };
    const base = await startRouteHarness(
        t,
        { list: () => [{ ...terminal }], get: () => null },
        {
            context: {
                get: async () => {
                    throw new Error("sampler exploded");
                },
            },
            log: silentLog,
        },
    );

    const res = await fetch(`${base}/api/v1/pty`);
    assert.equal(res.status, 200, "a context failure is not a terminal-list failure");
    const body = await res.json();
    assert.equal(body.terminals[0].context, null);

    const source = await readFile(new URL("../index.mjs", import.meta.url), "utf8");
    const authGate = source.indexOf("if (!requireAuth(req, res)) return;");
    const ptyWire = source.indexOf("handlePtyRoute(req, res, pathname)");
    assert.ok(authGate > -1, "the bearer-token gate must exist");
    assert.ok(ptyWire > -1, "the PTY handler must be called in the route chain");
    assert.ok(ptyWire > authGate, "the PTY handler must run after the bearer check");
});
