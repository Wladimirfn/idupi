// Supervisor for the idupi-screen Go helper, mirroring the PiRpcManager
// lifecycle: persistent child, spawn-on-demand, reject pending work on crash
// and respawn on next use, refuse new work while shutting down.
//
// Protocol framing lives in screen-protocol.mjs; this module owns process
// lifecycle and request correlation only.

import { execFile, spawn } from "node:child_process";
import { access, constants } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const execFileP = promisify(execFile);

import {
  createFrameDecoder,
  encodeRequest,
  splitFrameBody,
} from "./screen-protocol.mjs";

const helperDir = join(
  fileURLToPath(new URL("../", import.meta.url)),
  "screen-helper",
);
const helperExe = join(helperDir, "idupi-screen.exe");

// Distribution decision (brief §9.1): compile on the user's machine at first
// use when the binary is missing, so no binary is committed to the repo.
let buildPromise = null;
export function ensureHelperBuilt() {
  if (!buildPromise) {
    buildPromise = (async () => {
      try {
        await access(helperExe, constants.X_OK);
      } catch {
        await execFileP(
          "go",
          ["build", "-ldflags=-s -w", "-o", helperExe, "."],
          {
            cwd: helperDir,
          },
        );
      }
      return helperExe;
    })();
  }
  return buildPromise;
}

export class ScreenHelper {
  constructor({
    command = helperExe,
    commandArgs = [],
    requestTimeoutMs = 5_000,
  } = {}) {
    this.command = command;
    this.commandArgs = commandArgs;
    this.requestTimeoutMs = requestTimeoutMs;
    this.child = null;
    this.stopped = false;
    this.nextId = 1;
    // id -> { resolve, reject, timer, wantFrame }
    this.pending = new Map();
    const decode = createFrameDecoder();
    this.decode = decode;
  }

  ensureStarted() {
    if (this.stopped) return false;
    if (this.child && this.child.exitCode === null) return true;

    const child = spawn(this.command, this.commandArgs, {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
    });
    this.child = child;

    child.stdout.on("data", (chunk) => {
      for (const message of this.decode(chunk)) this.onMessage(message);
    });
    child.stderr.on("data", (chunk) => {
      this.lastStderr = chunk.toString().slice(0, 2_000);
    });
    child.on("close", (code) => {
      // Reject everything in flight; the next ensureStarted() respawns.
      for (const entry of this.pending.values()) {
        clearTimeout(entry.timer);
        entry.reject(
          new Error(
            `helper crashed or exited (code=${code}) ${this.lastStderr ?? ""}`.trim(),
          ),
        );
      }
      this.pending.clear();
      if (this.child === child) this.child = null;
    });
    return true;
  }

  onMessage({ kind, body }) {
    let id = null;
    let entry = null;
    if (kind === "J") {
      let parsed;
      try {
        parsed = JSON.parse(body.toString());
      } catch (err) {
        throw new Error(`helper sent invalid control JSON: ${err.message}`);
      }
      id = parsed.id;
      entry = id == null ? null : this.pending.get(id);
      if (!entry) {
        // Untagged control message; nothing correlates — drop it.
        return;
      }
      clearTimeout(entry.timer);
      this.pending.delete(id);
      if (parsed.ok) entry.resolve(parsed);
      else entry.reject(new Error(parsed.error ?? "helper returned ok=false"));
      return;
    }
    // Frame bodies carry their own meta.id.
    const { meta, jpeg } = splitFrameBody(body);
    id = meta.id;
    entry = id == null ? null : this.pending.get(id);
    if (!entry) return;
    clearTimeout(entry.timer);
    this.pending.delete(id);
    entry.resolve({ meta, jpeg });
  }

  /** Send one raw command object; resolves with the matching response. */
  request(payload, { wantFrame = false } = {}) {
    if (!this.ensureStarted()) {
      return Promise.reject(new Error("screen helper is stopped"));
    }
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(
          new Error(`screen helper timeout after ${this.requestTimeoutMs}ms`),
        );
      }, this.requestTimeoutMs);
      this.pending.set(id, { resolve, reject, timer, wantFrame });
      try {
        this.child.stdin.write(encodeRequest({ ...payload, id }));
      } catch (err) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(err);
      }
    });
  }

  list() {
    return this.request({ cmd: "list" }).then((r) => r.monitors);
  }

  capture({ monitor, width, height, quality }) {
    return this.request(
      { cmd: "capture", monitor, width, height, quality },
      { wantFrame: true },
    ).then((r) => ({ meta: r.meta ?? r, jpeg: r.jpeg }));
  }

  stop() {
    this.stopped = true;
    for (const entry of this.pending.values()) {
      clearTimeout(entry.timer);
      entry.reject(new Error("screen helper is shutting down"));
    }
    this.pending.clear();
    return new Promise((resolve) => {
      if (!this.child || this.child.exitCode !== null) {
        this.child = null;
        resolve();
        return;
      }
      const child = this.child;
      child.once("close", () => resolve());
      child.kill();
    });
  }
}
