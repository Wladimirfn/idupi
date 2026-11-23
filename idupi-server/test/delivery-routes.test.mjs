// Tests for the APK delivery feature (apk-delivery, T1).
//
// Three layers, each with the smallest harness that can prove it:
//   - registry: real temp directories, real copies, real hashes;
//   - routes: a real http.Server on an ephemeral port, exactly as index.mjs
//     wires the handler (behind auth, which this harness stands in for);
//   - CLI: the real script spawned as a child process against that server.
//
// Run (from repo root):
//   node --test idupi-server/test/delivery-routes.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import http from "node:http";
import {
    mkdir,
    mkdtemp,
    readFile,
    rm,
    stat,
    unlink,
    writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { createDeliveryRegistry } from "../lib/delivery-registry.mjs";
import { createDeliveryRoutes } from "../lib/delivery-routes.mjs";

const SERVER_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const PUSH_SCRIPT = join(SERVER_ROOT, "bin", "idupi-push.mjs");
const silentLog = { log() {}, info() {}, warn() {}, error() {} };

async function makeTempDir(t) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-delivery-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    return dir;
}

async function writeApk(dir, name, bytes = "fake-apk-bytes") {
    const file = join(dir, name);
    await writeFile(file, bytes);
    return file;
}

// --- registry --------------------------------------------------------------

test("register copies the bytes and computes the sha256", async (t) => {
    const root = await makeTempDir(t);
    const sourceDir = await makeTempDir(t);
    const storeDir = join(root, "deliveries");
    const bytes = Buffer.from("PK\u0003\u0004 idupi apk payload");
    const source = await writeApk(sourceDir, "app-release.apk", bytes);
    const registry = createDeliveryRegistry({
        storeDir,
        log: silentLog,
        now: () => new Date("2026-11-23T10:00:00Z"),
    });

    const entry = await registry.register(source);

    assert.match(entry.id, /^dlv-[0-9a-f]{12}$/);
    assert.equal(entry.name, "app-release.apk");
    assert.equal(entry.size, bytes.length);
    assert.equal(entry.sha256, createHash("sha256").update(bytes).digest("hex"));
    assert.equal(entry.sourcePath, source);
    assert.equal(entry.createdAt, "2026-11-23T10:00:00.000Z");
    assert.deepEqual(await readFile(registry.filePath(entry.id)), bytes);

    // A later rebuild of the source must not change the snapshot.
    await writeFile(source, "rebuilt-and-different");
    assert.deepEqual(await readFile(registry.filePath(entry.id)), bytes);

    const index = JSON.parse(await readFile(join(storeDir, "index.json"), "utf8"));
    assert.equal(index.deliveries.length, 1);
    assert.equal(index.deliveries[0].id, entry.id);
});

test("register rejects relative, UNC, device, wrong-extension, directory, missing and oversize paths", async (t) => {
    const root = await makeTempDir(t);
    const sourceDir = await makeTempDir(t);
    const storeDir = join(root, "deliveries");
    const registry = createDeliveryRegistry({ storeDir, maxBytes: 8, log: silentLog });

    const textFile = await writeApk(sourceDir, "notes.txt", "hi");
    const tooBig = await writeApk(sourceDir, "big.apk", Buffer.alloc(64, 1));
    const directoryNamedApk = join(sourceDir, "folder.apk");
    await mkdir(directoryNamedApk);

    await assert.rejects(
        registry.register("relative.apk"),
        (err) => err.code === "INVALID_PATH",
    );
    await assert.rejects(
        registry.register("\\\\server\\share\\app.apk"),
        (err) => err.code === "INVALID_PATH",
    );
    await assert.rejects(
        registry.register("\\\\?\\C:\\temp\\app.apk"),
        (err) => err.code === "INVALID_PATH",
    );
    await assert.rejects(
        registry.register(textFile),
        (err) => err.code === "NOT_APK",
    );
    await assert.rejects(
        registry.register(directoryNamedApk),
        (err) => err.code === "NOT_FILE",
    );
    await assert.rejects(
        registry.register(join(sourceDir, "missing.apk")),
        (err) => err.code === "NOT_FOUND",
    );
    await assert.rejects(
        registry.register(tooBig),
        (err) => err.code === "TOO_LARGE",
    );
    assert.deepEqual(registry.list(), []);
});

test("an EEXIST id collision never deletes the file that already owns the name", async (t) => {
    const root = await makeTempDir(t);
    const sourceDir = await makeTempDir(t);
    const storeDir = join(root, "deliveries");
    await mkdir(storeDir, { recursive: true });
    const source = await writeApk(sourceDir, "app-release.apk");
    const squatter = join(storeDir, "dlv-aaaaaaaaaaaa.apk");
    await writeFile(squatter, "someone else's snapshot");

    const registry = createDeliveryRegistry({
        storeDir,
        newId: () => "dlv-aaaaaaaaaaaa",
        log: silentLog,
        now: () => new Date("2026-11-23T10:00:00Z"),
    });

    await assert.rejects(registry.register(source), (err) => err.code === "EEXIST");
    assert.equal(
        await readFile(squatter, "utf8"),
        "someone else's snapshot",
        "the retry path must not unlink a file this attempt did not create",
    );
    assert.deepEqual(registry.list(), []);
});

test("eviction beyond maxEntries deletes the oldest snapshot", async (t) => {
    const root = await makeTempDir(t);
    const sourceDir = await makeTempDir(t);
    const storeDir = join(root, "deliveries");
    let tick = 0;
    const registry = createDeliveryRegistry({
        storeDir,
        maxEntries: 2,
        log: silentLog,
        now: () => new Date(Date.UTC(2026, 10, 23, 10, 0, tick++)),
    });

    const first = await registry.register(await writeApk(sourceDir, "one.apk", "one"));
    const second = await registry.register(await writeApk(sourceDir, "two.apk", "two"));
    const third = await registry.register(await writeApk(sourceDir, "three.apk", "three"));

    assert.deepEqual(
        registry.list().map((entry) => entry.id),
        [third.id, second.id],
        "list is newest first and the oldest entry is gone",
    );
    await assert.rejects(
        stat(join(storeDir, `${first.id}.apk`)),
        (err) => err.code === "ENOENT",
    );
    const index = JSON.parse(await readFile(join(storeDir, "index.json"), "utf8"));
    assert.deepEqual(index.deliveries.map((entry) => entry.id), [second.id, third.id]);
});

test("index reload drops entries whose snapshot file is missing", async (t) => {
    const root = await makeTempDir(t);
    const sourceDir = await makeTempDir(t);
    const storeDir = join(root, "deliveries");
    let tick = 0;
    const registry = createDeliveryRegistry({
        storeDir,
        log: silentLog,
        now: () => new Date(Date.UTC(2026, 10, 23, 11, 0, tick++)),
    });

    const first = await registry.register(await writeApk(sourceDir, "one.apk", "one"));
    const second = await registry.register(await writeApk(sourceDir, "two.apk", "two"));
    await unlink(join(storeDir, `${first.id}.apk`));

    const reloaded = createDeliveryRegistry({ storeDir, log: silentLog });

    assert.deepEqual(reloaded.list().map((entry) => entry.id), [second.id]);
    const index = JSON.parse(await readFile(join(storeDir, "index.json"), "utf8"));
    assert.deepEqual(index.deliveries.map((entry) => entry.id), [second.id]);
});

// --- routes ----------------------------------------------------------------

async function startHarness(t) {
    const root = await makeTempDir(t);
    const storeDir = join(root, "deliveries");
    const registry = createDeliveryRegistry({ storeDir, log: silentLog });
    const handle = createDeliveryRoutes(registry);
    const server = http.createServer(async (req, res) => {
        try {
            const pathname = new URL(req.url, "http://localhost").pathname;
            if (await handle(req, res, pathname)) return;
            // Anything the handler does not own stands in for the rest of the
            // route chain: a distinct status proves it was not answered here.
            res.writeHead(418);
            res.end();
        } catch (err) {
            if (!res.headersSent) res.writeHead(500);
            res.end(String((err && err.message) || err));
        }
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const base = `http://127.0.0.1:${server.address().port}`;
    t.after(async () => {
        server.closeAllConnections?.();
        await new Promise((resolve) => server.close(resolve));
    });
    return { base, registry };
}

function postJson(base, path, body) {
    return fetch(base + path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: typeof body === "string" ? body : JSON.stringify(body),
    });
}

test("POST /api/v1/deliveries registers, then GET lists it", async (t) => {
    const { base } = await startHarness(t);
    const sourceDir = await makeTempDir(t);
    const bytes = Buffer.from("PK\u0003\u0004 route bytes");
    const source = await writeApk(sourceDir, "app-release.apk", bytes);

    let res = await postJson(base, "/api/v1/deliveries", { path: source });
    assert.equal(res.status, 201);
    const entry = await res.json();
    assert.match(entry.id, /^dlv-[0-9a-f]{12}$/);
    assert.equal(entry.name, "app-release.apk");
    assert.equal(entry.size, bytes.length);
    assert.equal(entry.sha256, createHash("sha256").update(bytes).digest("hex"));

    res = await fetch(base + "/api/v1/deliveries");
    assert.equal(res.status, 200);
    const list = await res.json();
    assert.equal(list.deliveries.length, 1);
    assert.equal(list.deliveries[0].id, entry.id);
});

test("POST /api/v1/deliveries maps invalid input to 400 and oversize bodies to 413", async (t) => {
    const { base } = await startHarness(t);
    const sourceDir = await makeTempDir(t);
    const textFile = await writeApk(sourceDir, "notes.txt", "hi");

    let res = await postJson(base, "/api/v1/deliveries", { path: "relative.apk" });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_PATH");

    res = await postJson(base, "/api/v1/deliveries", { path: "\\\\server\\share\\x.apk" });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_PATH");

    res = await postJson(base, "/api/v1/deliveries", { path: textFile });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "NOT_APK");

    res = await postJson(base, "/api/v1/deliveries", {});
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_PATH");

    res = await postJson(base, "/api/v1/deliveries", "{not json");
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "BAD_REQUEST");

    res = await postJson(
        base,
        "/api/v1/deliveries",
        JSON.stringify({ path: "C:\\" + "x".repeat(17 * 1024) + ".apk" }),
    );
    assert.equal(res.status, 413);
    assert.equal((await res.json()).code, "TOO_LARGE");
});

test("download streams the snapshot bytes with install headers; HEAD answers the same headers", async (t) => {
    const { base } = await startHarness(t);
    const sourceDir = await makeTempDir(t);
    const bytes = Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x00, 0xff, 0x10, 0x20]);
    const source = await writeApk(sourceDir, "app.apk", bytes);

    let res = await postJson(base, "/api/v1/deliveries", { path: source });
    const entry = await res.json();
    const url = `${base}/api/v1/deliveries/${entry.id}/download`;

    res = await fetch(url);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("content-type"), "application/vnd.android.package-archive");
    assert.equal(Number(res.headers.get("content-length")), bytes.length);
    assert.equal(res.headers.get("content-disposition"), 'attachment; filename="app.apk"');
    assert.equal(res.headers.get("cache-control"), "no-store");
    assert.equal(res.headers.get("x-content-sha256"), entry.sha256);
    assert.deepEqual(Buffer.from(await res.arrayBuffer()), bytes);

    res = await fetch(url, { method: "HEAD" });
    assert.equal(res.status, 200);
    assert.equal(Number(res.headers.get("content-length")), bytes.length);
    assert.equal(res.headers.get("x-content-sha256"), entry.sha256);
    assert.equal((await res.arrayBuffer()).byteLength, 0);
});

test("download answers 404 for a malformed id and for an unknown id", async (t) => {
    const { base } = await startHarness(t);

    let res = await fetch(`${base}/api/v1/deliveries/not-a-delivery-id/download`);
    assert.equal(res.status, 404);

    res = await fetch(`${base}/api/v1/deliveries/dlv-0123456789ab/download`);
    assert.equal(res.status, 404);

    res = await fetch(`${base}/api/v1/deliveries/dlv-0123456789ab`, { method: "DELETE" });
    assert.equal(res.status, 404);
});

test("DELETE /api/v1/deliveries/{id} removes once, then answers 404", async (t) => {
    const { base, registry } = await startHarness(t);
    const sourceDir = await makeTempDir(t);
    const source = await writeApk(sourceDir, "app.apk", "delete-me");

    let res = await postJson(base, "/api/v1/deliveries", { path: source });
    const entry = await res.json();

    res = await fetch(`${base}/api/v1/deliveries/${entry.id}`, { method: "DELETE" });
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { ok: true });
    assert.equal(registry.get(entry.id), null);

    res = await fetch(`${base}/api/v1/deliveries/${entry.id}`, { method: "DELETE" });
    assert.equal(res.status, 404);
    await res.arrayBuffer();
});

// --- CLI -------------------------------------------------------------------

function runCli(args, env) {
    return new Promise((resolve) => {
        const child = spawn(process.execPath, [PUSH_SCRIPT, ...args], {
            cwd: SERVER_ROOT,
            env: { ...process.env, ...env },
            windowsHide: true,
        });
        let stdout = "";
        let stderr = "";
        child.stdout.on("data", (chunk) => {
            stdout += chunk.toString("utf8");
        });
        child.stderr.on("data", (chunk) => {
            stderr += chunk.toString("utf8");
        });
        child.on("error", (err) => resolve({ code: -1, stdout, stderr: stderr + err.message }));
        child.on("close", (code) => resolve({ code, stdout, stderr }));
    });
}

test("idupi-push publishes an APK through the live routes and lists it", async (t) => {
    const { base, registry } = await startHarness(t);
    const sourceDir = await makeTempDir(t);
    const apk = await writeApk(sourceDir, "app-release.apk", Buffer.from("cli-apk-bytes"));
    const env = {
        IDUPI_PORT: new URL(base).port,
        IDUPI_TOKEN: "test-token",
    };

    const pushed = await runCli([apk], env);
    assert.equal(pushed.code, 0, pushed.stderr);
    assert.match(
        pushed.stdout,
        /APK enviada al teléfono: app-release\.apk \(0\.0 MB, id dlv-[0-9a-f]{12}\)/,
    );
    assert.equal(registry.list().length, 1);

    const listed = await runCli(["ls"], env);
    assert.equal(listed.code, 0, listed.stderr);
    assert.match(listed.stdout, /Entregas \(1\):/);
    assert.match(listed.stdout, /app-release\.apk/);

    // A relative path is resolved from the child's cwd, so the CLI must fail
    // with the server's message rather than a client-side guess.
    const rejected = await runCli(["does-not-exist.apk"], env);
    assert.equal(rejected.code, 1);
    assert.match(rejected.stderr, /No se pudo enviar:/);
    assert.doesNotMatch(rejected.stderr, /test-token/);
});
