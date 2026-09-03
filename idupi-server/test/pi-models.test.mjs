// ============================================================================
// idupi-server/test/pi-models.test.mjs
//
// Pins mergePiModelCatalogs — the pure dedup/merge used by getAvailableModels()
// (index.mjs) to combine ~/.pi/agent/models-store.json entries (kept first)
// with ~/.pi/agent/models.json local entries (local/ornith, local/qwen38).
//
// Run (from repo root):
//   node --test idupi-server/test/pi-models.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { mergePiModelCatalogs } from "../lib/pi-models.mjs";

test("store entries win on conflicts; local entries fill the gaps", () => {
    const store = [
        { id: "ornith", name: "Ornith (store)", provider: "local" },
        { id: "MiniMax-M2.7", name: "MiniMax M2.7", provider: "minimax" },
    ];
    const local = [
        { id: "ornith", name: "Ornith-1.5-9B Q6_K (128k)", provider: "local" },
        { id: "qwen38", name: "Qwen3.8-27B IQ2_S (64k)", provider: "local" },
    ];
    const merged = mergePiModelCatalogs(store, local);
    assert.deepEqual(
        merged.map((m) => `${m.provider}/${m.id}`),
        ["local/ornith", "minimax/MiniMax-M2.7", "local/qwen38"],
        "store entries first, deduped by provider/model",
    );
    assert.equal(merged[0].name, "Ornith (store)", "the store entry wins the name on conflict");
    assert.equal(merged[2].name, "Qwen3.8-27B IQ2_S (64k)", "local entry kept verbatim when new");
});

test("dedup is per provider/model — same id under different providers stays", () => {
    const merged = mergePiModelCatalogs(
        [{ id: "x", provider: "p1" }],
        [{ id: "x", provider: "p1" }, { id: "x", provider: "p2" }],
    );
    assert.equal(merged.length, 2);
    assert.deepEqual(merged.map((m) => m.provider), ["p1", "p2"]);
});

test("missing or empty inputs never crash and produce sane output", () => {
    assert.deepEqual(mergePiModelCatalogs(), []);
    assert.deepEqual(mergePiModelCatalogs(null, undefined), []);
    assert.deepEqual(mergePiModelCatalogs([], []), []);
    assert.deepEqual(mergePiModelCatalogs([{ id: "a", provider: "p" }], null), [
        { id: "a", name: "a", provider: "p" },
    ]);
});

test("entries without a usable id are skipped; provider defaults to pi-cli", () => {
    const merged = mergePiModelCatalogs(
        [{ id: "a" }, { name: "no-id" }, { id: "", provider: "p" }],
        [],
    );
    assert.deepEqual(merged, [{ id: "a", name: "a", provider: "pi-cli" }]);
});