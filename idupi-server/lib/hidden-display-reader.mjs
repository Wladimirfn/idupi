// Hidden virtual display reader (phone-health, T1).
//
// Reads an app that exposes no Health Connect data (Huawei Health; Honor
// Health tentative) without lighting the owner's screen. The verified flow on
// the owner's device:
//
//   scrcpy --new-display=1080x2340/440 --start-app=<pkg> --no-window
//          --no-audio --record=<temp.mkv> --max-fps=5 --video-bit-rate=500K
//   adb shell input -d <id> keyevent KEYCODE_WAKEUP   (wakes only that display)
//   adb shell uiautomator dump --display <id> <file>
//   adb exec-out cat <file>; adb shell rm -f <file>
//
// Killing scrcpy destroys the display. The reader is strictly read-only: it
// launches an allowlisted package on a virtual display and dumps its hierarchy,
// nothing else. One read runs at a time; the temp recording and the remote dump
// are deleted in `finally`, and a 60 s deadline bounds the whole flow.
//
// Process spawning, sleeping and the clock are injected so tests never start
// scrcpy or adb.

import { spawn as nodeSpawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { unlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { HEALTH_PACKAGE_LIST, healthPackageInfo, isHealthPackage } from "./health-profiles.mjs";

export const READ_TIMEOUT_MS = 60_000;
export const DISPLAY_WAIT_MS = 15_000;
export const DISPLAY_POLL_MS = 250;
export const DISPLAY_READY_DELAY_MS = 4_000;
export const WAKE_DELAY_MS = 3_000;
export const RETRY_DELAY_MS = 2_000;

export const WAKE_TIMEOUT_MS = 10_000;
export const DUMP_TIMEOUT_MS = 20_000;
export const CAT_TIMEOUT_MS = 15_000;
export const REMOVE_TIMEOUT_MS = 10_000;
export const MAX_XML_BYTES = 8 * 1024 * 1024;

const MAX_OUTPUT_BYTES = 64 * 1024;
const MAX_DISPLAY_POLLS = 120;
const MAX_ATTEMPTS = 2;

/** Fixed argv for the virtual display; never built from user input. */
export function scrcpyArguments({ serial, packageName, recordPath }) {
    return [
        "-s",
        serial,
        "--new-display=1080x2340/440",
        `--start-app=${packageName}`,
        "--no-window",
        "--no-audio",
        `--record=${recordPath}`,
        "--max-fps=5",
        "--video-bit-rate=500K",
    ];
}

/** `New display: ... (id=3)` -> 3. Accepts both scrcpy output streams. */
export function parseNewDisplayId(text) {
    const match = /New display:[^\n(]*\(id=(\d+)\)/i.exec(String(text ?? ""));
    if (!match) return null;
    const id = Number(match[1]);
    return Number.isInteger(id) && id >= 0 ? id : null;
}

const NAMED_ENTITIES = new Map([
    ["lt", "<"],
    ["gt", ">"],
    ["amp", "&"],
    ["quot", "\""],
    ["apos", "'"],
    ["nbsp", " "],
]);

/** Decodes the XML entities uiautomator emits, including numeric ones. */
export function decodeXmlEntities(text) {
    return String(text ?? "").replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (whole, body) => {
        if (body.startsWith("#")) {
            const hex = body[1] === "x" || body[1] === "X";
            const code = Number.parseInt(hex ? body.slice(2) : body.slice(1), hex ? 16 : 10);
            if (!Number.isInteger(code) || code < 0 || code > 0x10FFFF) return whole;
            try {
                return String.fromCodePoint(code);
            } catch {
                return whole;
            }
        }
        const named = NAMED_ENTITIES.get(body.toLowerCase());
        return named === undefined ? whole : named;
    });
}

/** `[x1,y1][x2,y2]` -> `{x1,y1,x2,y2}` or null. */
export function parseBounds(raw) {
    const match = /^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$/.exec(String(raw ?? "").trim());
    if (!match) return null;
    return {
        x1: Number(match[1]),
        y1: Number(match[2]),
        x2: Number(match[3]),
        y2: Number(match[4]),
    };
}

function parseAttributes(raw) {
    const attributes = {};
    const pattern = /([A-Za-z0-9_:-]+)="([^"]*)"/g;
    let match;
    while ((match = pattern.exec(raw)) !== null) {
        attributes[match[1]] = match[2];
    }
    return attributes;
}

/**
 * Pure uiautomator XML parser. Returns one entry per `<node>`:
 * `{text, resourceId, contentDesc, packageName, bounds, rect}`.
 */
export function parseUiXml(xml) {
    const source = String(xml ?? "");
    const nodes = [];
    const pattern = /<node\b([^>]*?)\/?>/g;
    let match;
    while ((match = pattern.exec(source)) !== null) {
        const attributes = parseAttributes(match[1]);
        const bounds = attributes.bounds || "";
        nodes.push({
            text: decodeXmlEntities(attributes.text || ""),
            resourceId: decodeXmlEntities(attributes["resource-id"] || ""),
            contentDesc: decodeXmlEntities(attributes["content-desc"] || ""),
            packageName: decodeXmlEntities(attributes.package || ""),
            bounds,
            rect: parseBounds(bounds),
        });
    }
    return nodes;
}

function defaultSleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function tailOf(text) {
    const value = String(text ?? "");
    return value.length > 400 ? value.slice(-400) : value;
}

/**
 * @param {{
 *   connection: ReturnType<import("./adb-connection.mjs").createAdbConnection>,
 *   run: (command: string, args: string[], options?: object) => Promise<{stdout?: string, stderr?: string}>,
 *   spawn?: typeof nodeSpawn,
 *   sleep?: (ms: number) => Promise<void>,
 *   nowMs?: () => number,
 *   tmpDir?: string,
 *   randomId?: () => string,
 *   unlink?: (path: string) => void,
 *   platform?: string,
 *   log?: { warn?: Function },
 * }} options
 */
export function createHiddenDisplayReader({
    connection,
    run,
    spawn = nodeSpawn,
    sleep = defaultSleep,
    nowMs = () => Date.now(),
    tmpDir = tmpdir(),
    randomId = () => randomBytes(8).toString("hex"),
    unlink = (path) => unlinkSync(path),
    platform = process.platform,
    readTimeoutMs = READ_TIMEOUT_MS,
    displayWaitMs = DISPLAY_WAIT_MS,
    displayReadyDelayMs = DISPLAY_READY_DELAY_MS,
    wakeDelayMs = WAKE_DELAY_MS,
    retryDelayMs = RETRY_DELAY_MS,
    log,
} = {}) {
    if (!connection) throw new TypeError("createHiddenDisplayReader requires connection");
    if (typeof run !== "function") throw new TypeError("createHiddenDisplayReader requires run");

    // One read at a time: two scrcpy processes would race for the display id.
    let tail = Promise.resolve();

    function withLock(task) {
        const result = tail.then(task, task);
        tail = result.then(() => undefined, () => undefined);
        return result;
    }

    function expired(deadline) {
        return nowMs() >= deadline;
    }

    /** Bounded pause; false means the overall deadline was reached first. */
    async function pause(ms, deadline) {
        if (expired(deadline)) return false;
        const remaining = deadline - nowMs();
        await sleep(Math.max(0, Math.min(ms, remaining)));
        return !expired(deadline);
    }

    async function waitForDisplayId(output, deadline) {
        const waitDeadline = Math.min(deadline, nowMs() + displayWaitMs);
        for (let attempt = 0; attempt < MAX_DISPLAY_POLLS; attempt += 1) {
            const id = parseNewDisplayId(output.text);
            if (id !== null) return id;
            if (expired(deadline) || nowMs() >= waitDeadline) break;
            await sleep(DISPLAY_POLL_MS);
        }
        return parseNewDisplayId(output.text);
    }

    async function catRemote(adbPath, serial, remotePath) {
        for (const args of [
            ["-s", serial, "exec-out", "cat", remotePath],
            ["-s", serial, "shell", "cat", remotePath],
        ]) {
            try {
                const result = await run(adbPath, args, {
                    timeout: CAT_TIMEOUT_MS,
                    maxBuffer: MAX_XML_BYTES,
                });
                const xml = String(result?.stdout ?? "");
                if (xml.trim().length > 0) return xml;
            } catch {
                // Try the other transport; both can fail when the dump is empty.
            }
        }
        return "";
    }

    async function dumpOnce({ adbPath, serial, displayId, remoteXml, deadline }) {
        await run(
            adbPath,
            ["-s", serial, "shell", "input", "-d", String(displayId), "keyevent", "KEYCODE_WAKEUP"],
            { timeout: WAKE_TIMEOUT_MS },
        ).catch(() => null);
        if (!(await pause(wakeDelayMs, deadline))) return { ok: false, reason: "timeout" };
        await run(
            adbPath,
            ["-s", serial, "shell", "uiautomator", "dump", "--display", String(displayId), remoteXml],
            { timeout: DUMP_TIMEOUT_MS },
        ).catch(() => null);
        const xml = await catRemote(adbPath, serial, remoteXml);
        return { ok: true, xml };
    }

    async function killTree(child) {
        if (!child) return;
        const pid = child.pid;
        if (platform === "win32" && Number.isInteger(pid)) {
            try {
                await run("taskkill", ["/PID", String(pid), "/T", "/F"], { timeout: 5_000 });
            } catch {
                // The process may already be gone; child.kill below is the fallback.
            }
        }
        try {
            child.kill?.("SIGKILL");
        } catch {
            // Nothing left to kill.
        }
    }

    async function runRead({ packageName, profile }) {
        const paths = connection.paths();
        if (!paths.adb.found) return { ok: false, reason: "adb_missing" };
        if (!paths.scrcpy.found) return { ok: false, reason: "scrcpy_missing" };

        const link = await connection.ensureConnected();
        if (!link.ok) {
            return { ok: false, reason: "adb_unreachable", detail: link.reason || null };
        }
        const serial = link.serial;
        const adbPath = paths.adb.path;
        const deadline = nowMs() + readTimeoutMs;

        let child = null;
        let recordPath = null;
        let remoteXml = null;
        let dumpAttempted = false;

        try {
            recordPath = join(tmpDir, `idupi-hd-${randomId()}.mkv`);
            remoteXml = `/sdcard/idupi-hd-${randomId()}.xml`;
            const output = { text: "" };

            child = spawn(
                paths.scrcpy.path,
                scrcpyArguments({ serial, packageName, recordPath }),
                {
                    env: { ...process.env, ADB: adbPath },
                    windowsHide: true,
                    stdio: ["ignore", "pipe", "pipe"],
                },
            );
            const collect = (chunk) => {
                if (output.text.length >= MAX_OUTPUT_BYTES) return;
                output.text += String(chunk ?? "");
            };
            child?.stdout?.on?.("data", collect);
            child?.stderr?.on?.("data", collect);
            child?.on?.("error", () => {
                // spawn failures surface as "no display" below instead of a crash.
            });

            const displayId = await waitForDisplayId(output, deadline);
            if (displayId === null) {
                return { ok: false, reason: "no_display", detail: tailOf(output.text) };
            }
            if (!(await pause(displayReadyDelayMs, deadline))) {
                return { ok: false, reason: "timeout" };
            }

            let nodes = [];
            for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt += 1) {
                if (attempt > 0 && !(await pause(retryDelayMs, deadline))) {
                    return { ok: false, reason: "timeout" };
                }
                const dump = await dumpOnce({
                    adbPath,
                    serial,
                    displayId,
                    remoteXml,
                    deadline,
                });
                dumpAttempted = true;
                if (!dump.ok) return { ok: false, reason: dump.reason };
                nodes = parseUiXml(dump.xml);
                if (nodes.some((node) => node.packageName === packageName)) {
                    return {
                        ok: true,
                        packageName,
                        profile: profile || healthPackageInfo(packageName)?.source || null,
                        displayId,
                        xml: dump.xml,
                        nodes,
                    };
                }
            }
            return { ok: false, reason: "app_not_visible", displayId };
        } finally {
            if (dumpAttempted && serial && remoteXml) {
                try {
                    await run(adbPath, ["-s", serial, "shell", "rm", "-f", remoteXml], {
                        timeout: REMOVE_TIMEOUT_MS,
                    });
                } catch {
                    // A leftover dump is harmless and cleaned on the next run.
                }
            }
            await killTree(child);
            if (recordPath) {
                try {
                    unlink(recordPath);
                } catch {
                    // The temp file may never have been created.
                }
            }
        }
    }

    /**
     * Reads one allowlisted app on a hidden display. Never throws: a failure is
     * `{ok:false, reason}` with a bounded, value-free detail.
     */
    function readApp({ packageName, profile } = {}) {
        if (!isHealthPackage(packageName)) {
            return Promise.resolve({ ok: false, reason: "package_not_allowed" });
        }
        return withLock(async () => {
            try {
                return await runRead({ packageName, profile });
            } catch (err) {
                log?.warn?.(`[health] hidden read failed: ${err?.message || err}`);
                return { ok: false, reason: "read_failed" };
            }
        });
    }

    function listAllowed() {
        return HEALTH_PACKAGE_LIST.map((packageName) => ({
            packageName,
            ...allowedInfo(packageName),
        }));
    }

    return {
        readApp,
        listAllowed,
    };
}

function allowedInfo(packageName) {
    const info = healthPackageInfo(packageName) || {};
    return {
        label: info.label || packageName,
        source: info.source || null,
        tentative: info.tentative === true,
    };
}
