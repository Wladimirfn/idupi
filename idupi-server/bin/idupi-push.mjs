#!/usr/bin/env node
// idupi-push: publishes an APK built on the PC so the phone can install it
// (apk-delivery, T1). Thin I/O shell: argument parsing and formatting live in
// this file as exported pure helpers (a dedicated lib module was outside the
// T1 file set), while the actual delivery work is the server's
// (lib/delivery-routes.mjs). Token and port resolution are REUSED from
// lib/term-cli.mjs so both CLIs behave identically.
//
// `--install` is the PC-side install path (phone-shell, T7b): after publishing,
// the APK is installed over the already-paired wireless adb, and then the
// accessibility rebind kick runs. The default (publish only) keeps the original
// notification-and-tap flow unchanged.

import { spawn } from "node:child_process";
import { readFileSync, writeSync } from "node:fs";
import http from "node:http";
import { basename, dirname, resolve as resolvePath } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { HOST, resolvePort, resolveToken } from "../lib/term-cli.mjs";
import { createAdbConnection } from "../lib/adb-connection.mjs";
import { createAccessibilityKick } from "../lib/accessibility-kick.mjs";

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
// Same layout as bin/idupi-term.mjs and server-auth.mjs: the token file lives
// at the repository root, two levels above idupi-server/bin/.
const TOKEN_PATH = resolvePath(SCRIPT_DIR, "..", "..", ".idupi-token");
// The adb target list lives next to the server (`data/adb-targets.json`), the
// same file the health reader uses, so a paired phone is already reachable.
const ADB_DATA_DIR = resolvePath(SCRIPT_DIR, "..", "data");

const USAGE = `idupi-push — entrega APKs de la PC al teléfono

Uso:
  idupi-push <archivo.apk>            Publica el APK para el teléfono
  idupi-push <archivo.apk> --install  Lo publica y además lo instala por adb,
                                      con el reajuste de accesibilidad después
  idupi-push ls                       Lista las entregas publicadas
  idupi-push help                     Muestra esta ayuda
`;

const SERVER_UNREACHABLE =
    `No se pudo conectar con IDUPI en ${HOST}:`;
const SERVER_UNREACHABLE_HINT =
    ". ¿Está corriendo? (idupi-server/scripts/start-server.ps1)";
const TOKEN_REJECTED = "Token rechazado: revisa .idupi-token.";

let port = 8788;
let token = "";

/**
 * Parses the command line. `argv` excludes `node` and the script path.
 *
 * @param {string[]} [argv]
 * @returns {{ command: "push", file: string, install: boolean } | { command: "list" } | { command: "help", error?: string }}
 */
export function parseArgs(argv = []) {
    const args = Array.isArray(argv) ? [...argv] : [];
    if (args.length === 0) {
        return { command: "help", error: "Falta el archivo APK." };
    }

    const [head, ...rest] = args;
    if (head === "ls" || head === "list") {
        if (rest.length > 0) {
            return { command: "help", error: "Uso: idupi-push ls" };
        }
        return { command: "list" };
    }
    if (head === "help" || head === "--help" || head === "-h") {
        return { command: "help" };
    }
    if (head.startsWith("-")) {
        return { command: "help", error: `Opción desconocida: ${head}` };
    }
    // `--install` is the only flag; everything else after the path is a typo.
    let install = false;
    const extra = [];
    for (const token of rest) {
        if (token === "--install") {
            install = true;
            continue;
        }
        extra.push(token);
    }
    if (extra.length > 0) {
        return { command: "help", error: "Uso: idupi-push <archivo.apk> [--install]" };
    }
    return { command: "push", file: head, install };
}

/**
 * Melody-free size label for the confirmation line: one decimal, binary MB.
 *
 * @param {number} bytes
 * @returns {string}
 */
export function formatSize(bytes) {
    const value = Number(bytes);
    if (!Number.isFinite(value) || value < 0) return "0.0";
    return (value / (1024 * 1024)).toFixed(1);
}

/**
 * @param {Array<{id: string, name: string, size: number, createdAt: string}>} deliveries
 * @returns {string}
 */
export function formatDeliveryList(deliveries) {
    if (!Array.isArray(deliveries) || deliveries.length === 0) {
        return "No hay entregas.";
    }
    const lines = [`Entregas (${deliveries.length}):`];
    for (const delivery of deliveries) {
        lines.push(
            `  ${delivery.id}  ${delivery.name}  ` +
            `${formatSize(delivery.size)} MB  ${delivery.createdAt}`,
        );
    }
    return lines.join("\n");
}

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

// --- HTTP client -----------------------------------------------------------

function apiRequest(method, path, body) {
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
        // A hung server must not hang a push forever.
        req.setTimeout(20_000, () => req.destroy(new Error("timeout")));
        req.on("error", reject);
        if (payload) req.write(payload);
        req.end();
    });
}

async function pushFile(file) {
    let res;
    try {
        res = await apiRequest("POST", "/api/v1/deliveries", { path: file });
    } catch {
        fail(serverUnreachable());
    }
    if (res.status === 401) fail(TOKEN_REJECTED);
    if (res.status === 400) {
        const detail = res.json?.error || res.json?.message || "archivo inválido";
        fail(`No se pudo enviar: ${detail}`);
    }
    if (res.status !== 201) fail(`IDUPI respondió HTTP ${res.status}.`);
    const entry = res.json || {};
    const name = entry.name || basename(file);
    writeOut(
        `APK enviada al teléfono: ${name} ` +
        `(${formatSize(entry.size)} MB, id ${entry.id})\n`,
    );
}

async function listDeliveries() {
    let res;
    try {
        res = await apiRequest("GET", "/api/v1/deliveries");
    } catch {
        fail(serverUnreachable());
    }
    if (res.status === 401) fail(TOKEN_REJECTED);
    if (res.status !== 200) fail(`IDUPI respondió HTTP ${res.status}.`);
    writeOut(formatDeliveryList(res.json?.deliveries) + "\n");
}

// --- PC-side install + accessibility kick (phone-shell, T7b) ---------------

/**
 * Installs an APK over the already-paired wireless adb, then runs the
 * accessibility rebind kick (phone-shell, T7b).
 *
 * The install is the equivalent of `adb install -r`: it replaces the package
 * keeping its data, and a signed APK with the same key upgrades in place. The
 * kick follows because on the owner's phone an `install -r` of IDUPI leaves its
 * accessibility service enabled but unbound until some UiAutomation connects
 * and disconnects.
 *
 * Both steps are best effort: a phone that is not reachable, an adb that is
 * missing or a failed install is reported and the already-published delivery
 * still stands for the notification-and-tap path.
 *
 * @param {string} file absolute APK path
 * @param {{
 *   connection?: ReturnType<typeof createAdbConnection>,
 *   kick?: ReturnType<typeof createAccessibilityKick>,
 *   run?: (command: string, args: string[], options?: object) => Promise<unknown>,
 * }} [deps] injected for tests; production resolves the real adb connection
 * @returns {Promise<{ ok: boolean, serial?: string, reason?: string, kicked?: boolean }>}
 */
export async function installAndKick(file, deps = {}) {
    const connection = deps.connection ?? createAdbConnection({ dataDir: ADB_DATA_DIR });
    const kick = deps.kick ?? createAccessibilityKick({ connection });
    const run = deps.run ?? defaultAdbRun;

    const link = await connection.ensureConnected();
    if (!link || link.ok !== true || typeof link.serial !== "string") {
        const reason = (link && link.reason) || "adb_unavailable";
        writeOut(`No se pudo instalar por adb: ${reason}.\n`);
        return { ok: false, reason };
    }

    let adbPath = "adb";
    try {
        const resolved = connection.paths();
        if (resolved?.adb?.path) adbPath = resolved.adb.path;
    } catch {
        // Fall back to the bare name; the install failure is reported below.
    }

    try {
        await run(adbPath, ["-s", link.serial, "install", "-r", file], { timeout: 120_000 });
    } catch (err) {
        const detail = `${err?.stdout ?? ""}${err?.stderr ?? ""}`.trim() ||
            err?.message || "adb install falló";
        writeOut(`La instalación por adb falló: ${detail}\n`);
        return { ok: false, serial: link.serial, reason: "install_failed" };
    }
    writeOut(`Instalada por adb en ${link.serial}.\n`);

    const outcome = await kick.kick({ serial: link.serial });
    writeOut(
        outcome.ok
            ? "Reajuste de accesibilidad enviado.\n"
            : `Reajuste de accesibilidad omitido (${outcome.reason || "sin detalle"}).\n`,
    );
    return { ok: true, serial: link.serial, kicked: outcome.ok === true };
}

function defaultAdbRun(command, args, options = {}) {
    return new Promise((resolve, reject) => {
        const child = spawn(command, args, { windowsHide: true });
        let stdout = "";
        let stderr = "";
        const timer = setTimeout(() => child.kill(), options.timeout ?? 120_000);
        child.stdout.on("data", (chunk) => {
            stdout += chunk.toString("utf8");
        });
        child.stderr.on("data", (chunk) => {
            stderr += chunk.toString("utf8");
        });
        child.on("error", (err) => {
            clearTimeout(timer);
            reject(err);
        });
        child.on("close", (code) => {
            clearTimeout(timer);
            if (code === 0) {
                resolve({ stdout, stderr });
            } else {
                const err = new Error(`adb exited with ${code}`);
                err.stdout = stdout;
                err.stderr = stderr;
                reject(err);
            }
        });
    });
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

    if (parsed.command === "list") {
        await listDeliveries();
        return;
    }

    const absolute = resolvePath(process.cwd(), parsed.file);
    await pushFile(absolute);
    if (parsed.install) await installAndKick(absolute);
}

const invokedDirectly = process.argv[1]
    ? import.meta.url === pathToFileURL(resolvePath(process.argv[1])).href
    : false;

if (invokedDirectly) {
    main().catch((err) => {
        fail(`Error inesperado: ${err?.message || String(err)}`);
    });
}
