// ============================================================================
// idupi-server/lib/orchestrator/engines/pi.mjs
//
// Pi adapter — reads/writes ~/.pi/subagents.json → model_profiles.
// Phase names translated at this boundary:
//   canonical sdd-propose ⇄ Pi-native sdd-proposal
//   canonical sdd-verify  ← Pi-native sdd-status and sdd-sync
//
// Phases with no Pi equivalent (review-risk, review-resilience,
// review-readability, review-reliability) are skipped — never written under
// a guessed name — and reported in the result.skipped[] list.
//
// NOT written: ~/.pi/gentle-ai/models.json (per the spec, Pi's native binding
// surface is subagents.json post the May 2026 migration).
// ============================================================================

import { readFileSync, writeFileSync, existsSync, copyFileSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { homedir } from "node:os";
import {
    canonicalize,
    decanonicalize,
    PI_NO_EQUIVALENT_PHASES,
} from "../contract.mjs";

export const engineId = "pi";

/** Native config files this adapter reads/writes — surfaced for the backup UI. */
export const nativeTargets = Object.freeze([
    join(homedir(), ".pi", "subagents.json"),
]);

/** Per-process "first write" tracking. */
const backedUp = new Set();

/** Default file contents when subagents.json does not exist yet. */
const EMPTY_DOC = Object.freeze({ model_profiles: {} });

function subagentsPath() {
    return join(homedir(), ".pi", "subagents.json");
}

function readDoc(filePath) {
    if (!existsSync(filePath)) return JSON.parse(JSON.stringify(EMPTY_DOC));
    try {
        const parsed = JSON.parse(readFileSync(filePath, "utf8"));
        if (!parsed || typeof parsed !== "object") return JSON.parse(JSON.stringify(EMPTY_DOC));
        if (!parsed.model_profiles || typeof parsed.model_profiles !== "object") {
            parsed.model_profiles = {};
        }
        return parsed;
    } catch {
        return JSON.parse(JSON.stringify(EMPTY_DOC));
    }
}

function backupIfFirstWrite(filePath) {
    if (backedUp.has(filePath)) return;
    if (!existsSync(filePath)) return; // first write creates the file; nothing to back up
    const bakPath = `${filePath}.bak`;
    if (existsSync(bakPath)) {
        backedUp.add(filePath);
        return;
    }
    try {
        copyFileSync(filePath, bakPath);
    } catch {
        // best-effort; backup must not block the write
    }
    backedUp.add(filePath);
}

function writeDoc(filePath, doc) {
    backupIfFirstWrite(filePath);
    mkdirSync(dirname(filePath), { recursive: true });
    writeFileSync(filePath, JSON.stringify(doc, null, 2), "utf8");
}

/**
 * Read Pi bindings, translating Pi-native phase names to canonical.
 *
 * @returns {Promise<{
 *   bindings: Record<string, { providerId?: string, modelId?: string, effort?: string }>,
 *   skipped: string[],
 * }>}
 */
export async function readEngine() {
    const doc = readDoc(subagentsPath());
    const bindings = {};
    const skipped = [];
    for (const [nativeName, raw] of Object.entries(doc.model_profiles ?? {})) {
        if (!raw || typeof raw !== "object") continue;
        const canonicalName = canonicalize(nativeName);
        if (canonicalName !== nativeName && PI_NO_EQUIVALENT_PHASES.includes(canonicalName)) {
            skipped.push(canonicalName);
            continue;
        }
        const binding = {};
        if (typeof raw.provider_id === "string") binding.providerId = raw.provider_id;
        else if (typeof raw.providerId === "string") binding.providerId = raw.providerId;
        if (typeof raw.model_id === "string") binding.modelId = raw.model_id;
        else if (typeof raw.modelId === "string") binding.modelId = raw.modelId;
        if (typeof raw.effort === "string") binding.effort = raw.effort;
        bindings[canonicalName] = binding;
    }
    return { bindings, skipped };
}

/**
 * Write one Pi binding for a canonical phase.
 *
 * @param {string} canonicalPhase
 * @param {{ providerId?: string, modelId?: string, effort?: string }} binding
 * @returns {Promise<{ ok: boolean, error?: string, skipped: string[] }>}
 */
export async function writeEngine(canonicalPhase, binding) {
    const nativeName = decanonicalize(canonicalPhase, "pi");
    if (nativeName === undefined || PI_NO_EQUIVALENT_PHASES.includes(canonicalPhase)) {
        return { ok: true, skipped: [canonicalPhase] };
    }
    try {
        const doc = readDoc(subagentsPath());
        const profile = { model_id: binding.modelId ?? "" };
        if (binding.providerId) profile.provider_id = binding.providerId;
        if (binding.effort) profile.effort = binding.effort;
        doc.model_profiles[nativeName] = profile;
        writeDoc(subagentsPath(), doc);
        return { ok: true, skipped: [] };
    } catch (err) {
        return { ok: false, error: err.message, skipped: [] };
    }
}

/** Surface internals for testing only. */
export const __testing = Object.freeze({
    EMPTY_DOC,
    readDoc,
    writeDoc,
    backupIfFirstWrite,
    backedUp,
    subagentsPath,
});