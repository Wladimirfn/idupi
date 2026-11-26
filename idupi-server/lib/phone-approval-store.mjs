// Phone approval store (phone-approvals, T1).
//
// Every gated phone action waits here for the owner's explicit decision. The
// file lives in idupi-server/data/ (gitignored) and writes atomically, same as
// the calendar proposal store. The command queue itself stays in memory; an
// approval survives a restart because it carries the command it guards, and
// the phone store rebuilds the queued command after an approve.
//
// Two rules are load-bearing:
//
//   - Expiry NEVER approves. An unanswered approval becomes `expired` and the
//     command fails with APPROVAL_EXPIRED, unlike ui-request-registry.
//   - A decided approval never changes again. A second decision answers
//     ALREADY_DECIDED; an expired one answers EXPIRED even for an approve.

import { randomBytes } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const APPROVAL_ID_PATTERN = /^apr-[0-9a-f]{12}$/;
export const APPROVAL_STATES = ["pending", "approved", "rejected", "expired"];
export const APPROVAL_DECISIONS = ["approve", "reject"];
export const DEFAULT_APPROVAL_TTL_MS = 10 * 60 * 1000;
export const DEFAULT_MAX_APPROVALS = 200;
export const DEFAULT_DECIDED_TTL_MS = 24 * 60 * 60 * 1000;
export const DEFAULT_APPROVAL_LIST_LIMIT = 50;
export const MAX_APPROVAL_LIST_LIMIT = 200;

const APPROVALS_FILE = "phone-approvals.json";
const MAX_REASON_CHARS = 80;
const MAX_TARGET_CHARS = 500;
const MAX_REQUESTER_CHARS = 60;
const MAX_COMMAND_ID_CHARS = 80;
const MAX_PACKAGE_CHARS = 200;
const MAX_APP_LABEL_CHARS = 200;

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

function parseInstant(value) {
    if (typeof value !== "string" || value.trim().length === 0) return null;
    const ms = Date.parse(value);
    return Number.isFinite(ms) ? ms : null;
}

function cloneApproval(approval) {
    if (!approval || typeof approval !== "object") return approval;
    return {
        ...approval,
        command: approval.command
            ? { type: approval.command.type, args: { ...approval.command.args } }
            : null,
        foregroundApp: approval.foregroundApp
            ? { ...approval.foregroundApp }
            : null,
    };
}

/**
 * @param {{
 *   dataDir: string,
 *   now?: (() => Date | number | string) | Date | number | string,
 *   approvalTtlMs?: number,
 *   maxApprovals?: number,
 *   decidedTtlMs?: number,
 *   log?: { warn?: Function, info?: Function },
 * }} options
 */
export function createPhoneApprovalStore({
    dataDir,
    now = () => new Date(),
    approvalTtlMs = DEFAULT_APPROVAL_TTL_MS,
    maxApprovals = DEFAULT_MAX_APPROVALS,
    decidedTtlMs = DEFAULT_DECIDED_TTL_MS,
    log = console,
} = {}) {
    if (typeof dataDir !== "string" || dataDir.length === 0) {
        throw new Error("dataDir is required");
    }

    const approvalsFile = join(dataDir, APPROVALS_FILE);
    const ttl = Number.isFinite(approvalTtlMs) && approvalTtlMs > 0
        ? approvalTtlMs
        : DEFAULT_APPROVAL_TTL_MS;
    const cap = Number.isInteger(maxApprovals) && maxApprovals > 0
        ? maxApprovals
        : DEFAULT_MAX_APPROVALS;
    const decidedTtl = Number.isFinite(decidedTtlMs) && decidedTtlMs >= 0
        ? decidedTtlMs
        : DEFAULT_DECIDED_TTL_MS;

    /** @type {object[]} insertion order: oldest first */
    const approvals = [];

    let tempCounter = 0;

    function clockMs() {
        const value = typeof now === "function" ? now() : now;
        const date = value instanceof Date ? value : new Date(value);
        const ms = date.getTime();
        return Number.isFinite(ms) ? ms : Date.now();
    }

    function clockIso() {
        return new Date(clockMs()).toISOString();
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
            approvalsFile,
            JSON.stringify({ version: 1, approvals }, null, 2) + "\n",
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

    function isValidApproval(candidate) {
        if (!isPlainObject(candidate)) return false;
        if (!APPROVAL_ID_PATTERN.test(String(candidate.id ?? ""))) return false;
        if (!APPROVAL_STATES.includes(candidate.state)) return false;
        if (!isPlainObject(candidate.command)) return false;
        if (typeof candidate.command.type !== "string" || candidate.command.type.length === 0) {
            return false;
        }
        if (!isPlainObject(candidate.command.args)) return false;
        if (parseInstant(candidate.createdAt) === null) return false;
        if (parseInstant(candidate.expiresAt) === null) return false;
        return true;
    }

    /** Drops decided entries older than the retention window, then caps size. */
    function prune(referenceMs = clockMs()) {
        const cutoff = referenceMs - decidedTtl;
        let changed = false;
        for (let index = approvals.length - 1; index >= 0; index -= 1) {
            const candidate = approvals[index];
            if (candidate.state === "pending") continue;
            const decidedMs = parseInstant(candidate.decidedAt) ?? parseInstant(candidate.createdAt);
            if (decidedMs !== null && decidedMs < cutoff) {
                approvals.splice(index, 1);
                changed = true;
            }
        }
        while (approvals.length > cap) {
            // Never drop a pending approval while a decided one can go first.
            let victim = approvals.findIndex((candidate) => candidate.state !== "pending");
            if (victim === -1) victim = 0;
            approvals.splice(victim, 1);
            changed = true;
        }
        return changed;
    }

    /** Marks pending approvals past their deadline as expired. Never approves. */
    function expireStale(referenceMs = clockMs()) {
        const nowIso = new Date(referenceMs).toISOString();
        const expired = [];
        for (const approval of approvals) {
            if (approval.state !== "pending") continue;
            const expiresMs = parseInstant(approval.expiresAt);
            if (expiresMs === null || expiresMs > referenceMs) continue;
            approval.state = "expired";
            approval.decidedAt = nowIso;
            expired.push(cloneApproval(approval));
        }
        if (expired.length > 0) persist();
        return expired;
    }

    function createApproval(input) {
        if (!isPlainObject(input)) {
            throw typedError("INVALID_APPROVAL", "body must be a JSON object");
        }
        if (!isPlainObject(input.command)) {
            throw typedError("INVALID_APPROVAL", "command must be a JSON object");
        }
        if (typeof input.command.type !== "string" || input.command.type.length === 0) {
            throw typedError("INVALID_APPROVAL", "command.type is required");
        }
        if (!isPlainObject(input.command.args)) {
            throw typedError("INVALID_APPROVAL", "command.args must be a JSON object");
        }
        if (typeof input.reason !== "string" || input.reason.trim().length === 0) {
            throw typedError("INVALID_APPROVAL", "reason is required");
        }
        if (typeof input.commandId !== "string" || input.commandId.trim().length === 0) {
            throw typedError("INVALID_APPROVAL", "commandId is required");
        }
        if (input.target !== undefined && input.target !== null && typeof input.target !== "string") {
            throw typedError("INVALID_APPROVAL", "target must be a string");
        }
        if (input.requestedBy !== undefined && typeof input.requestedBy !== "string") {
            throw typedError("INVALID_APPROVAL", "requestedBy must be a string");
        }
        if (input.foregroundApp !== undefined && input.foregroundApp !== null &&
            !isPlainObject(input.foregroundApp)) {
            throw typedError("INVALID_APPROVAL", "foregroundApp must be a JSON object");
        }

        const createdAt = clockIso();
        const approval = {
            id: `apr-${randomBytes(6).toString("hex")}`,
            state: "pending",
            command: {
                type: input.command.type,
                args: { ...input.command.args },
            },
            reason: clip(input.reason.trim(), MAX_REASON_CHARS),
            target: typeof input.target === "string" && input.target.length > 0
                ? clip(input.target, MAX_TARGET_CHARS)
                : null,
            foregroundApp: input.foregroundApp
                ? {
                    packageName: clip(input.foregroundApp.packageName ?? "", MAX_PACKAGE_CHARS),
                    label: clip(input.foregroundApp.label ?? "", MAX_APP_LABEL_CHARS),
                }
                : null,
            requestedBy: clip(
                typeof input.requestedBy === "string" && input.requestedBy.trim().length > 0
                    ? input.requestedBy.trim()
                    : "cli",
                MAX_REQUESTER_CHARS,
            ),
            createdAt,
            expiresAt: new Date(Date.parse(createdAt) + ttl).toISOString(),
            decidedAt: null,
            // Present for approvals the server grants on behalf of a running
            // owner bubble turn ("owner-request:<turnId>"); null when the
            // owner decided it on the phone or the PC.
            decidedBy: null,
            commandId: clip(input.commandId.trim(), MAX_COMMAND_ID_CHARS),
            resultCommandId: null,
        };

        prune();
        approvals.push(approval);
        prune();
        persist();
        return cloneApproval(approval);
    }

    function getApproval(id) {
        expireStale();
        const approval = approvals.find((candidate) => candidate.id === id);
        return approval ? cloneApproval(approval) : null;
    }

    /** Newest first. `state` filters one state; "all" returns everything. */
    function listApprovals({ state = "pending", limit = DEFAULT_APPROVAL_LIST_LIMIT } = {}) {
        expireStale();
        if (state !== null && state !== undefined && state !== "all" &&
            !APPROVAL_STATES.includes(state)) {
            throw typedError(
                "INVALID_STATUS",
                "state must be one of: " + APPROVAL_STATES.join(", "),
            );
        }
        const safeLimit = Number.isInteger(limit) && limit > 0
            ? Math.min(limit, MAX_APPROVAL_LIST_LIMIT)
            : DEFAULT_APPROVAL_LIST_LIMIT;
        return approvals
            .map((approval, index) => ({ approval, index }))
            .filter(({ approval }) => (
                state === null || state === undefined || state === "all" ||
                approval.state === state
            ))
            .sort((a, b) => {
                const delta = Date.parse(b.approval.createdAt) - Date.parse(a.approval.createdAt);
                return delta !== 0 ? delta : b.index - a.index;
            })
            .slice(0, safeLimit)
            .map(({ approval }) => cloneApproval(approval));
    }

    /**
     * Records the owner's decision. Expiry always wins: an approval whose TTL
     * elapsed answers `expired` and stays expired even when the decision is
     * "approve".
     *
     * `decidedBy` is server-side provenance for an automatic decision
     * ("owner-request:<turnId>"); the route must never take it from a request
     * body. Manual decisions leave it null.
     */
    function decideApproval(id, decision, { decidedBy = null } = {}) {
        if (!APPROVAL_DECISIONS.includes(decision)) {
            throw typedError("INVALID_DECISION", "decision must be approve or reject");
        }
        expireStale();
        const approval = approvals.find((candidate) => candidate.id === id);
        if (!approval) return { ok: false, reason: "not_found" };
        if (approval.state === "expired") return { ok: false, reason: "expired" };
        if (approval.state !== "pending") return { ok: false, reason: "already_decided" };

        approval.state = decision === "approve" ? "approved" : "rejected";
        approval.decidedAt = clockIso();
        approval.decidedBy = typeof decidedBy === "string" && decidedBy.trim().length > 0
            ? clip(decidedBy.trim(), MAX_REQUESTER_CHARS)
            : null;
        persist();
        return { ok: true, approval: cloneApproval(approval) };
    }

    /** Links the approval to the command id that will actually be delivered. */
    function attachResultCommand(id, resultCommandId) {
        const approval = approvals.find((candidate) => candidate.id === id);
        if (!approval) return null;
        approval.resultCommandId = typeof resultCommandId === "string" && resultCommandId.length > 0
            ? clip(resultCommandId, MAX_COMMAND_ID_CHARS)
            : null;
        persist();
        return cloneApproval(approval);
    }

    function load() {
        const parsed = readJsonFile(approvalsFile);
        if (!parsed) return;
        if (!Array.isArray(parsed.approvals)) {
            log?.warn?.("[phone] archivo sin aprobaciones, se ignora");
            return;
        }
        for (const candidate of parsed.approvals) {
            if (isValidApproval(candidate)) approvals.push(candidate);
        }
        const pruned = prune();
        const expired = expireStale();
        if (pruned && expired.length === 0) persist();
    }

    load();

    return {
        createApproval,
        getApproval,
        listApprovals,
        decideApproval,
        attachResultCommand,
        expireStale,
    };
}
