import test from "node:test";
import assert from "node:assert/strict";
import { spawn, execFile } from "node:child_process";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

// Integration seam: boot the REAL server as a child process on an ephemeral
// port and drive it over HTTP. We deliberately do NOT import index.mjs here:
// its module scope leaves live timers (activity heartbeat, SSE hub) that would
// keep the test process alive forever. A child process dies clean on kill.
const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const { loadToken } = await import(
  join(repoRoot, "server-auth.mjs").replace(/\\/g, "/")
).catch(() => import("file://" + join(repoRoot, "server-auth.mjs")));

const TOKEN = loadToken();

function startServer(port) {
  const child = spawn(
    process.execPath,
    [join(repoRoot, "idupi-server", "index.mjs")],
    {
      env: { ...process.env, PORT: String(port) },
      stdio: "ignore",
      windowsHide: true,
    },
  );
  return child;
}

function killTree(pid) {
  if (process.platform === "win32") {
    execFile("taskkill", ["/F", "/T", "/PID", String(pid)], () => {});
  } else {
    try {
      process.kill(-pid, "SIGKILL");
    } catch {
      /* already gone */
    }
  }
}

async function waitForServer(port, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      // Any response (even 401) means the listener is up.
      const res = await fetch(`http://127.0.0.1:${port}/api/v1/status`);
      await res.arrayBuffer();
      return;
    } catch {
      await new Promise((r) => setTimeout(r, 300));
    }
  }
  throw new Error("server did not start in time");
}

test("screen routes reject requests without a bearer token", async () => {
  const port = 18_000 + Math.floor(Math.random() * 2_000);
  const child = startServer(port);
  try {
    await waitForServer(port);
    const cases = [
      ["GET", `/api/v1/screen/monitors`],
      ["GET", `/api/v1/screen/stream?sid=t&viewportW=800&viewportH=450`],
      ["POST", `/api/v1/screen/ack`],
    ];
    for (const [method, path] of cases) {
      const res = await fetch(`http://127.0.0.1:${port}${path}`, { method });
      assert.equal(res.status, 401, `${method} ${path} debe exigir token`);
      await res.arrayBuffer(); // drain
    }
  } finally {
    killTree(child.pid);
  }
}, 60_000);

test("screen routes still answer with a valid token", async () => {
  const port = 19_000 + Math.floor(Math.random() * 2_000);
  const child = startServer(port);
  try {
    await waitForServer(port);
    // /monitors exercises the full path: auth -> helper build -> list.
    const res = await fetch(`http://127.0.0.1:${port}/api/v1/screen/monitors`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
    });
    assert.equal(res.status, 200);
    const monitors = await res.json();
    assert.ok(Array.isArray(monitors));
    assert.ok(monitors.length >= 1);
  } finally {
    killTree(child.pid);
  }
}, 60_000);
