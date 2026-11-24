// ============================================================================
// idupi-server/lib/opencode-cli.mjs
//
// Bounded adapter for the OpenCode v2 CLI (verified against v2.4.1).
//
// OpenCode v2 removed the `db <sql> --format json` subcommand the session
// listing used to rely on, and `models <provider>` no longer takes a
// positional provider. What v2 does offer:
//
//   - `opencode session list --format json --max-count N`, run with cwd = the
//     project directory: a JSON array of that project's TOP-LEVEL sessions,
//     newest first, with { id, title, updated, created, projectId, directory }.
//     ~0.5 s per call, so the parsed list is cached per directory.
//   - `opencode models`: one `provider/model` line per model (no provider
//     argument). The provider list is derived by prefix filtering here.
//
// Message counts (the one-exchange noise filter) come from the v2 SQLite
// store (`session_message`), opened read-only through the built-in
// `node:sqlite`. This is a best-effort enrichment: any failure (missing file,
// table or column, busy database, older Node without node:sqlite) makes the
// counts UNKNOWN -- `msgCount: null`, noise filter skipped, never a fabricated
// zero. A failure must never take the session listing down with it.
//
// Everything here is injectable (`createOpenCodeCli({ ...deps })`) so the
// behavior is unit-testable without a real OpenCode install or database.
// Production imports the `openCodeCli` singleton from index.mjs.
// ============================================================================

import { execFile as nodeExecFile, execFileSync as nodeExecFileSync } from "node:child_process";
import { statSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";
import { normalizePathForCompare, resolveOpenCodeExePath } from "./sessions.mjs";

/** Parsed `session list` cache window. One CLI call per directory per TTL. */
export const SESSION_LIST_CACHE_TTL_MS = 10000;

/** `opencode models` output is ~700 lines and changes rarely. */
export const MODELS_CACHE_TTL_MS = 60000;

/** Upper bound on sessions requested per `session list` call (pagination cap). */
export const SESSION_LIST_CAP = 1000;

const SESSION_LIST_TIMEOUT_MS = 4000;
const MODELS_TIMEOUT_MS = 5000;
const DEFAULT_MAX_BUFFER = 64 * 1024 * 1024;

/** SQLite caps bound parameters (SQLITE_MAX_VARIABLE_NUMBER); stay far below. */
const COUNT_QUERY_CHUNK = 400;

/**
 * Opens the OpenCode v2 store read-only. `node:sqlite` is imported lazily so a
 * Node build without it degrades counts to "unknown" instead of refusing to
 * boot the whole server.
 *
 * @param {string} dbPath
 * @returns {Promise<{prepare: Function, close?: Function}>}
 */
async function defaultOpenCountsDb(dbPath) {
    const { DatabaseSync } = await import("node:sqlite");
    return new DatabaseSync(dbPath, { readOnly: true });
}

/**
 * Turns one CLI row into the listing's canonical shape. v2 uses
 * `updated`/`created`; the `time_updated`/`time_created` names are accepted
 * defensively so a future/older shape keeps working.
 */
function normalizeSessionRow(row) {
    if (!row || typeof row !== "object") return null;
    const id = typeof row.id === "string" ? row.id : null;
    if (!id) return null;

    const updated = Number.isFinite(row.updated)
        ? row.updated
        : (Number.isFinite(row.time_updated) ? row.time_updated : 0);
    const created = Number.isFinite(row.created)
        ? row.created
        : (Number.isFinite(row.time_created) ? row.time_created : 0);

    return {
        id,
        title: typeof row.title === "string" ? row.title : "",
        updated,
        created,
        directory: typeof row.directory === "string" ? row.directory : ""
    };
}

/**
 * Newest first: `updated` DESC, `id` DESC -- the same total order the old
 * SQL query used, so cursors stay stable across the two implementations.
 */
function sortNewestFirst(rows) {
    return rows.slice().sort((a, b) => {
        if (b.updated !== a.updated) return b.updated - a.updated;
        if (a.id === b.id) return 0;
        return a.id < b.id ? 1 : -1;
    });
}

/**
 * @param {object} [deps] injectable seams (tests pass fakes)
 * @param {Function} [deps.execFile] callback-style child_process.execFile
 * @param {Function} [deps.execFileSync] child_process.execFileSync
 * @param {Function} [deps.resolveExe] resolves the real OpenCode executable
 * @param {Function} [deps.statDirectory] returns true when the path is a directory
 * @param {Function} [deps.openCountsDb] async (dbPath) => node:sqlite-like handle
 * @param {Function} [deps.countsDbPath] returns the OpenCode store path
 * @param {Function} [deps.now] clock in ms
 * @param {Function} [deps.warn] warning sink
 */
export function createOpenCodeCli(deps = {}) {
    const {
        execFile: execFileImpl = nodeExecFile,
        execFileSync: execFileSyncImpl = nodeExecFileSync,
        resolveExe = resolveOpenCodeExePath,
        statDirectory = (p) => statSync(p).isDirectory(),
        openCountsDb = defaultOpenCountsDb,
        countsDbPath = () => join(homedir(), ".local", "share", "opencode", "opencode.db"),
        now = Date.now,
        warn = (msg) => console.warn(msg),
        sessionListTimeoutMs = SESSION_LIST_TIMEOUT_MS,
        modelsTimeoutMs = MODELS_TIMEOUT_MS,
        maxBuffer = DEFAULT_MAX_BUFFER,
        listCap = SESSION_LIST_CAP,
        listCacheTtlMs = SESSION_LIST_CACHE_TTL_MS,
        modelsCacheTtlMs = MODELS_CACHE_TTL_MS
    } = deps;

    const listCache = new Map(); // projectDir (lowercased) -> { at, rows }
    let modelsCache = null; // { at, lines }
    let countsWarned = false;

    function isDirectory(p) {
        try {
            return Boolean(p) && statDirectory(p);
        } catch {
            return false;
        }
    }

    function execFileText(file, args, opts) {
        return new Promise((resolve, reject) => {
            execFileImpl(file, args, opts, (err, stdout) => {
                if (err) { reject(err); return; }
                resolve(String(stdout ?? ""));
            });
        });
    }

    /** Parses a `session list --format json` reply; noisy output degrades to []. */
    function parseSessionList(raw) {
        const text = String(raw ?? "").trim();
        if (!text) return [];
        let parsed;
        try {
            parsed = JSON.parse(text);
        } catch (err) {
            warn(`[OpenCode Sessions Warning] JSON inválido de 'session list' (${err.message}) — salida="${text.slice(0, 140)}"`);
            return [];
        }
        if (!Array.isArray(parsed)) {
            warn(`[OpenCode Sessions Warning] 'session list' no devolvió un array (${typeof parsed}) — salida="${text.slice(0, 140)}"`);
            return [];
        }
        return parsed.map(normalizeSessionRow).filter(Boolean);
    }

    /** One uncached CLI call. Throws on a CLI failure (the scan reports it). */
    async function requestSessionList(projectDir, maxCount) {
        const exePath = resolveExe();
        const raw = await execFileText(
            exePath,
            ["session", "list", "--format", "json", "--max-count", String(maxCount)],
            {
                cwd: projectDir,
                windowsHide: true,
                timeout: sessionListTimeoutMs,
                maxBuffer,
                encoding: "utf8"
            }
        );
        return sortNewestFirst(parseSessionList(raw));
    }

    /**
     * Cached, newest-first list for one project directory. A non-existent
     * directory yields an empty list without invoking the CLI: the registry
     * can point at a project that was moved or deleted.
     */
    async function listForDirectory(projectDir) {
        if (!isDirectory(projectDir)) return [];
        const key = String(projectDir).toLowerCase();
        const cached = listCache.get(key);
        if (cached && (now() - cached.at) < listCacheTtlMs) {
            return cached.rows;
        }
        const rows = await requestSessionList(projectDir, listCap);
        listCache.set(key, { at: now(), rows });
        return rows;
    }

    function warnCountsUnknown(err) {
        if (countsWarned) return;
        countsWarned = true;
        const reason = err && err.message ? err.message : String(err);
        warn(`[OpenCode Session Counts Warning] counts=unknown (${reason}) — el filtro de ruido por cantidad de mensajes queda desactivado.`);
    }

    /**
     * Exact message counts for the given session ids, from the v2 store.
     * Bound parameters only (placeholders are built, values never are).
     *
     * @returns {Promise<Map<string, number>|null>} null when counts are unknown
     */
    async function readMessageCounts(sessionIds) {
        if (!sessionIds.length) return new Map();
        let db = null;
        try {
            db = await openCountsDb(countsDbPath());
            const counts = new Map();
            for (let i = 0; i < sessionIds.length; i += COUNT_QUERY_CHUNK) {
                const chunk = sessionIds.slice(i, i + COUNT_QUERY_CHUNK);
                const placeholders = chunk.map(() => "?").join(",");
                const sql = `SELECT session_id, COUNT(*) AS n FROM session_message WHERE session_id IN (${placeholders}) GROUP BY session_id`;
                const rows = db.prepare(sql).all(...chunk);
                for (const row of rows || []) {
                    counts.set(row.session_id, Number(row.n) || 0);
                }
            }
            return counts;
        } catch (err) {
            warnCountsUnknown(err);
            return null;
        } finally {
            try { db?.close?.(); } catch { /* best-effort close */ }
        }
    }

    function applyCursor(rows, subCursor) {
        if (!subCursor) return rows;
        const ts = subCursor.ts;
        const id = subCursor.id;
        if (!Number.isFinite(ts) || typeof id !== "string") {
            throw new Error("Invalid OpenCode cursor");
        }
        return rows.filter((r) => r.updated < ts || (r.updated === ts && r.id < id));
    }

    function safeLimit(limit) {
        if (!Number.isInteger(limit) || limit < 1) return 30;
        return Math.min(limit, 200);
    }

    /**
     * One bounded page for a project, with the existing cursor semantics and
     * the one-exchange noise filter (sessions with <= 2 messages) applied
     * BEFORE slicing so pages are not shortened after the fact -- exactly how
     * the old SQL `COUNT(*) > 2` predicate behaved. The filter runs only when
     * the counts are known; otherwise every listed session is returned and
     * `msgCount` is null (never fabricated).
     */
    async function fetchPage({ projectDir, normProjectPath, subCursor, limit, includeAll = false }) {
        const rows = await listForDirectory(projectDir);
        const norm = normalizePathForCompare(normProjectPath || projectDir);

        // Defensive: `session list` is scoped by cwd already, but a CLI change
        // must not leak another project's sessions into this listing.
        let candidates = rows.filter((r) => normalizePathForCompare(r.directory) === norm);
        candidates = applyCursor(candidates, subCursor);

        let counts = null;
        if (!includeAll) {
            counts = await readMessageCounts(candidates.map((r) => r.id));
            if (counts) {
                candidates = candidates.filter((r) => (counts.get(r.id) || 0) > 2);
            }
        }

        const page = candidates.slice(0, safeLimit(limit));
        // `includeAll` never needed the candidate counts for filtering; it
        // still hydrates exact messageCount for the page it returns.
        if (includeAll && page.length > 0) {
            counts = await readMessageCounts(page.map((r) => r.id));
        }

        return page.map((r) => ({
            ts: r.updated,
            id: r.id,
            title: r.title,
            msgCount: counts ? (counts.get(r.id) || 0) : null
        }));
    }

    /**
     * True session count, using the same noise filter as the listing when the
     * counts are known. Unknown counts report the unfiltered (true) count
     * instead of inventing a smaller number.
     */
    async function countSessions({ projectDir, normProjectPath, includeAll = false }) {
        const rows = await listForDirectory(projectDir);
        const norm = normalizePathForCompare(normProjectPath || projectDir);
        const scoped = rows.filter((r) => normalizePathForCompare(r.directory) === norm);
        if (includeAll) return scoped.length;

        const counts = await readMessageCounts(scoped.map((r) => r.id));
        if (!counts) return scoped.length;
        return scoped.filter((r) => (counts.get(r.id) || 0) > 2).length;
    }

    /**
     * The newest session id for a project, right after the first message of a
     * brand-new OpenCode session. Deliberately UNCACHED and `--max-count 1`:
     * the cache may predate the session being looked up. Never throws -- the
     * caller is an already successful chat request.
     */
    async function findLatestSession(projectDir) {
        try {
            if (!isDirectory(projectDir)) return null;
            const rows = await requestSessionList(projectDir, 1);
            const norm = normalizePathForCompare(projectDir);
            const match = rows.find((r) => normalizePathForCompare(r.directory) === norm);
            return match ? match.id : null;
        } catch (err) {
            warn(`[OpenCode Session] no se pudo resolver la última sesión: ${err.message}`);
            return null;
        }
    }

    /** Every `provider/model` line, cached. Throws on a CLI failure. */
    function listAllModels() {
        if (modelsCache && (now() - modelsCache.at) < modelsCacheTtlMs) {
            return modelsCache.lines;
        }
        const exePath = resolveExe();
        const raw = execFileSyncImpl(exePath, ["models"], {
            encoding: "utf8",
            windowsHide: true,
            timeout: modelsTimeoutMs,
            maxBuffer
        });
        const lines = String(raw ?? "")
            .split("\n")
            .map((l) => l.trim())
            .filter((l) => l.length > 0
                && !l.startsWith("Notice:")
                && !l.startsWith("Error")
                && !l.startsWith("opencode models"));
        modelsCache = { at: now(), lines };
        return lines;
    }

    /** Models of one provider: v2 has no positional filter, so prefix here. */
    function modelsForProvider(providerId) {
        const prefix = `${providerId}/`;
        return listAllModels().filter((line) => line.startsWith(prefix));
    }

    return {
        fetchPage,
        countSessions,
        findLatestSession,
        listAllModels,
        modelsForProvider
    };
}
