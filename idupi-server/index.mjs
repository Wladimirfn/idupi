// ============================================================================
// IDUPI Dedicated Server Bridge (Servidor Independiente)
// Puerto por defecto: 8788
// Maneja: Pi CLI RPC, Exploración, Lectura, Proyectos, Sesiones, Modelos y Detección Real de Procesos/Terminales de la PC (Bun, Claude, Codex, Kimi, Node, Python, PowerShell, CMD)
// ============================================================================

import http from "node:http";
import { spawn, execSync, execFile } from "node:child_process";
import { readdirSync, statSync, readFileSync, writeFileSync, existsSync, rmSync, openSync, readSync, closeSync } from "node:fs";
import { homedir, networkInterfaces } from "node:os";
import { join, basename, relative, dirname } from "node:path";
import { randomUUID } from "node:crypto";
import { createAuthGuard, loadToken } from "../server-auth.mjs";
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

// Aliases cortos que Claude Code acepta en --model (verificado con `claude --help`:
// "Provide an alias for the latest model (e.g. 'fable', 'opus', or 'sonnet')...").
// Solo mapeamos los alias que tienen un ID completo autorizado en nuestro catálogo.
const CLAUDE_MODEL_ALIASES = {
    opus: "claude-opus-5",
    sonnet: "claude-sonnet-5",
    haiku: "claude-haiku-4-5"
};

// Estado global de la tarea activa en segundo plano
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
            const raw = execSync("opencode models", { encoding: "utf8", timeout: 5000 });
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

    // Pi CLI models (~/.pi/agent/models-store.json)
    const modelsStorePath = join(homedir(), ".pi", "agent", "models-store.json");
    let models = [];

    try {
        if (existsSync(modelsStorePath)) {
            const data = JSON.parse(readFileSync(modelsStorePath, "utf8"));
            for (const providerKey of Object.keys(data)) {
                const providerObj = data[providerKey];
                if (providerObj && Array.isArray(providerObj.models)) {
                    for (const m of providerObj.models) {
                        if (m && m.id) {
                            models.push({
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
                const rawCsv = execSync('wmic process get caption,commandline,processid /format:csv', { encoding: "utf8", timeout: 4000 });
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
                    execSync(`taskkill /F /PID ${pid}`, { timeout: 3000 });
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
                    execSync(`taskkill /F /PID ${pid}`, { timeout: 3000 });
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
                    reject(parseErr);
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

async function fetchOpenCodePage(normProjPath, subCursor, limit) {
    const escapedDir = escapeSqlValue(normProjPath);
    const validLimit = validateNumeric(limit, { integer: true, min: 1, max: 200 });
    const cursorClause = buildOpenCodeCursorClause(subCursor);
    const sql = `SELECT s.id, s.directory, s.title, s.time_updated, (SELECT COUNT(*) FROM message m WHERE m.session_id = s.id) as msg_count FROM session s WHERE REPLACE(LOWER(s.directory), '\\', '/') = '${escapedDir}'${cursorClause} ORDER BY s.time_updated DESC, s.id DESC LIMIT ${validLimit}`;
    const rows = await execOpenCodeDb(sql);
    return rows.map(r => ({ ts: r.time_updated, id: r.id, title: r.title, msgCount: r.msg_count }));
}

async function countOpenCodeSessions(normProjPath) {
    const escapedDir = escapeSqlValue(normProjPath);
    const sql = `SELECT COUNT(*) as cnt FROM session s WHERE REPLACE(LOWER(s.directory), '\\', '/') = '${escapedDir}'`;
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
async function fetchEnginePageResult(engine, normProjPath, subCursor, limit) {
    try {
        if (engine === "opencode") {
            const rows = await fetchOpenCodePage(normProjPath, subCursor, limit);
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
async function fetchSessionsPage({ engine, cursorParam, limit, projPath, projName, deps = {} }) {
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

        const result = await fetchEngine(engine, normProjPath, subCursor, limit);
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
            fetchEngine(name, normProjPath, cursor, limit).then((r) => {
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
    describeSubagentName
};

/**
 * Answers `GET /api/v1/sessions/counts` -- true per-engine counts (never
 * derived from a truncated listing), reading the same lightweight-index
 * cache / OpenCode SQL aggregate the listing uses. Failed engines' keys are
 * omitted from `counts`, never reported as `0` (design's `/counts` section).
 */
async function fetchSessionCounts(projPath) {
    const normProjPath = normalizePathForCompare(projPath);
    const failures = [];

    const results = await Promise.all(ENGINES.map(async (engine) => {
        try {
            const count = engine === "opencode"
                ? await countOpenCodeSessions(normProjPath)
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

function getSessionHistoryById(sessionId) {
    if (sessionId.startsWith("ses_")) {
        try {
            const raw = execSync(`opencode export ${sessionId}`, { encoding: "utf8", timeout: 8000 });
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
        }
    }

    const targetFilePath = findSessionFilePath(sessionId);
    if (!targetFilePath || !existsSync(targetFilePath)) return null;

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
        piRpc.setModel(detectedModel, detectedProvider);
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
        this.currentSessionPath = null;
        this.currentModelId = null;
        this.currentProvider = null;
        this.thinkingAnnounced = false;
        this.pendingTimeoutTimer = null;
    }

    setModel(modelId, provider = null) {
        const changed = (this.currentModelId !== modelId) || (provider && this.currentProvider !== provider);
        this.currentModelId = modelId;
        if (provider) this.currentProvider = provider;

        currentStatus.operatingAi = modelId;
        if (provider) currentStatus.operatingProvider = provider;

        if (changed && this.child && !this.child.killed) {
            console.log(`[IDUPI Pi RPC] Reiniciando subproceso Pi CLI para aplicar modelo único: ${provider ? provider + '/' : ''}${modelId}`);
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

    resumeSession(sessionPath) {
        if (this.currentSessionPath === sessionPath && this.child && !this.child.killed) {
            console.log(`[IDUPI Pi RPC] Sesión ya activa: ${sessionPath}`);
            return;
        }
        this.currentSessionPath = sessionPath;
        if (this.child && !this.child.killed) {
            console.log(`[IDUPI Pi RPC] Reanudando sesión específica: ${sessionPath}`);
            this.child.kill("SIGTERM");
            this.child = null;
        }
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
                const text = delta.delta || "";
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

            if (event.type === "message_end" && event.message?.role === "assistant") {
                const text = event.message.content?.find(c => c.type === "text")?.text;
                if (text && !this.currentOutput) {
                    this.currentOutput = text;
                    activeTask.output = text;
                }
            }

            if (event.type === "tool_execution_start") {
                const tName = event.toolName || "herramienta";
                const tId = event.toolCallId || event.id || String(Date.now());
                const tDetail = describeToolInput(event);
                console.log(`\n[IDUPI Tool] Ejecutando: ${tName}`);

                const isSubagent = tName.startsWith("sdd-") || tName.startsWith("review-") || 
                                   tName.startsWith("jd-") || tName === "invoke_subagent" || 
                                   tName === "delegate" || tName === "subagent" || 
                                   tName === "gentle-orchestrator" || tName === "build" || 
                                   tName === "explore" || tName === "plan";

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
                const isSubagent = tName.startsWith("sdd-") || tName.startsWith("review-") || 
                                   tName.startsWith("jd-") || tName === "invoke_subagent" || 
                                   tName === "delegate" || tName === "subagent" || 
                                   tName === "gentle-orchestrator" || tName === "build" || 
                                   tName === "explore" || tName === "plan";

                if (isSubagent) {
                    const piSummary = extractPiResultText(event.result);
                    if (!piSummary && event.result && typeof event.result === "object") {
                        // Unknown shape: say so in the log with the real keys, so the
                        // next live run identifies the field instead of us guessing.
                        console.warn(
                            `[IDUPI Subagente] Forma de 'result' no reconocida para '${tName}'. Claves: ${Object.keys(event.result).join(", ")}`,
                        );
                    }
                    publishChatEvent(CHAT_EVENTS.SUBAGENT_END, {
                        id: tId,
                        name: this._subagentName || describeSubagentName(tName, event.input || {}),
                        summary: piSummary ? piSummary.slice(0, 300) : "Subagente completó la tarea",
                        ok: event.isError !== true
                    });
                }

                publishChatEvent(CHAT_EVENTS.TOOL_END, {
                    id: tId,
                    name: tName,
                    ok: event.isError !== true
                });
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

            if (event.type === "agent_end" && this.pendingResolve) {
                // Terminalize any still-open Pi activity (Change A).
                if (this._currentActivityId) {
                    activityRegistry.terminalize(this._currentActivityId, { ok: true });
                    this._currentActivityId = null;
                    this._activityMcp = false;
                }
                console.log("\n[IDUPI Pi RPC] Respuesta completada.");
                clearTimeout(this.pendingTimeoutTimer);
                this.pendingTimeoutTimer = null;
                const resultText = this.currentOutput.trim() || "Respuesta procesada correctamente por Pi CLI.";

                activeTask.status = "completed";
                activeTask.output = resultText;

                this.thinkingAnnounced = false;
                publishChatEvent(CHAT_EVENTS.THINKING, { active: false });
                publishChatEvent(CHAT_EVENTS.MESSAGE_END, { text: resultText });

                const resolve = this.pendingResolve;
                this.pendingResolve = null;
                this.pendingReject = null;
                resolve(resultText);
            }

            noteUnmappedRpcEvent(event.type);
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

        return new Promise((resolve, reject) => {
            this.pendingResolve = resolve;
            this.pendingReject = reject;

            // Pi CLI is a persistent RPC subprocess, not spawned per-message,
            // so a stuck request never closes on its own the way a one-shot
            // CLI would. If no 'agent_end' arrives in time, force a restart
            // (see AGENT_CLI_TIMEOUT_MS) rather than leave the chat -- and
            // every future message, since sendPrompt() rejects new requests
            // while one is pending -- hung indefinitely.
            this.pendingTimeoutTimer = setTimeout(() => {
                if (!this.pendingResolve) return;
                const stuckPid = this.child?.pid;
                console.warn(`[IDUPI Pi RPC Timeout] Sin 'agent_end' tras ${AGENT_CLI_TIMEOUT_MS}ms, terminando el proceso Pi CLI (PID ${stuckPid}).`);
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
                const timeoutMsg = `⚠️ Pi CLI no respondió dentro de ${AGENT_CLI_TIMEOUT_MS / 1000}s y fue detenido.`;
                activeTask.output = timeoutMsg;
                publishChatEvent(CHAT_EVENTS.MESSAGE_END, { text: timeoutMsg });
                settleResolve(timeoutMsg);
            }, AGENT_CLI_TIMEOUT_MS);

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
 * What to call a delegation card. Every fan-out through pi-subagents arrives as
 * the same tool name, `subagent`, so labelling cards with it produces a column
 * of identical rows. The role the model chose (`scout`, `researcher`, ...) is
 * the part a reader can actually use.
 */
function describeSubagentName(toolName, input) {
    if (toolName !== "subagent" || !input || typeof input !== "object") return toolName;
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
    return toolName;
}

// Pi's RPC vocabulary is not documented here, and guessing the delegation event
// name would produce a feature that silently never fires. Instead we log each
// unmapped type once, so a real session tells us what to map.
const seenUnmappedEvents = new Set();
const MAPPED_RPC_EVENTS = new Set([
    "model_change", "active_model", "message_update", "message_end",
    "tool_execution_start", "tool_execution_end", "agent_end"
]);

function noteUnmappedRpcEvent(type) {
    if (!type || MAPPED_RPC_EVENTS.has(type) || seenUnmappedEvents.has(type)) return;
    seenUnmappedEvents.add(type);
    console.log(`[chat-stream] Evento RPC sin mapear: '${type}' — candidato para delegación/subagente`);
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

const server = http.createServer(async (req, res) => {
    // Every endpoint below can read files or spawn shells, so nothing is
    // reachable before the bearer token is verified.
    if (!requireAuth(req, res)) return;

    const rawUrl = req.url || "";
    const parsedUrl = new URL(rawUrl, `http://localhost:${PORT}`);
    const pathname = parsedUrl.pathname;

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
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(activeTask));
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
                projName: activeProj.name
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
            const result = await fetchSessionCounts(activeProj.path);
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
        const sessionData = getSessionHistoryById(sessionId);

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
                    if (sessionId.startsWith("ses_") || currentStatus.activeEngine === "opencode") {
                        activeOpenCodeSessionId = sessionId;
                        currentStatus.activeEngine = "opencode";
                        console.log(`[OpenCode Session Resume] Sesión reanudada: ${sessionId}`);
                        res.writeHead(200, { "Content-Type": "application/json" });
                        res.end(JSON.stringify({ status: "ok", sessionId, engine: "opencode" }));
                        return;
                    }
                    const sessionPath = findSessionFilePath(sessionId);
                    if (sessionPath && sessionPath.includes(".claude")) {
                        activeClaudeSessionId = sessionId;
                        currentStatus.activeEngine = "claude";
                        console.log(`[Claude Session Resume] Sesión reanudada: ${sessionId}`);
                        res.writeHead(200, { "Content-Type": "application/json" });
                        res.end(JSON.stringify({ status: "ok", sessionId, engine: "claude" }));
                        return;
                    }
                    if (sessionPath) {
                        currentStatus.activeEngine = "pi-cli";
                        piRpc.resumeSession(sessionPath);
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
        activeClaudeSessionId = null;
        activeOpenCodeSessionId = null;
        console.log("[Sessions] Nueva sesión solicitada. Estado de sesiones reseteado.");
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ status: "ok" }));
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

                currentStatus.operatingAi = modelName;
                if (providerName) currentStatus.operatingProvider = providerName;

                console.log(`[Model Switch] Aplicando modelo único: ${providerName || 'auto'}/${modelName}...`);

                if (currentStatus.activeEngine === "claude") {
                    // Se recuerda el modelo elegido para pasarlo como --model en cada
                    // invocación del CLI de Claude (ver rama "claude" de /api/v1/chat/send).
                    // Sin esto, el switch quedaba solo en el estado del servidor y nunca
                    // llegaba a afectar al proceso real de Claude Code.
                    activeClaudeModelId = modelName;
                    console.log(`[Model Switch] Claude CLI usará --model ${modelName} en el próximo mensaje.`);
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

                // Helper de seguridad para evitar borrar carpetas del sistema
                function isProtectedSystemPath(dirPath) {
                    if (!dirPath) return true;
                    const norm = dirPath.replace(/[/\\]+$/, "").toLowerCase();
                    const home = homedir().replace(/[/\\]+$/, "").toLowerCase();
                    // Raíces de discos (ej: C:, C:\, D:, /)
                    if (/^[a-z]:[/\\]?$/.test(norm) || norm === "" || norm === "/") return true;
                    // Directorios críticos de Windows / Linux
                    const forbidden = [
                        home,
                        "c:\\windows", "c:\\program files", "c:\\program files (x86)",
                        "c:\\programdata", "c:\\users", "/usr", "/etc", "/var", "/bin", "/sbin", "/home"
                    ];
                    return forbidden.some(f => norm === f || (norm.startsWith(f + "\\") && norm.split("\\").length <= 3));
                }

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
                        claudeAssignments: pData.claudeAssignments || pData.claude_phase_assignments || {}
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
            const statePath = join(homedir(), ".gentle-ai", "state.json");
            let stateData = {};
            if (existsSync(statePath)) {
                try {
                    stateData = JSON.parse(readFileSync(statePath, "utf8"));
                } catch (e) {}
            }

            let sddInfo = {
                changeName: null,
                applyState: "idle",
                nextRecommended: "sdd-new",
                taskProgress: { total: 0, completed: 0, pending: 0, allComplete: false },
                blockedReasons: []
            };

            try {
                const sddRaw = execSync("gentle-ai sdd-status", { cwd: resolveProject(activeProjectId).path, encoding: "utf8", timeout: 4000 });
                const jsonMatch = sddRaw.match(/```json\s*([\s\S]*?)\s*```/);
                if (jsonMatch && jsonMatch[1]) {
                    const parsedSdd = JSON.parse(jsonMatch[1]);
                    sddInfo = {
                        changeName: parsedSdd.changeName || null,
                        applyState: parsedSdd.applyState || "idle",
                        nextRecommended: parsedSdd.nextRecommended || "sdd-new",
                        taskProgress: parsedSdd.taskProgress || { total: 0, completed: 0, pending: 0, allComplete: false },
                        blockedReasons: parsedSdd.blockedReasons || []
                    };
                }
            } catch (sddErr) {}

            // Leer todos los agentes configurados en ~/.config/opencode/opencode.json
            const opencodeConfigPath = join(homedir(), ".config", "opencode", "opencode.json");
            let opencodeAgents = {};
            if (existsSync(opencodeConfigPath)) {
                try {
                    const op = JSON.parse(readFileSync(opencodeConfigPath, "utf8"));
                    if (op.agent) {
                        for (const [agentKey, agentVal] of Object.entries(op.agent)) {
                            const rawModel = agentVal.model || "";
                            const parts = rawModel.split("/");
                            const providerId = parts.length > 1 ? parts[0] : "opencode-go";
                            const modelId = parts.length > 1 ? parts.slice(1).join("/") : rawModel;
                            opencodeAgents[agentKey] = {
                                provider_id: providerId,
                                model_id: modelId,
                                effort: agentVal.variant || null
                            };
                        }
                    }
                } catch (e) {}
            }

            const combinedModelAssignments = { ...opencodeAgents, ...(stateData.model_assignments || {}) };
            const sddProfilesList = getCustomSddProfiles();

            const responsePayload = {
                persona: stateData.persona || "gentleman",
                preset: stateData.preset || "full-gentleman",
                installedAgents: stateData.installed_agents || ["opencode", "claude-code", "pi", "codex", "kiro-ide", "kimi"],
                components: stateData.components || ["engram", "sdd", "skills", "gga", "codegraph", "context7", "permissions"],
                rddMode: stateData.rdd_mode || "on",
                sddStatus: sddInfo,
                claudePhaseAssignments: stateData.claude_phase_assignments || {},
                modelAssignments: combinedModelAssignments,
                providers: getDetectedProviders(),
                sddProfiles: sddProfilesList,
                activeProfile: stateData.active_profile || null
            };

            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify(responsePayload));
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
            try {
                const parsed = JSON.parse(body || "{}");
                const profileId = parsed.profileId;

                if (!profileId) {
                    res.writeHead(400, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ error: "profileId es obligatorio" }));
                    return;
                }

                let targetModels = null;
                let targetClaude = null;

                const customPath = join(homedir(), ".config", "opencode", "profiles", `${profileId}.json`);
                if (existsSync(customPath)) {
                    const customData = JSON.parse(readFileSync(customPath, "utf8"));
                    targetModels = customData.modelAssignments || customData.model_assignments;
                    targetClaude = customData.claudeAssignments || customData.claude_phase_assignments;
                }

                if (!targetModels) {
                    res.writeHead(404, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ error: `Perfil '${profileId}' no encontrado` }));
                    return;
                }

                const statePath = join(homedir(), ".gentle-ai", "state.json");
                let stateData = {};
                if (existsSync(statePath)) {
                    stateData = JSON.parse(readFileSync(statePath, "utf8"));
                }

                stateData.installed_agents = ["opencode", "claude-code", "pi", "codex", "kiro-ide", "kimi"];
                stateData.model_assignments = targetModels;
                if (targetClaude) stateData.claude_phase_assignments = targetClaude;
                stateData.active_profile = profileId;

                writeFileSync(statePath, JSON.stringify(stateData, null, 2), "utf8");
                console.log(`[Gentle-AI Orchestrator] Perfil SDD '${profileId}' aplicado.`);

                // Actualizar también opencode.json
                try {
                    const opPath = join(homedir(), ".config", "opencode", "opencode.json");
                    if (existsSync(opPath)) {
                        const opData = JSON.parse(readFileSync(opPath, "utf8"));
                        if (!opData.agent) opData.agent = {};
                        for (const [rKey, rVal] of Object.entries(targetModels)) {
                            if (!opData.agent[rKey]) opData.agent[rKey] = {};
                            const fModel = rVal.provider_id ? `${rVal.provider_id}/${rVal.model_id}` : rVal.model_id;
                            opData.agent[rKey].model = fModel;
                            if (rVal.effort) opData.agent[rKey].variant = rVal.effort;
                            else delete opData.agent[rKey].variant;
                        }
                        writeFileSync(opPath, JSON.stringify(opData, null, 2), "utf8");
                    }
                } catch(e) {}

                // Sincronizar automáticamente en PC
                try {
                    execSync("gentle-ai sync", { encoding: "utf8", timeout: 10000 });
                    console.log(`[Gentle-AI Sync] Perfil sincronizado con todos los agentes.`);
                } catch (syncErr) {
                    console.warn(`[Gentle-AI Sync Warn]:`, syncErr.message);
                }

                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ status: "ok", profileId, activeProfile: profileId }));
            } catch (err) {
                console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
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
                    claudeAssignments: parsed.claudeAssignments || stateData.claude_phase_assignments || {}
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
            try {
                const parsed = JSON.parse(body || "{}");
                const { engine, phase, modelId, providerId, effort } = parsed;

                if (!engine || !phase || !modelId) {
                    res.writeHead(400, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ error: "engine, phase y modelId son obligatorios" }));
                    return;
                }

                const statePath = join(homedir(), ".gentle-ai", "state.json");
                let stateData = {};
                if (existsSync(statePath)) {
                    stateData = JSON.parse(readFileSync(statePath, "utf8"));
                }

                stateData.installed_agents = ["opencode", "claude-code", "pi", "codex", "kiro-ide", "kimi"];

                if (engine === "claude") {
                    if (!stateData.claude_phase_assignments) stateData.claude_phase_assignments = {};
                    stateData.claude_phase_assignments[phase] = { model: modelId };
                } else {
                    if (!stateData.model_assignments) stateData.model_assignments = {};
                    stateData.model_assignments[phase] = {
                        provider_id: providerId || "opencode-go",
                        model_id: modelId,
                        ...(effort ? { effort } : {})
                    };

                    // Actualizar también opencode.json directamente
                    try {
                        const opPath = join(homedir(), ".config", "opencode", "opencode.json");
                        if (existsSync(opPath)) {
                            const opData = JSON.parse(readFileSync(opPath, "utf8"));
                            if (!opData.agent) opData.agent = {};
                            if (!opData.agent[phase]) opData.agent[phase] = {};
                            const fullModel = providerId ? `${providerId}/${modelId}` : modelId;
                            opData.agent[phase].model = fullModel;
                            if (effort) opData.agent[phase].variant = effort;
                            else delete opData.agent[phase].variant;
                            writeFileSync(opPath, JSON.stringify(opData, null, 2), "utf8");
                            console.log(`[OpenCode Direct Update] Actualizado opencode.json para '${phase}' -> ${fullModel}`);
                        }
                    } catch(opErr) {
                        console.warn("[OpenCode Config Update Warn]:", opErr.message);
                    }
                }

                writeFileSync(statePath, JSON.stringify(stateData, null, 2), "utf8");
                console.log(`[Gentle-AI Orchestrator] Actualizado ${engine} [${phase}] -> ${modelId}`);

                // Propagar cambios a los archivos de configuración de cada agente vía sync
                try {
                    execSync("gentle-ai sync", { encoding: "utf8", timeout: 8000 });
                    console.log(`[Gentle-AI Sync] Sincronización automática de modelos completada.`);
                } catch (syncErr) {
                    console.warn(`[Gentle-AI Sync] Aviso en auto-sync:`, syncErr.message);
                }

                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ status: "ok", phase, modelId }));
            } catch (err) {
                console.error(`[IDUPI 500] ${req.method} ${pathname}`, err);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
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
                    output = execSync(cmd, { cwd: activeProj.path, encoding: "utf8", timeout: 15000 });
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
        
        const fullFilePath = join(proj.path, filePathParam);
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

// Ejecución Asíncrona en Streaming para Claude CLI (Soporta tareas largas, subagentes en vivo y no se corta)
function runClaudeCli(projPath, sessionId, isNewSession, modelId, message) {
    return new Promise((resolve, reject) => {
        publishChatEvent(CHAT_EVENTS.THINKING, { active: true });

        const modelArgs = modelId ? `--model ${JSON.stringify(modelId)} ` : "";
        const sessionArgs = sessionId
            ? (isNewSession ? `--session-id ${sessionId} ` : `-r ${sessionId} `)
            : "";
        const cmdLine = `claude ${modelArgs}${sessionArgs}--output-format stream-json --verbose --permission-mode bypassPermissions -p ${JSON.stringify(message)}`;

        console.log(`[Claude CLI Spawn] Iniciando Claude en ${projPath}: ${cmdLine}...`);

        let fullOutput = "";
        let buffer = "";
        let activeSubagentId = null;
        let activeSubagentName = null;
        let settled = false;
        let timedOut = false;

        const child = spawn(cmdLine, {
            cwd: projPath,
            shell: true,
            windowsHide: true,
            stdio: ["ignore", "pipe", "pipe"],
            env: process.env
        });

        // See AGENT_CLI_TIMEOUT_MS at module scope: `shell: true` makes
        // `child` the cmd.exe wrapper, so a tree-kill (not child.kill()) is
        // required to reach Claude CLI itself and any MCP servers it spawns.
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

                // 2. Subagentes y herramientas en vivo
                if (event.type === "assistant" && event.message?.content) {
                    const content = event.message.content;
                    if (Array.isArray(content)) {
                        for (const item of content) {
                            if (item.type === "text" && item.text) {
                                fullOutput = item.text;
                                activeTask.output = fullOutput;
                                publishChatEvent(CHAT_EVENTS.TEXT_DELTA, { text: item.text });
                            } else if (item.type === "tool_use") {
                                const toolName = item.name || "Tool";
                                const isSubagent = toolName.toLowerCase().includes("task") || 
                                                   toolName.toLowerCase().includes("agent") || 
                                                   toolName.toLowerCase().startsWith("sdd-") || 
                                                   toolName.toLowerCase().startsWith("jd-") || 
                                                   toolName.toLowerCase().startsWith("review-");
                                const toolId = item.id || `tool-${Date.now()}`;
                                if (isSubagent) {
                                    activeSubagentId = toolId;
                                    // The Task tool is always literally named "Task"; the agent
                                    // the user actually picked lives in subagent_type.
                                    activeSubagentName = item.input?.subagent_type || toolName;
                                    const taskDesc = item.input?.task || item.input?.prompt || item.input?.description || `Ejecutando subagente ${toolName}...`;
                                    publishChatEvent(CHAT_EVENTS.SUBAGENT_START, {
                                        id: toolId,
                                        name: activeSubagentName,
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
                                        message: describeToolInput(item) || `Ejecutando ${toolName}...`
                                    });
                                }
                            }
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
                                if (toolId === activeSubagentId) {
                                    // The subagent's real answer is in item.content; discarding
                                    // it left the card reading "Subagente completó la tarea"
                                    // no matter what the subagent actually reported. Pi and
                                    // OpenCode already surface their result the same way.
                                    const resultText = extractToolResultText(item.content).trim();
                                    publishChatEvent(CHAT_EVENTS.SUBAGENT_END, {
                                        id: toolId,
                                        name: activeSubagentName || "Subagent",
                                        summary: resultText ? resultText.slice(0, 300) : "Subagente completó la tarea",
                                        ok: !item.is_error
                                    });
                                    activeSubagentId = null;
                                    activeSubagentName = null;
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
                    const info = event.rate_limit_info;
                    const resetDate = info?.resetsAt ? new Date(info.resetsAt * 1000).toLocaleTimeString() : "próximamente";
                    const rateMsg = `⚠️ Límite de sesión de Claude alcanzado. Se restablece a las ${resetDate}.`;
                    fullOutput = rateMsg;
                    activeTask.output = fullOutput;
                    publishChatEvent(CHAT_EVENTS.TEXT_DELTA, { text: rateMsg });
                }

                // 5. Resultado final del CLI
                if (event.type === "result" && event.result) {
                    const resStr = typeof event.result === "string" ? event.result : JSON.stringify(event.result);
                    if (resStr.trim()) {
                        fullOutput = resStr.trim();
                        activeTask.output = fullOutput;
                    }
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
            if (activeSubagentId) {
                publishChatEvent(CHAT_EVENTS.SUBAGENT_END, {
                    id: activeSubagentId,
                    name: activeSubagentName || "Subagent",
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
            publishChatEvent(CHAT_EVENTS.MESSAGE_END, { text: cleanResult });
            resolve(cleanResult);
        });
    });
}

// Ejecución Asíncrona en Streaming para OpenCode

function runOpenCodeCli(projPath, sessionId, message) {
    return new Promise((resolve, reject) => {
        publishChatEvent(CHAT_EVENTS.THINKING, { active: true });

        const sessionArgs = sessionId ? `-s ${sessionId} ` : "";
        const cmdLine = `opencode run --format json --auto ${sessionArgs}${JSON.stringify(message)}`;

        console.log(`[OpenCode CLI Spawn] Iniciando OpenCode en ${projPath}: ${cmdLine}...`);

        let fullOutput = "";
        let buffer = "";
        let settled = false;
        let timedOut = false;

        const child = spawn(cmdLine, {
            cwd: projPath,
            shell: true,
            windowsHide: true,
            stdio: ["ignore", "pipe", "pipe"],
            env: process.env
        });

        // `shell: true` on Windows makes `child` the cmd.exe wrapper, not
        // `opencode` itself -- child.kill() only terminates that wrapper and
        // orphans opencode.exe plus any MCP servers it spawns (observed live:
        // a hung `npx @playwright/mcp` left running 9+ minutes after the
        // wrapper alone would have been killed). `taskkill /T` walks the real
        // process tree by PID instead, so every descendant actually dies.
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
                    activeTask.output = fullOutput;
                    publishChatEvent(CHAT_EVENTS.TEXT_DELTA, { text: event.part.text });
                } else if (event.type === "tool_use" && event.part) {
                    const toolName = event.part.tool || "Tool";
                    const input = event.part.state?.input || {};
                    const callId = event.part.callID || event.part.id || `tool-${Date.now()}`;
                    const isSubagent = input.role || 
                                       toolName.toLowerCase().includes("supervisor") ||
                                       toolName.toLowerCase().includes("agent") ||
                                       toolName.toLowerCase().startsWith("sdd-") ||
                                       toolName.toLowerCase().startsWith("jd-") ||
                                       toolName.toLowerCase().startsWith("review-");
                    if (isSubagent) {
                        const subagentName = input.role || toolName;
                        const taskDesc = input.question || input.task || input.prompt || `Consultando a subagente ${subagentName}...`;
                        publishChatEvent(CHAT_EVENTS.SUBAGENT_START, {
                            id: callId,
                            name: subagentName,
                            task: taskDesc
                        });
                        publishChatEvent(CHAT_EVENTS.SUBAGENT_END, {
                            id: callId,
                            name: subagentName,
                            summary: event.part.state?.output ? String(event.part.state.output).slice(0, 300) : "Subagente completó la tarea",
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
                            message: ocDetail
                        });
                        publishChatEvent(CHAT_EVENTS.TOOL_END, {
                            id: callId,
                            name: toolName,
                            ok: ocOk
                        });
                    }
                }
            } catch (e) {
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
            const cleanResult = fullOutput.trim() || (
                timedOut
                    ? `⚠️ OpenCode no respondió dentro de ${AGENT_CLI_TIMEOUT_MS / 1000}s y fue detenido.`
                    : (code === 0 ? "Completado por OpenCode." : `OpenCode finalizó con código ${code}`)
            );
            activeTask.output = cleanResult;
            publishChatEvent(CHAT_EVENTS.MESSAGE_END, { text: cleanResult });
            resolve(cleanResult);
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
                        agentOutput = await runOpenCodeCli(activeProj.path, activeOpenCodeSessionId, userMessage);
                        if (!activeOpenCodeSessionId) {
                            try {
                                const rawDb = execSync('opencode db "SELECT id FROM session ORDER BY time_updated DESC LIMIT 1" --format json', { encoding: "utf8" });
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

                    currentStatus.busy = false;
                    currentStatus.cliTask = "En espera de mensajes";

                    res.writeHead(200, { "Content-Type": "application/json" });
                    res.end(JSON.stringify({ status: "ok", taskId, output: agentOutput }));
                } catch (piErr) {
                    activeTask.status = "error";
                    activeTask.error = piErr.message;

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
});

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
        tailscaleIp = execSync("tailscale ip -4", { encoding: "utf8", timeout: 2000 }).trim();
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
