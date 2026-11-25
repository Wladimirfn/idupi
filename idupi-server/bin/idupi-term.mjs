#!/usr/bin/env node
// idupi-term: Windows Terminal client for the shared IDUPI PTYs
// (live-shared-terminals, T3). It attaches to the SAME live PTY the Android
// app and the browser page use, like `tmux attach`: stdin is forwarded as raw
// bytes, the chunked stream is written to stdout, and Ctrl+] detaches without
// killing the terminal.
//
// This file is the thin I/O shell. Parsing, token/ref resolution, list
// rendering, detach-key splitting, UTF-8 chunking and the backoff schedule
// live in ../lib/term-cli.mjs so they run under node:test without a TTY.

import { spawn } from "node:child_process";
import { readFileSync, writeSync } from "node:fs";
import http from "node:http";
import { dirname, resolve as resolvePath } from "node:path";
import readline from "node:readline";
import { fileURLToPath } from "node:url";

import {
    DEFAULT_COLS,
    DEFAULT_ROWS,
    HOST,
    INPUT_FLUSH_MS,
    MAX_INPUT_CHUNK_BYTES,
    RECONNECT_MIN_MS,
    RESIZE_DEBOUNCE_MS,
    chunkUtf8,
    createInputDecoder,
    formatExitMessage,
    formatTerminalList,
    nextBackoff,
    parseArgs,
    parseSelection,
    resolvePort,
    resolveRef,
    resolveToken,
    shortId,
    splitDetachKey,
} from "../lib/term-cli.mjs";

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
// Same layout as server-auth.mjs: the token file lives at the repository root,
// two levels above idupi-server/bin/.
const TOKEN_PATH = resolvePath(SCRIPT_DIR, "..", "..", ".idupi-token");

const USAGE = `idupi-term — terminales compartidas de IDUPI

Uso:
  idupi-term                  Lista y conecta (crea una si no hay vivas)
  idupi-term ls               Lista las terminales
  idupi-term new [--cwd DIR]  Crea una terminal y se conecta
  idupi-term attach <ref>     Conecta a una terminal (id, prefijo o número)
  idupi-term web              Abre la vista web en el navegador
  idupi-term help             Muestra esta ayuda

En una sesión conectada: Ctrl+] desconecta (la terminal sigue viva).
`;

const SERVER_UNREACHABLE =
    `No se pudo conectar con IDUPI en ${HOST}:`;
const SERVER_UNREACHABLE_HINT =
    ". ¿Está corriendo? (idupi-server/scripts/start-server.ps1)";
const TOKEN_REJECTED = "Token rechazado: revisa .idupi-token.";

let port = 8788;
let token = "";
let activeId = null;
let detaching = false;
let restored = false;
let resizeTimer = null;

// --- console hygiene -------------------------------------------------------

function restoreConsole() {
    if (restored) return;
    restored = true;
    try {
        if (process.stdin.isTTY) process.stdin.setRawMode(false);
    } catch {
        /* the console may already be gone */
    }
    if (process.stdout.isTTY) {
        try {
            process.stdout.write("\x1b[?25h\x1b[0m");
        } catch {
            /* stdout closed: nothing left to restore */
        }
    }
}

function fatal(message) {
    restoreConsole();
    writeAndExit(process.stderr, message + "\n", 1);
}

// process.exit() drops pending asynchronous pipe writes, and callers keep
// running until the event loop drains. A synchronous fd write both flushes the
// message and lets the caller terminate immediately afterwards.
function writeAndExit(stream, text, code) {
    try {
        writeSync(stream.fd, Buffer.from(text, "utf8"));
    } catch {
        try {
            stream.write(text);
        } catch {
            /* the stream is already gone: nothing left to say */
        }
    }
    process.exit(code);
}

function serverUnreachable() {
    return SERVER_UNREACHABLE + port + SERVER_UNREACHABLE_HINT;
}

function dimension(value, fallback) {
    return Number.isInteger(value) && value >= 1 && value <= 1000 ? value : fallback;
}

function consoleSize() {
    return {
        cols: dimension(process.stdout.columns, DEFAULT_COLS),
        rows: dimension(process.stdout.rows, DEFAULT_ROWS),
    };
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
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
            { host: HOST, port, method, path, headers },
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
        // A hung server must not hang a detach or a list forever.
        req.setTimeout(10_000, () => req.destroy(new Error("timeout")));
        req.on("error", reject);
        if (payload) req.write(payload);
        req.end();
    });
}

async function listTerminals() {
    let res;
    try {
        res = await apiRequest("GET", "/api/v1/pty");
    } catch {
        fatal(serverUnreachable());
    }
    if (res.status === 401) fatal(TOKEN_REJECTED);
    if (res.status !== 200) fatal(`IDUPI respondió HTTP ${res.status}.`);
    return Array.isArray(res.json?.terminals) ? res.json.terminals : [];
}

async function fetchInfo(id) {
    const res = await apiRequest("GET", "/api/v1/pty");
    if (res.status === 401) fatal(TOKEN_REJECTED);
    if (res.status !== 200) {
        throw new Error(`IDUPI respondió HTTP ${res.status}`);
    }
    const terminals = Array.isArray(res.json?.terminals) ? res.json.terminals : [];
    return terminals.find((info) => info.id === id) || null;
}

async function createTerminal(cwd) {
    const { cols, rows } = consoleSize();
    const body = { cols, rows };
    if (cwd) body.cwd = cwd;
    let res;
    try {
        res = await apiRequest("POST", "/api/v1/pty", body);
    } catch {
        fatal(serverUnreachable());
    }
    if (res.status === 401) fatal(TOKEN_REJECTED);
    if (res.status === 429) fatal("Límite de terminales alcanzado.");
    if (res.status !== 201) {
        const detail = res.json?.message || `HTTP ${res.status}`;
        fatal(`No se pudo crear la terminal: ${detail}`);
    }
    return res.json;
}

// --- input pump ------------------------------------------------------------

const decoder = createInputDecoder();
let pending = [];
let inputTimer = null;
let inputChain = Promise.resolve();

function scheduleFlush() {
    if (inputTimer || detaching) return;
    inputTimer = setTimeout(() => {
        inputTimer = null;
        inputChain = inputChain.then(flushInput);
    }, INPUT_FLUSH_MS);
}

async function sendInputText(text) {
    if (!activeId) return;
    for (const chunk of chunkUtf8(text, MAX_INPUT_CHUNK_BYTES)) {
        try {
            const res = await apiRequest(
                "POST",
                `/api/v1/pty/${encodeURIComponent(activeId)}/input`,
                { data: chunk },
            );
            if (res.status === 401) fatal(TOKEN_REJECTED);
            if (res.status === 404) finishGone(null);
        } catch {
            // Keystrokes are not replayable: drop this chunk instead of
            // resending it out of order or duplicating it.
        }
    }
}

async function flushInput() {
    if (pending.length === 0) return;
    const buffers = pending;
    pending = [];
    const text = decoder.write(Buffer.concat(buffers));
    if (text) await sendInputText(text);
    if (pending.length > 0 && !detaching) scheduleFlush();
}

function onStdin(chunk) {
    if (detaching) return;
    const split = splitDetachKey(chunk);
    if (split.data.length > 0) pending.push(Buffer.from(split.data));
    if (split.detached) {
        detach();
        return;
    }
    scheduleFlush();
}

function beginInput() {
    process.stdin.on("data", onStdin);
    process.stdin.on("error", () => {});
    // A pipe has no raw mode; the CLI still works with piped stdin (tests).
    if (process.stdin.isTTY) {
        try {
            process.stdin.setRawMode(true);
        } catch {
            /* not fatal: line-buffered input still reaches the PTY */
        }
    }
    process.stdin.resume();
}

function detach() {
    if (detaching) return;
    detaching = true;
    if (inputTimer) {
        clearTimeout(inputTimer);
        inputTimer = null;
    }
    // Bytes typed before Ctrl+] still land in the PTY before we leave.
    inputChain = inputChain.then(flushInput);
    void inputChain.finally(() => {
        restoreConsole();
        writeAndExit(
            process.stdout,
            "\r\nDesconectado. La terminal sigue viva en la PC.\r\n",
            0,
        );
    });
}

// --- stream ----------------------------------------------------------------

function openStream(id) {
    return new Promise((resolve, reject) => {
        let settled = false;
        const req = http.request(
            {
                host: HOST,
                port,
                method: "GET",
                path: `/api/v1/pty/${encodeURIComponent(id)}/stream`,
                headers: { Authorization: `Bearer ${token}` },
            },
            (res) => {
                settled = true;
                resolve({ req, res });
            },
        );
        req.on("error", (err) => {
            if (settled) return;
            settled = true;
            reject(err);
        });
        req.end();
    });
}

function consumeStream(res) {
    return new Promise((resolve) => {
        let settled = false;
        const done = () => {
            if (settled) return;
            settled = true;
            resolve();
        };
        res.on("data", (chunk) => {
            if (detaching) return;
            try {
                process.stdout.write(chunk);
            } catch {
                /* stdout closed: the process is already leaving */
            }
        });
        res.on("end", done);
        res.on("close", done);
        res.on("error", done);
    });
}

function postResize() {
    if (!activeId || detaching) return;
    const { cols, rows } = consoleSize();
    void apiRequest(
        "POST",
        `/api/v1/pty/${encodeURIComponent(activeId)}/resize`,
        { cols, rows },
    ).then((res) => {
        if (res.status === 401) fatal(TOKEN_REJECTED);
    }).catch(() => {
        /* best effort: the next resize retries */
    });
}

function onResize() {
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
        resizeTimer = null;
        postResize();
    }, RESIZE_DEBOUNCE_MS);
}

function finishGone(exitCode) {
    restoreConsole();
    writeAndExit(
        process.stdout,
        "\r\n" + formatExitMessage(exitCode) + "\r\n",
        0,
    );
}

async function streamLoop(id) {
    let backoff = RECONNECT_MIN_MS;
    let everConnected = false;

    for (;;) {
        if (detaching) return;
        let opened;
        try {
            opened = await openStream(id);
        } catch {
            if (!everConnected) fatal(serverUnreachable());
            await sleep(backoff);
            backoff = nextBackoff(backoff);
            continue;
        }
        if (detaching) return;

        const res = opened.res;
        if (res.statusCode === 401) fatal(TOKEN_REJECTED);
        if (res.statusCode === 404) finishGone(null);
        if (res.statusCode !== 200) {
            res.on("error", () => {});
            res.resume();
            if (!everConnected) fatal(`IDUPI respondió HTTP ${res.statusCode}.`);
            await sleep(backoff);
            backoff = nextBackoff(backoff);
            continue;
        }

        everConnected = true;
        backoff = RECONNECT_MIN_MS;
        await consumeStream(res);
        if (detaching) return;

        // The server ends the stream when the PTY exits and also when a slow
        // consumer hits its backpressure cap. The list says which happened.
        let info;
        try {
            info = await fetchInfo(id);
        } catch {
            // Server restart or transient failure: keep trying with backoff.
            process.stdout.write("\x1bc");
            await sleep(backoff);
            backoff = nextBackoff(backoff);
            continue;
        }
        if (!info || !info.alive) finishGone(info ? info.exitCode : null);
        // Alive, so this was backpressure: the replay would duplicate what the
        // screen already shows, so reset the local screen first (RIS).
        process.stdout.write("\x1bc");
        await sleep(backoff);
        backoff = nextBackoff(backoff);
    }
}

async function attach(id) {
    activeId = id;
    const short = shortId(id);
    const line = `Conectado a ${short} — Ctrl+] para desconectar (la terminal sigue viva).`;
    if (process.stdout.isTTY) {
        process.stdout.write(`\x1b]0;idupi-term ${short}\x07`);
        process.stdout.write(`\x1b[2m${line}\x1b[0m\r\n`);
    } else {
        process.stdout.write(line + "\r\n");
    }
    beginInput();
    process.stdout.on("resize", onResize);
    // The PC reclaims the PTY size on attach; the last client to resize wins.
    postResize();
    await streamLoop(id);
}

// --- commands --------------------------------------------------------------

function printUsage() {
    process.stdout.write(USAGE + "\n");
}

function openWeb() {
    const url = `http://${HOST}:${port}/terminal/#token=${encodeURIComponent(token)}`;
    const [command, args] = process.platform === "win32"
        ? ["rundll32", ["url.dll,FileProtocolHandler", url]]
        : process.platform === "darwin"
            ? ["open", [url]]
            : ["xdg-open", [url]];
    try {
        const child = spawn(command, args, {
            detached: true,
            stdio: "ignore",
            windowsHide: true,
        });
        child.on("error", () => {});
        child.unref();
    } catch {
        fatal("No se pudo abrir el navegador.");
    }
    // The URL embeds the token fragment: it must never be printed.
    process.stdout.write("Abriendo la vista web…\n");
}

function ask(question) {
    return new Promise((resolve) => {
        const rl = readline.createInterface({
            input: process.stdin,
            output: process.stdout,
        });
        let answered = false;
        rl.question(question, (answer) => {
            answered = true;
            rl.close();
            resolve(answer);
        });
        rl.on("close", () => {
            if (!answered) resolve(null);
        });
    });
}

async function chooseTerminal(terminals) {
    for (;;) {
        const answer = await ask("Número para conectar, n = nueva, Enter = 1: ");
        if (answer === null) fatal("Entrada cerrada.");
        const choice = parseSelection(answer, terminals.length);
        if (choice.kind === "attach") {
            const info = terminals[choice.index - 1];
            if (info && info.alive) return info.id;
            process.stdout.write(
                "Esa terminal ya finalizó. Elige otra o n para crear una nueva.\r\n",
            );
            continue;
        }
        if (choice.kind === "new") return null;
        process.stdout.write(choice.message + "\r\n");
    }
}

async function main() {
    const parsed = parseArgs(process.argv.slice(2));
    if (parsed.command === "help") {
        printUsage();
        return;
    }
    if (parsed.error) fatal(parsed.error);

    try {
        port = resolvePort(process.env);
    } catch {
        fatal("IDUPI_PORT inválido.");
    }
    token = resolveToken({
        env: process.env,
        tokenPath: TOKEN_PATH,
        readFile: readFileSync,
    });
    if (!token) {
        fatal("Falta el token: define IDUPI_TOKEN o crea .idupi-token.");
    }

    if (parsed.command === "ls") {
        const terminals = await listTerminals();
        process.stdout.write(formatTerminalList(terminals) + "\n");
        return;
    }

    if (parsed.command === "web") {
        openWeb();
        return;
    }

    if (parsed.command === "new") {
        const info = await createTerminal(parsed.cwd || null);
        await attach(info.id);
        return;
    }

    if (parsed.command === "attach") {
        const terminals = await listTerminals();
        const resolved = resolveRef(parsed.ref, terminals);
        if (resolved.error) fatal(resolved.message);
        const info = terminals.find((entry) => entry.id === resolved.id);
        if (!info || !info.alive) {
            finishGone(info ? info.exitCode : null);
            return;
        }
        await attach(info.id);
        return;
    }

    // No arguments: list, prompt when there is something alive, otherwise
    // create one and attach.
    const terminals = await listTerminals();
    process.stdout.write(formatTerminalList(terminals) + "\r\n");
    if (!terminals.some((info) => info.alive)) {
        const info = await createTerminal(null);
        await attach(info.id);
        return;
    }
    const chosen = await chooseTerminal(terminals);
    if (chosen === null) {
        const info = await createTerminal(null);
        await attach(info.id);
        return;
    }
    await attach(chosen);
}

// --- process guards --------------------------------------------------------

function installProcessGuards() {
    process.on("exit", restoreConsole);
    process.on("SIGINT", () => {
        restoreConsole();
        process.exit(130);
    });
    process.on("SIGTERM", () => {
        restoreConsole();
        process.exit(143);
    });
    process.on("uncaughtException", (err) => {
        restoreConsole();
        console.error(`Error inesperado: ${err?.message || String(err)}`);
        process.exit(1);
    });
    process.on("unhandledRejection", (err) => {
        restoreConsole();
        console.error(`Error inesperado: ${err?.message || String(err)}`);
        process.exit(1);
    });
}

installProcessGuards();
main().catch((err) => {
    fatal(`Error inesperado: ${err?.message || String(err)}`);
});
