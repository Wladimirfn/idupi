import { execFile } from "node:child_process";
import { existsSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const execFileP = promisify(execFile);

const serverRoot = fileURLToPath(new URL("../", import.meta.url));
const driverDir = join(serverRoot, "drivers", "usbmmidd_v2");
const candidateInstallers = [
    join(driverDir, "deviceinstaller64.exe"),
    join(driverDir, "usbmmidd_v2", "deviceinstaller64.exe"),
    "C:\\usbmmidd_v2\\deviceinstaller64.exe",
];

const USBMMIDD_URLS = [
    "https://www.amyuni.com/downloads/usbmmidd_v2.zip",
];

let state = {
    active: false,
    usedIdd: false,
    usedTopologySwitch: false,
    monitorId: null,
    installerPath: null,
    driverInstalled: false,
};

function findInstaller() {
    for (const candidate of candidateInstallers) {
        if (existsSync(candidate)) return candidate;
    }
    return null;
}

async function ensureDriverDownloaded() {
    const existing = findInstaller();
    if (existing) {
        state.installerPath = existing;
        return existing;
    }

    mkdirSync(driverDir, { recursive: true });
    const zipPath = join(driverDir, "usbmmidd_v2.zip");

    for (const url of USBMMIDD_URLS) {
        try {
            const psDownload = [
                "$ProgressPreference = 'SilentlyContinue';",
                `Invoke-WebRequest -Uri '${url}' -OutFile '${zipPath}' -UseBasicParsing -TimeoutSec 15;`,
                `Expand-Archive -Path '${zipPath}' -DestinationPath '${driverDir}' -Force;`,
            ].join(" ");
            await execFileP("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", psDownload], {
                timeout: 25000,
                windowsHide: true,
            });
            const found = findInstaller();
            if (found) {
                state.installerPath = found;
                return found;
            }
        } catch {
            /* try next or fallback */
        }
    }
    return null;
}

async function runInstaller(installerPath, args, { allowElevate = false } = {}) {
    const cwd = join(installerPath, "..");
    try {
        await execFileP(installerPath, args, { cwd, timeout: 10000, windowsHide: true });
        return true;
    } catch {
        if (!allowElevate) return false;
        try {
            const argList = args.map((a) => `'${a}'`).join(", ");
            const psCmd = `Start-Process -FilePath '${installerPath}' -WorkingDirectory '${cwd}' -ArgumentList @(${argList}) -Verb RunAs -Wait -WindowStyle Hidden`;
            await execFileP("powershell.exe", ["-NoProfile", "-Command", psCmd], {
                timeout: 30000,
                windowsHide: true,
            });
            return true;
        } catch {
            return false;
        }
    }
}

function wait(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

export function getVirtualDisplayState() {
    return { ...state };
}

export async function enableVirtualDisplay(helper, { width = 1920, height = 1080 } = {}) {
    const targetW = Math.max(1280, Math.round(Number(width) || 1920));
    const targetH = Math.max(720, Math.round(Number(height) || 1080));

    const beforeMonitors = (await helper.list().catch(() => [])) || [];
    const beforeNames = new Set(beforeMonitors.map((m) => m.name));

    // Step 1: Try user-mode SetDisplayConfig(SDC_TOPOLOGY_EXTEND) first.
    // If usbmmidd was already enabled earlier in this session and merely switched to
    // SDC_TOPOLOGY_INTERNAL on exit, this re-activates the extra monitor in <150ms with zero UAC!
    await helper.request({
        cmd: "display_extend",
        width: targetW,
        height: targetH,
    }).catch(() => {});

    await wait(300);
    let monitors = (await helper.list().catch(() => [])) || beforeMonitors;

    // Step 2: If still only 1 monitor, attach/enable the IDD virtual display via deviceinstaller64
    if (monitors.length <= 1) {
        const installer = await ensureDriverDownloaded();
        if (installer) {
            let enabled = await runInstaller(installer, ["enableidd", "1"], { allowElevate: false });
            if (!enabled) {
                if (!state.driverInstalled) {
                    await runInstaller(installer, ["install", "usbmmidd.inf", "usbmmidd"], { allowElevate: true });
                    state.driverInstalled = true;
                }
                // Always allow elevation if non-elevated enableidd 1 returned false
                enabled = await runInstaller(installer, ["enableidd", "1"], { allowElevate: true });
            }
            if (enabled) {
                state.usedIdd = true;
            }
        }

        await helper.request({
            cmd: "display_extend",
            width: targetW,
            height: targetH,
        }).catch(() => {});

        for (let attempt = 0; attempt < 10; attempt += 1) {
            monitors = (await helper.list().catch(() => [])) || monitors;
            if (monitors.length > 1) break;
            await wait(250);
        }
    } else {
        state.usedTopologySwitch = true;
    }

    // Step 3: Ensure target resolution is applied to the secondary monitor
    if (monitors.length > 1) {
        await helper.request({
            cmd: "display_extend",
            width: targetW,
            height: targetH,
        }).catch(() => {});
        await wait(150);
        monitors = (await helper.list().catch(() => [])) || monitors;
    }

    const addedMonitor =
        monitors.find((m) => !beforeNames.has(m.name)) ||
        [...monitors].reverse().find((m) => !m.primary) ||
        monitors[monitors.length - 1] ||
        monitors[0];

    state.active = monitors.length > 1;
    state.monitorId = addedMonitor ? addedMonitor.id : 0;

    return {
        ok: true,
        active: state.active,
        monitorId: state.monitorId,
        monitor: addedMonitor ?? null,
        monitors,
        singleMonitorFallback: monitors.length <= 1,
    };
}

export async function disableVirtualDisplay(helper, { fullUnload = false } = {}) {
    const wasIdd = state.usedIdd;
    state.active = false;
    state.monitorId = null;

    // Switch Windows Display Topology to INTERNAL ("PC screen only").
    // This immediately detaches the secondary virtual monitor from the desktop and moves all
    // windows back to the primary monitor WITHOUT unloading the IDD driver from Device Manager,
    // so re-enabling Extra Monitor later works instantaneously via display_extend without UAC!
    if (helper) {
        await helper.request({ cmd: "display_internal" }).catch(() => {});
        await wait(350);
    }

    let monitors = helper ? (await helper.list().catch(() => [])) || [] : [];

    // If fullUnload is requested or display_internal left >1 monitors from IDD, run enableidd 0
    if ((fullUnload || monitors.length > 1) && wasIdd) {
        const installer = state.installerPath || findInstaller();
        if (installer) {
            const unloaded = await runInstaller(installer, ["enableidd", "0"], { allowElevate: false });
            if (!unloaded) {
                await runInstaller(installer, ["enableidd", "0"], { allowElevate: true });
            }
            await wait(350);
            if (helper) {
                monitors = (await helper.list().catch(() => [])) || monitors;
            }
        }
    }

    const primary = monitors.find((m) => m.primary) || monitors[0] || null;

    return {
        ok: true,
        active: false,
        monitorId: primary ? primary.id : 0,
        monitor: primary,
        monitors,
    };
}
