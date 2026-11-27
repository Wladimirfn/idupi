// Tests for the accessibility rebind kick (phone-shell, T7b).
//
// On the owner's S24 an `install -r` of IDUPI leaves its accessibility service
// enabled but unbound until some UiAutomation connects and disconnects, so the
// PC runs one throwaway `uiautomator dump` right after installing. The kick must
// always be best effort: a missing adb, an unreachable phone or a failing
// command is reported and never thrown.
//
// Run (from repo root):
//   node --test idupi-server/test/accessibility-kick.test.mjs

import test from "node:test";
import assert from "node:assert/strict";

import {
    KICK_REMOTE_PATH,
    KICK_SHELL_COMMAND,
    createAccessibilityKick,
    kickArgv,
} from "../lib/accessibility-kick.mjs";

const silentLog = { log() {}, info() {}, warn() {}, error() {} };
const ADB = "C:\\sdk\\adb.exe";
const SERIAL = "192.168.1.8:37123";

/** Records each run call and answers what the test asks for. */
function runRecorder(answer = { stdout: "UI hierchary dumped to: /data/local/tmp", stderr: "" }) {
    const calls = [];
    const run = async (command, args, options) => {
        calls.push({ command, args, options });
        if (answer instanceof Error) throw answer;
        return answer;
    };
    return { run, calls };
}

function connection({ serial = SERIAL, ok = true, reason = null } = {}) {
    return {
        ensureConnected: async () => (ok ? { ok: true, serial } : { ok: false, reason }),
        paths: () => ({ adb: { path: ADB, found: true } }),
    };
}

test("the kick is one adb shell call that dumps and deletes in the same breath", () => {
    assert.equal(KICK_SHELL_COMMAND, `uiautomator dump ${KICK_REMOTE_PATH}; rm -f ${KICK_REMOTE_PATH}`);
    assert.deepEqual(kickArgv(SERIAL), ["-s", SERIAL, "shell", KICK_SHELL_COMMAND]);
    // Never a free-form argv: the whole shell string is one argument.
    assert.equal(kickArgv(SERIAL).length, 4);
});

test("kick resolves the serial through the connection and runs the exact argv", async () => {
    const recorder = runRecorder();
    const kick = createAccessibilityKick({
        connection: connection(),
        run: recorder.run,
        log: silentLog,
    });

    const outcome = await kick.kick();

    assert.equal(outcome.ok, true);
    assert.equal(outcome.serial, SERIAL);
    assert.equal(recorder.calls.length, 1);
    assert.equal(recorder.calls[0].command, ADB);
    assert.deepEqual(recorder.calls[0].args, ["-s", SERIAL, "shell", KICK_SHELL_COMMAND]);
    assert.equal(recorder.calls[0].options.timeout, 20_000);
});

test("kick reuses an already connected serial without asking the connection", async () => {
    const recorder = runRecorder();
    let asked = 0;
    const kick = createAccessibilityKick({
        connection: {
            ensureConnected: async () => {
                asked += 1;
                return { ok: true, serial: "other" };
            },
            paths: () => ({ adb: { path: ADB, found: true } }),
        },
        run: recorder.run,
        log: silentLog,
    });

    const outcome = await kick.kick({ serial: SERIAL });

    assert.equal(outcome.ok, true);
    assert.equal(asked, 0);
    assert.deepEqual(recorder.calls[0].args, ["-s", SERIAL, "shell", KICK_SHELL_COMMAND]);
});

test("an unreachable phone skips the kick without throwing", async () => {
    const recorder = runRecorder();
    const kick = createAccessibilityKick({
        connection: connection({ ok: false, reason: "unreachable" }),
        run: recorder.run,
        log: silentLog,
    });

    const outcome = await kick.kick();

    assert.equal(outcome.ok, false);
    assert.equal(outcome.reason, "unreachable");
    assert.equal(recorder.calls.length, 0);
});

test("a throwing connection is reported, never propagated", async () => {
    const recorder = runRecorder();
    const kick = createAccessibilityKick({
        connection: {
            ensureConnected: async () => {
                throw new Error("adb server died");
            },
            paths: () => ({ adb: { path: ADB, found: true } }),
        },
        run: recorder.run,
        log: silentLog,
    });

    const outcome = await kick.kick();

    assert.equal(outcome.ok, false);
    assert.equal(outcome.reason, "adb_unavailable");
    assert.equal(recorder.calls.length, 0);
});

test("a failing adb command is logged and returned, not thrown", async () => {
    const failure = new Error("adb exited with 1");
    failure.stderr = "error: device offline";
    const recorder = runRecorder(failure);
    const warnings = [];
    const kick = createAccessibilityKick({
        connection: connection(),
        run: recorder.run,
        log: { ...silentLog, warn: (line) => warnings.push(line) },
    });

    const outcome = await kick.kick();

    assert.equal(outcome.ok, false);
    assert.equal(outcome.reason, "kick_failed");
    assert.equal(outcome.serial, SERIAL);
    assert.match(outcome.detail, /device offline/);
    assert.equal(warnings.length, 1);
});

test("without any connection the kick is a no-op result", async () => {
    const recorder = runRecorder();
    const kick = createAccessibilityKick({ run: recorder.run, log: silentLog });

    assert.deepEqual(await kick.kick(), { ok: false, reason: "no_connection" });
    assert.equal(recorder.calls.length, 0);
});
