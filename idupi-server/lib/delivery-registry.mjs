// APK delivery registry (apk-delivery, T1).
//
// The server is where an APK is built; the phone installs it. A delivery is a
// SNAPSHOT: register() streams the source file into storeDir while hashing it,
// so a rebuild that replaces the source cannot change the bytes under an
// in-flight download. The index is persisted next to the snapshots (temp file
// + rename) and reloaded on startup; entries whose snapshot disappeared are
// dropped instead of being served as broken downloads.
//
// Zero dependencies, node built-ins only, same as the rest of idupi-server.

import { createHash, randomBytes } from "node:crypto";
import {
    createReadStream,
    createWriteStream,
    mkdirSync,
    readFileSync,
    renameSync,
    statSync,
    unlinkSync,
    writeFileSync,
} from "node:fs";
import { stat } from "node:fs/promises";
import { basename, extname, isAbsolute, join } from "node:path";
import { Transform } from "node:stream";
import { pipeline } from "node:stream/promises";

export const ID_PATTERN = /^dlv-[0-9a-f]{12}$/;

export const DELIVERY_ERROR_CODES = new Set([
    "INVALID_PATH",
    "NOT_APK",
    "NOT_FILE",
    "TOO_LARGE",
    "NOT_FOUND",
]);

const INDEX_FILE = "index.json";
const DEFAULT_MAX_ENTRIES = 10;
const DEFAULT_MAX_BYTES = 300 * 1024 * 1024;

function typedError(code, message) {
    const err = new Error(message);
    err.code = code;
    return err;
}

/**
 * UNC (`\\server\share`), device (`\\?\C:\...`, `\\.\...`) and their forward
 * slash spellings are network or device namespaces, not a local file; the
 * registry only ever hands out files the phone can trust came from this PC.
 */
function isUncOrDevicePath(value) {
    return /^[\\/]{2}/.test(value);
}

function toIso(value) {
    if (value instanceof Date) return value.toISOString();
    if (typeof value === "number") {
        const fromNumber = new Date(value);
        return Number.isNaN(fromNumber.getTime())
            ? new Date().toISOString()
            : fromNumber.toISOString();
    }
    if (typeof value === "string") {
        const fromString = new Date(value);
        if (!Number.isNaN(fromString.getTime())) return fromString.toISOString();
    }
    return new Date().toISOString();
}

function isValidEntry(candidate) {
    if (!candidate || typeof candidate !== "object") return false;
    if (!ID_PATTERN.test(String(candidate.id ?? ""))) return false;
    if (typeof candidate.name !== "string" || candidate.name.length === 0) return false;
    if (!Number.isInteger(candidate.size) || candidate.size <= 0) return false;
    if (!/^[0-9a-f]{64}$/.test(String(candidate.sha256 ?? ""))) return false;
    if (typeof candidate.sourcePath !== "string" || candidate.sourcePath.length === 0) return false;
    return Number.isFinite(Date.parse(String(candidate.createdAt ?? "")));
}

function safeUnlink(path) {
    try {
        unlinkSync(path);
    } catch {
        /* already gone: the point of cleanup is only that it is not there */
    }
}

/**
 * Streams `sourcePath` into `destPath` with `flags: "wx"` while hashing and
 * counting the copied bytes. The cap is enforced on the copied bytes too, in
 * case the source grows between stat() and the stream.
 */
async function copyHashed(sourcePath, destPath, maxBytes) {
    const hash = createHash("sha256");
    let size = 0;
    const meter = new Transform({
        transform(chunk, _encoding, callback) {
            size += chunk.length;
            if (size > maxBytes) {
                callback(typedError("TOO_LARGE", `file exceeds ${maxBytes} bytes`));
                return;
            }
            hash.update(chunk);
            callback(null, chunk);
        },
    });
    await pipeline(
        createReadStream(sourcePath),
        meter,
        createWriteStream(destPath, { flags: "wx" }),
    );
    return { sha256: hash.digest("hex"), size };
}

/**
 * @param {{
 *   storeDir: string,
 *   maxEntries?: number,
 *   maxBytes?: number,
 *   now?: (() => Date | number | string) | Date | number | string,
 *   log?: { info?: Function, warn?: Function, error?: Function },
 * }} options
 */
export function createDeliveryRegistry({
    storeDir,
    maxEntries = DEFAULT_MAX_ENTRIES,
    maxBytes = DEFAULT_MAX_BYTES,
    now = () => new Date(),
    log = console,
} = {}) {
    if (typeof storeDir !== "string" || storeDir.length === 0) {
        throw typedError("INVALID_PATH", "storeDir is required");
    }

    const maxCount = Number.isInteger(maxEntries) && maxEntries >= 0
        ? maxEntries
        : DEFAULT_MAX_ENTRIES;
    const maxSize = Number.isFinite(maxBytes) && maxBytes > 0
        ? maxBytes
        : DEFAULT_MAX_BYTES;

    /** @type {Array<{id:string,name:string,size:number,sha256:string,sourcePath:string,createdAt:string}>} */
    const entries = [];
    let tempCounter = 0;

    function indexPayload() {
        return JSON.stringify({ version: 1, deliveries: entries }, null, 2) + "\n";
    }

    function persist() {
        mkdirSync(storeDir, { recursive: true });
        tempCounter += 1;
        const tempPath = join(storeDir, `.index-${process.pid}-${tempCounter}.tmp`);
        writeFileSync(tempPath, indexPayload(), "utf8");
        try {
            renameSync(tempPath, join(storeDir, INDEX_FILE));
        } catch (err) {
            safeUnlink(tempPath);
            throw err;
        }
    }

    // Load once, synchronously: the index holds at most `maxEntries` rows and
    // an async constructor would make list()/get() lie until a promise settled.
    function loadIndex() {
        let raw;
        try {
            raw = readFileSync(join(storeDir, INDEX_FILE), "utf8");
        } catch (err) {
            if (err && err.code !== "ENOENT") {
                log?.warn?.(`[delivery] no se pudo leer el índice: ${err.message}`);
            }
            return;
        }

        let parsed;
        try {
            parsed = JSON.parse(raw);
        } catch {
            log?.warn?.("[delivery] índice corrupto: se ignora");
            return;
        }

        const listed = Array.isArray(parsed) ? parsed : parsed?.deliveries;
        if (!Array.isArray(listed)) {
            log?.warn?.("[delivery] índice sin lista de entregas: se ignora");
            return;
        }

        let dropped = false;
        const seen = new Set();
        for (const candidate of listed) {
            if (!isValidEntry(candidate) || seen.has(candidate.id)) {
                dropped = true;
                continue;
            }
            try {
                const info = statSync(join(storeDir, `${candidate.id}.apk`));
                if (!info.isFile()) throw new Error("not a file");
            } catch {
                dropped = true;
                continue;
            }
            seen.add(candidate.id);
            entries.push({
                id: candidate.id,
                name: candidate.name,
                size: candidate.size,
                sha256: candidate.sha256,
                sourcePath: candidate.sourcePath,
                createdAt: candidate.createdAt,
            });
        }

        if (dropped) {
            try {
                persist();
            } catch (err) {
                log?.warn?.(`[delivery] no se pudo limpiar el índice: ${err.message}`);
            }
        }
    }

    function oldestIndex() {
        let oldest = 0;
        for (let i = 1; i < entries.length; i += 1) {
            if (Date.parse(entries[i].createdAt) < Date.parse(entries[oldest].createdAt)) {
                oldest = i;
            }
        }
        return oldest;
    }

    function evictBeyondLimit() {
        while (entries.length > maxCount) {
            const [removed] = entries.splice(oldestIndex(), 1);
            safeUnlink(join(storeDir, `${removed.id}.apk`));
        }
    }

    /**
     * Registers an existing APK on this PC and returns the delivery entry.
     *
     * @param {string} sourcePath absolute path to a regular `.apk` file
     */
    async function register(sourcePath) {
        if (typeof sourcePath !== "string" || sourcePath.trim().length === 0) {
            throw typedError("INVALID_PATH", "path is required");
        }
        if (isUncOrDevicePath(sourcePath)) {
            throw typedError("INVALID_PATH", "UNC and device paths are not allowed");
        }
        if (!isAbsolute(sourcePath)) {
            throw typedError("INVALID_PATH", "path must be absolute");
        }
        if (extname(sourcePath).toLowerCase() !== ".apk") {
            throw typedError("NOT_APK", "only .apk files can be delivered");
        }

        let info;
        try {
            info = await stat(sourcePath);
        } catch (err) {
            if (err && err.code === "ENOENT") {
                throw typedError("NOT_FOUND", `file not found: ${sourcePath}`);
            }
            throw typedError("INVALID_PATH", `cannot read ${sourcePath}: ${err.message}`);
        }
        if (!info.isFile()) {
            throw typedError("NOT_FILE", "path is not a regular file");
        }
        if (info.size <= 0) {
            throw typedError("NOT_FILE", "file is empty");
        }
        if (info.size > maxSize) {
            throw typedError("TOO_LARGE", `file exceeds ${maxSize} bytes`);
        }

        mkdirSync(storeDir, { recursive: true });
        let id;
        let stored;
        for (let attempt = 0; ; attempt += 1) {
            id = `dlv-${randomBytes(6).toString("hex")}`;
            const destPath = join(storeDir, `${id}.apk`);
            try {
                stored = await copyHashed(sourcePath, destPath, maxSize);
                break;
            } catch (err) {
                safeUnlink(destPath);
                if (err && err.code === "EEXIST" && attempt < 4) continue;
                throw err;
            }
        }

        const entry = {
            id,
            name: basename(sourcePath),
            size: stored.size,
            sha256: stored.sha256,
            sourcePath,
            createdAt: toIso(typeof now === "function" ? now() : now),
        };
        entries.push(entry);
        evictBeyondLimit();
        persist();
        log?.info?.(`[delivery] registrada ${entry.id} (${entry.name}, ${entry.size} bytes)`);
        return { ...entry };
    }

    /** Newest first. */
    function list() {
        return entries
            .map((entry, index) => ({ entry, index }))
            .sort((a, b) => {
                const delta = Date.parse(b.entry.createdAt) - Date.parse(a.entry.createdAt);
                return delta !== 0 ? delta : b.index - a.index;
            })
            .map(({ entry }) => ({ ...entry }));
    }

    function get(id) {
        if (!ID_PATTERN.test(String(id ?? ""))) return null;
        const entry = entries.find((candidate) => candidate.id === id);
        return entry ? { ...entry } : null;
    }

    function filePath(id) {
        return get(id) ? join(storeDir, `${id}.apk`) : null;
    }

    async function remove(id) {
        if (!ID_PATTERN.test(String(id ?? ""))) return false;
        const index = entries.findIndex((candidate) => candidate.id === id);
        if (index === -1) return false;
        const [removed] = entries.splice(index, 1);
        safeUnlink(join(storeDir, `${removed.id}.apk`));
        persist();
        log?.info?.(`[delivery] eliminada ${removed.id}`);
        return true;
    }

    loadIndex();

    return { register, list, get, filePath, remove };
}
