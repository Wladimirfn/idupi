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
// The owner's Ryzen 5600X reports no temperature or power through WMI
// (MSAcpi_ThermalZoneTemperature has 0 instances). Windows only exposes those
// sensors to an elevated process, hence the helper: it loads the same
// LibreHardwareMonitorLib 0.9.5 RivaTuner ships and refreshes a small JSON
// snapshot. A fresh helper file wins over the web server; a file older than
// 10 s is treated as absent (the helper stopped), and the web server is tried.
// A null here is the contract: the app renders "no disponible" instead of a
// made-up number.
//
// Everything is injected (execFile, fetch, readFile, now, os, sleep, env) so
// the test suite exercises the parsing and the delta maths without real
// hardware, and the server never needs a dependency for it (node built-ins
// only).

import * as nodeOs from "node:os";
import { execFile as nodeExecFile } from "node:child_process";
import { readFile as nodeReadFile } from "node:fs/promises";
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

const SENSORS_INSTALL_HINT =
    "Para temperatura y consumo del CPU, instalá el servicio de sensores con permisos de administrador: idupi-server\\scripts\\install-sensors-helper.ps1 (alternativa: LibreHardwareMonitor con 'Remote Web Server' activado en el puerto 8085)";
const SENSORS_STALE_HINT =
    "El servicio de sensores no está corriendo: sensors.json tiene más de 10 segundos sin actualizarse. Revisá la tarea programada 'IDUPI Sensors' (se instala con idupi-server\\scripts\\install-sensors-helper.ps1; alternativa: LibreHardwareMonitor con 'Remote Web Server' en el puerto 8085)";
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
 */
function findCpuNode(nodes) {
    let best = null;
    let bestScore = 0;
    for (const node of nodes) {
        const children = Array.isArray(node.Children) ? node.Children : [];
        if (children.length === 0) continue;
        const text = nodeText(node);
        if (SENSOR_GROUPS.has(text.toLowerCase())) continue;

        let score = 0;
        if (/ryzen|threadripper|intel|core\s*i\d|xeon/i.test(text)) score = 3;
        else if (/^cpu\b/i.test(text)) score = 2;
        else {
            const descendants = collectNodes(node).map((entry) => nodeText(entry));
            if (descendants.some((entry) => /tctl|tdie|cpu package/i.test(entry))) score = 1;
        }
        // Higher score wins; equal scores keep the first (shallower) match.
        if (score > bestScore) {
            best = node;
            bestScore = score;
        }
    }
    return best;
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
    const cpuNode = findCpuNode(nodes);
    const cpuNodes = cpuNode ? collectNodes(cpuNode) : [];
    const memoryTemperature = nodes
        .filter((node) => isTemperatureNode(node) && /dimm|smbus|memory/i.test(nodeText(node)))
        .map((node) => ({ node, value: parseSensorNumber(node.Value) }))
        .find((entry) => entry.value !== null);

    return {
        cpuTemperatureC: cpuNodes.length > 0 ? pickCpuTemperature(cpuNodes) : null,
        cpuPowerW: cpuNodes.length > 0 ? pickCpuPower(cpuNodes) : null,
        memoryTemperatureC: memoryTemperature ? memoryTemperature.value : null,
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
    now = () => Date.now(),
    os: osImpl = nodeOs,
    sleep = defaultSleep,
    env = process.env,
    cacheMs = DEFAULT_CACHE_MS,
} = {}) {
    let previousCpuTimes = null;
    let cached = null;
    let inFlight = null;

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

        return {
            status: "fresh",
            cpuTemperatureC: parseSensorNumber(data?.cpu?.temperatureC),
            cpuPowerW: parseSensorNumber(data?.cpu?.powerW),
            memoryTemperatureC: parseSensorNumber(data?.memory?.temperatureC),
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
        return {
            source: lhm.available ? "librehardwaremonitor" : null,
            cpuTemperatureC: lhm.cpuTemperatureC,
            cpuPowerW: lhm.cpuPowerW,
            memoryTemperatureC: lhm.memoryTemperatureC,
            helperStatus: helper.status,
        };
    }

    async function buildSample() {
        const [cpu, gpuResult, sensors] = await Promise.all([
            readCpu(),
            readGpus(),
            resolveSensors(),
        ]);

        const totalBytes = Number(osImpl.totalmem());
        const freeBytes = Number(osImpl.freemem());
        const usedBytes = Math.max(0, totalBytes - freeBytes);
        const hints = [];
        if (!gpuResult.available) hints.push(GPU_HINT);
        if (!sensors.source) {
            hints.push(sensors.helperStatus === "stale" ? SENSORS_STALE_HINT : SENSORS_INSTALL_HINT);
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
