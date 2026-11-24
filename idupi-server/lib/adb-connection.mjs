// ADB connection manager (phone-health, T1).
//
// The health reader talks to the owner's phone over wireless ADB. Wireless
// debugging hands out a new TCP port on every session, so the manager keeps a
// small list of last-known targets in idupi-server/data/adb-targets.json
// (gitignored) and tries, in order: a serial `adb devices` already lists, an
// mDNS `_adb-tls-connect` service, the saved serials, and finally a bounded TCP
// probe of the saved hosts. The probe only scans addresses the owner already
// saved; it never sweeps a network it was not told about.
//
// Every process call uses execFile with a fixed argv (never a shell) and a
// timeout. The runner, filesystem helpers, PATH lookup, socket probe and clock
// are injected so tests never spawn adb, scrcpy or a real socket.

import { execFile } from "node:child_process";
import {
    existsSync,
    mkdirSync,
    readFileSync,
    readdirSync,
    renameSync,
    unlinkSync,
    writeFileSync,
} from "node:fs";
import net from "node:net";
import { homedir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";

export const STATE_FILE = "adb-targets.json";
export const MAX_TARGETS = 8;

export const PORT_SCAN_MIN = 30000;
export const PORT_SCAN_MAX = 49999;
export const PORT_SCAN_CONCURRENCY = 200;
export const PORT_SCAN_CONNECT_TIMEOUT_MS = 300;
export const PORT_SCAN_DEADLINE_MS = 45_000;

export const ADB_DEVICES_TIMEOUT_MS = 10_000;
export const ADB_MDNS_TIMEOUT_MS = 10_000;
export const ADB_CONNECT_TIMEOUT_MS = 8_000;
export const ADB_PAIR_TIMEOUT_MS = 30_000;
export const ADB_MODEL_TIMEOUT_MS = 5_000;

const HOST_PORT_PATTERN = /^(\[[0-9A-Fa-f:.]{2,45}\]|[A-Za-z0-9._-]{1,253}):([0-9]{1,5})$/;
const PAIRING_CODE_PATTERN = /^\d{6}$/;
const NETWORKS = new Set(["lan", "tailscale"]);

const execFileAsync = promisify(execFile);

function defaultRun(command, args, options = {}) {
    return execFileAsync(command, args, {
        windowsHide: true,
        encoding: "utf8",
        maxBuffer: 8 * 1024 * 1024,
        ...options,
    });
}

function isPlainObject(value) {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function textOf(value) {
    return typeof value === "string" ? value : "";
}

/**
 * Looks a bare command name up in PATH without spawning `where`/`which`.
 * Windows appends PATHEXT entries; other platforms use the name as-is.
 */
function makePathLookup(exists, env, platform) {
    return (name) => {
        if (!name) return null;
        if (platform !== "win32") {
            if (name.includes("/")) return exists(name) ? name : null;
        }
        const rawPath = env.PATH || env.Path || "";
        const separator = platform === "win32" ? ";" : ":";
        const extensions = platform === "win32"
            ? String(env.PATHEXT || ".COM;.EXE;.BAT;.CMD").split(";").filter(Boolean)
            : [""];
        for (const dir of rawPath.split(separator)) {
            if (!dir) continue;
            const plain = join(dir, name);
            if (exists(plain)) return plain;
            for (const ext of extensions) {
                const candidate = join(dir, `${name}${ext.toLowerCase()}`);
                if (exists(candidate)) return candidate;
                const upper = join(dir, `${name}${ext.toUpperCase()}`);
                if (exists(upper)) return upper;
            }
        }
        return null;
    };
}

/** Same lookup as firmware autodetect: env override, SDK default, then PATH. */
export function resolveAdbPath({ env = process.env, exists = existsSync, platform = process.platform } = {}) {
    if (textOf(env.IDUPI_ADB).trim()) {
        return { path: env.IDUPI_ADB.trim(), source: "env" };
    }
    const localAppData = textOf(env.LOCALAPPDATA).trim() ||
        join(homedir(), "AppData", "Local");
    const sdkPath = join(
        localAppData,
        "Android",
        "Sdk",
        "platform-tools",
        platform === "win32" ? "adb.exe" : "adb",
    );
    if (exists(sdkPath)) return { path: sdkPath, source: "sdk" };
    return { path: "adb", source: "path" };
}

/** Numeric compare of `5.0.1` style versions, used to pick the newest winget copy. */
function compareVersions(left, right) {
    const a = String(left).split(".").map((part) => Number(part) || 0);
    const b = String(right).split(".").map((part) => Number(part) || 0);
    const length = Math.max(a.length, b.length);
    for (let index = 0; index < length; index += 1) {
        const diff = (a[index] || 0) - (b[index] || 0);
        if (diff !== 0) return diff;
    }
    return 0;
}

/** Newest scrcpy.exe installed by winget, or null. Exported for tests. */
export function findWingetScrcpy({ env = process.env, exists = existsSync, listDir = readdirSync } = {}) {
    const localAppData = textOf(env.LOCALAPPDATA).trim() ||
        join(homedir(), "AppData", "Local");
    const packagesDir = join(localAppData, "Microsoft", "WinGet", "Packages");
    let packageEntries = [];
    try {
        packageEntries = listDir(packagesDir);
    } catch {
        return null;
    }
    let best = null;
    for (const packageName of packageEntries) {
        if (!String(packageName).startsWith("Genymobile.scrcpy_")) continue;
        let inner = [];
        try {
            inner = listDir(join(packagesDir, packageName));
        } catch {
            continue;
        }
        for (const entry of inner) {
            const match = /^scrcpy-win64-v(.+)$/.exec(String(entry));
            if (!match) continue;
            const candidate = join(packagesDir, packageName, entry, "scrcpy.exe");
            if (!exists(candidate)) continue;
            if (!best || compareVersions(match[1], best.version) > 0) {
                best = { path: candidate, version: match[1] };
            }
        }
    }
    return best ? best.path : null;
}

/** Resolves scrcpy: env, PATH, then the newest winget package. */
export function resolveScrcpyPath({
    env = process.env,
    exists = existsSync,
    listDir = readdirSync,
    which = makePathLookup(exists, env, process.platform),
    platform = process.platform,
} = {}) {
    if (textOf(env.IDUPI_SCRCPY).trim()) {
        return { path: env.IDUPI_SCRCPY.trim(), source: "env" };
    }
    const onPath = which(platform === "win32" ? "scrcpy.exe" : "scrcpy");
    if (onPath) return { path: onPath, source: "path" };
    const winget = findWingetScrcpy({ env, exists, listDir });
    if (winget) return { path: winget, source: "winget" };
    return { path: "scrcpy", source: "missing" };
}

/** `192.168.x`, `10.x`, `172.16-31.x` are home/office LAN; `100.64/10` is Tailscale. */
export function classifyNetwork(host) {
    const value = textOf(host).trim();
    if (!value) return "lan";
    const octets = value.split(".");
    if (octets.length === 4 && octets.every((part) => /^\d{1,3}$/.test(part))) {
        const [a, b] = octets.map(Number);
        if (a === 100 && b >= 64 && b <= 127) return "tailscale";
    }
    return "lan";
}

/** Strict `host:port` validation; the host half is never handed to a shell. */
export function validateHostPort(raw) {
    if (typeof raw !== "string") return null;
    const match = HOST_PORT_PATTERN.exec(raw.trim());
    if (!match) return null;
    const port = Number(match[2]);
    if (!Number.isInteger(port) || port < 1 || port > 65535) return null;
    const host = match[1];
    if (host.startsWith("[") && host.endsWith("]")) {
        return { host: host.slice(1, -1), port };
    }
    return { host, port };
}

export function validatePairingCode(raw) {
    return typeof raw === "string" && PAIRING_CODE_PATTERN.test(raw.trim());
}

/** Parses `adb devices` output into `{serial, state}` rows, header excluded. */
export function parseAdbDevices(output) {
    const rows = [];
    for (const rawLine of textOf(output).split(/\r?\n/)) {
        const line = rawLine.trim();
        if (!line || line.startsWith("List of devices")) continue;
        const parts = line.split(/\s+/);
        if (parts.length < 2) continue;
        const serial = parts[0];
        const state = parts[1];
        if (!serial || serial.startsWith("*")) continue;
        rows.push({ serial, state });
    }
    return rows;
}

/**
 * Parses `adb mdns services` output. Only `_adb-tls-connect` entries can be
 * connected to; `_adb-tls-pairing` needs the pairing code flow.
 */
export function parseMdnsServices(output) {
    const services = [];
    for (const rawLine of textOf(output).split(/\r?\n/)) {
        const line = rawLine.trim();
        if (!line || line.startsWith("List of")) continue;
        const parts = line.split(/\s+/);
        if (parts.length < 3) continue;
        const serviceType = parts[1];
        const address = parts[parts.length - 1];
        if (!serviceType.includes("_adb-tls-connect")) continue;
        const validated = validateHostPort(address);
        if (!validated) continue;
        services.push({
            name: parts[0],
            serial: `${validated.host}:${validated.port}`,
            host: validated.host,
            port: validated.port,
        });
    }
    return services;
}

/** True when an `adb connect` output line means the target is usable. */
export function connectAccepted(output) {
    const text = textOf(output).toLowerCase();
    if (!text) return false;
    if (text.includes("failed") || text.includes("cannot") || text.includes("unable")) {
        return false;
    }
    return text.includes("connected to") || text.includes("already connected");
}

/**
 * @param {{
 *   dataDir: string,
 *   env?: object,
 *   run?: (command: string, args: string[], options?: object) => Promise<{stdout?: string, stderr?: string}>,
 *   exists?: (path: string) => boolean,
 *   listDir?: (path: string) => string[],
 *   which?: (name: string) => string | null,
 *   probeTcp?: (host: string, port: number, timeoutMs: number) => Promise<boolean>,
 *   now?: () => Date,
 *   platform?: string,
 *   log?: { warn?: Function },
 * }} options
 */
export function createAdbConnection({
    dataDir,
    env = process.env,
    run = defaultRun,
    exists = existsSync,
    listDir = readdirSync,
    which = makePathLookup(exists, env, process.platform),
    probeTcp = defaultProbeTcp,
    now = () => new Date(),
    platform = process.platform,
    log,
} = {}) {
    if (typeof dataDir !== "string" || !dataDir) {
        throw new TypeError("createAdbConnection requires dataDir");
    }

    const stateFile = join(dataDir, STATE_FILE);
    let tempCounter = 0;
    let cachedSerial = null;
    let cachedPaths = null;
    let targets = loadTargets();

    // --- persistence -------------------------------------------------------

    function loadTargets() {
        let parsed;
        try {
            parsed = JSON.parse(readFileSync(stateFile, "utf8"));
        } catch (err) {
            if (err && err.code !== "ENOENT") {
                log?.warn?.(`[adb] cannot read ${STATE_FILE}: ${err.message}`);
            }
            return [];
        }
        const list = Array.isArray(parsed) ? parsed : parsed?.targets;
        if (!Array.isArray(list)) return [];
        const seen = new Set();
        const valid = [];
        for (const candidate of list) {
            const target = normalizeTarget(candidate);
            if (!target) continue;
            const key = target.serial.toLowerCase();
            if (seen.has(key)) continue;
            seen.add(key);
            valid.push(target);
            if (valid.length >= MAX_TARGETS) break;
        }
        return valid;
    }

    function atomicWrite(payload) {
        mkdirSync(dataDir, { recursive: true });
        tempCounter += 1;
        const tempPath = `${stateFile}.${process.pid}-${tempCounter}.tmp`;
        writeFileSync(tempPath, payload, "utf8");
        try {
            renameSync(tempPath, stateFile);
        } catch (err) {
            try {
                unlinkSync(tempPath);
            } catch {
                // The rename already failed; a missing temp file is fine.
            }
            throw err;
        }
    }

    function persist() {
        atomicWrite(JSON.stringify({ version: 1, targets }, null, 2) + "\n");
    }

    /** Saves or refreshes one target, newest first, capped at MAX_TARGETS. */
    function saveTarget({ serial, host, port, network, model }) {
        const validated = validateHostPort(serial);
        if (!validated) return null;
        const key = `${validated.host}:${validated.port}`.toLowerCase();
        const existing = targets.find((entry) => entry.serial.toLowerCase() === key);
        const target = {
            serial: `${validated.host}:${validated.port}`,
            host: validated.host,
            port: validated.port,
            network: NETWORKS.has(network)
                ? network
                : existing?.network || classifyNetwork(host || validated.host),
            model: textOf(model).trim() || existing?.model || null,
            lastConnectedAt: now().toISOString(),
        };
        targets = [target, ...targets.filter((entry) => entry.serial.toLowerCase() !== key)]
            .slice(0, MAX_TARGETS);
        try {
            persist();
        } catch (err) {
            log?.warn?.(`[adb] cannot write ${STATE_FILE}: ${err.message}`);
        }
        return target;
    }

    // --- adb helpers -------------------------------------------------------

    function paths() {
        if (!cachedPaths) {
            const adb = resolveAdbPath({ env, exists, platform });
            const scrcpy = resolveScrcpyPath({ env, exists, listDir, which, platform });
            cachedPaths = {
                adb: { ...adb, found: isFound(adb.path) },
                scrcpy: { ...scrcpy, found: isFound(scrcpy.path) },
            };
        }
        return { adb: cachedPaths.adb, scrcpy: cachedPaths.scrcpy };
    }

    function isFound(path) {
        if (!path) return false;
        if (path.includes("/") || path.includes("\\")) return exists(path);
        return Boolean(which(platform === "win32" ? `${path}.exe` : path));
    }

    async function runAdb(args, timeout, maxBuffer) {
        const { adb } = paths();
        return run(adb.path, args, {
            timeout,
            ...(maxBuffer ? { maxBuffer } : {}),
        });
    }

    async function runAdbText(args, timeout, maxBuffer) {
        try {
            const result = await runAdb(args, timeout, maxBuffer);
            return `${textOf(result?.stdout)}\n${textOf(result?.stderr)}`;
        } catch (err) {
            return `${textOf(err?.stdout)}\n${textOf(err?.stderr)}\n${textOf(err?.message)}`;
        }
    }

    async function listDevices() {
        const text = await runAdbText(["devices"], ADB_DEVICES_TIMEOUT_MS);
        return parseAdbDevices(text);
    }

    /** Best-effort model name; a failure never blocks the connection. */
    async function readModel(serial) {
        try {
            const result = await runAdb(
                ["-s", serial, "shell", "getprop", "ro.product.model"],
                ADB_MODEL_TIMEOUT_MS,
            );
            const model = textOf(result?.stdout).trim();
            return model || null;
        } catch {
            return null;
        }
    }

    async function connectSerial(hostPort, timeout = ADB_CONNECT_TIMEOUT_MS) {
        const text = await runAdbText(["connect", hostPort], timeout);
        return { accepted: connectAccepted(text), detail: text.trim() };
    }

    function remember(serial) {
        cachedSerial = serial;
    }

    function selectFromDevices(devices) {
        const ready = devices.filter((entry) => entry.state === "device");
        if (ready.length === 0) return null;
        if (cachedSerial) {
            const cached = ready.find(
                (entry) => entry.serial.toLowerCase() === cachedSerial.toLowerCase(),
            );
            if (cached) return { serial: cached.serial, via: "cached" };
        }
        const saved = new Set(targets.map((entry) => entry.serial.toLowerCase()));
        const known = ready.find((entry) => saved.has(entry.serial.toLowerCase()));
        if (known) return { serial: known.serial, via: "devices" };
        return null;
    }

    /**
     * Picks a working serial. Order: cached, devices, mDNS, saved serials, TCP
     * probe of saved hosts. Returns `{ok, serial, via}` or `{ok:false, reason}`.
     */
    async function ensureConnected() {
        const resolved = paths();
        if (!resolved.adb.found) return { ok: false, reason: "adb_missing" };

        const devices = await listDevices();
        const fromDevices = selectFromDevices(devices);
        if (fromDevices) {
            remember(fromDevices.serial);
            touchTarget(fromDevices.serial);
            return { ok: true, serial: fromDevices.serial, via: fromDevices.via };
        }

        // mDNS discovery: only `_adb-tls-connect` entries can be connected to.
        const mdns = parseMdnsServices(await runAdbText(["mdns", "services"], ADB_MDNS_TIMEOUT_MS));
        for (const service of mdns) {
            const result = await connectSerial(service.serial);
            if (!result.accepted) continue;
            const model = await readModel(service.serial);
            saveTarget({
                serial: service.serial,
                host: service.host,
                port: service.port,
                network: classifyNetwork(service.host),
                model,
            });
            remember(service.serial);
            return { ok: true, serial: service.serial, via: "mdns" };
        }

        // Last-known serials, newest first.
        for (const target of targets) {
            const result = await connectSerial(target.serial);
            if (!result.accepted) continue;
            const model = await readModel(target.serial);
            saveTarget({ ...target, model });
            remember(target.serial);
            return { ok: true, serial: target.serial, via: "last-known" };
        }

        // Bounded port probe over hosts the owner already saved.
        const probed = await probeSavedHosts();
        if (probed) {
            remember(probed.serial);
            return { ok: true, serial: probed.serial, via: "probe" };
        }

        if (targets.length === 0) return { ok: false, reason: "no_targets" };
        return { ok: false, reason: "unreachable" };
    }

    function touchTarget(serial) {
        const key = String(serial || "").toLowerCase();
        const existing = targets.find((entry) => entry.serial.toLowerCase() === key);
        if (!existing) return;
        existing.lastConnectedAt = now().toISOString();
        targets = [existing, ...targets.filter((entry) => entry !== existing)];
        try {
            persist();
        } catch {
            // A read-only data dir must not break a working connection.
        }
    }

    /**
     * Scans saved hosts for an open wireless-debugging port, then confirms it
     * with `adb connect`. Bounded by PORT_SCAN_CONCURRENCY, a 300 ms connect
     * timeout per socket and an overall deadline.
     */
    async function probeSavedHosts() {
        if (targets.length === 0) return null;
        const hosts = [...new Set(targets.map((entry) => entry.host))];
        const deadline = Date.now() + PORT_SCAN_DEADLINE_MS;

        for (const host of hosts) {
            let found = null;
            for (
                let start = PORT_SCAN_MIN;
                start <= PORT_SCAN_MAX && !found;
                start += PORT_SCAN_CONCURRENCY
            ) {
                if (Date.now() > deadline) return null;
                const end = Math.min(start + PORT_SCAN_CONCURRENCY - 1, PORT_SCAN_MAX);
                const ports = [];
                for (let port = start; port <= end; port += 1) ports.push(port);
                const results = await Promise.allSettled(
                    ports.map((port) => probeTcp(host, port, PORT_SCAN_CONNECT_TIMEOUT_MS)),
                );
                for (let index = 0; index < results.length; index += 1) {
                    const result = results[index];
                    if (result.status === "fulfilled" && result.value === true) {
                        found = ports[index];
                        break;
                    }
                }
            }
            if (found === null) continue;
            const hostPort = `${host}:${found}`;
            const connected = await connectSerial(hostPort);
            if (!connected.accepted) continue;
            const model = await readModel(hostPort);
            saveTarget({
                serial: hostPort,
                host,
                port: found,
                network: classifyNetwork(host),
                model,
            });
            return { serial: hostPort };
        }
        return null;
    }

    /** Pairing uses the 6-digit code the phone shows; it does not save a target. */
    async function pair({ hostPort, code } = {}) {
        const validated = validateHostPort(hostPort);
        if (!validated) return { ok: false, reason: "invalid_host_port" };
        if (!validatePairingCode(code)) return { ok: false, reason: "invalid_code" };
        const text = await runAdbText(
            ["pair", `${validated.host}:${validated.port}`, String(code).trim()],
            ADB_PAIR_TIMEOUT_MS,
        );
        if (!/successfully paired/i.test(text)) {
            return { ok: false, reason: "pair_failed", detail: text.trim() };
        }
        return {
            ok: true,
            host: validated.host,
            port: validated.port,
            detail: text.trim(),
        };
    }

    /** Connects to an explicit host:port and saves it when adb accepts it. */
    async function connect({ hostPort } = {}) {
        const validated = validateHostPort(hostPort);
        if (!validated) return { ok: false, reason: "invalid_host_port" };
        const serial = `${validated.host}:${validated.port}`;
        const result = await connectSerial(serial);
        if (!result.accepted) {
            return { ok: false, reason: "connect_failed", detail: result.detail };
        }
        const model = await readModel(serial);
        const target = saveTarget({
            serial,
            host: validated.host,
            port: validated.port,
            network: classifyNetwork(validated.host),
            model,
        });
        remember(serial);
        return { ok: true, serial, target };
    }

    async function scrcpyVersion(scrcpyPath) {
        try {
            const result = await run(scrcpyPath, ["--version"], { timeout: 5_000 });
            const text = `${textOf(result?.stdout)}\n${textOf(result?.stderr)}`;
            const match = /scrcpy\s+(\d+(?:\.\d+)+)/i.exec(text);
            return match ? match[1] : null;
        } catch {
            return null;
        }
    }

    /** Read-only snapshot for `GET /api/v1/health/adb` and the CLI. */
    async function status() {
        const resolved = paths();
        let connected = false;
        let serial = null;
        let via = null;
        if (resolved.adb.found) {
            const devices = await listDevices();
            const selected = selectFromDevices(devices);
            if (selected) {
                connected = true;
                serial = selected.serial;
                via = selected.via;
                remember(serial);
            }
        }
        const scrcpy = {
            path: resolved.scrcpy.path,
            found: resolved.scrcpy.found,
            version: resolved.scrcpy.found ? await scrcpyVersion(resolved.scrcpy.path) : null,
        };
        return {
            adb: { path: resolved.adb.path, found: resolved.adb.found },
            scrcpy,
            connected,
            serial,
            via,
            targets: targets.map((entry) => ({ ...entry })),
        };
    }

    function hasSavedTargets() {
        return targets.length > 0;
    }

    function listTargets() {
        return targets.map((entry) => ({ ...entry }));
    }

    return {
        ensureConnected,
        pair,
        connect,
        status,
        paths,
        hasSavedTargets,
        listTargets,
    };
}

function normalizeTarget(candidate) {
    if (!isPlainObject(candidate)) return null;
    const validated = validateHostPort(candidate.serial);
    if (!validated) return null;
    const network = NETWORKS.has(candidate.network)
        ? candidate.network
        : classifyNetwork(validated.host);
    const model = typeof candidate.model === "string" && candidate.model.trim()
        ? candidate.model.trim().slice(0, 120)
        : null;
    const connectedAt = typeof candidate.lastConnectedAt === "string" &&
        Number.isFinite(Date.parse(candidate.lastConnectedAt))
        ? new Date(candidate.lastConnectedAt).toISOString()
        : null;
    return {
        serial: `${validated.host}:${validated.port}`,
        host: validated.host,
        port: validated.port,
        network,
        model,
        lastConnectedAt: connectedAt,
    };
}

function defaultProbeTcp(host, port, timeoutMs) {
    return new Promise((resolve) => {
        const socket = new net.Socket();
        let settled = false;
        const finish = (open) => {
            if (settled) return;
            settled = true;
            socket.destroy();
            resolve(open);
        };
        socket.setTimeout(timeoutMs);
        socket.once("connect", () => finish(true));
        socket.once("timeout", () => finish(false));
        socket.once("error", () => finish(false));
        try {
            socket.connect(port, host);
        } catch {
            finish(false);
        }
    });
}
