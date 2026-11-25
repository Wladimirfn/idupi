// Live PC metrics for the dashboard (app-redesign, T3/T6).
//
// Four sources, each optional and each able to answer "I do not know":
//   - node:os for CPU usage (delta between two samples) and RAM;
//   - nvidia-smi for GPU usage, temperature, power and VRAM;
//   - the elevated IDUPI sensors helper (scripts/sensors-helper.ps1), which
//     writes CPU temperature/power and DIMM temperature to a JSON file;
//   - LibreHardwareMonitor's built-in web server (data.json), the manual
//     alternative for the same values.
//
// Fixed disks (T8) are separate: one cached CIM query names them (labels and
// file system), and statfs measures each one.
//
// The owner's Ryzen 5600X reports no temperature or power through WMI
// (MSAcpi_ThermalZoneTemperature has 0 instances). Windows only exposes those
// sensors to an elevated process, hence the helper: it loads the pinned
// LibreHardwareMonitorLib (0.9.6, installed under %ProgramData%\IDUPI\lhm by
// scripts/install-sensors-helper.ps1, reading through the PawnIO driver) and
// refreshes a small JSON snapshot. A fresh helper file wins over the web server;
// a file older than 10 s is treated as absent (the helper stopped), and the web
// server is tried.
// A null here is the contract: the app renders "no disponible" instead of a
// made-up number.
//
// Everything is injected (execFile, fetch, readFile, now, os, sleep, env) so
// the test suite exercises the parsing and the delta maths without real
// hardware, and the server never needs a dependency for it (node built-ins
// only).

import * as nodeOs from "node:os";
import { execFile as nodeExecFile } from "node:child_process";
import {
    access as nodeAccess,
    readFile as nodeReadFile,
    statfs as nodeStatfs,
} from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";

const nodeExecFileAsync = promisify(nodeExecFile);

const NVIDIA_SMI = "nvidia-smi";
const NVIDIA_QUERY = [
    "--query-gpu=name,utilization.gpu,temperature.gpu,power.draw,memory.used,memory.total",
    "--format=csv,noheader,nounits",
];
const NVIDIA_TIMEOUT_MS = 3000;
const NVIDIA_MAX_BUFFER = 256 * 1024;

const DEFAULT_LHM_URL = "http://127.0.0.1:8085/data.json";
const LHM_TIMEOUT_MS = 800;

// The helper rewrites its snapshot every 2 s; anything older than this means
// the task stopped (or the file is a leftover from a previous boot).
const HELPER_MAX_AGE_MS = 10_000;
const HELPER_CLOCK_SKEW_MS = 1000;

const DEFAULT_CACHE_MS = 1500;
// First call has no previous CPU sample, so it measures over a short window
// instead of inventing a number: the request costs ~250 ms once, then every
// later sample compares against the previous one.
const FIRST_CPU_SAMPLE_MS = 250;

const BYTES_PER_MIB = 1024 * 1024;

// Fixed disks (T8). The CIM query is the only way to get a volume label, the
// file system and the "fixed" drive type; it costs one PowerShell start, so it
// is cached for a minute. Sizes come from statfs (cheap and exact), and when
// CIM is unavailable every letter that statfs can read is reported with a null
// label instead of hiding the card.
const CIM_TIMEOUT_MS = 8000;
const CIM_MAX_BUFFER = 256 * 1024;
const DISK_CIM_CACHE_MS = 60_000;
const DRIVE_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".split("");
const LOGICAL_DISK_SCRIPT =
    "Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' | Select-Object DeviceID,VolumeName,FileSystem,Size,FreeSpace | ConvertTo-Json";

const SENSORS_INSTALL_HINT =
    "Para temperatura y consumo del CPU, instala el servicio de sensores con permisos de administrador: idupi-server\\scripts\\install-sensors-helper.ps1 (alternativa: LibreHardwareMonitor con 'Remote Web Server' activado en el puerto 8085)";
const SENSORS_STALE_HINT =
    "El servicio de sensores no está corriendo: sensors.json tiene más de 10 segundos sin actualizarse. Revisa la tarea programada 'IDUPI Sensors' (se instala con idupi-server\\scripts\\install-sensors-helper.ps1; alternativa: LibreHardwareMonitor con 'Remote Web Server' en el puerto 8085)";
const SENSORS_NO_DRIVER_HINT =
    "El servicio de sensores corre, pero LibreHardwareMonitor no puede leer el CPU. Vuelve a ejecutar idupi-server\\scripts\\install-sensors-helper.ps1: instala el driver PawnIO y la versión fija de LibreHardwareMonitor que el helper necesita";
const GPU_HINT =
    "No se detectó nvidia-smi: no hay métricas de GPU disponibles en esta PC";

const SENSOR_GROUPS = new Set([
    "temperatures",
    "powers",
    "clocks",
    "load",
    "fans",
    "voltages",
    "currents",
    "miscellaneous",
    "data",
]);

function roundTo(value, digits) {
    const factor = 10 ** digits;
    return Math.round(value * factor) / factor;
}

/**
 * Parses the first decimal number in a sensor value ("45.3 °C", "65,2 W",
 * "1234 RPM"). Returns null for "[N/A]", "N/A" and anything without a number,
 * which is exactly how nvidia-smi reports an unsupported sensor.
 */
export function parseSensorNumber(value) {
    if (typeof value === "number") return Number.isFinite(value) ? value : null;
    if (typeof value !== "string") return null;
    const match = value.replace(",", ".").match(/-?\d+(?:\.\d+)?/);
    if (!match) return null;
    const parsed = Number(match[0]);
    return Number.isFinite(parsed) ? parsed : null;
}

/** Sensor readings that cannot be real when zero or negative become null. */
export function positiveOrNull(value) {
    return typeof value === "number" && value > 0 ? value : null;
}

/**
 * `Get-CimInstance ... | ConvertTo-Json` -> fixed-disk rows. One matching
 * drive serializes as an object and several serialize as an array, so both
 * shapes parse. Rows without a "C:" style DeviceID are dropped instead of
 * turning into a phantom disk.
 */
export function parseLogicalDisks(stdout) {
    const text = String(stdout ?? "").trim();
    if (!text) return [];
    let data;
    try {
        data = JSON.parse(text);
    } catch {
        return [];
    }
    const rows = Array.isArray(data) ? data : [data];
    const disks = [];
    for (const row of rows) {
        if (!row || typeof row !== "object") continue;
        const deviceId = String(row.DeviceID ?? "").trim();
        if (!/^[A-Za-z]:$/.test(deviceId)) continue;
        const label = typeof row.VolumeName === "string" ? row.VolumeName.trim() : "";
        const fileSystem = typeof row.FileSystem === "string" ? row.FileSystem.trim() : "";
        const sizeBytes = Number(row.Size);
        const freeBytes = Number(row.FreeSpace);
        disks.push({
            mount: deviceId.toUpperCase(),
            label: label || null,
            fileSystem: fileSystem || null,
            sizeBytes: Number.isFinite(sizeBytes) && sizeBytes > 0 ? sizeBytes : null,
            freeBytes: Number.isFinite(freeBytes) && freeBytes >= 0 ? freeBytes : null,
        });
    }
    return disks;
}

/**
 * `fs.promises.statfs` (GetDiskFreeSpaceEx on Windows) -> wire bytes. bavail is
 * the space the caller can actually use, which is what "free" means for the
 * storage card; used is the complement of total.
 */
export function statfsBytes(stats) {
    const blockSize = Number(stats?.bsize);
    const blocks = Number(stats?.blocks);
    const available = Number(stats?.bavail);
    if (!(blockSize > 0) || !(blocks > 0) || !(available >= 0)) return null;
    const totalBytes = Math.round(blockSize * blocks);
    const freeBytes = Math.round(blockSize * available);
    if (!(totalBytes > 0)) return null;
    const usedBytes = Math.max(0, totalBytes - freeBytes);
    return {
        totalBytes,
        freeBytes,
        usedBytes,
        usagePercent: roundTo((usedBytes / totalBytes) * 100, 1),
    };
}

/**
 * nvidia-smi CSV row -> GPU metric. MiB from the tool become bytes on the
 * wire; "[N/A]" stays null instead of becoming 0.
 */
export function parseNvidiaSmi(stdout) {
    if (typeof stdout !== "string") return [];
    const gpus = [];
    for (const rawLine of stdout.split(/\r?\n/)) {
        const line = rawLine.trim();
        if (!line) continue;
        const cells = line.split(",").map((cell) => cell.trim());
        if (cells.length < 6) continue;
        const [name, usage, temperature, power, used, total] = cells;
        const usedMib = parseSensorNumber(used);
        const totalMib = parseSensorNumber(total);
        gpus.push({
            name: name || null,
            usagePercent: parseSensorNumber(usage),
            temperatureC: parseSensorNumber(temperature),
            powerW: parseSensorNumber(power),
            memoryUsedBytes: usedMib === null ? null : Math.round(usedMib * BYTES_PER_MIB),
            memoryTotalBytes: totalMib === null ? null : Math.round(totalMib * BYTES_PER_MIB),
        });
    }
    return gpus;
}

function collectNodes(node, out = []) {
    if (!node || typeof node !== "object") return out;
    out.push(node);
    const children = Array.isArray(node.Children) ? node.Children : [];
    for (const child of children) collectNodes(child, out);
    return out;
}

/**
 * Depth-first walk that records each node's depth. `collectNodes` stays the
 * flat list the parsers use; this one is what lets "prefer the deepest match"
 * resolve a score tie without a second traversal.
 */
function collectNodesWithDepth(node, depth = 0, out = []) {
    if (!node || typeof node !== "object") return out;
    out.push({ node, depth });
    const children = Array.isArray(node.Children) ? node.Children : [];
    for (const child of children) collectNodesWithDepth(child, depth + 1, out);
    return out;
}

function nodeText(node) {
    return String(node?.Text ?? "").trim();
}

function isTemperatureNode(node) {
    const type = String(node?.Type ?? "").toLowerCase();
    if (type) return type === "temperature";
    const value = String(node?.Value ?? "");
    const sensorId = String(node?.SensorId ?? "").toLowerCase();
    return value.includes("°C") || sensorId.includes("/temperature/");
}

function isPowerNode(node) {
    const type = String(node?.Type ?? "").toLowerCase();
    if (type) return type === "power";
    const value = String(node?.Value ?? "");
    const sensorId = String(node?.SensorId ?? "").toLowerCase();
    return (value.includes("W") && !value.includes("°C")) || sensorId.includes("/power/");
}

/**
 * Finds the hardware node that owns the CPU sensors. LHM nests groups under
 * the device name ("Temperatures", "Powers", ...), so the name test skips the
 * group containers and prefers a device-looking node; the fallback covers
 * boards whose device name is not AMD/Intel branded.
 *
 * The root ("Sensor") and the computer node contain the whole tree, so their
 * descendants always mention Tctl/Package and they would win the fallback
 * score on an unbranded device name ("AMD Athlon 3000G"). Nodes that merely
 * contain another candidate are dropped, and equal scores resolve to the
 * deeper node: the CPU device wins, never its containers.
 */
export function findCpuNode(root) {
    const groups = new Set(Array.from(SENSOR_GROUPS, (name) => name.toLowerCase()));
    const candidates = [];
    for (const { node, depth } of collectNodesWithDepth(root)) {
        const children = Array.isArray(node.Children) ? node.Children : [];
        if (children.length === 0) continue;
        const text = nodeText(node);
        if (groups.has(text.toLowerCase())) continue;

        let score = 0;
        if (/ryzen|threadripper|intel|core\s*i\d|xeon/i.test(text)) score = 3;
        else if (/^cpu\b/i.test(text)) score = 2;
        else if (collectNodes(node).some((entry) => /tctl|tdie|cpu package/i.test(nodeText(entry)))) {
            score = 1;
        }
        if (score === 0) continue;
        candidates.push({ node, depth, score });
    }

    const leaves = candidates.filter(
        (candidate) =>
            !candidates.some(
                (other) =>
                    other !== candidate &&
                    collectNodes(candidate.node).includes(other.node),
            ),
    );

    let best = null;
    for (const candidate of leaves) {
        if (
            !best ||
            candidate.score > best.score ||
            (candidate.score === best.score && candidate.depth > best.depth)
        ) {
            best = candidate;
        }
    }
    return best ? best.node : null;
}

function pickCpuTemperature(nodes) {
    const candidates = nodes
        .filter(isTemperatureNode)
        .map((node, index) => ({ node, index, value: parseSensorNumber(node.Value) }))
        .filter((entry) => entry.value !== null);
    if (candidates.length === 0) return null;

    const priority = (text) => {
        if (/tctl|tdie/i.test(text)) return 0;
        if (/^cpu package$/i.test(text)) return 1;
        if (/core/i.test(text)) return 2;
        return 3;
    };
    candidates.sort(
        (a, b) => priority(nodeText(a.node)) - priority(nodeText(b.node)) || a.index - b.index,
    );
    return candidates[0].value;
}

function pickCpuPower(nodes) {
    const candidates = nodes
        .filter(isPowerNode)
        .map((node, index) => ({ node, index, value: parseSensorNumber(node.Value) }))
        .filter((entry) => entry.value !== null);
    if (candidates.length === 0) return null;

    const packageEntry = candidates.find((entry) => /^(cpu )?package$/i.test(nodeText(entry.node)));
    return (packageEntry ?? candidates[0]).value;
}

/**
 * Walks an LHM data.json tree and pulls the three values the dashboard needs.
 * Missing sensors stay null: an Intel board with no DIMM temperature, or a
 * laptop whose only CPU sensor is "CPU Package", must not fabricate the rest.
 */
export function parseLhmSensors(root) {
    const nodes = collectNodes(root);
    const cpuNode = findCpuNode(root);
    const cpuNodes = cpuNode ? collectNodes(cpuNode) : [];
    const memoryTemperature = nodes
        .filter((node) => isTemperatureNode(node) && /dimm|smbus|memory/i.test(nodeText(node)))
        .map((node) => ({ node, value: parseSensorNumber(node.Value) }))
        .find((entry) => entry.value !== null);

    // LHM reports 0 for a sensor it cannot read (no kernel driver), and 0 °C /
    // 0 W is never a real reading of a running CPU.
    return {
        cpuTemperatureC: cpuNodes.length > 0
            ? positiveOrNull(pickCpuTemperature(cpuNodes))
            : null,
        cpuPowerW: cpuNodes.length > 0 ? positiveOrNull(pickCpuPower(cpuNodes)) : null,
        memoryTemperatureC: memoryTemperature
            ? positiveOrNull(memoryTemperature.value)
            : null,
    };
}

/** Per-CPU times snapshot; [cpus] is `os.cpus()`. */
export function snapshotCpuTimes(cpus) {
    if (!Array.isArray(cpus)) return [];
    return cpus.map((cpu) => {
        const times = cpu?.times ?? {};
        return {
            user: Number(times.user) || 0,
            nice: Number(times.nice) || 0,
            sys: Number(times.sys) || 0,
            idle: Number(times.idle) || 0,
            irq: Number(times.irq) || 0,
        };
    });
}

/**
 * CPU usage between two `os.cpus()` snapshots: (busy delta / total delta).
 * Null when the counters are missing or went backwards (e.g. the process
 * watched a CPU set that changed), never a fake 0%.
 */
export function cpuUsagePercent(previous, current) {
    if (!Array.isArray(previous) || !Array.isArray(current)) return null;
    if (previous.length === 0 || previous.length !== current.length) return null;

    let totalDelta = 0;
    let idleDelta = 0;
    for (let index = 0; index < previous.length; index++) {
        const before = previous[index];
        const after = current[index];
        if (!before || !after) return null;
        totalDelta +=
            (after.user - before.user) +
            (after.nice - before.nice) +
            (after.sys - before.sys) +
            (after.idle - before.idle) +
            (after.irq - before.irq);
        idleDelta += after.idle - before.idle;
    }
    if (!(totalDelta > 0) || idleDelta < 0) return null;
    return roundTo(((totalDelta - idleDelta) / totalDelta) * 100, 1);
}

function defaultSleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

/** %ProgramData%\IDUPI\sensors.json unless IDUPI_SENSORS_FILE overrides it. */
function resolveSensorsFile(env) {
    if (env.IDUPI_SENSORS_FILE) return env.IDUPI_SENSORS_FILE;
    const programData = env.ProgramData || env.PROGRAMDATA || "C:\\ProgramData";
    return path.join(programData, "IDUPI", "sensors.json");
}

/**
 * Builds the metrics sampler. Every collaborator is injectable so tests never
 * touch real hardware, the network, or the wall clock.
 *
 * @param {object} [options]
 * @param {(file: string, args: string[], options: object) => Promise<{ stdout: string }>} [options.execFile]
 * @param {typeof fetch} [options.fetch]
 * @param {(file: string, encoding: string) => Promise<string | Buffer>} [options.readFile]
 * @param {(root: string) => Promise<object>} [options.statfs]
 * @param {(root: string) => Promise<void>} [options.access]
 * @param {string[]} [options.driveLetters]
 * @param {() => number} [options.now]
 * @param {typeof import("node:os")} [options.os]
 * @param {(ms: number) => Promise<void>} [options.sleep]
 * @param {Record<string, string | undefined>} [options.env]
 * @param {number} [options.cacheMs]
 */
export function createSystemMetrics({
    execFile = nodeExecFileAsync,
    fetch: fetchImpl = globalThis.fetch,
    readFile = nodeReadFile,
    statfs = nodeStatfs,
    access = nodeAccess,
    driveLetters = DRIVE_LETTERS,
    now = () => Date.now(),
    os: osImpl = nodeOs,
    sleep = defaultSleep,
    env = process.env,
    cacheMs = DEFAULT_CACHE_MS,
} = {}) {
    let previousCpuTimes = null;
    let cached = null;
    let inFlight = null;
    let cimCache = null;
    let cimRefresh = null;

    async function readCpu() {
        const cpus = osImpl.cpus();
        const first = Array.isArray(cpus) && cpus.length > 0 ? cpus[0] : null;
        const name = first?.model ? String(first.model).trim().replace(/\s+/g, " ") : null;
        const cores = Array.isArray(cpus) && cpus.length > 0 ? cpus.length : null;

        if (!previousCpuTimes) {
            previousCpuTimes = snapshotCpuTimes(cpus);
            await sleep(FIRST_CPU_SAMPLE_MS);
        }
        const current = snapshotCpuTimes(osImpl.cpus());
        const usagePercent = cpuUsagePercent(previousCpuTimes, current);
        previousCpuTimes = current;
        return { name, cores, usagePercent };
    }

    async function readGpus() {
        try {
            const { stdout } = await execFile(NVIDIA_SMI, NVIDIA_QUERY, {
                timeout: NVIDIA_TIMEOUT_MS,
                windowsHide: true,
                maxBuffer: NVIDIA_MAX_BUFFER,
                encoding: "utf8",
            });
            const gpus = parseNvidiaSmi(stdout);
            return { gpus, available: gpus.length > 0 };
        } catch {
            // Missing binary, no NVIDIA driver, or a timeout: the dashboard
            // says "no disponible" for GPU instead of failing the whole sample.
            return { gpus: [], available: false };
        }
    }

    /**
     * Reads the elevated helper's snapshot. Only a parseable file sampled less
     * than 10 s ago counts. The status also drives the hint when no source is
     * fresh, because "not installed" and "stopped" need different fixes.
     */
    async function readHelperSensors() {
        let raw;
        try {
            raw = await readFile(resolveSensorsFile(env), "utf8");
        } catch {
            return { status: "missing" };
        }

        let data;
        try {
            data = JSON.parse(String(raw));
        } catch {
            return { status: "invalid" };
        }

        const sampledAt = Date.parse(data?.sampledAt);
        if (!Number.isFinite(sampledAt)) return { status: "invalid" };

        const age = now() - sampledAt;
        if (!(age < HELPER_MAX_AGE_MS && age > -HELPER_CLOCK_SKEW_MS)) {
            return { status: "stale" };
        }

        // LibreHardwareMonitor reports 0 for sensors it cannot read (no kernel
        // driver), and 0 °C / 0 W is never a real reading on a running CPU.
        const cpuTemperatureC = positiveOrNull(parseSensorNumber(data?.cpu?.temperatureC));
        const cpuPowerW = positiveOrNull(parseSensorNumber(data?.cpu?.powerW));
        if (cpuTemperatureC === null && cpuPowerW === null) return { status: "nodriver" };

        return {
            status: "fresh",
            cpuTemperatureC,
            cpuPowerW,
            memoryTemperatureC: positiveOrNull(parseSensorNumber(data?.memory?.temperatureC)),
        };
    }

    async function readLhmSensors() {
        const url = env.IDUPI_LHM_URL || DEFAULT_LHM_URL;
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), LHM_TIMEOUT_MS);
        try {
            const response = await fetchImpl(url, { signal: controller.signal });
            if (!response || response.ok === false) {
                return { available: false, cpuTemperatureC: null, cpuPowerW: null, memoryTemperatureC: null };
            }
            const data = await response.json();
            const parsed = parseLhmSensors(data);
            return { available: true, ...parsed };
        } catch {
            return { available: false, cpuTemperatureC: null, cpuPowerW: null, memoryTemperatureC: null };
        } finally {
            clearTimeout(timer);
        }
    }

    async function statfsDisk(mount) {
        try {
            return statfsBytes(await statfs(`${mount}\\`));
        } catch {
            return null;
        }
    }

    /** CIM Size/FreeSpace as the size fallback when statfs cannot read a drive. */
    function cimDiskSizes(disk) {
        if (!(disk.sizeBytes > 0) || disk.freeBytes === null) return null;
        const totalBytes = disk.sizeBytes;
        const freeBytes = Math.min(disk.freeBytes, totalBytes);
        const usedBytes = Math.max(0, totalBytes - freeBytes);
        return {
            totalBytes,
            freeBytes,
            usedBytes,
            usagePercent: roundTo((usedBytes / totalBytes) * 100, 1),
        };
    }

    /**
     * Fixed-disk metadata from CIM, cached for a minute. A failure is cached
     * too: retrying a broken PowerShell every sample would spawn it on every
     * dashboard poll. `null` means "CIM unavailable, fall back to statfs".
     */
    async function readCimDisks() {
        const at = now();
        if (cimCache && at - cimCache.at < DISK_CIM_CACHE_MS) return cimCache.entries;
        // Stale-while-revalidate: the CIM query takes ~3 s, which would stall
        // one dashboard poll every minute. Once there is a cached answer, serve
        // it and refresh in the background (one refresh in flight at a time).
        if (cimCache) {
            if (!cimRefresh) {
                cimRefresh = queryCimDisks().finally(() => { cimRefresh = null; });
            }
            return cimCache.entries;
        }
        return queryCimDisks();
    }

    async function queryCimDisks() {
        const at = now();
        let entries = null;
        try {
            const { stdout } = await execFile(
                "powershell",
                ["-NoProfile", "-Command", LOGICAL_DISK_SCRIPT],
                {
                    timeout: CIM_TIMEOUT_MS,
                    windowsHide: true,
                    maxBuffer: CIM_MAX_BUFFER,
                    encoding: "utf8",
                },
            );
            const parsed = parseLogicalDisks(stdout);
            if (parsed.length > 0) entries = parsed;
        } catch {
            entries = null;
        }
        cimCache = { at, entries };
        return entries;
    }

    /**
     * Every fixed local disk. With CIM the list is authoritative (DriveType=3
     * only); without it, a letter is a disk when it exists and statfs reports a
     * positive total size. Sizes prefer statfs, so the card matches Explorer.
     */
    async function readDisks() {
        const cim = await readCimDisks();
        if (cim) {
            const disks = await Promise.all(
                cim.map(async (disk) => {
                    const sizes = (await statfsDisk(disk.mount)) ?? cimDiskSizes(disk);
                    if (!sizes) return null;
                    return {
                        mount: disk.mount,
                        label: disk.label,
                        fileSystem: disk.fileSystem,
                        ...sizes,
                    };
                }),
            );
            return disks.filter(Boolean).sort((a, b) => a.mount.localeCompare(b.mount));
        }

        const disks = await Promise.all(
            driveLetters.map(async (letter) => {
                const mount = `${String(letter).toUpperCase()}:`;
                try {
                    await access(`${mount}\\`);
                } catch {
                    return null;
                }
                const sizes = await statfsDisk(mount);
                if (!sizes) return null;
                return { mount, label: null, fileSystem: null, ...sizes };
            }),
        );
        return disks.filter(Boolean).sort((a, b) => a.mount.localeCompare(b.mount));
    }

    /**
     * Helper file first (elevated and precise) and the LHM web server second
     * (the manual alternative). Both unavailable leaves the fields null.
     */
    async function resolveSensors() {
        const helper = await readHelperSensors();
        if (helper.status === "fresh") {
            return {
                source: "sensors-helper",
                cpuTemperatureC: helper.cpuTemperatureC,
                cpuPowerW: helper.cpuPowerW,
                memoryTemperatureC: helper.memoryTemperatureC,
                helperStatus: helper.status,
            };
        }

        const lhm = await readLhmSensors();
        // LHM answering with all-null CPU readings is the same "no kernel
        // driver" case as the helper's: keep the values null, do not claim a
        // source, and let the hint name the installer instead of asking for a
        // web server that is already running.
        const lhmReadable = lhm.available &&
            (lhm.cpuTemperatureC !== null || lhm.cpuPowerW !== null);
        return {
            source: lhmReadable ? "librehardwaremonitor" : null,
            cpuTemperatureC: lhm.cpuTemperatureC,
            cpuPowerW: lhm.cpuPowerW,
            memoryTemperatureC: lhm.memoryTemperatureC,
            helperStatus: helper.status,
            lhmStatus: lhm.available ? (lhmReadable ? "fresh" : "nodriver") : null,
        };
    }

    async function buildSample() {
        const [cpu, gpuResult, sensors, disks] = await Promise.all([
            readCpu(),
            readGpus(),
            resolveSensors(),
            readDisks(),
        ]);

        const totalBytes = Number(osImpl.totalmem());
        const freeBytes = Number(osImpl.freemem());
        const usedBytes = Math.max(0, totalBytes - freeBytes);
        const hints = [];
        if (!gpuResult.available) hints.push(GPU_HINT);
        if (!sensors.source) {
            const noDriver =
                sensors.helperStatus === "nodriver" || sensors.lhmStatus === "nodriver";
            hints.push(
                noDriver ? SENSORS_NO_DRIVER_HINT
                    : sensors.helperStatus === "stale" ? SENSORS_STALE_HINT
                        : SENSORS_INSTALL_HINT
            );
        }

        return {
            sampledAt: new Date(now()).toISOString(),
            cpu: {
                name: cpu.name,
                usagePercent: cpu.usagePercent,
                temperatureC: sensors.cpuTemperatureC,
                powerW: sensors.cpuPowerW,
                cores: cpu.cores,
            },
            memory: {
                usedBytes,
                totalBytes,
                usagePercent: totalBytes > 0 ? roundTo((usedBytes / totalBytes) * 100, 1) : null,
                temperatureC: sensors.memoryTemperatureC,
            },
            gpus: gpuResult.gpus,
            disks,
            sources: {
                gpu: gpuResult.available ? "nvidia-smi" : null,
                sensors: sensors.source,
            },
            hints,
        };
    }

    return {
        /**
         * One metrics sample. Concurrent pollers inside the cache window share
         * the same promise, so a 2 s phone poll cannot spawn overlapping
         * nvidia-smi processes.
         */
        sample() {
            const at = now();
            if (cached && at - cached.at < cacheMs) return Promise.resolve(cached.value);
            if (inFlight) return inFlight;
            inFlight = buildSample()
                .then((value) => {
                    cached = { at: now(), value };
                    return value;
                })
                .finally(() => {
                    inFlight = null;
                });
            return inFlight;
        },
    };
}
