// ============================================================================
// IDUPI Backend Bridge Server para Pi CLI (Modo RPC Real)
// Escucha en el puerto 8787 y mantiene una sesión Pi RPC abierta en tu PC
// ============================================================================

import http from "node:http";
import { spawn } from "node:child_process";
import { homedir } from "node:os";
import { join } from "node:path";
import { createAuthGuard, loadToken } from "../server-auth.mjs";

const PORT = process.env.PORT || 8787;
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

console.log("=================================================");
console.log("🚀 Iniciando IDUPI Bridge Server (Modo RPC) en puerto " + PORT);
console.log("📍 Pi CLI ruta: " + PI_CLI_JS);
console.log("=================================================");

let currentStatus = {
    connected: true,
    pcName: process.env.COMPUTERNAME || "PC Principal",
    project: "IDUPI Main Project",
    agent: "Pi CLI RPC (Orquestador Activo)",
    busy: false,
    queueSize: 0,
    activeAgents: ["Pi CLI Developer"],
    cliTask: "En espera de mensajes desde la app IDUPI",
    operatingAi: "Pi AI Assistant (RPC)"
};

class PiRpcProcess {
    constructor() {
        this.child = null;
        this.buffer = "";
        this.pendingResolve = null;
        this.pendingReject = null;
        this.currentOutput = "";
    }

    ensureStarted() {
        if (this.child && !this.child.killed) return;

        console.log("[Pi RPC] Iniciando proceso Pi CLI en modo RPC (--mode rpc)...");
        this.child = spawn(process.execPath, [PI_CLI_JS, "--mode", "rpc"], {
            cwd: process.cwd(),
            env: process.env,
            shell: false,
            windowsHide: true
        });

        this.child.stdout.setEncoding("utf8");
        this.child.stderr.setEncoding("utf8");

        this.child.stdout.on("data", (chunk) => this.onStdout(chunk));
        this.child.stderr.on("data", (chunk) => console.error("[Pi RPC stderr]", chunk.trim()));

        this.child.on("close", (code) => {
            console.log(`[Pi RPC] Proceso cerrado con código ${code}`);
            this.child = null;
            if (this.pendingReject) {
                this.pendingReject(new Error(`Pi RPC se cerró con código ${code}`));
                this.pendingResolve = null;
                this.pendingReject = null;
            }
        });
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

            // Transmisión de texto incremental (deltas)
            const delta = event.assistantMessageEvent;
            if (event.type === "message_update" && delta?.type === "text_delta") {
                const text = delta.delta || "";
                this.currentOutput += text;
                process.stdout.write(text);
            }

            // Inicio de ejecución de herramienta
            if (event.type === "tool_execution_start") {
                console.log(`\n[Pi RPC Tool] Ejecutando: ${event.toolName}`);
            }

            // Fin del turno del agente
            if (event.type === "agent_end") {
                console.log("\n[Pi RPC] Respuesta completada.");
                if (this.pendingResolve) {
                    const resultText = this.currentOutput.trim() || "Respuesta procesada correctamente por Pi CLI.";
                    this.pendingResolve(resultText);
                    this.pendingResolve = null;
                    this.pendingReject = null;
                }
            }
        } catch (e) {
            // Ignorar líneas no JSON
        }
    }

    async sendPrompt(message) {
        this.ensureStarted();

        if (this.pendingResolve) {
            throw new Error("Ya hay una tarea de Pi CLI ejecutándose en tu PC.");
        }

        this.currentOutput = "";

        return new Promise((resolve, reject) => {
            this.pendingResolve = resolve;
            this.pendingReject = reject;

            const promptCmd = JSON.stringify({
                id: `idupi-${Date.now()}`,
                type: "prompt",
                message: message,
                streamingBehavior: "followUp"
            }) + "\n";

            console.log(`\n[IDUPI App -> Pi RPC] Enviando prompt: "${message}"`);
            this.child.stdin.write(promptCmd, (err) => {
                if (err) {
                    this.pendingResolve = null;
                    this.pendingReject = null;
                    reject(err);
                }
            });
        });
    }
}

const piRpc = new PiRpcProcess();

const server = http.createServer(async (req, res) => {
    // This server drives a Pi CLI process on the host, so nothing is reachable
    // before the bearer token is verified.
    if (!requireAuth(req, res)) return;

    const url = req.url || "";

    if (url === "/api/v1/status" && req.method === "GET") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(currentStatus));
        return;
    }

    if (url === "/api/v1/projects" && req.method === "GET") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify([
            { id: "idupi", name: "IDUPI Mobile App", path: process.cwd(), isActive: true, status: "Activo", lastActivity: "Ahora" }
        ]));
        return;
    }

    if (url === "/api/v1/alerts" && req.method === "GET") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify([]));
        return;
    }

    if (url === "/api/v1/chat/message" && req.method === "POST") {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", async () => {
            try {
                const parsed = JSON.parse(body);
                const userMessage = parsed.message || "";
                
                currentStatus.busy = true;
                currentStatus.cliTask = `Procesando en Pi CLI: "${userMessage}"`;

                const piOutput = await piRpc.sendPrompt(userMessage);

                currentStatus.busy = false;
                currentStatus.cliTask = "En espera de mensajes";

                res.writeHead(200, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ status: "ok", output: piOutput }));
            } catch (err) {
                currentStatus.busy = false;
                console.error("[IDUPI Error]", err.message);
                res.writeHead(500, { "Content-Type": "application/json" });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    res.writeHead(404);
    res.end("Not Found");
});

server.listen(PORT, "0.0.0.0", () => {
    console.log(`✅ IDUPI Bridge Server RPC listo y escuchando en http://0.0.0.0:${PORT}`);
    console.log(`📱 Pi CLI se iniciará automáticamente en modo RPC (--mode rpc) cuando envíes un mensaje.`);
});
