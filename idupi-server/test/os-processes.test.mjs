// Tests for the PC process list and end-process routes (app-redesign, T10).
//
// Three layers with the smallest harness that proves each:
//   - pure parsing and classification from a PowerShell JSON fixture;
//   - the sampler with injected execFile/os/clock, so no test spawns
//     PowerShell, PowerShell's CIM query, or taskkill;
//   - a real http.Server on an ephemeral port, wired exactly like index.mjs
//     (behind auth, which this harness stands in for), plus one static check
//     that index.mjs calls the handler AFTER requireAuth.
//
// Run (from repo root):
//   node --test idupi-server/test/os-processes.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { readFile } from "node:fs/promises";

import {
    createOsProcesses,
    parseProcessList,
} from "../lib/os-processes.mjs";
import { createOsProcessesRoutes } from "../lib/os-processes-routes.mjs";

const GIB = 1024 * 1024 * 1024;
const MIB = 1024 * 1024;
const SYSTEM_ROOT = "C:\\Windows";

function row({
    id,
    name,
    parent = null,
    title = "",
    window = false,
    path = null,
    memory = 50 * MIB,
    cpuMs = 0,
}) {
    return {
        Id: id,
        ProcessName: name,
        MainWindowTitle: title,
        HasMainWindow: window,
        Path: path,
        WorkingSet64: memory,
        TotalProcessorTimeMs: cpuMs,
        ParentPid: parent,
    };
}

function psJson(rows) {
    return JSON.stringify(rows);
}

function fakeOs({ cpuCount = 8, totalmemBytes = 32 * GIB } = {}) {
    return {
        cpus: () => Array.from({ length: cpuCount }, () => ({ model: "AMD Ryzen 5 5600X" })),
        totalmem: () => totalmemBytes,
    };
}

function makeProcesses({ rows, execFile, now, cacheMs = 0, selfPid = 4000, extraIdupiPids } = {}) {
    return createOsProcesses({
        execFile:
            execFile ??
            (async () => ({
                stdout: psJson(rows),
            })),
        os: fakeOs(),
        platform: "win32",
        env: { SystemRoot: SYSTEM_ROOT },
        now,
        cacheMs,
        selfPid,
        extraIdupiPids,
        log: () => {},
    });
}

// --- parsing ---------------------------------------------------------------

test("process JSON parses as one object or as an array; junk rows are dropped", () => {
    const single = parseProcessList(
        psJson(
            row({
                id: 123,
                name: "chrome",
                parent: 4,
                title: "Inbox - Chrome",
                window: true,
                path: "C:\\Apps\\chrome.exe",
                memory: 300 * MIB,
                cpuMs: 1234.5,
            }),
        ),
    );
    assert.deepEqual(single, [
        {
            pid: 123,
            parentPid: 4,
            name: "chrome",
            title: "Inbox - Chrome",
            hasWindow: true,
            path: "C:\\Apps\\chrome.exe",
            memoryBytes: 300 * MIB,
            cpuTimeMs: 1234.5,
        },
    ]);

    const many = parseProcessList(
        psJson([
            row({ id: 1, name: "a" }),
            row({ id: 2, name: "b", window: 1, path: "C:\\b.exe", cpuMs: null }),
            { Id: "nope", ProcessName: "bad" },
            { Id: 3, ProcessName: "" },
            null,
        ]),
    );
    assert.equal(many.length, 2);
    assert.equal(many[1].hasWindow, true, "HasMainWindow accepts 1 as well as true");
    assert.equal(many[1].cpuTimeMs, null);
    assert.equal(many[1].title, null);

    assert.deepEqual(parseProcessList(""), []);
    assert.deepEqual(parseProcessList("not json"), []);
});

// --- sampler ---------------------------------------------------------------

test("classification follows idupi > agents > windows > apps > background", async () => {
    const rows = [
        row({ id: 4000, name: "node" }), // the server itself
        row({ id: 4100, name: "powershell", parent: 2000 }), // PTY host, extra root
        row({ id: 4101, name: "node", parent: 4100 }), // PTY child
        row({ id: 4102, name: "cmd", parent: 4101 }), // deep PTY descendant
        row({ id: 900, name: "idupi-screen", parent: 2000 }),
        row({ id: 500, name: "claude", parent: 2000, window: true }), // window ignored
        row({ id: 501, name: "node", parent: 500 }), // node started by an agent
        row({ id: 502, name: "node", parent: 2000 }), // bare node
        row({ id: 503, name: "opencode", parent: 2000 }),
        row({ id: 4, name: "System", parent: 0 }),
        row({ id: 620, name: "csrss", parent: 4 }),
        row({ id: 621, name: "svchost", path: "C:\\Windows\\System32\\svchost.exe" }),
        row({ id: 100, name: "Registry" }),
        row({ id: 3000, name: "explorer", path: "C:\\Windows\\explorer.exe" }),
        row({ id: 700, name: "msedge", path: "C:\\Users\\me\\Edge\\msedge.exe", window: true }),
        row({ id: 701, name: "python", path: "C:\\Python312\\python.exe" }),
    ];
    const sample = await makeProcesses({
        rows,
        extraIdupiPids: () => [4100],
    }).sample();

    const category = Object.fromEntries(sample.processes.map((entry) => [entry.pid, entry.category]));
    assert.equal(category[4000], "idupi");
    assert.equal(category[4100], "idupi", "an injected PTY host is an IDUPI root");
    assert.equal(category[4101], "idupi", "the PTY host subtree inherits IDUPI");
    assert.equal(category[4102], "idupi");
    assert.equal(category[900], "idupi");
    assert.equal(category[500], "agents", "agents beat a main window");
    assert.equal(category[501], "agents", "node belongs to agents only via an agent ancestor");
    assert.equal(category[502], "background", "a bare node stays in the background");
    assert.equal(category[503], "agents");
    assert.equal(category[4], "windows");
    assert.equal(category[620], "windows", "a no-path kernel name is Windows");
    assert.equal(category[621], "windows", "an image under %SystemRoot% is Windows");
    assert.equal(category[100], "windows");
    assert.equal(category[3000], "windows");
    assert.equal(category[700], "apps", "a non-system window is an app");
    assert.equal(category[701], "background");

    // idupi beats agents: a claude started by an IDUPI terminal is not an agent.
    const nested = await makeProcesses({
        rows: [row({ id: 4101, name: "claude", parent: 4100 })],
        extraIdupiPids: [4100],
    }).sample();
    assert.equal(nested.processes[0].category, "idupi");
});

test("kill protection covers pid 0/4, the server and critical names; explorer stays killable", async () => {
    const rows = [
        row({ id: 0, name: "Idle" }),
        row({ id: 4, name: "System", parent: 0 }),
        row({ id: 4000, name: "node" }), // self
        row({ id: 620, name: "csrss" }),
        row({ id: 621, name: "Winlogon", path: "C:\\Windows\\System32\\winlogon.exe" }),
        row({ id: 622, name: "msmpeng", path: "C:\\ProgramData\\Microsoft\\Windows Defender\\MsMpEng.exe" }),
        row({ id: 3000, name: "explorer", path: "C:\\Windows\\explorer.exe" }),
        row({ id: 3001, name: "notepad", path: "C:\\Windows\\System32\\notepad.exe" }),
    ];
    const sample = await makeProcesses({ rows }).sample();

    const protectedByPid = Object.fromEntries(
        sample.processes.map((entry) => [entry.pid, entry.protected]),
    );
    assert.equal(protectedByPid[0], true);
    assert.equal(protectedByPid[4], true);
    assert.equal(protectedByPid[4000], true, "the IDUPI server must not kill itself");
    assert.equal(protectedByPid[620], true);
    assert.equal(protectedByPid[621], true);
    assert.equal(protectedByPid[622], true, "Defender lives outside %SystemRoot% but is critical");
    assert.equal(protectedByPid[3000], false, "explorer is deliberately not protected");
    assert.equal(protectedByPid[3001], false);
});

test("cpuPercent is the TotalProcessorTime delta over elapsed time and cpu count", async () => {
    let clock = 1_000_000;
    let call = 0;
    const samplesJson = [
        psJson([
            row({ id: 100, name: "app", cpuMs: 1000 }),
            row({ id: 101, name: "idle-app", cpuMs: 500 }),
        ]),
        psJson([
            row({ id: 100, name: "app", cpuMs: 3000 }),
            row({ id: 101, name: "renamed-app", cpuMs: 1500 }),
        ]),
        psJson([
            row({ id: 100, name: "app", cpuMs: 2000 }), // counter went backwards
            row({ id: 101, name: "renamed-app", cpuMs: 1500 }),
        ]),
    ];
    const processes = makeProcesses({
        execFile: async () => ({ stdout: samplesJson[call++] }),
        now: () => clock,
    });

    const first = await processes.sample();
    assert.deepEqual(first.processes.map((entry) => entry.cpuPercent), [null, null]);
    assert.equal(first.cpuCount, 8);
    assert.equal(first.totalMemoryBytes, 32 * GIB);

    clock += 1000;
    const second = await processes.sample();
    // 2000 ms delta over 1000 ms wall time on 8 CPUs = 25 %.
    assert.equal(second.processes[0].cpuPercent, 25);
    // The key is pid+name: a renamed process has no previous sample.
    assert.equal(second.processes[1].cpuPercent, null);

    clock += 1000;
    const third = await processes.sample();
    assert.equal(third.processes[0].cpuPercent, null, "a decreasing counter is not a percentage");
});

test("concurrent pollers share one sample inside the 2 s cache window", async () => {
    let clock = 1_000_000;
    let calls = 0;
    const processes = makeProcesses({
        execFile: async () => {
            calls++;
            return { stdout: psJson([row({ id: 100, name: "app", cpuMs: calls * 100 })]) };
        },
        now: () => clock,
        cacheMs: 2000,
    });

    const [first, second] = await Promise.all([processes.sample(), processes.sample()]);
    const third = await processes.sample();
    assert.equal(first, second);
    assert.equal(first, third);
    assert.equal(calls, 1);

    clock += 2100;
    await processes.sample();
    assert.equal(calls, 2);
});

test("a failed PowerShell probe answers an empty list instead of throwing", async () => {
    const processes = makeProcesses({
        execFile: async () => {
            const error = new Error("powershell timed out");
            error.code = "ETIMEDOUT";
            throw error;
        },
    });

    const sample = await processes.sample();
    assert.deepEqual(sample.processes, []);
    assert.equal(sample.cpuCount, 8);
    assert.equal(sample.totalMemoryBytes, 32 * GIB);
});

// --- routes ----------------------------------------------------------------

async function startHarness(t, processes) {
    const handle = createOsProcessesRoutes(processes);
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
    const base = `http://127.0.0.1:${server.address().port}`;
    t.after(async () => {
        server.closeAllConnections?.();
        await new Promise((resolve) => server.close(resolve));
    });
    return base;
}

function killHarness(t) {
    const taskkillCalls = [];
    const processes = makeProcesses({
        rows: [
            row({ id: 100, name: "csrss" }),
            row({ id: 300, name: "notepad", path: "C:\\Apps\\notepad.exe" }),
        ],
        execFile: async (file, args) => {
            if (file === "powershell") {
                return {
                    stdout: psJson([
                        row({ id: 100, name: "csrss" }),
                        row({ id: 300, name: "notepad", path: "C:\\Apps\\notepad.exe" }),
                    ]),
                };
            }
            taskkillCalls.push(args);
            const pid = Number(args[1]);
            if (pid === 200) {
                const error = new Error("Command failed: taskkill");
                error.code = 128;
                error.stderr = 'ERROR: The process "200" not found.';
                throw error;
            }
            return { stdout: "SUCCESS: sent termination signal." };
        },
    });
    return { processes, taskkillCalls };
}

test("GET /api/v1/os/processes answers the process-list shape", async (t) => {
    const processes = makeProcesses({
        rows: [row({ id: 100, name: "app", window: true })],
    });
    const base = await startHarness(t, processes);

    const res = await fetch(`${base}/api/v1/os/processes`);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("content-type"), "application/json");
    assert.equal(res.headers.get("cache-control"), "no-store");

    const body = await res.json();
    assert.deepEqual(Object.keys(body), [
        "sampledAt",
        "totalMemoryBytes",
        "cpuCount",
        "processes",
    ]);
    assert.deepEqual(body.processes[0], {
        pid: 100,
        parentPid: null,
        name: "app",
        title: null,
        path: null,
        memoryBytes: 50 * MIB,
        cpuPercent: null,
        category: "apps",
        protected: false,
    });
});

test("HEAD answers the same headers with no body; foreign paths are not ours", async (t) => {
    const processes = makeProcesses({ rows: [row({ id: 100, name: "app" })] });
    const base = await startHarness(t, processes);

    const head = await fetch(`${base}/api/v1/os/processes`, { method: "HEAD" });
    assert.equal(head.status, 200);
    assert.ok(Number(head.headers.get("content-length")) > 0);
    assert.equal((await head.arrayBuffer()).byteLength, 0);

    const foreign = await fetch(`${base}/api/v1/status`);
    assert.equal(foreign.status, 418);
    await foreign.arrayBuffer();
});

test("collection rejects non read-only methods with 405 and Allow", async (t) => {
    const processes = makeProcesses({ rows: [] });
    const base = await startHarness(t, processes);

    for (const method of ["POST", "PUT", "DELETE", "PATCH"]) {
        const res = await fetch(`${base}/api/v1/os/processes`, { method });
        assert.equal(res.status, 405, method);
        assert.equal(res.headers.get("allow"), "GET, HEAD");
        assert.equal((await res.json()).code, "METHOD_NOT_ALLOWED");
    }
});

test("kill answers 200, 409 and 404 with the injected taskkill", async (t) => {
    const { processes, taskkillCalls } = killHarness(t);
    const base = await startHarness(t, processes);

    // Populate the shared sample (also the source of the protection check).
    const list = await fetch(`${base}/api/v1/os/processes`);
    assert.equal(list.status, 200);
    await list.json();

    let res = await fetch(`${base}/api/v1/os/processes/100/kill`, { method: "POST" });
    assert.equal(res.status, 409);
    assert.equal((await res.json()).code, "PROTECTED");
    assert.equal(taskkillCalls.length, 0, "a protected process never reaches taskkill");

    res = await fetch(`${base}/api/v1/os/processes/200/kill`, { method: "POST" });
    assert.equal(res.status, 404);
    assert.equal((await res.json()).code, "NOT_FOUND");

    res = await fetch(`${base}/api/v1/os/processes/300/kill`, { method: "POST" });
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { ok: true });
    assert.deepEqual(taskkillCalls.at(-1), ["/PID", "300", "/T", "/F"]);
});

test("kill validates the pid and rejects other methods", async (t) => {
    const processes = makeProcesses({ rows: [] });
    const base = await startHarness(t, processes);

    for (const bad of ["abc", "0", "-3", "1.5"]) {
        const res = await fetch(`${base}/api/v1/os/processes/${bad}/kill`, { method: "POST" });
        assert.equal(res.status, 400, bad);
        assert.equal((await res.json()).code, "INVALID_PID");
    }

    const res = await fetch(`${base}/api/v1/os/processes/300/kill`);
    assert.equal(res.status, 405);
    assert.equal(res.headers.get("allow"), "POST");
    assert.equal((await res.json()).code, "METHOD_NOT_ALLOWED");
});

test("kill maps an unexpected taskkill failure to 500", async (t) => {
    const processes = makeProcesses({
        rows: [row({ id: 300, name: "notepad" })],
        execFile: async (file) => {
            if (file === "powershell") {
                return { stdout: psJson([row({ id: 300, name: "notepad" })]) };
            }
            const error = new Error("Access is denied.");
            error.code = 1;
            throw error;
        },
    });
    const base = await startHarness(t, processes);

    const res = await fetch(`${base}/api/v1/os/processes/300/kill`, { method: "POST" });
    assert.equal(res.status, 500);
    assert.equal((await res.json()).code, "INTERNAL");
});

// --- wiring ----------------------------------------------------------------

test("index.mjs wires the process routes behind requireAuth", async () => {
    const source = await readFile(new URL("../index.mjs", import.meta.url), "utf8");
    const authGate = source.indexOf("if (!requireAuth(req, res)) return;");
    const processesWire = source.indexOf("handleOsProcessesRoute(req, res, pathname)");

    assert.ok(authGate > -1, "the bearer-token gate must exist");
    assert.ok(processesWire > -1, "the processes handler must be called in the route chain");
    assert.ok(
        processesWire > authGate,
        "the processes handler must run after the bearer-token check",
    );
});
