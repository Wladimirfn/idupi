// ============================================================================
// idupi-server/lib/orchestrator/engines/opencode.mjs
//
// OpenCode adapter — reads/writes ~/.config/opencode/opencode.json → op.agent[phase].model
// plus .variant for effort. Phase names are identity (OpenCode IS the canonical
// namespace). Preserves all non-agent keys on read-merge-write.
// ============================================================================

import { readFileSync, writeFileSync, existsSync, copyFileSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { homedir } from "node:os";

export const engineId = "opencode";

export const nativeTargets = Object.freeze([
    join(homedir(), ".config", "opencode", "opencode.json"),
]);

const backedUp = new Set();

function opencodeConfigPath() {
    return join(homedir(), ".config", "opencode", "opencode.json");
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
 * Read OpenCode inline agent models into canonical bindings.
 *
 * @returns {Promise<{
 *   bindings: Record<string, { providerId?: string, modelId?: string, effort?: string }>,
 *   skipped: string[],
 * }>}
 */
export async function readEngine() {
    const doc = readDoc(opencodeConfigPath());
    const agent = (doc && doc.agent && typeof doc.agent === "object") ? doc.agent : {};
    const bindings = {};
    for (const [phase, val] of Object.entries(agent)) {
        if (!val || typeof val !== "object") continue;
        const rawModel = typeof val.model === "string" ? val.model : "";
        if (!rawModel) continue;
        const parts = rawModel.split("/");
        const providerId = parts.length > 1 ? parts[0] : "opencode-go";
        const modelId = parts.length > 1 ? parts.slice(1).join("/") : rawModel;
        const binding = { providerId, modelId };
        if (typeof val.variant === "string" && val.variant.length > 0) {
            binding.effort = val.variant;
        }
        bindings[phase] = binding;
    }
    return { bindings, skipped: [] };
}

/**
 * Write one OpenCode binding for a canonical phase. Read-merge-write preserves
 * every other key in op.agent and every key outside op.*.
 *
 * @param {string} canonicalPhase
 * @param {{ providerId?: string, modelId?: string, effort?: string }} binding
 * @returns {Promise<{ ok: boolean, error?: string, skipped: string[] }>}
 */
export async function writeEngine(canonicalPhase, binding) {
    try {
        const filePath = opencodeConfigPath();
        const doc = readDoc(filePath);
        if (!doc.agent || typeof doc.agent !== "object") doc.agent = {};
        if (!doc.agent[canonicalPhase] || typeof doc.agent[canonicalPhase] !== "object") {
            doc.agent[canonicalPhase] = {};
        }
        const fullModel = binding.providerId
            ? `${binding.providerId}/${binding.modelId ?? ""}`
            : (binding.modelId ?? "");
        doc.agent[canonicalPhase].model = fullModel;
        if (binding.effort) doc.agent[canonicalPhase].variant = binding.effort;
        else delete doc.agent[canonicalPhase].variant;
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
    opencodeConfigPath,
});