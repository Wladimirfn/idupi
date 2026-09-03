// ============================================================================
// IDUPI Dedicated Server Bridge (Servidor Independiente)
// Puerto por defecto: 8788
// Maneja: Pi CLI RPC, Exploración, Lectura, Proyectos, Sesiones, Modelos y Detección Real de Procesos/Terminales de la PC (Bun, Claude, Codex, Kimi, Node, Python, PowerShell, CMD)
// ============================================================================

import http from "node:http";
import { spawn, execSync, execFile, execFileSync } from "node:child_process";
import { readdirSync, statSync, readFileSync, writeFileSync, existsSync, rmSync, openSync, readSync, closeSync } from "node:fs";
import { homedir, networkInterfaces } from "node:os";
import { join, basename, relative, dirname } from "node:path";
import { randomUUID } from "node:crypto";
import { createAuthGuard, loadToken } from "../server-auth.mjs";
import { TaskRegistry } from "./lib/task-registry.mjs";
import { parseSubagentNotify, resolveNoticeCard, describeNoticeResult } from "./lib/subagent-notify.mjs";
import { SubagentCards } from "./lib/subagent-cards.mjs";
import { planAssistantMessage } from "./lib/claude-stream.mjs";
import { MessageBoundary } from "./lib/message-boundary.mjs";
import { describeRateLimit } from "./lib/rate-limit-notice.mjs";
import { CHAT_EVENTS, publish as publishChatEvent, subscribe as subscribeChatStream } from "./chat-events.mjs";
import {
    escapeSqlValue,
    validateNumeric,
    encodeCursor,
    decodeCursor,
    mergePage,
    ENGINES,
    DONE,
    buildClaudeIndex,
    buildPiIndex,
    resolveOpenCodeExePath
} from "./lib/sessions.mjs";

import {
    ActivityRegistry,
    classifyMcp,
    redactActivity,
} from "./lib/activity.mjs";

// Remote screen module (docs/remote-screen-module.md): routes live in their
// own module so this monolith stops growing.
import { handleScreenRoute, shutdownScreen } from "./lib/screen-routes.mjs";
import { resolveProjectFilePath } from "./lib/project-files.mjs";
import { isProtectedSystemPath } from "./lib/system-paths.mjs";
import { claudeArgs, openCodeArgs, normalizeOpenCodeModel } from "./lib/agent-cmdline.mjs";
import { mergePiModelCatalogs } from "./lib/pi-models.mjs";
// Phase 3 (fix-ui-request-selection): pure normalizers that turn each
// engine's raw UI-request event into the canonical shape the registry
// expects. Imported once here so the engine adapters in this file (Pi RPC
// handler; future Claude/OpenCode wiring) and any unit test can share the
// same translator.
import { normalizeUiRequest as normalizePiUiRequest } from "./lib/orchestrator/engines/pi.mjs";
import { normalizeUiRequest as normalizeClaudeUiRequest } from "./lib/orchestrator/engines/claude.mjs";
import { normalizeUiRequest as normalizeOpenCodeUiRequest } from "./lib/orchestrator/engines/opencode.mjs";

// PendingUiRequestRegistry (fix-ui-request-selection, Phase 1): a single
// session-bound registry owns the select/confirm/input lifecycle for every
// engine CLI. The 120s expiry timer auto-approves requests so old APKs and
// quiet clients never reach the 300s `AGENT_CLI_TIMEOUT_MS` taskkill. The
// `IDUPI_UI_REQUEST_PHASE_A` env var gates the Phase A unblock contract;
// default ON, set to "0" to disable (Phase B will flip this off once the app
// is shipping). Phase 2 wires the SSE emit + POST route on top of the same
// registry.
import { UI_REQUEST_DEADLINE_MS } from "./lib/cli-constants.mjs";
import {
    PendingUiRequestRegistry,
    UI_REQUEST_METHODS,
    validateUiAnswer,
} from "./lib/ui-request-registry.mjs";

const IDUPI_UI_REQUEST_PHASE_A = process.env.IDUPI_UI_REQUEST_PHASE_A !== "0"; // default ON
const uiRequestRegistry = new PendingUiRequestRegistry();

// Phase 2 transport (fix-ui-request-selection): on register, broadcast a
// `ui_request` SSE frame so any subscribed chat app can render the dialog
// with the same token it must echo back on POST /api/v1/chat/ui-response.
// The engine adapters (Phase 3) are the callers of register(); the wiring
// here only cares that the registry's `register` event ends up on the SSE bus.
uiRequestRegistry.on("register", ({ entry }) => {
    publishChatEvent(CHAT_EVENTS.UI_REQUEST, {
        requestId: entry.requestId,
        token: entry.token,
        method: entry.method,
        title: entry.title,
        message: entry.message,
        options: entry.options,
        // Server-reported deadline so the countdown renders correctly even if
        // the app clock is skewed relative to the server clock. Positive only;
        // 0 means "already expired" (impossible at register time, kept for
        // symmetry with the spec's deadlineMs contract).
        deadlineMs: Math.max(0, entry.expiresAt - Date.now()),
        sessionId: entry.sessionId,
        engine: entry.engine,
    });
});

// Phase 1 fallback (kept + extended for Phase 2 transport): the 120s timer
// fires `expire` with the blanket auto-approve decision. The log line is the
// audit trail the spec asks for ("Expiry MUST yield a terminal, logged
// resolution"); the SSE frame is what tells the app to drop the dialog so a
// re-render after a background roundtrip doesn't keep the spinner alive.
uiRequestRegistry.on("expire", ({ entry, decision }) => {
    console.log(
        `[ui-request] AUTO-APPROVE method=${entry.method} ` +
        `sessionId=${entry.sessionId} requestId=${entry.requestId} ` +
        `resolution=${decision.source} value=${JSON.stringify(decision.value)} ` +
        `(phaseA=${IDUPI_UI_REQUEST_PHASE_A ? "on" : "off"})`,
    );
    // Phase 3 (fix-ui-request-selection): expiry is a TERMINAL decision — the
    // engine must receive it on stdin, otherwise a pending ask_user_question
    // keeps Pi's dialog open and rides into the 300s taskkill. writeUiResponseToStdin
    // IS the guard: it returns false when no writer is registered for this
    // requestId (already answered, child closed, or the engine had no id to
    // correlate), and the entry is terminal either way. The writer is cleared
    // so a late POST on the same requestId short-circuits to 404.
    const stdinOk = writeUiResponseToStdin(entry.requestId, decision.value);
    clearUiRequestStdinWriter(entry.requestId);
    if (!stdinOk) {
        console.warn(
            `[ui-request] expire sin writer de stdin para ${entry.requestId} ` +
            `(engine=${entry.engine}) — el diálogo del motor puede seguir abierto hasta el taskkill`,
        );
    }
    publishChatEvent(CHAT_EVENTS.UI_REQUEST_RESOLVED, {
        requestId: entry.requestId,
        sessionId: entry.sessionId,
        engine: entry.engine,
        resolution: decision.source, // "auto_approve"
        value: decision.value,
    });
});
console.log(
    `[ui-request] registry ready: deadline=${UI_REQUEST_DEADLINE_MS}ms, ` +
    `phaseA=${IDUPI_UI_REQUEST_PHASE_A ? "on (default)" : "off (IDUPI_UI_REQUEST_PHASE_A=0)"}`,
);

// Phase 3 (fix-ui-request-selection): map a registered requestId to a writer
// function that knows how to deliver the answer to that specific engine's
// stdin. Populated by each engine adapter at register-time (Pi captures
// `this.child.stdin`; Claude/OpenCode retain the child they just spawned
// with `["pipe","pipe","pipe"]`). Cleared after a successful write, and on
// the engine child 'close' so a dead child never gets a late answer.
//
// Lives outside the registry on purpose: the registry's contract is
// "lifecycle only — no I/O" (see ui-request-registry.mjs doc-block). Keeping
// the writer seam here means a future engine that uses a websocket, an
// in-process queue, or a fake for tests can plug in without touching the
// registry module.
const uiRequestStdinWriters = new Map(); // requestId → (value) => boolean

function setUiRequestStdinWriter(requestId, writer) {
    if (typeof writer === "function") uiRequestStdinWriters.set(requestId, writer);
}

function clearUiRequestStdinWriter(requestId) {
    uiRequestStdinWriters.delete(requestId);
}

function writeUiResponseToStdin(requestId, value) {
    const writer = uiRequestStdinWriters.get(requestId);
    if (!writer) return false;
    try {
        return writer(value) === true;
    } catch (err) {
        console.warn(`[ui-request] stdin write failed for ${requestId}: ${err?.message || err}`);
        return false;
    }
}

/**
 * Drop every writer bound to a specific engine child. Called from the
 * child 'close' handlers in runClaudeCli / runOpenCodeCli / PiRpcManager so a
 * late POST never tries to write to a dead pipe. Each writer carries a
 * `__child` back-pointer so we can identify its owner without introspecting
 * the closure.
 */
function clearUiRequestStdinWritersForChild(child) {
    if (!child) return;
    // Collect first, then delete: mutating a Map while iterating it is
    // well-defined per the spec, but the snapshot is clearer and survives
    // any future refactor that swaps the container.
    const toDelete = [];
    for (const [requestId, writer] of uiRequestStdinWriters) {
        if (writer && writer.__child === child) toDelete.push(requestId);
    }
    for (const requestId of toDelete) uiRequestStdinWriters.delete(requestId);
}

// Orchestrator delegation: engines resolve via resolveEngine(); per-route
// helpers in lib/orchestrator/routes.mjs own per-engine isolation, .bak, and
// gentle-ai sync wrapping. Routes in this file stay thin (parsing + response
// shape only) so this monolith stops growing.
import {
    handleStatusRoute,
    handleProfileApplyWithPresets,
    handleModelsUpdate,
} from "./lib/orchestrator/routes.mjs";

const PORT = process.env.PORT || 8788;
const requireAuth = createAuthGuard(loadToken());
const PI_CLI_JS = join(
    homedir(),
    "AppData",
    "Roaming",
    "npm",
    "node_modules",
    "@earendil-works",
    "pi-coding-agent",
    "dist",
    "cli.js"
);
const PROJECTS_JSON_FILE = join(process.cwd(), "idupi-server", "projects.json");

console.log("=================================================");
console.log("📱 Servidor Independiente IDUPI Server (Modo RPC)");
console.log("🌐 Puerto: " + PORT);
console.log("📍 Pi CLI ruta: " + PI_CLI_JS);
console.log("=================================================");

let currentStatus = {
    connected: true,
    pcName: process.env.COMPUTERNAME || "PC Principal IDUPI",
    project: "Proyecto Actual (IDUPI)",
    agent: "Pi CLI RPC (Servidor IDUPI)",
    busy: false,
    queueSize: 0,
    activeAgents: ["Pi CLI Native Agent"],
    cliTask: "Listo y en espera desde la app IDUPI",
    operatingAi: "gpt-5.6-luna",
    operatingProvider: "openai-codex",
    activeEngine: "pi-cli"
};

// Sesión activa persistente para Claude CLI y OpenCode Engine (evita crear múltiples sesiones en cada mensaje)
let activeClaudeSessionId = null;
let activeOpenCodeSessionId = null;

// Modelo Claude explícitamente seleccionado por el usuario vía /api/v1/model/switch
// mientras el motor activo es "claude". Si es null, el CLI de Claude usa su propio
// modelo por defecto (el de ~/.claude/settings.json) y no se agrega --model al comando.
let activeClaudeModelId = null;

// Modelo OpenCode explícitamente seleccionado vía /api/v1/model/switch mientras el
// motor activo es "opencode". Si es null, el CLI de OpenCode usa su modelo por
// defecto y no se agrega -m al comando. Se pasa a `opencode run -m` como
// provider/model (verificado con `opencode run --help`).
let activeOpenCodeModel = null; // { model, provider }

// Aliases cortos que Claude Code acepta en --model (verificado con `claude --help`:
// "Provide an alias for the latest model (e.g. 'fable', 'opus', or 'sonnet')...").
// Solo mapeamos los alias que tienen un ID completo autorizado en nuestro catálogo.
const CLAUDE_MODEL_ALIASES = {
    opus: "claude-opus-5",
    sonnet: "claude-sonnet-5",
    haiku: "claude-haiku-4-5"
};

// Estado global de la tarea activa en segundo plano
// Per-client task state. `activeTask` stays for the status surfaces that read
// it directly; this is what a polling client correlates against.
const taskRegistry = new TaskRegistry();

let activeTask = {
    id: null,
    message: null,
    status: "idle", // "idle", "running", "completed", "error"
    output: null,
    error: null,
    startTime: null
};

// Cargar el modelo y proveedor por defecto desde ~/.pi/agent/settings.json al iniciar
function loadDefaultModel() {
    const settingsPath = join(homedir(), ".pi", "agent", "settings.json");
    try {
        if (existsSync(settingsPath)) {
            const settings = JSON.parse(readFileSync(settingsPath, "utf8"));
            if (settings.defaultModel) {
                currentStatus.operatingAi = settings.defaultModel;
            }
            if (settings.defaultProvider) {
                currentStatus.operatingProvider = settings.defaultProvider;
            }
            console.log(`[IDUPI Server] Modelo por defecto detectado: ${currentStatus.operatingProvider || 'auto'}/${currentStatus.operatingAi}`);
        }
    } catch (e) {}
}
loadDefaultModel();

// Obtener el catálogo real de modelos según el Motor Activo (Pi CLI, Claude CLI, OpenCode)
function getAvailableModels() {
    if (currentStatus.activeEngine === "opencode") {
        try {
            const raw = execSync("opencode models", { encoding: "utf8", timeout: 5000, maxBuffer: EXEC_MAX_BUFFER });
            const lines = raw.split("\n").map(l => l.trim()).filter(l => l.length > 0 && !l.startsWith("Notice:") && !l.startsWith("Error:"));
            const realModels = [];
            for (const line of lines) {
                if (line.includes("/")) {
                    const parts = line.split("/");
                    const provider = parts[0];
                    const modelName = parts.slice(1).join("/");
                    realModels.push({
                        id: line,
                        name: modelName,
                        provider: provider
                    });
                } else {
                    realModels.push({
                        id: line,
                        name: line,
                        provider: "opencode"
                    });
                }
            }
            if (realModels.length > 0) {
                console.log(`[OpenCode Models] Detección exitosa de ${realModels.length} modelos reales en tu PC.`);
                return realModels;
            }
        } catch (e) {
            console.error("[OpenCode Models Error]", e.message);
        }
        return [
            { id: "opencode-go/gpt-5.6-luna", name: "gpt-5.6-luna", provider: "opencode-go" },
            { id: "opencode-go/deepseek-v4-pro", name: "deepseek-v4-pro", provider: "opencode-go" },
            { id: "opencode-go/glm-5.2", name: "glm-5.2", provider: "opencode-go" },
            { id: "opencode-go/grok-4.5", name: "grok-4.5", provider: "opencode-go" },
            { id: "opencode-go/qwen3.8-max", name: "qwen3.8-max", provider: "opencode-go" }
        ];
    }

    if (currentStatus.activeEngine === "claude") {
        // Catálogo vigente de modelos Anthropic para Claude Code (verificado vía
        // documentación oficial; los IDs retirados NUNCA deben reintroducirse aquí).
        const claudeModels = [
            { id: "claude-opus-5", name: "Claude Opus 5", provider: "anthropic" },
            { id: "claude-sonnet-5", name: "Claude Sonnet 5", provider: "anthropic" },
            { id: "claude-opus-4-8", name: "Claude Opus 4.8", provider: "anthropic" },
            { id: "claude-haiku-4-5", name: "Claude Haiku 4.5", provider: "anthropic" }
        ];
        try {
            const claudeSettingsPath = join(homedir(), ".claude", "settings.json");
            if (existsSync(claudeSettingsPath)) {
                const settings = JSON.parse(readFileSync(claudeSettingsPath, "utf8"));
                if (settings.model) {
                    // settings.json puede contener un alias corto ("opus", "sonnet", "haiku")
                    // en vez de un ID de modelo completo. Resolverlo antes de exponerlo,
                    // para no mandar a la app un identificador que no es utilizable tal cual.
                    const resolvedId = CLAUDE_MODEL_ALIASES[settings.model] || settings.model;
                    if (!claudeModels.some(m => m.id === resolvedId)) {
                        const isKnownAlias = Boolean(CLAUDE_MODEL_ALIASES[settings.model]);
                        claudeModels.unshift({
                            id: resolvedId,
                            name: isKnownAlias
                                ? `${resolvedId} (Configurado en PC vía alias '${settings.model}')`
                                : `${settings.model} (Configurado en PC - alias no reconocido)`,
                            provider: "anthropic"
                        });
                    }
                }
            }
        } catch (e) {}
        return claudeModels;
    }

    // Pi CLI models (~/.pi/agent/models-store.json + ~/.pi/agent/models.json)
    const modelsStorePath = join(homedir(), ".pi", "agent", "models-store.json");
    const modelsJsonPath = join(homedir(), ".pi", "agent", "models.json");
    const storeModels = [];

    try {
        if (existsSync(modelsStorePath)) {
            const data = JSON.parse(readFileSync(modelsStorePath, "utf8"));
            for (const providerKey of Object.keys(data)) {
                const providerObj = data[providerKey];
                if (providerObj && Array.isArray(providerObj.models)) {
                    for (const m of providerObj.models) {
                        if (m && m.id) {
                            storeModels.push({
                                id: m.id,
                                name: m.name || m.id,
                                provider: m.provider || providerKey
                            });
                        }
                    }
                }
            }
        }
    } catch (e) {
        console.error("[Models Store Error]", e.message);
    }

    // Local/self-hosted models (local/ornith, local/qwen38) live in
    // ~/.pi/agent/models.json under { providers: { <key>: { models: [...] } } }
    // — the same providers[].models[] shape the store uses, but with the
    // `providers` wrapper. A missing/corrupt file must not crash the catalog.
    const localModels = [];
    try {
        if (existsSync(modelsJsonPath)) {
            const data = JSON.parse(readFileSync(modelsJsonPath, "utf8"));
            const providers = (data && typeof data === "object" && data.providers && typeof data.providers === "object")
                ? data.providers
                : {};
            for (const providerKey of Object.keys(providers)) {
                const providerObj = providers[providerKey];
                if (providerObj && Array.isArray(providerObj.models)) {
                    for (const m of providerObj.models) {
                        if (m && m.id) {
                            localModels.push({
                                id: m.id,
                                name: m.name || m.id,
                                provider: m.provider || providerKey
                            });
                        }
                    }
                }
            }
        }
    } catch (e) {
        console.error("[Models JSON Error]", e.message);
    }

    // Store entries first; local entries only fill provider/model pairs the
    // store does not already list.
    let models = mergePiModelCatalogs(storeModels, localModels);

    // No inventamos un catálogo de modelos Pi CLI. Si ~/.pi/agent/models-store.json
    // no existe o no tiene entradas, reportamos como mínimo el modelo activo real,
    // detectado a partir de los eventos RPC "model_change"/"active_model" que
    // PiRpcManager ya vuelca en currentStatus.operatingAi/operatingProvider.
    if (models.length === 0) {
        if (currentStatus.operatingAi) {
            models = [{
                id: currentStatus.operatingAi,
                name: currentStatus.operatingAi,
                provider: currentStatus.operatingProvider || "pi-cli"
            }];
        }
        return models;
    }

    // El catálogo del store existe: asegurar que el modelo activo detectado por RPC
    // esté presente aunque no figure en el archivo estático (p. ej. modelo elegido
    // en caliente que aún no fue persistido en models-store.json).
    if (currentStatus.operatingAi && !models.some(m => m.id === currentStatus.operatingAi)) {
        models.unshift({
            id: currentStatus.operatingAi,
            name: `${currentStatus.operatingAi} (Activo)`,
            provider: currentStatus.operatingProvider || "pi-cli"
        });
    }

    return models;
}

// ============================================================================
// Detección de Comandos Slash (/comando) según el Motor Activo
// ============================================================================
// Nunca se inventan listas de comandos: cada rama lee archivos/documentación
// reales que efectivamente existen para el CLI detectado. Si no hay una fuente
// verificable, se devuelve una lista vacía para ese motor.

// Extrae una descripción de un archivo Markdown de comando. Formato compartido
// por Claude Code y OpenCode: frontmatter YAML opcional con `description:`,
// seguido del cuerpo del prompt. Si no hay frontmatter/descripción, se usa la
// primera línea de texto no vacía y que no sea un encabezado.
function parseCommandMarkdownFile(filePath) {
    const raw = readFileSync(filePath, "utf8");
    const lines = raw.split(/\r?\n/);

    let description = "";
    let bodyStartIndex = 0;

    if (lines[0] !== undefined && lines[0].trim() === "---") {
        let end = -1;
        for (let i = 1; i < lines.length; i++) {
            if (lines[i].trim() === "---") { end = i; break; }
        }
        if (end !== -1) {
            for (let i = 1; i < end; i++) {
                const match = lines[i].match(/^description:\s*(.*)$/);
                if (match) {
                    let value = match[1].trim();
                    if (value === "|" || value === ">" || value === "") {
                        // Descripción multilínea: concatenar las líneas indentadas siguientes
                        const parts = [];
                        for (let j = i + 1; j < end; j++) {
                            if (/^\s+\S/.test(lines[j])) {
                                parts.push(lines[j].trim());
                            } else {
                                break;
                            }
                        }
                        value = parts.join(" ").trim();
                    } else {
                        value = value.replace(/^["']|["']$/g, "");
                    }
                    description = value;
                    break;
                }
            }
            bodyStartIndex = end + 1;
        }
    }

    if (!description) {
        for (let i = bodyStartIndex; i < lines.length; i++) {
            const trimmed = lines[i].trim();
            if (trimmed.length === 0) continue;
            if (trimmed.startsWith("#")) continue;
            if (trimmed === "---") continue;
            description = trimmed;
            break;
        }
    }

    if (!description) {
        description = "Comando personalizado sin descripción disponible.";
    }

    // Recortar descripciones extensas para que la lista de comandos rápidos sea legible
    if (description.length > 160) {
        description = description.slice(0, 157).trimEnd() + "...";
    }

    return description;
}

// Lee un directorio de archivos *.md de comandos y produce entradas
// { command, description, category }. Nunca lanza: directorio ausente o
// archivo individual ilegible simplemente se omite.
function readCommandsFromDir(dirPath, prefix, category) {
    const result = [];
    try {
        if (!existsSync(dirPath)) return result;
        const entries = readdirSync(dirPath).filter(f => f.toLowerCase().endsWith(".md"));
        for (const fileName of entries) {
            try {
                const fullPath = join(dirPath, fileName);
                if (!statSync(fullPath).isFile()) continue;
                const name = basename(fileName, ".md");
                const description = parseCommandMarkdownFile(fullPath);
                result.push({ command: `${prefix}${name}`, description, category });
            } catch (e) {
                // Archivo individual ilegible/corrupto: se omite sin abortar el resto
            }
        }
    } catch (e) {
        console.error(`[Commands Detection Error] ${dirPath}:`, e.message);
    }
    return result;
}

// Comandos integrados de Pi CLI: se parsean desde la tabla "Commands" del
// README.md que viene instalado junto al paquete detectado en PI_CLI_JS (docs
// reales del binario presente en esta PC, no una lista inventada). `pi --help`
// no produce salida (verificado), por lo que esta es la única fuente confiable.
function readPiCliBuiltinCommands() {
    const result = [];
    try {
        const readmePath = join(PI_CLI_JS, "..", "..", "README.md");
        if (!existsSync(readmePath)) return result;

        const raw = readFileSync(readmePath, "utf8");
        const lines = raw.split(/\r?\n/);
        let inTable = false;

        for (const line of lines) {
            if (/^\|\s*Command\s*\|\s*Description\s*\|/i.test(line)) {
                inTable = true;
                continue;
            }
            if (!inTable) continue;
            if (!line.trim().startsWith("|")) break; // fin de la tabla Markdown
            if (/^\|\s*-+\s*\|/.test(line)) continue; // fila separadora "|---|---|"

            const cells = line.split("|").map(c => c.trim()).filter((_, idx, arr) => idx > 0 && idx < arr.length - 1);
            if (cells.length < 2) continue;

            const cmdMatches = cells[0].match(/`(\/[^`]+)`/g) || [];
            for (const raw2 of cmdMatches) {
                const cmdName = raw2.replace(/`/g, "").split(" ")[0];
                result.push({
                    command: cmdName,
                    description: cells[1] || "Comando integrado de Pi CLI.",
                    category: "Pi CLI · Integrado"
                });
            }
        }
    } catch (e) {
        console.error("[Pi CLI Commands Detection Error]", e.message);
    }
    return result;
}

// Skills locales de Pi CLI, expuestos en el propio CLI como comandos "/skill:<nombre>"
// (documentado en README.md: "skills are available as /skill:name"). Se listan a
// partir de ~/.pi/agent/skills/*/SKILL.md, que sí existen de verdad en esta PC.
function readPiCliSkillCommands() {
    const result = [];
    try {
        const skillsDir = join(homedir(), ".pi", "agent", "skills");
        if (!existsSync(skillsDir)) return result;

        const entries = readdirSync(skillsDir);
        for (const entry of entries) {
            try {
                const skillFile = join(skillsDir, entry, "SKILL.md");
                if (!existsSync(skillFile) || !statSync(skillFile).isFile()) continue;
                const description = parseCommandMarkdownFile(skillFile);
                result.push({ command: `/skill:${entry}`, description, category: "Pi CLI · Skill" });
            } catch (e) {
                // Skill individual ilegible: se omite sin abortar el resto
            }
        }
    } catch (e) {
        console.error("[Pi CLI Skills Detection Error]", e.message);
    }
    return result;
}

// Catálogo de comandos slash disponibles según el Motor Activo (Pi CLI, Claude CLI, OpenCode)
function getAvailableCommands() {
    try {
        const engine = currentStatus.activeEngine;
        const activeProj = resolveProject(activeProjectId);

        if (engine === "claude") {
            const userCommands = readCommandsFromDir(join(homedir(), ".claude", "commands"), "/", "Claude · Usuario");
            const projectCommands = activeProj && activeProj.path
                ? readCommandsFromDir(join(activeProj.path, ".claude", "commands"), "/", "Claude · Proyecto")
                : [];
            return [...userCommands, ...projectCommands];
        }

        if (engine === "opencode") {
            const userCommands = readCommandsFromDir(join(homedir(), ".config", "opencode", "commands"), "/", "OpenCode · Usuario");
            let projectCommands = [];
            if (activeProj && activeProj.path) {
                projectCommands = readCommandsFromDir(join(activeProj.path, ".opencode", "commands"), "/", "OpenCode · Proyecto");
                if (projectCommands.length === 0) {
                    // OpenCode también acepta el nombre singular "command" por compatibilidad
                    projectCommands = readCommandsFromDir(join(activeProj.path, ".opencode", "command"), "/", "OpenCode · Proyecto");
                }
            }
            return [...userCommands, ...projectCommands];
        }

        // pi-cli: comandos integrados documentados en el propio README.md instalado
        // + skills locales disponibles como /skill:<nombre>
        return [...readPiCliBuiltinCommands(), ...readPiCliSkillCommands()];
    } catch (e) {
        console.error("[Commands Detection Error]", e.message);
        return [];
    }
}

// Cargar o inicializar la lista de proyectos persistentes
const defaultProjects = [
    {
        id: "idupi",
        name: "Proyecto Actual (IDUPI)",
        path: process.cwd(),
        isActive: true,
        status: "Activo",
        lastActivity: "Ahora"
    }
];

let registeredProjects = [...defaultProjects];

function loadSavedProjects() {
    try {
        if (existsSync(PROJECTS_JSON_FILE)) {
            const data = readFileSync(PROJECTS_JSON_FILE, "utf8");
            const parsed = JSON.parse(data);
            if (Array.isArray(parsed) && parsed.length > 0) {
                registeredProjects = parsed;
                console.log(`[Projects DB] Cargados ${registeredProjects.length} proyectos guardados.`);
            }
        }
    } catch (e) {
        console.error("[Projects DB Error]", e.message);
    }
}

function saveProjects() {
    try {
        writeFileSync(PROJECTS_JSON_FILE, JSON.stringify(registeredProjects, null, 2), "utf8");
        console.log("[Projects DB] Proyectos guardados en disco.");
    } catch (e) {
        console.error("[Projects DB Save Error]", e.message);
    }
}

loadSavedProjects();

let activeProjectId = registeredProjects.find(p => p.isActive)?.id || registeredProjects[0].id;

function resolveProject(projId) {
    if (!projId || projId === "rcm") return registeredProjects[0];
    return registeredProjects.find(p => p.id === projId) || registeredProjects[0];
}

// Gestor Multiterminal Real con Inspección de Procesos en Ejecución en tu PC (Bun, Claude, Codex, Kimi, Node, Python, PowerShell, CMD)
class TerminalManager {
    constructor() {
        this.terminals = new Map();
        this.initDefaultTerminals();
    }

    initDefaultTerminals() {
        this.terminals.set("pi-cli", {
            id: "pi-cli",
            name: "Pi CLI RPC Engine",
            type: "agent",
            status: "running",
            cwd: process.cwd(),
            pid: process.pid,
            logs: ["[System] Terminal Pi CLI RPC lista y conectada."]
        });
    }

    listTerminals() {
        const list = [];

        // 1. Terminal Pi CLI RPC gestionada
        list.push({
            id: "pi-cli",
            name: "Pi CLI RPC Engine",
            type: "agent",
            status: "running",
            cwd: process.cwd(),
            pid: process.pid,
            logCount: 1
        });

        // 2. Terminales interactivas creadas explícitamente desde la App
        for (const [id, term] of this.terminals.entries()) {
            if (id !== "pi-cli") {
                list.push({
                    id: term.id,
                    name: term.name,
                    type: term.type,
                    status: term.status,
                    cwd: term.cwd,
                    pid: term.pid,
                    logCount: term.logs ? term.logs.length : 0
                });
            }
        }

        // 3. Inspección REAL de Procesos y Terminales Abiertas en tu PC (Bun, Claude, Codex, Kimi, Node, Python, PowerShell, CMD, Deno)
        try {
            if (process.platform === "win32") {
                const rawCsv = execSync('wmic process get caption,commandline,processid /format:csv', { encoding: "utf8", timeout: 4000, maxBuffer: EXEC_MAX_BUFFER });
                const lines = rawCsv.split("\n").filter(l => l.trim().length > 0);

                for (let i = 1; i < lines.length; i++) {
                    const line = lines[i].trim();
                    if (!line || line.startsWith("Node,")) continue;

                    const parts = line.split(",");
                    if (parts.length < 4) continue;

                    const pidStr = parts[parts.length - 1];
                    const pid = parseInt(pidStr, 10);
                    const caption = (parts[1] || "").toLowerCase();
                    const commandLine = parts.slice(2, parts.length - 1).join(",");

                    if (!pid || isNaN(pid) || pid === process.pid) continue;

                    // Filtrar procesadores/terminales e IAs de interés: Bun, Claude, Codex, Kimi, Deno, Node, Python, PowerShell, CMD, Windows Terminal, Bash
                    const isTargetProcess = caption.includes("powershell") || caption.includes("cmd") || caption.includes("node") || 
                                             caption.includes("python") || caption.includes("wt") || caption.includes("bash") || 
                                             caption.includes("bun") || caption.includes("claude") || caption.includes("codex") || 
                                             caption.includes("kimi") || caption.includes("deno");
                    
                    if (!isTargetProcess) continue;

                    // Ignorar subprocesos auxiliares de inspección
                    if (commandLine.includes("wmic process") || commandLine.includes("npm-shim.js") || commandLine.includes("Get-CimInstance")) continue;

                    const procId = `proc-${pid}`;
                    if (list.some(t => t.id === procId || t.pid === pid)) continue;

                    let type = "shell";
                    let cleanTitle = `${parts[1]} (PID ${pid})`;

                    if (caption.includes("bun")) {
                        type = "agent";
                        cleanTitle = `Bun Runtime (PID ${pid})`;
                    } else if (caption.includes("claude")) {
                        type = "agent";
                        cleanTitle = `Claude AI CLI (PID ${pid})`;
                    } else if (caption.includes("codex")) {
                        type = "agent";
                        cleanTitle = `Codex Agent CLI (PID ${pid})`;
                    } else if (caption.includes("kimi")) {
                        type = "agent";
                        cleanTitle = `Kimi AI Shell (PID ${pid})`;
                    } else if (caption.includes("deno")) {
                        type = "server";
                        cleanTitle = `Deno Engine (PID ${pid})`;
                    } else if (caption.includes("node")) {
                        type = "server";
                        if (commandLine.includes("index.mjs")) cleanTitle = `IDUPI Server (PID ${pid})`;
                        else if (commandLine.includes("index.js")) cleanTitle = `Node App: ${basename(commandLine.split(" ").pop() || "server.js")} (PID ${pid})`;
                        else if (commandLine.includes("mcp-server")) cleanTitle = `MCP Server (PID ${pid})`;
                        else cleanTitle = `Node Server (PID ${pid})`;
                    } else if (caption.includes("powershell")) {
                        type = "shell";
                        if (commandLine.includes(".ps1")) cleanTitle = `PowerShell: ${basename(commandLine.split(" ").pop() || "script.ps1")} (PID ${pid})`;
                        else cleanTitle = `PowerShell Window (PID ${pid})`;
                    } else if (caption.includes("cmd")) {
                        type = "shell";
                        cleanTitle = `CMD Window (PID ${pid})`;
                    } else if (caption.includes("python")) {
                        type = "server";
                        cleanTitle = `Python App (PID ${pid})`;
                    }

                    list.push({
                        id: procId,
                        name: cleanTitle,
                        type: type,
                        status: "running",
                        pid: pid,
                        cwd: process.cwd(),
                        logCount: 0
                    });
                }
            }
        } catch (e) {
            console.error("[Real System Process Inspection Error]", e.message);
        }

        return list;
    }

    getTerminalLogs(id) {
        const term = this.terminals.get(id);
        if (term) return term.logs || [];

        if (id.startsWith("proc-")) {
            const pid = parseInt(id.replace("proc-", ""), 10);

            // Lectura en tiempo real segura y NO destructiva de la actividad reciente
            const sessionsBaseDir = join(homedir(), ".pi", "agent", "sessions");
            let latestSessionFile = null;
            let latestMtime = 0;

            try {
                if (existsSync(sessionsBaseDir)) {
                    const subdirs = readdirSync(sessionsBaseDir);
                    for (const subdir of subdirs) {
                        const dirPath = join(sessionsBaseDir, subdir);
                        if (statSync(dirPath).isDirectory()) {
                            const files = readdirSync(dirPath).filter(f => f.endsWith(".jsonl"));
                            for (const f of files) {
                                const fp = join(dirPath, f);
                                const stat = statSync(fp);
                                if (stat.mtimeMs > latestMtime) {
                                    latestMtime = stat.mtimeMs;
                                    latestSessionFile = fp;
                                }
                            }
                        }
                    }
                }
            } catch (e) {}

            let liveActivityLogs = [
                `[Sistema OS Windows] Inspección de proceso real`,
                `PID de Proceso: ${pid}`,
                `Modo de Lectura: SOLO LECTURA (100% Seguro - Sin riesgo de alterar o romper la ejecución)`
            ];

            if (latestSessionFile && (Date.now() - latestMtime < 600000)) {
                try {
                    const content = readFileSync(latestSessionFile, "utf8");
                    const lines = content.split("\n").filter(l => l.trim());
                    liveActivityLogs.push(`--- ACTIVIDAD RECIENTE REGISTRADA EN LA PC (${basename(latestSessionFile)}) ---`);
                    
                    const recentEntries = lines.slice(-6);
                    for (const rawLine of recentEntries) {
                        try {
                            const entry = JSON.parse(rawLine);
                            if (entry.type === "message" && entry.message) {
                                const role = (entry.message.role || "sys").toUpperCase();
                                const text = entry.message.content?.find(c => c.type === "text")?.text || "";
                                if (text) {
                                    const snippet = text.length > 120 ? text.slice(0, 117) + "..." : text;
                                    liveActivityLogs.push(`[${role}] ${snippet}`);
                                }
                            }
                        } catch (e) {}
                    }
                } catch (e) {}
            } else {
                liveActivityLogs.push(`[Estado] Proceso en ejecución activa escuchando en tu PC.`);
            }

            return liveActivityLogs;
        }

        return ["// Terminal no encontrada"];
    }

    spawnNewTerminal(customName = null) {
        const activeProj = resolveProject(activeProjectId);
        const targetCwd = activeProj.path;
        const id = `term-${Date.now()}`;
        const name = customName || `PowerShell Shell (${basename(targetCwd)})`;

        try {
            const shellExe = process.platform === "win32" ? "powershell.exe" : "bash";
            const args = process.platform === "win32" ? ["-NoExit", "-ExecutionPolicy", "Bypass", "-Command", "-"] : ["-i"];
            const child = spawn(shellExe, args, {
                cwd: targetCwd,
                env: process.env,
                shell: true,
                windowsHide: false
            });

            const termObj = {
                id,
                name,
                type: "shell",
                status: "running",
                cwd: targetCwd,
                pid: child.pid,
                child,
                logs: [`[Terminal ${id}] Inicializada en ${targetCwd}`]
            };

            const stripAnsi = (str) => str.replace(/[\u001b\u009b][#()#;?]*(?:[0-9]{1,4}(?:;[0-9]{0,4})*)?[0-9A-ORZcf-nqry=><]/g, "");

            child.stdout.on("data", (data) => {
                const raw = stripAnsi(data.toString());
                const lines = raw.split("\n").map(l => l.replace(/\r/g, "")).filter(l => l.trim().length > 0);
                termObj.logs.push(...lines);
                if (termObj.logs.length > 500) termObj.logs = termObj.logs.slice(-300);
            });

            child.stderr.on("data", (data) => {
                const raw = stripAnsi(data.toString());
                const lines = raw.split("\n").map(l => l.replace(/\r/g, "")).filter(l => l.trim().length > 0);
                termObj.logs.push(...lines.map(l => `[STDERR] ${l}`));
                if (termObj.logs.length > 500) termObj.logs = termObj.logs.slice(-300);
            });

            child.on("close", (code) => {
                termObj.status = "closed";
                termObj.logs.push(`[Proceso cerrado con código ${code}]`);
            });

            this.terminals.set(id, termObj);
            console.log(`[Terminal Manager] Nueva terminal '${name}' creada con PID ${child.pid}`);
            return termObj;
        } catch (e) {
            console.error("[Terminal Spawn Error]", e.message);
            return null;
        }
    }

    async execCommand(termId, command) {
        const term = this.terminals.get(termId || "pi-cli");
        if (term) {
            term.logs.push(`$ ${command}`);
            if (term.id === "pi-cli") {
                const out = await piRpc.sendPrompt(command);
                term.logs.push(out);
                return out;
            }
            if (term.child && term.child.stdin && !term.child.killed) {
                term.child.stdin.write(command + "\n");
                // Esperar 600ms para capturar la salida inmediata de stdout/stderr
                await new Promise(resolve => setTimeout(resolve, 600));
                return `Comando enviado a ${term.name}`;
            }
        }

        const activeProj = resolveProject(activeProjectId);
        return new Promise((resolve) => {
            const shellExe = process.platform === "win32" ? "cmd.exe" : "bash";
            const flag = process.platform === "win32" ? "/c" : "-c";
            const execProc = spawn(shellExe, [flag, command], {
                cwd: activeProj.path,
                env: process.env,
                shell: true
            });

            let output = "";
            execProc.stdout.on("data", d => { output += d.toString(); });
            execProc.stderr.on("data", d => { output += d.toString(); });
            execProc.on("close", () => {
                resolve(output || "Comando ejecutado.");
            });
        });
    }

    restartServer(termId) {
        if (termId.startsWith("proc-")) {
            const pid = parseInt(termId.replace("proc-", ""), 10);
            if (pid && !isNaN(pid)) {
                try {
                    execSync(`taskkill /F /PID ${pid}`, { timeout: 3000, maxBuffer: EXEC_MAX_BUFFER });
                    return `Proceso real (PID ${pid}) finalizado/detenido en tu PC exitosamente.`;
                } catch (e) {
                    return `Error al finalizar proceso PID ${pid}: ${e.message}`;
                }
            }
        }

        const term = this.terminals.get(termId || "pi-cli");
        if (term) {
            if (term.id === "pi-cli") {
                piRpc.switchProject();
                term.logs.push("[System] Servidor RPC Pi CLI reiniciado correctamente.");
                return "Pi CLI RPC reiniciado.";
            }

            if (term.child && !term.child.killed) {
                term.child.kill("SIGTERM");
                term.logs.push("[System] Proceso de terminal finalizado. Reiniciando...");
                const newTerm = this.spawnNewTerminal(term.name);
                return `Terminal '${term.name}' reiniciada exitosamente.`;
            }
        }

        return "Proceso/Servidor reseteado.";
    }

    closeTerminal(termId) {
        if (termId.startsWith("proc-")) {
            const pid = parseInt(termId.replace("proc-", ""), 10);
            if (pid && !isNaN(pid)) {
                try {
                    execSync(`taskkill /F /PID ${pid}`, { timeout: 3000, maxBuffer: EXEC_MAX_BUFFER });
                    return true;
                } catch (e) {}
            }
        }

        const term = this.terminals.get(termId);
        if (term && term.id !== "pi-cli") {
            if (term.child && !term.child.killed) term.child.kill("SIGTERM");
            this.terminals.delete(termId);
            return true;
        }
        return false;
    }
}

const terminalMgr = new TerminalManager();

// Mapa global en memoria para resolver sessionId -> filePath en <1ms
const sessionFilePathMap = new Map();

// Normalizes an absolute path for exact cross-platform comparison: lowercase,
// forward slashes, no trailing slash. Session metadata and the project registry
// both funnel through this before being compared -- matching sessions to a
// project by path-containment ("includes") instead of exact equality let a
// short/common project name (or one path being a substring of another, e.g.
// a worktree sibling) silently pull in sessions that belong to other projects.
function normalizePathForCompare(p) {
    return (p || "").toLowerCase().replace(/\\/g, "/").replace(/\/+$/, "");
}

// ----------------------------------------------------------------------------
// Session listing (session-listing-accuracy-perf, PR3) -- wires PR1/PR2's
// pure/bounded-I/O `lib/sessions.mjs` functions into real per-engine scans,
// an in-memory lightweight-index cache (Pi/Claude only -- see design.md's
// "Cache & Lightweight Index Contract"), and OpenCode's SQL-scoped WHERE +
// cursor + LIMIT query. See design.md's "Combined-page algorithm" and
// "Response Envelope" sections for the full contract this implements.
// ----------------------------------------------------------------------------

// Rebuilds a Pi/Claude engine's lightweight index at most once per this TTL
// per (project, engine). This bounds the warm path to O(page size) between
// rebuilds (spec's "Per-Page Bounded Work") while staying simple -- the
// design's own freshness-token recomputation costs as much as a rebuild for
// both engines (buildClaudeIndex/buildPiIndex compute it as a byproduct of
// the same scan), so a cheap separate revalidation isn't available; a
// bounded TTL is the pragmatic, honestly-stated middle ground (see the
// design's own "Known limitation, stated plainly" on freshness-token gaps).
const SESSIONS_INDEX_CACHE_TTL_MS = 15000;
const sessionsIndexCache = new Map(); // key: `${normProjPath}::${engine}` -> {records, freshnessToken, builtAt}

// TEST SEAM (behavior-preserving): `opts.now` overrides the clock and
// `opts.buildIndex` overrides the real Pi/Claude index builder so the TTL
// contract can be exercised deterministically. Production callers pass no
// `opts`, so the real clock and builders are used.
function getOrBuildEngineIndex(engine, normProjPath, opts = {}) {
    const now = opts.now || Date.now;
    const buildIndex = opts.buildIndex || null;
    const cacheKey = `${normProjPath}::${engine}`;
    const cached = sessionsIndexCache.get(cacheKey);
    if (cached && (now() - cached.builtAt) < SESSIONS_INDEX_CACHE_TTL_MS) {
        return cached;
    }

    let built;
    if (buildIndex) {
        built = buildIndex(engine, normProjPath);
    } else if (engine === "claude") {
        const claudeProjectsDir = join(homedir(), ".claude", "projects");
        const normFull = normProjPath.replace(/[^a-z0-9]/g, "-");
        let matchedDir = null;
        if (existsSync(claudeProjectsDir)) {
            for (const subdir of readdirSync(claudeProjectsDir)) {
                const full = join(claudeProjectsDir, subdir);
                if (!statSync(full).isDirectory()) continue;
                const normSubdir = subdir.toLowerCase().replace(/[^a-z0-9]/g, "-");
                if (normSubdir === normFull) { matchedDir = full; break; }
            }
        }
        built = matchedDir ? buildClaudeIndex(matchedDir) : { records: [], freshnessToken: 0 };
    } else if (engine === "pi-cli") {
        const piSessionsBaseDir = join(homedir(), ".pi", "agent", "sessions");
        built = buildPiIndex(piSessionsBaseDir, normProjPath);
    } else {
        throw new Error(`getOrBuildEngineIndex: unsupported engine '${engine}'`);
    }

    const entry = { records: built.records, freshnessToken: built.freshnessToken, builtAt: now() };
    sessionsIndexCache.set(cacheKey, entry);
    return entry;
}

// Bounded head+tail hydration window (design's "Content hydration" section):
// starts at 8 KB, doubles on a miss, caps at 256 KB per side -- never a
// full-file read for a large session.
const HYDRATION_WINDOW_START = 8 * 1024;
const HYDRATION_WINDOW_CAP = 256 * 1024;

/**
 * Bounded head+tail hydration for title/preview, page-scoped: only called
 * for the `limit` items actually returned, never for the full candidate set.
 * `parseLine(line)` returns `{role:"user"|"assistant", text}` or `null`.
 */
function hydrateHeadTail(filePath, parseLine) {
    const size = statSync(filePath).size;
    const fd = openSync(filePath, "r");
    try {
        let firstPrompt = "";
        let lastReply = "";

        for (let w = HYDRATION_WINDOW_START; ; w = Math.min(w * 2, HYDRATION_WINDOW_CAP)) {
            const capped = Math.min(w, size);
            const buf = Buffer.alloc(capped);
            readSync(fd, buf, 0, capped, 0);
            const text = buf.toString("utf8");
            const lines = capped < size ? text.split("\n").slice(0, -1) : text.split("\n");
            for (const line of lines) {
                const entry = parseLine(line);
                if (entry && entry.role === "user" && entry.text) { firstPrompt = entry.text; break; }
            }
            if (firstPrompt || capped >= HYDRATION_WINDOW_CAP || capped >= size) break;
        }

        for (let w = HYDRATION_WINDOW_START; ; w = Math.min(w * 2, HYDRATION_WINDOW_CAP)) {
            const capped = Math.min(w, size);
            const pos = Math.max(0, size - capped);
            const buf = Buffer.alloc(capped);
            readSync(fd, buf, 0, capped, pos);
            const text = buf.toString("utf8");
            // A window that doesn't start at byte 0 may begin mid-line; drop
            // its (possibly partial) leading line, per the design's UTF-8/
            // line-boundary handling note.
            const lines = pos > 0 ? text.split("\n").slice(1) : text.split("\n");
            for (let i = lines.length - 1; i >= 0; i--) {
                const entry = parseLine(lines[i]);
                if (entry && entry.role === "assistant" && entry.text) { lastReply = entry.text; break; }
            }
            if (lastReply || capped >= HYDRATION_WINDOW_CAP || capped >= size) break;
        }

        return { firstPrompt, lastReply };
    } finally {
        closeSync(fd);
    }
}

function parseClaudeLine(line) {
    const trimmed = line.trim();
    if (!trimmed) return null;
    try {
        const entry = JSON.parse(trimmed);
        if (entry.type === "user" && entry.message?.content) {
            const text = typeof entry.message.content === "string" ? entry.message.content : "";
            if (text && !text.startsWith("<local-command-caveat>") && !text.startsWith("<command-name>")) {
                return { role: "user", text };
            }
        }
        if (entry.type === "assistant" && entry.message?.content) {
            const text = typeof entry.message.content === "string" ? entry.message.content : "";
            if (text) return { role: "assistant", text };
        }
    } catch { /* possibly a truncated/partial line at a window boundary -- ignore */ }
    return null;
}

function parsePiLine(line) {
    const trimmed = line.trim();
    if (!trimmed) return null;
    try {
        const entry = JSON.parse(trimmed);
        if (entry.type === "message" && entry.message?.role) {
            const text = entry.message.content?.find(c => c.type === "text")?.text;
            if (text) return { role: entry.message.role, text };
        }
    } catch { /* possibly a truncated/partial line at a window boundary -- ignore */ }
    return null;
}

function buildPiClaudeSessionItem(engine, record, projName) {
    const parseLine = engine === "claude" ? parseClaudeLine : parsePiLine;
    const engineLabel = engine === "claude" ? "Claude" : "Pi";
    let firstPrompt = "";
    let lastReply = "";
    try {
        ({ firstPrompt, lastReply } = hydrateHeadTail(record.filePath, parseLine));
    } catch (err) {
        console.error(`[${engineLabel} Sessions Scan Error]`, record.filePath, err.message);
    }

    const sid = record.id;
    sessionFilePathMap.set(sid, record.filePath);

    const formattedDate = new Date(record.timestamp).toLocaleString("es-ES", {
        day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit"
    });
    const titleText = firstPrompt || `Sesión ${engineLabel} (${sid.slice(0, 8)})`;

    // No fabricated message count for Pi/Claude (spec's "No Fabricated
    // Session Metadata"): a bounded read cannot substantiate an exact count.
    return {
        id: sid,
        title: titleText.length > 40 ? titleText.slice(0, 37) + "..." : titleText,
        project: projName,
        date: formattedDate,
        messageCount: null,
        isFavorite: false,
        preview: lastReply || firstPrompt || `Sesión registrada en tu PC por ${engineLabel} CLI.`,
        engine,
        rawTimestamp: record.timestamp
    };
}

function buildOpenCodeSessionItem(row, projName) {
    const formattedDate = new Date(row.ts).toLocaleString("es-ES", {
        day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit"
    });
    const titleText = row.title || `Sesión OpenCode (${row.id.slice(0, 8)})`;
    return {
        id: row.id,
        title: titleText.length > 40 ? titleText.slice(0, 37) + "..." : titleText,
        project: projName,
        date: formattedDate,
        // Exact via SQL COUNT -- free, no extra I/O (design decision #8).
        messageCount: typeof row.msgCount === "number" ? row.msgCount : null,
        isFavorite: false,
        preview: "Sesión registrada en tu PC por OpenCode Engine.",
        engine: "opencode",
        rawTimestamp: row.ts
    };
}

/**
 * Runs `opencode db <sql> --format json` via the resolved native executable
 * -- `execFile(resolvedExe, argv, {timeout})`, never `execSync`, never a
 * shell string. This is the fix for both the SQL-construction surface (argv
 * array, no shell interpolation of the invocation itself) and the
 * concurrent-SSE-stall defect (`execFile` never blocks the event loop) --
 * see design.md's "OpenCode Invocation -- Verified Resolution".
 */
function execOpenCodeDb(sql) {
    return new Promise((resolve, reject) => {
        let exePath;
        try {
            exePath = resolveOpenCodeExePath();
        } catch (err) {
            reject(err);
            return;
        }
        execFile(
            exePath,
            ["db", sql, "--format", "json"],
            { timeout: 4000, encoding: "utf8" },
            (err, stdout) => {
                if (err) { reject(err); return; }
                try {
                    resolve(JSON.parse(stdout));
                } catch (parseErr) {
                    // Guard del scan: `opencode db` puede devolver JSON vacío o
                    // incompleto cuando el CLI está ocupado o imprime un aviso en
                    // stdout ("Unexpected end of JSON input" observado en el log
                    // real). Un scan de sesiones no debe fallar (502) por una
                    // respuesta ruidosa: se registra el aviso estructurado y se
                    // degrada a resultado vacío -- las queries de acá siempre
                    // devuelven arrays, así que [] es un resultado legítimo.
                    console.warn(`[OpenCode DB JSON Parse Warning] ${parseErr.message} — sql="${String(sql).slice(0, 90)}" salida="${String(stdout).slice(0, 140)}"`);
                    resolve([]);
                }
            }
        );
    });
}

/**
 * Builds the cursor predicate fragment for the OpenCode SQL WHERE clause.
 * Every interpolated value goes through PR1's `escapeSqlValue`/
 * `validateNumeric` first, per the SQL Safety Contract -- no other path may
 * construct this SQL string.
 */
function buildOpenCodeCursorClause(subCursor) {
    if (!subCursor) return "";
    const ts = validateNumeric(subCursor.ts);
    const id = escapeSqlValue(String(subCursor.id));
    return ` AND (s.time_updated < ${ts} OR (s.time_updated = ${ts} AND s.id < '${id}'))`;
}

/**
 * The two kinds of row a session list should not open with.
 *
 * `parent_id` is OpenCode's own marker: NULL for a session the user started,
 * set for one a subagent ran under. Measured on this project's real store, 99
 * of 108 sessions were children -- the list was 92% delegated work nobody
 * opened by hand. The second clause drops a session with a single exchange: a
 * one-shot run, a test, an abandoned start.
 *
 * It belongs in SQL, not in a filter over the fetched page: dropping rows after
 * the LIMIT returns short pages and makes the cursor skip ahead. The listing
 * and the count share this one clause so they cannot disagree -- a count that
 * ignored it would report 108 beside a list of 9.
 *
 * No value is interpolated here, so nothing needs escaping; every other
 * interpolation in these queries still goes through escapeSqlValue /
 * validateNumeric per the SQL Safety Contract.
 */
function buildOpenCodeNoiseClause(includeAll) {
    return includeAll
        ? ""
        : " AND s.parent_id IS NULL AND (SELECT COUNT(*) FROM message m2 WHERE m2.session_id = s.id) > 2";
}

async function fetchOpenCodePage(normProjPath, subCursor, limit, includeAll = false) {
    const escapedDir = escapeSqlValue(normProjPath);
    const validLimit = validateNumeric(limit, { integer: true, min: 1, max: 200 });
    const cursorClause = buildOpenCodeCursorClause(subCursor);
    const noiseClause = buildOpenCodeNoiseClause(includeAll);
    const sql = `SELECT s.id, s.directory, s.title, s.time_updated, (SELECT COUNT(*) FROM message m WHERE m.session_id = s.id) as msg_count FROM session s WHERE REPLACE(LOWER(s.directory), '\\', '/') = '${escapedDir}'${noiseClause}${cursorClause} ORDER BY s.time_updated DESC, s.id DESC LIMIT ${validLimit}`;
    const rows = await execOpenCodeDb(sql);
    return rows.map(r => ({ ts: r.time_updated, id: r.id, title: r.title, msgCount: r.msg_count }));
}

async function countOpenCodeSessions(normProjPath, includeAll = false) {
    const escapedDir = escapeSqlValue(normProjPath);
    const noiseClause = buildOpenCodeNoiseClause(includeAll);
    const sql = `SELECT COUNT(*) as cnt FROM session s WHERE REPLACE(LOWER(s.directory), '\\', '/') = '${escapedDir}'${noiseClause}`;
    const rows = await execOpenCodeDb(sql);
    return (rows && rows[0] && typeof rows[0].cnt === "number") ? rows[0].cnt : 0;
}

const ENGINE_LABELS = { "claude": "Claude", "pi-cli": "Pi", "opencode": "OpenCode" };

/**
 * Fetches up to `limit` items for one engine, applying `subCursor`
 * (`{ts,id}` or `null` for first page). Never throws to the caller --
 * scan/query failures are logged (structured, per design's
 * Failure/Partial-Success Contract) and reported as `{failed: true}`, never
 * a silent empty result.
 */
async function fetchEnginePageResult(engine, normProjPath, subCursor, limit, includeAll = false) {
    try {
        if (engine === "opencode") {
            const rows = await fetchOpenCodePage(normProjPath, subCursor, limit, includeAll);
            return { failed: false, items: rows.map(r => ({ ts: r.ts, id: r.id, row: r })) };
        }

        const { records } = getOrBuildEngineIndex(engine, normProjPath);
        let startIdx = 0;
        if (subCursor) {
            startIdx = records.findIndex(
                r => r.timestamp < subCursor.ts || (r.timestamp === subCursor.ts && r.id < subCursor.id)
            );
            if (startIdx === -1) startIdx = records.length;
        }
        const pageRecords = records.slice(startIdx, startIdx + limit);
        return { failed: false, items: pageRecords.map(r => ({ ts: r.timestamp, id: r.id, record: r })) };
    } catch (err) {
        console.error(`[${ENGINE_LABELS[engine] || engine} Sessions Scan Error]`, normProjPath, err.message);
        return { failed: true };
    }
}

function toSessionItem(engine, item, projName) {
    return engine === "opencode"
        ? buildOpenCodeSessionItem(item.row, projName)
        : buildPiClaudeSessionItem(engine, item.record, projName);
}

/**
 * Answers one `GET /api/v1/sessions` request -- per-engine bounded page, or
 * the `engine=all` k-way merge via PR1's `mergePage`. Returns the one
 * documented envelope for a successful (or `engine=all` partially-failed)
 * request; throws (with `httpStatus`/`engine` attached) for a per-engine
 * scan failure, which the route handler maps to a 502.
 */
async function fetchSessionsPage({ engine, cursorParam, limit, projPath, projName, includeAll = false, deps = {} }) {
    const fetchEngine = deps.fetchEnginePageResult || fetchEnginePageResult;
    const normProjPath = normalizePathForCompare(projPath);

    if (engine !== "all") {
        let subCursor = null;
        if (cursorParam) {
            try {
                subCursor = decodeCursor(cursorParam);
            } catch (err) {
                throw Object.assign(new Error(`Invalid cursor: ${err.message}`), { httpStatus: 502, engine });
            }
        }
        if (subCursor === DONE) {
            return { sessions: [], nextCursor: null, partial: false, failures: [] };
        }

        const result = await fetchEngine(engine, normProjPath, subCursor, limit, includeAll);
        if (result.failed) {
            throw Object.assign(new Error(`Failed to scan ${engine} sessions`), { httpStatus: 502, engine });
        }

        const n = result.items.length;
        const isDone = n < limit;
        const nextCursor = isDone
            ? null
            : encodeCursor({ ts: result.items[n - 1].ts, id: result.items[n - 1].id });

        return {
            sessions: result.items.map(it => toSessionItem(engine, it, projName)),
            nextCursor,
            partial: false,
            failures: []
        };
    }

    // engine=all: combined k-way merge (design's "Combined-page algorithm").
    let combinedCursor = { "pi-cli": null, "opencode": null, "claude": null };
    if (cursorParam) {
        try {
            const decoded = decodeCursor(cursorParam);
            if (decoded && typeof decoded === "object") combinedCursor = { ...combinedCursor, ...decoded };
        } catch {
            // Malformed combined cursor: degrade to first page rather than
            // fail the whole "Todos" view -- there is no documented 502
            // shape for engine=all (only the one envelope).
        }
    }

    const engineStates = {};
    const failures = [];
    const fetchPromises = [];

    for (const name of ENGINES) {
        const cursor = combinedCursor[name] ?? null;
        engineStates[name] = { cursor };
        if (cursor === DONE) continue;
        fetchPromises.push(
            fetchEngine(name, normProjPath, cursor, limit, includeAll).then((r) => {
                engineStates[name].fetchResult = r.failed ? { failed: true } : { failed: false, items: r.items };
                if (r.failed) failures.push({ engine: name, message: `Failed to scan ${name} sessions` });
            })
        );
    }
    await Promise.all(fetchPromises);

    const merged = mergePage(limit, engineStates);

    return {
        sessions: merged.items.map(it => toSessionItem(it.engine, it, projName)),
        nextCursor: merged.done ? null : encodeCursor(merged.nextCursors),
        partial: failures.length > 0,
        failures
    };
}

// TEST SEAM (behavior-preserving): export the sessions functions so the test
// suite can exercise them directly (with injected deps/clocks) without spawning
// the HTTP server. Production code never imports these names.
export {
    SESSIONS_INDEX_CACHE_TTL_MS,
    sessionsIndexCache,
    getOrBuildEngineIndex,
    fetchEnginePageResult,
    fetchSessionsPage,
    toSessionItem,
    buildPiClaudeSessionItem,
    buildOpenCodeSessionItem,
    execOpenCodeDb,
    describeToolInput,
    describeSubagentName,
    isSubagentTool,
    isAsyncDispatchReceipt,
    summarizeSubagentResult
};

/**
 * Answers `GET /api/v1/sessions/counts` -- true per-engine counts (never
 * derived from a truncated listing), reading the same lightweight-index
 * cache / OpenCode SQL aggregate the listing uses. Failed engines' keys are
 * omitted from `counts`, never reported as `0` (design's `/counts` section).
 */
async function fetchSessionCounts(projPath, includeAll = false) {
    const normProjPath = normalizePathForCompare(projPath);
    const failures = [];

    const results = await Promise.all(ENGINES.map(async (engine) => {
        try {
            const count = engine === "opencode"
                ? await countOpenCodeSessions(normProjPath, includeAll)
                : getOrBuildEngineIndex(engine, normProjPath).records.length;
            return { engine, count };
        } catch (err) {
            console.error(`[${ENGINE_LABELS[engine] || engine} Sessions Scan Error]`, normProjPath, err.message);
            failures.push({ engine, message: `Failed to count ${engine} sessions` });
            return { engine, count: null };
        }
    }));

    const counts = {};
    let allTotal = 0;
    let anySucceeded = false;
    for (const r of results) {
        if (r.count !== null) {
            counts[r.engine] = r.count;
            allTotal += r.count;
            anySucceeded = true;
        }
    }
    if (anySucceeded) counts.all = allTotal;

    return { counts, partial: failures.length > 0, failures, allFailed: !anySucceeded };
}

function findSessionFilePath(sessionId) {
    if (sessionFilePathMap.has(sessionId)) {
        const cachedPath = sessionFilePathMap.get(sessionId);
        if (existsSync(cachedPath)) return cachedPath;
    }

    // 1. Buscar en ~/.claude/projects/
    try {
        const claudeProjectsDir = join(homedir(), ".claude", "projects");
        if (existsSync(claudeProjectsDir)) {
            const subdirs = readdirSync(claudeProjectsDir);
            for (const subdir of subdirs) {
                const dirFullPath = join(claudeProjectsDir, subdir);
                if (!statSync(dirFullPath).isDirectory()) continue;
                const files = readdirSync(dirFullPath).filter(f => f.endsWith(".jsonl"));
                for (const f of files) {
                    if (f.includes(sessionId)) {
                        const fullP = join(dirFullPath, f);
                        sessionFilePathMap.set(sessionId, fullP);
                        return fullP;
                    }
                }
            }
        }
    } catch (e) {}

    // 2. Buscar en ~/.pi/agent/sessions/
    const sessionsBaseDir = join(homedir(), ".pi", "agent", "sessions");
    if (!existsSync(sessionsBaseDir)) return null;

    try {
        const subdirs = readdirSync(sessionsBaseDir);
        for (const subdir of subdirs) {
            const dirFullPath = join(sessionsBaseDir, subdir);
            if (!statSync(dirFullPath).isDirectory()) continue;

            const files = readdirSync(dirFullPath).filter(f => f.endsWith(".jsonl"));
            for (const f of files) {
                if (f.includes(sessionId)) {
                    const fullP = join(dirFullPath, f);
                    sessionFilePathMap.set(sessionId, fullP);
                    return fullP;
                }
            }
        }
    } catch (e) {}

    return null;
}

/**
 * History for one session, or null when there is genuinely nothing to read.
 * Throws when the session exists but reading it failed, so the caller can tell
 * "not found" apart from "found but unreadable" instead of reporting both as 404.
 */
function getSessionHistoryById(sessionId) {
    let exportError = null;
    if (sessionId.startsWith("ses_")) {
        try {
            const raw = execSync(`opencode export ${sessionId}`, { encoding: "utf8", timeout: 8000, maxBuffer: EXEC_MAX_BUFFER });
            const jsonText = raw.slice(raw.indexOf("{"));
            const data = JSON.parse(jsonText);
            const messages = [];

            if (Array.isArray(data.messages)) {
                for (let i = 0; i < data.messages.length; i++) {
                    const msg = data.messages[i];
                    const role = msg.info?.role;
                    if (role === "user" || role === "assistant") {
                        const parts = msg.parts || [];
                        const textParts = parts.filter(p => p.type === "text").map(p => p.text || "").join("\n").trim();
                        if (textParts) {
                            messages.push({
                                id: msg.info.id || `opencode-msg-${i}`,
                                role: role,
                                text: textParts.replace(/^"|"$/g, ""),
                                timestamp: msg.info.time?.created || Date.now()
                            });
                        }
                    }
                }
            }
            return { messages, model: data.info?.model?.id || "gpt-5.6-luna" };
        } catch (e) {
            console.error("[OpenCode Export History Error]", e.message);
            // The session exists; reading it failed. Falling through used to
            // end in a 404 "Sesión no encontrada", which sent the app looking
            // for a missing session while the real problem was the export --
            // and left the user able to chat in a session whose history
            // supposedly did not exist.
            exportError = e;
        }
    }

    const targetFilePath = findSessionFilePath(sessionId);
    if (!targetFilePath || !existsSync(targetFilePath)) {
        if (exportError) throw exportError;
        return null;
    }

    const messages = [];
    let detectedModel = null;
    let detectedProvider = null;

    try {
        const content = readFileSync(targetFilePath, "utf8");
        const lines = content.split("\n").filter(l => l.trim());

        for (let i = 0; i < lines.length; i++) {
            try {
                const entry = JSON.parse(lines[i]);

                if (entry.type === "model_change") {
                    if (entry.modelId || entry.model) detectedModel = entry.modelId || entry.model;
                    if (entry.provider) detectedProvider = entry.provider;
                }

                // Entrada formato Pi CLI
                if (entry.type === "message" && entry.message) {
                    const role = entry.message.role;
                    const text = typeof entry.message.content === "string" ? entry.message.content : (Array.isArray(entry.message.content) ? entry.message.content.find(c => c.type === "text")?.text || "" : "");
                    const ts = entry.timestamp || entry.message.timestamp || Date.now();

                    if (text && !text.startsWith("<local-command-caveat>") && (role === "user" || role === "assistant")) {
                        messages.push({
                            id: entry.id || `msg-${i}`,
                            role: role,
                            text: text,
                            timestamp: typeof ts === "number" ? ts : new Date(ts).getTime()
                        });
                    }
                }

                // Entrada formato Claude CLI
                if (entry.type === "user" && entry.message?.content) {
                    const raw = entry.message.content;
                    const text = typeof raw === "string" ? raw : (Array.isArray(raw) ? raw.find(c => c.type === "text")?.text || "" : "");
                    const ts = entry.timestamp || Date.now();
                    if (text && !text.startsWith("<local-command-caveat>") && 
                                !text.startsWith("<command-name>") && 
                                !text.startsWith("<task-notification>") &&
                                !text.startsWith("<persisted-output>")) {
                        messages.push({
                            id: entry.uuid || `msg-${i}`,
                            role: "user",
                            text: text,
                            timestamp: typeof ts === "number" ? ts : new Date(ts).getTime()
                        });
                    }
                } else if (entry.type === "assistant" && entry.message?.content) {
                    const raw = entry.message.content;
                    const text = typeof raw === "string" ? raw : (Array.isArray(raw) ? raw.filter(c => c.type === "text").map(c => c.text).join("\n") : "");
                    const ts = entry.timestamp || Date.now();
                    if (text && !text.startsWith("<task-notification>") && !text.startsWith("<local-command-caveat>")) {
                        messages.push({
                            id: entry.uuid || `msg-${i}`,
                            role: "assistant",
                            text: text,
                            timestamp: typeof ts === "number" ? ts : new Date(ts).getTime()
                        });
                    }
                }
            } catch (e) {}
        }
    } catch (e) {}

    if (detectedModel) {
        currentStatus.operatingAi = detectedModel;
        if (detectedProvider) currentStatus.operatingProvider = detectedProvider;
        // Las entradas model_change son formato Pi; una sesión Claude (bajo
        // ~/.claude) nunca debe reescribir el modelo del motor Pi. OpenCode ya
        // retornó antes por el prefijo ses_, así que aquí solo puede ser Pi.
        if (!targetFilePath.includes(".claude")) {
            piRpc.setModel(detectedModel, detectedProvider);
        }
        console.log(`[Session History] Modelo detectado en sesión: ${detectedProvider ? detectedProvider + '/' : ''}${detectedModel}`);
    }

    return { filePath: targetFilePath, messages, model: detectedModel, provider: detectedProvider };
}

// ----------------------------------------------------------------------------
// Explorador Visual de Carpetas y Discos de PC
// ----------------------------------------------------------------------------
function getAvailableDrives() {
    const drives = [];
    const letters = "CDEFGHIJKLMNOPQRSTUVWXYZ".split("");
    for (const letter of letters) {
        const driveRoot = `${letter}:\\`;
        try {
            if (existsSync(driveRoot)) {
                drives.push({
                    name: `Disco (${letter}:)`,
                    path: driveRoot,
                    isProject: false
                });
            }
        } catch (e) {}
    }
    return drives;
}

function getFileSystemShortcuts() {
    const home = homedir();
    const list = [
        { name: "Usuario", path: home, isProject: false },
        { name: "Escritorio", path: join(home, "Desktop"), isProject: false },
        { name: "Documentos", path: join(home, "Documents"), isProject: false },
        { name: "Descargas", path: join(home, "Downloads"), isProject: false }
    ];
    const commonDev = [
        join(home, "AndroidStudioProjects"),
        join(home, "dev"),
        join(home, "Projects"),
        join(home, "Proyectos"),
        join(home, "workspace"),
        join(home, "repos")
    ];
    for (const devPath of commonDev) {
        try {
            if (existsSync(devPath)) {
                list.push({ name: devPath.split(/[\\/]/).pop() || devPath, path: devPath, isProject: true });
            }
        } catch (e) {}
    }
    return list.filter(item => {
        try { return existsSync(item.path); } catch (e) { return false; }
    });
}

function normalizeBrowsePath(targetPath) {
    if (!targetPath || !targetPath.trim()) return "";
    let trimmed = targetPath.trim();
    if (/^[a-zA-Z]:[/\\]?$/.test(trimmed)) {
        return trimmed.slice(0, 1).toUpperCase() + ":\\";
    }
    trimmed = trimmed.replace(/[/\\]+$/, "");
    return trimmed;
}

function getParentBrowsePath(normPath) {
    if (!normPath) return null;
    if (/^[a-zA-Z]:\\?$/.test(normPath)) {
        return "";
    }
    let parent = dirname(normPath);
    if (/^[a-zA-Z]:\\?$/.test(parent)) {
        return parent.slice(0, 1).toUpperCase() + ":\\";
    }
    if (parent === normPath) {
        return "";
    }
    return parent;
}

function browseFileSystem(targetPath) {
    const norm = normalizeBrowsePath(targetPath);
    if (!norm) {
        return {
            currentPath: "",
            parentPath: null,
            shortcuts: getFileSystemShortcuts(),
            directories: getAvailableDrives()
        };
    }

    const parent = getParentBrowsePath(norm);
    const directories = [];

    try {
        if (existsSync(norm)) {
            const entries = readdirSync(norm, { withFileTypes: true });
            for (const entry of entries) {
                if (entry.isDirectory()) {
                    const name = entry.name;
                    if (name.startsWith("$") || name.startsWith(".") || name === "System Volume Information" || name === "Recovery") {
                        continue;
                    }
                    const fullP = join(norm, name);
                    let isProj = false;
                    let projType = null;
                    try {
                        if (existsSync(join(fullP, ".git"))) { isProj = true; projType = "Git"; }
                        else if (existsSync(join(fullP, "package.json"))) { isProj = true; projType = "Node.js"; }
                        else if (existsSync(join(fullP, "build.gradle")) || existsSync(join(fullP, "build.gradle.kts"))) { isProj = true; projType = "Android / Gradle"; }
                        else if (existsSync(join(fullP, "requirements.txt")) || existsSync(join(fullP, "pyproject.toml"))) { isProj = true; projType = "Python"; }
                    } catch (e) {}

                    directories.push({
                        name: name,
                        path: fullP,
                        isProject: isProj,
                        projectType: projType
                    });
                }
            }
        }
    } catch (e) {
        console.error("[Browse Error]", e.message);
    }

    return {
        currentPath: norm,
        parentPath: parent,
        shortcuts: getFileSystemShortcuts(),
        directories: directories.sort((a, b) => (b.isProject ? 1 : 0) - (a.isProject ? 1 : 0) || a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }))
    };
}

const filesTreeCache = new Map();

function getProjectFilesTree(dirPath, relativeTo = dirPath) {
    const EXCLUDED = ["node_modules", ".git", "build", ".gradle", ".idea", "bin", "obj", ".codegraph", ".system_generated"];
    const results = [];

    try {
        const stat = statSync(dirPath);
        const cached = filesTreeCache.get(dirPath);
        if (cached && cached.mtime >= stat.mtimeMs) {
            return cached.tree;
        }

        const items = readdirSync(dirPath);
        for (const item of items) {
            if (EXCLUDED.includes(item)) continue;
            const fullPath = join(dirPath, item);
            const relPath = relative(relativeTo, fullPath).replace(/\\/g, "/");
            try {
                const itemStat = statSync(fullPath);
                if (itemStat.isDirectory()) {
                    results.push({
                        name: item,
                        path: relPath,
                        isDirectory: true,
                        children: getProjectFilesTree(fullPath, relativeTo),
                        status: "Synced"
                    });
                } else {
                    results.push({
                        name: item,
                        path: relPath,
                        isDirectory: false,
                        children: [],
                        status: "Synced"
                    });
                }
            } catch (e) {}
        }

        filesTreeCache.set(dirPath, { mtime: stat.mtimeMs, tree: results });
    } catch (e) {
        console.error(`[Explorer Error] No se pudo leer ${dirPath}:`, e.message);
    }

    return results;
}

// Gestor de Proceso Pi CLI RPC Persistente con Cambio de Modelo por Parámetros CLI (--provider y --model)
// Shared by runClaudeCli, runOpenCodeCli and PiRpcManager.sendPrompt -- if the
// underlying agent CLI (or an MCP server it spawns) never closes/replies, the
// request must not hang the chat forever (observed live: a hung
// `npx @playwright/mcp` left an OpenCode chat stuck on "Pensando..." for 9+
// minutes with no recovery).
//
// MUST stay at module scope. It previously sat inside the http.createServer
// request handler, where runClaudeCli/runOpenCodeCli could see it but
// PiRpcManager -- declared above that handler -- could not: every Pi message
// threw ReferenceError and returned 500.
// Node's default stdout buffer for a synchronous exec is 1 MB, and exceeding it
// throws ENOBUFS. `opencode export` on a large session hit it, the history
// endpoint degraded into a 404, and the app reported "error al reanudar la
// sesión". Several other calls here can legitimately produce more than a
// megabyte -- `wmic process` on a busy machine, a full model listing, and the
// orchestrator action route, which runs an arbitrary user-supplied command.
const EXEC_MAX_BUFFER = 64 * 1024 * 1024;

const AGENT_CLI_TIMEOUT_MS = 5 * 60 * 1000;

/**
 * Prose out of a Pi `tool_execution_end` result. The previous code did
 * `String(event.result)`, which renders a plain object as the literal string
 * "[object Object]" -- the chat then showed that instead of what the subagent
 * actually reported.
 *
 * Pi's exact result shape for a subagent is not yet captured from a real run,
 * so this handles the shapes we have seen elsewhere and returns null for
 * anything else rather than inventing text. The caller logs the unknown keys
 * so the next real run tells us the field instead of us guessing.
 */
function extractPiResultText(result) {
    if (typeof result === "string") return result;
    if (Array.isArray(result)) {
        return result.map((r) => extractPiResultText(r)).filter(Boolean).join("\n") || null;
    }
    if (!result || typeof result !== "object") return null;
    for (const key of ["text", "output", "summary", "message", "content"]) {
        const v = result[key];
        if (typeof v === "string" && v.trim()) return v;
        if (Array.isArray(v)) {
            const joined = extractPiResultText(v);
            if (joined) return joined;
        }
    }
    return null;
}

class PiRpcManager {
    constructor() {
        this.child = null;
        this.buffer = "";
        this.pendingResolve = null;
        this.pendingReject = null;
        this.currentOutput = "";
        /** Last assistant message Pi closed in this turn; what the POST answers with. */
        this.lastAnswer = "";
        this.currentSessionPath = null;
        this.currentModelId = null;
        this.currentProvider = null;
        /**
         * Model switch requested while a turn was in flight. Applied
         * deterministically at the next turn boundary (agent_end /
         * auto_retry_end / idle-watchdog force-end) by
         * _maybeApplyPendingModelChange(). Never applies mid-turn: killing a
         * busy child would destroy the in-flight answer.
         * @type {{modelId: string, provider: string|null} | null}
         */
        this.pendingModelChange = null;
        this.thinkingAnnounced = false;
        this.pendingTimeoutTimer = null;
        /**
         * Delegation cards still waiting for an async run to report back,
         * oldest first. Insertion-ordered so an unnamed match resolves the
         * longest-waiting card rather than an arbitrary one. An async run
         * outlives the turn that launched it, so this must NOT be cleared at
         * agent_end.
         * @type {Map<string, string>} card id -> subagent name
         */
        this.pendingSubagents = new Map();
    }

    /** Caps at a size no real fan-out reaches, so a lost notice cannot leak. */
    rememberPendingSubagent(id, name) {
        if (!id) return;
        this.pendingSubagents.set(id, name);
        while (this.pendingSubagents.size > 32) {
            this.pendingSubagents.delete(this.pendingSubagents.keys().next().value);
        }
    }

    /**
     * Closes the card an async run belongs to, with what the subagent actually
     * said.
     *
     * pi-subagents reports the child's own run id, while the receipt carried
     * the parent workflow's id, so the two never match directly. The role name
     * is the field both sides share; when it does not identify a card either
     * (a fan-out of same-role children, say) the longest-waiting card is the
     * only defensible choice. With no card at all -- a resumed session, a
     * restarted server -- the answer is still shown as its own card instead of
     * being dropped.
     */
    resolvePendingSubagent(notice) {
        const { id, name, isNew } = resolveNoticeCard(this.pendingSubagents, notice);
        if (isNew) {
            publishChatEvent(CHAT_EVENTS.SUBAGENT_START, { id, name, task: null });
        } else {
            this.pendingSubagents.delete(id);
        }
        // Pi's preview cap cuts the notice inside the first child's output, so
        // the other children are not in the text at all. Saying how many ran is
        // honest; showing one child's answer as if it were the whole result is
        // not.
        publishChatEvent(CHAT_EVENTS.SUBAGENT_END, {
            id,
            name,
            summary: describeNoticeResult(notice),
            ok: notice.ok,
        });
    }

    /**
     * Applies a model switch to the Pi RPC child.
     *
     * The child is PERSISTENT across turns, so a switch only takes effect on
     * the next spawn (ensureStarted() builds --provider/--model from
     * currentProvider/currentModelId). When Pi is idle we restart the child
     * immediately; when a turn is in flight we never kill it mid-answer (that
     * would destroy the in-flight result) — the change is queued in
     * pendingModelChange and applied deterministically at the next turn
     * boundary by _maybeApplyPendingModelChange(). This makes the previously
     * fictional "encolado" log line real: before this fix the vars changed
     * but the child never restarted, so a busy switch silently never applied.
     *
     * A request without a provider CLEARS the stale one (provider is not
     * carried over from a previous selection), and the status surfaces follow
     * the same rule so the app never shows a provider that is no longer in
     * effect.
     *
     * @param {string} modelId
     * @param {string|null} [provider]
     * @returns {{ok: true, applied: boolean, queued?: boolean}}
     */
    setModel(modelId, provider = null) {
        const providerCleared = provider === null || provider === undefined || provider === "";
        const effectiveProvider = providerCleared ? null : provider;

        const modelChanged = this.currentModelId !== modelId;
        const providerChanged = (this.currentProvider ?? null) !== effectiveProvider;
        const changed = modelChanged || providerChanged;

        // Apply the new values immediately: the status surfaces and the next
        // ensureStarted() both read these fields, and the stale provider must
        // never survive a provider-less switch.
        this.currentModelId = modelId;
        this.currentProvider = effectiveProvider;

        currentStatus.operatingAi = modelId;
        currentStatus.operatingProvider = effectiveProvider;

        if (!changed) {
            return { ok: true, applied: false };
        }

        if (this.isBusy) {
            // Never kill a busy child. Queue the change; the turn-boundary
            // hook applies it right after the in-flight answer completes.
            this.pendingModelChange = { modelId, provider: effectiveProvider };
            console.warn(`[IDUPI Pi RPC] Cambio de modelo ${effectiveProvider ? effectiveProvider + '/' : ''}${modelId} encolado - hay un mensaje en vuelo, se aplicará al terminar la consulta`);
            return { ok: true, applied: false, queued: true };
        }

        if (this.child && !this.child.killed) {
            console.log(`[IDUPI Pi RPC] Reiniciando subproceso Pi CLI para aplicar modelo único: ${effectiveProvider ? effectiveProvider + '/' : ''}${modelId}`);
            this.child.kill("SIGTERM");
            this.child = null;
            return { ok: true, applied: true };
        }
        // No live child yet (or it just closed): the next ensureStarted() picks
        // up the new model on spawn — nothing to restart.
        return { ok: true, applied: false };
    }

    /**
     * Drains a queued model switch once Pi is idle. Called from every turn
     * boundary (agent_end completion, auto_retry_end, idle-watchdog force-end)
     * so the restart is deterministic: the change applies exactly when the
     * in-flight answer is done, never mid-turn.
     *
     * Safe to call anywhere: it is a no-op without a queued change, and it
     * re-checks isBusy so a brand-new turn that started between the boundary
     * and this call is never disturbed (the change stays queued for the next
     * boundary).
     */
    _maybeApplyPendingModelChange() {
        if (!this.pendingModelChange) return;
        if (this.isBusy) return; // another turn started — re-check at the next boundary
        const pending = this.pendingModelChange;
        this.pendingModelChange = null;
        if (this.child && !this.child.killed) {
            console.log(`[IDUPI Pi RPC] Aplicando modelo encolado: ${pending.provider ? pending.provider + '/' : ''}${pending.modelId}`);
            this.child.kill("SIGTERM");
            this.child = null;
        }
    }

    ensureStarted() {
        if (this.child && !this.child.killed) return;

        const activeProj = resolveProject(activeProjectId);
        const sessionArgs = this.currentSessionPath ? ["--session", this.currentSessionPath] : [];

        let modelArgs = [];
        if (this.currentProvider && this.currentModelId) {
            modelArgs = ["--provider", this.currentProvider, "--model", this.currentModelId];
        } else if (this.currentModelId) {
            modelArgs = ["--model", this.currentModelId];
        }

        console.log(`[IDUPI Pi RPC] Iniciando Pi CLI RPC en '${activeProj.path}' (modelo: ${modelArgs.join(" ") || 'default'})`);

        this.child = spawn(process.execPath, [PI_CLI_JS, ...modelArgs, ...sessionArgs, "--mode", "rpc"], {
            cwd: activeProj.path,
            env: process.env,
            shell: false,
            windowsHide: true
        });

        this.child.stdout.setEncoding("utf8");
        this.child.stderr.setEncoding("utf8");

        this.child.stdout.on("data", (chunk) => this.onStdout(chunk));
        this.child.stderr.on("data", (chunk) => {
            const errStr = chunk.trim();
            if (errStr) console.error("[IDUPI Pi RPC stderr]", redactActivity(errStr));
        });

        this.child.on("close", (code) => {
            console.log(`[IDUPI Pi RPC] Proceso cerrado con código ${code}`);
            // Phase 3 (fix-ui-request-selection): drop any pending stdin
            // writers bound to this specific child so a late POST never
            // tries to write to a dead pipe. The registry's 120s timer
            // still owns the entry's terminal transition.
            try { clearUiRequestStdinWritersForChild(this.child); } catch {}
            this.child = null;
            if (this.pendingReject) {
                clearTimeout(this.pendingTimeoutTimer);
                this.pendingTimeoutTimer = null;
                this.pendingReject(new Error(`Pi RPC se cerró con código ${code}`));
                this.pendingResolve = null;
                this.pendingReject = null;
            }
        });
    }

    /** True while a chat message is still waiting for its answer. */
    get isBusy() {
        return this.pendingResolve !== null;
    }

    /**
     * (Re)arms the watchdog that kills a Pi process which has gone silent.
     *
     * Pi CLI is a persistent RPC subprocess, not spawned per message, so a
     * genuinely stuck request never closes on its own -- and sendPrompt()
     * refuses new messages while one is pending, so the whole chat would hang.
     *
     * The window used to be measured from the start of the message, which
     * killed real work: a turn running a long chain of tools was terminated at
     * exactly five minutes while Pi was visibly executing bash. It is measured
     * from the last sign of life instead, so the limit is silence, not
     * duration. Pi emits no periodic keepalive -- every event corresponds to
     * actual work -- so a hung tool still produces the silence that fires it.
     */
    armIdleWatchdog() {
        if (!this.pendingResolve) return;
        clearTimeout(this.pendingTimeoutTimer);
        this.pendingTimeoutTimer = setTimeout(() => {
            if (!this.pendingResolve) return;
            const stuckPid = this.child?.pid;
            console.warn(`[IDUPI Pi RPC Timeout] Pi no dio señales de vida en ${AGENT_CLI_TIMEOUT_MS}ms, terminando el proceso (PID ${stuckPid}).`);
            const settleResolve = this.pendingResolve;
            this.pendingResolve = null;
            this.pendingReject = null;
            this.pendingTimeoutTimer = null;
            if (stuckPid) {
                execFile("taskkill", ["/F", "/T", "/PID", String(stuckPid)], () => {});
            }
            this.child = null;
            this.thinkingAnnounced = false;
            publishChatEvent(CHAT_EVENTS.THINKING, { active: false });
            const timeoutMsg = `⚠️ Pi CLI estuvo ${AGENT_CLI_TIMEOUT_MS / 1000}s sin dar señales de vida y fue detenido.`;
            activeTask.output = timeoutMsg;
            publishChatEvent(CHAT_EVENTS.MESSAGE_END, { text: timeoutMsg });
            settleResolve(timeoutMsg);
            this._maybeApplyPendingModelChange();
        }, AGENT_CLI_TIMEOUT_MS);
    }

    /**
     * Binds the next message to a session, restarting the RPC child when the
     * session actually changes.
     *
     * Refuses while a message is in flight. `currentSessionPath` is only ever
     * set here, so a chat that started its own Pi session leaves it null: the
     * user then taps that same session in the list, the resolved path differs
     * from null, and this used to SIGTERM a child that was mid-answer. The
     * close handler turned that into `Pi RPC se cerró con código null` and a
     * 500, and the work was gone -- for the sole act of opening a session.
     *
     * @returns {boolean} false when it was refused because Pi is working.
     */
    resumeSession(sessionPath) {
        if (this.currentSessionPath === sessionPath && this.child && !this.child.killed) {
            console.log(`[IDUPI Pi RPC] Sesión ya activa: ${sessionPath}`);
            return true;
        }
        if (this.isBusy) {
            console.warn(`[IDUPI Pi RPC] Cambio de sesión rechazado: hay una respuesta en curso (${sessionPath})`);
            return false;
        }
        this.currentSessionPath = sessionPath;
        if (this.child && !this.child.killed) {
            console.log(`[IDUPI Pi RPC] Reanudando sesión específica: ${sessionPath}`);
            this.child.kill("SIGTERM");
            this.child = null;
        }
        return true;
    }

    /**
     * Drops the resumed session so the next turn starts a fresh one.
     *
     * Pi's session is not an id the way Claude's and OpenCode's are: it is a
     * live child started with `--session <path>`, so clearing the path and
     * ending the child is what actually creates a new session. The model and
     * provider are deliberately left alone -- ensureStarted() rebuilds the child
     * from them, which is what "nueva pero ya configurada" means.
     *
     * Refuses while a turn is running, for the same reason resumeSession does:
     * SIGTERM on a working child ends it with "Pi RPC se cerró con código null"
     * and the answer in flight is lost.
     *
     * @returns {boolean} false when a turn is in progress and nothing was done.
     */
    startNewSession() {
        if (this.isBusy) {
            console.warn("[IDUPI Pi RPC] Sesión nueva rechazada: hay una respuesta en curso.");
            return false;
        }
        this.currentSessionPath = null;
        if (this.child && !this.child.killed) {
            console.log("[IDUPI Pi RPC] Cerrando la sesión actual para abrir una nueva...");
            this.child.kill("SIGTERM");
            this.child = null;
        }
        return true;
    }

    switchProject() {
        this.currentSessionPath = null;
        if (this.child && !this.child.killed) {
            console.log("[IDUPI Pi RPC] Reiniciando sesión RPC para cambiar de proyecto...");
            this.child.kill("SIGTERM");
            this.child = null;
        }
    }

    onStdout(chunk) {
        this.buffer += chunk;
        let newlineIdx = this.buffer.indexOf("\n");
        while (newlineIdx !== -1) {
            const line = this.buffer.slice(0, newlineIdx).trim();
            this.buffer = this.buffer.slice(newlineIdx + 1);
            if (line) this.handleRpcLine(line);
            newlineIdx = this.buffer.indexOf("\n");
        }
    }

    handleRpcLine(line) {
        try {
            const event = JSON.parse(line);

            // Any event at all is proof Pi is still working, whatever it is.
            // The watchdog measures silence, so this is what stops a long but
            // healthy turn from being killed mid-work.
            this.armIdleWatchdog();

            if (event.type === "model_change" || event.type === "active_model") {
                const m = event.modelId || event.model || event.data?.model;
                const p = event.provider || event.data?.provider;
                if (m) {
                    currentStatus.operatingAi = m;
                    if (p) currentStatus.operatingProvider = p;
                    console.log(`[Pi RPC Event] Modelo cambiado a: ${p ? p + '/' : ''}${m}`);
                }
            }

            const delta = event.assistantMessageEvent;
            if (event.type === "message_update" && delta?.type === "text_delta") {
                let text = delta.delta || "";
                // Filter Pi's internal self-instruction that leaks as text (e.g. "User repeated messages... Keep short... system context shows we're on...")
                // This is not user-facing; it's the model's private plan. Drop it, keep only the real answer.
                if (/User repeated messages/i.test(text) || /system context shows we're on/i.test(text) || /Use the actual date/i.test(text)) {
                    // Strip the instruction line, keep only the real answer after it (after newline)
                    const parts = text.split(/\r?\n/);
                    const realParts = parts.filter(p => !/User repeated messages/i.test(p) && !/system context/i.test(p) && !/Use the actual date/i.test(p) && !/Keep short and in Spanish/i.test(p));
                    text = realParts.join("\n").trim();
                    if (!text) return;
                }
                this.currentOutput += text;
                activeTask.output = this.currentOutput;
                process.stdout.write(text);
                // The first delta means the model stopped deliberating and
                // started answering.
                if (this.thinkingAnnounced) {
                    this.thinkingAnnounced = false;
                    publishChatEvent(CHAT_EVENTS.THINKING, { active: false });
                }
                publishChatEvent(CHAT_EVENTS.TEXT_DELTA, { text });
            }

            // One Pi turn holds SEVERAL assistant messages: the short preamble
            // it writes before reaching for tools, then the real answer after
            // them. All of them were accumulated into a single buffer and
            // delivered as one chat message at agent_end, so the preamble never
            // arrived as its own message -- and the single message_end that did
            // arrive overwrote whatever the app had on screen.
            //
            // Each assistant message is closed here, where Pi itself ends it.
            if (event.type === "message_end" && event.message?.role === "assistant") {
                let fromEvent = event.message.content?.find(c => c.type === "text")?.text || "";
                // Filter same internal instruction if it leaked into the final message
                if (/User repeated messages/i.test(fromEvent) || /system context shows we're on/i.test(fromEvent)) {
                    const parts = fromEvent.split(/\r?\n/);
                    fromEvent = parts.filter(p => !/User repeated messages/i.test(p) && !/system context/i.test(p) && !/Use the actual date/i.test(p) && !/Keep short and in Spanish/i.test(p)).join("\n");
                }
                // Prefer Pi's own copy; the accumulated deltas are the fallback
                // for a message that streamed but arrived here without content.
                let text = (fromEvent || this.currentOutput).trim();
                // Final safety: strip instruction if still present in currentOutput fallback
                if (/User repeated messages/i.test(text) || /system context shows we're on/i.test(text)) {
                    const parts = text.split(/\r?\n/);
                    text = parts.filter(p => !/User repeated messages/i.test(p) && !/system context/i.test(p) && !/Use the actual date/i.test(p) && !/Keep short and in Spanish/i.test(p)).join("\n").trim();
                }
                this.currentOutput = "";
                if (text) {
                    this.lastAnswer = text;
                    activeTask.output = text;
                    publishChatEvent(CHAT_EVENTS.MESSAGE_END, { text });
                }
            }

            if (event.type === "tool_execution_start") {
                const tName = event.toolName || "herramienta";
                const tId = event.toolCallId || event.id || String(Date.now());
                const tDetail = describeToolInput(event);
                console.log(`\n[IDUPI Tool] Ejecutando: ${tName}`);

                const isSubagent = isSubagentTool(tName, event.input);

                // Activity visibility (Change A): one stable id across the
                // lifecycle. Pi MCP is detected structurally via statusKey=mcp
                // (see extension_ui_request below); the start stays generic
                // until the end enriches `server` additively.
                this._currentActivityId = tId;
                this._activityMcp = false;
                const mcpStart = detectMcp("pi-cli", { toolName: tName });
                activityRegistry.start(tId, {
                    engine: "pi-cli",
                    project: activeProjectId,
                    sessionId: currentActivitySession("pi-cli"),
                    kind: mcpStart.isMcp ? "mcp" : "tool",
                    name: mcpStart.name,
                    detail: tDetail,
                    server: mcpStart.isMcp ? mcpStart.server : undefined,
                });

                if (isSubagent) {
                    // Claude and OpenCode already resolve the role above; Pi sent
                    // the raw tool name, so every fan-out produced a column of
                    // cards all called "subagent".
                    this._subagentName = describeSubagentName(tName, event.input || {});
                    publishChatEvent(CHAT_EVENTS.SUBAGENT_START, {
                        id: tId,
                        name: this._subagentName,
                        task: tDetail
                    });
                }

                publishChatEvent(CHAT_EVENTS.TOOL_START, {
                    id: tId,
                    name: tName,
                    detail: tDetail
                });
            }

            if (event.type === "tool_execution_end") {
                const tName = event.toolName || "herramienta";
                const tId = event.toolCallId || event.id || "";
                // Activity visibility (Change A): additive server enrichment
                // (never replaces the name/id), then single terminal.
                const mcpEnd = detectMcp("pi-cli", { toolName: tName, server: event.result?.details?.server });
                activityRegistry.update(tId, { server: mcpEnd.server });
                activityRegistry.terminalize(tId, { ok: event.isError !== true });
                this._currentActivityId = null;
                this._activityMcp = false;
                // Second, independent proof that delegated work finished.
                //
                // subagent_wait blocks until the runs it was given complete, so
                // its return IS that proof -- no parsing required. The card had
                // exactly one way to close, through a notice whose channel and
                // whose shape both turned out to be wrong, and each time it was
                // wrong the card span forever on work that was done. One signal
                // gating a terminal state is the actual defect.
                if (tName === "subagent_wait" && this.pendingSubagents.size) {
                    const waited = extractPiResultText(event.result) || "";
                    console.log(`[IDUPI Subagente] subagent_wait cerró ${this.pendingSubagents.size} tarjeta(s) pendiente(s).`);
                    for (const [cardId, cardName] of [...this.pendingSubagents]) {
                        this.pendingSubagents.delete(cardId);
                        publishChatEvent(CHAT_EVENTS.SUBAGENT_END, {
                            id: cardId,
                            name: cardName,
                            summary: summarizeSubagentResult(waited) || "El subagente terminó.",
                            ok: event.isError !== true,
                        });
                    }
                }

                const isSubagent = isSubagentTool(tName, event.input);

                if (isSubagent) {
                    const piSummary = extractPiResultText(event.result);
                    if (!piSummary && event.result && typeof event.result === "object") {
                        // Unknown shape: say so in the log with the real keys, so the
                        // next live run identifies the field instead of us guessing.
                        console.warn(
                            `[IDUPI Subagente] Forma de 'result' no reconocida para '${tName}'. Claves: ${Object.keys(event.result).join(", ")}`,
                        );
                    }
                    const subagentName = this._subagentName || describeSubagentName(tName, event.input || {});
                    if (isAsyncDispatchReceipt(piSummary)) {
                        // An async run answers the tool call instantly with a
                        // dispatch receipt. Closing the card here reported a
                        // completion that had not happened and printed text
                        // addressed to the model under "Respuesta". The card
                        // stays open until the real answer arrives as a
                        // `subagent-notify` entry (see entry_appended below).
                        this.rememberPendingSubagent(tId, subagentName);
                    } else {
                        publishChatEvent(CHAT_EVENTS.SUBAGENT_END, {
                            id: tId,
                            name: subagentName,
                            summary: summarizeSubagentResult(piSummary),
                            ok: event.isError !== true
                        });
                    }
                }

                publishChatEvent(CHAT_EVENTS.TOOL_END, {
                    id: tId,
                    name: tName,
                    ok: event.isError !== true
                });
            }

            // Where an async subagent's real answer arrives.
            //
            // It is a CUSTOM message, not a session entry event. Pi injects it
            // with sendCustomMessage(), which appends the entry and then emits
            // message_start/message_end with role "custom"
            // (dist/core/agent-session.js:1096). `entry_appended` is emitted
            // only by appendEntry(), a different API writing a `custom` entry
            // -- so a handler keyed on it can never fire, and the delegation
            // card stayed open forever waiting for an event that was never
            // coming. Read from the type union, never observed: that is how
            // the wrong channel got picked.
            //
            // The message is `display: false` -- Pi's own UI hides it, because
            // its text is addressed to the model -- but it carries the child's
            // output, the one thing the card was missing.
            if (event.type === "message_end" && event.message?.role === "custom"
                && event.message.customType === "subagent-notify") {
                const raw = extractCustomMessageText(event.message.content);
                const notice = parseSubagentNotify(raw);
                if (notice) {
                    // Closing used to require a readable answer, so a notice
                    // whose shape we could not read left the card spinning on
                    // work that had finished. Finishing and being readable are
                    // different facts: the notice proves the first one.
                    if (!notice.output) {
                        console.warn("[IDUPI Subagente] Aviso de fin sin respuesta legible:", raw.slice(0, 200));
                    }
                    this.resolvePendingSubagent(notice);
                } else {
                    console.warn("[IDUPI Subagente] Aviso de fin no reconocido:", raw.slice(0, 200));
                }
            }

            // Pi MCP status signal (Change A): a generic MCP start. The active
            // operation is marked MCP; `result.details.server` at the end adds
            // the server name without replacing the name/id.
            if (event.type === "extension_ui_request" && event.method === "setStatus" && event.statusKey === "mcp") {
                if (this._currentActivityId) {
                    this._activityMcp = true;
                    activityRegistry.update(this._currentActivityId, { kind: "mcp" });
                }
            }

            // Phase 3 (fix-ui-request-selection, engine adapters): Pi's
            // `extension_ui_request` carries every interactive prompt as
            // { method: "select" | "confirm" | "input", ... }. The handler
            // above only routes `setStatus`; the other methods must reach
            // the PendingUiRequestRegistry so the chat app gets a
            // `ui_request` SSE frame with a token it can echo back on POST
            // /api/v1/chat/ui-response. Pi's persistent RPC child already
            // uses `["pipe","pipe","pipe"]` (the default), so we just retain
            // `this.child.stdin` as the answer sink.
            if (event.type === "extension_ui_request"
                && event.method !== "setStatus"
                && typeof this.child?.stdin?.write === "function") {
                const normalized = normalizePiUiRequest(event);
                if (normalized) {
                    const piSession = currentActivitySession("pi");
                    const reg = uiRequestRegistry.register({
                        sessionId: piSession,
                        engine: "pi",
                        method: normalized.method,
                        options: normalized.options,
                        title: normalized.title,
                        message: normalized.message,
                    });
                    // Pi correlates a UI answer by the `id` it put on the
                    // `extension_ui_request` frame (rpc-mode handleInputLine:
                    // `pendingExtensionRequests.get(response.id)`). Without an
                    // id no response frame can ever resolve the dialog, so we
                    // do not install a writer — the registry entry still gets
                    // its SSE frame and terminal expiry, but there is nothing
                    // to write to stdin.
                    const piRequestId = typeof event.id === "string" ? event.id : null;
                    if (piRequestId === null) {
                        console.warn(`[ui-request] Pi extension_ui_request sin id; no se puede responder por stdin (requestId=${reg.requestId})`);
                    } else {
                        const writer = (value) => {
                            // Pi consumes JSON-RPC frames on stdin; a UI answer
                            // is a correlated `extension_ui_response` frame
                            // addressed to the PI request id, NOT the registry
                            // requestId. Per rpc-mode's parseResponse:
                            //   select/input → { type, id, value }   (option text / free text)
                            //   confirm      → { type, id, confirmed } (boolean)
                            //   expiry cancel→ { type, id, cancelled: true } (confirm/input blanket cancel)
                            // `__child` lets the close-handler drop this writer
                            // when the persistent RPC child is replaced.
                            const ch = this.child;
                            if (!ch || ch.killed || !ch.stdin || ch.stdin.destroyed) return false;
                            let payload;
                            if (value && typeof value === "object" && value.cancelled === true) {
                                payload = { type: "extension_ui_response", id: piRequestId, cancelled: true };
                            } else if (normalized.method === "confirm") {
                                payload = { type: "extension_ui_response", id: piRequestId, confirmed: value === true };
                            } else {
                                payload = { type: "extension_ui_response", id: piRequestId, value };
                            }
                            try {
                                ch.stdin.write(JSON.stringify(payload) + "\n");
                                return true;
                            } catch (err) {
                                console.warn(`[ui-request] Pi stdin write failed for ${reg.requestId}: ${err?.message || err}`);
                                return false;
                            }
                        };
                        writer.__child = this.child;
                        setUiRequestStdinWriter(reg.requestId, writer);
                    }
                }
            }

            // Pi emits agent_end BEFORE an automatic retry as well, flagged
            // with willRetry (dist/core/agent-session.d.ts). Treating that as
            // the end of the turn closed the request on an attempt that had
            // produced nothing: the app got the "Respuesta procesada
            // correctamente" filler while the real answer streamed a moment
            // later into a turn nobody was waiting on any more.
            if (event.type === "agent_end" && event.willRetry === true) {
                console.log("[IDUPI Pi RPC] Intento fallido; Pi va a reintentar. La respuesta sigue pendiente.");
            }

            if (event.type === "auto_retry_start") {
                console.warn(
                    `[IDUPI Pi RPC] Reintento ${event.attempt}/${event.maxAttempts} en ${event.delayMs}ms: ${event.errorMessage}`,
                );
                // The retry regenerates from scratch, so the half message the
                // failed attempt left behind is not a prefix of the real one.
                // Only that unfinished message is dropped: messages Pi already
                // closed in this turn were published and cleared as they ended,
                // so a retry can no longer swallow the preamble the way it did
                // when the whole turn shared one buffer.
                this.currentOutput = "";
                publishChatEvent(CHAT_EVENTS.THINKING, { active: true });
            }

            // Retries exhausted. Without this the request sat until the 5
            // minute backstop even though Pi already knew it had given up.
            if (event.type === "auto_retry_end" && event.success === false && this.pendingReject) {
                const reject = this.pendingReject;
                clearTimeout(this.pendingTimeoutTimer);
                this.pendingTimeoutTimer = null;
                this.pendingResolve = null;
                this.pendingReject = null;
                this.thinkingAnnounced = false;
                publishChatEvent(CHAT_EVENTS.THINKING, { active: false });
                reject(new Error(event.finalError || `Pi agotó los reintentos en el intento ${event.attempt}`));
                this._maybeApplyPendingModelChange();
            }

            if (event.type === "agent_end" && event.willRetry !== true && this.pendingResolve) {
                // Terminalize any still-open Pi activity (Change A).
                if (this._currentActivityId) {
                    activityRegistry.terminalize(this._currentActivityId, { ok: true });
                    this._currentActivityId = null;
                    this._activityMcp = false;
                }
                console.log("\n[IDUPI Pi RPC] Respuesta completada.");
                clearTimeout(this.pendingTimeoutTimer);
                this.pendingTimeoutTimer = null;
                // Text Pi streamed but never closed with a message_end of its
                // own. Publishing it here is the only way it reaches the chat;
                // everything Pi did close was already sent as its own message.
                // FIX: always publish via SSE, even when Pi completed via retries with empty buffers,
                // so the app's chat bubble actually shows the result (100% of Pi's output).
                const leftover = this.currentOutput.trim();
                let publishedLeftover = false;
                if (leftover) {
                    this.lastAnswer = leftover;
                    publishChatEvent(CHAT_EVENTS.MESSAGE_END, { text: leftover });
                    publishedLeftover = true;
                }
                this.currentOutput = "";
                const resultText = this.lastAnswer || "Respuesta procesada correctamente por Pi CLI.";
                // If Pi completed after retries with no leftover/lastAnswer, still push the fallback via SSE
                // so the POST's output and the SSE stream stay in sync (fixes "completa como éxito pero no llega").
                if (!publishedLeftover && resultText) {
                    publishChatEvent(CHAT_EVENTS.MESSAGE_END, { text: resultText });
                }

                activeTask.status = "completed";
                activeTask.output = resultText;

                this.thinkingAnnounced = false;
                publishChatEvent(CHAT_EVENTS.THINKING, { active: false });

                const resolve = this.pendingResolve;
                this.pendingResolve = null;
                this.pendingReject = null;
                resolve(resultText);
                // Turn boundary: apply a queued model switch now that Pi is
                // idle (safe — the answer is delivered, the child restarts on
                // the next ensureStarted with the new --provider/--model).
                this._maybeApplyPendingModelChange();
            }

            noteUnmappedRpcEvent(event.type, event);
        } catch (e) {}
    }

    async sendPrompt(message) {
        this.ensureStarted();

        if (this.pendingResolve) {
            throw new Error("Ya hay una consulta procesándose en Pi CLI.");
        }

        // Announced up front and cleared by the first text delta, so the app can
        // show deliberation instead of an unexplained pause.
        this.thinkingAnnounced = true;
        publishChatEvent(CHAT_EVENTS.THINKING, { active: true });

        this.currentOutput = "";
        this.lastAnswer = "";

        return new Promise((resolve, reject) => {
            this.pendingResolve = resolve;
            this.pendingReject = reject;

            this.armIdleWatchdog();

            const promptCmd = JSON.stringify({
                id: `idupi-${Date.now()}`,
                type: "prompt",
                message: message,
                streamingBehavior: "followUp"
            }) + "\n";

            console.log(`\n[IDUPI App -> Pi RPC] Mensaje enviado: "${message}"`);

            // Both failure paths must clear the same state. A write to a
            // destroyed/ended stdin throws SYNCHRONOUSLY (ERR_STREAM_DESTROYED)
            // and never invokes the callback: without this catch the pending
            // timer would survive and later taskkill a PID this request no
            // longer owns, and pendingResolve would stay set, making every
            // later sendPrompt reject with "Ya hay una consulta procesándose"
            // until the server restarts.
            const failPending = (err) => {
                clearTimeout(this.pendingTimeoutTimer);
                this.pendingTimeoutTimer = null;
                this.pendingResolve = null;
                this.pendingReject = null;
                this.thinkingAnnounced = false;
                publishChatEvent(CHAT_EVENTS.THINKING, { active: false });
                reject(err);
            };

            try {
                this.child.stdin.write(promptCmd, (err) => {
                    if (err) failPending(err);
                });
            } catch (err) {
                failPending(err);
            }
        });
    }
}

/**
 * Text of a Claude `tool_result`, whose `content` arrives in two real shapes:
 * a plain string, or an array of blocks (only `text` blocks carry prose --
 * `tool_reference` and friends do not). Both were observed in real captures,
 * so handle each rather than assuming one.
 */
function extractToolResultText(content) {
    if (typeof content === "string") return content;
    if (Array.isArray(content)) {
        return content
            .map((block) => {
                if (typeof block === "string") return block;
                return block?.type === "text" && typeof block.text === "string" ? block.text : "";
            })
            .filter(Boolean)
            .join("\n");
    }
    return "";
}

/** Short human-readable summary of a tool call, for the chat timeline. */
/** Bounds any detail line to the same width the chat has always used. */
function boundDetail(text) {
    return text.length > 120 ? text.slice(0, 117) + "..." : text;
}

function describeToolInput(event) {
    const input = event.input || event.args || event.parameters;
    if (!input || typeof input !== "object") return null;
    // Prefer the fields that identify *what* the tool acted on. `task` is
    // deliberately last: it is the broadest field, and a tool that names a
    // concrete target should still show the target.
    for (const key of ["path", "file_path", "command", "pattern", "query", "url"]) {
        if (typeof input[key] === "string" && input[key]) {
            return boundDetail(input[key]);
        }
    }

    // pi-subagents' `subagent` tool carries neither: its parameters are `agent`
    // (the role) and `task` (the prompt the model wrote for the child). Without
    // this the delegation card showed generic filler and the real instruction --
    // the single most useful thing to read -- never reached the chat.
    const children = Array.isArray(input.children) ? input.children
        : Array.isArray(input.steps) ? input.steps
        : null;
    if (children && children.length) {
        const lines = children
            .map((c) => {
                if (!c || typeof c !== "object") return "";
                const task = typeof c.task === "string" ? c.task : "";
                const agent = typeof c.agent === "string" ? c.agent : "";
                if (agent && task) return `${agent}: ${task}`;
                return agent || task;
            })
            .filter(Boolean);
        if (lines.length) return boundDetail(lines.join(" | "));
    }
    if (typeof input.task === "string" && input.task) return boundDetail(input.task);

    return null;
}

/**
 * Is this tool call a delegation to a subagent?
 *
 * One rule for all three engines. There used to be four inline copies with
 * three different rule sets, and they disagreed in a way the user could see:
 * OpenCode's copy never listed `task`, which is the exact name OpenCode gives
 * its delegation tool, so an OpenCode subagent produced no card at all -- only
 * the orchestrator's final answer. Claude's copy did list it.
 *
 * `includes("agent")` from the old Claude copy is deliberately not kept: it
 * matched any MCP tool with "agent" in its name and turned ordinary calls into
 * delegation cards. Every real delegation tool is covered by name or prefix.
 */
const SUBAGENT_TOOL_NAMES = new Set([
    "task",              // Claude Code and OpenCode
    "agent",             // Claude Code, older builds
    "subagent",          // pi-subagents
    "invoke_subagent",
    "delegate",
    "gentle-orchestrator",
    "build",
    "explore",
    "plan",
]);
const SUBAGENT_TOOL_PREFIXES = ["sdd-", "review-", "jd-"];

/**
 * Text of a Pi custom message. `content` is `string | (TextContent |
 * ImageContent)[]` (CustomMessageEntry, dist/core/session-manager.d.ts), so
 * assuming the string form would silently read nothing from the array one.
 */
function extractCustomMessageText(content) {
    if (typeof content === "string") return content;
    if (!Array.isArray(content)) return "";
    return content
        .filter((part) => part && part.type === "text" && typeof part.text === "string")
        .map((part) => part.text)
        .join("\n");
}

function isSubagentTool(toolName, input) {
    const name = typeof toolName === "string" ? toolName.toLowerCase() : "";
    if (!name) return false;

    // pi-subagents reuses ONE tool for launching, polling, stopping and
    // scheduling. Its schema settles which is which: `action` is
    //   "Optional management/control action. Omit this field for structured
    //    single-child or workflowScript execution."
    // so a call that carries one is a question about existing work, never new
    // work. Treating every call as a delegation opened a card per poll: two
    // subagents produced four cards, three of them from one launch plus two
    // status queries and a wait.
    if (input && typeof input === "object" && typeof input.action === "string" && input.action) {
        return false;
    }
    // subagent_wait blocks on runs that already exist; it starts nothing.
    if (name !== "subagent" && name.startsWith("subagent_")) return false;

    // A role/subagent_type parameter names the child being launched, so it
    // identifies a delegation whatever the tool ends up being called.
    if (input && typeof input === "object") {
        if (typeof input.role === "string" && input.role) return true;
        if (typeof input.subagent_type === "string" && input.subagent_type) return true;
    }
    if (SUBAGENT_TOOL_NAMES.has(name)) return true;
    if (SUBAGENT_TOOL_PREFIXES.some((prefix) => name.startsWith(prefix))) return true;
    return name.includes("subagent") || name.includes("supervisor");
}

/**
 * What to call a delegation card. Every fan-out through pi-subagents arrives as
 * the same tool name, `subagent`, so labelling cards with it produces a column
 * of identical rows. The role the model chose (`scout`, `researcher`, ...) is
 * the part a reader can actually use.
 */
/**
 * True when a `subagent` result is the dispatch receipt an async run returns
 * immediately, rather than anything the subagent produced.
 *
 * Both markers are required. A single phrase would misfire on ordinary prose
 * that happens to mention background work -- and mislabelling a real answer as
 * "no answer yet" is the worse failure of the two.
 */
function isAsyncDispatchReceipt(text) {
    if (typeof text !== "string" || !text) return false;
    return /async run is detached/i.test(text) && /running in the background/i.test(text);
}

/**
 * What the delegation card shows. A receipt is reported as what it is -- work
 * handed to the background -- instead of being printed under "Respuesta" as if
 * the subagent had replied with instructions addressed to the model.
 *
 * For an async run the card is now left open instead, and closed by the
 * `subagent-notify` entry that carries the real answer.
 */
function summarizeSubagentResult(text) {
    if (isAsyncDispatchReceipt(text)) {
        return "Delegado en segundo plano: el subagente sigue trabajando, todavía sin respuesta.";
    }
    const trimmed = typeof text === "string" ? text.trim() : "";
    if (!trimmed) return "Subagente completó la tarea";
    return trimmed.slice(0, 300);
}

function describeSubagentName(toolName, input) {
    if (!input || typeof input !== "object") return toolName;
    // Claude and OpenCode put the chosen role in `subagent_type` / `role` while
    // still calling the tool `task`, so reading it here is what keeps those
    // cards from all being labelled "task".
    if (typeof input.subagent_type === "string" && input.subagent_type) return input.subagent_type;
    if (typeof input.role === "string" && input.role) return input.role;
    const children = Array.isArray(input.children) ? input.children
        : Array.isArray(input.steps) ? input.steps
        : null;
    if (children && children.length) {
        const roles = [...new Set(
            children.map((c) => (c && typeof c.agent === "string" ? c.agent : "")).filter(Boolean),
        )];
        if (roles.length) return roles.join(", ");
    }
    if (typeof input.agent === "string" && input.agent) return input.agent;

    // A fan-out arrives as a workflowScript: the roles are written inside the
    // script the model generated, and nowhere else at launch time, so without
    // this the card for a two-subagent run is labelled "subagent". Only a
    // label -- if the shape ever changes the card falls back to the tool name.
    if (typeof input.workflowScript === "string" && input.workflowScript) {
        const roles = [...new Set(
            [...input.workflowScript.matchAll(/agent:\s*['"]([^'"]+)['"]/g)].map((m) => m[1]),
        )];
        if (roles.length) return roles.join(", ");
    }
    return toolName;
}

// Pi's RPC vocabulary is not documented here, and guessing the delegation event
// name would produce a feature that silently never fires. Instead we log each
// unmapped type once, so a real session tells us what to map.
const seenUnmappedEvents = new Set();
const MAPPED_RPC_EVENTS = new Set([
    "model_change", "active_model", "message_update", "message_end",
    "tool_execution_start", "tool_execution_end", "agent_end",
    "auto_retry_start", "auto_retry_end",
    // `entry_appended` is deliberately NOT listed. It was marked as mapped on
    // the strength of a type union alone, before any run had shown it, and
    // that silenced the one log line that would have revealed the subagent
    // notice never arrives through it. Nothing here gets marked mapped until a
    // live run proves it exists.
    // Identified from a live run and deliberately not acted on: ordinary turn
    // bookkeeping and RPC command replies. Listing them keeps the log for
    // events that are genuinely still unknown, instead of flagging routine
    // traffic as a delegation candidate on every single turn.
    "agent_start", "agent_settled", "turn_start", "turn_end", "message_start",
    "response", "extension_ui_request", "tool_execution_update", "queue_update",
]);

function noteUnmappedRpcEvent(type, event) {
    if (!type || MAPPED_RPC_EVENTS.has(type) || seenUnmappedEvents.has(type)) return;
    seenUnmappedEvents.add(type);
    // Log the field names once, so a live run identifies an unmapped payload
    // instead of us guessing at it. This is how `entry_appended` -- the event
    // that actually carries an async subagent's answer -- was found.
    let shape = "";
    try {
        if (event && typeof event === "object") shape = ` — campos: ${Object.keys(event).join(", ")}`;
    } catch { /* logging must never break the stream */ }
    console.log(`[chat-stream] Evento RPC sin mapear: '${type}' — candidato para delegación/subagente${shape}`);
}

const piRpc = new PiRpcManager();

// ---------------------------------------------------------------------------
// Activity visibility wiring (Change A: live-cli-activity-visibility).
// A single registry instance, published through the existing SSE hub so
// chat-events.mjs keeps owning transport, per-subscriber queues, and context
// filtering. The 15s operation heartbeat lives here and is stopped solely by
// terminalize(id); a subscriber disconnect never touches it.
// ---------------------------------------------------------------------------
const activityRegistry = new ActivityRegistry({
    publish: (type, data) => publishChatEvent(type, data),
});

// Stable identity for the active operation, per engine. Falls back to a
// deterministic synthetic id when no session is bound yet, so the SSE
// subscriber (bound to the same expression) always matches.
function currentActivitySession(engine) {
    if (engine === "claude") return activeClaudeSessionId || ("claude:" + activeProjectId);
    if (engine === "opencode") return activeOpenCodeSessionId || ("opencode:" + activeProjectId);
    return piRpc.currentSessionPath || ("pi:" + activeProjectId);
}

// Structural, zero-allowlist MCP classification per active engine label.
// (classifyMcp uses canonical labels claude/opencode/pi; pi-cli maps to pi.)
function detectMcp(engineLabel, ev) {
    const norm = engineLabel === "pi-cli" ? "pi" : engineLabel;
    return classifyMcp({
        engine: norm,
        toolName: ev.toolName,
        name: ev.name,
        statusKey: ev.statusKey,
        server: ev.server,
    });
}

const handleRequest = async (req, res) => {
    // Every endpoint below can read files or spawn shells, so nothing is
    // reachable before the bearer token is verified.
    if (!requireAuth(req, res)) return;

    const rawUrl = req.url || "";
    const parsedUrl = new URL(rawUrl, `http://localhost:${PORT}`);
    const pathname = parsedUrl.pathname;

    // Remote screen routes live in lib/screen-routes.mjs; it answers true when
    // the path is theirs. Still behind requireAuth like everything below.
    if (await handleScreenRoute(req, res, pathname)) return;

    // 0. Stream de eventos del chat (SSE). Long-lived: no responde y sigue abierto.
    if (pathname === "/api/v1/chat/stream" && req.method === "GET") {
        // Context is server-derived (never client query) so activity frames are
        // filtered to the subscriber's engine/project/session — Change A (D4).
        subscribeChatStream(req, res, {
            engine: currentStatus.activeEngine,
            project: activeProjectId,
            sessionId: currentActivitySession(currentStatus.activeEngine),
        });
        return;
    }

    // 1. Estado del Servidor
    if (pathname === "/api/v1/status" && req.method === "GET") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(currentStatus));
        return;
    }

    // 1c. Seleccionar Motor de IA Activo (pi-cli, claude, opencode)
    if (pathname === "/api/v1/engine/select" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            try {
                const parsed = JSON.parse(body || "{}");
                const engineId = parsed.engineId;
                if (engineId) {
                    currentStatus.activeEngine = engineId;
                    if (engineId === "claude") {
                        currentStatus.agent = "Claude CLI Engine";
                        currentStatus.operatingProvider = "anthropic";
                        currentStatus.operatingAi = "claude-sonnet-5";
                    } else if (engineId === "opencode") {
                        currentStatus.agent = "OpenCode Engine";
                        currentStatus.operatingProvider = "opencode-go";
                        currentStatus.operatingAi = "gpt-5.6-luna";
                    } else {
                        currentStatus.activeEngine = "pi-cli";
                        currentStatus.agent = "Pi CLI RPC (Servidor IDUPI)";
                        currentStatus.operatingProvider = "openai-codex";
                        currentStatus.operatingAi = "gpt-5.6-luna";
                    }
                    activeClaudeSessionId = null;
                    activeOpenCodeSessionId = null;
                    activeClaudeModelId = null;
                    console.log(`[Engine Switch] Motor activo cambiado a: '${currentStatus.activeEngine}'. Sesiones reseteadas.`);
                    res.writeHead(200, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ status: "ok", activeEngine: currentStatus.activeEngine }));
                    return;
                }
                res.writeHead(400, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: "engineId es requerido" }));
            } catch (err) {
                console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // 1b. Tarea activa en segundo plano
    if (pathname === "/api/v1/chat/active-task" && req.method === "GET") {
        // With an id the client gets ITS task and nothing else -- previously a
        // poll returned whatever task happened to be current, so a second
        // message handed its answer to the first message's poll. Without an id
        // the old single-task behavior is preserved for older builds.
        const clientTaskId = parsedUrl.searchParams.get("clientTaskId");
        const payload = clientTaskId ? taskRegistry.get(clientTaskId) : activeTask;
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(payload));
        return;
    }

    // 1d. Respuesta autenticada a un UI Request pendiente (fix-ui-request-selection,
    //     Phase 2 transport). El cuerpo lleva el valor exacto que el usuario eligió
    //     junto con el token y sessionId emitidos en el SSE `ui_request`. El
    //     registry valida TODO (token === entry.token === currentTokenFor(sessionId),
    //     sessionId === entry.sessionId, exact-value por método) ANTES de tocar el
    //     estado: un 400/404/409 nunca llega a _terminate, así que el timer de
    //     120s sigue siendo capaz de auto-aprobar después. La escritura a
    //     `child.stdin` se agrega en Phase 3 (spawn pipe para Claude/OpenCode);
    //     por ahora este handler resuelve la entrada y emite `ui_request_resolved`
    //     para que la app cierre el diálogo.
    if (pathname.startsWith("/api/v1/chat/ui-response/") && req.method === "POST") {
        const requestId = decodeURIComponent(
            pathname.slice("/api/v1/chat/ui-response/".length),
        );
        if (!requestId) {
            res.writeHead(400, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: "requestId requerido en la URL" }));
            return;
        }
        let raw = "";
        req.on("data", chunk => { raw += chunk; });
        req.on("end", () => {
            let body;
            try {
                body = raw ? JSON.parse(raw) : {};
            } catch (err) {
                res.writeHead(400, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: `body JSON inválido: ${err.message}` }));
                return;
            }
            const { value, token, sessionId } = body || {};
            // Validación de presencia ANTES del registry: una `token` faltante no
            // es un "token mismatch" (409), es un body mal formado (400). Misma
            // idea para sessionId y value.
            if (typeof sessionId !== "string" || sessionId.length === 0) {
                res.writeHead(400, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: "sessionId es requerido" }));
                return;
            }
            if (typeof token === "undefined" || token === null) {
                res.writeHead(400, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: "token es requerido" }));
                return;
            }
            if (typeof value === "undefined") {
                res.writeHead(400, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: "value es requerido" }));
                return;
            }
            const result = uiRequestRegistry.resolve({ requestId, token, sessionId, value });
            if (!result.ok) {
                // 400 = exact-value rechazado; 404 = requestId desconocido;
                // 409 = token stale o sessionId no coincide. El registry ya
                // blanqueó el código, solo lo servimos al cliente.
                res.writeHead(result.status, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: result.reason }));
                return;
            }
            // Phase 3 (engine adapters): deliver the validated answer to the
            // engine's stdin as ONE JSON line. The writer was installed by
            // the adapter that produced this requestId (Pi captures the
            // persistent RPC child; Claude/OpenCode retain the per-message
            // spawn). A failure here is logged but does NOT undo the
            // resolution — the entry is already terminal, the worst case is
            // the engine's 300s taskkill. The writer is cleared either way
            // so a re-POST on the same requestId short-circuits to 404.
            const stdinOk = writeUiResponseToStdin(requestId, result.value);
            clearUiRequestStdinWriter(requestId);
            // Resuelto por el cliente. Espejamos la transición terminal en el bus
            // SSE para que la app descarte el diálogo (mismo canal que usa el
            // expire listener, con `resolution: "client"` para distinguir).
            publishChatEvent(CHAT_EVENTS.UI_REQUEST_RESOLVED, {
                requestId,
                sessionId,
                engine: result.entry.engine,
                resolution: "client",
                value: result.value,
            });
            console.log(
                `[ui-request] CLIENT-RESOLVED method=${result.entry.method} ` +
                `sessionId=${sessionId} requestId=${requestId} ` +
                `value=${JSON.stringify(result.value)}`,
            );
            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify({
                status: "resolved",
                requestId,
                value: result.value,
            }));
        });
        return;
    }

    // 2. Lista de Proyectos
    if (pathname === "/api/v1/projects" && req.method === "GET") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(registeredProjects));
        return;
    }

    // 3. Catálogo Real de Modelos de IA Instalados/Registrados en Pi CLI
    if (pathname === "/api/v1/models" && req.method === "GET") {
        const availableModels = getAvailableModels();
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(availableModels));
        return;
    }

    // 3b. Catálogo de Comandos Slash (/comando) según el Motor Activo (Pi CLI, Claude CLI, OpenCode)
    if (pathname === "/api/v1/commands" && req.method === "GET") {
        const availableCommands = getAvailableCommands();
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(availableCommands));
        return;
    }

    // 4. Lista de Terminales y Procesos Reales de la PC (Bun, Claude, Codex, Kimi, Node, Python, PowerShell, CMD)
    if (pathname === "/api/v1/terminals" && req.method === "GET") {
        const terminalsList = terminalMgr.listTerminals();
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(terminalsList));
        return;
    }

    // 4b. Logs de una Terminal Específica
    if (pathname.startsWith("/api/v1/terminals/") && pathname.endsWith("/logs") && req.method === "GET") {
        const parts = pathname.split("/");
        const termId = parts[4];
        const logs = terminalMgr.getTerminalLogs(termId);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(logs));
        return;
    }

    // 4c. Crear/Abrir Nueva Terminal (PowerShell / Shell Session)
    if (pathname === "/api/v1/terminals/spawn" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            try {
                const parsed = JSON.parse(body || "{}");
                const newTerm = terminalMgr.spawnNewTerminal(parsed.name);
                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ status: "ok", terminal: newTerm }));
            } catch (err) {
                console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // 4d. Ejecutar Comando en Terminal Seleccionada o Resetear Servidor
    if (pathname === "/api/v1/terminals/exec" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", async () => {
            try {
                const parsed = JSON.parse(body || "{}");
                const { termId, command } = parsed;

                if (!command) {
                    res.writeHead(400, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ error: "Comando es requerido" }));
                    return;
                }

                console.log(`[Terminal Exec] Ejecutando "${command}" en terminal '${termId || 'pi-cli'}'...`);
                const result = await terminalMgr.execCommand(termId, command);

                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ status: "ok", output: result }));
            } catch (err) {
                console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // 4e. Reiniciar/Resetear Servidor o Proceso Real en tu PC (taskkill / restart)
    if (pathname === "/api/v1/terminals/restart" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            try {
                const parsed = JSON.parse(body || "{}");
                const termId = parsed.termId || "pi-cli";
                console.log(`[Terminal Reset] Reiniciando/deteniendo servidor o proceso '${termId}'...`);
                const msg = terminalMgr.restartServer(termId);
                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ status: "ok", message: msg }));
            } catch (err) {
                console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // 4f. Cerrar Terminal / Finalizar Proceso Activo
    if (pathname.startsWith("/api/v1/terminals/") && req.method === "DELETE") {
        const parts = pathname.split("/");
        const termId = parts[4];
        terminalMgr.closeTerminal(termId);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ status: "ok" }));
        return;
    }

    // 5. Sesiones Reales (Multi-Motor: Pi CLI, OpenCode, Claude) del Proyecto Activo
    // Per-engine bounded pagination + engine=all k-way merge (session-listing-accuracy-perf, PR3).
    // `offset` is accepted only for literal compatibility with the spec's example
    // scenario (offset=0 == first page); it is never itself a pagination driver --
    // only `cursor` advances a listing (design's "offset=0 compatibility note").
    if (pathname === "/api/v1/sessions" && req.method === "GET") {
        const projectIdParam = parsedUrl.searchParams.get("projectId") || parsedUrl.searchParams.get("project");
        const activeProj = resolveProject(projectIdParam || activeProjectId);
        const engineParam = parsedUrl.searchParams.get("engine") || "all";
        const cursorParam = parsedUrl.searchParams.get("cursor") || null;
        // Off by default: the list opens with the sessions the user actually
        // started. The switch in the app turns it on to see everything.
        const includeAll = parsedUrl.searchParams.get("includeAll") === "true";

        let limitParam;
        try {
            limitParam = validateNumeric(parseInt(parsedUrl.searchParams.get("limit") || "30", 10), { integer: true, min: 1, max: 200 });
        } catch {
            limitParam = 30;
        }

        try {
            const page = await fetchSessionsPage({
                engine: engineParam,
                cursorParam,
                limit: limitParam,
                projPath: activeProj.path,
                projName: activeProj.name,
                includeAll
            });
            console.log(`[Sessions DB] Cargadas ${page.sessions.length} sesiones para '${activeProj.name}' (Filtro: ${engineParam}).`);
            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify(page));
        } catch (err) {
            console.error("[Sessions Route Error]", engineParam, err.message);
            res.writeHead(err.httpStatus || 502, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ engine: err.engine || engineParam, error: err.message }));
        }
        return;
    }

    // 5b. Conteos Reales por Motor (independientes de cualquier listado truncado)
    if (pathname === "/api/v1/sessions/counts" && req.method === "GET") {
        const projectIdParam = parsedUrl.searchParams.get("projectId") || parsedUrl.searchParams.get("project");
        const activeProj = resolveProject(projectIdParam || activeProjectId);

        try {
            const result = await fetchSessionCounts(
                activeProj.path,
                parsedUrl.searchParams.get("includeAll") === "true"
            );
            if (result.allFailed) {
                res.writeHead(502, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: "Failed to count sessions for all engines", failures: result.failures }));
                return;
            }
            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ counts: result.counts, partial: result.partial, failures: result.failures }));
        } catch (err) {
            console.error("[Sessions Counts Route Error]", err.message);
            res.writeHead(502, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: err.message, failures: [] }));
        }
        return;
    }

    // 6. Obtener Historial Completo de Mensajes de una Sesión en <2ms
    if (pathname.startsWith("/api/v1/sessions/") && pathname.endsWith("/history") && req.method === "GET") {
        const parts = pathname.split("/");
        const sessionId = parts[4];
        
        console.log(`[Sessions History API] Obteniendo historial para sesión ${sessionId}...`);
        let sessionData;
        try {
            sessionData = getSessionHistoryById(sessionId);
        } catch (err) {
            console.error(`[IDUPI 502] ${req.method} ${pathname}`, err);
            res.writeHead(502, { "Content-Type": "application/json" });
            res.end(JSON.stringify({
                error: `No se pudo leer el historial de la sesión: ${err.message}`,
            }));
            return;
        }

        if (sessionData) {
            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ sessionId, history: sessionData.messages, model: sessionData.model || currentStatus.operatingAi }));
        } else {
            res.writeHead(404, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: "Sesión no encontrada" }));
        }
        return;
    }

    // 7. Reanudar Sesión Específica (Multi-Motor)
    if (pathname === "/api/v1/sessions/resume" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            try {
                const parsed = JSON.parse(body);
                const sessionId = parsed.sessionId;
                if (sessionId) {
                    // Clasificación por forma de ID + tienda de sesiones, NUNCA solo por
                    // el motor activo: un id de Pi/Claude (UUID) reanudado con el motor
                    // OpenCode activo caía en la rama OpenCode y la sesión real jamás se
                    // reanudaba. Los ids de OpenCode son siempre "ses_" (verificado con
                    // `opencode db`); el resto se resuelve en la tienda de Pi, y solo se
                    // cae al motor activo cuando el id no puede ser un id de tienda.
                    const sessionPath = findSessionFilePath(sessionId);
                    const looksLikeStoreUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(sessionId);
                    if (sessionId.startsWith("ses_") || (!sessionPath && !looksLikeStoreUuid && currentStatus.activeEngine === "opencode")) {
                        activeOpenCodeSessionId = sessionId;
                        currentStatus.activeEngine = "opencode";
                        console.log(`[OpenCode Session Resume] Sesión reanudada: ${sessionId}`);
                        res.writeHead(200, { "Content-Type": "application/json" });
                        res.end(JSON.stringify({ status: "ok", sessionId, engine: "opencode" }));
                        return;
                    }
                    if (sessionPath && sessionPath.includes(".claude")) {
                        activeClaudeSessionId = sessionId;
                        currentStatus.activeEngine = "claude";
                        console.log(`[Claude Session Resume] Sesión reanudada: ${sessionId}`);
                        res.writeHead(200, { "Content-Type": "application/json" });
                        res.end(JSON.stringify({ status: "ok", sessionId, engine: "claude" }));
                        return;
                    }
                    if (sessionPath) {
                        if (!piRpc.resumeSession(sessionPath)) {
                            res.writeHead(409, { "Content-Type": "application/json" });
                            res.end(JSON.stringify({
                                error: "Pi está respondiendo ahora mismo. Esperá a que termine para cambiar de sesión.",
                            }));
                            return;
                        }
                        currentStatus.activeEngine = "pi-cli";
                        console.log(`[Pi Session Resume] Sesión reanudada: ${sessionPath}`);
                        res.writeHead(200, { "Content-Type": "application/json" });
                        res.end(JSON.stringify({ status: "ok", sessionId, engine: "pi-cli", filePath: sessionPath }));
                        return;
                    }
                }
                res.writeHead(400, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: "Sesión no encontrada para reanudar" }));
            } catch (err) {
                console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // 7b. Crear Nueva Sesión (Limpia sesión activa para iniciar conversación limpia)
    if (pathname === "/api/v1/sessions/new" && req.method === "POST") {
        // Pi is the one engine whose session is a live child, so it can refuse.
        // Claude and OpenCode only hold an id, and clearing an id cannot lose a
        // turn -- but they are cleared only once Pi agreed, so a refusal leaves
        // ALL three engines exactly as they were.
        if (!piRpc.startNewSession()) {
            res.writeHead(409, { "Content-Type": "application/json" });
            res.end(JSON.stringify({
                error: "Hay una respuesta en curso. Esperá a que termine para abrir una sesión nueva."
            }));
            return;
        }
        activeClaudeSessionId = null;
        activeOpenCodeSessionId = null;
        console.log(`[Sessions] Sesión nueva lista en '${currentStatus.activeEngine}' con ${currentStatus.operatingProvider || "auto"}/${currentStatus.operatingAi}.`);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({
            status: "ok",
            activeEngine: currentStatus.activeEngine,
            operatingAi: currentStatus.operatingAi,
            operatingProvider: currentStatus.operatingProvider
        }));
        return;
    }

    // 8. Cambiar Modelo de IA Activo en Pi CLI (--provider y --model)
    if (pathname === "/api/v1/model/switch" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            try {
                const parsed = JSON.parse(body);
                const modelName = parsed.model || parsed.modelId;
                let providerName = parsed.provider;

                if (!modelName) {
                    res.writeHead(400, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ error: "Nombre de modelo requerido" }));
                    return;
                }

                if (!providerName) {
                    const allModels = getAvailableModels();
                    const found = allModels.find(m => m.id.toLowerCase() === modelName.toLowerCase());
                    if (found) providerName = found.provider;
                }

                const prevOperatingAi = currentStatus.operatingAi;
                const prevOperatingProvider = currentStatus.operatingProvider;
                currentStatus.operatingAi = modelName;
                if (providerName) currentStatus.operatingProvider = providerName;

                // The log used to print provider + '/' + model verbatim, which
                // showed a DOUBLED prefix for OpenCode catalog ids that already
                // carry their provider ("opencode/opencode/muse-..."). Print the
                // normalized single id instead of the misleading double.
                const displayModelId = modelName.includes("/")
                    ? modelName
                    : (providerName ? `${providerName}/${modelName}` : modelName);
                console.log(`[Model Switch] Aplicando modelo único: ${displayModelId}...`);

                if (currentStatus.activeEngine === "claude") {
                    // Se recuerda el modelo elegido para pasarlo como --model en cada
                    // invocación del CLI de Claude (ver rama "claude" de /api/v1/chat/send).
                    // Sin esto, el switch quedaba solo en el estado del servidor y nunca
                    // llegaba a afectar al proceso real de Claude Code.
                    activeClaudeModelId = modelName;
                    console.log(`[Model Switch] Claude CLI usará --model ${modelName} en el próximo mensaje.`);
                } else if (currentStatus.activeEngine === "opencode") {
                    // OpenCode también es un CLI por mensaje: el modelo se recuerda y se
                    // pasa como -m en cada invocación (ver rama "opencode" de
                    // /api/v1/chat/send). El id se normaliza (los ids del catálogo ya
                    // llevan el proveedor; prefijarlo de nuevo construye
                    // "opencode/opencode/x", que el CLI no resuelve) y se valida contra
                    // el catálogo real ANTES de recordarlo: un modelo inexistente falla
                    // rápido con un mensaje claro en vez de llegar al spawn y colgarse.
                    const normalizedOpenCodeModel = normalizeOpenCodeModel(modelName, providerName);
                    const allModels = getAvailableModels();
                    const catalogHasModel = allModels.some(
                        m => String(m.id || "").toLowerCase() === normalizedOpenCodeModel.toLowerCase()
                    );
                    if (!catalogHasModel) {
                        console.warn(`[Model Switch] Modelo "${normalizedOpenCodeModel}" no existe en opencode models; se ignora el switch.`);
                        currentStatus.operatingAi = prevOperatingAi;
                        currentStatus.operatingProvider = prevOperatingProvider;
                        res.writeHead(400, { "Content-Type": "application/json" });
                        res.end(JSON.stringify({ error: `El modelo "${normalizedOpenCodeModel}" no existe en opencode models.` }));
                        return;
                    }
                    activeOpenCodeModel = { model: normalizedOpenCodeModel, provider: providerName || null };
                    console.log(`[Model Switch] OpenCode CLI usará -m ${normalizedOpenCodeModel} en el próximo mensaje.`);
                } else {
                    piRpc.setModel(modelName, providerName);
                }

                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ status: "ok", operatingAi: modelName, operatingProvider: providerName }));
            } catch (err) {
                console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // 9. Agregar Nuevo Proyecto Dinámicamente
    if (pathname === "/api/v1/projects/add" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            try {
                const parsed = JSON.parse(body);
                const { name, path } = parsed;

                if (!name || !path) {
                    res.writeHead(400, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ error: "Nombre y Ruta son obligatorios" }));
                    return;
                }

                if (!existsSync(path)) {
                    res.writeHead(400, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ error: `La ruta '${path}' no existe en tu PC` }));
                    return;
                }

                const slugId = name.toLowerCase().trim().replace(/[^a-z0-9]/g, "_");
                const existingIdx = registeredProjects.findIndex(p => p.id === slugId || p.path.toLowerCase() === path.toLowerCase());

                const newProject = {
                    id: existingIdx >= 0 ? registeredProjects[existingIdx].id : slugId,
                    name: name.trim(),
                    path: path.trim(),
                    isActive: false,
                    status: "Disponible",
                    lastActivity: "Recién agregado"
                };

                if (existingIdx >= 0) {
                    registeredProjects[existingIdx] = newProject;
                } else {
                    registeredProjects.push(newProject);
                }

                saveProjects();
                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ status: "ok", project: newProject }));
            } catch (err) {
                console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // 9b. Explorador Visual de Carpetas y Discos de PC
    if (pathname === "/api/v1/fs/browse" && req.method === "GET") {
        try {
            const requestedPath = parsedUrl.searchParams.get("path") || "";
            const result = browseFileSystem(requestedPath);
            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify(result));
        } catch (err) {
            console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
            res.writeHead(500, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: err.message }));
        }
        return;
    }

    // 9c. Eliminar o Quitar Proyectos (Individual o Múltiple)
    if ((pathname === "/api/v1/projects/remove" || pathname === "/api/v1/projects/delete") && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            try {
                const parsed = JSON.parse(body || "{}");
                const targetIds = Array.isArray(parsed.projectIds) ? parsed.projectIds : (parsed.projectId ? [parsed.projectId] : []);
                const deleteFiles = Boolean(parsed.deleteFiles);

                if (targetIds.length === 0) {
                    res.writeHead(400, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ error: "Debe especificar al menos un projectId" }));
                    return;
                }

                // Helper de seguridad para evitar borrar carpetas del sistema.
                // Vive en lib/system-paths.mjs (testeado): protege raíces de
                // sistema a CUALQUIER profundidad y deja deletable el territorio
                // del usuario donde viven los proyectos.

                const removedList = [];
                for (const pid of targetIds) {
                    const idx = registeredProjects.findIndex(p => p.id === pid);
                    if (idx >= 0) {
                        const proj = registeredProjects[idx];
                        if (deleteFiles && proj.path) {
                            if (!isProtectedSystemPath(proj.path)) {
                                try {
                                    if (existsSync(proj.path)) {
                                        rmSync(proj.path, { recursive: true, force: true });
                                        console.log(`[Project Remove] Carpeta física eliminada: ${proj.path}`);
                                    }
                                } catch (rmErr) {
                                    console.error(`[Project Remove Error] Error al borrar archivos de ${proj.path}:`, rmErr.message);
                                }
                            } else {
                                console.warn(`[Project Remove Warning] Ruta protegida, se omitió el borrado físico: ${proj.path}`);
                            }
                        }
                        registeredProjects.splice(idx, 1);
                        removedList.push(pid);
                    }
                }

                if (registeredProjects.length === 0) {
                    registeredProjects.push({
                        id: "idupi",
                        name: "Proyecto Principal",
                        path: process.cwd(),
                        isActive: true,
                        status: "Activo",
                        lastActivity: "Ahora"
                    });
                }

                // Si el proyecto activo fue eliminado, activar el primero disponible
                if (!registeredProjects.some(p => p.id === activeProjectId)) {
                    activeProjectId = registeredProjects[0].id;
                    registeredProjects[0].isActive = true;
                    currentStatus.project = registeredProjects[0].name;
                    activeClaudeSessionId = null;
                    piRpc.switchProject();
                }

                saveProjects();
                console.log(`[Projects DB] Eliminados ${removedList.length} proyecto(s): ${removedList.join(", ")}`);
                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ status: "ok", removedCount: removedList.length, projects: registeredProjects }));
            } catch (err) {
                console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

function getDetectedProviders() {
    const providers = new Set(["opencode-go"]);
    try {
        const authPath = join(homedir(), ".local", "share", "opencode", "auth.json");
        if (existsSync(authPath)) {
            const authData = JSON.parse(readFileSync(authPath, "utf8"));
            Object.keys(authData).forEach(k => providers.add(k));
        }
    } catch (e) {}

    // Otros proveedores comunes
    providers.add("openai");
    providers.add("anthropic");
    providers.add("google");
    providers.add("minimax");
    providers.add("zai");
    providers.add("moonshotai");
    providers.add("kimi-for-coding");
    providers.add("alibaba");

    return Array.from(providers);
}

const SDD_PROFILE_PRESETS = {
    "strong": {
        id: "strong",
        name: "Strong (Alta Precisión & Calidad)",
        description: "Modelos insignia para razonamiento profundo y arquitectura compleja (GPT-5.6-Luna, DeepSeek-V4-Pro, Claude 3.7 Sonnet).",
        modelAssignments: {
            "gentle-orchestrator": { provider_id: "opencode-go", model_id: "gpt-5.6-luna", effort: "high" },
            "sdd-explore": { provider_id: "opencode-go", model_id: "deepseek-v4-pro" },
            "sdd-propose": { provider_id: "opencode-go", model_id: "qwen3.7-max" },
            "sdd-spec": { provider_id: "opencode-go", model_id: "deepseek-v4-pro" },
            "sdd-design": { provider_id: "opencode-go", model_id: "deepseek-v4-pro" },
            "sdd-tasks": { provider_id: "opencode-go", model_id: "gpt-5.6-luna", effort: "high" },
            "sdd-apply": { provider_id: "opencode-go", model_id: "gpt-5.6-luna", effort: "high" },
            "sdd-verify": { provider_id: "opencode-go", model_id: "gpt-5.6-luna", effort: "high" },
            "sdd-archive": { provider_id: "opencode-go", model_id: "mimo-v2.5" },
            "review-risk": { provider_id: "opencode-go", model_id: "qwen3.7-max" },
            "review-resilience": { provider_id: "opencode-go", model_id: "qwen3.7-max" },
            "review-readability": { provider_id: "opencode-go", model_id: "qwen3.7-max" },
            "review-reliability": { provider_id: "opencode-go", model_id: "deepseek-v4-pro" }
        },
        claudeAssignments: {
            "sdd-explore": { model: "sonnet" },
            "sdd-propose": { model: "sonnet" },
            "sdd-spec": { model: "sonnet" },
            "sdd-design": { model: "sonnet" },
            "sdd-tasks": { model: "sonnet" },
            "sdd-apply": { model: "sonnet" },
            "sdd-verify": { model: "sonnet" },
            "sdd-archive": { model: "haiku" }
        },
        piAssignments: {
            "sdd-explore":  { provider_id: "opencode-go", model_id: "deepseek-v4-pro" },
            "sdd-propose":  { provider_id: "opencode-go", model_id: "qwen3.7-max" },
            "sdd-spec":     { provider_id: "opencode-go", model_id: "deepseek-v4-pro" },
            "sdd-design":   { provider_id: "opencode-go", model_id: "deepseek-v4-pro" },
            "sdd-tasks":    { provider_id: "opencode-go", model_id: "gpt-5.6-luna", effort: "high" },
            "sdd-apply":    { provider_id: "opencode-go", model_id: "gpt-5.6-luna", effort: "high" },
            "sdd-verify":   { provider_id: "opencode-go", model_id: "gpt-5.6-luna", effort: "high" },
            "sdd-archive":  { provider_id: "opencode-go", model_id: "mimo-v2.5" }
        }
    },
    "mid": {
        id: "mid",
        name: "Mid (Equilibrado & Rápido)",
        description: "Balance óptimo entre velocidad, costo y razonamiento (Hy3, Qwen 3.7 Plus, DeepSeek-V4-Flash, Sonnet).",
        modelAssignments: {
            "gentle-orchestrator": { provider_id: "opencode-go", model_id: "gpt-5.6-luna", effort: "high" },
            "sdd-explore": { provider_id: "opencode-go", model_id: "mimo-v2.5" },
            "sdd-propose": { provider_id: "opencode-go", model_id: "qwen3.7-max" },
            "sdd-spec": { provider_id: "opencode-go", model_id: "qwen3.7-plus" },
            "sdd-design": { provider_id: "opencode-go", model_id: "deepseek-v4-pro" },
            "sdd-tasks": { provider_id: "opencode-go", model_id: "hy3", effort: "high" },
            "sdd-apply": { provider_id: "opencode-go", model_id: "hy3" },
            "sdd-verify": { provider_id: "opencode-go", model_id: "hy3" },
            "sdd-archive": { provider_id: "opencode-go", model_id: "mimo-v2.5" },
            "review-risk": { provider_id: "opencode-go", model_id: "qwen3.7-plus" },
            "review-resilience": { provider_id: "opencode-go", model_id: "qwen3.7-plus" },
            "review-readability": { provider_id: "opencode-go", model_id: "qwen3.7-max" },
            "review-reliability": { provider_id: "opencode-go", model_id: "deepseek-v4-flash" }
        },
        claudeAssignments: {
            "sdd-explore": { model: "sonnet" },
            "sdd-propose": { model: "sonnet" },
            "sdd-spec": { model: "sonnet" },
            "sdd-design": { model: "sonnet" },
            "sdd-tasks": { model: "sonnet" },
            "sdd-apply": { model: "sonnet" },
            "sdd-verify": { model: "sonnet" },
            "sdd-archive": { model: "haiku" }
        },
        piAssignments: {
            "sdd-explore": { provider_id: "opencode-go", model_id: "mimo-v2.5" },
            "sdd-propose": { provider_id: "opencode-go", model_id: "qwen3.7-max" },
            "sdd-spec":    { provider_id: "opencode-go", model_id: "qwen3.7-plus" },
            "sdd-design":  { provider_id: "opencode-go", model_id: "deepseek-v4-pro" },
            "sdd-tasks":   { provider_id: "opencode-go", model_id: "hy3", effort: "high" },
            "sdd-apply":   { provider_id: "opencode-go", model_id: "hy3" },
            "sdd-verify":  { provider_id: "opencode-go", model_id: "hy3" },
            "sdd-archive": { provider_id: "opencode-go", model_id: "mimo-v2.5" }
        }
    },
    "cheap": {
        id: "cheap",
        name: "Cheap (Económico & Liviano)",
        description: "Ultra rápido y bajo costo para exploración ágil y documentación (Mimo-v2.5, Haiku, Qwen 3.7 Flash).",
        modelAssignments: {
            "gentle-orchestrator": { provider_id: "opencode-go", model_id: "hy3", effort: "low" },
            "sdd-explore": { provider_id: "opencode-go", model_id: "mimo-v2.5" },
            "sdd-propose": { provider_id: "opencode-go", model_id: "qwen3.7-plus" },
            "sdd-spec": { provider_id: "opencode-go", model_id: "qwen3.7-plus" },
            "sdd-design": { provider_id: "opencode-go", model_id: "mimo-v2.5" },
            "sdd-tasks": { provider_id: "opencode-go", model_id: "mimo-v2.5" },
            "sdd-apply": { provider_id: "opencode-go", model_id: "hy3" },
            "sdd-verify": { provider_id: "opencode-go", model_id: "hy3" },
            "sdd-archive": { provider_id: "opencode-go", model_id: "mimo-v2.5" },
            "review-risk": { provider_id: "opencode-go", model_id: "deepseek-v4-flash" },
            "review-resilience": { provider_id: "opencode-go", model_id: "deepseek-v4-flash" },
            "review-readability": { provider_id: "opencode-go", model_id: "deepseek-v4-flash" },
            "review-reliability": { provider_id: "opencode-go", model_id: "deepseek-v4-flash" }
        },
        claudeAssignments: {
            "sdd-explore": { model: "haiku" },
            "sdd-propose": { model: "haiku" },
            "sdd-spec": { model: "haiku" },
            "sdd-design": { model: "haiku" },
            "sdd-tasks": { model: "haiku" },
            "sdd-apply": { model: "haiku" },
            "sdd-verify": { model: "haiku" },
            "sdd-archive": { model: "haiku" }
        },
        piAssignments: {
            "sdd-explore": { provider_id: "opencode-go", model_id: "mimo-v2.5" },
            "sdd-propose": { provider_id: "opencode-go", model_id: "qwen3.7-plus" },
            "sdd-spec":    { provider_id: "opencode-go", model_id: "qwen3.7-plus" },
            "sdd-design":  { provider_id: "opencode-go", model_id: "mimo-v2.5" },
            "sdd-tasks":   { provider_id: "opencode-go", model_id: "mimo-v2.5" },
            "sdd-apply":   { provider_id: "opencode-go", model_id: "hy3" },
            "sdd-verify":  { provider_id: "opencode-go", model_id: "hy3" },
            "sdd-archive": { provider_id: "opencode-go", model_id: "mimo-v2.5" }
        }
    }
};

function getCustomSddProfiles() {
    const list = [];
    const profilesDir = join(homedir(), ".config", "opencode", "profiles");
    if (existsSync(profilesDir)) {
        try {
            const files = readdirSync(profilesDir).filter(f => f.endsWith(".json"));
            for (const f of files) {
                try {
                    const pData = JSON.parse(readFileSync(join(profilesDir, f), "utf8"));
                    const id = basename(f, ".json");
                    list.push({
                        id,
                        name: pData.name || id,
                        description: pData.description || `Perfil SDD guardado en tu PC`,
                        isCustom: true,
                        modelAssignments: pData.modelAssignments || pData.model_assignments || {},
                        claudeAssignments: pData.claudeAssignments || pData.claude_phase_assignments || {},
                        piAssignments: pData.piAssignments || pData.pi_phase_assignments || {}
                    });
                } catch (e) {}
            }
        } catch (e) {}
    }
    return list;
}

const providerModelsCache = new Map();

function getModelsForProvider(providerId) {
    if (providerModelsCache.has(providerId)) {
        return providerModelsCache.get(providerId);
    }
    try {
        const raw = execSync(`opencode models "${providerId}"`, { encoding: "utf8", timeout: 4000, maxBuffer: EXEC_MAX_BUFFER });
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

    // 10a2. Obtener Modelos Reales de un Proveedor de OpenCode
    if (pathname.startsWith("/api/v1/orchestrator/providers/") && pathname.endsWith("/models") && req.method === "GET") {
        const parts = pathname.split("/");
        const providerId = parts[5];
        const models = getModelsForProvider(providerId);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ provider: providerId, models }));
        return;
    }

    // 10b. Estado y Asignaciones de Orquestador SDD & Gentle-AI
    if (pathname === "/api/v1/orchestrator/status" && req.method === "GET") {
        try {
            const { status, body } = await handleStatusRoute(
                resolveProject(activeProjectId).path,
                getDetectedProviders,
                getCustomSddProfiles,
            );
            res.writeHead(status, { "Content-Type": "application/json" });
            res.end(JSON.stringify(body));
        } catch (err) {
            console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
            res.writeHead(500, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: err.message }));
        }
        return;
    }

    // 10b2. Aplicar Perfil SDD Completo a Gentle-AI
    if (pathname === "/api/v1/orchestrator/profiles/apply" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            (async () => {
                try {
                    const parsed = JSON.parse(body || "{}");
                    const { status, body: respBody } = await handleProfileApplyWithPresets(
                        SDD_PROFILE_PRESETS,
                        parsed,
                        { log: (m) => console.log(m), warn: (m) => console.warn(m) },
                    );
                    res.writeHead(status, { "Content-Type": "application/json" });
                    res.end(JSON.stringify(respBody));
                } catch (err) {
                    console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                    res.writeHead(500, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ error: err.message }));
                }
            })();
        });
        return;
    }

    // 10b3. Guardar o Actualizar Perfil SDD en ~/.config/opencode/profiles/
    if (pathname === "/api/v1/orchestrator/profiles/save" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            try {
                const parsed = JSON.parse(body || "{}");
                const profileName = (parsed.profileName || parsed.name || "").trim();
                const profileId = (parsed.profileId || parsed.id || profileName.toLowerCase().replace(/[^a-z0-9_-]/g, "-")).trim();

                if (!profileName) {
                    res.writeHead(400, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ error: "profileName es obligatorio" }));
                    return;
                }

                const statePath = join(homedir(), ".gentle-ai", "state.json");
                let stateData = {};
                if (existsSync(statePath)) {
                    stateData = JSON.parse(readFileSync(statePath, "utf8"));
                }

                const profilesDir = join(homedir(), ".config", "opencode", "profiles");
                if (!existsSync(profilesDir)) {
                    mkdirSync(profilesDir, { recursive: true });
                }

                const profileFilePath = join(profilesDir, `${profileId}.json`);

                const profilePayload = {
                    id: profileId,
                    name: profileName,
                    description: parsed.description || `Perfil SDD guardado por IDUPI`,
                    created_at: new Date().toISOString(),
                    modelAssignments: parsed.modelAssignments || stateData.model_assignments || {},
                    claudeAssignments: parsed.claudeAssignments || stateData.claude_phase_assignments || {},
                    piAssignments: parsed.piAssignments || stateData.pi_phase_assignments || {}
                };

                writeFileSync(profileFilePath, JSON.stringify(profilePayload, null, 2), "utf8");
                console.log(`[Gentle-AI Profiles] Guardado perfil SDD: ${profileFilePath}`);

                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ status: "ok", id: profileId, name: profileName, profile: profilePayload }));
            } catch (err) {
                console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // 10b4. Eliminar Perfil SDD de ~/.config/opencode/profiles/
    if (pathname === "/api/v1/orchestrator/profiles/delete" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            try {
                const parsed = JSON.parse(body || "{}");
                const profileId = parsed.profileId;

                if (!profileId) {
                    res.writeHead(400, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ error: "profileId es obligatorio" }));
                    return;
                }

                const profileFilePath = join(homedir(), ".config", "opencode", "profiles", `${profileId}.json`);
                if (existsSync(profileFilePath)) {
                    unlinkSync(profileFilePath);
                    console.log(`[Gentle-AI Profiles] Eliminado perfil SDD: ${profileFilePath}`);
                }

                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ status: "ok", profileId }));
            } catch (err) {
                console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // 10c. Actualizar Asignación de Modelo en Gentle-AI
    if (pathname === "/api/v1/orchestrator/models/update" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            (async () => {
                try {
                    const parsed = JSON.parse(body || "{}");
                    const { status, body: respBody } = await handleModelsUpdate(
                        parsed,
                        { log: (m) => console.log(m), warn: (m) => console.warn(m) },
                    );
                    res.writeHead(status, { "Content-Type": "application/json" });
                    res.end(JSON.stringify(respBody));
                } catch (err) {
                    console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                    res.writeHead(500, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ error: err.message }));
                }
            })();
        });
        return;
    }

    // 10d. Ejecutar Acción / Herramienta de Gentle-AI en la PC
    if (pathname === "/api/v1/orchestrator/action" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            try {
                const parsed = JSON.parse(body || "{}");
                const action = parsed.action || "";
                let cmd = "";

                // Asegurar que state.json tenga los agentes registrados
                const statePath = join(homedir(), ".gentle-ai", "state.json");
                if (existsSync(statePath)) {
                    try {
                        const stateData = JSON.parse(readFileSync(statePath, "utf8"));
                        if (!stateData.installed_agents || stateData.installed_agents.length < 5) {
                            stateData.installed_agents = ["opencode", "claude-code", "pi", "codex", "kiro-ide", "kimi"];
                            writeFileSync(statePath, JSON.stringify(stateData, null, 2), "utf8");
                        }
                    } catch (e) {}
                }

                switch (action) {
                    case "sync":
                        cmd = "gentle-ai sync";
                        break;
                    case "doctor":
                        cmd = "gentle-ai doctor";
                        break;
                    case "skill-refresh":
                        cmd = "gentle-ai skill-registry refresh";
                        break;
                    case "review-status":
                        cmd = "gentle-ai review status";
                        break;
                    case "sdd-status":
                        cmd = "gentle-ai sdd-status";
                        break;
                    default:
                        res.writeHead(400, { "Content-Type": "application/json" });
                        res.end(JSON.stringify({ error: `Acción '${action}' no válida` }));
                        return;
                }

                const activeProj = resolveProject(activeProjectId);
                console.log(`[Gentle-AI Action] Ejecutando: ${cmd} en ${activeProj.path}...`);
                let output = "";
                let success = true;
                try {
                    output = execSync(cmd, { cwd: activeProj.path, encoding: "utf8", timeout: 15000, maxBuffer: EXEC_MAX_BUFFER });
                } catch (execErr) {
                    output = execErr.stdout ? execErr.stdout.toString() : (execErr.stderr ? execErr.stderr.toString() : execErr.message);
                    success = false;
                }

                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ status: success ? "ok" : "error", action, output }));
            } catch (err) {
                console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // 10. Seleccionar/Cambiar Proyecto Activo
    if (pathname === "/api/v1/projects/switch" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            try {
                const parsed = JSON.parse(body);
                if (parsed.projectId) {
                    const resolved = resolveProject(parsed.projectId);
                    activeProjectId = resolved.id;
                    activeClaudeSessionId = null;
                    registeredProjects.forEach(p => p.isActive = (p.id === activeProjectId));
                    currentStatus.project = resolved.name;
                    saveProjects();
                    piRpc.switchProject();
                    console.log(`[Project Switch] Proyecto activo cambiado a: ${resolved.name} (${activeProjectId}). Sesión reseteada.`);
                }
                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ status: "ok", activeProjectId }));
            } catch (err) {
                res.writeHead(400, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // 11. Eliminar Proyecto Registrado
    if (pathname.startsWith("/api/v1/projects/") && req.method === "DELETE") {
        const parts = pathname.split("/");
        const projId = parts[4];
        registeredProjects = registeredProjects.filter(p => p.id !== projId);
        saveProjects();
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ status: "ok" }));
        return;
    }

    // 12. Árbol de Archivos de Proyecto
    if (pathname.startsWith("/api/v1/projects/") && pathname.endsWith("/files") && req.method === "GET") {
        const parts = pathname.split("/");
        const projId = parts[4];
        const proj = resolveProject(projId);
        
        const filesTree = getProjectFilesTree(proj.path);
        
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(filesTree));
        return;
    }

    // 13. Lectura de Contenido de Archivo Real
    if (pathname.startsWith("/api/v1/projects/") && pathname.endsWith("/file-content") && req.method === "GET") {
        const parts = pathname.split("/");
        const projId = parts[4];
        const filePathParam = parsedUrl.searchParams.get("path") || "";
        const proj = resolveProject(projId);

        // Containment guard (security fix): join(root, userPath) alone let
        // dot-dot escapes AND absolute paths read arbitrary files on this
        // machine with nothing but the bearer token.
        const resolvedFile = resolveProjectFilePath(proj.path, filePathParam);
        if (!resolvedFile.ok) {
            res.writeHead(403, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: "path escapes the project root" }));
            return;
        }
        const fullFilePath = resolvedFile.path;
        console.log(`[File Reader] Leyendo contenido real de: ${fullFilePath}`);

        try {
            if (existsSync(fullFilePath)) {
                const content = readFileSync(fullFilePath, "utf8");
                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ content }));
            } else {
                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ content: `// Archivo '${filePathParam}' no encontrado en ${proj.path}` }));
            }
        } catch (err) {
            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ content: `// Error al leer '${filePathParam}': ${err.message}` }));
        }
        return;
    }

    // 14. Alertas del Supervisor
    if (pathname === "/api/v1/alerts" && req.method === "GET") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify([]));
        return;
    }

/**
 * Resolves WHAT to spawn for the Claude CLI, without a shell: the native
 * installer's claude.exe when present, else the real cli.js parsed out of the
 * npm .cmd shim (spawning .cmd requires shell:true -- exactly the injection
 * surface removed here). Cached; throws with a clear message if neither exists.
 */
let claudeSpawnTargetCache = null;
function resolveClaudeSpawnTarget() {
    if (claudeSpawnTargetCache) return claudeSpawnTargetCache;
    // 1. Native installers (Windows + Unix)
    const nativeCandidates = [
        join(homedir(), ".local", "bin", "claude.exe"),
        join(homedir(), "AppData", "Local", "Programs", "claude", "claude.exe"),
        join(homedir(), "AppData", "Local", "claude", "claude.exe"),
        join(process.env.LOCALAPPDATA || "", "Programs", "claude", "claude.exe"),
        join(process.env.APPDATA || "", "npm", "claude.cmd"),
        join(homedir(), "AppData", "Roaming", "npm", "claude.cmd"),
    ].filter(Boolean);
    for (const p of nativeCandidates) {
        try { if (existsSync(p) && p.toLowerCase().endsWith(".exe")) { claudeSpawnTargetCache = { exe: p }; return claudeSpawnTargetCache; } } catch {}
        // .cmd candidates are parsed below, but if they exist we can at least log
    }
    let hits = [];
    try {
        hits = execFileSync("where", ["claude"], { encoding: "utf8", maxBuffer: EXEC_MAX_BUFFER })
            .split(/\r?\n/)
            .map((s) => s.trim())
            .filter(Boolean);
    } catch {}
    if (hits.length === 0) {
        try {
            hits = execFileSync("where", ["claude.cmd"], { encoding: "utf8", maxBuffer: EXEC_MAX_BUFFER })
                .split(/\r?\n/)
                .map((s) => s.trim())
                .filter(Boolean);
        } catch {}
    }
    // Direct .exe hit from PATH (e.g. native installer added to PATH)
    const exeHit = hits.find((h) => h.toLowerCase().endsWith("claude.exe"));
    if (exeHit && existsSync(exeHit)) {
        claudeSpawnTargetCache = { exe: exeHit };
        return claudeSpawnTargetCache;
    }
    // .cmd shim hits - try each, not just first
    const cmdHits = hits.filter((h) => h.toLowerCase().endsWith(".cmd"));
    // Also add known shim locations if where missed them
    for (const p of nativeCandidates.filter((p) => p.toLowerCase().endsWith(".cmd"))) {
        if (existsSync(p) && !cmdHits.includes(p)) cmdHits.push(p);
    }
    for (const cmdShim of cmdHits) {
        try {
            const shim = readFileSync(cmdShim, "utf8");
            // Universal: match any claude-code entry - cli.js, bin/claude.js, bin/claude.exe (newer installer)
            const m = shim.match(/"([^"]*claude-code[\\\/][^"]+\.(js|exe))"/i)
                ?? shim.match(/([A-Za-z]:[^\s"]*claude-code[\\\/][^\s"]+\.(js|exe))/i)
                ?? shim.match(/([^\s"]*claude-code[\\\/][^\s"]+\.(js|exe))/i);
            if (m) {
                let targetPath = m[1].replace(/\//g, "\\");
                // Shim may contain %dp0% variable - resolve relative to shim dir
                if (targetPath.includes("%dp0%") || targetPath.includes("%~dp0") || targetPath.startsWith("%")) {
                    // Generic: the quoted part after %dp0% is the relative path inside npm package
                    const relMatch = shim.match(/%~?dp0%[\\\/]*([^"]+\.(js|exe))/i);
                    if (relMatch) {
                        targetPath = join(dirname(cmdShim), relMatch[1].replace(/\//g, "\\"));
                    } else {
                        targetPath = join(dirname(cmdShim), "node_modules", "@anthropic-ai", "claude-code", "bin", "claude.js");
                        if (!existsSync(targetPath)) targetPath = join(dirname(cmdShim), "node_modules", "@anthropic-ai", "claude-code", "cli.js");
                    }
                }
                const isJs = targetPath.toLowerCase().endsWith(".js");
                if (existsSync(targetPath)) {
                    claudeSpawnTargetCache = isJs ? { js: targetPath } : { exe: targetPath };
                    return claudeSpawnTargetCache;
                }
                // Even if not exists, try it - node will error clearly
                claudeSpawnTargetCache = isJs ? { js: targetPath } : { exe: targetPath };
                return claudeSpawnTargetCache;
            }
            console.warn(`[Claude Resolve] shim ${cmdShim} no matcheó regex, contenido: ${shim.slice(0, 400)}`);
        } catch (e) {
            console.warn(`[Claude Resolve] no se pudo leer shim ${cmdShim}: ${e.message}`);
        }
    }
    // Universal fallback: use the actual hit found by where (world-wide, no hardcoded path).
    // spawn("claude") without extension fails with ENOENT on some Node/Windows combos
    // because PATHEXT resolution needs the full .cmd path, so we use the hit directly.
    if (cmdHits.length > 0) {
        const fallbackShim = cmdHits[0];
        console.warn(`[Claude Resolve] shim no parseable pero where encontró ${hits.length} hit(s), fallback a shim directo ${fallbackShim} via cmd.exe`);
        claudeSpawnTargetCache = { cmdShim: fallbackShim };
        return claudeSpawnTargetCache;
    }
    if (hits.length > 0) {
        console.warn(`[Claude Resolve] shim no parseable pero where encontró ${hits.length} hit(s), fallback a spawn("claude") directo`);
        claudeSpawnTargetCache = { cmd: "claude" };
        return claudeSpawnTargetCache;
    }
    // Last probe: try to run claude --version directly (covers npx/pnpx edge cases)
    try {
        execFileSync("claude", ["--version"], { encoding: "utf8", timeout: 3000, maxBuffer: 4096 });
        console.warn(`[Claude Resolve] claude --version ok, fallback a spawn("claude")`);
        claudeSpawnTargetCache = { cmd: "claude" };
        return claudeSpawnTargetCache;
    } catch {}
    console.error(`[Claude Resolve] FAIL hits=${JSON.stringify(hits)} cmdHits=${JSON.stringify(cmdHits)} nativeChecked=${JSON.stringify(nativeCandidates)}`);
    const err = new Error(
        `No se encontró el ejecutable de Claude. where hits: ${hits.join(", ") || "(vacío)"}. Revisá que 'where claude' en cmd devuelva algo y que el shim .cmd contenga cli.js. Instalá Claude Code y reiniciá el server, o cambiá el motor a Pi/OpenCode.`
    );
    err.code = "CLAUDE_NOT_INSTALLED";
    throw err;
}

// Ejecución Asíncrona en Streaming para Claude CLI (Soporta tareas largas, subagentes en vivo y no se corta)
function runClaudeCli(projPath, sessionId, isNewSession, modelId, message) {
    return new Promise((resolve, reject) => {
        publishChatEvent(CHAT_EVENTS.THINKING, { active: true });

        // Security fix: the command used to be one interpolated STRING under
        // shell:true -- JSON.stringify's \" closes cmd.exe's quoted region,
        // so `& calc.exe` after it executed. Arguments now travel as an
        // ARRAY to a resolved executable: data can never become syntax.
        const target = resolveClaudeSpawnTarget();
        const args = claudeArgs({ modelId, sessionId, isNewSession, message });

        const targetLabel = target.exe ? target.exe : target.js ? `node ${target.js}` : target.cmdShim ? `cmd /c ${target.cmdShim}` : `claude (PATH)`;
        console.log(`[Claude CLI Spawn] Iniciando Claude en ${projPath}: ${targetLabel}`);

        let fullOutput = "";
        let buffer = "";
        // One card per delegation, keyed by the tool_use id Claude itself
        // assigns. A parallel launch opens several at once, so a single slot
        // here silently evicted every card but the last (see
        // test/claude-parallel-subagents.test.mjs).
        const subagentCards = new SubagentCards();
        // The last assistant message already delivered to the chat. Now that
        // every message is closed as it ends, the one published at close would
        // otherwise show the final answer a second time.
        let lastDeliveredText = "";
        let settled = false;
        let timedOut = false;

        let child;
        if (target.exe) {
            child = spawn(target.exe, args, {
                cwd: projPath,
                windowsHide: true,
                // Phase 3 (fix-ui-request-selection): stdin is now pipe so the
                // POST handler can deliver the user's UI answer back into
                // the engine as one JSON line. `bypassPermissions` at launch
                // covers permission prompts; this pipe covers interactive
                // questions that bypass does not.
                stdio: ["pipe", "pipe", "pipe"],
                env: process.env
            });
        } else if (target.js) {
            child = spawn(process.execPath, [target.js, ...args], {
                cwd: projPath,
                windowsHide: true,
                stdio: ["pipe", "pipe", "pipe"],
                env: process.env
            });
        } else if (target.cmdShim) {
            // .cmd shim needs cmd.exe to execute, but args stay as ARRAY after /c shim
            // The shim itself handles the node invocation, so we pass it as the command
            // and claude args after it. No shell:true on the outer spawn - we spawn cmd.exe directly.
            child = spawn("cmd.exe", ["/c", target.cmdShim, ...args], {
                cwd: projPath,
                windowsHide: true,
                stdio: ["pipe", "pipe", "pipe"],
                env: process.env
            });
        } else {
            child = spawn(target.cmd, args, {
                cwd: projPath,
                windowsHide: true,
                stdio: ["pipe", "pipe", "pipe"],
                env: process.env
            });
        }

        // Without a shell wrapper `child` IS Claude itself, but MCP servers it
        // spawns are its children -- a tree-kill still reaches everything.
        const timeoutTimer = setTimeout(() => {
            if (settled) return;
            timedOut = true;
            console.warn(`[Claude CLI Timeout] Sin cierre tras ${AGENT_CLI_TIMEOUT_MS}ms, terminando el árbol de procesos (PID ${child.pid}).`);
            execFile("taskkill", ["/F", "/T", "/PID", String(child.pid)], () => {});
        }, AGENT_CLI_TIMEOUT_MS);

        const processJsonLine = (line) => {
            const trimmed = line.trim();
            if (!trimmed) return;
            try {
                const event = JSON.parse(trimmed);

                // 1. Deliberación / Texto en tiempo real
                if (event.type === "content_block_delta" && event.delta?.text) {
                    fullOutput += event.delta.text;
                    activeTask.output = fullOutput;
                    publishChatEvent(CHAT_EVENTS.TEXT_DELTA, { text: event.delta.text });
                }

                // 2. Mensajes, subagentes y herramientas en vivo
                if (event.type === "assistant" && event.message?.content) {
                    const plan = planAssistantMessage(event.message.content);

                    // Each `assistant` event is ONE complete message, and a turn
                    // holds several of them. Closing each where Claude ends it is
                    // what keeps the chat in order: without it the app appended
                    // every later message into the bubble the first one opened,
                    // so the final answer rendered ABOVE the tool and subagent
                    // cards it came after. Closed before this message's own tool
                    // cards open, because its text was written before they ran.
                    if (plan.text) {
                        fullOutput = plan.text.trim();
                        activeTask.output = fullOutput;
                        lastDeliveredText = fullOutput;
                        publishChatEvent(CHAT_EVENTS.MESSAGE_END, { text: fullOutput });
                    }

                    for (const tool of plan.tools) {
                        const { id: toolId, name: toolName, item } = tool;
                        if (isSubagentTool(toolName, tool.input)) {
                            // The Task tool is always literally named "Task"; the agent
                            // the user actually picked lives in subagent_type.
                            const subagentName = describeSubagentName(toolName, tool.input);
                            subagentCards.open(toolId, subagentName);
                            const taskDesc = tool.input.task || tool.input.prompt || tool.input.description || `Ejecutando subagente ${toolName}...`;
                            publishChatEvent(CHAT_EVENTS.SUBAGENT_START, {
                                id: toolId,
                                name: subagentName,
                                task: taskDesc
                            });
                        } else {
                            const mcp = detectMcp("claude", { toolName });
                            activityRegistry.start(toolId, {
                                engine: "claude",
                                project: activeProjectId,
                                sessionId: currentActivitySession("claude"),
                                kind: mcp.isMcp ? "mcp" : "tool",
                                name: mcp.name,
                                detail: describeToolInput(item) || `Ejecutando ${toolName}...`,
                                server: mcp.isMcp ? mcp.server : undefined,
                            });
                            publishChatEvent(CHAT_EVENTS.TOOL_START, {
                                id: toolId,
                                name: toolName,
                                detail: describeToolInput(item) || `Ejecutando ${toolName}...`
                            });
                        }
                    }
                }

                // 3. Fin de herramienta / subagente
                if (event.type === "user" && event.message?.content) {
                    const content = event.message.content;
                    if (Array.isArray(content)) {
                        for (const item of content) {
                            if (item.type === "tool_result") {
                                const toolId = item.tool_use_id;
                                // Each result closes its OWN card. Comparing against the
                                // last launch instead routed the first subagent's result
                                // to the plain-tool branch below, leaving its card open.
                                const card = toolId ? subagentCards.close(toolId) : null;
                                if (card) {
                                    // The subagent's real answer is in item.content; discarding
                                    // it left the card reading "Subagente completó la tarea"
                                    // no matter what the subagent actually reported. Pi and
                                    // OpenCode already surface their result the same way.
                                    const resultText = extractToolResultText(item.content).trim();
                                    publishChatEvent(CHAT_EVENTS.SUBAGENT_END, {
                                        id: toolId,
                                        name: card.name || "Subagent",
                                        summary: summarizeSubagentResult(resultText),
                                        ok: !item.is_error
                                    });
                                } else if (toolId) {
                                    activityRegistry.terminalize(toolId, { ok: !item.is_error });
                                    publishChatEvent(CHAT_EVENTS.TOOL_END, {
                                        id: toolId,
                                        name: "Tool",
                                        ok: !item.is_error
                                    });
                                }
                            }
                        }
                    }
                }

                // 4. Detección de límite de tasa / cuota
                if (event.type === "rate_limit_event") {
                    // Claude emits this as ordinary status during a healthy turn.
                    // Announcing it every time told the app the quota was gone
                    // mid-conversation, and assigning it over fullOutput replaced
                    // the model's answer with the warning. It goes out on the
                    // system channel now, and only when the payload says so.
                    const notice = describeRateLimit(event.rate_limit_info);
                    if (notice) {
                        publishChatEvent(CHAT_EVENTS.TOOL_START, {
                            id: `rate-limit-${Date.now()}`,
                            name: "system",
                            detail: notice
                        });
                    } else {
                        console.log(`[Claude CLI] rate_limit_event informativo: ${JSON.stringify(event.rate_limit_info ?? null)}`);
                    }
                }

                // 5. Resultado final del CLI
                if (event.type === "result" && event.result) {
                    const resStr = typeof event.result === "string" ? event.result : JSON.stringify(event.result);
                    if (resStr.trim()) {
                        fullOutput = resStr.trim();
                        activeTask.output = fullOutput;
                    }
                }

                // 6. Phase 3 (fix-ui-request-selection): Claude may emit an
                // interactive question despite `--permission-mode
                // bypassPermissions` (e.g. `ask_user_question`). Normalize
                // and register so the chat app gets a `ui_request` SSE frame
                // and can POST its answer back to the child's stdin.
                const claudeUi = normalizeClaudeUiRequest(event);
                if (claudeUi && typeof child.stdin?.write === "function" && !child.killed) {
                    const reg = uiRequestRegistry.register({
                        sessionId: currentActivitySession("claude"),
                        engine: "claude",
                        method: claudeUi.method,
                        options: claudeUi.options,
                        title: claudeUi.title,
                        message: claudeUi.message,
                    });
                    const writer = (value) => {
                        if (!child || child.killed || !child.stdin || child.stdin.destroyed) return false;
                        try {
                            child.stdin.write(JSON.stringify({
                                type: "ui_response",
                                requestId: reg.requestId,
                                value,
                            }) + "\n");
                            return true;
                        } catch (err) {
                            console.warn(`[ui-request] Claude stdin write failed for ${reg.requestId}: ${err?.message || err}`);
                            return false;
                        }
                    };
                    writer.__child = child;
                    setUiRequestStdinWriter(reg.requestId, writer);
                }
            } catch (e) {
                // Línea no-JSON de salida estándar
                fullOutput += trimmed + "\n";
                activeTask.output = fullOutput;
                publishChatEvent(CHAT_EVENTS.TEXT_DELTA, { text: trimmed });
            }
        };

        child.stdout.on("data", (chunk) => {
            buffer += chunk.toString("utf8");
            const lines = buffer.split("\n");
            buffer = lines.pop();
            for (const line of lines) {
                processJsonLine(line);
            }
        });

        child.stderr.on("data", (chunk) => {
            const str = chunk.toString("utf8");
            console.log(`[Claude CLI Stderr] ${redactActivity(str.trim())}`);
        });

        child.on("error", (err) => {
            if (settled) return;
            settled = true;
            clearTimeout(timeoutTimer);
            publishChatEvent(CHAT_EVENTS.THINKING, { active: false });
            reject(err);
        });

        child.on("close", (code) => {
            if (settled) return;
            settled = true;
            clearTimeout(timeoutTimer);
            if (buffer.trim()) {
                processJsonLine(buffer);
            }

            publishChatEvent(CHAT_EVENTS.THINKING, { active: false });
            // Phase 3 (fix-ui-request-selection): drop pending stdin writers
            // bound to this Claude child so a late POST never tries to write
            // to a dead pipe. The registry's 120s timer still owns the entry's
            // terminal transition.
            try { clearUiRequestStdinWritersForChild(child); } catch {}
            // A run can end with cards still open (a crash, a timeout, a kill).
            // Closing one of them and leaving the rest is the same single-slot
            // mistake at the end of the stream, so every one is closed.
            for (const card of subagentCards.drain()) {
                publishChatEvent(CHAT_EVENTS.SUBAGENT_END, {
                    id: card.id,
                    name: card.name || "Subagent",
                    summary: "Subagente finalizó sin devolver un resultado",
                    ok: code === 0
                });
            }

            const cleanResult = fullOutput.trim() || (
                timedOut
                    ? `⚠️ Claude CLI no respondió dentro de ${AGENT_CLI_TIMEOUT_MS / 1000}s y fue detenido.`
                    : (code === 0 ? "Completado por Claude CLI." : `Claude CLI finalizó con código ${code}`)
            );
            activeTask.output = cleanResult;
            // Every assistant message was already closed where it ended, so the
            // only thing left to deliver here is a turn the chat received
            // nothing from: a timeout notice, an exit code, or output that never
            // arrived as an assistant message at all.
            if (!lastDeliveredText) {
                publishChatEvent(CHAT_EVENTS.MESSAGE_END, { text: cleanResult });
            }
            // The app emits a SECOND MessageEnded from this response body
            // (RealIduPiClient.sendMessage): the answer has two transports. Pi
            // has always resolved its last message, so the app's dedup matched
            // and nothing was shown twice. Returning the whole turn concatenated
            // is what made it show up again as one blob at the bottom.
            resolve(lastDeliveredText || cleanResult);
        });
    });
}

// Ejecución Asíncrona en Streaming para OpenCode

function runOpenCodeCli(projPath, sessionId, message, openCodeModel = null) {
    return new Promise((resolve, reject) => {
        publishChatEvent(CHAT_EVENTS.THINKING, { active: true });

        // Security fix (same class as Claude's): arguments travel as an ARRAY
        // to the resolved real executable -- a crafted sessionId or message
        // can no longer become cmd.exe syntax.
        const opencodeExe = resolveOpenCodeExePath();

        // The model id is normalized BEFORE the argv is built: an id that
        // already carries its provider must never be provider-prefixed again
        // ("opencode/opencode/x" cannot be resolved and left the CLI stuck).
        // A doubled first segment is rejected outright -- that shape is the
        // known-bad provider+model-that-contains-provider combination.
        let openCodeModelArg = openCodeModel;
        if (openCodeModel && openCodeModel.model) {
            const normalizedModel = normalizeOpenCodeModel(openCodeModel.model, openCodeModel.provider || "");
            const segments = normalizedModel.split("/");
            if (segments.length >= 3 && segments[0].toLowerCase() === segments[1].toLowerCase()) {
                publishChatEvent(CHAT_EVENTS.THINKING, { active: false });
                reject(new Error(`Modelo inválido "${normalizedModel}": prefijo de proveedor duplicado.`));
                return;
            }
            openCodeModelArg = { model: normalizedModel, provider: openCodeModel.provider || null };
        }
        const args = openCodeArgs({ model: openCodeModelArg?.model || "", provider: openCodeModelArg?.provider || "", sessionId, message });

        console.log(`[OpenCode CLI Spawn] Iniciando OpenCode en ${projPath}: ${opencodeExe}`);

        let fullOutput = "";
        let buffer = "";
        // OpenCode's stream marks no end of message, so it is derived: the text
        // written since the last tool call IS one message.
        const boundary = new MessageBoundary();
        let lastDeliveredText = "";
        let settled = false;
        let timedOut = false;

        const child = spawn(opencodeExe, args, {
            cwd: projPath,
            windowsHide: true,
            // Phase 3 (fix-ui-request-selection): stdin is now pipe so the
            // POST handler can deliver the user's UI answer back into the
            // engine as one JSON line. `--auto` at launch covers most
            // permission prompts; this pipe covers interactive questions
            // that `--auto` does not.
            stdio: ["pipe", "pipe", "pipe"],
            env: process.env
        });

        // CRITICAL hang fix (verified empirically against opencode-ai 1.18.27
        // on Windows): `opencode run` BLOCKS at startup while stdin is an
        // OPEN pipe -- the CLI waits on stdin before the session is even
        // created, emits nothing, and never exits, so the 'close' listener
        // below never fires and the only resolution was the 300s
        // AGENT_CLI_TIMEOUT_MS taskkill ("pensando para siempre" en la app).
        // EOF-ing stdin right after spawn unblocks startup (probe: piped
        // stdin + child.stdin.end() -> exit 0 y stream normal; stdin abierto
        // -> hang en init con cero salida). Trade-off: the Phase-3
        // ui_response stdin delivery can no longer reach the child -- the
        // writer guard below already logs when stdin is destroyed, and
        // `--auto` denies question/permission prompts by default anyway.
        if (child.stdin) {
            try { child.stdin.end(); } catch (err) { console.warn(`[OpenCode CLI] stdin close falló: ${err?.message || err}`); }
        }

        // MCP servers OpenCode spawns are its children -- the tree-kill still
        // reaches every descendant from the real root.
        const timeoutTimer = setTimeout(() => {
            if (settled) return;
            timedOut = true;
            console.warn(`[OpenCode CLI Timeout] Sin cierre tras ${AGENT_CLI_TIMEOUT_MS}ms, terminando el árbol de procesos (PID ${child.pid}).`);
            execFile("taskkill", ["/F", "/T", "/PID", String(child.pid)], () => {});
        }, AGENT_CLI_TIMEOUT_MS);

        const processJsonLine = (line) => {
            const trimmed = line.trim();
            if (!trimmed) return;
            try {
                const event = JSON.parse(trimmed);
                if (event.type === "text" && event.part?.text) {
                    fullOutput += event.part.text;
                    boundary.append(event.part.text);
                    activeTask.output = fullOutput;
                    publishChatEvent(CHAT_EVENTS.TEXT_DELTA, { text: event.part.text });
                } else if (event.type === "tool_use" && event.part) {
                    const toolName = event.part.tool || "Tool";
                    const input = event.part.state?.input || {};
                    const callId = event.part.callID || event.part.id || `tool-${Date.now()}`;
                    const isSubagent = isSubagentTool(toolName, input);

                    // Reaching for a tool ends the message being written, so it
                    // is closed HERE -- before this tool's card is appended
                    // below it. Without that the bubble stayed open all turn and
                    // every later message was written back into it, above every
                    // card. See lib/message-boundary.mjs.
                    const finished = boundary.take();
                    if (finished) {
                        lastDeliveredText = finished;
                        publishChatEvent(CHAT_EVENTS.MESSAGE_END, { text: finished });
                    }

                    if (isSubagent) {
                        const subagentName = describeSubagentName(toolName, input);
                        const taskDesc = input.question || input.task || input.prompt
                            || input.description || `Consultando a subagente ${subagentName}...`;
                        publishChatEvent(CHAT_EVENTS.SUBAGENT_START, {
                            id: callId,
                            name: subagentName,
                            task: taskDesc
                        });
                        publishChatEvent(CHAT_EVENTS.SUBAGENT_END, {
                            id: callId,
                            name: subagentName,
                            summary: summarizeSubagentResult(
                                event.part.state?.output ? String(event.part.state.output) : "",
                            ),
                            ok: event.part.state?.status !== "error"
                        });
                    } else {
                        // OpenCode delivers start+end together; server is
                        // additive (may be unknown until the end event).
                        const mcp = detectMcp("opencode", { toolName, server: event.part?.state?.server });
                        const ocDetail = describeToolInput({ input }) || `Ejecutando ${toolName}...`;
                        activityRegistry.start(callId, {
                            engine: "opencode",
                            project: activeProjectId,
                            sessionId: currentActivitySession("opencode"),
                            kind: mcp.isMcp ? "mcp" : "tool",
                            name: mcp.name,
                            detail: ocDetail,
                            server: mcp.server,
                        });
                        const ocOk = event.part.state?.status !== "error";
                        if (!ocOk) activityRegistry.terminalize(callId, { ok: false, errorClass: "tool" });
                        else activityRegistry.terminalize(callId, { ok: true });
                        publishChatEvent(CHAT_EVENTS.TOOL_START, {
                            id: callId,
                            name: toolName,
                            detail: ocDetail
                        });
                        publishChatEvent(CHAT_EVENTS.TOOL_END, {
                            id: callId,
                            name: toolName,
                            ok: ocOk
                        });
                    }
                }

                // Phase 3 (fix-ui-request-selection): OpenCode may surface a
                // `question` part or a permission prompt despite `--auto`.
                // Normalize through the engine adapter so the chat app gets a
                // `ui_request` SSE frame and the answer routes back through
                // child.stdin.
                const ocUi = normalizeOpenCodeUiRequest(event);
                if (ocUi && typeof child.stdin?.write === "function" && !child.killed) {
                    const reg = uiRequestRegistry.register({
                        sessionId: currentActivitySession("opencode"),
                        engine: "opencode",
                        method: ocUi.method,
                        options: ocUi.options,
                        title: ocUi.title,
                        message: ocUi.message,
                    });
                    const writer = (value) => {
                        if (!child || child.killed || !child.stdin || child.stdin.destroyed) return false;
                        try {
                            child.stdin.write(JSON.stringify({
                                type: "ui_response",
                                requestId: reg.requestId,
                                value,
                            }) + "\n");
                            return true;
                        } catch (err) {
                            console.warn(`[ui-request] OpenCode stdin write failed for ${reg.requestId}: ${err?.message || err}`);
                            return false;
                        }
                    };
                    writer.__child = child;
                    setUiRequestStdinWriter(reg.requestId, writer);
                }
            } catch (e) {
                fullOutput += trimmed + "\n";
                boundary.append(trimmed + "\n");
                activeTask.output = fullOutput;
                publishChatEvent(CHAT_EVENTS.TEXT_DELTA, { text: trimmed });
            }
        };

        child.stdout.on("data", (chunk) => {
            buffer += chunk.toString("utf8");
            const lines = buffer.split("\n");
            buffer = lines.pop();
            for (const line of lines) {
                processJsonLine(line);
            }
        });

        child.stderr.on("data", (chunk) => {
            const str = chunk.toString("utf8");
            console.log(`[OpenCode CLI Stderr] ${redactActivity(str.trim())}`);
        });

        child.on("error", (err) => {
            if (settled) return;
            settled = true;
            clearTimeout(timeoutTimer);
            publishChatEvent(CHAT_EVENTS.THINKING, { active: false });
            reject(err);
        });

        child.on("close", (code) => {
            if (settled) return;
            settled = true;
            clearTimeout(timeoutTimer);
            if (buffer.trim()) {
                processJsonLine(buffer);
            }
            publishChatEvent(CHAT_EVENTS.THINKING, { active: false });
            // Phase 3 (fix-ui-request-selection): drop pending stdin writers
            // bound to this OpenCode child so a late POST never writes to a
            // dead pipe. The registry's 120s timer still owns the entry's
            // terminal transition.
            try { clearUiRequestStdinWritersForChild(child); } catch {}

            // The last message has no tool after it to close it, so the end of
            // the run does.
            const finished = boundary.take();
            if (finished) {
                lastDeliveredText = finished;
                publishChatEvent(CHAT_EVENTS.MESSAGE_END, { text: finished });
            }

            const cleanResult = fullOutput.trim() || (
                timedOut
                    ? `⚠️ OpenCode no respondió dentro de ${AGENT_CLI_TIMEOUT_MS / 1000}s y fue detenido.`
                    : (code === 0 ? "Completado por OpenCode." : `OpenCode finalizó con código ${code}`)
            );
            activeTask.output = cleanResult;
            // cleanResult is the WHOLE turn concatenated, so it must never be
            // published on top of messages already delivered one by one -- that
            // would repeat the entire turn as one bubble at the bottom. It is
            // published only when the chat received nothing at all: a timeout
            // notice, an exit code, output that never arrived as a message.
            if (!lastDeliveredText) {
                publishChatEvent(CHAT_EVENTS.MESSAGE_END, { text: cleanResult });
            }
            // The app emits a SECOND MessageEnded from this response body
            // (RealIduPiClient.sendMessage): the answer has two transports. Pi
            // has always resolved its last message, so the app's dedup matched
            // and nothing was shown twice. Returning the whole turn concatenated
            // is what made it show up again as one blob at the bottom.
            resolve(lastDeliveredText || cleanResult);
        });
    });
}

    // 15. Chat Interactivo con Seguimiento de Tarea Activa en Segundo Plano
    if (pathname === "/api/v1/chat/message" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", async () => {
            try {
                const parsed = JSON.parse(body);
                const userMessage = parsed.message || "";
                
                const taskId = `task-${Date.now()}`;
                const clientTaskId = typeof parsed.clientTaskId === "string" ? parsed.clientTaskId : null;
                if (clientTaskId) taskRegistry.start(clientTaskId, userMessage);
                activeTask = {
                    id: taskId,
                    message: userMessage,
                    status: "running",
                    output: "",
                    error: null,
                    startTime: Date.now()
                };

                currentStatus.busy = true;
                currentStatus.cliTask = `Procesando: "${userMessage}"`;

                try {
                    let agentOutput = "";
                    const activeProj = resolveProject(activeProjectId);

                    if (currentStatus.activeEngine === "claude") {
                        const isNewClaudeSession = !activeClaudeSessionId;
                        if (isNewClaudeSession) {
                            activeClaudeSessionId = randomUUID();
                            console.log(`[Claude Session] Creando nueva sesión con ID: ${activeClaudeSessionId}`);
                        }
                        agentOutput = await runClaudeCli(activeProj.path, activeClaudeSessionId, isNewClaudeSession, activeClaudeModelId, userMessage);
                    } else if (currentStatus.activeEngine === "opencode") {
                        agentOutput = await runOpenCodeCli(activeProj.path, activeOpenCodeSessionId, userMessage, activeOpenCodeModel);
                        if (!activeOpenCodeSessionId) {
                            try {
                                const rawDb = execSync('opencode db "SELECT id FROM session ORDER BY time_updated DESC LIMIT 1" --format json', { encoding: "utf8", maxBuffer: EXEC_MAX_BUFFER });
                                const latestSes = JSON.parse(rawDb)[0];
                                if (latestSes && latestSes.id) {
                                    activeOpenCodeSessionId = latestSes.id;
                                    console.log(`[OpenCode Session] Sesión fijada tras primer mensaje: ${activeOpenCodeSessionId}`);
                                }
                            } catch (e) {}
                        }
                    } else {
                        agentOutput = await piRpc.sendPrompt(userMessage);
                    }

                    activeTask.status = "completed";
                    activeTask.output = agentOutput;
                    if (clientTaskId) taskRegistry.finish(clientTaskId, { output: agentOutput });

                    currentStatus.busy = false;
                    currentStatus.cliTask = "En espera de mensajes";

                    res.writeHead(200, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ status: "ok", taskId, output: agentOutput }));
                } catch (piErr) {
                    // Graceful degrade for missing Claude binary: don't crash the session,
                    // show the message as assistant output so the user can switch engine.
                    if (piErr && piErr.code === "CLAUDE_NOT_INSTALLED") {
                        const friendly = piErr.message;
                        activeTask.status = "completed";
                        activeTask.output = `⚠️ ${friendly}`;
                        if (clientTaskId) taskRegistry.finish(clientTaskId, { output: friendly });
                        currentStatus.busy = false;
                        currentStatus.cliTask = "En espera de mensajes";
                        console.warn(`[Claude Not Installed] ${friendly}`);
                        res.writeHead(200, { "Content-Type": "application/json" });
                        res.end(JSON.stringify({ status: "ok", taskId, output: `⚠️ ${friendly}`, code: "CLAUDE_NOT_INSTALLED" }));
                        return;
                    }
                    activeTask.status = "error";
                    activeTask.error = piErr.message;
                    if (clientTaskId) taskRegistry.finish(clientTaskId, { error: piErr.message });

                    currentStatus.busy = false;
                    currentStatus.cliTask = "Error en tarea";

                    console.error(`[IDUPI 500] ${req.method} ${pathname}`, piErr);
                    res.writeHead(500, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ error: piErr.message }));
                }
            } catch (err) {
                currentStatus.busy = false;
                console.error("[IDUPI Error]", err.message);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "Endpoint no encontrado" }));
};

/**
 * Never rejects. http.createServer does not await its handler, so a throw
 * inside an async one becomes an unhandled rejection -- fatal in Node 24. The
 * process exits, taking every SSE subscriber and the persistent Pi subprocess
 * with it, and the request is never answered.
 *
 * That was reachable without any exotic input: two routes had no try/catch,
 * and resolveProject falls back to registeredProjects[0], which is `undefined`
 * once the last project is deleted -- so reading `proj.path` threw.
 *
 * Answering 500 is the fallback, not the goal: a route that can fail should
 * still handle its own failure and say something useful. This only guarantees
 * that failing to do so costs one request instead of the whole server.
 */
export async function guardedRequest(req, res) {
    try {
        await handleRequest(req, res);
    } catch (err) {
        try {
            console.error(`[IDUPI 500] ${req?.method} ${req?.url}`, err);
        } catch { /* logging must never be the thing that kills us */ }
        try {
            if (!res.headersSent) {
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err?.message || "Error interno" }));
            } else {
                res.end();
            }
        } catch { /* socket already gone: nothing left to say */ }
    }
}

const server = http.createServer(guardedRequest);

// Kill the capture helper when the server process exits so it never outlives us.
process.on("exit", shutdownScreen);


// TEST SEAM (behavior-preserving): skip binding the socket when IDUPI_NO_LISTEN=1
// so the test suite can import this module and exercise the sessions functions
// without starting the HTTP server. Production never sets this variable.
if (process.env.IDUPI_NO_LISTEN !== "1") {
    server.listen(PORT, "0.0.0.0", () => {
    console.log(`✅ IDUPI Server listo y escuchando en el Puerto: ${PORT}`);
    console.log("-------------------------------------------------");
    console.log("📱 Direcciones Host para tu Celular:");
    
    // 1. Tailscale IP (para conectar desde fuera de casa)
    let tailscaleIp = null;
    try {
        tailscaleIp = execSync("tailscale ip -4", { encoding: "utf8", timeout: 2000, maxBuffer: EXEC_MAX_BUFFER }).trim();
    } catch (e) {}
    if (tailscaleIp) {
        console.log(`   👉 Host (Fuera de casa / Tailscale): ${tailscaleIp}`);
        console.log(`      ⚠️ (Requiere activar la app Tailscale en tu celular)`);
    }

    // 2. Red Local WiFi (para conectar en casa)
    const nets = networkInterfaces();
    for (const name of Object.keys(nets)) {
        for (const net of nets[name]) {
            if (net.family === "IPv4" && !net.internal && !net.address.startsWith("169.254")) {
                if (net.address !== tailscaleIp) {
                    console.log(`   👉 Host (En casa / Mismo WiFi):      ${net.address}`);
                }
            }
        }
    }
    console.log(`   👉 Puerto: ${PORT}`);
    console.log("-------------------------------------------------");
    });
}
