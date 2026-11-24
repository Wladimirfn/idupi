// Tests for the ADB connection manager (phone-health, T1). No real process is
// ever spawned: the runner, PATH lookup, filesystem probes and TCP probe are
// injected.
//
// Run (from repo root):
//   node --test idupi-server/test/adb-connection.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

import {
    classifyNetwork,
    connectAccepted,
    createAdbConnection,
    findWingetScrcpy,
    MAX_TARGETS,
    parseAdbDevices,
    parseMdnsServices,
    resolveAdbPath,
    resolveScrcpyPath,
    validateHostPort,
    validatePairingCode,
} from "../lib/adb-connection.mjs";

const ADB = "C:\\Android\\platform-tools\\adb.exe";
const SCRCPY = "C:\\tools\\scrcpy\\scrcpy.exe";
const NOW = new Date("2026-11-24T12:00:00.000Z");

async function makeDir(t) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-adb-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    return dir;
}

function target(overrides = {}) {
    return {
        serial: "192.168.1.50:37123",
        host: "192.168.1.50",
        port: 37123,
        network: "lan",
        model: null,
        lastConnectedAt: null,
        ...overrides,
    };
}

/** Fake adb runner keyed by the first argv entry (after `-s <serial>`). */
function fakeAdb({
    devices = "",
    mdns = "",
    connect = {},
    pairText = "Successfully paired to 192.168.1.50:37123",
    model = "SM-S928B",
} = {}) {
    const calls = [];
    async function run(command, args, options = {}) {
        calls.push({ command, args: [...args], options });
        const first = args[0] === "-s" ? args[2] : args[0];
        if (first === "devices") {
            return { stdout: `List of devices attached\n${devices}`, stderr: "" };
        }
        if (first === "mdns") {
            return { stdout: `List of discovered mdns services\n${mdns}`, stderr: "" };
        }
        if (first === "connect") {
            const address = args[1];
            const text = Object.prototype.hasOwnProperty.call(connect, address)
                ? connect[address]
                : `failed to connect to '${address}': Connection refused`;
            return { stdout: text, stderr: "" };
        }
        if (first === "pair") {
            return { stdout: pairText, stderr: "" };
        }
        if (first === "shell" && args[3] === "getprop") {
            return { stdout: `${model}\n`, stderr: "" };
        }
        return { stdout: "", stderr: "" };
    }
    return { run, calls };
}

function makeConnection({ dir, run, probeTcp, exists, targets = [], now = () => NOW } = {}) {
    const existsFn = exists || ((path) => path === ADB || path === SCRCPY);
    return createAdbConnection({
        dataDir: dir,
        env: { IDUPI_ADB: ADB, LOCALAPPDATA: "C:\\local", PATH: "" },
        run,
        exists: existsFn,
        listDir: () => [],
        which: () => null,
        probeTcp: probeTcp || (async () => false),
        now,
        platform: "win32",
        targets,
    });
}

// --- resolution ------------------------------------------------------------

test("resolveAdbPath prefers env, then the SDK path, then PATH", () => {
    assert.deepEqual(
        resolveAdbPath({ env: { IDUPI_ADB: "C:\\x\\adb.exe" }, exists: () => false }),
        { path: "C:\\x\\adb.exe", source: "env" },
    );
    assert.deepEqual(
        resolveAdbPath({
            env: { LOCALAPPDATA: "C:\\local" },
            exists: (path) => path.endsWith("adb.exe"),
            platform: "win32",
        }),
        {
            path: "C:\\local\\Android\\Sdk\\platform-tools\\adb.exe",
            source: "sdk",
        },
    );
    assert.deepEqual(
        resolveAdbPath({ env: { LOCALAPPDATA: "C:\\local" }, exists: () => false }),
        { path: "adb", source: "path" },
    );
});

test("resolveScrcpyPath prefers env, then PATH, then the newest winget copy", () => {
    assert.deepEqual(
        resolveScrcpyPath({ env: { IDUPI_SCRCPY: SCRCPY }, exists: () => false }),
        { path: SCRCPY, source: "env" },
    );
    assert.deepEqual(
        resolveScrcpyPath({
            env: {},
            exists: () => false,
            which: () => "C:\\bin\\scrcpy.exe",
        }),
        { path: "C:\\bin\\scrcpy.exe", source: "path" },
    );
    assert.deepEqual(
        resolveScrcpyPath({ env: {}, exists: () => false, which: () => null }),
        { path: "scrcpy", source: "missing" },
    );
});

test("findWingetScrcpy picks the highest scrcpy-win64 version", () => {
    const packages = "C:\\local\\Microsoft\\WinGet\\Packages";
    const exists = (path) =>
        path === join(packages, "Genymobile.scrcpy_pkg", "scrcpy-win64-v3.2", "scrcpy.exe") ||
        path === join(packages, "Genymobile.scrcpy_pkg", "scrcpy-win64-v5.0.1", "scrcpy.exe");
    const listDir = (path) => {
        if (path === packages) return ["Genymobile.scrcpy_pkg", "Other.App_pkg"];
        if (path === join(packages, "Genymobile.scrcpy_pkg")) {
            return ["scrcpy-win64-v3.2", "scrcpy-win64-v5.0.1"];
        }
        return [];
    };
    assert.equal(
        findWingetScrcpy({ env: { LOCALAPPDATA: "C:\\local" }, exists, listDir }),
        join(packages, "Genymobile.scrcpy_pkg", "scrcpy-win64-v5.0.1", "scrcpy.exe"),
    );
});

// --- discovery parsing -----------------------------------------------------

test("parseAdbDevices skips headers and keeps serial/state pairs", () => {
    assert.deepEqual(
        parseAdbDevices(
            "List of devices attached\n192.168.1.50:37123\tdevice\nemulator-5554\toffline\n\n",
        ),
        [
            { serial: "192.168.1.50:37123", state: "device" },
            { serial: "emulator-5554", state: "offline" },
        ],
    );
});

test("parseMdnsServices keeps only _adb-tls-connect entries with a valid host:port", () => {
    const output = [
        "List of discovered mdns services",
        "adb-abc-xyz\t_adb-tls-connect._tcp\t192.168.1.50:37123",
        "adb-pair-1\t_adb-tls-pairing._tcp\t192.168.1.50:41000",
        "garbage line",
    ].join("\n");
    assert.deepEqual(parseMdnsServices(output), [
        {
            name: "adb-abc-xyz",
            serial: "192.168.1.50:37123",
            host: "192.168.1.50",
            port: 37123,
        },
    ]);
});

test("validateHostPort and validatePairingCode reject anything shell-shaped", () => {
    assert.deepEqual(validateHostPort("192.168.1.50:37123"), {
        host: "192.168.1.50",
        port: 37123,
    });
    assert.deepEqual(validateHostPort("[::1]:5555"), { host: "::1", port: 5555 });
    for (const bad of ["adb devices", "host", ":1234", "host:0", "host:99999", "host:12;rm", null]) {
        assert.equal(validateHostPort(bad), null, String(bad));
    }
    assert.equal(validatePairingCode("123456"), true);
    assert.equal(validatePairingCode("12345"), false);
    assert.equal(validatePairingCode("1234567"), false);
    assert.equal(validatePairingCode("12345a"), false);
});

test("classifyNetwork marks the Tailscale CGNAT range", () => {
    assert.equal(classifyNetwork("100.101.102.103"), "tailscale");
    assert.equal(classifyNetwork("192.168.1.50"), "lan");
    assert.equal(classifyNetwork("10.0.0.5"), "lan");
    assert.equal(classifyNetwork("host.local"), "lan");
});

test("connectAccepted ignores failure text even when the exit code was zero", () => {
    assert.equal(connectAccepted("connected to 192.168.1.50:37123"), true);
    assert.equal(connectAccepted("already connected to 192.168.1.50:37123"), true);
    assert.equal(connectAccepted("failed to connect to 'x': refused"), false);
    assert.equal(connectAccepted("connected to x\nfailed to authenticate"), false);
    assert.equal(connectAccepted(""), false);
});

// --- ensureConnected order -------------------------------------------------

test("ensureConnected uses a saved serial that adb devices already lists", async (t) => {
    const dir = await makeDir(t);
    await writeFile(
        join(dir, "adb-targets.json"),
        JSON.stringify({ version: 1, targets: [target()] }),
        "utf8",
    );
    const { run, calls } = fakeAdb({ devices: "192.168.1.50:37123\tdevice\n" });
    const connection = makeConnection({ dir, run });

    const result = await connection.ensureConnected();
    assert.deepEqual(result, { ok: true, serial: "192.168.1.50:37123", via: "devices" });
    assert.ok(calls.some((call) => call.args[0] === "devices"));

    const cached = await connection.ensureConnected();
    assert.equal(cached.via, "cached");
});

test("ensureConnected falls back to mDNS and saves the discovered target", async (t) => {
    const dir = await makeDir(t);
    const { run } = fakeAdb({
        devices: "",
        mdns: "adb-1-2\t_adb-tls-connect._tcp\t192.168.1.90:41234",
        connect: { "192.168.1.90:41234": "connected to 192.168.1.90:41234" },
    });
    const connection = makeConnection({ dir, run });

    const result = await connection.ensureConnected();
    assert.deepEqual(result, { ok: true, serial: "192.168.1.90:41234", via: "mdns" });

    const stored = JSON.parse(await readFile(join(dir, "adb-targets.json"), "utf8"));
    assert.equal(stored.targets[0].serial, "192.168.1.90:41234");
    assert.equal(stored.targets[0].network, "lan");
    assert.equal(stored.targets[0].model, "SM-S928B");
});

test("ensureConnected tries saved serials before probing ports", async (t) => {
    const dir = await makeDir(t);
    await writeFile(
        join(dir, "adb-targets.json"),
        JSON.stringify({ version: 1, targets: [target()] }),
        "utf8",
    );
    const { run } = fakeAdb({
        devices: "",
        connect: { "192.168.1.50:37123": "connected to 192.168.1.50:37123" },
    });
    let probed = false;
    const connection = makeConnection({
        dir,
        run,
        probeTcp: async () => {
            probed = true;
            return false;
        },
    });

    const result = await connection.ensureConnected();
    assert.deepEqual(result, { ok: true, serial: "192.168.1.50:37123", via: "last-known" });
    assert.equal(probed, false, "the port probe must not run when the saved serial worked");
});

test("ensureConnected probes saved hosts when every other route fails", async (t) => {
    const dir = await makeDir(t);
    await writeFile(
        join(dir, "adb-targets.json"),
        JSON.stringify({
            version: 1,
            targets: [target({ serial: "192.168.1.50:39123", port: 39123 })],
        }),
        "utf8",
    );
    const { run } = fakeAdb({
        devices: "",
        connect: { "192.168.1.50:37123": "connected to 192.168.1.50:37123" },
    });
    const probes = [];
    const connection = makeConnection({
        dir,
        run,
        probeTcp: async (host, port) => {
            probes.push({ host, port });
            return port === 37123;
        },
    });

    const result = await connection.ensureConnected();
    assert.deepEqual(result, { ok: true, serial: "192.168.1.50:37123", via: "probe" });
    assert.ok(probes.length > 0);
    assert.ok(probes.every((entry) => entry.host === "192.168.1.50"));
});

test("ensureConnected reports adb_missing when the resolved adb does not exist", async (t) => {
    const dir = await makeDir(t);
    const { run } = fakeAdb();
    const connection = makeConnection({
        dir,
        run,
        exists: () => false,
    });
    assert.deepEqual(await connection.ensureConnected(), { ok: false, reason: "adb_missing" });
});

test("ensureConnected reports no_targets and unreachable without a saved target", async (t) => {
    const dir = await makeDir(t);
    const { run } = fakeAdb({ devices: "" });
    const connection = makeConnection({ dir, run });
    assert.deepEqual(await connection.ensureConnected(), { ok: false, reason: "no_targets" });
});

test("targets are deduped and capped at MAX_TARGETS", async (t) => {
    const dir = await makeDir(t);
    const { run } = fakeAdb({ devices: "" });
    const acceptingRun = async (command, args, options) => {
        if (args[0] === "connect") return { stdout: `connected to ${args[1]}`, stderr: "" };
        return run(command, args, options);
    };
    const connection = makeConnection({ dir, run: acceptingRun });

    for (let index = 0; index < MAX_TARGETS + 3; index += 1) {
        await connection.connect({ hostPort: `10.0.0.${index + 1}:4000${index}` });
    }
    const targets = connection.listTargets();
    assert.equal(targets.length, MAX_TARGETS);
});

// --- pair / connect --------------------------------------------------------

test("pair validates the code and host:port before touching adb", async (t) => {
    const dir = await makeDir(t);
    const { run, calls } = fakeAdb();
    const connection = makeConnection({ dir, run });

    assert.deepEqual(await connection.pair({ hostPort: "192.168.1.50:37123", code: "12ab" }), {
        ok: false,
        reason: "invalid_code",
    });
    assert.deepEqual(await connection.pair({ hostPort: "bad", code: "123456" }), {
        ok: false,
        reason: "invalid_host_port",
    });
    assert.equal(calls.length, 0);
});

test("pair reports success and never saves a target", async (t) => {
    const dir = await makeDir(t);
    const { run, calls } = fakeAdb({
        pairText: "Successfully paired to 192.168.1.50:37123",
    });
    const connection = makeConnection({ dir, run });

    const result = await connection.pair({ hostPort: "192.168.1.50:37123", code: "123456" });
    assert.equal(result.ok, true);
    const pairCall = calls.find((call) => call.args[0] === "pair");
    assert.deepEqual(pairCall.args, ["pair", "192.168.1.50:37123", "123456"]);
    assert.equal(connection.listTargets().length, 0);
});

test("connect saves the target on success and reports failures", async (t) => {
    const dir = await makeDir(t);
    const { run } = fakeAdb({
        connect: { "192.168.1.50:37123": "connected to 192.168.1.50:37123" },
    });
    const connection = makeConnection({ dir, run });

    const ok = await connection.connect({ hostPort: "192.168.1.50:37123" });
    assert.equal(ok.ok, true);
    assert.equal(ok.serial, "192.168.1.50:37123");
    assert.equal(connection.listTargets()[0].model, "SM-S928B");

    const failed = await connection.connect({ hostPort: "192.168.1.50:39999" });
    assert.equal(failed.ok, false);
    assert.equal(failed.reason, "connect_failed");
    assert.equal(connection.listTargets().length, 1);
});

test("status reports paths, version, targets and connection state", async (t) => {
    const dir = await makeDir(t);
    await writeFile(
        join(dir, "adb-targets.json"),
        JSON.stringify({ version: 1, targets: [target()] }),
        "utf8",
    );
    const { run } = fakeAdb({ devices: "192.168.1.50:37123\tdevice\n" });
    const connection = createAdbConnection({
        dataDir: dir,
        env: { IDUPI_ADB: ADB, IDUPI_SCRCPY: SCRCPY, PATH: "" },
        run: async (command, args, options) => {
            if (command === SCRCPY) return { stdout: "scrcpy 5.0.1 <https://github.com>", stderr: "" };
            return run(command, args, options);
        },
        exists: (path) => path === ADB || path === SCRCPY,
        listDir: () => [],
        which: () => null,
        probeTcp: async () => false,
        now: () => NOW,
        platform: "win32",
    });

    const status = await connection.status();
    assert.deepEqual(status.adb, { path: ADB, found: true });
    assert.deepEqual(status.scrcpy, { path: SCRCPY, found: true, version: "5.0.1" });
    assert.equal(status.connected, true);
    assert.equal(status.serial, "192.168.1.50:37123");
    assert.equal(status.targets.length, 1);
});
