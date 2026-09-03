// ============================================================================
// lib/pi-models.mjs — pure catalog merge for the Pi CLI model list
//
// Pi keeps its model catalog split across two files under ~/.pi/agent/:
//   - models-store.json  — every provider the agent has talked to
//                          ({ providerKey: { models: [...] } })
//   - models.json        — locally configured providers, e.g. self-hosted
//                          models (local/ornith, local/qwen38) under
//                          { providers: { providerKey: { models: [...] } } }
//
// The server merges both into ONE catalog. Store entries win on conflicts,
// and the merge is deduped by provider/model so the same model never appears
// twice regardless of which file (or both) lists it.
//
// Pure function: no I/O, no logging, no side effects — easy to test without
// touching $HOME.
// ============================================================================

/**
 * Merge two normalized model lists into one deduped catalog.
 *
 * @param {Array<{id: string, name?: string, provider?: string}>} storeModels
 *        entries from models-store.json — kept first on conflicts.
 * @param {Array<{id: string, name?: string, provider?: string}>} localModels
 *        entries from models.json — only added when provider/model is new.
 * @returns {Array<{id: string, name: string, provider: string}>}
 */
export function mergePiModelCatalogs(storeModels, localModels) {
    const seen = new Set();
    const merged = [];
    for (const list of [storeModels ?? [], localModels ?? []]) {
        for (const m of list) {
            if (!m || typeof m.id !== "string" || m.id.length === 0) continue;
            const provider = typeof m.provider === "string" && m.provider.length > 0 ? m.provider : "pi-cli";
            const key = `${provider}/${m.id}`;
            if (seen.has(key)) continue;
            seen.add(key);
            merged.push({
                id: m.id,
                name: typeof m.name === "string" && m.name.length > 0 ? m.name : m.id,
                provider,
            });
        }
    }
    return merged;
}