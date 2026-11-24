// Phone inbox store (phone-inbox, T1).
//
// Two small JSON files live in idupi-server/data/ (gitignored):
//   inbox.json         notification items, newest 500 kept
//   inbox-config.json  { agent, model, effort } used by the agent runner
//
// Writes are atomic (temp file + rename) and both files are loaded once at
// startup. The store is synchronous on purpose: the route handler calls it
// between awaits and the file is at most 500 small items, so an async API
// would only add interleaving bugs.

import { randomBytes } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { AGENTS, EFFORTS } from "./inbox-agent.mjs";

export const ITEM_ID_PATTERN = /^inb-[0-9a-f]{12}$/;

/**
 * App ids the inbox accepts: a preset id ("whatsapp_business") or the package
 * name of any app the owner follows ("org.telegram.messenger"). Both are
 * lowercase; anything else is refused so a malformed client cannot store an
 * arbitrary string that later reaches the agent prompt.
 */
export const APP_ID_PATTERN = /^[a-z0-9_.]{1,120}$/;

/** The optional display label the phone captures from PackageManager. */
export const MAX_APP_LABEL_CHARS = 60;

export function isValidAppId(value) {
    return typeof value === "string" && APP_ID_PATTERN.test(value);
}

export const INBOX_STATUSES = ["analyzing", "ready", "failed"];
export const INBOX_IMPORTANCES = ["high", "medium", "low"];
export const INBOX_STATES = ["open", "handled", "replied", "auto_replied", "dismissed"];

/** States that make an item closed for the owner's working list. */
export const CLOSED_STATES = ["handled", "replied", "auto_replied", "dismissed"];

export const DEFAULT_MAX_ITEMS = 500;
export const DEFAULT_MAX_TEXT_CHARS = 4000;

/** Closed items older than this are pruned; open items are never pruned. */
export const DEFAULT_PRUNE_AFTER_MS = 24 * 60 * 60 * 1000;

export const DEFAULT_AGENT = "opencode";
export const DEFAULT_EFFORT = "max";
// `opencode models` on this PC lists opencode-go/deepseek-v4.1-flash and no
// non-flash opencode-go/deepseek-v4.1* model, so the flash variant is the
// verified default.
export const DEFAULT_MODEL = "opencode-go/deepseek-v4.1-flash";

const ITEMS_FILE = "inbox.json";
const CONFIG_FILE = "inbox-config.json";
const MAX_FIELD_CHARS = 200;
const MAX_NOTIFICATION_KEY_CHARS = 400;
const MAX_CATEGORY_CHARS = 60;
const MAX_ERROR_CHARS = 300;
const MAX_CONFIG_MODEL_CHARS = 200;
const MAX_RELATED_ITEMS = 5;

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

function singleLine(value, maxChars) {
    return clip(String(value ?? "").replace(/\s+/g, " ").trim(), maxChars);
}

function isValidItem(candidate) {
    if (!candidate || typeof candidate !== "object") return false;
    if (!ITEM_ID_PATTERN.test(String(candidate.id ?? ""))) return false;
    if (!isValidAppId(candidate.app)) return false;
    if (typeof candidate.text !== "string") return false;
    if (!Number.isFinite(Date.parse(String(candidate.receivedAt ?? "")))) return false;
    return true;
}

/**
 * @param {{
 *   dataDir: string,
 *   now?: (() => Date | number | string) | Date | number | string,
 *   maxItems?: number,
 *   maxTextChars?: number,
 *   pruneAfterMs?: number,
 *   log?: { warn?: Function, info?: Function },
 * }} options
 */
export function createInboxStore({
    dataDir,
    now = () => new Date(),
    maxItems = DEFAULT_MAX_ITEMS,
    maxTextChars = DEFAULT_MAX_TEXT_CHARS,
    pruneAfterMs = DEFAULT_PRUNE_AFTER_MS,
    log = console,
} = {}) {
    if (typeof dataDir !== "string" || dataDir.length === 0) {
        throw new Error("dataDir is required");
    }

    const maxCount = Number.isInteger(maxItems) && maxItems > 0 ? maxItems : DEFAULT_MAX_ITEMS;
    const maxText = Number.isInteger(maxTextChars) && maxTextChars > 0
        ? maxTextChars
        : DEFAULT_MAX_TEXT_CHARS;
    const pruneAfter = Number.isFinite(pruneAfterMs) && pruneAfterMs >= 0
        ? pruneAfterMs
        : DEFAULT_PRUNE_AFTER_MS;

    const itemsFile = join(dataDir, ITEMS_FILE);
    const configFile = join(dataDir, CONFIG_FILE);

    /** @type {object[]} insertion order: oldest first */
    const items = [];
    /** @type {{ agent: string, model: string, effort: string }} */
    let config = { agent: DEFAULT_AGENT, model: DEFAULT_MODEL, effort: DEFAULT_EFFORT };

    let tempCounter = 0;

    function clock() {
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

    function persistItems({ prune = true } = {}) {
        if (prune) pruneClosed();
        atomicWrite(itemsFile, JSON.stringify({ version: 1, items }, null, 2) + "\n");
    }

    /**
     * Drops closed items whose updatedAt is older than `pruneAfter`. Open items
     * are kept forever by this rule; returns whether anything was removed.
     */
    function pruneClosed() {
        const cutoff = Date.parse(clock()) - pruneAfter;
        if (!Number.isFinite(cutoff)) return false;
        let changed = false;
        for (let index = items.length - 1; index >= 0; index -= 1) {
            const item = items[index];
            if (!CLOSED_STATES.includes(item.state)) continue;
            const updatedAt = Date.parse(item.updatedAt);
            if (Number.isFinite(updatedAt) && updatedAt < cutoff) {
                items.splice(index, 1);
                changed = true;
            }
        }
        return changed;
    }

    function persistConfig() {
        atomicWrite(configFile, JSON.stringify({ version: 1, ...config }, null, 2) + "\n");
    }

    function readJsonFile(filePath) {
        let raw;
        try {
            raw = readFileSync(filePath, "utf8");
        } catch (err) {
            if (err && err.code !== "ENOENT") {
                log?.warn?.(`[inbox] no se pudo leer ${filePath}: ${err.message}`);
            }
            return null;
        }
        try {
            return JSON.parse(raw);
        } catch {
            log?.warn?.(`[inbox] archivo corrupto, se ignora: ${filePath}`);
            return null;
        }
    }

    function loadItems() {
        const parsed = readJsonFile(itemsFile);
        if (!parsed) return;
        const listed = Array.isArray(parsed) ? parsed : parsed?.items;
        if (!Array.isArray(listed)) {
            log?.warn?.("[inbox] archivo sin lista de items, se ignora");
            return;
        }

        let changed = false;
        for (const candidate of listed) {
            if (!isValidItem(candidate)) {
                changed = true;
                continue;
            }
            items.push({
                id: candidate.id,
                app: candidate.app,
                appLabel: singleLine(candidate.appLabel, MAX_APP_LABEL_CHARS),
                packageName: clip(candidate.packageName, MAX_FIELD_CHARS),
                sender: clip(candidate.sender, MAX_FIELD_CHARS),
                conversation: clip(candidate.conversation, MAX_FIELD_CHARS),
                text: clip(candidate.text, maxText),
                postedAt: toIso(candidate.postedAt, candidate.receivedAt),
                receivedAt: toIso(candidate.receivedAt, clock()),
                notificationKey: clip(candidate.notificationKey, MAX_NOTIFICATION_KEY_CHARS),
                status: INBOX_STATUSES.includes(candidate.status) ? candidate.status : "failed",
                importance: INBOX_IMPORTANCES.includes(candidate.importance) ? candidate.importance : null,
                category: singleLine(candidate.category, MAX_CATEGORY_CHARS),
                summary: clip(candidate.summary, 600),
                suggestedReply: clip(candidate.suggestedReply, 1200),
                error: candidate.error ? clip(candidate.error, MAX_ERROR_CHARS) : null,
                state: INBOX_STATES.includes(candidate.state) ? candidate.state : "open",
                updatedAt: toIso(candidate.updatedAt, candidate.receivedAt),
            });
        }

        if (items.length > maxCount) {
            items.splice(0, items.length - maxCount);
            changed = true;
        }
        if (changed) {
            try {
                persistItems();
            } catch (err) {
                log?.warn?.(`[inbox] no se pudo limpiar inbox.json: ${err.message}`);
            }
        }

        // The file may be valid yet still hold closed items past the window.
        if (pruneClosed()) {
            try {
                persistItems({ prune: false });
            } catch (err) {
                log?.warn?.(`[inbox] no se pudo podar inbox.json: ${err.message}`);
            }
        }
    }

    function loadConfig() {
        const parsed = readJsonFile(configFile);
        if (!parsed) return;
        const candidate = {
            agent: parsed?.agent,
            model: parsed?.model,
            effort: parsed?.effort,
        };
        if (
            !AGENTS.includes(candidate.agent) ||
            !EFFORTS.includes(candidate.effort) ||
            typeof candidate.model !== "string" ||
            candidate.model.trim().length === 0
        ) {
            log?.warn?.("[inbox] configuración inválida, se usan los valores por defecto");
            return;
        }
        config = {
            agent: candidate.agent,
            model: clip(candidate.model.trim(), MAX_CONFIG_MODEL_CHARS),
            effort: candidate.effort,
        };
    }

    /**
     * Adds one notification. Dedup key: same notificationKey AND same text.
     *
     * @returns {{ item: object, created: boolean }}
     */
    function add(input = {}) {
        const app = String(input.app ?? "");
        if (!isValidAppId(app)) {
            throw new Error("invalid app");
        }
        const text = clip(typeof input.text === "string" ? input.text : String(input.text ?? ""), maxText);
        if (text.trim().length === 0) {
            throw new Error("text is required");
        }
        const notificationKey = clip(String(input.notificationKey ?? ""), MAX_NOTIFICATION_KEY_CHARS);
        if (notificationKey.trim().length === 0) {
            throw new Error("notificationKey is required");
        }

        const existing = items.find(
            (candidate) => candidate.notificationKey === notificationKey && candidate.text === text,
        );
        if (existing) {
            return { item: clone(existing), created: false };
        }

        const receivedAt = clock();
        const item = {
            id: `inb-${randomBytes(6).toString("hex")}`,
            app,
            appLabel: singleLine(input.appLabel, MAX_APP_LABEL_CHARS),
            packageName: clip(input.packageName, MAX_FIELD_CHARS),
            sender: clip(input.sender, MAX_FIELD_CHARS),
            conversation: clip(input.conversation, MAX_FIELD_CHARS),
            text,
            postedAt: toIso(input.postedAt, receivedAt),
            receivedAt,
            notificationKey,
            status: "analyzing",
            importance: null,
            category: "",
            summary: "",
            suggestedReply: "",
            error: null,
            state: "open",
            updatedAt: receivedAt,
        };

        items.push(item);
        if (items.length > maxCount) {
            items.splice(0, items.length - maxCount);
        }
        persistItems();
        return { item: clone(item), created: true };
    }

    function get(id) {
        const item = items.find((candidate) => candidate.id === id);
        return item ? clone(item) : null;
    }

    /** Newest first, insertion order as the tie-breaker. */
    function list({ limit = 50 } = {}) {
        if (pruneClosed()) {
            try {
                persistItems({ prune: false });
            } catch (err) {
                log?.warn?.(`[inbox] no se pudo podar inbox.json: ${err.message}`);
            }
        }
        const safeLimit = Number.isInteger(limit) && limit > 0 ? limit : 50;
        return items
            .map((item, index) => ({ item, index }))
            .sort((a, b) => {
                const delta = Date.parse(b.item.receivedAt) - Date.parse(a.item.receivedAt);
                return delta !== 0 ? delta : b.index - a.index;
            })
            .slice(0, safeLimit)
            .map(({ item }) => clone(item));
    }

    /**
     * Up to five earlier items of the same app + conversation, newest first.
     * Used as untrusted context for the classification prompt.
     */
    function related(item, limit = 5) {
        if (!item) return [];
        const cutoff = Date.parse(item.receivedAt);
        const safeLimit = Number.isInteger(limit) && limit > 0
            ? Math.min(limit, MAX_RELATED_ITEMS)
            : MAX_RELATED_ITEMS;
        return items
            .filter((candidate) => (
                candidate.id !== item.id &&
                candidate.app === item.app &&
                candidate.conversation === item.conversation &&
                Date.parse(candidate.receivedAt) <= cutoff
            ))
            .sort((a, b) => Date.parse(b.receivedAt) - Date.parse(a.receivedAt))
            .slice(0, safeLimit)
            .map((candidate) => clone(candidate));
    }

    /**
     * Applies one validated patch. Returns the updated item or null when the id
     * is unknown.
     */
    function update(id, patch = {}) {
        const item = items.find((candidate) => candidate.id === id);
        if (!item) return null;

        if ("status" in patch) {
            if (!INBOX_STATUSES.includes(patch.status)) throw new Error("invalid status");
            item.status = patch.status;
        }
        if ("state" in patch) {
            if (!INBOX_STATES.includes(patch.state)) throw new Error("invalid state");
            item.state = patch.state;
        }
        if ("importance" in patch) {
            if (patch.importance !== null && !INBOX_IMPORTANCES.includes(patch.importance)) {
                throw new Error("invalid importance");
            }
            item.importance = patch.importance;
        }
        if ("category" in patch) item.category = singleLine(patch.category, MAX_CATEGORY_CHARS);
        if ("summary" in patch) item.summary = clip(patch.summary, 600);
        if ("suggestedReply" in patch) item.suggestedReply = clip(patch.suggestedReply, 1200);
        if ("error" in patch) item.error = patch.error ? clip(String(patch.error), MAX_ERROR_CHARS) : null;
        item.updatedAt = toIso(patch.updatedAt, clock());

        persistItems();
        return clone(item);
    }

    function getConfig() {
        return { ...config };
    }

    /**
     * Validates and persists { agent, model, effort }. Throws on invalid input;
     * the route maps that to 400.
     */
    function setConfig(next = {}) {
        const agent = String(next.agent ?? "");
        const effort = String(next.effort ?? "");
        const model = typeof next.model === "string" ? next.model.trim() : "";
        if (!AGENTS.includes(agent)) throw new Error("unknown agent");
        if (!EFFORTS.includes(effort)) throw new Error("unknown effort");
        if (model.length === 0) throw new Error("model is required");
        if (model.length > MAX_CONFIG_MODEL_CHARS) throw new Error("model is too long");

        config = { agent, model, effort };
        persistConfig();
        return getConfig();
    }

    loadItems();
    loadConfig();

    return {
        add,
        get,
        list,
        related,
        update,
        getConfig,
        setConfig,
    };
}
