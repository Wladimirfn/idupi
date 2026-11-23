// Every PC process for the Procesos screen (app-redesign, T10).
//
// One PowerShell call per sample returns every process on the machine with the
// fields the phone needs: identity, the window title (so "Procesos" can show
// what an app is), the image path for the Windows/IDUPI split, working-set
// memory (what Task Manager calls "Memory") and cumulative CPU time. The same
// script also reads the CIM parent map, because "node" is an agent only when
// an agent started it and IDUPI owns its own whole subtree.
//
// CPU percentage needs two samples: the delta of TotalProcessorTime divided by
// the elapsed wall time and the CPU count. The first sample answers null, which
// the app renders as "—", never as a made-up 0 %. The result is cached for 2 s
// so several pollers share one PowerShell start.
//
// `kill(pid)` uses taskkill /T /F and refuses the critical set; the route
// module maps its outcome to 200/404/409/500. Everything is injectable
// (execFile, os, now, platform, selfPid, extra IDUPI roots) so tests never
// touch the real machine.

import * as nodeOs from "node:os";
import { execFile as nodeExecFile } from "node:child_process";
import { promisify } from "node:util";

const nodeExecFileAsync = promisify(nodeExecFile);

const POWERSHELL_TIMEOUT_MS = 10_000;
const POWERSHELL_MAX_BUFFER = 8 * 1024 * 1024;
const TASKKILL_TIMEOUT_MS = 8000;
const TASKKILL_MAX_BUFFER = 64 * 1024;
const DEFAULT_CACHE_MS = 2000;
const MAX_PARENT_DEPTH = 64;

// One process query and one parent query in a single PowerShell start. With
// -Compress the payload stays small (~60 KB for a few hundred processes), and
// Select-equivalent pscustomobject keeps only the fields the app consumes.
// [Console]::OutputEncoding makes window titles with accents arrive as UTF-8
// instead of the console OEM code page. ConvertTo-Json receives -InputObject
// (not a pipeline) so a single-process machine still serializes an array.
const PROCESS_SCRIPT = `
$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$parentByPid = @{}
Get-CimInstance Win32_Process -Property ProcessId,ParentProcessId | ForEach-Object {
    $parentByPid[[int]$_.ProcessId] = [int]$_.ParentProcessId
}
$items = @(Get-Process | ForEach-Object {
    $cpuMs = $null
    if ($null -ne $_.TotalProcessorTime) {
        $cpuMs = [math]::Round($_.TotalProcessorTime.TotalMilliseconds, 1)
    }
    $parent = $null
    if ($parentByPid.ContainsKey([int]$_.Id)) {
        $parent = [int]$parentByPid[[int]$_.Id]
    }
    [pscustomobject]@{
        Id = [int]$_.Id
        ProcessName = [string]$_.ProcessName
        MainWindowTitle = [string]$_.MainWindowTitle
        HasMainWindow = (([int64]$_.MainWindowHandle) -ne 0)
        Path = $_.Path
        WorkingSet64 = [int64]$_.WorkingSet64
        TotalProcessorTimeMs = $cpuMs
        ParentPid = $parent
    }
})
ConvertTo-Json -InputObject $items -Compress -Depth 3
`.trim();

// Agent tool names as they appear in ProcessName. Precedence is
// idupi > agents > windows > apps > background; `node` joins agents only when
// an agent is in its parent chain, because a bare node is usually a dev tool.
export const AGENT_PROCESS_NAMES = new Set([
    "claude",
    "opencode",
    "codex",
    "cmdc",
    "mcode",
    "kimi",
    "qwen",
    "pi",
    "gemini",
    "ollama",
]);

const NODE_PROCESS_NAMES = new Set(["node"]);

// Windows-owned processes that may have no readable Path (kernel and protected
// processes). Anything under %SystemRoot% is Windows by path anyway; this set
// covers the ones that cannot report it.
export const WINDOWS_PROCESS_NAMES = new Set([
    "system",
    "idle",
    "registry",
    "memory compression",
    "secure system",
    "smss",
    "csrss",
    "wininit",
    "services",
    "lsass",
    "svchost",
    "dwm",
    "fontdrvhost",
    "winlogon",
    "msmpeng",
    "nissrv",
]);

// Never killable. This is the clear constant the route checks: process 0/4,
// the IDUPI server itself, the critical Windows names above (a process whose
// Path is unreadable must not become killable), and any critical name whose
// image sits under %SystemRoot%\System32. Explorer is deliberately NOT here:
// ending it only restarts the shell, so the app may let the owner do it.
export const CRITICAL_PROCESS_NAMES = new Set([
    "system",
    "idle",
    "registry",
    "smss",
    "csrss",
    "wininit",
    "winlogon",
    "services",
    "lsass",
    "svchost",
    "dwm",
    "fontdrvhost",
    "memory compression",
    "secure system",
    "msmpeng",
]);

function roundTo(value, digits) {
    const factor = 10 ** digits;
    return Math.round(value * factor) / factor;
}

/** "Notepad.exe" -> "notepad"; comparison is case-insensitive everywhere. */
export function normalizeProcessName(name) {
    return String(name ?? "").trim().toLowerCase().replace(/\.exe$/, "");
}

function hasOwnWindow(value) {
    return value === true || Number(value) > 0;
}

/**
 * `ConvertTo-Json` output -> rows. One process serializes as an object and
 * several as an array; both parse. Rows without an integer pid or a name are
 * dropped, and an unreadable Path stays null (access denied is normal for
 * protected processes, not an error).
 */
export function parseProcessList(stdout) {
    const text = String(stdout ?? "").trim();
    if (!text) return [];
    let data;
    try {
        data = JSON.parse(text);
    } catch {
        return [];
    }
    const rows = Array.isArray(data) ? data : [data];
    const processes = [];
    for (const row of rows) {
        if (!row || typeof row !== "object") continue;
        const pid = Number(row.Id);
        if (!Number.isInteger(pid) || pid < 0) continue;
        const name = String(row.ProcessName ?? "").trim();
        if (!name) continue;

        const title = typeof row.MainWindowTitle === "string" ? row.MainWindowTitle.trim() : "";
        const execPath = typeof row.Path === "string" ? row.Path.trim() : "";
        const parentRaw = row.ParentPid;
        const memoryBytes = Number(row.WorkingSet64);
        const cpuRaw = row.TotalProcessorTimeMs;
        const parentPid = Number(parentRaw);
        const cpuTimeMs = Number(cpuRaw);

        processes.push({
            pid,
            parentPid:
                parentRaw !== null && parentRaw !== undefined &&
                Number.isInteger(parentPid) && parentPid >= 0
                    ? parentPid
                    : null,
            name,
            title: title || null,
            hasWindow: hasOwnWindow(row.HasMainWindow),
            path: execPath || null,
            memoryBytes: Number.isFinite(memoryBytes) && memoryBytes > 0 ? memoryBytes : 0,
            cpuTimeMs:
                cpuRaw !== null && cpuRaw !== undefined &&
                Number.isFinite(cpuTimeMs) && cpuTimeMs >= 0
                    ? cpuTimeMs
                    : null,
        });
    }
    return processes;
}

function isUnder(root, candidate) {
    if (typeof candidate !== "string" || candidate.length === 0) return false;
    const base = String(root ?? "").replace(/[\\/]+$/, "").toLowerCase();
    if (!base) return false;
    const value = candidate.toLowerCase();
    return value === base || value.startsWith(`${base}\\`) || value.startsWith(`${base}/`);
}

function ancestorChain(pid, byPid) {
    const chain = [];
    const seen = new Set();
    let current = pid;
    for (let depth = 0; depth < MAX_PARENT_DEPTH; depth++) {
        const entry = byPid.get(current);
        if (!entry) break;
        const parent = entry.parentPid;
        if (parent === null || seen.has(parent)) break;
        seen.add(parent);
        chain.push(parent);
        current = parent;
    }
    return chain;
}

function hasAncestorIn(entry, roots, byPid) {
    return ancestorChain(entry.pid, byPid).some((pid) => roots.has(pid));
}

function hasAgentAncestor(entry, byPid) {
    for (const pid of ancestorChain(entry.pid, byPid)) {
        const ancestor = byPid.get(pid);
        if (ancestor && AGENT_PROCESS_NAMES.has(normalizeProcessName(ancestor.name))) {
            return true;
        }
    }
    return false;
}

/**
 * Category by fixed precedence: idupi > agents > windows > apps > background.
 * `idupi` covers the server, every descendant, the PTY hosts passed as extra
 * roots, and idupi-screen.exe; `agents` covers the known CLIs plus node
 * processes started by one; `windows` covers images under %SystemRoot% and the
 * no-path kernel names; `apps` is anything with a main window; the rest is
 * background.
 */
export function categorizeProcess(entry, { selfPid, byPid, idupiRoots, systemRoot }) {
    const name = normalizeProcessName(entry.name);

    if (entry.pid === selfPid || idupiRoots.has(entry.pid)) return "idupi";
    if (name === "idupi-screen" || name === "idupi-server") return "idupi";
    if (hasAncestorIn(entry, idupiRoots, byPid)) return "idupi";

    if (AGENT_PROCESS_NAMES.has(name)) return "agents";
    if (NODE_PROCESS_NAMES.has(name) && hasAgentAncestor(entry, byPid)) return "agents";

    if (isUnder(systemRoot, entry.path)) return "windows";
    if (!entry.path && WINDOWS_PROCESS_NAMES.has(name)) return "windows";

    if (entry.hasWindow) return "apps";
    return "background";
}

/**
 * Kill protection: pid 0/4, the IDUPI server, and the critical name set. The
 * task also asked for "anything under %SystemRoot%\System32 whose name is in
 * the critical list"; a critical name is protected by name alone, which is a
 * superset of that rule and keeps a process killable-safe when Windows denies
 * its Path (protected processes usually deny it). Explorer is not protected on
 * purpose: ending it only restarts the shell.
 */
export function isProtectedProcess(entry, { selfPid }) {
    if (entry.pid === 0 || entry.pid === 4 || entry.pid === selfPid) return true;
    return CRITICAL_PROCESS_NAMES.has(normalizeProcessName(entry.name));
}

function taskkillNotFound(err) {
    if (Number(err?.code) === 128) return true;
    const text = `${err?.stderr ?? ""} ${err?.message ?? ""}`.toLowerCase();
    return /not found|no se encuentra|no existe|no such process/.test(text);
}

/**
 * Builds the sampler and the kill action.
 *
 * @param {object} [options]
 * @param {(file: string, args: string[], options: object) => Promise<{ stdout: string }>} [options.execFile]
 * @param {typeof import("node:os")} [options.os]
 * @param {NodeJS.Platform} [options.platform]
 * @param {Record<string, string | undefined>} [options.env]
 * @param {() => number} [options.now]
 * @param {number} [options.selfPid]
 * @param {() => number[] | number[]} [options.extraIdupiPids]  PTY host pids
 * @param {number} [options.cacheMs]
 * @param {(...args: unknown[]) => void} [options.log]
 */
export function createOsProcesses({
    execFile = nodeExecFileAsync,
    os: osImpl = nodeOs,
    platform = process.platform,
    env = process.env,
    now = () => Date.now(),
    selfPid = process.pid,
    extraIdupiPids = () => [],
    cacheMs = DEFAULT_CACHE_MS,
    log = () => {},
} = {}) {
    let cached = null;
    let inFlight = null;
    let previous = null;

    function systemRoot() {
        return env.SystemRoot || env.SYSTEMROOT || env.windir || "C:\\Windows";
    }

    function resolveIdupiRoots() {
        const roots = new Set([selfPid]);
        const extra = typeof extraIdupiPids === "function" ? extraIdupiPids() : extraIdupiPids;
        for (const value of Array.isArray(extra) ? extra : []) {
            const pid = Number(value);
            if (Number.isInteger(pid) && pid > 0) roots.add(pid);
        }
        return roots;
    }

    function cpuPercentFor(entry, at, cpuCount) {
        if (!previous || entry.cpuTimeMs === null) return null;
        const before = previous.cpuTimes.get(`${entry.pid}:${entry.name}`);
        if (before === undefined || before === null) return null;
        const elapsed = at - previous.at;
        if (!(elapsed > 0)) return null;
        const delta = entry.cpuTimeMs - before;
        if (!(delta >= 0)) return null;
        return roundTo((delta / (elapsed * cpuCount)) * 100, 1);
    }

    async function buildSample() {
        const at = now();
        const totalMemoryBytes = Number(osImpl.totalmem());
        const cpus = osImpl.cpus();
        const cpuCount = Math.max(1, Array.isArray(cpus) && cpus.length > 0 ? cpus.length : 1);
        const empty = {
            sampledAt: new Date(at).toISOString(),
            totalMemoryBytes,
            cpuCount,
            processes: [],
        };

        if (platform !== "win32") return empty;

        let rows;
        try {
            const { stdout } = await execFile(
                "powershell",
                ["-NoProfile", "-Command", PROCESS_SCRIPT],
                {
                    timeout: POWERSHELL_TIMEOUT_MS,
                    windowsHide: true,
                    maxBuffer: POWERSHELL_MAX_BUFFER,
                    encoding: "utf8",
                },
            );
            rows = parseProcessList(stdout);
        } catch (err) {
            // A failed probe answers an empty list, never invented processes;
            // the previous CPU baseline is kept so the next good sample can
            // still report deltas.
            log(`[os-processes] process query failed: ${(err && err.message) || err}`);
            return empty;
        }

        const byPid = new Map(rows.map((entry) => [entry.pid, entry]));
        const byKey = new Map(rows.map((entry) => [`${entry.pid}:${entry.name}`, entry.cpuTimeMs]));
        const context = {
            selfPid,
            byPid,
            idupiRoots: resolveIdupiRoots(),
            systemRoot: systemRoot(),
        };

        const processes = rows.map((entry) => ({
            pid: entry.pid,
            parentPid: entry.parentPid,
            name: entry.name,
            title: entry.title,
            path: entry.path,
            memoryBytes: entry.memoryBytes,
            cpuPercent: cpuPercentFor(entry, at, cpuCount),
            category: categorizeProcess(entry, context),
            protected: isProtectedProcess(entry, context),
        }));

        previous = { at, cpuTimes: byKey };

        return {
            sampledAt: new Date(at).toISOString(),
            totalMemoryBytes,
            cpuCount,
            processes,
        };
    }

    function findProcess(pid) {
        return sample().then((result) => result.processes.find((entry) => entry.pid === pid) ?? null);
    }

    /** @returns {Promise<{ status: "killed" | "protected" | "gone" | "failed", name: string | null, error?: string }>} */
    async function kill(pid) {
        const entry = await findProcess(pid);
        if (entry && entry.protected) {
            return { status: "protected", name: entry.name };
        }

        try {
            await execFile("taskkill", ["/PID", String(pid), "/T", "/F"], {
                timeout: TASKKILL_TIMEOUT_MS,
                windowsHide: true,
                maxBuffer: TASKKILL_MAX_BUFFER,
                encoding: "utf8",
            });
            log(`[os-processes] killed ${entry ? entry.name : "unknown process"} (PID ${pid})`);
            return { status: "killed", name: entry ? entry.name : null };
        } catch (err) {
            if (taskkillNotFound(err)) {
                return { status: "gone", name: entry ? entry.name : null };
            }
            return {
                status: "failed",
                name: entry ? entry.name : null,
                error: (err && err.message) || "taskkill failed",
            };
        }
    }

    /**
     * One process sample. Concurrent pollers inside the cache window share the
     * same promise, so a 2 s phone poll cannot spawn overlapping PowerShell
     * processes.
     */
    function sample() {
        const at = now();
        if (cached && at - cached.at < cacheMs) return Promise.resolve(cached.value);
        if (inFlight) return inFlight;
        inFlight = buildSample()
            .then((value) => {
                cached = { at: now(), value };
                return value;
            })
            .finally(() => {
                inFlight = null;
            });
        return inFlight;
    }

    return { sample, kill };
}
