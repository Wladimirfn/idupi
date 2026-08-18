import { execSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";

const providerModelsCache = new Map();

function getModelsForProvider(providerId) {
    if (providerModelsCache.has(providerId)) {
        return providerModelsCache.get(providerId);
    }
    try {
        const raw = execSync(`opencode models "${providerId}"`, { encoding: "utf8", timeout: 4000 });
        const lines = raw.split("\n").map(l => l.trim()).filter(l => l && !l.startsWith("Error") && !l.startsWith("opencode models"));
        const models = lines.map(line => {
            const parts = line.split("/");
            const id = parts.length > 1 ? parts.slice(1).join("/") : line;
            return {
                id: id,
                fullId: line,
                name: id,
                provider: parts[0] || providerId
            };
        });
        if (models.length > 0) {
            providerModelsCache.set(providerId, models);
            return models;
        }
    } catch(e) {}
    return [];
}

console.log("opencode-go models:", getModelsForProvider("opencode-go").slice(0, 3));
console.log("openai models:", getModelsForProvider("openai").slice(0, 3));
console.log("minimax models:", getModelsForProvider("minimax").slice(0, 3));
