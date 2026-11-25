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

import { AGENTS, EFFORTS, MAX_INTERACTION_ANSWER_CHARS, MAX_INTERACTION_INSTRUCTION_CHARS, MAX_PROJECT_HINT_CHARS } from "./inbox-agent.mjs";

export const ITEM_ID_PATTERN = /^inb-[0-9a-f]{12}$/;

/**
 * App ids the inbox accepts: a preset id ("whatsapp_business") or the package
 * name of any app the owner follows ("org.telegram.messenger"). Android
 * package names may contain uppercase letters; anything outside letters,
 * digits, "_" and "." is refused so a malformed client cannot store an
 * arbitrary string that later reaches the agent prompt.
 */
export const APP_ID_PATTERN = /^[A-Za-z0-9_.]{1,120}$/;

/** The optional display label the phone captures from PackageManager. */
export const MAX_APP_LABEL_CHARS = 60;

export function isValidAppId(value) {
    return typeof value === "string" && APP_ID_PATTERN.test(value);
}

export const INBOX_STATUSES = ["analyzing", "ready", "failed"];
export const INBOX_IMPORTANCES = ["high", "medium", "low"];
export const INBOX_STATES = ["open", "handled", "replied", "auto_replied", "dismissed"];

/** Interactuar job state persisted with the item (alerts-flow A3). */
export const INTERACTION_STATUSES = ["running", "done", "failed"];

/** States that make an item closed for the owner's working list. */
export const CLOSED_STATES = ["handled", "replied", "auto_replied", "dismissed"];

export const DEFAULT_MAX_ITEMS = 500;
export const DEFAULT_MAX_TEXT_CHARS = 4000;

/**
 * Category stored on a digest-only item (inbox-management T4): the phone
 * silenced the conversation, so the message is captured for the conversation
 * digest instead of being classified on its own.
 */
export const DIGEST_ONLY_CATEGORY = "Silenciado";

/** Closed items older than this are pruned; open items are never pruned. */
export const DEFAULT_PRUNE_AFTER_MS = 24 * 60 * 60 * 1000;

export const DEFAULT_AGENT = "opencode";
export const DEFAULT_EFFORT = "max";
// `opencode models` on this PC lists opencode-go/deepseek-v4.1-flash and no
// non-flash opencode-go/deepseek-v4.1* model, so the flash variant is the
// verified default.
export const DEFAULT_MODEL = "opencode-go/deepseek-v4.1-flash";
// Proactive work (alerts-flow A4): on by default, because IDUPI's whole point
// is to land work ready to review. Nothing is ever sent to a third party.
export const DEFAULT_PROACTIVE = true;

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

/** The extracted calendar event, revalidated on load; malformed becomes null. */
function sanitizeStoredEvent(value) {
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    const title = singleLine(value.title, 120);
    if (title.length === 0) return null;
    const start = toIso(value.start, null);
    const end = toIso(value.end, null);
    if (!start || !end) return null;
    return {
        title,
        start,
        end,
        allDay: value.allDay === true,
        location: singleLine(value.location, 200),
    };
}

/** Deterministic overlap warnings attached after the analysis. */
function sanitizeEventConflicts(value) {
    if (!Array.isArray(value)) return [];
    const conflicts = [];
    for (const entry of value.slice(0, 20)) {
        if (!entry || typeof entry !== "object") continue;
        const start = toIso(entry.start, null);
        const end = toIso(entry.end, null);
        if (!start || !end) continue;
        conflicts.push({ title: singleLine(entry.title, 120), start, end });
    }
    return conflicts;
}

/**
 * The Interactuar result revalidated on load and on every write (alerts-flow
 * A3). A missing or malformed block becomes null: an item from before A3, or
 * one written by a newer server this build cannot trust, still decodes.
 */
function sanitizeStoredInteraction(value) {
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    const status = INTERACTION_STATUSES.includes(value.status) ? value.status : null;
    if (!status) return null;
    const instruction = clip(value.instruction, MAX_INTERACTION_INSTRUCTION_CHARS).trim();
    if (instruction.length === 0) return null;
    return {
        status,
        instruction,
        projectId: singleLine(value.projectId, MAX_FIELD_CHARS) || null,
        projectName: singleLine(value.projectName, MAX_FIELD_CHARS) || null,
        answer: clip(value.answer, MAX_INTERACTION_ANSWER_CHARS),
        startedAt: toIso(value.startedAt, null),
        finishedAt: toIso(value.finishedAt, null),
        error: value.error ? clip(String(value.error), MAX_ERROR_CHARS) : null,
        // Proactive jobs (alerts-flow A4) are marked so the app can say IDUPI
        // did the work itself. Missing on items from before A4: false.
        auto: value.auto === true,
    };
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
    /** @type {{ agent: string, model: string, effort: string, proactive: boolean }} */
    let config = {
        agent: DEFAULT_AGENT,
        model: DEFAULT_MODEL,
        effort: DEFAULT_EFFORT,
        proactive: DEFAULT_PROACTIVE,
    };

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
                // Items written before alerts-flow A2 have no flag: false.
                promotion: candidate.promotion === true,
                // Proactive classification (alerts-flow A4); old items: false/empty.
                technical: candidate.technical === true,
                projectHint: singleLine(candidate.projectHint, MAX_PROJECT_HINT_CHARS),
                // Items written before alerts-flow A3 have no interaction: null.
                interaction: sanitizeStoredInteraction(candidate.interaction),
                event: sanitizeStoredEvent(candidate.event),
                eventConflicts: sanitizeEventConflicts(candidate.eventConflicts),
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
            // A file written before alerts-flow A4 has no flag: keep it on.
            proactive: typeof parsed?.proactive === "boolean"
                ? parsed.proactive
                : DEFAULT_PROACTIVE,
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
        // A silenced conversation is summarized per conversation on demand, so
        // its items skip the per-message agent entirely (inbox-management T4):
        // they are stored already "ready" and the digest route does the work.
        const digestOnly = input.digestOnly === true;
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
            status: digestOnly ? "ready" : "analyzing",
            importance: digestOnly ? "low" : null,
            category: digestOnly ? DIGEST_ONLY_CATEGORY : "",
            summary: "",
            suggestedReply: "",
            promotion: false,
            // Filled by the analysis (alerts-flow A4).
            technical: false,
            projectHint: "",
            // Interactuar (alerts-flow A3): filled by the route, not the capture.
            interaction: null,
            event: null,
            eventConflicts: [],
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
     * Open items of one app + conversation, oldest first. `limit` keeps the
     * NEWEST ones (the tail), which is what a conversation digest summarizes.
     */
    function openByConversation(app, conversation, limit = 200) {
        const safeLimit = Number.isInteger(limit) && limit > 0 ? limit : 200;
        return items
            .filter((item) => (
                item.state === "open" &&
                item.app === app &&
                item.conversation === conversation
            ))
            .sort((a, b) => Date.parse(a.receivedAt) - Date.parse(b.receivedAt))
            .slice(-safeLimit)
            .map((item) => clone(item));
    }

    /**
     * Closes every open item of one conversation in a single write
     * (inbox-management T4). Returns the updated items so the phone can count
     * what left the list.
     */
    function closeConversation(app, conversation, state, updatedAt) {
        if (!CLOSED_STATES.includes(state)) throw new Error("invalid state");
        const stamp = toIso(updatedAt, clock());
        const closed = [];
        for (const item of items) {
            if (item.state !== "open" || item.app !== app || item.conversation !== conversation) {
                continue;
            }
            item.state = state;
            item.updatedAt = stamp;
            closed.push(clone(item));
        }
        if (closed.length > 0) persistItems();
        return closed;
    }

    /**
     * Open promotion items of every app, oldest first (the newest [limit] are
     * kept). Alerts-flow A2: one promotions digest summarizes them together,
     * across apps and conversations.
     */
    function openPromotions(limit = 200) {
        const safeLimit = Number.isInteger(limit) && limit > 0 ? limit : 200;
        return items
            .filter((item) => item.state === "open" && item.promotion === true)
            .sort((a, b) => Date.parse(a.receivedAt) - Date.parse(b.receivedAt))
            .slice(-safeLimit)
            .map((item) => clone(item));
    }

    /** Closes every open promotion item in one write (alerts-flow A2). */
    function closePromotions(state, updatedAt) {
        if (!CLOSED_STATES.includes(state)) throw new Error("invalid state");
        const stamp = toIso(updatedAt, clock());
        const closed = [];
        for (const item of items) {
            if (item.state !== "open" || item.promotion !== true) continue;
            item.state = state;
            item.updatedAt = stamp;
            closed.push(clone(item));
        }
        if (closed.length > 0) persistItems();
        return closed;
    }

    /** Digest keys (`app\u0000conversation`) that still have an open item. */
    function openConversationKeys() {
        const keys = new Set();
        for (const item of items) {
            if (item.state !== "open") continue;
            keys.add(`${item.app}\u0000${item.conversation}`);
        }
        return keys;
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
        if ("promotion" in patch) {
            if (typeof patch.promotion !== "boolean") throw new Error("invalid promotion");
            item.promotion = patch.promotion;
        }
        if ("technical" in patch) {
            if (typeof patch.technical !== "boolean") throw new Error("invalid technical");
            item.technical = patch.technical;
        }
        if ("projectHint" in patch) {
            item.projectHint = singleLine(patch.projectHint, MAX_PROJECT_HINT_CHARS);
        }
        if ("interaction" in patch) {
            item.interaction = sanitizeStoredInteraction(patch.interaction);
        }
        if ("event" in patch) item.event = sanitizeStoredEvent(patch.event);
        if ("eventConflicts" in patch) {
            item.eventConflicts = sanitizeEventConflicts(patch.eventConflicts);
        }
        if ("error" in patch) item.error = patch.error ? clip(String(patch.error), MAX_ERROR_CHARS) : null;
        item.updatedAt = toIso(patch.updatedAt, clock());

        persistItems();
        return clone(item);
    }

    function getConfig() {
        return { ...config };
    }

    /**
     * Validates and persists { agent, model, effort, proactive }. Throws on
     * invalid input; the route maps that to 400. `proactive` is optional: an
     * older app that does not send it keeps the stored value (alerts-flow A4).
     */
    function setConfig(next = {}) {
        const agent = String(next.agent ?? "");
        const effort = String(next.effort ?? "");
        const model = typeof next.model === "string" ? next.model.trim() : "";
        if (!AGENTS.includes(agent)) throw new Error("unknown agent");
        if (!EFFORTS.includes(effort)) throw new Error("unknown effort");
        if (model.length === 0) throw new Error("model is required");
        if (model.length > MAX_CONFIG_MODEL_CHARS) throw new Error("model is too long");
        const proactive = next.proactive === undefined ? config.proactive : next.proactive;
        if (typeof proactive !== "boolean") throw new Error("invalid proactive");

        config = { agent, model, effort, proactive };
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
        openByConversation,
        closeConversation,
        openPromotions,
        closePromotions,
        openConversationKeys,
        update,
        getConfig,
        setConfig,
    };
}
