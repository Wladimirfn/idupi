// Accessibility rebind kick (phone-shell, T7b).
//
// On the owner's S24 Ultra an `install -r` of IDUPI (Android 16, One UI) leaves
// the app's accessibility service enabled but unbound until some UiAutomation
// connects and disconnects. Toggling `enabled_accessibility_services` and a
// plain restart do not help; a `uiautomator dump` does, because uiautomator
// itself is a UiAutomation client.
//
// So after an APK that replaces IDUPI is installed over adb, the PC asks the
// phone for one throwaway dump. The dump file is deleted in the same shell
// command, so nothing is left on the device.
//
// Everything here is best effort: the kick is a convenience that repairs a
// cosmetic state, never a step a release can fail on. A missing adb, an
// unreachable phone or a failing command is reported and the caller continues.
//
// The command runs with a fixed argv (never through a shell on the PC) and the
// runner and connection are injected, so tests never spawn adb.

import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

/** Where the throwaway dump is written before it is deleted in the same call. */
export const KICK_REMOTE_PATH = "/data/local/tmp/idupi-kick.xml";

/**
 * The single POSIX shell string `adb shell` receives. Keeping the dump and the
 * cleanup together means one round trip and no leftover file.
 */
export const KICK_SHELL_COMMAND =
    `uiautomator dump ${KICK_REMOTE_PATH}; rm -f ${KICK_REMOTE_PATH}`;

export const KICK_TIMEOUT_MS = 20_000;

function textOf(value) {
    return typeof value === "string" ? value : "";
}

/** Exact argv for one kick: `adb -s <serial> shell '<command>'`. */
export function kickArgv(serial) {
    return ["-s", String(serial), "shell", KICK_SHELL_COMMAND];
}

/**
 * @param {{
 *   connection?: { ensureConnected: Function, paths: Function },
 *   run?: (command: string, args: string[], options?: object) => Promise<unknown>,
 *   log?: { info?: Function, warn?: Function },
 *   timeoutMs?: number,
 * }} options
 */
export function createAccessibilityKick({
    connection = null,
    run = (command, args, options) => execFileAsync(command, args, {
        windowsHide: true,
        encoding: "utf8",
        ...options,
    }),
    log = console,
    timeoutMs = KICK_TIMEOUT_MS,
} = {}) {
    /**
     * Runs the kick once. Never throws: it answers what happened so the caller
     * can log it and move on.
     *
     * @param {{ serial?: string }} [options] an already connected serial; when
     *   omitted the injected connection resolves one.
     * @returns {Promise<{ ok: boolean, serial?: string, reason?: string, detail?: string }>}
     */
    async function kick({ serial = null } = {}) {
        let target = typeof serial === "string" && serial.trim().length > 0
            ? serial.trim()
            : null;

        if (target === null) {
            if (!connection || typeof connection.ensureConnected !== "function") {
                return { ok: false, reason: "no_connection" };
            }
            let link;
            try {
                link = await connection.ensureConnected();
            } catch (err) {
                const reason = (err && err.message) || "adb_unavailable";
                log?.info?.(`[phone] accessibility kick skipped: ${reason}`);
                return { ok: false, reason: "adb_unavailable" };
            }
            if (!link || link.ok !== true || typeof link.serial !== "string") {
                const reason = (link && link.reason) || "adb_unavailable";
                log?.info?.(`[phone] accessibility kick skipped: adb ${reason}`);
                return { ok: false, reason };
            }
            target = link.serial;
        }

        let adbPath = "adb";
        if (connection && typeof connection.paths === "function") {
            try {
                const resolved = connection.paths();
                if (resolved && resolved.adb && textOf(resolved.adb.path)) {
                    adbPath = resolved.adb.path;
                }
            } catch {
                // Fall back to the bare name; the run failure is reported below.
            }
        }

        try {
            const result = await run(adbPath, kickArgv(target), { timeout: timeoutMs });
            const detail = `${textOf(result?.stdout)}\n${textOf(result?.stderr)}`.trim();
            log?.info?.(`[phone] accessibility kick sent to ${target}`);
            return { ok: true, serial: target, detail };
        } catch (err) {
            const detail = `${textOf(err?.stdout)}\n${textOf(err?.stderr)}`.trim() ||
                textOf(err?.message) || "adb failed";
            log?.warn?.(`[phone] accessibility kick failed on ${target}: ${detail}`);
            return { ok: false, serial: target, reason: "kick_failed", detail };
        }
    }

    return { kick };
}
