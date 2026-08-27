// Supervisor for the idupi-screen Go helper, mirroring the PiRpcManager
// lifecycle: persistent child, spawn-on-demand, reject pending work on crash
// and respawn on next use, refuse new work while shutting down.
//
// Protocol framing lives in screen-protocol.mjs; this module owns process
// lifecycle and request correlation only.

import { execFile, spawn } from "node:child_process";
import { readdirSync, statSync } from "node:fs";
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
// Rebuild ALSO when any .go source is newer than the exe: a stale binary
// built before a fix silently ships the old behaviour forever (the fullscreen
// keyboard bug lived in exactly such a stale exe).
let buildPromise = null;
export function ensureHelperBuilt() {
  if (!buildPromise) {
    buildPromise = (async () => {
      let stale = true; // missing/unreadable exe => build
      try {
        const exeStat = statSync(helperExe);
        stale = readdirSync(helperDir)
          .filter((f) => f.endsWith(".go"))
          .some((f) => {
            try {
              return statSync(join(helperDir, f)).mtimeMs > exeStat.mtimeMs;
            } catch {
              return false; // unreadable source: trust the exe
            }
          });
      } catch {
        // exe missing or unreadable: build it.
      }
      if (stale) {
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
  }

  ensureStarted() {
    if (this.stopped) return false;
    if (this.child && this.child.exitCode === null) return true;

    const child = spawn(this.command, this.commandArgs, {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
    });
    this.child = child;

    // A FRESH decoder per spawn: bytes buffered for a dead child's stream
    // are poison -- parsing a new session as their continuation produced
    // "frame length out of range" garbage after every respawn.
    const decode = createFrameDecoder();
    this.decode = decode;

    // A write into a pipe whose reader already died surfaces ASYNC as an
    // 'error' event on the socket; without listeners Node tears down the
    // WHOLE SERVER (production: write EPIPE mid-session while the user was
    // streaming). Pending requests get rejected by the close handler below
    // -- these only keep the process alive long enough to do that.
    child.stdin.on("error", () => {});
    child.stdout.on("error", () => {});
    child.stderr.on("error", () => {});

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
