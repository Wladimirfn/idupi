import test from "node:test";
import assert from "node:assert/strict";
import { spawn, execFile } from "node:child_process";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

// Remote input ships ON: the owner asked for a working remote the moment the
// server starts, and only an explicit IDUPI_REMOTE_INPUT=0 turns it off.
// These tests prove the inverted gate -- the flag ABSENT means enabled, and
// only the explicit 0 downgrades it to a 403 even for a valid bearer token.
const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const { loadToken } = await import("file://" + join(repoRoot, "server-auth.mjs"));

const TOKEN = loadToken();

function startServer(port, extraEnv = {}) {
  return spawn(process.execPath, [join(repoRoot, "idupi-server", "index.mjs")], {
    env: { ...process.env, PORT: String(port), ...extraEnv },
    stdio: "ignore",
    windowsHide: true,
  });
}

function killTree(pid) {
  if (process.platform === "win32") {
    execFile("taskkill", ["/F", "/T", "/PID", String(pid)], () => {});
  } else {
    try {
      process.kill(-pid, "SIGKILL");
    } catch {}
  }
}

async function waitForServer(port, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/api/v1/status`);
      await res.arrayBuffer();
      return;
    } catch {
      await new Promise((r) => setTimeout(r, 300));
    }
  }
  throw new Error("server did not start in time");
}

test("input is reported ENABLED while the flag is absent (default ON)", async () => {
  const port = 21_000 + Math.floor(Math.random() * 1_000);
  const child = startServer(port);
  try {
    await waitForServer(port);
    // Config only: never forward a live input here, this suite runs on the
    // owner's real desktop and an enabled forward would move their mouse.
    const res = await fetch(`http://127.0.0.1:${port}/api/v1/screen/config`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
    });
    assert.equal(res.status, 200);
    const config = await res.json();
    assert.equal(config.remoteInputEnabled, true);
  } finally {
    killTree(child.pid);
  }
}, 60_000);

test("the config endpoint reports disabled when the flag is explicitly 0", async () => {
  const port = 23_000;
  const child = startServer(port, { IDUPI_REMOTE_INPUT: "0" });
  try {
    await waitForServer(port);
    const res = await fetch(`http://127.0.0.1:${port}/api/v1/screen/config`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
    });
    assert.equal(res.status, 200);
    const config = await res.json();
    assert.equal(config.remoteInputEnabled, false);
  } finally {
    killTree(child.pid);
  }
}, 60_000);

test("a valid token gets 403 on input while the flag is explicitly 0", async () => {
  const port = 22_000 + Math.floor(Math.random() * 1_000);
  const child = startServer(port, { IDUPI_REMOTE_INPUT: "0" });
  try {
    await waitForServer(port);
    const res = await fetch(`http://127.0.0.1:${port}/api/v1/screen/input`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ type: "move", monitor: 0, x: 0.5, y: 0.5 }),
    });
    assert.equal(res.status, 403);
    await res.arrayBuffer();
  } finally {
    killTree(child.pid);
  }
}, 60_000);
