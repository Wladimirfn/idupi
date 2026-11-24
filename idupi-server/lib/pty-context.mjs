// Live context for each interactive PTY: where it is and what runs in it.
//
// The PTY manager only knows the launch data (`powershell.exe -NoLogo`,
// `C:\Users\elmas`), so two terminals look identical after `cd` + `claude` or
// `opencode`. This module resolves, per terminal:
//
//   - the live cwd, read from the PEB of the process that owns the terminal
//     (the running agent when there is one, the shell otherwise) by the Go
//     helper's `proc-cwd` subcommand;
//   - the git project and branch of that cwd (`git rev-parse`);
//   - the deepest agent CLI running under the PTY host, recognised with the
//     same process-name list the Procesos screen uses.
//
// Why the agent process and not the shell: PowerShell's `cd` changes its
// *location*, not its process current directory, so the shell's PEB keeps the
// launch directory forever. A child it spawns (`claude`, `opencode`) does get
// the current location, so the agent's PEB is the one that moves.
//
// Everything here is injectable (sampleProcesses, readCwds, execFile, now) so
// tests never start PowerShell, the Go helper or git, and every failure lowers
// to `null` instead of throwing into the route.

import { execFile as nodeExecFile } from "node:child_process";
import { promisify } from "node:util";

import { AGENT_PROCESS_NAMES, normalizeProcessName } from "./os-processes.mjs";
import { ensureHelperBuilt } from "./screen-helper.mjs";

const nodeExecFileAsync = promisify(nodeExecFile);

const POWERSHELL_TIMEOUT_MS = 10_000;
const POWERSHELL_MAX_BUFFER = 8 * 1024 * 1024;
const HELPER_TIMEOUT_MS = 10_000;
const HELPER_MAX_BUFFER = 1024 * 1024;
const GIT_TIMEOUT_MS = 3_000;
const GIT_MAX_BUFFER = 64 * 1024;
const DEFAULT_CACHE_MS = 3_000;
const DEFAULT_GIT_CACHE_MS = 10_000;
const MAX_HELPER_PIDS = 64;
const MAX_TREE_DEPTH = 64;

// One PowerShell start per sample: identity, parent and command line of every
// process. -Compress keeps the payload small; ConvertTo-Json receives the
// pipeline so several rows arrive as an array (a single row as an object).
const PROCESS_SCRIPT = `
$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, Name, CommandLine | ConvertTo-Json -Compress
`.trim();

// Agent labels the phone shows. The recognised NAMES come from
// os-processes.mjs (one shared list for Procesos and terminals); this map only
// adds the display label, and drops shared names that are not terminal agents
// (ollama runs as a background server, never as an interactive CLI).
export const AGENT_DISPLAY_NAMES = new Map([
    ["claude", "Claude Code"],
    ["opencode", "OpenCode"],
    ["codex", "Codex"],
    ["cmdc", "Command Code"],
    ["mcode", "MiniMax Code"],
    ["kimi", "Kimi"],
    ["qwen", "Qwen"],
    ["gemini", "Gemini"],
    ["pi", "Pi"],
]);

/** Shared agent-name list ∩ names a terminal can run. */
export const PTY_AGENT_NAMES = new Set(
    [...AGENT_PROCESS_NAMES].filter((name) => AGENT_DISPLAY_NAMES.has(name)),
);

// A node/bun process is an agent when the script it runs is one of the CLIs.
// The executable is a JS entry point, so the agent is named by a path segment
// (`.../codex/bin/codex.js`) or by the npm package that ships it.
const NODE_LIKE_NAMES = new Set(["node", "bun"]);

const AGENT_PACKAGE_TOKENS = [
    ["@openai/codex", "codex"],
    ["codex-cli", "codex"],
    ["@anthropic-ai/claude-code", "claude"],
    ["claude-code", "claude"],
    ["@google/gemini-cli", "gemini"],
    ["@qwen-code/qwen-code", "qwen"],
    ["opencode-ai", "opencode"],
    ["@opencode-ai/", "opencode"],
    ["kimi-cli", "kimi"],
];

const CONSOLE_HOST_NAMES = new Set(["conhost", "openconsole"]);

/** "Notepad.exe" / "C:\\x\\node.exe" -> "notepad" / "node" (shared helper). */
export function matchAgentName(name) {
    const normalized = normalizeProcessName(name);
    return PTY_AGENT_NAMES.has(normalized) ? normalized : null;
}

/** Agent named by a node/bun command line, or null. */
export function agentFromCommandLine(commandLine) {
    const text = String(commandLine ?? "").toLowerCase();
    if (!text) return null;

    for (const rawToken of text.split(/\s+/)) {
        const token = rawToken.replace(/^["']+|["']+$/g, "");
        if (!token) continue;
        for (const segment of token.split(/[\\/]/)) {
            const bare = segment.replace(/\.(exe|cmd|bat|ps1|js|mjs|cjs|sh)$/, "");
            if (PTY_AGENT_NAMES.has(bare)) return bare;
        }
    }
    for (const [needle, agent] of AGENT_PACKAGE_TOKENS) {
        if (text.includes(needle)) return agent;
    }
    return null;
}

/**
 * Agent key for one process row, or null: the process name first (claude.exe,
 * opencode.exe), then, for node/bun launchers, the script it runs.
 */
export function matchAgent(entry) {
    if (!entry || typeof entry !== "object") return null;
    const byName = matchAgentName(entry.name);
    if (byName) return byName;
    if (!NODE_LIKE_NAMES.has(normalizeProcessName(entry.name))) return null;
    return agentFromCommandLine(entry.commandLine);
}

/** `proc-cwd` stdout -> Map<pid, cwd|null>. */
export function parseProcCwdOutput(stdout) {
    const cwds = new Map();
    const text = String(stdout ?? "").trim();
    if (!text) return cwds;
    let data;
    try {
        data = JSON.parse(text);
    } catch {
        return cwds;
    }
    if (!data || typeof data !== "object" || Array.isArray(data)) return cwds;
    for (const [key, value] of Object.entries(data)) {
        const pid = Number(key);
        if (!Number.isInteger(pid)) continue;
        cwds.set(pid, typeof value === "string" && value.trim() ? value : null);
    }
    return cwds;
}

/** One PowerShell sample -> [{pid, parentPid, name, commandLine}]. */
export function parseProcessSample(stdout) {
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
        const pid = Number(row.ProcessId);
        if (!Number.isInteger(pid) || pid <= 0) continue;
        const name = String(row.Name ?? "").trim();
        if (!name) continue;
        const parentRaw = Number(row.ParentProcessId);
        processes.push({
            pid,
            parentPid: Number.isInteger(parentRaw) && parentRaw > 0 ? parentRaw : null,
            name,
            commandLine: typeof row.CommandLine === "string" ? row.CommandLine : null,
        });
    }
    return processes;
}

async function defaultSampleProcesses() {
    const { stdout } = await nodeExecFileAsync(
        "powershell",
        ["-NoProfile", "-NonInteractive", "-Command", PROCESS_SCRIPT],
        {
            timeout: POWERSHELL_TIMEOUT_MS,
            windowsHide: true,
            maxBuffer: POWERSHELL_MAX_BUFFER,
            encoding: "utf8",
        },
    );
    return parseProcessSample(stdout);
}

async function defaultReadCwds(pids, { execFile, resolveHelper }) {
    const list = [...new Set(
        (Array.isArray(pids) ? pids : []).map(Number).filter((pid) => Number.isInteger(pid) && pid > 0),
    )].slice(0, MAX_HELPER_PIDS);
    if (list.length === 0) return new Map();
    const exe = await resolveHelper();
    const { stdout } = await execFile(
        exe,
        ["proc-cwd", ...list.map(String)],
        {
            timeout: HELPER_TIMEOUT_MS,
            windowsHide: true,
            maxBuffer: HELPER_MAX_BUFFER,
            encoding: "utf8",
        },
    );
    return parseProcCwdOutput(stdout);
}

function cwdKey(cwd) {
    return String(cwd ?? "").replace(/[\\/]+$/, "").toLowerCase();
}

function lastSegment(path) {
    const text = String(path ?? "").trim().replace(/[\\/]+$/, "");
    if (!text) return null;
    const parts = text.split(/[\\/]/);
    return parts[parts.length - 1] || null;
}

function firstString(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) return value;
    }
    return null;
}

function normalizeCwdMap(value) {
    const map = new Map();
    const entries = value instanceof Map
        ? value.entries()
        : value && typeof value === "object" && !Array.isArray(value)
            ? Object.entries(value)
            : [];
    for (const [key, cwd] of entries) {
        const pid = Number(key);
        if (!Number.isInteger(pid)) continue;
        map.set(pid, typeof cwd === "string" && cwd.trim() ? cwd : null);
    }
    return map;
}

/** parent pid -> children, each list ordered by pid for a stable "first". */
function childrenIndex(rows) {
    const byParent = new Map();
    for (const row of rows) {
        if (row.parentPid === null) continue;
        if (!byParent.has(row.parentPid)) byParent.set(row.parentPid, []);
        byParent.get(row.parentPid).push(row);
    }
    for (const list of byParent.values()) list.sort((a, b) => a.pid - b.pid);
    return byParent;
}

/** Every descendant of the host, breadth-first, each with its depth. */
function descendantsOf(hostPid, childrenByParent) {
    const out = [];
    const seen = new Set([hostPid]);
    const queue = [{ pid: hostPid, depth: 0 }];
    while (queue.length > 0) {
        const current = queue.shift();
        if (current.depth >= MAX_TREE_DEPTH) continue;
        for (const child of childrenByParent.get(current.pid) ?? []) {
            if (seen.has(child.pid)) continue;
            seen.add(child.pid);
            const depth = current.depth + 1;
            out.push({ row: child, depth, agent: matchAgent(child) });
            queue.push({ pid: child.pid, depth });
        }
    }
    return out;
}

/** First direct child of the host that is not a console host. */
function directShell(hostPid, childrenByParent) {
    for (const child of childrenByParent.get(hostPid) ?? []) {
        if (!CONSOLE_HOST_NAMES.has(normalizeProcessName(child.name))) return child;
    }
    return null;
}

/**
 * Deepest agent below the host. BFS keeps depth non-decreasing, so the last
 * entry at the maximum depth wins a tie: the newest nested agent is the one
 * actually in the foreground.
 */
function deepestAgent(descendants) {
    let best = null;
    for (const entry of descendants) {
        if (!entry.agent) continue;
        if (!best || entry.depth >= best.depth) best = entry;
    }
    return best;
}

/**
 * Builds the PTY context provider.
 *
 * @param {object} [options]
 * @param {() => Promise<Array<{pid:number,parentPid:number|null,name:string,commandLine:string|null}>>} [options.sampleProcesses]
 * @param {(pids:number[]) => Promise<Map<number,string|null>|Record<string,string|null>>} [options.readCwds]
 * @param {(file:string,args:string[],options:object) => Promise<{stdout:string}>} [options.execFile]
 * @param {() => number} [options.now]
 * @param {number} [options.cacheMs] whole-context cache
 * @param {number} [options.gitCacheMs] per-cwd git cache
 * @param {() => Promise<string>} [options.resolveHelper] helper exe resolver
 * @param {(message:string) => void} [options.log]
 */
export function createPtyContext({
    sampleProcesses = defaultSampleProcesses,
    readCwds,
    execFile = nodeExecFileAsync,
    now = () => Date.now(),
    cacheMs = DEFAULT_CACHE_MS,
    gitCacheMs = DEFAULT_GIT_CACHE_MS,
    resolveHelper = ensureHelperBuilt,
    log = () => {},
} = {}) {
    const readCwdBatch = typeof readCwds === "function"
        ? readCwds
        : (pids) => defaultReadCwds(pids, { execFile, resolveHelper });

    let contextCache = null; // { signature, at, value }
    let inFlight = null; // { signature, promise }
    const gitCache = new Map(); // lowercased cwd -> { at, value }

    async function shortSha(cwd) {
        try {
            const { stdout } = await execFile(
                "git",
                ["-C", cwd, "rev-parse", "--short", "HEAD"],
                {
                    timeout: GIT_TIMEOUT_MS,
                    windowsHide: true,
                    encoding: "utf8",
                    maxBuffer: GIT_MAX_BUFFER,
                },
            );
            return String(stdout ?? "").trim() || null;
        } catch {
            return null;
        }
    }

    async function resolveGit(cwd) {
        const fallback = { project: lastSegment(cwd), branch: null, detached: false };
        try {
            const { stdout } = await execFile(
                "git",
                ["-C", cwd, "rev-parse", "--show-toplevel", "--abbrev-ref", "HEAD"],
                {
                    timeout: GIT_TIMEOUT_MS,
                    windowsHide: true,
                    encoding: "utf8",
                    maxBuffer: GIT_MAX_BUFFER,
                },
            );
            const lines = String(stdout ?? "")
                .split(/\r?\n/)
                .map((line) => line.trim())
                .filter(Boolean);
            if (lines.length === 0) return fallback;
            const project = lastSegment(lines[0]) || fallback.project;
            const ref = lines[1] ?? "";
            if (ref && ref !== "HEAD") return { project, branch: ref, detached: false };
            if (ref === "HEAD") {
                // Detached HEAD: the short sha is still the useful label.
                return { project, branch: await shortSha(cwd), detached: true };
            }
            return { project, branch: null, detached: false };
        } catch {
            // Outside a repository (or no git): the folder is the project.
            return fallback;
        }
    }

    async function gitInfo(cwd) {
        const key = cwdKey(cwd);
        const at = now();
        const cached = gitCache.get(key);
        if (cached && at - cached.at < gitCacheMs) return cached.value;
        const value = await resolveGit(cwd);
        gitCache.set(key, { at, value });
        return value;
    }

    async function build(terminals) {
        let sample = [];
        try {
            sample = await sampleProcesses();
        } catch (err) {
            log(`[pty-context] process sample failed: ${(err && err.message) || err}`);
            sample = [];
        }
        const rows = Array.isArray(sample)
            ? sample.filter((row) => row && Number.isInteger(row.pid) && row.pid > 0)
            : [];
        const childrenByParent = childrenIndex(rows);

        const entries = [];
        const cwdPids = new Set();
        for (const terminal of terminals) {
            const key = terminal.id ?? `pid-${terminal.pid}`;
            const descendants = descendantsOf(terminal.pid, childrenByParent);
            const shell = directShell(terminal.pid, childrenByParent);
            const agent = deepestAgent(descendants);
            const contextPid = agent ? agent.row.pid : shell ? shell.pid : null;
            if (contextPid) cwdPids.add(contextPid);
            if (shell) cwdPids.add(shell.pid);
            entries.push({ key, terminal, shell, agent });
        }

        let cwds = new Map();
        if (cwdPids.size > 0) {
            try {
                cwds = normalizeCwdMap(await readCwdBatch([...cwdPids]));
            } catch (err) {
                log(`[pty-context] proc-cwd failed: ${(err && err.message) || err}`);
                cwds = new Map();
            }
        }

        const resolved = entries.map((entry) => {
            const contextPid = entry.agent
                ? entry.agent.row.pid
                : entry.shell ? entry.shell.pid : null;
            const cwd = firstString(
                contextPid ? cwds.get(contextPid) : null,
                entry.shell ? cwds.get(entry.shell.pid) : null,
                entry.terminal.cwd,
            );
            return { ...entry, cwd };
        });

        const gitByCwd = new Map();
        const uniqueCwds = [...new Set(resolved.map((entry) => entry.cwd).filter(Boolean))];
        await Promise.all(uniqueCwds.map(async (cwd) => {
            gitByCwd.set(cwdKey(cwd), await gitInfo(cwd));
        }));

        const contexts = new Map();
        for (const entry of resolved) {
            const git = entry.cwd
                ? gitByCwd.get(cwdKey(entry.cwd)) ?? {
                    project: lastSegment(entry.cwd),
                    branch: null,
                    detached: false,
                }
                : { project: null, branch: null, detached: false };
            contexts.set(entry.key, {
                cwd: entry.cwd ?? null,
                project: git.project ?? null,
                branch: git.branch ?? null,
                detached: git.detached === true,
                agent: entry.agent
                    ? {
                        id: entry.agent.agent,
                        name: AGENT_DISPLAY_NAMES.get(entry.agent.agent) ?? entry.agent.agent,
                        pid: entry.agent.row.pid,
                    }
                    : null,
            });
        }
        return contexts;
    }

    /**
     * Context per terminal id. Never rejects and never throws into the route:
     * a failed piece answers null for that field. Results are cached for
     * `cacheMs` while the same terminal set is asked, and nothing runs when
     * there are no terminals.
     *
     * @param {Array<{id?: string, pid?: number, cwd?: string}>} terminals
     * @returns {Promise<Map<string, object>>}
     */
    async function get(terminals = []) {
        const list = Array.isArray(terminals)
            ? terminals.filter((entry) => entry && Number.isInteger(entry.pid) && entry.pid > 0)
            : [];
        if (list.length === 0) return new Map();

        const signature = list
            .map((entry) => `${entry.id ?? entry.pid}:${entry.pid}:${entry.cwd ?? ""}`)
            .sort()
            .join("|");

        if (
            contextCache &&
            contextCache.signature === signature &&
            now() - contextCache.at < cacheMs
        ) {
            return contextCache.value;
        }
        if (inFlight && inFlight.signature === signature) return inFlight.promise;

        const promise = build(list)
            .then((value) => {
                contextCache = { signature, at: now(), value };
                return value;
            })
            .catch((err) => {
                log(`[pty-context] context failed: ${(err && err.message) || err}`);
                const empty = new Map();
                for (const entry of list) {
                    empty.set(entry.id ?? `pid-${entry.pid}`, {
                        cwd: firstString(entry.cwd),
                        project: firstString(entry.cwd) ? lastSegment(entry.cwd) : null,
                        branch: null,
                        detached: false,
                        agent: null,
                    });
                }
                return empty;
            })
            .finally(() => {
                if (inFlight && inFlight.promise === promise) inFlight = null;
            });

        inFlight = { signature, promise };
        return promise;
    }

    return { get };
}
