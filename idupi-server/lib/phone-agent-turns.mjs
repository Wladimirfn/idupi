// Phone bubble turn store (phone-bubble, T1).
//
// One record per owner request typed or dictated into the bubble. The file
// lives in idupi-server/data/ (gitignored) and writes atomically, same as the
// phone approval store. Only the last 20 turns are kept: a turn exists so the
// phone can reconnect to its answer, not as a history feature.
//
// The store is synchronous on purpose (small payloads, called between awaits)
// and notifies subscribers on every change so the route's long poll can answer
// the moment a turn moves instead of polling the file.
//
// A turn never resumes after a restart: its agent process died with the old
// server, so the loader marks every queued/running record as failed instead of
// lying about a job that is not running.

import { randomBytes } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const TURN_ID_PATTERN = /^pat-[0-9a-f]{12}$/;
export const TURN_STATUSES = ["queued", "running", "done", "failed", "cancelled"];
export const TURN_TERMINAL_STATUSES = ["done", "failed", "cancelled"];

export const DEFAULT_MAX_TURNS = 20;
export const DEFAULT_TURN_LIST_LIMIT = 20;
export const MAX_TURN_LIST_LIMIT = 20;

// Owner text is one bubble message, not a document.
export const MAX_TURN_TEXT_CHARS = 2000;
// The prompt asks the agent for at most 600 chars; the store keeps a wider
// margin so a slightly long answer is still shown instead of rejected.
export const MAX_TURN_ANSWER_CHARS = 1200;
export const MAX_TURN_STEPS = 40;
export const MAX_STEP_SUMMARY_CHARS = 200;
export const MAX_TURN_ERROR_CHARS = 300;
export const MAX_TURN_AGENT_CHARS = 60;

const TURNS_FILE = "phone-agent-turns.json";
const MAX_STEP_KIND_CHARS = 40;
const RESTART_ERROR = "El servidor se reinició antes de terminar el pedido.";

function typedError(code, message) {
    const err = new Error(message);
    err.code = code;
    return err;
}

function isPlainObject(value) {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function clip(value, maxChars) {
    const text = typeof value === "string" ? value : String(value ?? "");
    return text.length > maxChars ? text.slice(0, maxChars) : text;
}

function toIso(value, fallback) {
    if (value instanceof Date && !Number.isNaN(value.getTime())) return value.toISOString();
    if (typeof value === "number") {
        const fromNumber = new Date(value);
        if (!Number.isNaN(fromNumber.getTime())) return fromNumber.toISOString();
    }
    if (typeof value === "string") {
        const fromString = new Date(value);
        if (!Number.isNaN(fromString.getTime())) return fromString.toISOString();
    }
    return fallback;
}

function cloneTurn(turn) {
    if (!turn || typeof turn !== "object") return turn;
    return {
        ...turn,
        steps: Array.isArray(turn.steps) ? turn.steps.map((step) => ({ ...step })) : [],
    };
}

function normalizeStep(raw, fallbackAt) {
    if (!isPlainObject(raw)) return null;
    const summary = clip(raw.summary, MAX_STEP_SUMMARY_CHARS).trim();
    if (summary.length === 0) return null;
    return {
        at: toIso(raw.at, fallbackAt),
        kind: clip(raw.kind, MAX_STEP_KIND_CHARS).trim() || "step",
        summary,
    };
}

function requireOwnerText(raw) {
    if (typeof raw !== "string") {
        throw typedError("INVALID_TEXT", "text must be a string");
    }
    const text = raw.trim();
    if (text.length === 0) {
        throw typedError("INVALID_TEXT", "text is required");
    }
    if (text.length > MAX_TURN_TEXT_CHARS) {
        throw typedError(
            "INVALID_TEXT",
            `text exceeds ${MAX_TURN_TEXT_CHARS} characters`,
        );
    }
    return text;
}

/**
 * @param {{
 *   dataDir: string,
 *   now?: (() => Date | number | string) | Date | number | string,
 *   maxTurns?: number,
 *   log?: { warn?: Function, info?: Function },
 * }} options
 */
export function createPhoneAgentTurnStore({
    dataDir,
    now = () => new Date(),
    maxTurns = DEFAULT_MAX_TURNS,
    log = console,
} = {}) {
    if (typeof dataDir !== "string" || dataDir.length === 0) {
        throw new Error("dataDir is required");
    }

    const turnsFile = join(dataDir, TURNS_FILE);
    const cap = Number.isInteger(maxTurns) && maxTurns > 0 ? maxTurns : DEFAULT_MAX_TURNS;

    /** @type {object[]} insertion order: oldest first */
    const turns = [];

    let tempCounter = 0;

    const listeners = new Set();

    function nowIso() {
        return toIso(typeof now === "function" ? now() : now, new Date().toISOString());
    }

    function atomicWrite(filePath, payload) {
        mkdirSync(dataDir, { recursive: true });
        tempCounter += 1;
        const tempPath = `${filePath}.${process.pid}-${tempCounter}.tmp`;
        writeFileSync(tempPath, payload, "utf8");
        try {
            renameSync(tempPath, filePath);
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
        atomicWrite(
            turnsFile,
            JSON.stringify({ version: 1, turns }, null, 2) + "\n",
        );
    }

    function notify() {
        for (const listener of [...listeners]) {
            try {
                listener();
            } catch {
                // One broken waiter must never break the others.
            }
        }
    }

    function readJsonFile(filePath) {
        let raw;
        try {
            raw = readFileSync(filePath, "utf8");
        } catch (err) {
            if (err && err.code !== "ENOENT") {
                log?.warn?.(`[phone] no se pudo leer ${filePath}: ${err.message}`);
            }
            return null;
        }
        try {
            return JSON.parse(raw);
        } catch {
            log?.warn?.(`[phone] archivo corrupto, se ignora: ${filePath}`);
            return null;
        }
    }

    function normalizeRecord(raw) {
        if (!isPlainObject(raw)) return null;
        if (typeof raw.id !== "string" || !TURN_ID_PATTERN.test(raw.id)) return null;
        if (typeof raw.text !== "string") return null;
        const text = raw.text.trim();
        if (text.length === 0 || text.length > MAX_TURN_TEXT_CHARS) return null;
        if (!TURN_STATUSES.includes(raw.status)) return null;

        const createdAt = toIso(raw.createdAt, nowIso());
        const steps = Array.isArray(raw.steps)
            ? raw.steps
                .map((step) => normalizeStep(step, createdAt))
                .filter(Boolean)
                .slice(0, MAX_TURN_STEPS)
            : [];
        return {
            id: raw.id,
            text,
            status: raw.status,
            answer: clip(raw.answer, MAX_TURN_ANSWER_CHARS),
            steps,
            error: typeof raw.error === "string" && raw.error.length > 0
                ? clip(raw.error, MAX_TURN_ERROR_CHARS)
                : null,
            createdAt,
            updatedAt: toIso(raw.updatedAt, createdAt),
            agent: clip(raw.agent, MAX_TURN_AGENT_CHARS).trim(),
        };
    }

    function load() {
        const parsed = readJsonFile(turnsFile);
        if (!parsed || !Array.isArray(parsed.turns)) return;

        let migrated = false;
        for (const raw of parsed.turns.slice(-cap)) {
            const turn = normalizeRecord(raw);
            if (!turn) continue;
            // Nothing runs after a restart, so a non-terminal record is a lie.
            if (turn.status === "queued" || turn.status === "running") {
                turn.status = "failed";
                turn.error = RESTART_ERROR;
                turn.updatedAt = nowIso();
                migrated = true;
            }
            turns.push(turn);
        }
        // Re-save only the kept records (and the terminal migration).
        if (migrated || parsed.turns.length !== turns.length) {
            try {
                persist();
            } catch {
                // A read-only data dir still serves the in-memory records.
            }
        }
    }

    function prune() {
        while (turns.length > cap) turns.shift();
    }

    function find(id) {
        return turns.find((turn) => turn.id === id) ?? null;
    }

    function create({ text, agent } = {}) {
        const ownerText = requireOwnerText(text);
        const stamp = nowIso();
        const turn = {
            id: `pat-${randomBytes(6).toString("hex")}`,
            text: ownerText,
            status: "queued",
            answer: "",
            steps: [],
            error: null,
            createdAt: stamp,
            updatedAt: stamp,
            agent: clip(agent, MAX_TURN_AGENT_CHARS).trim(),
        };
        turns.push(turn);
        prune();
        persist();
        notify();
        return cloneTurn(turn);
    }

    function get(id) {
        const turn = find(id);
        return turn ? cloneTurn(turn) : null;
    }

    /** Newest first; `limit` is bounded by the store cap. */
    function list({ limit = DEFAULT_TURN_LIST_LIMIT } = {}) {
        const safeLimit = Number.isInteger(limit) && limit > 0
            ? Math.min(limit, MAX_TURN_LIST_LIMIT)
            : DEFAULT_TURN_LIST_LIMIT;
        return turns
            .map((turn, index) => ({ turn, index }))
            .sort((a, b) => {
                const delta = Date.parse(b.turn.createdAt) - Date.parse(a.turn.createdAt);
                return delta !== 0 ? delta : b.index - a.index;
            })
            .slice(0, safeLimit)
            .map(({ turn }) => cloneTurn(turn));
    }

    /** Newest turn that has not finished, or null. */
    function findActive() {
        for (let index = turns.length - 1; index >= 0; index -= 1) {
            const turn = turns[index];
            if (turn.status === "queued" || turn.status === "running") {
                return cloneTurn(turn);
            }
        }
        return null;
    }

    function update(id, patch = {}) {
        const turn = find(id);
        const next = isPlainObject(patch) ? patch : {};
        if (next.status !== undefined && !TURN_STATUSES.includes(next.status)) {
            throw typedError("INVALID_STATUS", "status must be one of: " + TURN_STATUSES.join(", "));
        }
        if (!turn) return null;

        let changed = false;
        if (next.status !== undefined && next.status !== turn.status) {
            turn.status = next.status;
            changed = true;
        }
        if (next.answer !== undefined) {
            const answer = clip(next.answer, MAX_TURN_ANSWER_CHARS);
            if (answer !== turn.answer) {
                turn.answer = answer;
                changed = true;
            }
        }
        if (next.error !== undefined) {
            const error = typeof next.error === "string" && next.error.length > 0
                ? clip(next.error, MAX_TURN_ERROR_CHARS)
                : null;
            if (error !== turn.error) {
                turn.error = error;
                changed = true;
            }
        }
        if (!changed) return cloneTurn(turn);

        turn.updatedAt = nowIso();
        persist();
        notify();
        return cloneTurn(turn);
    }

    function addStep(id, step = {}) {
        const turn = find(id);
        if (!turn) return null;
        if (turn.steps.length >= MAX_TURN_STEPS) return cloneTurn(turn);
        const normalized = normalizeStep(
            { ...step, at: nowIso() },
            nowIso(),
        );
        if (!normalized) return cloneTurn(turn);

        turn.steps.push(normalized);
        turn.updatedAt = normalized.at;
        persist();
        notify();
        return cloneTurn(turn);
    }

    function cancel(id) {
        const turn = find(id);
        if (!turn) return null;
        if (turn.status !== "queued" && turn.status !== "running") return null;
        turn.status = "cancelled";
        turn.updatedAt = nowIso();
        persist();
        notify();
        return cloneTurn(turn);
    }

    function subscribe(listener) {
        if (typeof listener !== "function") return () => {};
        listeners.add(listener);
        return () => {
            listeners.delete(listener);
        };
    }

    load();

    return {
        create,
        get,
        list,
        findActive,
        update,
        addStep,
        cancel,
        subscribe,
    };
}
