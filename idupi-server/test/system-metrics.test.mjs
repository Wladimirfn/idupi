// Tests for the dashboard metrics endpoint (app-redesign, T3).
//
// Three layers with the smallest harness that proves each:
//   - pure parsers (nvidia-smi CSV, LHM tree, CPU delta maths);
//   - the sampler factory with injected os/execFile/fetch/clock, so no test
//     touches real hardware, a real GPU process, or the wall clock;
//   - a real http.Server on an ephemeral port for the route shape, plus one
//     static check that index.mjs wires the handler AFTER requireAuth.
//
// Run (from repo root):
//   node --test idupi-server/test/system-metrics.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { readFile } from "node:fs/promises";

import {
    createSystemMetrics,
    cpuUsagePercent,
    findCpuNode,
    parseLhmSensors,
    parseLogicalDisks,
    parseNvidiaSmi,
    statfsBytes,
} from "../lib/system-metrics.mjs";
import { createSystemMetricsRoutes } from "../lib/system-metrics-routes.mjs";

const GIB = 1024 * 1024 * 1024;
const MIB = 1024 * 1024;

const nvidiaLine = "NVIDIA GeForce RTX 3060, 4, 39.97, 13.36, 1026, 12288";

function cpuTimes({ user, nice = 0, sys, idle, irq = 0 }) {
    return { user, nice, sys, idle, irq };
}

/** Fake `os` whose cpus() walks a fixed list of snapshots, one per call. */
function fakeOs({ snapshots, totalBytes = 16 * GIB, freeBytes = 6 * GIB, model = "AMD Ryzen 5 5600X" }) {
    let call = 0;
    return {
        cpus() {
            const snapshot = snapshots[Math.min(call, snapshots.length - 1)];
            call++;
            return snapshot.map((times) => ({ model, speed: 3600, times }));
        },
        totalmem: () => totalBytes,
        freemem: () => freeBytes,
    };
}

function unusableSources() {
    return {
        execFile: async () => {
            const error = new Error("spawn nvidia-smi ENOENT");
            error.code = "ENOENT";
            throw error;
        },
        fetch: async () => {
            throw new Error("ECONNREFUSED 127.0.0.1:8085");
        },
        readFile: async () => {
            const error = new Error("ENOENT: sensors.json");
            error.code = "ENOENT";
            throw error;
        },
        // No drive probing in unit tests: the disk cases inject their own.
        driveLetters: [],
    };
}

const HELPER_NOW = Date.parse("2026-11-23T12:00:00.000Z");

function helperSnapshot({
    sampledAt = new Date(HELPER_NOW - 2000).toISOString(),
    cpuTemperatureC = 51.2,
    cpuPowerW = 63.4,
    memoryTemperatureC = 34.5,
} = {}) {
    return JSON.stringify({
        sampledAt,
        elevated: true,
        source: "librehardwaremonitor",
        libVersion: "0.9.5.0",
        cpu: { name: "AMD Ryzen 5 5600X", temperatureC: cpuTemperatureC, powerW: cpuPowerW },
        memory: { temperatureC: memoryTemperatureC },
        sensors: [],
    });
}

/** Sampler wired for the helper-file tests: fixed clock, no real I/O. */
function helperMetrics({ readFile, fetch: fetchImpl, now = () => HELPER_NOW }) {
    return createSystemMetrics({
        os: fakeOs({
            snapshots: [
                [cpuTimes({ user: 10, sys: 10, idle: 80 })],
                [cpuTimes({ user: 20, sys: 20, idle: 160 })],
            ],
        }),
        execFile: async () => ({ stdout: `${nvidiaLine}\n` }),
        fetch:
            fetchImpl ??
            (async () => {
                throw new Error("ECONNREFUSED");
            }),
        readFile,
        sleep: async () => {},
        now,
        driveLetters: [],
        env: { IDUPI_SENSORS_FILE: "C:\\ProgramData\\IDUPI\\sensors.json" },
    });
}

const lhmAmdTree = {
    Text: "Sensor",
    Children: [
        {
            Text: "AMD Ryzen 5 5600X",
            Children: [
                {
                    Text: "Temperatures",
                    Type: "Group",
                    Children: [
                        {
                            Text: "Core (Tctl/Tdie)",
                            Value: "45.3 °C",
                            Type: "Temperature",
                            SensorId: "/amdcpu/0/temperature/2",
                        },
                        { Text: "Core #1", Value: "41.0 °C", Type: "Temperature" },
                    ],
                },
                {
                    Text: "Powers",
                    Children: [{ Text: "Package", Value: "65.2 W", Type: "Power" }],
                },
            ],
        },
    ],
};

const lhmIntelTree = {
    Text: "Sensor",
    Children: [
        {
            Text: "Intel Core i7-12700K",
            Children: [
                {
                    Text: "Temperatures",
                    Children: [
                        { Text: "Core #1", Value: "50.0 °C" },
                        { Text: "CPU Package", Value: "55.0 °C" },
                    ],
                },
                {
                    Text: "Powers",
                    Children: [{ Text: "Package", Value: "88.1 W" }],
                },
            ],
        },
        {
            Text: "Motherboard",
            Children: [
                {
                    Text: "Temperatures",
                    Children: [{ Text: "DIMM #1", Value: "38.0 °C" }],
                },
            ],
        },
    ],
};

// --- nvidia-smi ------------------------------------------------------------

test("nvidia-smi rows: numeric sensors become numbers, [N/A] stays null", () => {
    const gpus = parseNvidiaSmi(
        `${nvidiaLine}\nNVIDIA T400, [N/A], [N/A], [N/A], [N/A], [N/A]\n`,
    );

    assert.equal(gpus.length, 2);
    assert.deepEqual(gpus[0], {
        name: "NVIDIA GeForce RTX 3060",
        usagePercent: 4,
        temperatureC: 39.97,
        powerW: 13.36,
        memoryUsedBytes: 1026 * MIB,
        memoryTotalBytes: 12288 * MIB,
    });
    assert.deepEqual(gpus[1], {
        name: "NVIDIA T400",
        usagePercent: null,
        temperatureC: null,
        powerW: null,
        memoryUsedBytes: null,
        memoryTotalBytes: null,
    });
});

test("nvidia-smi parsing ignores blank and truncated rows", () => {
    assert.deepEqual(parseNvidiaSmi(""), []);
    assert.deepEqual(parseNvidiaSmi("only, three, cells\n"), []);
    assert.deepEqual(parseNvidiaSmi(undefined), []);
});

// --- CPU delta maths -------------------------------------------------------

test("cpu usage is the busy fraction between two snapshots", () => {
    const previous = [cpuTimes({ user: 100, sys: 100, idle: 800 })];
    const current = [cpuTimes({ user: 200, sys: 150, idle: 1650 })];

    // total delta 1000, idle delta 850 -> 15% busy.
    assert.equal(cpuUsagePercent(previous, current), 15);
});

test("cpu usage is null without a forward-moving delta", () => {
    const same = [cpuTimes({ user: 100, sys: 100, idle: 800 })];
    assert.equal(cpuUsagePercent(same, same), null);
    assert.equal(cpuUsagePercent([], []), null);
    assert.equal(cpuUsagePercent(undefined, same), null);
});

// --- LibreHardwareMonitor tree ---------------------------------------------

test("LHM AMD tree: Tctl/Tdie temperature and Package power win", () => {
    const sensors = parseLhmSensors(lhmAmdTree);

    assert.equal(sensors.cpuTemperatureC, 45.3);
    assert.equal(sensors.cpuPowerW, 65.2);
    assert.equal(sensors.memoryTemperatureC, null);
});

test("LHM Intel tree: CPU Package temperature and Package power", () => {
    const sensors = parseLhmSensors(lhmIntelTree);

    assert.equal(sensors.cpuTemperatureC, 55);
    assert.equal(sensors.cpuPowerW, 88.1);
    assert.equal(sensors.memoryTemperatureC, 38);
});

test("LHM tree without CPU sensors leaves the fields null", () => {
    const sensors = parseLhmSensors({ Text: "Sensor", Children: [] });

    assert.deepEqual(sensors, {
        cpuTemperatureC: null,
        cpuPowerW: null,
        memoryTemperatureC: null,
    });
});

// LHM nests hardware under root -> computer -> motherboard. "AMD Athlon 3000G"
// matches no vendor/product name test, so every container used to win the
// fallback score first and the CPU node was never selected.
const lhmUnbrandedCpuTree = {
    Text: "Sensor",
    Children: [
        {
            Text: "DESKTOP-IDUPI",
            Children: [
                {
                    Text: "B450M DS3H",
                    Children: [
                        {
                            Text: "AMD Athlon 3000G",
                            Children: [
                                {
                                    Text: "Temperatures",
                                    Children: [
                                        { Text: "Core (Tctl/Tdie)", Value: "48.0 °C" },
                                        { Text: "Core #1", Value: "46.0 °C" },
                                    ],
                                },
                                {
                                    Text: "Powers",
                                    Children: [{ Text: "Package", Value: "35.0 W" }],
                                },
                            ],
                        },
                    ],
                },
            ],
        },
    ],
};

test("LHM CPU lookup prefers the unbranded CPU device over the root/computer node", () => {
    const cpuNode = findCpuNode(lhmUnbrandedCpuTree);

    assert.ok(cpuNode);
    assert.equal(cpuNode.Text, "AMD Athlon 3000G");

    const sensors = parseLhmSensors(lhmUnbrandedCpuTree);
    assert.equal(sensors.cpuTemperatureC, 48);
    assert.equal(sensors.cpuPowerW, 35);
});

test("LHM zero readings are unreadable, not a real 0 °C / 0 W", () => {
    const zeroTree = {
        Text: "Sensor",
        Children: [
            {
                Text: "AMD Ryzen 5 5600X",
                Children: [
                    {
                        Text: "Temperatures",
                        Children: [{ Text: "Core (Tctl/Tdie)", Value: "0.0 °C" }],
                    },
                    {
                        Text: "Powers",
                        Children: [{ Text: "Package", Value: "0.0 W" }],
                    },
                ],
            },
        ],
    };

    assert.deepEqual(parseLhmSensors(zeroTree), {
        cpuTemperatureC: null,
        cpuPowerW: null,
        memoryTemperatureC: null,
    });
});

// --- fixed disks (T8) ------------------------------------------------------

const CIM_ONE_DISK = JSON.stringify({
    DeviceID: "C:",
    VolumeName: "Windows",
    FileSystem: "NTFS",
    Size: 500 * GIB,
    FreeSpace: 100 * GIB,
});

const CIM_TWO_DISKS = JSON.stringify([
    {
        DeviceID: "C:",
        VolumeName: "Windows",
        FileSystem: "NTFS",
        Size: 500 * GIB,
        FreeSpace: 100 * GIB,
    },
    { DeviceID: "D:", VolumeName: "", FileSystem: "NTFS", Size: 2000 * GIB, FreeSpace: 500 * GIB },
    { DeviceID: "E:", VolumeName: "Backup", FileSystem: "NTFS", Size: 32 * GIB, FreeSpace: 30 * GIB },
]);

function statfsFor(totalBytes, freeBytes) {
    const bsize = 4096;
    return { bsize, blocks: totalBytes / bsize, bavail: freeBytes / bsize };
}

function diskMetrics({ execFile, statfs, access = async () => {}, driveLetters = [] }) {
    return createSystemMetrics({
        os: fakeOs({
            snapshots: [
                [cpuTimes({ user: 10, sys: 10, idle: 80 })],
                [cpuTimes({ user: 20, sys: 20, idle: 160 })],
            ],
        }),
        execFile,
        fetch: async () => {
            throw new Error("ECONNREFUSED");
        },
        readFile: async () => {
            const error = new Error("ENOENT: sensors.json");
            error.code = "ENOENT";
            throw error;
        },
        statfs,
        access,
        driveLetters,
        sleep: async () => {},
    });
}

test("logical disk JSON parses as one object or as an array", () => {
    assert.deepEqual(parseLogicalDisks(CIM_ONE_DISK), [
        {
            mount: "C:",
            label: "Windows",
            fileSystem: "NTFS",
            sizeBytes: 500 * GIB,
            freeBytes: 100 * GIB,
        },
    ]);

    const disks = parseLogicalDisks(CIM_TWO_DISKS);
    assert.equal(disks.length, 3);
    assert.equal(disks[1].mount, "D:");
    assert.equal(disks[1].label, null, "an empty VolumeName is not a label");

    assert.deepEqual(parseLogicalDisks(""), []);
    assert.deepEqual(parseLogicalDisks("not json"), []);
    assert.deepEqual(parseLogicalDisks("null"), []);
    assert.deepEqual(
        parseLogicalDisks(JSON.stringify([{ DeviceID: "not-a-drive" }, { DeviceID: "C:" }])),
        [
            {
                mount: "C:",
                label: null,
                fileSystem: null,
                sizeBytes: null,
                freeBytes: null,
            },
        ],
    );
});

test("statfs bytes: total is blocks times block size and free is available space", () => {
    assert.deepEqual(statfsBytes({ bsize: 4096, blocks: 1000, bavail: 250 }), {
        totalBytes: 4096 * 1000,
        freeBytes: 4096 * 250,
        usedBytes: 4096 * 750,
        usagePercent: 75,
    });
    assert.equal(statfsBytes({ bsize: 0, blocks: 1000, bavail: 1 }), null);
    assert.equal(statfsBytes({ bsize: 4096, blocks: 0, bavail: 0 }), null);
    assert.equal(statfsBytes(undefined), null);
});

test("the sample lists every fixed disk with CIM labels and statfs sizes", async () => {
    const calls = [];
    const metrics = diskMetrics({
        execFile: async (file, args) => {
            calls.push({ file, args });
            if (file === "nvidia-smi") {
                const error = new Error("spawn nvidia-smi ENOENT");
                error.code = "ENOENT";
                throw error;
            }
            return { stdout: CIM_TWO_DISKS };
        },
        statfs: async (root) => {
            if (root === "C:\\") return statfsFor(500 * GIB, 125 * GIB);
            if (root === "D:\\") return statfsFor(2000 * GIB, 500 * GIB);
            // E:\\ cannot be read by statfs: the CIM Size/FreeSpace fallback
            // still produces a disk instead of hiding it.
            const error = new Error("ENOENT: E:\\");
            error.code = "ENOENT";
            throw error;
        },
        driveLetters: ["C", "D", "E"],
    });

    const sample = await metrics.sample();

    assert.deepEqual(sample.disks, [
        {
            mount: "C:",
            label: "Windows",
            fileSystem: "NTFS",
            totalBytes: 500 * GIB,
            freeBytes: 125 * GIB,
            usedBytes: 375 * GIB,
            usagePercent: 75,
        },
        {
            mount: "D:",
            label: null,
            fileSystem: "NTFS",
            totalBytes: 2000 * GIB,
            freeBytes: 500 * GIB,
            usedBytes: 1500 * GIB,
            usagePercent: 75,
        },
        {
            mount: "E:",
            label: "Backup",
            fileSystem: "NTFS",
            totalBytes: 32 * GIB,
            freeBytes: 30 * GIB,
            usedBytes: 2 * GIB,
            usagePercent: 6.3,
        },
    ]);

    const cimCall = calls.find((call) => call.file === "powershell");
    assert.ok(cimCall, "the fixed-disk metadata query must run");
    assert.match(cimCall.args[2], /DriveType=3/);
    assert.match(cimCall.args[2], /Win32_LogicalDisk/);
    assert.equal(cimCall.args[0], "-NoProfile");
});

test("a failed CIM query falls back to statfs-only disks with null labels", async () => {
    const metrics = diskMetrics({
        execFile: async (file) => {
            if (file === "nvidia-smi") {
                const error = new Error("spawn nvidia-smi ENOENT");
                error.code = "ENOENT";
                throw error;
            }
            throw new Error("powershell is not available");
        },
        statfs: async (root) => {
            if (root === "C:\\") return statfsFor(100 * GIB, 40 * GIB);
            const error = new Error("ENOENT: " + root);
            error.code = "ENOENT";
            throw error;
        },
        access: async (root) => {
            if (root === "C:\\") return;
            const error = new Error("ENOENT: " + root);
            error.code = "ENOENT";
            throw error;
        },
        driveLetters: ["C", "D"],
    });

    const sample = await metrics.sample();

    assert.deepEqual(sample.disks, [
        {
            mount: "C:",
            label: null,
            fileSystem: null,
            totalBytes: 100 * GIB,
            freeBytes: 40 * GIB,
            usedBytes: 60 * GIB,
            usagePercent: 60,
        },
    ]);
});

test("the CIM disk metadata query is cached for a minute", async () => {
    let clock = 1_000_000;
    let cimCalls = 0;
    const metrics = createSystemMetrics({
        os: fakeOs({
            snapshots: [
                [cpuTimes({ user: 10, sys: 10, idle: 80 })],
                [cpuTimes({ user: 20, sys: 20, idle: 160 })],
                [cpuTimes({ user: 30, sys: 30, idle: 240 })],
                [cpuTimes({ user: 40, sys: 40, idle: 320 })],
            ],
        }),
        execFile: async (file) => {
            if (file === "nvidia-smi") {
                const error = new Error("spawn nvidia-smi ENOENT");
                error.code = "ENOENT";
                throw error;
            }
            cimCalls++;
            return { stdout: CIM_ONE_DISK };
        },
        fetch: async () => {
            throw new Error("ECONNREFUSED");
        },
        readFile: async () => {
            const error = new Error("ENOENT: sensors.json");
            error.code = "ENOENT";
            throw error;
        },
        statfs: async () => statfsFor(500 * GIB, 100 * GIB),
        sleep: async () => {},
        now: () => clock,
        cacheMs: 1500,
    });

    await metrics.sample();
    clock += 2000;
    await metrics.sample();
    assert.equal(cimCalls, 1, "two samples inside a minute share the CIM query");

    clock += 61_000;
    await metrics.sample();
    assert.equal(cimCalls, 2);
});

test("an expired CIM cache is served stale while it refreshes in the background", async () => {
    let clock = 1_000_000;
    let cimCalls = 0;
    let releaseRefresh;
    const metrics = createSystemMetrics({
        os: fakeOs({
            snapshots: [
                [cpuTimes({ user: 10, sys: 10, idle: 80 })],
                [cpuTimes({ user: 20, sys: 20, idle: 160 })],
                [cpuTimes({ user: 30, sys: 30, idle: 240 })],
                [cpuTimes({ user: 40, sys: 40, idle: 320 })],
            ],
        }),
        execFile: async (file) => {
            if (file === "nvidia-smi") {
                const error = new Error("spawn nvidia-smi ENOENT");
                error.code = "ENOENT";
                throw error;
            }
            cimCalls++;
            // The second (background) query never settles until released: a
            // sample that awaited it would hang this test.
            if (cimCalls === 2) await new Promise((resolve) => { releaseRefresh = resolve; });
            return { stdout: CIM_ONE_DISK };
        },
        fetch: async () => {
            throw new Error("ECONNREFUSED");
        },
        readFile: async () => {
            const error = new Error("ENOENT: sensors.json");
            error.code = "ENOENT";
            throw error;
        },
        statfs: async () => statfsFor(500 * GIB, 100 * GIB),
        sleep: async () => {},
        now: () => clock,
        cacheMs: 1500,
    });

    await metrics.sample();
    clock += 61_000;
    const stale = await metrics.sample();
    assert.equal(cimCalls, 2, "the expired cache started one background refresh");
    assert.equal(stale.disks.length, 1, "the stale disk list is served meanwhile");

    clock += 2000;
    await metrics.sample();
    assert.equal(cimCalls, 2, "no second refresh while one is in flight");
    releaseRefresh();
});

// --- sampler ---------------------------------------------------------------

test("sample composes cpu, memory and GPU values from the injected sources", async () => {
    const bus = fakeOs({
        snapshots: [
            Array.from({ length: 8 }, () => cpuTimes({ user: 100, sys: 100, idle: 800 })),
            Array.from({ length: 8 }, () => cpuTimes({ user: 200, sys: 150, idle: 1650 })),
        ],
    });
    const calls = [];
    const metrics = createSystemMetrics({
        os: bus,
        execFile: async (file, args, options) => {
            calls.push({ file, args, options });
            return { stdout: `${nvidiaLine}\n` };
        },
        fetch: async () => {
            throw new Error("ECONNREFUSED");
        },
        readFile: async () => {
            const error = new Error("ENOENT: sensors.json");
            error.code = "ENOENT";
            throw error;
        },
        sleep: async () => {},
        driveLetters: [],
        now: () => Date.parse("2026-11-23T12:00:00.000Z"),
    });

    const sample = await metrics.sample();

    assert.equal(sample.sampledAt, "2026-11-23T12:00:00.000Z");
    assert.deepEqual(sample.cpu, {
        name: "AMD Ryzen 5 5600X",
        usagePercent: 15,
        temperatureC: null,
        powerW: null,
        cores: 8,
    });
    assert.deepEqual(sample.memory, {
        usedBytes: 10 * GIB,
        totalBytes: 16 * GIB,
        usagePercent: 62.5,
        temperatureC: null,
    });
    assert.equal(sample.gpus.length, 1);
    assert.equal(sample.gpus[0].name, "NVIDIA GeForce RTX 3060");
    assert.equal(sample.gpus[0].memoryTotalBytes, 12288 * MIB);
    assert.deepEqual(sample.sources, { gpu: "nvidia-smi", sensors: null });
    assert.equal(sample.hints.length, 1);
    assert.match(sample.hints[0], /LibreHardwareMonitor/);
    assert.deepEqual(sample.disks, []);

    const nvidiaCalls = calls.filter((call) => call.file === "nvidia-smi");
    assert.equal(nvidiaCalls.length, 1);
    assert.deepEqual(nvidiaCalls[0].args, [
        "--query-gpu=name,utilization.gpu,temperature.gpu,power.draw,memory.used,memory.total",
        "--format=csv,noheader,nounits",
    ]);
    assert.equal(nvidiaCalls[0].options.timeout, 3000);

    const cimCalls = calls.filter((call) => call.file === "powershell");
    assert.equal(cimCalls.length, 1, "the fixed-disk query runs once per sample window");
    assert.deepEqual(cimCalls[0].args.slice(0, 2), ["-NoProfile", "-Command"]);
    assert.match(cimCalls[0].args[2], /Win32_LogicalDisk/);
    assert.equal(cimCalls[0].options.timeout, 8000);
});

test("a missing nvidia-smi degrades to no GPUs plus a hint", async () => {
    const metrics = createSystemMetrics({
        os: fakeOs({
            snapshots: [
                [cpuTimes({ user: 10, sys: 10, idle: 80 })],
                [cpuTimes({ user: 20, sys: 20, idle: 160 })],
            ],
        }),
        ...unusableSources(),
        sleep: async () => {},
    });

    const sample = await metrics.sample();

    assert.deepEqual(sample.gpus, []);
    assert.deepEqual(sample.sources, { gpu: null, sensors: null });
    assert.equal(sample.hints.length, 2);
    assert.match(sample.hints[0], /nvidia-smi/);
    assert.match(sample.hints[1], /LibreHardwareMonitor/);
    // The rest of the sample is still real: a dead GPU probe must not blank CPU/RAM.
    assert.equal(sample.cpu.usagePercent, 20);
    assert.equal(sample.memory.totalBytes, 16 * GIB);
});

test("LHM sensors reach the sample; unreachable LHM keeps them null", async () => {
    const base = {
        os: fakeOs({
            snapshots: [
                [cpuTimes({ user: 10, sys: 10, idle: 80 })],
                [cpuTimes({ user: 20, sys: 20, idle: 160 })],
            ],
        }),
        execFile: async () => ({ stdout: `${nvidiaLine}\n` }),
        readFile: async () => {
            const error = new Error("ENOENT: sensors.json");
            error.code = "ENOENT";
            throw error;
        },
        sleep: async () => {},
        driveLetters: [],
    };

    const withSensors = await createSystemMetrics({
        ...base,
        fetch: async () => ({ ok: true, json: async () => lhmAmdTree }),
    }).sample();
    assert.equal(withSensors.cpu.temperatureC, 45.3);
    assert.equal(withSensors.cpu.powerW, 65.2);
    assert.equal(withSensors.sources.sensors, "librehardwaremonitor");
    assert.deepEqual(withSensors.hints, []);

    const withoutSensors = await createSystemMetrics({
        ...base,
        fetch: async () => ({ ok: false, json: async () => ({}) }),
    }).sample();
    assert.equal(withoutSensors.cpu.temperatureC, null);
    assert.equal(withoutSensors.sources.sensors, null);
    assert.match(withoutSensors.hints[0], /LibreHardwareMonitor/);
});

test("a fresh sensors-helper file wins over the LHM web server", async () => {
    let lhmCalls = 0;
    const sample = await helperMetrics({
        readFile: async () => helperSnapshot(),
        fetch: async () => {
            lhmCalls++;
            return { ok: true, json: async () => lhmAmdTree };
        },
    }).sample();

    assert.equal(sample.cpu.temperatureC, 51.2);
    assert.equal(sample.cpu.powerW, 63.4);
    assert.equal(sample.memory.temperatureC, 34.5);
    assert.equal(sample.sources.sensors, "sensors-helper");
    assert.equal(lhmCalls, 0, "the helper file must short-circuit the web server probe");
    assert.deepEqual(sample.hints, []);
});

test("a stale helper file is ignored: LHM wins, or the hint says the helper stopped", async () => {
    const staleRead = async () =>
        helperSnapshot({ sampledAt: new Date(HELPER_NOW - 60_000).toISOString() });

    const withLhm = await helperMetrics({
        readFile: staleRead,
        fetch: async () => ({ ok: true, json: async () => lhmAmdTree }),
    }).sample();
    assert.equal(withLhm.cpu.temperatureC, 45.3);
    assert.equal(withLhm.cpu.powerW, 65.2);
    assert.equal(withLhm.sources.sensors, "librehardwaremonitor");
    assert.deepEqual(withLhm.hints, []);

    const withoutAny = await helperMetrics({ readFile: staleRead }).sample();
    assert.equal(withoutAny.cpu.temperatureC, null);
    assert.equal(withoutAny.sources.sensors, null);
    assert.match(withoutAny.hints[0], /no está corriendo/);
    assert.match(withoutAny.hints[0], /install-sensors-helper\.ps1/);
    assert.match(withoutAny.hints[0], /LibreHardwareMonitor/);
});

test("a malformed helper file is ignored like a missing one", async () => {
    const sample = await helperMetrics({
        readFile: async () => "{ this is not json",
    }).sample();

    assert.equal(sample.cpu.temperatureC, null);
    assert.equal(sample.cpu.powerW, null);
    assert.equal(sample.memory.temperatureC, null);
    assert.equal(sample.sources.sensors, null);
    assert.match(sample.hints[0], /install-sensors-helper\.ps1/);
});

test("helper nulls stay null instead of becoming zero", async () => {
    const sample = await helperMetrics({
        readFile: async () =>
            helperSnapshot({ cpuTemperatureC: null, cpuPowerW: null, memoryTemperatureC: null }),
    }).sample();

    assert.equal(sample.cpu.temperatureC, null);
    assert.equal(sample.cpu.powerW, null);
    assert.equal(sample.memory.temperatureC, null);
    // A fresh helper that cannot read the CPU (no kernel driver) does not own
    // the source: the LHM web server may still answer, else the hint names the
    // installer, which brings PawnIO and the pinned LibreHardwareMonitor.
    assert.equal(sample.sources.sensors, null);
    assert.equal(sample.hints.length, 1);
    assert.match(sample.hints[0], /PawnIO/);
    assert.match(sample.hints[0], /install-sensors-helper\.ps1/);
    assert.match(sample.hints[0], /LibreHardwareMonitor/);
});

test("helper zeros (LHM without a driver) are treated as unreadable", async () => {
    const sample = await helperMetrics({
        readFile: async () =>
            helperSnapshot({ cpuTemperatureC: 0, cpuPowerW: 0, memoryTemperatureC: 0 }),
    }).sample();

    assert.equal(sample.cpu.temperatureC, null);
    assert.equal(sample.cpu.powerW, null);
    assert.equal(sample.memory.temperatureC, null);
    assert.match(sample.hints[0], /PawnIO/);
    assert.match(sample.hints[0], /install-sensors-helper\.ps1/);
});

test("an LHM server with all-null CPU readings counts like nodriver for the hint", async () => {
    const sample = await helperMetrics({
        readFile: async () => {
            const error = new Error("ENOENT: sensors.json");
            error.code = "ENOENT";
            throw error;
        },
        fetch: async () => ({
            ok: true,
            json: async () => ({
                Text: "Sensor",
                Children: [
                    {
                        Text: "AMD Ryzen 5 5600X",
                        Children: [
                            {
                                Text: "Temperatures",
                                Children: [{ Text: "Core (Tctl/Tdie)", Value: "0.0 °C" }],
                            },
                            {
                                Text: "Powers",
                                Children: [{ Text: "Package", Value: "0.0 W" }],
                            },
                        ],
                    },
                ],
            }),
        }),
    }).sample();

    assert.equal(sample.cpu.temperatureC, null);
    assert.equal(sample.cpu.powerW, null);
    // LHM answered but cannot read the CPU: not a usable source, and the hint
    // must point at the driver/installer instead of a web server that is up.
    assert.equal(sample.sources.sensors, null);
    assert.match(sample.hints[0], /PawnIO/);
});

test("the no-source hint names the installer and the LHM alternative", async () => {
    const sample = await helperMetrics({ readFile: unusableSources().readFile }).sample();

    assert.equal(sample.sources.sensors, null);
    assert.equal(sample.hints.length, 1);
    assert.match(sample.hints[0], /idupi-server\\scripts\\install-sensors-helper\.ps1/);
    assert.match(sample.hints[0], /LibreHardwareMonitor/);
});

test("concurrent pollers share one sample inside the cache window", async () => {
    let clock = 1_000_000;
    let nvidiaCalls = 0;
    const metrics = createSystemMetrics({
        os: fakeOs({
            snapshots: [
                [cpuTimes({ user: 10, sys: 10, idle: 80 })],
                [cpuTimes({ user: 20, sys: 20, idle: 160 })],
            ],
        }),
        execFile: async (file) => {
            if (file !== "nvidia-smi") return { stdout: "" };
            nvidiaCalls++;
            return { stdout: `${nvidiaLine}\n` };
        },
        fetch: async () => {
            throw new Error("ECONNREFUSED");
        },
        readFile: async () => {
            const error = new Error("ENOENT: sensors.json");
            error.code = "ENOENT";
            throw error;
        },
        sleep: async () => {},
        driveLetters: [],
        now: () => clock,
        cacheMs: 1500,
    });

    const [first, second] = await Promise.all([metrics.sample(), metrics.sample()]);
    const third = await metrics.sample();
    assert.equal(first, second);
    assert.equal(first, third);
    assert.equal(nvidiaCalls, 1);

    clock += 1600;
    await metrics.sample();
    assert.equal(nvidiaCalls, 2);
});

// --- route -----------------------------------------------------------------

const fixtureSample = {
    sampledAt: "2026-11-23T12:00:00.000Z",
    cpu: { name: "AMD Ryzen 5 5600X", usagePercent: 12.5, temperatureC: null, powerW: null, cores: 12 },
    memory: { usedBytes: 10 * GIB, totalBytes: 32 * GIB, usagePercent: 31.3, temperatureC: null },
    gpus: [
        {
            name: "NVIDIA GeForce RTX 3060",
            usagePercent: 4,
            temperatureC: 39.97,
            powerW: 13.36,
            memoryUsedBytes: 1026 * MIB,
            memoryTotalBytes: 12288 * MIB,
        },
    ],
    disks: [
        {
            mount: "C:",
            label: "Windows",
            fileSystem: "NTFS",
            totalBytes: 500 * GIB,
            freeBytes: 125 * GIB,
            usedBytes: 375 * GIB,
            usagePercent: 75,
        },
    ],
    sources: { gpu: "nvidia-smi", sensors: null },
    hints: ["librehardwaremonitor apagado"],
};

async function startHarness(t, metrics) {
    const handle = createSystemMetricsRoutes(metrics);
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
    return base;
}

test("GET /api/v1/system/metrics answers the dashboard shape", async (t) => {
    const base = await startHarness(t, { sample: async () => fixtureSample });

    const res = await fetch(`${base}/api/v1/system/metrics`);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("content-type"), "application/json");
    assert.equal(res.headers.get("cache-control"), "no-store");

    const body = await res.json();
    assert.deepEqual(Object.keys(body), [
        "sampledAt",
        "cpu",
        "memory",
        "gpus",
        "disks",
        "sources",
        "hints",
    ]);
    assert.deepEqual(body.cpu, fixtureSample.cpu);
    assert.deepEqual(body.memory, fixtureSample.memory);
    assert.deepEqual(body.gpus, fixtureSample.gpus);
    assert.deepEqual(body.disks, fixtureSample.disks);
    assert.deepEqual(body.sources, fixtureSample.sources);
    assert.deepEqual(body.hints, fixtureSample.hints);
});

test("HEAD answers the same headers with no body; other paths are not ours", async (t) => {
    const base = await startHarness(t, { sample: async () => fixtureSample });

    const head = await fetch(`${base}/api/v1/system/metrics`, { method: "HEAD" });
    assert.equal(head.status, 200);
    assert.ok(Number(head.headers.get("content-length")) > 0);
    assert.equal((await head.arrayBuffer()).byteLength, 0);

    const foreign = await fetch(`${base}/api/v1/status`);
    assert.equal(foreign.status, 418);
    await foreign.arrayBuffer();
});

test("non read-only methods answer 405 with Allow", async (t) => {
    let sampled = 0;
    const base = await startHarness(t, {
        sample: async () => {
            sampled++;
            return fixtureSample;
        },
    });

    for (const method of ["POST", "PUT", "DELETE", "PATCH"]) {
        const res = await fetch(`${base}/api/v1/system/metrics`, { method });
        assert.equal(res.status, 405, method);
        assert.equal(res.headers.get("allow"), "GET, HEAD");
        assert.equal((await res.json()).code, "METHOD_NOT_ALLOWED");
    }
    assert.equal(sampled, 0);
});

test("a failing sampler answers 500 instead of a malformed payload", async (t) => {
    const base = await startHarness(t, {
        sample: async () => {
            throw new Error("nvidia exploded");
        },
    });

    const res = await fetch(`${base}/api/v1/system/metrics`);
    assert.equal(res.status, 500);
    assert.equal((await res.json()).code, "INTERNAL");
});

// --- wiring ----------------------------------------------------------------

test("index.mjs wires the metrics route behind requireAuth", async () => {
    const source = await readFile(new URL("../index.mjs", import.meta.url), "utf8");
    const authGate = source.indexOf("if (!requireAuth(req, res)) return;");
    const metricsWire = source.indexOf("handleSystemMetricsRoute(req, res, pathname)");

    assert.ok(authGate > -1, "the bearer-token gate must exist");
    assert.ok(metricsWire > -1, "the metrics handler must be called in the route chain");
    assert.ok(
        metricsWire > authGate,
        "the metrics handler must run after the bearer-token check",
    );
});
