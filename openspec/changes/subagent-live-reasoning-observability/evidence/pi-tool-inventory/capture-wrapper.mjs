#!/usr/bin/env node
/**
 * Pi RPC Capture Wrapper — empirical tool inventory for subagent-live-reasoning-observability
 * 
 * Spawns Pi CLI in RPC mode, captures all stdout/stderr with monotonic timestamps,
 * sends a prompt asking for tool inventory, and writes structured JSONL output.
 */
import { spawn } from "node:child_process";
import { writeFileSync, appendFileSync } from "node:fs";
import { join } from "node:path";
import { createHash } from "node:crypto";

const CAPTURE_DIR = process.argv[2] || "C:\\Users\\dev\\AppData\\Local\\Temp\\opencode\\pi-subagent-recapture";
const PI_CLI_JS = "C:\\Users\\dev\\AppData\\Roaming\\npm\\node_modules\\@earendil-works\\pi-coding-agent\\dist\\cli.js";
const REPO_CWD = "C:\\Users\\dev\\AndroidStudioProjects\\IDUPI";
const PROMPT_TIMEOUT_MS = 150_000; // 150s per turn
const PROCESS_TIMEOUT_MS = 180_000; // 180s total process lifetime

const stdoutLog = join(CAPTURE_DIR, "stdout.jsonl");
const stderrLog = join(CAPTURE_DIR, "stderr.jsonl");
const manifestPath = join(CAPTURE_DIR, "manifest.json");

const startTime = performance.now();
const stdoutLines = [];
const stderrLines = [];
let stdoutBytes = 0;
let stderrBytes = 0;

function t_ms() {
    return Math.round((performance.now() - startTime) * 1000) / 1000;
}

function sha256hex(data) {
    return createHash("sha256").update(data).digest("hex");
}

// Spawn Pi CLI RPC
const child = spawn(process.execPath, [PI_CLI_JS, "--mode", "rpc"], {
    cwd: REPO_CWD,
    env: process.env,
    shell: false,
    windowsHide: true,
    stdio: ["pipe", "pipe", "pipe"]
});

const pid = child.pid;
const argv = [process.execPath, PI_CLI_JS, "--mode", "rpc"];

console.log(`[capture] Pi CLI RPC spawned — PID ${pid}`);
console.log(`[capture] argv: ${JSON.stringify(argv)}`);
console.log(`[capture] cwd: ${REPO_CWD}`);

// Capture stdout
let stdoutBuffer = "";
child.stdout.setEncoding("utf8");
child.stdout.on("data", (chunk) => {
    stdoutBytes += Buffer.byteLength(chunk, "utf8");
    stdoutBuffer += chunk;
    let nlIdx = stdoutBuffer.indexOf("\n");
    while (nlIdx !== -1) {
        const line = stdoutBuffer.slice(0, nlIdx).trimEnd();
        stdoutBuffer = stdoutBuffer.slice(nlIdx + 1);
        if (line) {
            const entry = { line, t_ms: t_ms() };
            stdoutLines.push(entry);
            appendFileSync(stdoutLog, JSON.stringify(entry) + "\n");
            // Also print to parent stdout for live monitoring
            process.stdout.write(`[STDOUT ${entry.t_ms}] ${line.slice(0, 200)}${line.length > 200 ? "..." : ""}\n`);
        }
        nlIdx = stdoutBuffer.indexOf("\n");
    }
});

// Capture stderr
let stderrBuffer = "";
child.stderr.setEncoding("utf8");
child.stderr.on("data", (chunk) => {
    stderrBytes += Buffer.byteLength(chunk, "utf8");
    stderrBuffer += chunk;
    let nlIdx = stderrBuffer.indexOf("\n");
    while (nlIdx !== -1) {
        const line = stderrBuffer.slice(0, nlIdx).trimEnd();
        stderrBuffer = stderrBuffer.slice(nlIdx + 1);
        if (line) {
            const entry = { line, t_ms: t_ms() };
            stderrLines.push(entry);
            appendFileSync(stderrLog, JSON.stringify(entry) + "\n");
            process.stderr.write(`[STDERR ${entry.t_ms}] ${line.slice(0, 200)}\n`);
        }
        nlIdx = stderrBuffer.indexOf("\n");
    }
});

// Wait for Pi to be ready (look for first event or timeout)
function waitForReady(timeoutMs) {
    return new Promise((resolve) => {
        const start = performance.now();
        const check = setInterval(() => {
            if (stdoutLines.length > 0 || (performance.now() - start) > timeoutMs) {
                clearInterval(check);
                resolve();
            }
        }, 100);
    });
}

// Send a prompt and wait for agent_end
function sendPrompt(message, timeoutMs = PROMPT_TIMEOUT_MS) {
    return new Promise((resolve, reject) => {
        const promptId = `recapture-${Date.now()}`;
        const promptCmd = JSON.stringify({
            id: promptId,
            type: "prompt",
            message,
            streamingBehavior: "followUp"
        }) + "\n";

        console.log(`\n[capture] Sending prompt (${promptId}): "${message.slice(0, 100)}..."`);
        const beforeCount = stdoutLines.length;

        const timer = setTimeout(() => {
            reject(new Error(`Prompt timed out after ${timeoutMs}ms`));
        }, timeoutMs);

        child.stdin.write(promptCmd, (err) => {
            if (err) {
                clearTimeout(timer);
                reject(err);
            }
        });

        // Watch for agent_end or agent_settled
        const watcher = setInterval(() => {
            const recentLines = stdoutLines.slice(beforeCount);
            const hasEnd = recentLines.some(l => 
                l.line.includes('"type":"agent_end"') || 
                l.line.includes('"type":"agent_settled"')
            );
            if (hasEnd) {
                clearInterval(watcher);
                clearTimeout(timer);
                // Wait a bit more for any trailing events
                setTimeout(resolve, 500);
            }
        }, 200);
    });
}

// Graceful termination
function terminate() {
    console.log(`\n[capture] Terminating Pi CLI RPC (PID ${pid})...`);
    try {
        child.kill("SIGTERM");
    } catch {}
    setTimeout(() => {
        try {
            child.kill("SIGKILL");
        } catch {}
        writeManifest();
        process.exit(0);
    }, 2000);
}

function writeManifest() {
    const stdoutRaw = stdoutLines.map(e => e.line).join("\n") + (stdoutLines.length > 0 ? "\n" : "");
    const stderrRaw = stderrLines.map(e => e.line).join("\n") + (stderrLines.length > 0 ? "\n" : "");
    
    const manifest = {
        pid,
        argv,
        cwd: REPO_CWD,
        startTime: new Date().toISOString(),
        durationMs: Math.round(t_ms()),
        stdout: {
            lines: stdoutLines.length,
            bytes: stdoutBytes,
            sha256: sha256hex(stdoutRaw),
            trailingNewline: stdoutLines.length > 0
        },
        stderr: {
            lines: stderrLines.length,
            bytes: stderrBytes,
            sha256: sha256hex(stderrRaw),
            trailingNewline: stderrLines.length > 0
        },
        prompts: [],
        toolInventory: null,
        processExited: child.killed || child.exitCode !== null,
        exitCode: child.exitCode
    };

    writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
    console.log(`[capture] Manifest written to ${manifestPath}`);
    console.log(`[capture] Stdout: ${stdoutLines.length} lines, ${stdoutBytes} bytes, SHA-256: ${manifest.stdout.sha256}`);
    console.log(`[capture] Stderr: ${stderrLines.length} lines, ${stderrBytes} bytes`);
}

// Main flow
async function main() {
    console.log(`[capture] Waiting for Pi CLI to initialize...`);
    await waitForReady(10_000);
    console.log(`[capture] Got ${stdoutLines.length} initial events`);

    // Prompt 1: Ask Pi to list its available tools
    console.log(`\n${"=".repeat(60)}`);
    console.log(`[capture] PROMPT 1: Tool inventory request`);
    console.log(`${"=".repeat(60)}`);
    
    try {
        await sendPrompt(
            "CRITICAL: Do NOT call any tools. Do NOT read files. Do NOT search anything. " +
            "Simply return the COMPLETE list of tool names available to you in this session. " +
            "Return ONLY a JSON array of tool name strings, nothing else. " +
            "Example: [\"read\", \"grep\", \"bash\"]",
            60_000
        );
    } catch (e) {
        console.error(`[capture] Prompt 1 error: ${e.message}`);
    }

    // Brief pause between prompts
    await new Promise(r => setTimeout(r, 2000));

    // Check if subagent tool was found in the output
    const fullOutput = stdoutLines.map(e => e.line).join("\n");
    const hasSubagent = fullOutput.includes("subagent") || fullOutput.includes("Subagent");
    
    console.log(`\n[capture] Subagent tool detected in output: ${hasSubagent}`);

    if (hasSubagent) {
        // Prompt 2: Invoke subagent
        console.log(`\n${"=".repeat(60)}`);
        console.log(`[capture] PROMPT 2: Subagent invocation`);
        console.log(`${"=".repeat(60)}`);
        
        try {
            await sendPrompt(
                "Invoke the tool named 'subagent' exactly once with role 'researcher'. " +
                "The subagent must perform these 3 read-only operations itself (not you): " +
                "1. Read idupi-server/chat-events.mjs " +
                "2. Grep all lines containing 'SUBAGENT' in idupi-server/index.mjs " +
                "3. Read lines 1-50 of idupi-server/index.mjs " +
                "Then return a markdown table with columns: Operation | File | Finding | Count.",
                PROMPT_TIMEOUT_MS
            );
        } catch (e) {
            console.error(`[capture] Prompt 2 error: ${e.message}`);
        }
    } else {
        console.log(`[capture] Subagent tool NOT available — skipping invocation prompt`);
    }

    // Terminate
    terminate();
}

// Handle process exit
child.on("close", (code) => {
    console.log(`[capture] Pi CLI exited with code ${code}`);
    writeManifest();
});

child.on("error", (err) => {
    console.error(`[capture] Spawn error: ${err.message}`);
    writeManifest();
    process.exit(1);
});

// Process-level timeout
setTimeout(() => {
    console.error(`[capture] Process timeout — force killing`);
    terminate();
}, PROCESS_TIMEOUT_MS);

// Handle signals
process.on("SIGINT", terminate);
process.on("SIGTERM", terminate);

main().catch(err => {
    console.error(`[capture] Fatal: ${err.message}`);
    terminate();
});
