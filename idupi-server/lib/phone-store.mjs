// Phone agent store (phone-agent, T1 + phone-approvals, T1).
//
// Two different things live here:
//
//   - The status snapshot the phone reports: the latest one plus a sampled
//     24 h history, persisted in idupi-server/data/phone-status.json
//     (gitignored). Writes are atomic (temp file + rename), same as
//     inbox-store.mjs.
//   - The PC-to-phone command queue: in memory only, because a command is
//     worthless after 60 s and a server restart must never replay a stale tap.
//
// Risk gate (phone-approvals): every command is classified before it enters the
// queue. A gated command parks as `awaiting_approval` and is never delivered
// until the owner approves it on the phone. Approval persistence lives in
// phone-approval-store.mjs; this store owns the command side of the
// transition, so a rejection or an expiry always fails the command instead of
// letting it run.
//
// The store is synchronous on purpose: payloads are small and the route
// handlers call it between awaits, so an async API would only add interleaving
// bugs. Subscribers are notified on every queue change so the phone long-poll
// and the PC result wait can answer immediately without polling.

import { randomBytes } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { RISK_REASONS, classifyCommand } from "./phone-risk.mjs";

// Status body: one snapshot is a few hundred bytes; 64 KiB covers every field
// without letting a client buffer an arbitrary payload.
export const DEFAULT_MAX_STATUS_BYTES = 64 * 1024;
export const DEFAULT_MAX_RESULT_BYTES = 256 * 1024;

// History: at most one sample every 5 minutes, the last 24 h, 300 entries.
export const DEFAULT_HISTORY_INTERVAL_MS = 5 * 60 * 1000;
export const DEFAULT_HISTORY_TTL_MS = 24 * 60 * 60 * 1000;
export const DEFAULT_HISTORY_LIMIT = 300;

// A pending command that nobody picked up in 60 s must never run later.
export const DEFAULT_COMMAND_TTL_MS = 60 * 1000;
export const DEFAULT_MAX_FINISHED_COMMANDS = 100;

// "online" means the phone talked to the server (status or poll) recently.
export const DEFAULT_ONLINE_WINDOW_MS = 2 * 60 * 1000;

export const DEFAULT_COMMAND_LIST_LIMIT = 20;
export const MAX_COMMAND_LIST_LIMIT = 100;

export const COMMAND_ID_PATTERN = /^phc-[0-9a-f]{12}$/;
export const COMMAND_TYPES = [
    "screen",
    "tap",
    "long_press",
    "type",
    "scroll",
    "global",
    "open",
    "wait",
    "status",
];
export const COMMAND_STATES = [
    "pending",
    "awaiting_approval",
    "delivered",
    "done",
    "failed",
    "expired",
];
export const SCROLL_DIRECTIONS = ["up", "down", "left", "right"];
export const GLOBAL_ACTIONS = ["back", "home", "recents", "notifications", "quick_settings"];

// Error codes the approval gate writes on a command. APPROVAL_REQUIRED travels
// the other way: the phone sends it as the error of a refused execution.
export const APPROVAL_REJECTED_CODE = "APPROVAL_REJECTED";
export const APPROVAL_EXPIRED_CODE = "APPROVAL_EXPIRED";
export const APPROVAL_REQUIRED_CODE = "APPROVAL_REQUIRED";

const STATUS_FILE = "phone-status.json";
const MAX_TEXT_CHARS = 4000;
const MAX_TARGET_TEXT_CHARS = 500;
const MAX_TARGET_ID_CHARS = 200;
const MAX_APP_CHARS = 200;
const MAX_COMMAND_ERROR_CHARS = 300;
const MAX_COORDINATE = 100_000;
const MAX_WAIT_MS = 10_000;

function typedError(code, message) {
    const err = new Error(message);
    err.code = code;
    return err;
}

function isPlainObject(value) {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function clone(value) {
    return value && typeof value === "object" ? { ...value } : value;
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

function clip(value, maxChars) {
    const text = typeof value === "string" ? value : String(value ?? "");
    return text.length > maxChars ? text.slice(0, maxChars) : text;
}

function serializedBytes(value) {
    try {
        return Buffer.byteLength(JSON.stringify(value), "utf8");
    } catch {
        return Number.POSITIVE_INFINITY;
    }
}

function requireString(value, field, maxChars) {
    if (typeof value !== "string") {
        throw typedError("INVALID_COMMAND", `${field} must be a string`);
    }
    const trimmed = value.trim();
    if (trimmed.length === 0) {
        throw typedError("INVALID_COMMAND", `${field} is required`);
    }
    if (value.length > maxChars) {
        throw typedError("INVALID_COMMAND", `${field} exceeds ${maxChars} characters`);
    }
    return value;
}

function requireCoordinate(value, field) {
    if (!Number.isInteger(value) || value < 0 || value > MAX_COORDINATE) {
        throw typedError("INVALID_COMMAND", `${field} must be an integer between 0 and ${MAX_COORDINATE}`);
    }
    return value;
}

function rejectUnknownArgs(args, allowed) {
    for (const key of Object.keys(args)) {
        if (!allowed.includes(key)) {
            throw typedError("INVALID_COMMAND", `unknown arg for this command: ${key}`);
        }
    }
}

/**
 * Validates the target of tap / long_press: exactly one of text, id or x+y.
 */
function normalizeTarget(args) {
    rejectUnknownArgs(args, ["text", "id", "x", "y"]);
    const hasText = args.text !== undefined;
    const hasId = args.id !== undefined;
    const hasX = args.x !== undefined;
    const hasY = args.y !== undefined;
    const forms = [hasText, hasId, hasX || hasY].filter(Boolean).length;
    if (forms !== 1) {
        throw typedError(
            "INVALID_COMMAND",
            "target must be exactly one of: text, id, or x + y",
        );
    }
    if (hasText) {
        return { text: requireString(args.text, "text", MAX_TARGET_TEXT_CHARS) };
    }
    if (hasId) {
        return { id: requireString(args.id, "id", MAX_TARGET_ID_CHARS) };
    }
    if (!hasX || !hasY) {
        throw typedError("INVALID_COMMAND", "x and y must be sent together");
    }
    return {
        x: requireCoordinate(args.x, "x"),
        y: requireCoordinate(args.y, "y"),
    };
}

/** Validates one command type and returns the normalized args. */
export function normalizeCommandArgs(type, rawArgs) {
    if (!COMMAND_TYPES.includes(type)) {
        throw typedError("INVALID_COMMAND", "type must be one of: " + COMMAND_TYPES.join(", "));
    }
    const args = rawArgs === undefined || rawArgs === null ? {} : rawArgs;
    if (!isPlainObject(args)) {
        throw typedError("INVALID_COMMAND", "args must be a JSON object");
    }

    switch (type) {
        case "screen":
        case "status":
            rejectUnknownArgs(args, []);
            return {};
        case "tap":
        case "long_press":
            return normalizeTarget(args);
        case "type": {
            rejectUnknownArgs(args, ["text", "submit"]);
            const text = requireString(args.text, "text", MAX_TEXT_CHARS);
            if (args.submit !== undefined && typeof args.submit !== "boolean") {
                throw typedError("INVALID_COMMAND", "submit must be a boolean");
            }
            const normalized = { text };
            if (args.submit === true) normalized.submit = true;
            return normalized;
        }
        case "scroll":
            rejectUnknownArgs(args, ["direction"]);
            if (!SCROLL_DIRECTIONS.includes(args.direction)) {
                throw typedError(
                    "INVALID_COMMAND",
                    "direction must be one of: " + SCROLL_DIRECTIONS.join(", "),
                );
            }
            return { direction: args.direction };
        case "global":
            rejectUnknownArgs(args, ["action"]);
            if (!GLOBAL_ACTIONS.includes(args.action)) {
                throw typedError(
                    "INVALID_COMMAND",
                    "action must be one of: " + GLOBAL_ACTIONS.join(", "),
                );
            }
            return { action: args.action };
        case "open":
            rejectUnknownArgs(args, ["app"]);
            return { app: requireString(args.app, "app", MAX_APP_CHARS) };
        case "wait":
            rejectUnknownArgs(args, ["ms"]);
            if (!Number.isInteger(args.ms) || args.ms < 0 || args.ms > MAX_WAIT_MS) {
                throw typedError("INVALID_COMMAND", `ms must be an integer between 0 and ${MAX_WAIT_MS}`);
            }
            return { ms: args.ms };
        default:
            throw typedError("INVALID_COMMAND", "unknown command type");
    }
}

/**
 * @param {{
 *   dataDir: string,
 *   now?: (() => Date | number | string) | Date | number | string,
 *   maxStatusBytes?: number,
 *   maxResultBytes?: number,
 *   historyIntervalMs?: number,
 *   historyTtlMs?: number,
 *   historyLimit?: number,
 *   commandTtlMs?: number,
 *   maxFinished?: number,
 *   onlineWindowMs?: number,
 *   approvalStore?: ReturnType<import("./phone-approval-store.mjs").createPhoneApprovalStore> | null,
 *   bypassApprovals?: boolean,
 *   log?: { warn?: Function, info?: Function },
 * }} options
 */
export function createPhoneStore({
    dataDir,
    now = () => new Date(),
    maxStatusBytes = DEFAULT_MAX_STATUS_BYTES,
    maxResultBytes = DEFAULT_MAX_RESULT_BYTES,
    historyIntervalMs = DEFAULT_HISTORY_INTERVAL_MS,
    historyTtlMs = DEFAULT_HISTORY_TTL_MS,
    historyLimit = DEFAULT_HISTORY_LIMIT,
    commandTtlMs = DEFAULT_COMMAND_TTL_MS,
    maxFinished = DEFAULT_MAX_FINISHED_COMMANDS,
    onlineWindowMs = DEFAULT_ONLINE_WINDOW_MS,
    approvalStore = null,
    bypassApprovals = false,
    log = console,
} = {}) {
    if (typeof dataDir !== "string" || dataDir.length === 0) {
        throw new Error("dataDir is required");
    }

    // Tests may bypass the gate explicitly. There is no runtime auto-approve:
    // index.mjs always wires an approval store and never sets this option.
    const approvalsEnabled = Boolean(approvalStore) && bypassApprovals !== true;

    const statusFile = join(dataDir, STATUS_FILE);

    /** @type {object | null} the phone snapshot plus server-side receivedAt */
    let latest = null;
    /** @type {object[]} oldest first, sampled */
    let history = [];
    /** ISO timestamp of the last status or command poll, or null */
    let lastSeenAt = null;

    /** @type {object[]} insertion order: oldest first */
    const commands = [];

    let tempCounter = 0;

    const listeners = new Set();

    function nowIso() {
        return toIso(typeof now === "function" ? now() : now, new Date().toISOString());
    }

    function nowMs() {
        return Date.parse(nowIso());
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

    function persistStatus() {
        atomicWrite(
            statusFile,
            JSON.stringify({ version: 1, latest, history }, null, 2) + "\n",
        );
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

    function isValidSample(candidate) {
        if (!isPlainObject(candidate)) return false;
        if (!Number.isFinite(Date.parse(String(candidate.receivedAt ?? "")))) return false;
        return serializedBytes(candidate) <= maxStatusBytes;
    }

    function pruneHistory(referenceMs) {
        const cutoff = referenceMs - historyTtlMs;
        while (history.length > 0 && Date.parse(history[0].receivedAt) < cutoff) {
            history.shift();
        }
        while (history.length > historyLimit) {
            history.shift();
        }
    }

    function load() {
        const parsed = readJsonFile(statusFile);
        if (!parsed) return;

        if (isValidSample(parsed.latest)) {
            latest = parsed.latest;
        }
        if (Array.isArray(parsed.history)) {
            history = parsed.history.filter(isValidSample);
        }
        pruneHistory(nowMs());
        // A snapshot received a minute ago still means the phone is online;
        // without this a restart would report "offline" until the next report.
        if (latest) lastSeenAt = latest.receivedAt;
    }

    function notify() {
        for (const listener of [...listeners]) {
            try {
                listener();
            } catch {
                // One broken waiter must never break the queue for the others.
            }
        }
    }

    /**
     * Registers a callback fired after every queue change. Returns an
     * unsubscribe function; safe to call more than once.
     */
    function subscribe(listener) {
        if (typeof listener !== "function") return () => {};
        listeners.add(listener);
        return () => {
            listeners.delete(listener);
        };
    }

    /**
     * Stores the phone status snapshot as-is plus a server-side receivedAt.
     * The latest entry is always replaced; history is sampled.
     */
    function recordStatus(snapshot) {
        if (!isPlainObject(snapshot)) {
            throw typedError("INVALID_STATUS", "status must be a JSON object");
        }
        if (serializedBytes(snapshot) > maxStatusBytes) {
            throw typedError("TOO_LARGE", `status exceeds ${maxStatusBytes} bytes`);
        }

        const receivedAt = nowIso();
        const sample = { ...snapshot, receivedAt };
        if (serializedBytes(sample) > maxStatusBytes) {
            throw typedError("TOO_LARGE", `status exceeds ${maxStatusBytes} bytes`);
        }
        latest = sample;
        lastSeenAt = receivedAt;

        const last = history[history.length - 1];
        const lastMs = last ? Date.parse(last.receivedAt) : Number.NaN;
        if (!Number.isFinite(lastMs) || Date.parse(receivedAt) - lastMs >= historyIntervalMs) {
            history.push(sample);
        }
        pruneHistory(Date.parse(receivedAt));

        persistStatus();
        return clone(sample);
    }

    /** Marks the phone as seen by a command poll, without touching history. */
    function markSeen() {
        lastSeenAt = nowIso();
    }

    function getStatusInfo() {
        const reference = nowMs();
        const seenMs = lastSeenAt ? Date.parse(lastSeenAt) : Number.NaN;
        const online = Number.isFinite(seenMs) && reference - seenMs <= onlineWindowMs;
        return {
            latest: latest ? clone(latest) : null,
            history: history.map(clone),
            online,
            lastSeenAt,
        };
    }

    /** Foreground app from the latest phone snapshot, or null. */
    function latestForegroundApp() {
        const candidate = latest && typeof latest === "object" ? latest.foregroundApp : null;
        if (!candidate || typeof candidate !== "object") return null;
        const packageName = typeof candidate.packageName === "string" ? candidate.packageName : "";
        const label = typeof candidate.label === "string" ? candidate.label : "";
        if (!packageName && !label) return null;
        return { packageName, label };
    }

    /** Argument snapshot the approval needs to describe the gated action. */
    function approvalArgs(command) {
        return {
            type: command.type,
            args: { ...command.args },
        };
    }

    /**
     * Parks one command as `awaiting_approval` and creates its persisted
     * approval. Does not notify; the caller owns that after the transition.
     */
    function beginApproval(command, { reason, target = null, requestedBy = "cli" } = {}) {
        const approval = approvalStore.createApproval({
            command: approvalArgs(command),
            reason,
            target,
            foregroundApp: latestForegroundApp(),
            requestedBy,
            commandId: command.id,
        });
        command.state = "awaiting_approval";
        command.deliveredAt = null;
        command.expiresAt = null;
        command.finishedAt = null;
        command.error = null;
        command.approvalId = approval.id;
        command.approvalExpiresAt = approval.expiresAt;
        command.approvalReason = approval.reason;
        command.approvalTarget = approval.target;
        return approval;
    }

    /** Adds the fields the phone verifies against an approved action. */
    function applyApproval(command, approval) {
        command.args = {
            ...command.args,
            approvalId: approval.id,
            approvedTarget: approval.target ?? null,
        };
        command.approvalId = approval.id;
        command.approvalExpiresAt = approval.expiresAt;
        command.approvalReason = approval.reason;
        command.approvalTarget = approval.target;
    }

    /** Rebuilds a queued command when the server restarted while waiting. */
    function rebuildCommandFromApproval(approval) {
        const createdAt = nowIso();
        const command = {
            id: `phc-${randomBytes(6).toString("hex")}`,
            type: approval.command.type,
            args: { ...approval.command.args },
            state: "pending",
            createdAt,
            expiresAt: new Date(Date.parse(createdAt) + commandTtlMs).toISOString(),
            deliveredAt: null,
            finishedAt: null,
            result: null,
            error: null,
        };
        applyApproval(command, approval);
        commands.push(command);
        pruneFinished();
        return command;
    }

    /** Requeues the in-memory command after an approval with a fresh TTL. */
    function requeueCommand(command, approval) {
        command.state = "pending";
        command.deliveredAt = null;
        command.finishedAt = null;
        command.result = null;
        command.error = null;
        command.expiresAt = new Date(nowMs() + commandTtlMs).toISOString();
        applyApproval(command, approval);
    }

    /** Expires approvals and fails their commands. Expiry never approves. */
    function syncExpiredApprovals() {
        if (!approvalStore) return false;
        const expired = approvalStore.expireStale();
        if (!Array.isArray(expired) || expired.length === 0) return false;
        let changed = false;
        for (const approval of expired) {
            const command = commands.find((candidate) => (
                candidate.id === approval.commandId || candidate.approvalId === approval.id
            ));
            if (!command || command.state !== "awaiting_approval") continue;
            command.state = "failed";
            command.error = APPROVAL_EXPIRED_CODE;
            command.result = null;
            command.finishedAt = nowIso();
            changed = true;
        }
        if (changed) notify();
        return changed;
    }

    /** Pending commands past their deadline become expired; nothing else does. */
    function expireStale(referenceMs = nowMs()) {
        syncExpiredApprovals();
        let changed = false;
        for (const command of commands) {
            if (command.state !== "pending") continue;
            if (Date.parse(command.expiresAt) > referenceMs) continue;
            command.state = "expired";
            changed = true;
        }
        if (changed) notify();
        return changed;
    }

    function pruneFinished() {
        let finished = commands.filter((command) => (
            command.state === "done" ||
            command.state === "failed" ||
            command.state === "expired"
        ));
        if (finished.length <= maxFinished) return;
        const excess = finished.length - maxFinished;
        const drop = new Set(finished.slice(0, excess));
        for (let index = commands.length - 1; index >= 0; index -= 1) {
            if (drop.has(commands[index])) commands.splice(index, 1);
        }
    }

    function createCommand({ type, args, requestedBy = "cli" } = {}) {
        if (typeof type !== "string" || type.length === 0) {
            throw typedError("INVALID_COMMAND", "type is required");
        }
        if (typeof requestedBy !== "string") {
            throw typedError("INVALID_COMMAND", "requestedBy must be a string");
        }
        const normalizedArgs = normalizeCommandArgs(type, args);
        const requester = requestedBy.trim().length > 0 ? requestedBy.trim() : "cli";
        const foreground = latestForegroundApp();
        const verdict = approvalsEnabled
            ? classifyCommand(
                { type, args: normalizedArgs },
                { foregroundPackage: foreground ? foreground.packageName : null },
            )
            : { gated: false, reason: "free" };

        const createdAt = nowIso();
        const command = {
            id: `phc-${randomBytes(6).toString("hex")}`,
            type,
            args: normalizedArgs,
            state: "pending",
            createdAt,
            expiresAt: new Date(Date.parse(createdAt) + commandTtlMs).toISOString(),
            deliveredAt: null,
            finishedAt: null,
            result: null,
            error: null,
        };
        commands.push(command);
        if (verdict.gated) {
            beginApproval(command, {
                reason: verdict.reason,
                target: verdict.target ?? null,
                requestedBy: requester,
            });
        }
        pruneFinished();
        notify();
        return clone(command);
    }

    function getCommand(id) {
        expireStale();
        const command = commands.find((candidate) => candidate.id === id);
        return command ? clone(command) : null;
    }

    /** Newest first. */
    function listCommands({ limit = DEFAULT_COMMAND_LIST_LIMIT } = {}) {
        expireStale();
        const safeLimit = Number.isInteger(limit) && limit > 0
            ? Math.min(limit, MAX_COMMAND_LIST_LIMIT)
            : DEFAULT_COMMAND_LIST_LIMIT;
        return commands
            .map((command, index) => ({ command, index }))
            .sort((a, b) => {
                const delta = Date.parse(b.command.createdAt) - Date.parse(a.command.createdAt);
                return delta !== 0 ? delta : b.index - a.index;
            })
            .slice(0, safeLimit)
            .map(({ command }) => clone(command));
    }

    /**
     * Delivers the oldest pending command: returns it marked as delivered, or
     * null when nothing is waiting. Expired commands are never delivered.
     */
    function takeNext() {
        expireStale();
        const command = commands.find((candidate) => candidate.state === "pending");
        if (!command) {
            markSeen();
            return null;
        }
        command.state = "delivered";
        command.deliveredAt = nowIso();
        markSeen();
        notify();
        return clone(command);
    }

    /**
     * Records the phone's result for one command.
     *
     * @returns {{ ok: true, command: object } | { ok: false, reason: "not_found" | "finished" | "expired" }}
     */
    function finishCommand(id, { ok, result = null, error = null } = {}) {
        expireStale();
        const command = commands.find((candidate) => candidate.id === id);
        if (!command) return { ok: false, reason: "not_found" };
        if (command.state === "done" || command.state === "failed") {
            return { ok: false, reason: "finished" };
        }
        if (command.state === "expired") return { ok: false, reason: "expired" };
        if (typeof ok !== "boolean") {
            throw typedError("INVALID_RESULT", "ok must be a boolean");
        }

        let storedResult = null;
        if (result !== undefined && result !== null) {
            if (serializedBytes(result) > maxResultBytes) {
                throw typedError("TOO_LARGE", `result exceeds ${maxResultBytes} bytes`);
            }
            storedResult = result;
        }

        // The phone refused to tap a node it judged risky. Convert the refusal
        // into an approval for the same command instead of failing it: the
        // owner still decides, and the node text becomes the approved target.
        if (
            ok === false && approvalsEnabled && typeof error === "string" &&
            error.trim().toUpperCase() === APPROVAL_REQUIRED_CODE
        ) {
            if (command.state === "awaiting_approval") {
                return { ok: true, command: clone(command) };
            }
            const nodeText = storedResult && typeof storedResult === "object" &&
                typeof storedResult.nodeText === "string"
                ? clip(storedResult.nodeText.trim(), MAX_TARGET_TEXT_CHARS)
                : "";
            const fallbackTarget = command.args && typeof command.args === "object"
                ? (
                    typeof command.args.text === "string" ? command.args.text
                        : typeof command.args.id === "string" ? command.args.id
                            : Number.isInteger(command.args.x) && Number.isInteger(command.args.y)
                                ? `${command.args.x},${command.args.y}`
                                : null
                )
                : null;
            command.result = storedResult;
            beginApproval(command, {
                reason: RISK_REASONS.phoneDetected,
                target: nodeText.length > 0 ? nodeText : fallbackTarget,
                requestedBy: "phone",
            });
            notify();
            return { ok: true, command: clone(command) };
        }

        let storedError = null;
        if (ok === false) {
            storedError = clip(
                typeof error === "string" && error.trim() ? error.trim() : "command failed",
                MAX_COMMAND_ERROR_CHARS,
            );
        } else if (error !== undefined && error !== null) {
            if (typeof error !== "string") {
                throw typedError("INVALID_RESULT", "error must be a string");
            }
        }

        command.state = ok ? "done" : "failed";
        command.result = storedResult;
        command.error = storedError;
        command.finishedAt = nowIso();
        pruneFinished();
        notify();
        return { ok: true, command: clone(command) };
    }

    /**
     * Applies the owner's decision to both the approval and the command.
     * Approve requeues the command (or rebuilds it after a restart); reject
     * fails it with APPROVAL_REJECTED. Expiry always wins.
     */
    function decideApproval(id, decision) {
        if (!approvalStore) return { ok: false, reason: "not_found" };
        syncExpiredApprovals();
        const outcome = approvalStore.decideApproval(id, decision);
        if (!outcome.ok) return outcome;

        const approval = outcome.approval;
        let command = commands.find((candidate) => (
            candidate.id === approval.commandId || candidate.approvalId === approval.id
        )) ?? null;

        if (approval.state === "approved") {
            if (!command) {
                command = rebuildCommandFromApproval(approval);
            } else {
                requeueCommand(command, approval);
            }
            approvalStore.attachResultCommand(approval.id, command.id);
        } else if (command && command.state === "awaiting_approval") {
            command.state = "failed";
            command.error = APPROVAL_REJECTED_CODE;
            command.result = null;
            command.finishedAt = nowIso();
        }

        notify();
        return {
            ok: true,
            approval: approvalStore.getApproval(approval.id) ?? approval,
            command: command ? clone(command) : null,
        };
    }

    function listApprovals(options = {}) {
        if (!approvalStore) return [];
        expireStale();
        return approvalStore.listApprovals(options);
    }

    function getApproval(id) {
        if (!approvalStore) return null;
        expireStale();
        return approvalStore.getApproval(id);
    }

    load();

    return {
        recordStatus,
        getStatusInfo,
        markSeen,
        createCommand,
        getCommand,
        listCommands,
        takeNext,
        finishCommand,
        subscribe,
        decideApproval,
        listApprovals,
        getApproval,
    };
}
