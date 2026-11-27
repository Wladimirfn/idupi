#!/usr/bin/env node
// idupi-phone: query and control the phone from the PC (phone-agent, T1).
//
// This file is the thin I/O shell, same shape as bin/idupi-push.mjs: argument
// parsing and formatting are exported pure helpers so node:test can cover them
// without a server, and the HTTP work only runs when the file is invoked
// directly. Token and port resolution are reused from lib/term-cli.mjs so all
// PC CLIs behave identically.

import { readFileSync, writeSync } from "node:fs";
import http from "node:http";
import { dirname, resolve as resolvePath } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { HOST, resolvePort, resolveToken } from "../lib/term-cli.mjs";
import { validateHostPort } from "../lib/adb-connection.mjs";
import { TURN_GRANT_ENV, TURN_GRANT_HEADER } from "../lib/phone-turn-grants.mjs";

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
// Same layout as bin/idupi-term.mjs and bin/idupi-push.mjs: the token file
// lives at the repository root, two levels above idupi-server/bin/.
const TOKEN_PATH = resolvePath(SCRIPT_DIR, "..", "..", ".idupi-token");

const USAGE = `idupi-phone — estado y control del teléfono desde la PC

Uso:
  idupi-phone status [--json]           Estado del teléfono (batería, señal, red, memoria, apps)
  idupi-phone screen [--json]           Texto de lo que se ve en la pantalla
  idupi-phone tap <texto>               Toca el nodo con ese texto
  idupi-phone tap --id <id>             Toca el nodo con ese resource id
  idupi-phone tap --xy <x> <y>          Toca esas coordenadas
  idupi-phone long-press <texto>        Mantiene presionado (mismas formas que tap)
  idupi-phone type <texto> [--submit]   Escribe texto (--submit también envía)
  idupi-phone scroll <up|down|left|right>
  idupi-phone back | home | recents | notifications
  idupi-phone quick-settings            Abre los ajustes rápidos
  idupi-phone open <app>                Abre una app (paquete o nombre visible)
  idupi-phone wait <ms>                 Espera en el teléfono (0..10000)
  idupi-phone calendar [agenda] [--days N] [--json]
                                        Agenda de los calendarios del teléfono (7 días por defecto)
  idupi-phone calendar free [--days N] [--min M]
                                        Espacios libres (mínimo 30 min por defecto)
  idupi-phone calendar propose --title T --start ISO [--end ISO] [--all-day]
                               [--location L] [--notes N]
                                        Propone un evento; se escribe solo si lo apruebas en el teléfono
  idupi-phone calendar proposals        Propuestas pendientes de aprobación
  idupi-phone health [summary] [--days N] [--json]
                                        Salud de los últimos N días (7 por defecto)
  idupi-phone health sources            Fuentes de salud y apps instaladas
  idupi-phone health refresh [--wait]   Lee Huawei Health en el teléfono (pantalla oculta)
  idupi-phone health digest [--refresh] [--json]
                                        Resumen diario del agente (salud, bienestar y agenda)
  idupi-phone health adb [status|pair <host:puerto> <código>|connect <host:puerto>]
                                        Estado y emparejamiento de ADB inalámbrico
  idupi-phone log                       Últimos comandos
  idupi-phone approvals [--json]        Aprobaciones pendientes en el teléfono
  idupi-phone help                      Muestra esta ayuda

El control debe estar activo en la app: permiso de accesibilidad e interruptor
maestro. Los comandos expiran a los 60 s si el teléfono no los toma a tiempo.
Los comandos riesgosos esperan tu aprobación en el teléfono (10 min); si no
respondes, no se ejecutan.
`;

const SERVER_UNREACHABLE =
    `No se pudo conectar con IDUPI en ${HOST}:`;
const SERVER_UNREACHABLE_HINT =
    ". ¿Está corriendo? (idupi-server/scripts/start-server.ps1)";
const TOKEN_REJECTED = "Token rechazado: revisa .idupi-token.";
const NO_RESPONSE =
    "El teléfono no respondió: ¿está activo el control desde la PC?";
const EXPIRED = "El comando expiró sin ejecutarse: el teléfono no lo tomó a tiempo.";
const DEADLINE_MS = 65_000;
const WAIT_BUDGET_SECONDS = 30;
const HTTP_TIMEOUT_MS = 40_000;

const GLOBAL_SHORTCUTS = {
    back: "back",
    home: "home",
    recents: "recents",
    notifications: "notifications",
    "quick-settings": "quick_settings",
    quick_settings: "quick_settings",
};

let port = 8788;
let token = "";

// --- pure parsing ----------------------------------------------------------

function targetUsage(label) {
    return `Uso: idupi-phone ${label} <texto> | --id <id> | --xy <x> <y>`;
}

/**
 * @param {"tap" | "long_press"} type
 * @param {string[]} rest
 */
function parseTargetCommand(type, rest) {
    const label = type === "tap" ? "tap" : "long-press";
    const usage = targetUsage(label);
    let id = null;
    let xy = null;
    const words = [];

    for (let index = 0; index < rest.length; index += 1) {
        const token = rest[index];
        if (token === "--id") {
            const value = rest[index + 1];
            if (typeof value !== "string" || value.trim().length === 0) {
                return { command: "help", error: usage };
            }
            id = value.trim();
            index += 1;
            continue;
        }
        if (token === "--xy") {
            const x = Number(rest[index + 1]);
            const y = Number(rest[index + 2]);
            if (
                !Number.isInteger(x) || x < 0 ||
                !Number.isInteger(y) || y < 0
            ) {
                return { command: "help", error: usage };
            }
            xy = { x, y };
            index += 2;
            continue;
        }
        if (token.startsWith("--")) return { command: "help", error: usage };
        words.push(token);
    }

    const forms = [id !== null, xy !== null, words.length > 0].filter(Boolean).length;
    if (forms !== 1) return { command: "help", error: usage };
    if (id !== null) return { command: type, target: { id } };
    if (xy) return { command: type, target: xy };
    return { command: type, target: { text: words.join(" ") } };
}

/**
 * Parses the command line. `argv` excludes `node` and the script path.
 *
 * @param {string[]} [argv]
 * @returns {object} a command descriptor, or { command: "help", error }
 */
export function parseArgs(argv = []) {
    const args = Array.isArray(argv) ? [...argv] : [];
    if (args.length === 0) {
        return { command: "help", error: "Falta el comando. Prueba: idupi-phone status" };
    }

    const [head, ...rest] = args;
    if (head === "help" || head === "--help" || head === "-h") {
        return { command: "help" };
    }

    if (head === "status" || head === "screen") {
        if (rest.length === 0) return { command: head, json: false };
        if (rest.length === 1 && rest[0] === "--json") return { command: head, json: true };
        return { command: "help", error: `Uso: idupi-phone ${head} [--json]` };
    }

    if (head === "tap") return parseTargetCommand("tap", rest);
    if (head === "long-press") return parseTargetCommand("long_press", rest);

    if (head === "type") {
        let submit = false;
        const words = [];
        for (const token of rest) {
            if (token === "--submit") {
                submit = true;
                continue;
            }
            if (token.startsWith("--")) {
                return { command: "help", error: "Uso: idupi-phone type <texto> [--submit]" };
            }
            words.push(token);
        }
        const text = words.join(" ");
        if (text.trim().length === 0) {
            return { command: "help", error: "Uso: idupi-phone type <texto> [--submit]" };
        }
        return { command: "type", text, submit };
    }

    if (head === "scroll") {
        if (rest.length !== 1 || !["up", "down", "left", "right"].includes(rest[0])) {
            return { command: "help", error: "Uso: idupi-phone scroll <up|down|left|right>" };
        }
        return { command: "scroll", direction: rest[0] };
    }

    if (head === "open") {
        const app = rest.join(" ").trim();
        if (app.length === 0) {
            return { command: "help", error: "Uso: idupi-phone open <app>" };
        }
        return { command: "open", app };
    }

    if (head === "wait") {
        if (rest.length !== 1 || !/^\d+$/.test(rest[0])) {
            return { command: "help", error: "Uso: idupi-phone wait <ms> (0..10000)" };
        }
        const ms = Number(rest[0]);
        if (!Number.isInteger(ms) || ms < 0 || ms > 10_000) {
            return { command: "help", error: "Uso: idupi-phone wait <ms> (0..10000)" };
        }
        return { command: "wait", ms };
    }

    if (head === "log") {
        if (rest.length > 0) return { command: "help", error: "Uso: idupi-phone log" };
        return { command: "log" };
    }

    if (head === "approvals") {
        if (rest.length === 0) return { command: "approvals", json: false };
        if (rest.length === 1 && rest[0] === "--json") {
            return { command: "approvals", json: true };
        }
        return { command: "help", error: "Uso: idupi-phone approvals [--json]" };
    }

    if (head === "calendar") return parseCalendarArgs(rest);
    if (head === "health") return parseHealthArgs(rest);

    if (Object.prototype.hasOwnProperty.call(GLOBAL_SHORTCUTS, head)) {
        if (rest.length > 0) {
            return { command: "help", error: `Uso: idupi-phone ${head}` };
        }
        return { command: "global", action: GLOBAL_SHORTCUTS[head] };
    }

    if (head.startsWith("-")) {
        return { command: "help", error: `Opción desconocida: ${head}` };
    }
    return { command: "help", error: `Comando desconocido: ${head}` };
}

const CALENDAR_USAGE =
    "Uso: idupi-phone calendar [agenda] [--days N] [--json] | " +
    "idupi-phone calendar free [--days N] [--min M] | " +
    "idupi-phone calendar propose --title T --start ISO [--end ISO] [--all-day] " +
    "[--location L] [--notes N] | idupi-phone calendar proposals";

/** Calendar subcommands share one parser; values are plain argv entries. */
function parseCalendarArgs(rest) {
    const args = Array.isArray(rest) ? [...rest] : [];
    if (args.length === 0) {
        return { command: "calendar", action: "agenda", days: 7, json: false };
    }
    const [sub, ...tail] = args;
    // "agenda" is optional: `calendar --days 3` means `calendar agenda --days 3`.
    if (sub.startsWith("--")) return parseAgendaArgs(args);
    if (sub === "agenda") return parseAgendaArgs(tail);
    if (sub === "free") return parseFreeArgs(tail);
    if (sub === "propose") return parseProposeArgs(tail);
    if (sub === "proposals") {
        if (tail.length > 0) return { command: "help", error: CALENDAR_USAGE };
        return { command: "calendar", action: "proposals" };
    }
    return { command: "help", error: CALENDAR_USAGE };
}

function parseDays(raw, fallback, max) {
    if (raw === undefined) return fallback;
    const value = Number(raw);
    if (!Number.isInteger(value) || value < 1 || value > max) return null;
    return value;
}

function parseAgendaArgs(tail) {
    let days = 7;
    let json = false;
    for (let index = 0; index < tail.length; index += 1) {
        const token = tail[index];
        if (token === "--json") {
            json = true;
            continue;
        }
        if (token === "--days") {
            const value = parseDays(tail[index + 1], 7, 30);
            if (value === null) return { command: "help", error: CALENDAR_USAGE };
            days = value;
            index += 1;
            continue;
        }
        return { command: "help", error: CALENDAR_USAGE };
    }
    return { command: "calendar", action: "agenda", days, json };
}

function parseFreeArgs(tail) {
    let days = 7;
    let min = 30;
    for (let index = 0; index < tail.length; index += 1) {
        const token = tail[index];
        if (token === "--days") {
            const value = parseDays(tail[index + 1], 7, 62);
            if (value === null) return { command: "help", error: CALENDAR_USAGE };
            days = value;
            index += 1;
            continue;
        }
        if (token === "--min") {
            const value = parseDays(tail[index + 1], 30, 1440);
            if (value === null) return { command: "help", error: CALENDAR_USAGE };
            min = value;
            index += 1;
            continue;
        }
        return { command: "help", error: CALENDAR_USAGE };
    }
    return { command: "calendar", action: "free", days, min };
}

function parseProposeArgs(tail) {
    let title = null;
    let start = null;
    let end = null;
    let allDay = false;
    let location = null;
    let notes = null;

    const valueAt = (index) => {
        const value = tail[index];
        if (typeof value !== "string" || value.trim().length === 0) return null;
        if (value.startsWith("--")) return null;
        return value.trim();
    };
    const fail = () => ({ command: "help", error: CALENDAR_USAGE });

    for (let index = 0; index < tail.length; index += 1) {
        const token = tail[index];
        if (token === "--all-day") {
            allDay = true;
            continue;
        }
        if (token === "--title") {
            title = valueAt(index + 1);
            if (title === null) return fail();
            index += 1;
            continue;
        }
        if (token === "--start") {
            start = valueAt(index + 1);
            if (start === null || Number.isNaN(Date.parse(start))) return fail();
            index += 1;
            continue;
        }
        if (token === "--end") {
            end = valueAt(index + 1);
            if (end === null || Number.isNaN(Date.parse(end))) return fail();
            index += 1;
            continue;
        }
        if (token === "--location") {
            location = valueAt(index + 1);
            if (location === null) return fail();
            index += 1;
            continue;
        }
        if (token === "--notes") {
            notes = valueAt(index + 1);
            if (notes === null) return fail();
            index += 1;
            continue;
        }
        return fail();
    }

    if (title === null || start === null) return fail();
    return {
        command: "calendar",
        action: "propose",
        title,
        start,
        end,
        allDay,
        location,
        notes,
    };
}

const HEALTH_USAGE =
    "Uso: idupi-phone health [summary] [--days N] [--json] | " +
    "idupi-phone health sources | idupi-phone health refresh [--wait] | " +
    "idupi-phone health digest [--refresh] [--json] | " +
    "idupi-phone health adb [status | pair <host:puerto> <código> | connect <host:puerto>]";

function parseHealthDays(raw) {
    if (raw === undefined) return 7;
    const value = Number(raw);
    if (!Number.isInteger(value) || value < 1 || value > 120) return null;
    return value;
}

/** Health subcommands all share this parser; values are plain argv entries. */
function parseHealthArgs(rest) {
    const args = Array.isArray(rest) ? [...rest] : [];
    if (args.length === 0) return { command: "health", action: "summary", days: 7, json: false };
    const [sub, ...tail] = args;
    if (sub.startsWith("--")) return parseHealthSummaryArgs(args);
    if (sub === "summary" || sub === "hoy") return parseHealthSummaryArgs(tail);
    if (sub === "sources") {
        if (tail.length === 1 && tail[0] === "--json") {
            return { command: "health", action: "sources", json: true };
        }
        if (tail.length > 0) return { command: "help", error: HEALTH_USAGE };
        return { command: "health", action: "sources", json: false };
    }
    if (sub === "refresh") return parseHealthRefreshArgs(tail);
    if (sub === "digest") return parseHealthDigestArgs(tail);
    if (sub === "adb") return parseHealthAdbArgs(tail);
    return { command: "help", error: HEALTH_USAGE };
}

function parseHealthSummaryArgs(tail) {
    let days = 7;
    let json = false;
    for (let index = 0; index < tail.length; index += 1) {
        const token = tail[index];
        if (token === "--json") {
            json = true;
            continue;
        }
        if (token === "--days") {
            const value = parseHealthDays(tail[index + 1]);
            if (value === null) return { command: "help", error: HEALTH_USAGE };
            days = value;
            index += 1;
            continue;
        }
        return { command: "help", error: HEALTH_USAGE };
    }
    return { command: "health", action: "summary", days, json };
}

function parseHealthRefreshArgs(tail) {
    let wait = false;
    let json = false;
    for (const token of tail) {
        if (token === "--wait") {
            wait = true;
            continue;
        }
        if (token === "--json") {
            json = true;
            continue;
        }
        return { command: "help", error: HEALTH_USAGE };
    }
    return { command: "health", action: "refresh", wait, json };
}

function parseHealthDigestArgs(tail) {
    let refresh = false;
    let json = false;
    for (const token of tail) {
        if (token === "--refresh") {
            refresh = true;
            continue;
        }
        if (token === "--json") {
            json = true;
            continue;
        }
        return { command: "help", error: HEALTH_USAGE };
    }
    return { command: "health", action: "digest", refresh, json };
}

function parseHealthAdbArgs(tail) {
    if (tail.length === 0) return { command: "health", action: "adb", adbAction: "status" };
    const [sub, ...rest] = tail;
    if (sub === "status") {
        if (rest.length === 1 && rest[0] === "--json") {
            return { command: "health", action: "adb", adbAction: "status", json: true };
        }
        if (rest.length > 0) return { command: "help", error: HEALTH_USAGE };
        return { command: "health", action: "adb", adbAction: "status", json: false };
    }
    if (sub === "pair") {
        if (rest.length !== 2) return { command: "help", error: HEALTH_USAGE };
        if (!validateHostPort(rest[0])) return { command: "help", error: HEALTH_USAGE };
        if (!/^\d{6}$/.test(rest[1])) return { command: "help", error: HEALTH_USAGE };
        return {
            command: "health",
            action: "adb",
            adbAction: "pair",
            hostPort: rest[0],
            code: rest[1],
        };
    }
    if (sub === "connect") {
        if (rest.length !== 1) return { command: "help", error: HEALTH_USAGE };
        if (!validateHostPort(rest[0])) return { command: "help", error: HEALTH_USAGE };
        return {
            command: "health",
            action: "adb",
            adbAction: "connect",
            hostPort: rest[0],
        };
    }
    return { command: "help", error: HEALTH_USAGE };
}

// --- pure formatting -------------------------------------------------------

/** One decimal, binary units. Invalid input degrades to "?". */
export function formatBytes(bytes) {
    const value = Number(bytes);
    if (!Number.isFinite(value) || value < 0) return "?";
    const units = ["B", "KB", "MB", "GB", "TB"];
    let scaled = value;
    let unit = 0;
    while (scaled >= 1024 && unit < units.length - 1) {
        scaled /= 1024;
        unit += 1;
    }
    if (unit === 0) return `${Math.round(scaled)} B`;
    return `${scaled.toFixed(1)} ${units[unit]}`;
}

/** Minutes as "42 min" or "1 h 5 min". */
export function formatDuration(totalMinutes) {
    const minutes = Number(totalMinutes);
    if (!Number.isFinite(minutes) || minutes < 0) return "?";
    const whole = Math.round(minutes);
    if (whole < 60) return `${whole} min`;
    const hours = Math.floor(whole / 60);
    const rest = whole % 60;
    return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
}

function formatAgo(seconds) {
    if (seconds < 60) return `hace ${seconds} s`;
    if (seconds < 3600) return `hace ${Math.round(seconds / 60)} min`;
    if (seconds < 86_400) return `hace ${Math.round(seconds / 3600)} h`;
    return `hace ${Math.round(seconds / 86_400)} d`;
}

function numberOrNull(value) {
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
}

/**
 * Pretty summary of GET /api/v1/phone/status.
 *
 * @param {{ latest?: object | null, history?: object[], online?: boolean, lastSeenAt?: string | null }} payload
 * @param {number} [nowMs]
 * @returns {string}
 */
export function formatPhoneStatus(payload, nowMs = Date.now()) {
    const latest = payload && typeof payload === "object" ? payload.latest : null;
    if (!latest || typeof latest !== "object") {
        return "Sin datos del teléfono todavía.";
    }

    const lines = [];
    const rawSeen = payload.lastSeenAt || latest.receivedAt;
    const seenMs = typeof rawSeen === "string" ? Date.parse(rawSeen) : Number.NaN;
    const ago = Number.isFinite(seenMs)
        ? formatAgo(Math.max(0, Math.round((nowMs - seenMs) / 1000)))
        : null;
    const connected = payload.online === true;
    lines.push(
        connected
            ? `Teléfono: en línea${ago ? ` (visto ${ago})` : ""}`
            : `Teléfono: sin conexión${ago ? ` (visto ${ago})` : ""}`,
    );

    const battery = latest.battery;
    if (battery && typeof battery === "object") {
        const percent = numberOrNull(battery.percent);
        if (percent !== null) {
            let line = `Batería: ${Math.round(percent)}%`;
            if (battery.charging === true) line += " (cargando)";
            const temperature = numberOrNull(battery.temperatureC);
            if (temperature !== null) line += ` ${temperature.toFixed(1)} °C`;
            lines.push(line);
        }
    }

    const signal = latest.signal;
    if (signal && typeof signal === "object") {
        const bits = [];
        const level = numberOrNull(signal.level);
        if (level !== null) bits.push(`${level}/5`);
        const dbm = numberOrNull(signal.dbm);
        if (dbm !== null) bits.push(`${dbm} dBm`);
        if (typeof signal.operator === "string" && signal.operator) bits.push(signal.operator);
        if (typeof signal.networkType === "string" && signal.networkType) {
            bits.push(signal.networkType);
        }
        if (bits.length > 0) lines.push(`Señal: ${bits.join(" · ")}`);
    }

    const network = latest.network;
    if (network && typeof network === "object") {
        const kind = String(network.kind || network.type || "");
        if (kind === "wifi") {
            let line = "Red: Wi-Fi";
            if (typeof network.ssid === "string" && network.ssid) line += ` "${network.ssid}"`;
            const rssi = numberOrNull(network.rssiDbm);
            if (rssi !== null) line += ` (${rssi} dBm)`;
            lines.push(line);
        } else if (kind === "mobile") {
            lines.push("Red: datos móviles");
        } else if (kind === "none") {
            lines.push("Red: sin conexión");
        } else if (kind) {
            lines.push(`Red: ${kind}`);
        }
    }

    const memory = latest.memory;
    if (memory && typeof memory === "object") {
        const total = numberOrNull(memory.totalBytes);
        if (total !== null && total > 0) {
            const used = numberOrNull(memory.usedBytes);
            const percent = used === null ? "" : ` (${Math.round((used / total) * 100)}%)`;
            lines.push(`Memoria: ${formatBytes(used)} de ${formatBytes(total)}${percent}`);
        }
    }

    const storage = latest.storage;
    if (storage && typeof storage === "object") {
        const total = numberOrNull(storage.totalBytes);
        if (total !== null && total > 0) {
            const used = numberOrNull(storage.usedBytes);
            const percent = used === null ? "" : ` (${Math.round((used / total) * 100)}%)`;
            lines.push(`Almacenamiento: ${formatBytes(used)} de ${formatBytes(total)}${percent}`);
        }
    }

    const foreground = latest.foregroundApp;
    if (foreground && typeof foreground === "object") {
        const label = typeof foreground.label === "string" ? foreground.label : "";
        const packageName = typeof foreground.packageName === "string"
            ? foreground.packageName
            : "";
        if (label || packageName) {
            const detail = label && packageName ? `${label} (${packageName})` : label || packageName;
            lines.push(`App en primer plano: ${detail}`);
        }
    }

    const topApps = Array.isArray(latest.topAppsToday) ? latest.topAppsToday : [];
    if (topApps.length > 0) {
        lines.push("Más usadas hoy:");
        for (const entry of topApps) {
            if (!entry || typeof entry !== "object") continue;
            const label = typeof entry.label === "string" && entry.label
                ? entry.label
                : String(entry.packageName || "");
            if (!label) continue;
            const minutes = numberOrNull(entry.minutes);
            lines.push(`  - ${label}: ${minutes === null ? "?" : formatDuration(minutes)}`);
        }
    }

    // phone-dashboard T3: the 8 largest apps with the cache a cleanup frees.
    const largestApps = Array.isArray(latest.largestApps) ? latest.largestApps : [];
    if (largestApps.length > 0) {
        lines.push("Apps que más ocupan:");
        for (const entry of largestApps) {
            if (!entry || typeof entry !== "object") continue;
            const label = typeof entry.label === "string" && entry.label
                ? entry.label
                : String(entry.packageName || "");
            if (!label) continue;
            const total = numberOrNull(entry.totalBytes);
            let line = `  - ${label}: ${formatBytes(total)}`;
            const cache = numberOrNull(entry.cacheBytes);
            if (cache !== null && cache > 0) line += ` (caché ${formatBytes(cache)})`;
            lines.push(line);
        }
    }

    return lines.join("\n");
}

/** Human-readable outcome of one failed command, including approval outcomes. */
export function failureMessage(command) {
    if (!command || typeof command !== "object") return NO_RESPONSE;
    const error = typeof command.error === "string" ? command.error : "";
    if (error === "APPROVAL_REJECTED") return "Rechazado en el teléfono.";
    if (error === "APPROVAL_EXPIRED") return "La aprobación venció; no se hizo nada.";
    return `El teléfono falló: ${error || "sin detalle"}`;
}

/** Human-readable outcome of one finished command. */
export function formatCommandResult(command) {
    if (!command || typeof command !== "object") return NO_RESPONSE;
    switch (command.state) {
        case "done": {
            const result = command.result && typeof command.result === "object"
                ? command.result
                : null;
            if (result && typeof result.message === "string" && result.message) {
                return `Listo: ${result.message}`;
            }
            if (result && typeof result.text === "string" && result.text) {
                return result.text;
            }
            return "Listo.";
        }
        case "failed":
            return failureMessage(command);
        case "expired":
            return EXPIRED;
        default:
            return `${NO_RESPONSE} (estado: ${command.state || "desconocido"})`;
    }
}

/** Recent-command log from GET /api/v1/phone/commands. */
export function formatCommandList(commands) {
    if (!Array.isArray(commands) || commands.length === 0) return "No hay comandos.";
    const lines = [`Comandos recientes (${commands.length}):`];
    for (const command of commands) {
        if (!command || typeof command !== "object") continue;
        const state = String(command.state || "?").padEnd(9);
        const type = String(command.type || "?").padEnd(10);
        let line = `  ${command.id}  ${state}  ${type}  ${command.createdAt}`;
        if (command.state === "failed" && command.error) line += ` — ${command.error}`;
        lines.push(line);
    }
    return lines.join("\n");
}

// --- approvals formatting (phone-approvals, T1) ---------------------------

const APPROVAL_REASON_LABELS = {
    type_submit: "envío de texto",
    sensitive_app: "acción en una app sensible",
    risky_text: "texto riesgoso",
    phone_detected_risky_target: "texto riesgoso detectado en el teléfono",
};

const DEFAULT_APPROVAL_MINUTES = 10;

function approvalExpiresMs(entry) {
    const raw = entry && (entry.approvalExpiresAt ?? entry.expiresAt);
    const ms = typeof raw === "string" ? Date.parse(raw) : Number.NaN;
    return Number.isFinite(ms) ? ms : null;
}

function approvalMinutesLeft(entry, nowMs) {
    const expiresMs = approvalExpiresMs(entry);
    if (expiresMs === null) return DEFAULT_APPROVAL_MINUTES;
    return Math.max(1, Math.ceil((expiresMs - nowMs) / 60_000));
}

/** Legible reason of a pending approval, target included when there is one. */
export function formatApprovalReason(entry) {
    const reason = entry && (entry.approvalReason ?? entry.reason);
    const target = entry && (entry.approvalTarget ?? entry.target);
    if (
        (reason === "risky_text" || reason === "phone_detected_risky_target") &&
        typeof target === "string" && target.length > 0
    ) {
        return `toque en "${target}"`;
    }
    return APPROVAL_REASON_LABELS[reason] || "acción riesgosa";
}

/**
 * Line printed when a command is parked: the reason and the time left.
 *
 * @param {object} command the awaiting_approval command
 * @param {number} [nowMs]
 */
export function formatApprovalWait(command, nowMs = Date.now()) {
    return `Esperando tu aprobación en el teléfono (${formatApprovalReason(command)}, ` +
        `vence en ${approvalMinutesLeft(command, nowMs)} min)…`;
}

function approvalCommandLabel(command) {
    if (!command || typeof command !== "object") return "comando";
    const type = typeof command.type === "string" && command.type ? command.type : "comando";
    const label = type === "long_press" ? "long-press" : type;
    const args = command.args && typeof command.args === "object" ? command.args : {};
    let target = null;
    if (typeof args.text === "string" && args.text.trim()) target = args.text.trim();
    else if (typeof args.id === "string" && args.id.trim()) target = args.id.trim();
    else if (Number.isInteger(args.x) && Number.isInteger(args.y)) target = `${args.x},${args.y}`;
    if (!target) return label;
    const short = target.length > 60 ? `${target.slice(0, 57)}...` : target;
    return `${label} "${short}"`;
}

/** Pending approval list from GET /api/v1/phone/approvals. */
export function formatApprovals(payload, nowMs = Date.now()) {
    const approvals = payload && Array.isArray(payload.approvals) ? payload.approvals : [];
    if (approvals.length === 0) return "No hay aprobaciones pendientes.";
    const lines = [`Aprobaciones pendientes (${approvals.length}):`];
    for (const approval of approvals) {
        if (!approval || typeof approval !== "object") continue;
        const bits = [approval.id, approvalCommandLabel(approval.command)];
        const foreground = approval.foregroundApp;
        if (foreground && typeof foreground === "object") {
            const packageName = typeof foreground.packageName === "string"
                ? foreground.packageName
                : "";
            const label = typeof foreground.label === "string" ? foreground.label : "";
            if (label && packageName) bits.push(`${label} (${packageName})`);
            else if (label || packageName) bits.push(label || packageName);
        }
        lines.push(
            `  ${bits.join("  ")}  — ${formatApprovalReason(approval)}, ` +
            `vence en ${approvalMinutesLeft(approval, nowMs)} min`,
        );
    }
    return lines.join("\n");
}

// --- calendar formatting (phone-calendar, T1) ------------------------------

const CALENDAR_DAY_MS = 86_400_000;
const WEEKDAY_LABELS = ["dom", "lun", "mar", "mié", "jue", "vie", "sáb"];
const MONTH_LABELS = [
    "ene", "feb", "mar", "abr", "may", "jun",
    "jul", "ago", "sep", "oct", "nov", "dic",
];
const PROPOSAL_STATUS_LABELS = {
    pending: "pendiente",
    approved: "aprobada",
    rejected: "rechazada",
    expired: "expirada",
};
const NO_CALENDAR = "Sin datos del calendario todavía. El teléfono no reportó sus calendarios.";

function offsetOf(payload) {
    const value = payload && typeof payload === "object" ? payload.tzOffsetMinutes : null;
    return Number.isInteger(value) ? value : 0;
}

/** Wall-clock Date in the phone's offset, read with the UTC getters. */
function shiftedDate(iso, offsetMinutes) {
    const ms = Date.parse(String(iso ?? ""));
    if (!Number.isFinite(ms)) return null;
    return new Date(ms + offsetMinutes * 60_000);
}

function pad2(value) {
    return String(value).padStart(2, "0");
}

function calendarDayKey(iso, offsetMinutes) {
    const date = shiftedDate(iso, offsetMinutes);
    if (!date) return "?";
    return `${date.getUTCFullYear()}-${pad2(date.getUTCMonth() + 1)}-${pad2(date.getUTCDate())}`;
}

function calendarDayLabel(iso, offsetMinutes) {
    const date = shiftedDate(iso, offsetMinutes);
    if (!date) return "?";
    return `${WEEKDAY_LABELS[date.getUTCDay()]} ${date.getUTCDate()} ${MONTH_LABELS[date.getUTCMonth()]}`;
}

function calendarTime(iso, offsetMinutes) {
    const date = shiftedDate(iso, offsetMinutes);
    if (!date) return "?";
    return `${pad2(date.getUTCHours())}:${pad2(date.getUTCMinutes())}`;
}

function calendarDateTime(iso, offsetMinutes) {
    return `${calendarDayLabel(iso, offsetMinutes)} ${calendarTime(iso, offsetMinutes)}`;
}

function calendarWhen(event, offsetMinutes) {
    if (event.allDay) return `todo el día (${calendarDayLabel(event.start, offsetMinutes)})`;
    return `${calendarTime(event.start, offsetMinutes)}-${calendarTime(event.end, offsetMinutes)}`;
}

function calendarNameFor(payload, calendarId) {
    const calendars = Array.isArray(payload?.calendars) ? payload.calendars : [];
    const found = calendars.find((entry) => entry && entry.id === calendarId);
    return found && typeof found.name === "string" ? found.name : "";
}

/**
 * User-facing agenda: events grouped by local day, conflicts flagged both per
 * event and in a short summary. Neutral Latin American Spanish.
 */
export function formatCalendarAgenda(payload) {
    if (!payload || !payload.capturedAt) return NO_CALENDAR;
    const offset = offsetOf(payload);
    const events = Array.isArray(payload.events) ? [...payload.events] : [];
    const conflicts = Array.isArray(payload.conflicts) ? payload.conflicts : [];
    const conflictIds = new Set();
    for (const pair of conflicts) {
        if (pair?.a?.id) conflictIds.add(pair.a.id);
        if (pair?.b?.id) conflictIds.add(pair.b.id);
    }

    events.sort((a, b) => Date.parse(a?.start) - Date.parse(b?.start));
    const lines = [
        `Agenda: ${events.length} ${events.length === 1 ? "evento" : "eventos"} ` +
        `(capturada ${calendarDateTime(payload.capturedAt, offset)})`,
    ];
    if (events.length === 0) {
        lines.push("No hay eventos en el rango.");
        return lines.join("\n");
    }

    const groups = new Map();
    for (const event of events) {
        const key = calendarDayKey(event.start, offset);
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(event);
    }
    for (const group of groups.values()) {
        lines.push(calendarDayLabel(group[0].start, offset));
        for (const event of group) {
            const when = event.allDay ? "todo el día" : calendarWhen(event, offset);
            let line = `  ${when.padEnd(16)}  ${event.title}`;
            const calendarName = calendarNameFor(payload, event.calendarId);
            if (calendarName) line += `  [${calendarName}]`;
            if (event.location) line += ` — ${event.location}`;
            if (conflictIds.has(event.id)) line += "  (conflicto)";
            lines.push(line);
        }
    }

    if (conflicts.length > 0) {
        lines.push(`Conflictos: ${conflicts.length}`);
        for (const pair of conflicts) {
            const a = pair?.a;
            const b = pair?.b;
            if (!a || !b) continue;
            lines.push(
                `  "${a.title}" ${calendarWhen(a, offset)} choca con ` +
                `"${b.title}" ${calendarWhen(b, offset)}`,
            );
        }
    }
    return lines.join("\n");
}

/** Free slots grouped by day, in the snapshot's offset. */
export function formatCalendarFree(payload) {
    if (!payload || payload.hasSnapshot !== true) return NO_CALENDAR;
    const offset = offsetOf(payload);
    const slots = Array.isArray(payload.slots) ? payload.slots : [];
    const min = Number.isFinite(payload.minMinutes) ? payload.minMinutes : 30;
    const lines = [`Espacios libres (mínimo ${min} min):`];
    if (slots.length === 0) {
        lines.push("No hay espacios libres en el rango.");
        return lines.join("\n");
    }
    const groups = new Map();
    for (const slot of slots) {
        const key = calendarDayKey(slot.start, offset);
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(slot);
    }
    for (const group of groups.values()) {
        lines.push(calendarDayLabel(group[0].start, offset));
        for (const slot of group) {
            lines.push(
                `  ${calendarTime(slot.start, offset)}-${calendarTime(slot.end, offset)} ` +
                `(${formatDuration(slot.minutes)})`,
            );
        }
    }
    return lines.join("\n");
}

/** Confirmation of a created proposal; writes still wait for the phone. */
export function formatProposalCreated(payload) {
    const proposal = payload && typeof payload === "object" ? payload.proposal : null;
    if (!proposal || typeof proposal !== "object") {
        return "IDUPI devolvió una respuesta inesperada.";
    }
    const offset = offsetOf(payload);
    const lines = [
        `Propuesta creada: ${proposal.id}`,
        `  ${proposal.title}  ${calendarWhen(proposal, offset)}`,
    ];
    if (proposal.location) lines.push(`  Lugar: ${proposal.location}`);
    lines.push("Estado: pendiente. Se escribe solo cuando la apruebas en el teléfono.");
    const conflicts = Array.isArray(proposal.conflicts) ? proposal.conflicts : [];
    if (conflicts.length === 0) {
        lines.push("Sin conflictos.");
    } else {
        lines.push(`Conflictos: ${conflicts.length}`);
        for (const entry of conflicts) {
            lines.push(`  "${entry.title}" ${calendarWhen(entry, offset)}`);
        }
    }
    return lines.join("\n");
}

/** Proposal queue, newest first. */
export function formatCalendarProposals(payload) {
    const proposals = payload && Array.isArray(payload.proposals) ? payload.proposals : [];
    if (proposals.length === 0) return "No hay propuestas.";
    const offset = offsetOf(payload);
    const lines = [`Propuestas (${proposals.length}):`];
    for (const proposal of proposals) {
        if (!proposal || typeof proposal !== "object") continue;
        const status = PROPOSAL_STATUS_LABELS[proposal.status] || proposal.status || "?";
        const conflicts = Array.isArray(proposal.conflicts) ? proposal.conflicts.length : 0;
        let line = `  ${proposal.id}  ${status.padEnd(9)}  "${proposal.title}"  ` +
            calendarWhen(proposal, offset);
        if (conflicts > 0) line += `  (${conflicts} conflicto${conflicts === 1 ? "" : "s"})`;
        lines.push(line);
    }
    return lines.join("\n");
}

// --- health formatting (phone-health, T1) ---------------------------------

const HEALTH_DAY_MS = 86_400_000;

function formatThousands(value) {
    const rounded = Math.round(Number(value));
    if (!Number.isFinite(rounded)) return "?";
    return String(rounded).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

function formatDecimal(value) {
    const number = Number(value);
    if (!Number.isFinite(number)) return "?";
    return Number.isInteger(number) ? String(number) : number.toFixed(1);
}

function formatMeters(meters) {
    const value = Number(meters);
    if (!Number.isFinite(value)) return "?";
    return value >= 1000 ? `${(value / 1000).toFixed(1)} km` : `${Math.round(value)} m`;
}

function healthGoal(value, format) {
    return value === null || value === undefined ? "" : ` de ${format(value)}`;
}

/** Compact, neutral Spanish lines for one merged day. */
export function formatHealthMetrics(metrics) {
    const source = metrics && typeof metrics === "object" ? metrics : {};
    const lines = [];
    const add = (text) => {
        if (text) lines.push(`  ${text}`);
    };
    if (source.steps !== null && source.steps !== undefined) {
        add(`Pasos: ${formatThousands(source.steps)}${healthGoal(source.stepsGoal, formatThousands)}`);
    }
    if (source.sleepMinutes !== null && source.sleepMinutes !== undefined) {
        add(
            `Sueño: ${formatDuration(source.sleepMinutes)}` +
            healthGoal(source.sleepGoalMinutes, formatDuration),
        );
    }
    if (source.heartRateBpm !== null && source.heartRateBpm !== undefined) {
        add(`Frecuencia cardíaca: ${Math.round(source.heartRateBpm)} lpm`);
    }
    if (source.restingHeartRateBpm !== null && source.restingHeartRateBpm !== undefined) {
        add(`Frecuencia en reposo: ${Math.round(source.restingHeartRateBpm)} lpm`);
    }
    if (
        source.heartRateMin !== null && source.heartRateMin !== undefined &&
        source.heartRateMax !== null && source.heartRateMax !== undefined
    ) {
        add(`Rango cardíaco: ${Math.round(source.heartRateMin)}-${Math.round(source.heartRateMax)} lpm`);
    }
    if (source.exerciseMinutes !== null && source.exerciseMinutes !== undefined) {
        add(
            `Ejercicio: ${formatDuration(source.exerciseMinutes)}` +
            healthGoal(source.exerciseGoalMinutes, formatDuration),
        );
    }
    const calories = source.activeCaloriesKcal ?? source.caloriesKcal;
    if (calories !== null && calories !== undefined) {
        add(`Calorías: ${formatThousands(calories)} kcal`);
    }
    if (source.distanceM !== null && source.distanceM !== undefined) {
        add(`Distancia: ${formatMeters(source.distanceM)}`);
    }
    if (source.waterMl !== null && source.waterMl !== undefined) {
        add(`Agua: ${formatThousands(source.waterMl)}${healthGoal(source.waterGoalMl, formatThousands)} ml`);
    }
    if (source.weightKg !== null && source.weightKg !== undefined) {
        add(`Peso: ${formatDecimal(source.weightKg)} kg`);
    }
    if (source.spo2Pct !== null && source.spo2Pct !== undefined) {
        add(`SpO2: ${Math.round(source.spo2Pct)}%`);
    }
    if (source.stress !== null && source.stress !== undefined) {
        const label = typeof source.stressLabel === "string" && source.stressLabel
            ? ` (${source.stressLabel})`
            : "";
        add(`Estrés: ${Math.round(source.stress)}${label}`);
    }
    if (source.screenTimeMinutes !== null && source.screenTimeMinutes !== undefined) {
        let line = `Pantalla: ${formatDuration(source.screenTimeMinutes)}`;
        if (source.unlocks !== null && source.unlocks !== undefined) {
            line += ` · ${formatThousands(source.unlocks)} desbloqueos`;
        }
        add(line);
    }
    if (source.nightScreenMinutes !== null && source.nightScreenMinutes !== undefined) {
        add(`Uso nocturno: ${formatDuration(source.nightScreenMinutes)}`);
    }
    const topApps = Array.isArray(source.topApps) ? source.topApps : [];
    if (topApps.length > 0) {
        add(
            "Apps: " +
            topApps.slice(0, 4)
                .map((entry) => `${entry.label} ${formatDuration(entry.minutes)}`)
                .join(", "),
        );
    }
    const workouts = Array.isArray(source.workouts) ? source.workouts : [];
    if (workouts.length > 0) {
        add(
            "Ejercicios: " +
            workouts.slice(0, 3)
                .map((entry) => `${entry.type || "sesión"} ${formatDuration(entry.minutes || 0)}`)
                .join(", "),
        );
    }
    return lines;
}

/** Day summary: newest day first, only days that carry data. */
export function formatHealthSummary(payload, nowMs = Date.now()) {
    const days = payload && Array.isArray(payload.days) ? payload.days : [];
    const offset = Number.isInteger(payload?.tzOffsetMinutes) ? payload.tzOffsetMinutes : 0;
    const withData = days.filter(
        (entry) => entry && entry.metrics && Object.keys(entry.metrics).length > 0,
    );
    if (withData.length === 0) {
        return "Sin datos de salud todavía. Ejecuta: idupi-phone health refresh";
    }
    const shifted = new Date(nowMs + offset * 60_000);
    const today = shifted.toISOString().slice(0, 10);
    const yesterday = new Date(shifted.getTime() - HEALTH_DAY_MS).toISOString().slice(0, 10);
    const rangeDays = Number.isInteger(payload?.rangeDays) ? payload.rangeDays : days.length;
    const lines = [`Salud (${rangeDays} ${rangeDays === 1 ? "día" : "días"}):`];
    for (const entry of [...withData].reverse()) {
        const label = entry.date === today ? "Hoy" : entry.date === yesterday ? "Ayer" : entry.date;
        lines.push(`${label} (${entry.date})`);
        lines.push(...formatHealthMetrics(entry.metrics));
    }
    return lines.join("\n");
}

export function formatHealthSources(payload) {
    const sources = payload && Array.isArray(payload.sources) ? payload.sources : [];
    const apps = payload && Array.isArray(payload.installedApps) ? payload.installedApps : [];
    const future = payload && Array.isArray(payload.futureSources) ? payload.futureSources : [];
    const lines = ["Fuentes de salud:"];
    if (sources.length === 0) {
        lines.push("  Sin fuentes registradas.");
    }
    for (const source of sources) {
        const id = source.id || source.source || "?";
        const label = source.label && source.label !== id ? ` (${source.label})` : "";
        const last = source.lastCaptureAt
            ? `última captura ${source.lastCaptureAt}`
            : "sin datos";
        lines.push(`  ${id}${label}: ${last}`);
    }
    if (future.length > 0) {
        lines.push("Fuentes futuras:");
        for (const source of future) {
            const state = source.enabled === true
                ? source.configured === true ? "habilitada" : "habilitada sin configurar"
                : "deshabilitada";
            lines.push(`  ${source.id} (${source.label || source.id}): ${state}`);
        }
    }
    if (apps.length > 0) {
        lines.push("Apps de salud en el teléfono:");
        for (const app of apps) {
            const label = app.label ? ` — ${app.label}` : "";
            const route = app.route ? ` (${app.route})` : "";
            lines.push(`  ${app.packageName}${label}${route}`);
        }
    }
    return lines.join("\n");
}

export function formatHealthRefresh(job) {
    if (!job || typeof job !== "object") return "IDUPI devolvió una respuesta inesperada.";
    const statusLabels = {
        queued: "en cola",
        running: "en curso",
        done: "listo",
        failed: "falló",
    };
    const lines = [`Actualización de salud: ${statusLabels[job.status] || job.status || "?"}`];
    if (job.id) lines.push(`  Job: ${job.id}`);
    for (const result of Array.isArray(job.results) ? job.results : []) {
        const source = result.source || "?";
        if (result.ok) {
            const detail = result.sourceApp ? ` (${result.sourceApp})` : "";
            lines.push(`  ${source}: ${result.metrics || 0} métricas${detail}`);
        } else {
            lines.push(`  ${source}: no se pudo leer (${result.reason || "error"})`);
        }
    }
    if (job.status === "queued" || job.status === "running") {
        lines.push("  Sigue en curso; usa --wait para esperar el resultado.");
    }
    if (job.error) lines.push(`  Detalle: ${job.error}`);
    return lines.join("\n");
}

const DIGEST_STATUS_LABELS = {
    ready: "listo",
    analyzing: "analizando",
    failed: "falló",
};

/**
 * Daily digest as the owner reads it: the summary, highlights, recommendations
 * and agenda notes, with the "not medical advice" footer the card also shows.
 */
export function formatHealthDigest(digest) {
    if (!digest || typeof digest !== "object") return "IDUPI devolvió una respuesta inesperada.";
    const date = digest.date || "hoy";
    const status = DIGEST_STATUS_LABELS[digest.status] || digest.status || "?";
    const lines = [`Resumen de salud (${date}): ${status}`];
    if (digest.status === "analyzing") {
        lines.push("El agente todavía está escribiendo el resumen.");
    }
    if (digest.error) lines.push(`  Error: ${digest.error}`);
    if (digest.summary) {
        lines.push("");
        lines.push(digest.summary);
    }
    const highlights = Array.isArray(digest.highlights) ? digest.highlights : [];
    if (highlights.length > 0) {
        lines.push("");
        lines.push("Destacados:");
        for (const entry of highlights) lines.push(`  - ${entry}`);
    }
    const recommendations = Array.isArray(digest.recommendations) ? digest.recommendations : [];
    if (recommendations.length > 0) {
        lines.push("");
        lines.push("Recomendaciones:");
        for (const entry of recommendations) lines.push(`  - ${entry}`);
    }
    const agendaNotes = Array.isArray(digest.agendaNotes) ? digest.agendaNotes : [];
    if (agendaNotes.length > 0) {
        lines.push("");
        lines.push("Agenda:");
        for (const entry of agendaNotes) lines.push(`  - ${entry}`);
    }
    if (digest.available === false) {
        lines.push("");
        lines.push("Sin datos de salud todavía: ejecuta idupi-phone health refresh.");
    }
    lines.push("");
    lines.push("No es consejo médico.");
    return lines.join("\n");
}

export function formatHealthAdb(payload) {
    if (!payload || typeof payload !== "object") return "IDUPI devolvió una respuesta inesperada.";
    const adb = payload.adb || {};
    const scrcpy = payload.scrcpy || {};
    const lines = [];
    lines.push(`ADB: ${adb.found === true ? "encontrado" : "no encontrado"}${adb.path ? ` (${adb.path})` : ""}`);
    if (scrcpy.found === true) {
        const version = scrcpy.version ? ` ${scrcpy.version}` : "";
        lines.push(`scrcpy:${version} (${scrcpy.path})`);
    } else {
        lines.push("scrcpy: no encontrado (instálalo con winget Genymobile.scrcpy)");
    }
    if (payload.connected === true) {
        lines.push(`Conexión: ${payload.serial}${payload.via ? ` (${payload.via})` : ""}`);
    } else {
        lines.push("Conexión: sin teléfono conectado");
    }
    const targets = Array.isArray(payload.targets) ? payload.targets : [];
    if (targets.length > 0) {
        lines.push("Objetivos guardados:");
        for (const target of targets) {
            const model = target.model ? ` · ${target.model}` : "";
            const last = target.lastConnectedAt ? ` · ${target.lastConnectedAt}` : "";
            lines.push(`  ${target.serial} · ${target.network}${model}${last}`);
        }
    }
    return lines.join("\n");
}

// --- process output --------------------------------------------------------

function serverUnreachable() {
    return SERVER_UNREACHABLE + port + SERVER_UNREACHABLE_HINT;
}

function writeOut(text) {
    try {
        writeSync(1, Buffer.from(text, "utf8"));
    } catch {
        try {
            process.stdout.write(text);
        } catch {
            /* stdout is gone: nothing left to say */
        }
    }
}

function fail(message) {
    try {
        writeSync(2, Buffer.from(message + "\n", "utf8"));
    } catch {
        try {
            process.stderr.write(message + "\n");
        } catch {
            /* stderr is gone: nothing left to say */
        }
    }
    process.exit(1);
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

// --- HTTP client -----------------------------------------------------------

/**
 * Header that carries the running bubble turn's secret, when the server
 * launched this CLI for one. The secret comes only from the child env the
 * runner set; screen text can never mint or extend it. It is never printed.
 */
export function turnGrantHeader(env = process.env) {
    const grant = env && typeof env === "object" ? env[TURN_GRANT_ENV] : undefined;
    if (typeof grant !== "string" || grant.length === 0) return {};
    return { [TURN_GRANT_HEADER]: grant };
}

function apiRequest(method, path, body, timeoutMs = 10_000, extraHeaders = null) {
    return new Promise((resolve, reject) => {
        const payload = body === undefined
            ? null
            : Buffer.from(JSON.stringify(body), "utf8");
        const headers = {
            Authorization: `Bearer ${token}`,
            ...(extraHeaders && typeof extraHeaders === "object" ? extraHeaders : {}),
        };
        if (payload) {
            headers["Content-Type"] = "application/json";
            headers["Content-Length"] = String(payload.length);
        }
        const req = http.request(
            // agent: false keeps one request/response from holding the process
            // open through the global keep-alive pool.
            { host: HOST, port, method, path, headers, agent: false },
            (res) => {
                const chunks = [];
                res.on("data", (chunk) => chunks.push(chunk));
                res.on("error", reject);
                res.on("end", () => {
                    const text = Buffer.concat(chunks).toString("utf8");
                    let json = null;
                    if (text) {
                        try {
                            json = JSON.parse(text);
                        } catch {
                            /* non-JSON error bodies are reported by status */
                        }
                    }
                    resolve({ status: res.statusCode, json, text });
                });
            },
        );
        // Long polls take up to 30 s; every other call should answer fast.
        req.setTimeout(timeoutMs, () => req.destroy(new Error("timeout")));
        req.on("error", reject);
        if (payload) req.write(payload);
        req.end();
    });
}

async function fetchStatus(json) {
    let res;
    try {
        res = await apiRequest("GET", "/api/v1/phone/status");
    } catch {
        fail(serverUnreachable());
    }
    if (res.status === 401) fail(TOKEN_REJECTED);
    if (res.status !== 200) fail(`IDUPI respondió HTTP ${res.status}.`);
    if (json) {
        writeOut(JSON.stringify(res.json, null, 2) + "\n");
        return;
    }
    writeOut(formatPhoneStatus(res.json) + "\n");
}

async function fetchApprovals(json) {
    let res;
    try {
        res = await apiRequest("GET", "/api/v1/phone/approvals?state=pending");
    } catch {
        fail(serverUnreachable());
    }
    if (res.status === 401) fail(TOKEN_REJECTED);
    if (res.status !== 200) fail(`IDUPI respondió HTTP ${res.status}.`);
    if (json) {
        writeOut(JSON.stringify(res.json, null, 2) + "\n");
        return;
    }
    writeOut(formatApprovals(res.json) + "\n");
}

async function enqueue(type, args) {
    let res;
    try {
        res = await apiRequest(
            "POST",
            "/api/v1/phone/commands",
            { type, args },
            10_000,
            turnGrantHeader(process.env),
        );
    } catch {
        fail(serverUnreachable());
    }
    if (res.status === 401) fail(TOKEN_REJECTED);
    if (res.status === 400) {
        fail(`No se pudo enviar el comando: ${res.json?.error || "inválido"}`);
    }
    if (res.status !== 201 || !res.json?.command?.id) {
        fail(`IDUPI respondió HTTP ${res.status}.`);
    }
    return res.json.command;
}

async function waitForCommand(id) {
    let deadline = Date.now() + DEADLINE_MS;
    for (;;) {
        const remaining = deadline - Date.now();
        if (remaining <= 0) return null;
        const wait = Math.min(
            WAIT_BUDGET_SECONDS,
            Math.max(1, Math.ceil(remaining / 1000)),
        );
        const path =
            `/api/v1/phone/commands/${encodeURIComponent(id)}?wait=${wait}`;
        let res;
        try {
            res = await apiRequest("GET", path, undefined, HTTP_TIMEOUT_MS);
        } catch {
            fail(serverUnreachable());
        }
        if (res.status === 401) fail(TOKEN_REJECTED);
        if (res.status === 404) fail("El comando ya no existe en el servidor.");
        if (res.status !== 200) fail(`IDUPI respondió HTTP ${res.status}.`);
        const command = res.json?.command;
        if (!command) fail("IDUPI devolvió una respuesta inesperada.");
        if (command.state === "done" || command.state === "failed" || command.state === "expired") {
            return command;
        }
        if (command.state === "awaiting_approval") {
            // The approval owns a 10 min TTL, longer than the command budget:
            // keep polling until the owner decides or the approval expires.
            const approvalMs = typeof command.approvalExpiresAt === "string"
                ? Date.parse(command.approvalExpiresAt)
                : Number.NaN;
            if (Number.isFinite(approvalMs)) {
                const approvalDeadline = approvalMs + DEADLINE_MS;
                if (approvalDeadline > deadline) deadline = approvalDeadline;
            }
        }
        await sleep(200);
    }
}

function requireFinished(command) {
    if (!command) fail(NO_RESPONSE);
    if (command.state === "expired") fail(EXPIRED);
    if (command.state === "failed") fail(failureMessage(command));
    return command;
}

/** Local midnight today plus `days` days: the default agenda range. */
function calendarRange(days) {
    const nowDate = new Date();
    const from = new Date(nowDate.getFullYear(), nowDate.getMonth(), nowDate.getDate());
    const to = new Date(from.getTime() + days * CALENDAR_DAY_MS);
    return { from: from.toISOString(), to: to.toISOString() };
}

/** Calendar API call with the same error mapping as the rest of the CLI. */
async function calendarRequest(method, path, body) {
    let res;
    try {
        res = await apiRequest(method, path, body);
    } catch {
        fail(serverUnreachable());
    }
    if (res.status === 401) fail(TOKEN_REJECTED);
    if (res.status >= 400) {
        const detail = res.json && typeof res.json.error === "string" ? `: ${res.json.error}` : "";
        fail(`IDUPI respondió HTTP ${res.status}${detail}.`);
    }
    return res;
}

async function runCalendar(parsed) {
    if (parsed.action === "agenda") {
        const { from, to } = calendarRange(parsed.days);
        const path = "/api/v1/phone/calendar" +
            `?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`;
        const res = await calendarRequest("GET", path);
        if (parsed.json) {
            writeOut(JSON.stringify(res.json, null, 2) + "\n");
            return;
        }
        writeOut(formatCalendarAgenda(res.json) + "\n");
        return;
    }

    if (parsed.action === "free") {
        const { from, to } = calendarRange(parsed.days);
        const path = "/api/v1/phone/calendar/free" +
            `?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&min=${parsed.min}`;
        const res = await calendarRequest("GET", path);
        writeOut(formatCalendarFree(res.json) + "\n");
        return;
    }

    if (parsed.action === "propose") {
        const body = {
            title: parsed.title,
            start: parsed.start,
            allDay: parsed.allDay,
            source: "idupi-phone",
        };
        if (parsed.end) body.end = parsed.end;
        if (parsed.location) body.location = parsed.location;
        if (parsed.notes) body.notes = parsed.notes;
        const res = await calendarRequest("POST", "/api/v1/phone/calendar/proposals", body);
        writeOut(formatProposalCreated(res.json) + "\n");
        return;
    }

    if (parsed.action === "proposals") {
        const res = await calendarRequest("GET", "/api/v1/phone/calendar/proposals?status=pending");
        writeOut(formatCalendarProposals(res.json) + "\n");
        return;
    }

    fail("Comando de calendario no reconocido.");
}

// --- health API calls (phone-health, T1) ----------------------------------

async function healthRequest(method, path, body) {
    let res;
    try {
        res = await apiRequest(method, path, body, 90_000);
    } catch {
        fail(serverUnreachable());
    }
    if (res.status === 401) fail(TOKEN_REJECTED);
    if (res.status >= 400) {
        const detail = res.json && typeof res.json.error === "string" ? `: ${res.json.error}` : "";
        fail(`IDUPI respondió HTTP ${res.status}${detail}.`);
    }
    return res;
}

async function waitHealthJob(id) {
    const deadline = Date.now() + 120_000;
    for (;;) {
        const res = await healthRequest("GET", `/api/v1/health/refresh/${encodeURIComponent(id)}`);
        const job = res.json?.job;
        if (!job) fail("IDUPI devolvió una respuesta inesperada.");
        if (job.status === "done" || job.status === "failed") return job;
        if (Date.now() >= deadline) return job;
        await sleep(1500);
    }
}

/** Polls the digest until the agent finishes or the 120 s budget runs out. */
async function waitHealthDigest() {
    const deadline = Date.now() + 120_000;
    for (;;) {
        const res = await healthRequest("GET", "/api/v1/health/digest");
        const digest = res.json;
        if (!digest || typeof digest !== "object") {
            fail("IDUPI devolvió una respuesta inesperada.");
        }
        if (digest.status !== "analyzing") return digest;
        if (Date.now() >= deadline) return digest;
        await sleep(1500);
    }
}

async function runHealth(parsed) {
    if (parsed.action === "summary") {
        const res = await healthRequest("GET", `/api/v1/health/summary?days=${parsed.days}`);
        if (parsed.json) {
            writeOut(JSON.stringify(res.json, null, 2) + "\n");
            return;
        }
        writeOut(formatHealthSummary(res.json) + "\n");
        return;
    }

    if (parsed.action === "sources") {
        const res = await healthRequest("GET", "/api/v1/health/sources");
        if (parsed.json) {
            writeOut(JSON.stringify(res.json, null, 2) + "\n");
            return;
        }
        writeOut(formatHealthSources(res.json) + "\n");
        return;
    }

    if (parsed.action === "refresh") {
        const res = await healthRequest("POST", "/api/v1/health/refresh", {});
        let job = res.json?.job;
        if (!job) fail("IDUPI devolvió una respuesta inesperada.");
        if (parsed.wait) job = await waitHealthJob(job.id);
        if (parsed.json) {
            writeOut(JSON.stringify(job, null, 2) + "\n");
            return;
        }
        writeOut(formatHealthRefresh(job) + "\n");
        return;
    }

    if (parsed.action === "digest") {
        let digest;
        if (parsed.refresh) {
            const res = await healthRequest("POST", "/api/v1/health/digest", { refresh: true });
            digest = res.json;
            if (!digest || typeof digest !== "object") {
                fail("IDUPI devolvió una respuesta inesperada.");
            }
            if (digest.status === "analyzing") digest = await waitHealthDigest();
        } else {
            const res = await healthRequest("GET", "/api/v1/health/digest");
            digest = res.json;
            if (!digest || typeof digest !== "object") {
                fail("IDUPI devolvió una respuesta inesperada.");
            }
        }
        if (parsed.json) {
            writeOut(JSON.stringify(digest, null, 2) + "\n");
            return;
        }
        writeOut(formatHealthDigest(digest) + "\n");
        return;
    }

    if (parsed.action === "adb") {
        let res;
        if (parsed.adbAction === "pair") {
            await healthRequest("POST", "/api/v1/health/adb/pair", {
                hostPort: parsed.hostPort,
                code: parsed.code,
            });
            writeOut(`Emparejado con ${parsed.hostPort}. Ahora conecta: ` +
                `idupi-phone health adb connect <host:puerto>\n`);
            return;
        }
        if (parsed.adbAction === "connect") {
            const connected = await healthRequest("POST", "/api/v1/health/adb/connect", {
                hostPort: parsed.hostPort,
            });
            const serial = connected.json?.serial || parsed.hostPort;
            writeOut(`Conectado: ${serial}\n`);
            return;
        }
        res = await healthRequest("GET", "/api/v1/health/adb");
        if (parsed.json) {
            writeOut(JSON.stringify(res.json, null, 2) + "\n");
            return;
        }
        writeOut(formatHealthAdb(res.json) + "\n");
        return;
    }

    fail("Comando de salud no reconocido.");
}

function actionFor(parsed) {    switch (parsed.command) {
        case "tap":
        case "long_press":
            return { type: parsed.command, args: parsed.target };
        case "type": {
            const args = { text: parsed.text };
            if (parsed.submit) args.submit = true;
            return { type: "type", args };
        }
        case "scroll":
            return { type: "scroll", args: { direction: parsed.direction } };
        case "global":
            return { type: "global", args: { action: parsed.action } };
        case "open":
            return { type: "open", args: { app: parsed.app } };
        case "wait":
            return { type: "wait", args: { ms: parsed.ms } };
        case "screen":
            return { type: "screen", args: {} };
        default:
            return null;
    }
}

async function main() {
    const parsed = parseArgs(process.argv.slice(2));
    if (parsed.command === "help") {
        if (parsed.error) {
            writeOut(parsed.error + "\n\n" + USAGE);
            process.exit(1);
        }
        writeOut(USAGE);
        return;
    }

    try {
        port = resolvePort(process.env);
    } catch {
        fail("IDUPI_PORT inválido.");
    }
    token = resolveToken({
        env: process.env,
        tokenPath: TOKEN_PATH,
        readFile: readFileSync,
    });
    if (!token) {
        fail("Falta el token: define IDUPI_TOKEN o crea .idupi-token.");
    }

    if (parsed.command === "status") {
        await fetchStatus(parsed.json);
        return;
    }

    if (parsed.command === "approvals") {
        await fetchApprovals(parsed.json);
        return;
    }

    if (parsed.command === "log") {
        let res;
        try {
            res = await apiRequest("GET", "/api/v1/phone/commands?limit=20");
        } catch {
            fail(serverUnreachable());
        }
        if (res.status === 401) fail(TOKEN_REJECTED);
        if (res.status !== 200) fail(`IDUPI respondió HTTP ${res.status}.`);
        writeOut(formatCommandList(res.json?.commands) + "\n");
        return;
    }

    if (parsed.command === "calendar") {
        await runCalendar(parsed);
        return;
    }

    if (parsed.command === "health") {
        await runHealth(parsed);
        return;
    }

    const action = actionFor(parsed);
    if (!action) fail("Comando no reconocido.");

    const created = await enqueue(action.type, action.args);
    if (created.state === "awaiting_approval") {
        writeOut(formatApprovalWait(created) + "\n");
    }
    const finished = requireFinished(await waitForCommand(created.id));

    if (parsed.command === "screen") {
        if (parsed.json) {
            writeOut(JSON.stringify(finished.result, null, 2) + "\n");
            return;
        }
        const text = finished.result && typeof finished.result === "object"
            ? finished.result.text
            : null;
        if (typeof text !== "string" || text.length === 0) {
            fail("El teléfono no devolvió el texto de la pantalla.");
        }
        writeOut(text.endsWith("\n") ? text : text + "\n");
        return;
    }

    writeOut(formatCommandResult(finished) + "\n");
}

const invokedDirectly = process.argv[1]
    ? import.meta.url === pathToFileURL(resolvePath(process.argv[1])).href
    : false;

if (invokedDirectly) {
    main().catch((err) => {
        fail(`Error inesperado: ${err?.message || String(err)}`);
    });
}
