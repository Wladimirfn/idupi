// Health routes, refresh jobs and the 3-hour scheduler (phone-health, T1).
//
// The phone pushes daily samples here; the PC reads merged summaries, asks for
// a hidden-display refresh of an app that does not publish to Health Connect
// (Huawei Health today, Honor Health tentative) and manages the ADB link.
//
// Refresh jobs are in-memory and serialized per source set: a second request
// while one is running answers the same job instead of starting another. The
// scheduler uses the same entry point, so a manual refresh and the timer never
// overlap on the reader mutex.
//
// Health values never enter a log line or an error body; job results carry only
// per-source status and a metric count.

import { randomBytes } from "node:crypto";

import { hasAnyMetric, parseHealthApp, toStoreMetrics } from "./health-profiles.mjs";

const PREFIX = "/api/v1/health";
const MAX_BODY_BYTES = 512 * 1024;
const JOB_ID_PATTERN = /^hgj-[0-9a-f]{12}$/;
const MAX_JOBS = 20;
const JOB_TTL_MS = 30 * 60 * 1000;
const MAX_SOURCES = 4;
const REFRESHABLE_SOURCES = new Set(["huawei_health"]);

export const DEFAULT_REFRESH_INTERVAL_MS = 3 * 60 * 60 * 1000;

function typedError(code, message) {
    const err = new Error(message);
    err.code = code;
    return err;
}

function isPlainObject(value) {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function sendJson(res, status, payload) {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(payload));
}

function sendError(res, status, message, code) {
    sendJson(res, status, { error: message, code });
}

function methodNotAllowed(res, allow) {
    res.writeHead(405, { "Content-Type": "application/json", "Allow": allow });
    res.end(JSON.stringify({ error: "method not allowed", code: "METHOD_NOT_ALLOWED" }));
}

function readJsonBody(req, maxBytes) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        let size = 0;
        let settled = false;
        req.on("data", (chunk) => {
            if (settled) return;
            const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
            size += buf.length;
            if (size > maxBytes) {
                settled = true;
                reject(typedError("TOO_LARGE", `body exceeds ${maxBytes} bytes`));
                return;
            }
            chunks.push(buf);
        });
        req.on("end", () => {
            if (settled) return;
            settled = true;
            try {
                resolve(JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}"));
            } catch {
                reject(typedError("BAD_REQUEST", "invalid JSON body"));
            }
        });
        req.on("error", (err) => {
            if (settled) return;
            settled = true;
            reject(err);
        });
    });
}

function bodyError(res, err) {
    if (err && err.code === "TOO_LARGE") {
        sendError(res, 413, err.message, "TOO_LARGE");
        return;
    }
    sendError(res, 400, (err && err.message) || "invalid body", "BAD_REQUEST");
}

function queryValue(req, key) {
    try {
        return new URL(req.url || "", "http://localhost").searchParams.get(key);
    } catch {
        return null;
    }
}

function normalizeRefreshSources(raw) {
    if (raw === undefined || raw === null) return ["huawei_health"];
    if (!Array.isArray(raw)) {
        throw typedError("INVALID_SOURCES", "sources must be an array");
    }
    if (raw.length === 0) return ["huawei_health"];
    if (raw.length > MAX_SOURCES) {
        throw typedError("INVALID_SOURCES", `sources is capped at ${MAX_SOURCES}`);
    }
    const sources = [];
    for (const candidate of raw) {
        if (typeof candidate !== "string" || !REFRESHABLE_SOURCES.has(candidate)) {
            throw typedError("INVALID_SOURCES", `unsupported source "${String(candidate)}"`);
        }
        if (!sources.includes(candidate)) sources.push(candidate);
    }
    return sources;
}

export function healthDayKey(date, tzOffsetMinutes) {
    const shifted = new Date(date.getTime() + tzOffsetMinutes * 60_000);
    return shifted.toISOString().slice(0, 10);
}

function countMetrics(metrics) {
    if (!metrics || typeof metrics !== "object") return 0;
    return Object.values(metrics).filter((value) => {
        if (value === null || value === undefined) return false;
        if (typeof value === "object") {
            return Object.values(value).some((inner) => inner !== null && inner !== undefined);
        }
        return true;
    }).length;
}

function publicJob(job) {
    return {
        id: job.id,
        status: job.status,
        sources: [...job.sources],
        reason: job.reason || null,
        createdAt: job.createdAt,
        startedAt: job.startedAt,
        finishedAt: job.finishedAt,
        results: job.results.map((entry) => ({ ...entry })),
        error: job.error || null,
    };
}

/**
 * Turns one allowlisted app on screen into one store sample. Installed apps the
 * phone reported with `route: "hidden_display"` are tried first, then the rest
 * of the allowlist for the source.
 */
export function createHealthCollector({ reader, store, now = () => new Date() } = {}) {
    if (!reader || !store) throw new TypeError("createHealthCollector requires reader and store");

    async function collect({ source } = {}) {
        if (typeof source !== "string" || !source) {
            throw typedError("INVALID_SOURCE", "source is required");
        }
        const allowed = reader.listAllowed().filter((entry) => entry.source === source);
        if (allowed.length === 0) {
            throw typedError("INVALID_SOURCE", `source "${source}" has no reader`);
        }
        const installed = new Set(
            store.installedApps()
                .filter((entry) => entry.route === "hidden_display")
                .map((entry) => entry.packageName),
        );
        const ordered = [...allowed].sort((left, right) => {
            const l = installed.has(left.packageName) ? 0 : 1;
            const r = installed.has(right.packageName) ? 0 : 1;
            return l - r;
        });

        const failures = [];
        for (const entry of ordered) {
            const read = await reader.readApp({
                packageName: entry.packageName,
                profile: source,
            });
            if (!read.ok) {
                failures.push(`${entry.packageName}:${read.reason || "failed"}`);
                continue;
            }
            const parsed = parseHealthApp(entry.packageName, read.nodes);
            if (!parsed || !hasAnyMetric(parsed.metrics)) {
                failures.push(`${entry.packageName}:no_metrics`);
                continue;
            }
            return {
                source,
                sourceApp: entry.packageName,
                tentative: parsed.tentative === true,
                metrics: toStoreMetrics(parsed.metrics),
                capturedAt: now().toISOString(),
            };
        }
        throw typedError(
            "COLLECT_FAILED",
            failures.length > 0
                ? `could not read: ${failures.join(", ")}`
                : "no allowlisted app available",
        );
    }

    return { collect };
}

/**
 * @param {{
 *   store: ReturnType<import("./health-store.mjs").createHealthStore>,
 *   adb: ReturnType<import("./adb-connection.mjs").createAdbConnection>,
 *   collector: ReturnType<typeof createHealthCollector>,
 *   now?: () => Date,
 *   log?: { warn?: Function, info?: Function },
 * }} options
 */
export function createHealthRoutes({ store, adb, collector, now = () => new Date(), log } = {}) {
    if (!store) throw new TypeError("createHealthRoutes requires store");
    if (!adb) throw new TypeError("createHealthRoutes requires adb");
    if (!collector) throw new TypeError("createHealthRoutes requires collector");

    const jobs = new Map();
    const jobPromises = new Map();

    function pruneJobs() {
        const cutoff = now().getTime() - JOB_TTL_MS;
        for (const [id, job] of jobs) {
            const finished = job.finishedAt ? Date.parse(job.finishedAt) : NaN;
            const stale = Number.isFinite(finished) && finished < cutoff;
            if (stale) {
                jobs.delete(id);
                jobPromises.delete(id);
            }
        }
        while (jobs.size > MAX_JOBS) {
            const oldest = jobs.keys().next().value;
            jobs.delete(oldest);
            jobPromises.delete(oldest);
        }
    }

    async function runJob(job) {
        job.status = "running";
        job.startedAt = now().toISOString();
        const dateKey = healthDayKey(now(), store.tzOffsetMinutes());
        let failures = 0;
        for (const source of job.sources) {
            try {
                const sample = await collector.collect({ source });
                const result = store.recordSamples({
                    samples: [{
                        date: dateKey,
                        source: sample.source,
                        sourceApp: sample.sourceApp,
                        metrics: sample.metrics,
                        capturedAt: sample.capturedAt || now().toISOString(),
                    }],
                });
                job.results.push({
                    source,
                    ok: true,
                    date: dateKey,
                    sourceApp: sample.sourceApp,
                    metrics: countMetrics(sample.metrics),
                    stored: result.stored,
                    merged: result.merged,
                });
            } catch (err) {
                failures += 1;
                job.results.push({
                    source,
                    ok: false,
                    reason: (err && err.code) || "collect_failed",
                });
                log?.warn?.(`[health] refresh ${source} failed: ${(err && err.code) || "error"}`);
            }
        }
        job.status = failures === job.sources.length ? "failed" : "done";
        job.finishedAt = now().toISOString();
        job.error = job.status === "failed" ? "no health source could be read" : null;
        return publicJob(job);
    }

    /** Starts (or joins) a refresh job. The promise settles when it finishes. */
    function triggerRefresh(sources, { reason = "manual" } = {}) {
        const requested = normalizeRefreshSources(sources);
        pruneJobs();
        for (const job of jobs.values()) {
            if (job.status !== "queued" && job.status !== "running") continue;
            const same = job.sources.length === requested.length &&
                job.sources.every((source, index) => source === requested[index]);
            if (same) return { job: publicJob(job), promise: jobPromises.get(job.id) };
        }
        const job = {
            id: `hgj-${randomBytes(6).toString("hex")}`,
            status: "queued",
            sources: requested,
            reason,
            createdAt: now().toISOString(),
            startedAt: null,
            finishedAt: null,
            results: [],
            error: null,
        };
        jobs.set(job.id, job);
        const promise = runJob(job);
        jobPromises.set(job.id, promise);
        return { job: publicJob(job), promise };
    }

    async function postSamples(req, res) {
        let body;
        try {
            body = await readJsonBody(req, MAX_BODY_BYTES);
        } catch (err) {
            bodyError(res, err);
            return;
        }
        if (!isPlainObject(body)) {
            sendError(res, 400, "body must be a JSON object", "BAD_REQUEST");
            return;
        }
        try {
            const result = store.recordSamples(body);
            sendJson(res, 200, result);
        } catch (err) {
            sendError(res, 400, (err && err.message) || "invalid samples", "INVALID_SAMPLE");
        }
    }

    function getSummary(req, res) {
        const raw = queryValue(req, "days");
        let days = 7;
        if (raw !== null && raw !== "") {
            if (!/^\d+$/.test(raw)) {
                sendError(res, 400, "days must be an integer between 1 and 120", "INVALID_DAYS");
                return;
            }
            days = Number(raw);
        }
        try {
            sendJson(res, 200, store.summary({ days }));
        } catch (err) {
            sendError(res, 400, (err && err.message) || "invalid days", "INVALID_DAYS");
        }
    }

    async function postRefresh(req, res) {
        let body;
        try {
            body = await readJsonBody(req, MAX_BODY_BYTES);
        } catch (err) {
            bodyError(res, err);
            return;
        }
        if (!isPlainObject(body)) {
            sendError(res, 400, "body must be a JSON object", "BAD_REQUEST");
            return;
        }
        let sources;
        try {
            sources = normalizeRefreshSources(body.sources);
        } catch (err) {
            sendError(res, 400, (err && err.message) || "invalid sources", "INVALID_SOURCES");
            return;
        }
        const { job } = triggerRefresh(sources, { reason: "manual" });
        sendJson(res, 202, { job });
    }

    function getJobById(res, id) {
        const job = jobs.get(id);
        if (!job) {
            sendError(res, 404, "refresh job not found", "NOT_FOUND");
            return;
        }
        sendJson(res, 200, { job: publicJob(job) });
    }

    async function postPair(req, res) {
        let body;
        try {
            body = await readJsonBody(req, MAX_BODY_BYTES);
        } catch (err) {
            bodyError(res, err);
            return;
        }
        if (!isPlainObject(body)) {
            sendError(res, 400, "body must be a JSON object", "BAD_REQUEST");
            return;
        }
        try {
            const result = await adb.pair({ hostPort: body.hostPort, code: body.code });
            if (!result.ok) {
                sendError(res, 400, `pairing failed: ${result.reason}`, "PAIR_FAILED");
                return;
            }
            sendJson(res, 200, result);
        } catch (err) {
            sendError(res, 500, "adb pair failed", "ADB_ERROR");
        }
    }

    async function postConnect(req, res) {
        let body;
        try {
            body = await readJsonBody(req, MAX_BODY_BYTES);
        } catch (err) {
            bodyError(res, err);
            return;
        }
        if (!isPlainObject(body)) {
            sendError(res, 400, "body must be a JSON object", "BAD_REQUEST");
            return;
        }
        try {
            const result = await adb.connect({ hostPort: body.hostPort });
            if (!result.ok) {
                sendError(res, 400, `connect failed: ${result.reason}`, "CONNECT_FAILED");
                return;
            }
            sendJson(res, 200, result);
        } catch (err) {
            sendError(res, 500, "adb connect failed", "ADB_ERROR");
        }
    }

    async function handle(req, res, pathname) {
        if (pathname !== PREFIX && !pathname.startsWith(PREFIX + "/")) return false;
        const parts = pathname.slice(PREFIX.length).split("/").filter(Boolean);
        if (parts.length > 3) return false;

        if (parts.length === 0) {
            if (req.method !== "GET") {
                methodNotAllowed(res, "GET");
                return true;
            }
            getSummary(req, res);
            return true;
        }

        if (parts[0] === "samples" && parts.length === 1) {
            if (req.method !== "POST") {
                methodNotAllowed(res, "POST");
                return true;
            }
            await postSamples(req, res);
            return true;
        }

        if (parts[0] === "summary" && parts.length === 1) {
            if (req.method !== "GET") {
                methodNotAllowed(res, "GET");
                return true;
            }
            getSummary(req, res);
            return true;
        }

        if (parts[0] === "sources" && parts.length === 1) {
            if (req.method !== "GET") {
                methodNotAllowed(res, "GET");
                return true;
            }
            sendJson(res, 200, store.sources());
            return true;
        }

        if (parts[0] === "refresh") {
            if (parts.length === 1) {
                if (req.method !== "POST") {
                    methodNotAllowed(res, "POST");
                    return true;
                }
                await postRefresh(req, res);
                return true;
            }
            if (parts.length === 2) {
                if (req.method !== "GET") {
                    methodNotAllowed(res, "GET");
                    return true;
                }
                if (!JOB_ID_PATTERN.test(parts[1])) {
                    sendError(res, 404, "refresh job not found", "NOT_FOUND");
                    return true;
                }
                getJobById(res, parts[1]);
                return true;
            }
        }

        if (parts[0] === "adb") {
            if (parts.length === 1) {
                if (req.method !== "GET") {
                    methodNotAllowed(res, "GET");
                    return true;
                }
                try {
                    sendJson(res, 200, await adb.status());
                } catch {
                    sendError(res, 500, "adb status failed", "ADB_ERROR");
                }
                return true;
            }
            if (parts.length === 2 && parts[1] === "pair") {
                if (req.method !== "POST") {
                    methodNotAllowed(res, "POST");
                    return true;
                }
                await postPair(req, res);
                return true;
            }
            if (parts.length === 2 && parts[1] === "connect") {
                if (req.method !== "POST") {
                    methodNotAllowed(res, "POST");
                    return true;
                }
                await postConnect(req, res);
                return true;
            }
        }

        return false;
    }

    return { handle, triggerRefresh };
}

/**
 * Refreshes Huawei Health every 3 h when a target is saved. Skips quietly when
 * ADB is unreachable or a refresh is already running; a timer tick never
 * overlaps another one.
 *
 * @param {{
 *   adb: ReturnType<import("./adb-connection.mjs").createAdbConnection>,
 *   triggerRefresh: (sources: string[], meta?: object) => {job: object, promise: Promise<object>},
 *   intervalMs?: number,
 *   now?: () => Date,
 *   setIntervalFn?: Function,
 *   clearIntervalFn?: Function,
 *   log?: { info?: Function, warn?: Function },
 * }} options
 */
export function createHealthScheduler({
    adb,
    triggerRefresh,
    intervalMs = DEFAULT_REFRESH_INTERVAL_MS,
    setIntervalFn = setInterval,
    clearIntervalFn = clearInterval,
    log,
} = {}) {
    if (!adb) throw new TypeError("createHealthScheduler requires adb");
    if (typeof triggerRefresh !== "function") {
        throw new TypeError("createHealthScheduler requires triggerRefresh");
    }
    let timer = null;
    let running = false;

    async function runNow() {
        if (running) return { ok: false, skipped: "overlap" };
        if (!adb.hasSavedTargets()) return { ok: false, skipped: "no_target" };
        running = true;
        try {
            const link = await adb.ensureConnected();
            if (!link.ok) {
                log?.info?.(`[health] scheduler skipped: adb ${link.reason}`);
                return { ok: false, skipped: "adb_unreachable", reason: link.reason };
            }
            const { promise } = triggerRefresh(["huawei_health"], { reason: "schedule" });
            const job = await promise;
            return { ok: job.status === "done", job };
        } catch (err) {
            log?.warn?.(`[health] scheduler failed: ${(err && err.code) || "error"}`);
            return { ok: false, skipped: "error" };
        } finally {
            running = false;
        }
    }

    function start() {
        if (timer) return;
        timer = setIntervalFn(() => {
            void runNow();
        }, intervalMs);
        if (timer && typeof timer.unref === "function") timer.unref();
    }

    function stop() {
        if (timer === null) return;
        clearIntervalFn(timer);
        timer = null;
    }

    return {
        start,
        stop,
        runNow,
        isRunning: () => running,
        intervalMs,
    };
}
