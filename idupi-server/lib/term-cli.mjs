// Pure logic for the `idupi-term` CLI (live-shared-terminals, T3).
//
// The CLI is a second client of the PTY API the Android app and the browser
// page already use (lib/pty-routes.mjs): same bearer token, same chunked
// stream, same input/resize POSTs. Everything here is deterministic and has
// no I/O so test/term-cli.test.mjs can pin the behaviour without a TTY, a
// server or the real token file. bin/idupi-term.mjs is the thin I/O shell.

import { StringDecoder } from "node:string_decoder";

export const DEFAULT_PORT = 8788;
export const HOST = "127.0.0.1";
export const RECONNECT_MIN_MS = 500;
export const RECONNECT_MAX_MS = 4000;
export const INPUT_FLUSH_MS = 5;
export const RESIZE_DEBOUNCE_MS = 100;
// The server caps an input body at 256 KiB; 64 KiB chunks leave a wide margin
// and keep one paste from becoming a single giant request.
export const MAX_INPUT_CHUNK_BYTES = 64 * 1024;
// Byte 0x1d is Ctrl+]. Raw mode delivers it as an ordinary byte in a chunk.
export const DETACH_BYTE = 0x1d;
// Mirrors the Android/browser defaults for a console without a TTY (pipes).
export const DEFAULT_COLS = 120;
export const DEFAULT_ROWS = 30;

const HELP_COMMANDS = new Set(["help", "--help", "-h"]);

/**
 * @typedef {object} PtyInfo
 * @property {string} id
 * @property {number} pid
 * @property {number} cols
 * @property {number} rows
 * @property {string} cwd
 * @property {string} shell
 * @property {string} createdAt
 * @property {boolean} alive
 * @property {number | null} exitCode
 * @property {string | null} error
 */

/**
 * Resolves the port from `IDUPI_PORT`, falling back to the server default.
 * An unset value is not an error; a malformed one is, so a typo never silently
 * points the CLI at a different server.
 *
 * @param {Record<string, string | undefined>} [env]
 * @returns {number}
 */
export function resolvePort(env = process.env) {
    const raw = String(env.IDUPI_PORT ?? "").trim();
    if (!raw) return DEFAULT_PORT;
    const port = Number(raw);
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
        throw new Error("IDUPI_PORT inválido");
    }
    return port;
}

/**
 * Parses the command line. `argv` excludes `node` and the script path.
 *
 * @param {string[]} [argv]
 * @returns {{
 *   command: "list" | "ls" | "new" | "attach" | "web" | "help",
 *   interactive?: boolean,
 *   ref?: string,
 *   cwd?: string | null,
 *   unknown?: string,
 *   error?: string,
 * }}
 */
export function parseArgs(argv = []) {
    const args = Array.isArray(argv) ? [...argv] : [];
    if (args.length === 0) return { command: "list", interactive: true };

    const [head, ...rest] = args;
    if (head === "ls") return { command: "ls" };

    if (head === "attach") {
        if (rest.length === 0) {
            return {
                command: "attach",
                error: "Uso: idupi-term attach <id|prefijo|número>",
            };
        }
        return { command: "attach", ref: rest[0] };
    }

    if (head === "new") {
        let cwd = null;
        for (let i = 0; i < rest.length; i += 1) {
            if (rest[i] === "--cwd") {
                if (i + 1 >= rest.length) {
                    return {
                        command: "new",
                        error: "Uso: idupi-term new [--cwd DIR]",
                    };
                }
                cwd = rest[i + 1];
                i += 1;
            } else {
                return { command: "help", unknown: rest[i] };
            }
        }
        return { command: "new", cwd };
    }

    if (head === "web") return { command: "web" };
    if (HELP_COMMANDS.has(head)) return { command: "help" };
    return { command: "help", unknown: head };
}

/**
 * Resolves the bearer token: the environment wins, then the token file.
 * Returns null when neither yields a value. The token itself is never logged
 * here or by the caller.
 *
 * @param {{
 *   env?: Record<string, string | undefined>,
 *   tokenPath?: string | null,
 *   readFile?: (path: string, encoding: string) => string,
 * }} [options]
 * @returns {string | null}
 */
export function resolveToken({ env = {}, tokenPath = null, readFile } = {}) {
    const fromEnv = typeof env.IDUPI_TOKEN === "string"
        ? env.IDUPI_TOKEN.trim()
        : "";
    if (fromEnv) return fromEnv;
    if (!tokenPath || typeof readFile !== "function") return null;
    try {
        const fromFile = String(readFile(tokenPath, "utf8")).trim();
        return fromFile || null;
    } catch {
        return null;
    }
}

/**
 * The id without the `pty-` prefix, first six characters: what the CLI shows
 * so a title bar and the list stay short.
 *
 * @param {string} id
 * @returns {string}
 */
export function shortId(id) {
    return String(id ?? "").replace(/^pty-/, "").slice(0, 6);
}

/**
 * Display name of the shell: the executable's basename, without arguments.
 *
 * @param {string} shell
 * @returns {string}
 */
export function shellName(shell) {
    const first = String(shell ?? "").trim().split(/\s+/)[0] || "shell";
    return first.replace(/^.*[\\/]/, "");
}

/**
 * Short age label: `12s`, `5m`, `3h`, `2d`. `?` when the timestamp is unusable.
 *
 * @param {string} createdAt ISO timestamp
 * @param {number} [now] milliseconds, injectable for tests
 * @returns {string}
 */
export function formatAge(createdAt, now = Date.now()) {
    const then = Date.parse(createdAt);
    if (!Number.isFinite(then)) return "?";
    const seconds = Math.max(0, Math.floor((now - then) / 1000));
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h`;
    return `${Math.floor(hours / 24)}d`;
}

/**
 * Numbered list used both by `ls` and by the no-argument prompt. The index is
 * 1-based and matches the order `GET /api/v1/pty` returned, which is what
 * `attach <n>` resolves against.
 *
 * @param {PtyInfo[]} terminals
 * @param {number} [now]
 * @returns {string}
 */
export function formatTerminalList(terminals, now = Date.now()) {
    if (!Array.isArray(terminals) || terminals.length === 0) {
        return "No hay terminales.";
    }
    const lines = ["  #  ID      Shell             Tamaño    Estado        Edad"];
    terminals.forEach((info, i) => {
        const index = String(i + 1).padStart(2, " ");
        const id = shortId(info.id).padEnd(6, " ");
        const shell = shellName(info.shell).padEnd(16, " ");
        const size = `${info.cols}x${info.rows}`.padEnd(9, " ");
        const state = (info.alive ? "viva" : "finalizada").padEnd(12, " ");
        const age = formatAge(info.createdAt, now);
        lines.push(` ${index}) ${id}  ${shell}  ${size}  ${state}  ${age}`);
    });
    return lines.join("\n");
}

/**
 * Resolves the `attach` reference against a PTY list: a full id, a unique id
 * prefix, or a 1-based index as printed by `ls`.
 *
 * @param {string} ref
 * @param {PtyInfo[]} terminals
 * @returns {{ id?: string, error?: "missing" | "ambiguous", message?: string }}
 */
export function resolveRef(ref, terminals) {
    const raw = String(ref ?? "").trim();
    const list = Array.isArray(terminals) ? terminals : [];
    if (!raw) {
        return {
            error: "missing",
            message: "Falta la referencia de la terminal.",
        };
    }

    if (/^\d+$/.test(raw)) {
        const index = Number(raw);
        if (index < 1 || index > list.length) {
            return {
                error: "missing",
                message: `No existe la terminal número ${raw}.`,
            };
        }
        return { id: list[index - 1].id };
    }

    const exact = list.find((info) => info.id === raw);
    if (exact) return { id: exact.id };

    const matches = list.filter((info) => String(info.id).startsWith(raw));
    if (matches.length === 1) return { id: matches[0].id };
    if (matches.length > 1) {
        return {
            error: "ambiguous",
            message:
                `Referencia ambigua: ${matches.length} terminales coinciden con '${raw}'.`,
        };
    }
    return {
        error: "missing",
        message: `No se encontró la terminal '${raw}'.`,
    };
}

/**
 * Interprets the no-argument selection prompt: empty means 1, `n` creates a
 * new terminal, anything else must be an in-range 1-based index.
 *
 * @param {string} answer
 * @param {number} count number of rows in the printed list
 * @returns {{ kind: "attach", index: number } | { kind: "new" } | { kind: "error", message: string }}
 */
export function parseSelection(answer, count) {
    const text = String(answer ?? "").trim();
    if (text === "") {
        if (count >= 1) return { kind: "attach", index: 1 };
        return { kind: "new" };
    }
    if (text.toLowerCase() === "n") return { kind: "new" };
    if (/^\d+$/.test(text)) {
        const index = Number(text);
        if (index >= 1 && index <= count) return { kind: "attach", index };
        return { kind: "error", message: `Número fuera de rango (1-${count}).` };
    }
    return { kind: "error", message: "Opción inválida." };
}

/**
 * Splits one raw stdin chunk at the first Ctrl+] byte. Bytes after the detach
 * key are discarded: the user asked to leave, not to type them.
 *
 * @param {Buffer | Uint8Array} chunk
 * @returns {{ data: Buffer, detached: boolean }}
 */
export function splitDetachKey(chunk) {
    const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk ?? []);
    const at = buf.indexOf(DETACH_BYTE);
    if (at === -1) return { data: buf, detached: false };
    return { data: buf.subarray(0, at), detached: true };
}

/**
 * Wraps StringDecoder so a UTF-8 character split across two stdin reads is
 * emitted whole with the second read instead of becoming U+FFFD.
 *
 * @returns {{ write: (chunk: Buffer | Uint8Array) => string, end: () => string }}
 */
export function createInputDecoder() {
    const decoder = new StringDecoder("utf8");
    return {
        write: (chunk) => decoder.write(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)),
        end: () => decoder.end(),
    };
}

function utf8LengthOfCodePoint(ch) {
    if (ch.length > 1) return 4; // surrogate pair: one supplementary code point
    const code = ch.codePointAt(0);
    if (code < 0x80) return 1;
    if (code < 0x800) return 2;
    return 3;
}

/**
 * Splits text into pieces whose UTF-8 encoding fits `maxBytes`, never cutting
 * a code point (and therefore never a surrogate pair) in half.
 *
 * @param {string} text
 * @param {number} [maxBytes]
 * @returns {string[]}
 */
export function chunkUtf8(text, maxBytes = MAX_INPUT_CHUNK_BYTES) {
    const source = String(text ?? "");
    const limit = Math.max(4, Math.floor(maxBytes) || MAX_INPUT_CHUNK_BYTES);
    const chunks = [];
    let start = 0;
    let bytes = 0;
    let i = 0;
    for (const ch of source) {
        const length = utf8LengthOfCodePoint(ch);
        if (bytes + length > limit) {
            chunks.push(source.slice(start, i));
            start = i;
            bytes = 0;
        }
        bytes += length;
        i += ch.length;
    }
    if (i > start) chunks.push(source.slice(start, i));
    return chunks;
}

/**
 * Next reconnect delay: 500 -> 1000 -> 2000 -> 4000 -> 4000.
 *
 * @param {number} currentMs
 * @returns {number}
 */
export function nextBackoff(currentMs) {
    const current = Number.isFinite(currentMs) && currentMs > 0
        ? currentMs
        : RECONNECT_MIN_MS;
    return Math.min(current * 2, RECONNECT_MAX_MS);
}

/**
 * Message shown when the stream ended and the PTY is gone or no longer alive.
 *
 * @param {number | null | undefined} exitCode
 * @returns {string}
 */
export function formatExitMessage(exitCode) {
    return Number.isInteger(exitCode)
        ? `Terminal finalizada (código ${exitCode}).`
        : "Terminal finalizada.";
}
