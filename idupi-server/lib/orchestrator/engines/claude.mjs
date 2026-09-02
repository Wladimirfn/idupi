// ============================================================================
// idupi-server/lib/orchestrator/engines/claude.mjs
//
// Claude adapter — reads/writes ~/.gentle-ai/state.json → claude_phase_assignments.
// Phase names are identity (Claude uses canonical namespace). Preserves all
// other keys in state.json on read-merge-write.
// ============================================================================

import { readFileSync, writeFileSync, existsSync, copyFileSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { homedir } from "node:os";

export const engineId = "claude";

export const nativeTargets = Object.freeze([
    join(homedir(), ".gentle-ai", "state.json"),
]);

const backedUp = new Set();

function statePath() {
    return join(homedir(), ".gentle-ai", "state.json");
}

function readDoc(filePath) {
    if (!existsSync(filePath)) return {};
    try {
        const parsed = JSON.parse(readFileSync(filePath, "utf8"));
        return (parsed && typeof parsed === "object") ? parsed : {};
    } catch {
        return {};
    }
}

function backupIfFirstWrite(filePath) {
    if (backedUp.has(filePath)) return;
    if (!existsSync(filePath)) return;
    const bakPath = `${filePath}.bak`;
    if (existsSync(bakPath)) {
        backedUp.add(filePath);
        return;
    }
    try {
        copyFileSync(filePath, bakPath);
    } catch {
        // best-effort
    }
    backedUp.add(filePath);
}

function writeDoc(filePath, doc) {
    backupIfFirstWrite(filePath);
    mkdirSync(dirname(filePath), { recursive: true });
    writeFileSync(filePath, JSON.stringify(doc, null, 2), "utf8");
}

/**
 * Read Claude bindings from claude_phase_assignments.
 *
 * @returns {Promise<{
 *   bindings: Record<string, { modelId?: string }>,
 *   skipped: string[],
 * }>}
 */
export async function readEngine() {
    const doc = readDoc(statePath());
    const cpa = (doc.claude_phase_assignments && typeof doc.claude_phase_assignments === "object")
        ? doc.claude_phase_assignments
        : {};
    const bindings = {};
    for (const [phase, val] of Object.entries(cpa)) {
        if (!val || typeof val !== "object") continue;
        if (typeof val.model === "string" && val.model.length > 0) {
            bindings[phase] = { modelId: val.model };
        }
    }
    return { bindings, skipped: [] };
}

/**
 * Write one Claude binding. Read-merge-write preserves every other key in
 * state.json (including model_assignments, persona, preset, etc.).
 *
 * @param {string} canonicalPhase
 * @param {{ modelId?: string }} binding
 * @returns {Promise<{ ok: boolean, error?: string, skipped: string[] }>}
 */
export async function writeEngine(canonicalPhase, binding) {
    try {
        const filePath = statePath();
        const doc = readDoc(filePath);
        if (!doc.claude_phase_assignments || typeof doc.claude_phase_assignments !== "object") {
            doc.claude_phase_assignments = {};
        }
        doc.claude_phase_assignments[canonicalPhase] = { model: binding.modelId ?? "" };
        writeDoc(filePath, doc);
        return { ok: true, skipped: [] };
    } catch (err) {
        return { ok: false, error: err.message, skipped: [] };
    }
}

export const __testing = Object.freeze({
    readDoc,
    writeDoc,
    backupIfFirstWrite,
    backedUp,
    statePath,
});