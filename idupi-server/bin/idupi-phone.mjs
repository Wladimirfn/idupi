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
  idupi-phone log                       Últimos comandos
  idupi-phone help                      Muestra esta ayuda

El control debe estar activo en la app: permiso de accesibilidad e interruptor
maestro. Los comandos expiran a los 60 s si el teléfono no los toma a tiempo.
`;

const SERVER_UNREACHABLE =
    `No se pudo conectar con IDUPI en ${HOST}:`;
const SERVER_UNREACHABLE_HINT =
    ". ¿Está corriendo? (idupi-server/scripts/start-server.ps1)";
const TOKEN_REJECTED = "Token rechazado: revisá .idupi-token.";
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
        return { command: "help", error: "Falta el comando. Probá: idupi-phone status" };
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

    if (head === "calendar") return parseCalendarArgs(rest);

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

    return lines.join("\n");
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
            return `El teléfono falló: ${command.error || "sin detalle"}`;
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

function apiRequest(method, path, body, timeoutMs = 10_000) {
    return new Promise((resolve, reject) => {
        const payload = body === undefined
            ? null
            : Buffer.from(JSON.stringify(body), "utf8");
        const headers = { Authorization: `Bearer ${token}` };
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

async function enqueue(type, args) {
    let res;
    try {
        res = await apiRequest("POST", "/api/v1/phone/commands", { type, args });
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
    const deadline = Date.now() + DEADLINE_MS;
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
        await sleep(200);
    }
}

function requireFinished(command) {
    if (!command) fail(NO_RESPONSE);
    if (command.state === "expired") fail(EXPIRED);
    if (command.state === "failed") {
        fail(`El teléfono falló: ${command.error || "sin detalle"}`);
    }
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

    const action = actionFor(parsed);
    if (!action) fail("Comando no reconocido.");

    const created = await enqueue(action.type, action.args);
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
