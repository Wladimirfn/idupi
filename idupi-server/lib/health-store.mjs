// Health store (phone-health, T1).
//
// One JSON file in idupi-server/data/ (gitignored):
//
//   health.json  { version, tzOffsetMinutes, samples, installedApps }
//
// A sample is one source's view of one owner-local day. The latest capture per
// (date, source) wins: its non-null metrics overwrite the older ones and the
// older values survive for the keys the new capture did not report. History is
// capped at 120 days.
//
// Everything that decides something is pure and exported (metric schema,
// validation, merge, day window). The store only owns persistence. Day keys
// are computed from the phone's tzOffsetMinutes, so no server-local-time math
// leaks into the history.

import { mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const HEALTH_FILE = "health.json";
export const RETENTION_DAYS = 120;
export const MAX_SAMPLES_PER_POST = 60;
export const MAX_STORED_SAMPLES = 4000;
export const MAX_INSTALLED_APPS = 40;
export const MAX_TOP_APPS = 20;
export const MAX_WORKOUTS = 50;
export const MAX_STRING_CHARS = 120;
export const MAX_TZ_OFFSET_MINUTES = 14 * 60;

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const SOURCE_PATTERN = /^[a-z][a-z0-9_]{0,39}$/;
const INSTALLED_ROUTE_PATTERN = /^[a-z][a-z0-9_]{0,39}$/;

/** Metric schema: every accepted key, its kind and its sanity range. */
export const METRIC_FIELDS = {
    steps: { kind: "int", min: 0, max: 200000 },
    stepsGoal: { kind: "int", min: 0, max: 200000 },
    distanceM: { kind: "number", min: 0, max: 1000000 },
    caloriesKcal: { kind: "number", min: 0, max: 50000 },
    activeCaloriesKcal: { kind: "number", min: 0, max: 50000 },
    heartRateBpm: { kind: "int", min: 20, max: 250 },
    restingHeartRateBpm: { kind: "int", min: 20, max: 200 },
    heartRateMin: { kind: "int", min: 20, max: 250 },
    heartRateMax: { kind: "int", min: 20, max: 250 },
    sleepMinutes: { kind: "int", min: 0, max: 1440 },
    sleepGoalMinutes: { kind: "int", min: 0, max: 1440 },
    sleepStart: { kind: "iso" },
    sleepEnd: { kind: "iso" },
    exerciseMinutes: { kind: "int", min: 0, max: 1440 },
    exerciseGoalMinutes: { kind: "int", min: 0, max: 1440 },
    weightKg: { kind: "number", min: 20, max: 400 },
    weightGoalKg: { kind: "number", min: 20, max: 400 },
    spo2Pct: { kind: "number", min: 50, max: 100 },
    stress: { kind: "int", min: 0, max: 100 },
    stressLabel: { kind: "text", maxChars: 40 },
    waterMl: { kind: "int", min: 0, max: 20000 },
    waterGoalMl: { kind: "int", min: 0, max: 20000 },
    screenTimeMinutes: { kind: "int", min: 0, max: 1440 },
    unlocks: { kind: "int", min: 0, max: 5000 },
    nightScreenMinutes: { kind: "int", min: 0, max: 1440 },
    wakeTime: { kind: "text", maxChars: 10 },
    wakeGoal: { kind: "text", maxChars: 10 },
    bedtimeGoal: { kind: "text", maxChars: 10 },
    sleepStages: { kind: "stages" },
    workouts: { kind: "workouts" },
    topApps: { kind: "topApps" },
};

export const METRIC_KEYS = Object.keys(METRIC_FIELDS);

/** Sources the CLI and the summary know by name; any valid id is accepted. */
export const SOURCE_LABELS = {
    huawei_health: "Huawei Health",
    health_connect: "Health Connect",
    wellbeing: "Bienestar digital",
    samsung_health: "Samsung Health",
    honor_health: "Honor Health",
    mi_fitness: "Mi Fitness",
};

const STAGE_KEYS = ["deep", "light", "rem", "awake"];

function typedError(code, message) {
    const err = new Error(message);
    err.code = code;
    return err;
}

function isPlainObject(value) {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function clipText(value, maxChars) {
    const text = String(value ?? "").trim();
    return text.length > maxChars ? text.slice(0, maxChars) : text;
}

function isoOrNull(value, field) {
    if (typeof value !== "string" || !value.trim()) return null;
    const ms = Date.parse(value);
    if (!Number.isFinite(ms)) {
        throw typedError("INVALID_SAMPLE", `${field} must be an ISO-8601 date`);
    }
    return new Date(ms).toISOString();
}

function numberInRange(value, field, min, max, integer) {
    if (typeof value !== "number" || !Number.isFinite(value)) {
        throw typedError("INVALID_SAMPLE", `${field} must be a number`);
    }
    if (integer && !Number.isInteger(value)) {
        throw typedError("INVALID_SAMPLE", `${field} must be an integer`);
    }
    if (value < min || value > max) {
        throw typedError("INVALID_SAMPLE", `${field} is outside ${min}..${max}`);
    }
    return value;
}

function normalizeSleepStages(value) {
    if (!isPlainObject(value)) {
        throw typedError("INVALID_SAMPLE", "sleepStages must be an object");
    }
    const stages = {};
    for (const key of Object.keys(value)) {
        if (!STAGE_KEYS.includes(key)) {
            throw typedError("INVALID_SAMPLE", `sleepStages.${key} is not a known stage`);
        }
        const minutes = value[key];
        if (minutes === null || minutes === undefined) continue;
        stages[key] = numberInRange(minutes, `sleepStages.${key}`, 0, 1440, true);
    }
    return stages;
}

function normalizeWorkouts(value) {
    if (!Array.isArray(value)) {
        throw typedError("INVALID_SAMPLE", "workouts must be an array");
    }
    if (value.length > MAX_WORKOUTS) {
        throw typedError("INVALID_SAMPLE", `workouts is capped at ${MAX_WORKOUTS}`);
    }
    return value.map((entry, index) => {
        if (!isPlainObject(entry)) {
            throw typedError("INVALID_SAMPLE", `workouts[${index}] must be an object`);
        }
        const workout = {};
        if (entry.type !== undefined && entry.type !== null) {
            workout.type = clipText(entry.type, 60);
        }
        if (entry.minutes !== undefined && entry.minutes !== null) {
            workout.minutes = numberInRange(entry.minutes, `workouts[${index}].minutes`, 0, 1440, true);
        }
        if (entry.start !== undefined && entry.start !== null) {
            workout.start = isoOrNull(entry.start, `workouts[${index}].start`);
        }
        if (entry.kcal !== undefined && entry.kcal !== null) {
            workout.kcal = numberInRange(entry.kcal, `workouts[${index}].kcal`, 0, 5000, false);
        }
        if (Object.keys(workout).length === 0) {
            throw typedError("INVALID_SAMPLE", `workouts[${index}] is empty`);
        }
        return workout;
    });
}

function normalizeTopApps(value) {
    if (!Array.isArray(value)) {
        throw typedError("INVALID_SAMPLE", "topApps must be an array");
    }
    if (value.length > MAX_TOP_APPS) {
        throw typedError("INVALID_SAMPLE", `topApps is capped at ${MAX_TOP_APPS}`);
    }
    return value.map((entry, index) => {
        if (!isPlainObject(entry)) {
            throw typedError("INVALID_SAMPLE", `topApps[${index}] must be an object`);
        }
        const label = clipText(entry.label, 80);
        if (!label) {
            throw typedError("INVALID_SAMPLE", `topApps[${index}].label is required`);
        }
        if (entry.minutes === undefined || entry.minutes === null) {
            throw typedError("INVALID_SAMPLE", `topApps[${index}].minutes is required`);
        }
        return {
            label,
            minutes: numberInRange(entry.minutes, `topApps[${index}].minutes`, 0, 1440, true),
        };
    });
}

/**
 * Validates and normalizes the metrics object. Unknown keys reject the sample;
 * null/undefined values are dropped so they never overwrite older data.
 */
export function normalizeMetrics(raw) {
    if (!isPlainObject(raw)) {
        throw typedError("INVALID_SAMPLE", "metrics must be an object");
    }
    const metrics = {};
    for (const key of Object.keys(raw)) {
        const field = METRIC_FIELDS[key];
        if (!field) {
            throw typedError("INVALID_SAMPLE", `unknown metric "${key}"`);
        }
        const value = raw[key];
        if (value === null || value === undefined) continue;
        switch (field.kind) {
            case "int":
                metrics[key] = numberInRange(value, key, field.min, field.max, true);
                break;
            case "number":
                metrics[key] = numberInRange(value, key, field.min, field.max, false);
                break;
            case "text": {
                if (typeof value !== "string" && typeof value !== "number") {
                    throw typedError("INVALID_SAMPLE", `${key} must be text`);
                }
                const text = clipText(value, field.maxChars);
                if (text) metrics[key] = text;
                break;
            }
            case "iso": {
                const iso = isoOrNull(value, key);
                if (iso) metrics[key] = iso;
                break;
            }
            case "stages":
                metrics[key] = normalizeSleepStages(value);
                break;
            case "workouts":
                metrics[key] = normalizeWorkouts(value);
                break;
            case "topApps":
                metrics[key] = normalizeTopApps(value);
                break;
            default:
                throw typedError("INVALID_SAMPLE", `metric "${key}" has no validator`);
        }
    }
    return metrics;
}

/** Newer non-null values win; older keys the capture did not report survive. */
export function mergeMetrics(previous, incoming) {
    const merged = { ...(previous || {}) };
    for (const [key, value] of Object.entries(incoming || {})) {
        if (value === null || value === undefined) continue;
        if (key === "sleepStages") {
            merged.sleepStages = { ...(merged.sleepStages || {}), ...value };
            continue;
        }
        merged[key] = value;
    }
    return merged;
}

function validDateKey(value) {
    if (typeof value !== "string" || !DATE_PATTERN.test(value)) return false;
    const [year, month, day] = value.split("-").map(Number);
    const date = new Date(Date.UTC(year, month - 1, day));
    return (
        date.getUTCFullYear() === year &&
        date.getUTCMonth() === month - 1 &&
        date.getUTCDate() === day
    );
}

function dayKey(date, tzOffsetMinutes) {
    const shifted = new Date(date.getTime() + tzOffsetMinutes * 60_000);
    return shifted.toISOString().slice(0, 10);
}

function addDays(dateKeyValue, days) {
    const [year, month, day] = dateKeyValue.split("-").map(Number);
    const date = new Date(Date.UTC(year, month - 1, day + days));
    return date.toISOString().slice(0, 10);
}

function defaultOffset(now) {
    // The phone reports its own offset; the PC offset is only a fallback.
    return -now.getTimezoneOffset();
}

/**
 * @param {{
 *   dataDir: string,
 *   now?: () => Date,
 *   log?: { warn?: Function },
 * }} options
 */
export function createHealthStore({ dataDir, now = () => new Date(), log } = {}) {
    if (typeof dataDir !== "string" || !dataDir) {
        throw new TypeError("createHealthStore requires dataDir");
    }
    const file = join(dataDir, HEALTH_FILE);
    let tempCounter = 0;

    const state = load();

    function load() {
        const empty = { version: 1, tzOffsetMinutes: null, samples: [], installedApps: [] };
        let parsed;
        try {
            parsed = JSON.parse(readFileSync(file, "utf8"));
        } catch (err) {
            if (err && err.code !== "ENOENT") {
                log?.warn?.(`[health] cannot read ${HEALTH_FILE}: ${err.message}`);
            }
            return empty;
        }
        if (!isPlainObject(parsed)) return empty;
        const known = new Set(METRIC_KEYS);
        const samples = [];
        for (const candidate of Array.isArray(parsed.samples) ? parsed.samples : []) {
            if (!isPlainObject(candidate)) continue;
            if (!validDateKey(candidate.date)) continue;
            if (typeof candidate.source !== "string" || !SOURCE_PATTERN.test(candidate.source)) continue;
            const metrics = {};
            for (const [key, value] of Object.entries(
                isPlainObject(candidate.metrics) ? candidate.metrics : {},
            )) {
                if (known.has(key) && value !== null && value !== undefined) metrics[key] = value;
            }
            samples.push({
                date: candidate.date,
                source: candidate.source,
                sourceApp: typeof candidate.sourceApp === "string" ? candidate.sourceApp : null,
                metrics,
                capturedAt: typeof candidate.capturedAt === "string" ? candidate.capturedAt : null,
            });
        }
        const installedApps = [];
        for (const candidate of Array.isArray(parsed.installedApps) ? parsed.installedApps : []) {
            if (!isPlainObject(candidate)) continue;
            if (typeof candidate.packageName !== "string") continue;
            installedApps.push({
                packageName: candidate.packageName.slice(0, 120),
                label: typeof candidate.label === "string" ? candidate.label.slice(0, 80) : null,
                route: typeof candidate.route === "string" ? candidate.route.slice(0, 40) : null,
                reportedAt: typeof candidate.reportedAt === "string" ? candidate.reportedAt : null,
            });
        }
        return {
            version: 1,
            tzOffsetMinutes: Number.isInteger(parsed.tzOffsetMinutes)
                ? parsed.tzOffsetMinutes
                : null,
            samples,
            installedApps,
        };
    }

    function atomicWrite(payload) {
        mkdirSync(dataDir, { recursive: true });
        tempCounter += 1;
        const tempPath = `${file}.${process.pid}-${tempCounter}.tmp`;
        writeFileSync(tempPath, payload, "utf8");
        try {
            renameSync(tempPath, file);
        } catch (err) {
            try {
                unlinkSync(tempPath);
            } catch {
                // The rename already failed; a missing temp file is fine.
            }
            throw err;
        }
    }

    function persist() {
        atomicWrite(JSON.stringify(state, null, 2) + "\n");
    }

    function tzOffset() {
        return Number.isInteger(state.tzOffsetMinutes)
            ? state.tzOffsetMinutes
            : defaultOffset(now());
    }

    function todayKey() {
        return dayKey(now(), tzOffset());
    }

    function validateTzOffset(value) {
        if (value === undefined || value === null) return null;
        if (!Number.isInteger(value) || Math.abs(value) > MAX_TZ_OFFSET_MINUTES) {
            throw typedError("INVALID_SAMPLE", "tzOffsetMinutes must be an integer between -840 and 840");
        }
        return value;
    }

    function validateInstalledApps(raw) {
        if (!Array.isArray(raw)) {
            throw typedError("INVALID_SAMPLE", "installedApps must be an array");
        }
        if (raw.length > MAX_INSTALLED_APPS) {
            throw typedError("INVALID_SAMPLE", `installedApps is capped at ${MAX_INSTALLED_APPS}`);
        }
        return raw.map((entry, index) => {
            if (!isPlainObject(entry)) {
                throw typedError("INVALID_SAMPLE", `installedApps[${index}] must be an object`);
            }
            const packageName = clipText(entry.packageName, 120);
            if (!/^[A-Za-z0-9_.]{1,120}$/.test(packageName)) {
                throw typedError("INVALID_SAMPLE", `installedApps[${index}].packageName is invalid`);
            }
            const route = clipText(entry.route, 40);
            if (!INSTALLED_ROUTE_PATTERN.test(route)) {
                throw typedError("INVALID_SAMPLE", `installedApps[${index}].route is invalid`);
            }
            return {
                packageName,
                label: clipText(entry.label, 80) || null,
                route,
                reportedAt: now().toISOString(),
            };
        });
    }

    function validateSample(raw, { tzOffsetMinutes, floorDate }) {
        if (!isPlainObject(raw)) {
            throw typedError("INVALID_SAMPLE", "each sample must be an object");
        }
        if (!validDateKey(raw.date)) {
            throw typedError("INVALID_SAMPLE", "date must be YYYY-MM-DD");
        }
        if (raw.date < floorDate) {
            throw typedError("INVALID_SAMPLE", "date is older than the retention window");
        }
        if (raw.date > todayKey()) {
            throw typedError("INVALID_SAMPLE", "date is in the future");
        }
        if (typeof raw.source !== "string" || !SOURCE_PATTERN.test(raw.source)) {
            throw typedError("INVALID_SAMPLE", "source is invalid");
        }
        const metrics = normalizeMetrics(raw.metrics);
        const capturedAt = raw.capturedAt === undefined || raw.capturedAt === null
            ? now().toISOString()
            : isoOrNull(raw.capturedAt, "capturedAt");
        if (!capturedAt) {
            throw typedError("INVALID_SAMPLE", "capturedAt is invalid");
        }
        return {
            date: raw.date,
            source: raw.source,
            sourceApp: clipText(raw.sourceApp, 120) || null,
            metrics,
            capturedAt,
            tzOffsetMinutes,
        };
    }

    function upsert(sample) {
        const key = `${sample.date}\u0000${sample.source}`;
        const existing = state.samples.find(
            (entry) => `${entry.date}\u0000${entry.source}` === key,
        );
        if (existing) {
            existing.metrics = mergeMetrics(existing.metrics, sample.metrics);
            existing.capturedAt = sample.capturedAt;
            existing.sourceApp = sample.sourceApp || existing.sourceApp;
            return "merged";
        }
        state.samples.push({
            date: sample.date,
            source: sample.source,
            sourceApp: sample.sourceApp,
            metrics: sample.metrics,
            capturedAt: sample.capturedAt,
        });
        return "stored";
    }

    function pruneRetention() {
        const floor = addDays(todayKey(), -(RETENTION_DAYS - 1));
        const before = state.samples.length;
        state.samples = state.samples.filter((entry) => entry.date >= floor);
        if (state.samples.length > MAX_STORED_SAMPLES) {
            state.samples.sort((a, b) => String(a.date).localeCompare(String(b.date)));
            state.samples = state.samples.slice(-MAX_STORED_SAMPLES);
        }
        return before - state.samples.length;
    }

    /**
     * Stores a phone upload. One bad sample rejects the whole request with a
     * 400 so a contract mismatch is visible instead of half-stored.
     */
    function recordSamples({ tzOffsetMinutes, samples, installedApps } = {}) {
        const offset = validateTzOffset(tzOffsetMinutes);
        const previousOffset = state.tzOffsetMinutes;
        const previousApps = state.installedApps;
        if (offset !== null) state.tzOffsetMinutes = offset;

        try {
            const floorDate = addDays(todayKey(), -(RETENTION_DAYS - 1));

            let appsApplied = 0;
            if (installedApps !== undefined) {
                const apps = validateInstalledApps(installedApps);
                const byPackage = new Map(state.installedApps.map((entry) => [entry.packageName, entry]));
                for (const app of apps) byPackage.set(app.packageName, app);
                state.installedApps = [...byPackage.values()]
                    .sort((a, b) => String(a.packageName).localeCompare(String(b.packageName)))
                    .slice(0, MAX_INSTALLED_APPS);
                appsApplied = apps.length;
            }

            const list = Array.isArray(samples) ? samples : [];
            if (list.length > MAX_SAMPLES_PER_POST) {
                throw typedError("INVALID_SAMPLE", `samples is capped at ${MAX_SAMPLES_PER_POST}`);
            }
            const normalized = list.map((sample) => validateSample(sample, {
                tzOffsetMinutes: offset ?? tzOffset(),
                floorDate,
            }));

            let stored = 0;
            let merged = 0;
            for (const sample of normalized) {
                if (upsert(sample) === "stored") stored += 1;
                else merged += 1;
            }
            const pruned = pruneRetention();
            persist();
            return {
                ok: true,
                stored,
                merged,
                pruned,
                installedApps: appsApplied,
                dates: [...new Set(normalized.map((sample) => sample.date))].sort(),
                tzOffsetMinutes: tzOffset(),
            };
        } catch (err) {
            // A rejected upload must not leave half-applied state behind.
            state.tzOffsetMinutes = previousOffset;
            state.installedApps = previousApps;
            throw err;
        }
    }

    /** Merged view of one date: newest non-null value per metric. */
    function mergeDay(dateKey) {
        const entries = state.samples
            .filter((entry) => entry.date === dateKey)
            .sort((a, b) => String(a.capturedAt).localeCompare(String(b.capturedAt)));
        if (entries.length === 0) {
            return { date: dateKey, metrics: {}, sources: {}, capturedAt: null };
        }
        let metrics = {};
        const sources = {};
        for (const entry of entries) {
            for (const [key, value] of Object.entries(entry.metrics)) {
                if (value === null || value === undefined) continue;
                metrics = mergeMetrics(metrics, { [key]: value });
                sources[key] = entry.source;
            }
        }
        return {
            date: dateKey,
            metrics,
            sources,
            capturedAt: entries[entries.length - 1].capturedAt,
        };
    }

    function summary({ days = 7 } = {}) {
        const requested = Number.isInteger(days) ? days : 7;
        if (requested < 1 || requested > RETENTION_DAYS) {
            throw typedError("INVALID_DAYS", `days must be an integer between 1 and ${RETENTION_DAYS}`);
        }
        const today = todayKey();
        const window = [];
        for (let offset = requested - 1; offset >= 0; offset -= 1) {
            window.push(addDays(today, -offset));
        }
        const daysView = window.map((key) => mergeDay(key));
        const allDates = [...new Set(state.samples.map((entry) => entry.date))].sort();
        const latest = allDates.length > 0
            ? mergeDay(allDates[allDates.length - 1])
            : null;
        return {
            rangeDays: requested,
            tzOffsetMinutes: tzOffset(),
            days: daysView,
            latest,
        };
    }

    function sources() {
        const lastBySource = new Map();
        for (const entry of state.samples) {
            const current = lastBySource.get(entry.source);
            const capturedAt = entry.capturedAt || "";
            if (!current || capturedAt > (current.lastCaptureAt || "")) {
                lastBySource.set(entry.source, {
                    lastCaptureAt: entry.capturedAt || null,
                    lastDate: entry.date,
                });
            }
        }
        const ids = new Set([...Object.keys(SOURCE_LABELS), ...lastBySource.keys()]);
        const list = [...ids]
            .sort((a, b) => a.localeCompare(b))
            .map((id) => ({
                id,
                label: SOURCE_LABELS[id] || id,
                lastCaptureAt: lastBySource.get(id)?.lastCaptureAt || null,
                lastDate: lastBySource.get(id)?.lastDate || null,
            }));
        return {
            sources: list,
            installedApps: state.installedApps.map((entry) => ({ ...entry })),
            tzOffsetMinutes: tzOffset(),
        };
    }

    function installedApps() {
        return state.installedApps.map((entry) => ({ ...entry }));
    }

    function sampleCount() {
        return state.samples.length;
    }

    return {
        recordSamples,
        summary,
        sources,
        installedApps,
        sampleCount,
        tzOffsetMinutes: tzOffset,
        todayKey,
    };
}
