import { execFile } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
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

const TASK_ENABLE = "IDUPI_VDD_Enable";
const TASK_DISABLE = "IDUPI_VDD_Disable";

let state = {
    active: false,
    addedCount: 0,
    monitorId: null,
    monitorName: null,
    installerPath: null,
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
            /* try next */
        }
    }
    return null;
}

function wait(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

async function runScheduledTask(taskName) {
    try {
        await execFileP("schtasks.exe", ["/Query", "/TN", taskName], {
            timeout: 4000,
            windowsHide: true,
        });
        await execFileP("schtasks.exe", ["/Run", "/TN", taskName], {
            timeout: 6000,
            windowsHide: true,
        });
        return true;
    } catch {
        return false;
    }
}

async function runElevatedSetupAndAction(installerPath, actionArg) {
    const cwd = join(installerPath, "..");
    const setupBat = join(cwd, "idupi_vdd_action.bat");
    const batContent = [
        `@echo off`,
        `cd /d "${cwd}"`,
        `deviceinstaller64.exe install usbmmidd.inf usbmmidd`,
        `deviceinstaller64.exe enableidd ${actionArg}`,
        `schtasks /Create /TN "${TASK_ENABLE}" /TR "cmd.exe /c cd /d \\"${cwd}\\" && deviceinstaller64.exe enableidd 1" /SC ONCE /ST 00:00 /RL HIGHEST /F`,
        `schtasks /Create /TN "${TASK_DISABLE}" /TR "cmd.exe /c cd /d \\"${cwd}\\" && deviceinstaller64.exe enableidd 0" /SC ONCE /ST 00:00 /RL HIGHEST /F`,
    ].join("\r\n");

    try {
        writeFileSync(setupBat, batContent, "utf8");
        const psCmd = `Start-Process -FilePath 'cmd.exe' -ArgumentList '/c','""${setupBat}""' -WorkingDirectory '${cwd}' -Verb RunAs -Wait -WindowStyle Hidden`;
        await execFileP("powershell.exe", ["-NoProfile", "-Command", psCmd], {
            timeout: 35000,
            windowsHide: true,
        });
        return true;
    } catch {
        return false;
    }
}

async function setIddEnabled(installerPath, enable) {
    const taskName = enable ? TASK_ENABLE : TASK_DISABLE;
    if (await runScheduledTask(taskName)) {
        await wait(600);
        return true;
    }
    return runElevatedSetupAndAction(installerPath, enable ? "1" : "0");
}

export function getVirtualDisplayState() {
    return { ...state };
}

export async function enableVirtualDisplay(helper, { width = 1920, height = 1080 } = {}) {
    const targetW = Math.max(1280, Math.round(Number(width) || 1920));
    const targetH = Math.max(720, Math.round(Number(height) || 1080));

    // Step 1: Always ensure all existing physical monitors are extended and ON first
    // (this also restores the 2nd physical monitor if it was previously turned off).
    await helper.request({
        cmd: "display_extend",
    }).catch(() => {});
    await wait(250);

    const beforeMonitors = (await helper.list().catch(() => [])) || [];
    const beforeNames = new Set(beforeMonitors.map((m) => m.name));

    // Step 2: If virtual monitor is not currently added, run enableidd 1 to ADD a new monitor (N -> N+1)
    if (!state.active) {
        const installer = await ensureDriverDownloaded();
        if (installer) {
            await setIddEnabled(installer, true);
        }

        // Extend desktop across all physical monitors + the new virtual monitor
        await helper.request({
            cmd: "display_extend",
            width: targetW,
            height: targetH,
        }).catch(() => {});

        let monitors = beforeMonitors;
        for (let attempt = 0; attempt < 12; attempt += 1) {
            monitors = (await helper.list().catch(() => [])) || monitors;
            if (monitors.length > beforeMonitors.length) {
                break;
            }
            await wait(250);
        }

        // If the scheduled task hadn't created the device yet, run elevated setup once
        if (monitors.length <= beforeMonitors.length && installer) {
            await runElevatedSetupAndAction(installer, "1");
            await helper.request({
                cmd: "display_extend",
                width: targetW,
                height: targetH,
            }).catch(() => {});
            for (let attempt = 0; attempt < 10; attempt += 1) {
                monitors = (await helper.list().catch(() => [])) || monitors;
                if (monitors.length > beforeMonitors.length) break;
                await wait(250);
            }
        }
    }

    // Step 3: Refresh monitor list and apply target resolution ONLY to the newly added virtual monitor
    let monitors = (await helper.list().catch(() => [])) || beforeMonitors;
    let newVirtualMonitor = monitors.find((m) => !beforeNames.has(m.name));

    if (newVirtualMonitor) {
        await helper.request({
            cmd: "display_extend",
            text: newVirtualMonitor.name,
            width: targetW,
            height: targetH,
        }).catch(() => {});
        await wait(150);
        monitors = (await helper.list().catch(() => [])) || monitors;
        newVirtualMonitor = monitors.find((m) => !beforeNames.has(m.name)) || newVirtualMonitor;
    }

    const addedMonitor =
        newVirtualMonitor ||
        (state.monitorName ? monitors.find((m) => m.name === state.monitorName) : null) ||
        monitors[monitors.length - 1] ||
        monitors[0];

    state.active = monitors.length > beforeMonitors.length || state.active;
    if (state.active) state.addedCount = 1;
    state.monitorId = addedMonitor ? addedMonitor.id : 0;
    state.monitorName = addedMonitor ? addedMonitor.name : null;

    return {
        ok: true,
        active: state.active,
        monitorId: state.monitorId,
        monitor: addedMonitor ?? null,
        monitors,
        singleMonitorFallback: monitors.length <= beforeMonitors.length,
    };
}

export async function disableVirtualDisplay(helper) {
    const wasActive = state.active || state.addedCount > 0;
    state.active = false;
    state.addedCount = 0;
    state.monitorId = null;
    state.monitorName = null;

    // IMPORTANT: Never call display_internal (SDC_TOPOLOGY_INTERNAL), because that turns off
    // the user's 2nd physical monitor! Instead, ONLY disable the virtual IDD monitor (enableidd 0)
    // and keep SDC_TOPOLOGY_EXTEND active so all physical monitors stay ON.
    const installer = state.installerPath || findInstaller();
    if (wasActive && installer) {
        await setIddEnabled(installer, false);
        await wait(350);
    }

    if (helper) {
        await helper.request({ cmd: "display_extend" }).catch(() => {});
        await wait(250);
    }

    const monitors = helper ? (await helper.list().catch(() => [])) || [] : [];
    const primary = monitors.find((m) => m.primary) || monitors[0] || null;

    return {
        ok: true,
        active: false,
        monitorId: primary ? primary.id : 0,
        monitor: primary,
        monitors,
    };
}
