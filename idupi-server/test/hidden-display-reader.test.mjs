// Tests for the hidden virtual display reader (phone-health, T1). Nothing here
// starts scrcpy, adb or taskkill: the child process, runner, clock and sleep are
// injected fakes.
//
// Run (from repo root):
//   node --test idupi-server/test/hidden-display-reader.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";

import {
    createHiddenDisplayReader,
    decodeXmlEntities,
    parseBounds,
    parseNewDisplayId,
    parseUiXml,
    scrcpyArguments,
} from "../lib/hidden-display-reader.mjs";

const PKG = "com.huawei.health";
const SERIAL = "192.168.1.50:37123";
const ADB = "C:\\sdk\\adb.exe";
const SCRCPY = "C:\\tools\\scrcpy.exe";

function nodeXml(packageName, text = "Hoy") {
    return [
        "<hierarchy rotation=\"0\">",
        `<node index="0" text="${text}" resource-id="${packageName}:id/step_text" `,
        `class="android.widget.TextView" package="${packageName}" `,
        "content-desc=\"\" bounds=\"[0,0][100,50]\" />",
        "</hierarchy>",
    ].join("");
}

function makeChild() {
    const child = new EventEmitter();
    child.pid = 4242;
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    child.killed = false;
    child.kill = () => {
        child.killed = true;
    };
    return child;
}

function baseConnection({ connected = true } = {}) {
    return {
        paths: () => ({
            adb: { path: ADB, found: true, source: "env" },
            scrcpy: { path: SCRCPY, found: true, source: "env" },
        }),
        ensureConnected: async () => (
            connected
                ? { ok: true, serial: SERIAL, via: "cached" }
                : { ok: false, reason: "unreachable" }
        ),
    };
}

function makeHarness(t, {
    displayOutput = "INFO: New display: 1080x2340/440 (id=3)\n",
    xmls = [nodeXml(PKG)],
    connected = true,
    clock = null,
    displayWaitMs,
    launcherOutput = "",
} = {}) {
    const calls = { run: [], spawn: [], unlink: [] };
    let dumpCount = 0;
    const children = [];

    async function run(command, args) {
        calls.run.push({ command, args: [...args] });
        if (command === "taskkill") return { stdout: "", stderr: "" };
        if (args[0] === "-s" && args[2] === "shell" && args[3] === "cmd") {
            return { stdout: launcherOutput, stderr: "" };
        }
        if (args[0] === "-s" && args[2] === "shell" && args[3] === "input") {
            return { stdout: "", stderr: "" };
        }
        if (args[0] === "-s" && args[2] === "shell" && args[3] === "uiautomator") {
            dumpCount += 1;
            return { stdout: "UI hierchary dumped to", stderr: "" };
        }
        if (args[0] === "-s" && args[2] === "exec-out") {
            const xml = xmls[Math.min(dumpCount - 1, xmls.length - 1)] || "";
            return { stdout: xml, stderr: "" };
        }
        if (args[0] === "-s" && args[2] === "shell" && args[3] === "rm") {
            return { stdout: "", stderr: "" };
        }
        return { stdout: "", stderr: "" };
    }

    function spawn(command, args, options) {
        const child = makeChild();
        calls.spawn.push({ command, args: [...args], options });
        children.push(child);
        queueMicrotask(() => child.stdout.emit("data", displayOutput));
        return child;
    }

    let clockIndex = 0;
    const nowMs = clock
        ? () => clock[Math.min(clockIndex++, clock.length - 1)]
        : () => Date.now();

    const reader = createHiddenDisplayReader({
        connection: baseConnection({ connected }),
        run,
        spawn,
        sleep: () => Promise.resolve(),
        nowMs,
        tmpDir: "C:\\temp",
        randomId: () => "abcd1234",
        unlink: (path) => calls.unlink.push(path),
        platform: "win32",
        ...(displayWaitMs === undefined ? {} : { displayWaitMs }),
    });
    t.after(() => {
        for (const child of children) child.killed = true;
    });
    return { reader, calls, dumpCount: () => dumpCount, children };
}

// --- launcher relaunch (locked phone) --------------------------------------

test("the resolved launcher is started on the woken hidden display", async (t) => {
    const { reader, calls } = makeHarness(t, {
        launcherOutput: "priority=0 preferredOrder=0\ncom.huawei.health/.MainActivity\n",
    });
    const result = await reader.readApp({ packageName: PKG });
    assert.equal(result.ok, true);
    const start = calls.run.find((call) => call.args[3] === "am");
    assert.deepEqual(start.args, ["-s", SERIAL, "shell", "am", "start", "--display", "3", "-n", "com.huawei.health/.MainActivity"]);
    const wakeIndex = calls.run.findIndex((call) => call.args[3] === "input");
    const startIndex = calls.run.findIndex((call) => call.args[3] === "am");
    const dumpIndex = calls.run.findIndex((call) => call.args[3] === "uiautomator");
    assert.ok(wakeIndex < startIndex && startIndex < dumpIndex, "wake, then start, then dump");
});

test("a launcher outside the allowlisted package is never started", async (t) => {
    const { reader, calls } = makeHarness(t, { launcherOutput: "com.evil.app/.Main\n" });
    await reader.readApp({ packageName: PKG });
    assert.equal(calls.run.some((call) => call.args[3] === "am"), false);
});

// --- pure helpers ----------------------------------------------------------

test("parseNewDisplayId reads both scrcpy output streams", () => {
    assert.equal(parseNewDisplayId("New display: 1080x2340/440 (id=3)"), 3);
    assert.equal(parseNewDisplayId("INFO: New display: 1x1 (id=12)\n"), 12);
    assert.equal(parseNewDisplayId("no display here"), null);
});

test("parseBounds turns the uiautomator rect into numbers", () => {
    assert.deepEqual(parseBounds("[0,120][1080,240]"), { x1: 0, y1: 120, x2: 1080, y2: 240 });
    assert.equal(parseBounds("nope"), null);
});

test("decodeXmlEntities handles named and numeric entities", () => {
    assert.equal(decodeXmlEntities("a &lt; b &amp; c &gt; d"), "a < b & c > d");
    assert.equal(decodeXmlEntities("l&iacute;nea&#10;siguiente"), "l&iacute;nea\nsiguiente");
    assert.equal(decodeXmlEntities("&#x41;&#66;"), "AB");
    assert.equal(decodeXmlEntities("&unknown;"), "&unknown;");
});

test("parseUiXml extracts the documented node fields", () => {
    const xml =
        '<hierarchy><node text="2,444/10,000 pasos" resource-id="com.huawei.health:id/step_text" ' +
        'content-desc="pasos &amp; meta" package="com.huawei.health" bounds="[0,10][500,60]" />' +
        '<node text="Sue&#241;o" package="com.huawei.health" bounds="[0,70][500,120]" /></hierarchy>';
    const nodes = parseUiXml(xml);
    assert.equal(nodes.length, 2);
    assert.deepEqual(nodes[0], {
        text: "2,444/10,000 pasos",
        resourceId: "com.huawei.health:id/step_text",
        contentDesc: "pasos & meta",
        packageName: "com.huawei.health",
        bounds: "[0,10][500,60]",
        rect: { x1: 0, y1: 10, x2: 500, y2: 60 },
    });
    assert.equal(nodes[1].text, "Sueño");
    assert.equal(nodes[1].resourceId, "");
});

test("scrcpyArguments is the verified fixed argv", () => {
    assert.deepEqual(
        scrcpyArguments({ serial: SERIAL, packageName: PKG, recordPath: "C:\\temp\\x.mkv" }),
        [
            "-s",
            SERIAL,
            "--new-display=1080x2340/440",
            `--start-app=${PKG}`,
            "--no-window",
            "--no-audio",
            "--record=C:\\temp\\x.mkv",
            "--max-fps=5",
            "--video-bit-rate=500K",
        ],
    );
});

// --- read flow -------------------------------------------------------------

test("readApp launches scrcpy, wakes the display, dumps and cleans up", async (t) => {
    const harness = makeHarness(t);
    const result = await harness.reader.readApp({ packageName: PKG, profile: "huawei_health" });

    assert.equal(result.ok, true);
    assert.equal(result.packageName, PKG);
    assert.equal(result.displayId, 3);
    assert.equal(result.nodes.length, 1);

    const spawned = harness.calls.spawn[0];
    assert.equal(spawned.command, SCRCPY);
    assert.equal(spawned.args[0], "-s");
    assert.equal(spawned.args[1], SERIAL);
    assert.ok(spawned.args.includes(`--start-app=${PKG}`));
    assert.ok(spawned.args.some((arg) => arg.startsWith("--record=")));
    assert.equal(spawned.options.env.ADB, ADB);
    assert.equal(spawned.options.windowsHide, true);

    const wake = harness.calls.run.find(
        (call) => call.args[2] === "shell" && call.args[3] === "input",
    );
    assert.deepEqual(wake.args.slice(4), ["-d", "3", "keyevent", "KEYCODE_WAKEUP"]);

    const dump = harness.calls.run.find(
        (call) => call.args[2] === "shell" && call.args[3] === "uiautomator",
    );
    assert.deepEqual(dump.args.slice(5, 8), ["--display", "3", "/sdcard/idupi-hd-abcd1234.xml"]);

    const remove = harness.calls.run.find(
        (call) => call.args[2] === "shell" && call.args[3] === "rm",
    );
    assert.ok(remove, "the remote dump must be deleted");

    assert.deepEqual(harness.calls.unlink, ["C:\\temp\\idupi-hd-abcd1234.mkv"]);
    const taskkill = harness.calls.run.find((call) => call.command === "taskkill");
    assert.deepEqual(taskkill.args, ["/PID", "4242", "/T", "/F"]);
    assert.equal(harness.children[0].killed, true);
});

test("readApp retries wake+dump once when the app is not visible yet", async (t) => {
    const harness = makeHarness(t, {
        xmls: [nodeXml("com.android.systemui"), nodeXml(PKG)],
    });
    const result = await harness.reader.readApp({ packageName: PKG });
    assert.equal(result.ok, true);
    assert.equal(harness.dumpCount(), 2);
});

test("readApp fails app_not_visible after the retry and still cleans up", async (t) => {
    const harness = makeHarness(t, {
        xmls: [nodeXml("com.android.systemui"), nodeXml("com.android.systemui")],
    });
    const result = await harness.reader.readApp({ packageName: PKG });
    assert.deepEqual(
        { ok: result.ok, reason: result.reason },
        { ok: false, reason: "app_not_visible" },
    );
    assert.equal(harness.dumpCount(), 2);
    assert.equal(harness.calls.unlink.length, 1);
    assert.ok(harness.calls.run.some((call) => call.args[3] === "rm"));
    assert.equal(harness.children[0].killed, true);
});

test("readApp rejects packages outside the allowlist without spawning", async (t) => {
    const harness = makeHarness(t);
    const result = await harness.reader.readApp({ packageName: "com.example.health" });
    assert.deepEqual(result, { ok: false, reason: "package_not_allowed" });
    assert.equal(harness.calls.spawn.length, 0);
});

test("readApp reports an unreachable phone without spawning", async (t) => {
    const harness = makeHarness(t, { connected: false });
    const result = await harness.reader.readApp({ packageName: PKG });
    assert.equal(result.ok, false);
    assert.equal(result.reason, "adb_unreachable");
    assert.equal(harness.calls.spawn.length, 0);
});

test("readApp serializes concurrent reads", async (t) => {
    let active = 0;
    let maxActive = 0;
    const reader = createHiddenDisplayReader({
        connection: baseConnection(),
        run: async (command, args, options) => {
            active += 1;
            maxActive = Math.max(maxActive, active);
            await new Promise((resolve) => setImmediate(resolve));
            try {
                if (command === "taskkill") return { stdout: "", stderr: "" };
                if (args[3] === "uiautomator") return { stdout: "dumped", stderr: "" };
                if (args[2] === "exec-out") return { stdout: nodeXml(PKG), stderr: "" };
                return { stdout: "", stderr: "" };
            } finally {
                active -= 1;
            }
        },
        spawn: (command, args, options) => {
            const child = makeChild();
            queueMicrotask(() => child.stdout.emit("data", "New display: x (id=2)\n"));
            return child;
        },
        sleep: () => Promise.resolve(),
        tmpDir: "C:\\temp",
        randomId: () => "abcd1234",
        unlink: () => {},
        platform: "win32",
    });

    const [first, second] = await Promise.all([
        reader.readApp({ packageName: PKG }),
        reader.readApp({ packageName: PKG }),
    ]);
    assert.equal(first.ok, true);
    assert.equal(second.ok, true);
    assert.equal(maxActive, 1, "a second read must wait for the first one");
});

test("readApp aborts with timeout and still removes the temp recording", async (t) => {
    const harness = makeHarness(t, {
        clock: [0, 1000, 2000, 3000, 200_000],
    });
    const result = await harness.reader.readApp({ packageName: PKG });
    assert.equal(result.ok, false);
    assert.equal(result.reason, "timeout");
    assert.deepEqual(harness.calls.unlink, ["C:\\temp\\idupi-hd-abcd1234.mkv"]);
    assert.equal(harness.children[0].killed, true);
    assert.equal(
        harness.calls.run.some((call) => call.args[3] === "rm"),
        false,
        "no dump was attempted, so nothing remote needs deleting",
    );
});
