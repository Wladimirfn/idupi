// Tests for the idupi-phone CLI helpers (phone-agent, T1): argument parsing
// and formatting only, no server and no network.
//
// Run (from repo root):
//   node --test idupi-server/test/idupi-phone-cli.test.mjs

import test from "node:test";
import assert from "node:assert/strict";

import {
    formatBytes,
    formatCommandList,
    formatCommandResult,
    formatDuration,
    formatPhoneStatus,
    parseArgs,
} from "../bin/idupi-phone.mjs";

test("parseArgs handles status, screen and their --json flag", () => {
    assert.deepEqual(parseArgs(["status"]), { command: "status", json: false });
    assert.deepEqual(parseArgs(["status", "--json"]), { command: "status", json: true });
    assert.deepEqual(parseArgs(["screen"]), { command: "screen", json: false });
    assert.deepEqual(parseArgs(["screen", "--json"]), { command: "screen", json: true });
    assert.equal(parseArgs(["status", "--nope"]).command, "help");
    assert.ok(parseArgs(["status", "extra"]).error);
});

test("parseArgs accepts the three tap target forms", () => {
    assert.deepEqual(parseArgs(["tap", "Iniciar", "sesión"]), {
        command: "tap",
        target: { text: "Iniciar sesión" },
    });
    assert.deepEqual(parseArgs(["tap", "--id", "com.app:id/ok"]), {
        command: "tap",
        target: { id: "com.app:id/ok" },
    });
    assert.deepEqual(parseArgs(["tap", "--xy", "10", "20"]), {
        command: "tap",
        target: { x: 10, y: 20 },
    });
    assert.deepEqual(parseArgs(["long-press", "Mensaje"]), {
        command: "long_press",
        target: { text: "Mensaje" },
    });
});

test("parseArgs rejects ambiguous or incomplete targets", () => {
    for (const argv of [
        ["tap"],
        ["tap", "texto", "--id", "x"],
        ["tap", "--xy", "10"],
        ["tap", "--xy", "a", "b"],
        ["tap", "--xy", "-1", "2"],
        ["long-press"],
        ["tap", "--unknown"],
    ]) {
        const parsed = parseArgs(argv);
        assert.equal(parsed.command, "help", argv.join(" "));
        assert.ok(parsed.error, argv.join(" "));
    }
});

test("parseArgs handles type, scroll, global shortcuts, open and wait", () => {
    assert.deepEqual(parseArgs(["type", "hola", "mundo"]), {
        command: "type",
        text: "hola mundo",
        submit: false,
    });
    assert.deepEqual(parseArgs(["type", "hola", "--submit"]), {
        command: "type",
        text: "hola",
        submit: true,
    });
    assert.equal(parseArgs(["type"]).command, "help");
    assert.equal(parseArgs(["type", "--submit"]).command, "help");

    assert.deepEqual(parseArgs(["scroll", "up"]), { command: "scroll", direction: "up" });
    assert.equal(parseArgs(["scroll"]).command, "help");
    assert.equal(parseArgs(["scroll", "sideways"]).command, "help");

    for (const action of ["back", "home", "recents", "notifications"]) {
        assert.deepEqual(parseArgs([action]), { command: "global", action });
    }
    assert.deepEqual(parseArgs(["quick-settings"]), {
        command: "global",
        action: "quick_settings",
    });
    assert.equal(parseArgs(["back", "extra"]).command, "help");

    assert.deepEqual(parseArgs(["open", "Google", "Maps"]), {
        command: "open",
        app: "Google Maps",
    });
    assert.equal(parseArgs(["open"]).command, "help");

    assert.deepEqual(parseArgs(["wait", "250"]), { command: "wait", ms: 250 });
    assert.equal(parseArgs(["wait", "10001"]).command, "help");
    assert.equal(parseArgs(["wait", "-1"]).command, "help");
    assert.equal(parseArgs(["wait"]).command, "help");
});

test("parseArgs covers log, help and unknown input", () => {
    assert.deepEqual(parseArgs(["log"]), { command: "log" });
    assert.equal(parseArgs(["log", "extra"]).command, "help");
    assert.deepEqual(parseArgs(["help"]), { command: "help" });
    assert.deepEqual(parseArgs(["--help"]), { command: "help" });
    assert.deepEqual(parseArgs(["-h"]), { command: "help" });

    const noArgs = parseArgs([]);
    assert.equal(noArgs.command, "help");
    assert.match(noArgs.error, /Falta el comando/);

    const unknown = parseArgs(["teleport"]);
    assert.equal(unknown.command, "help");
    assert.match(unknown.error, /Comando desconocido/);

    const flag = parseArgs(["--wat"]);
    assert.equal(flag.command, "help");
    assert.match(flag.error, /Opción desconocida/);
});

test("formatBytes renders binary units and degrades to ?", () => {
    assert.equal(formatBytes(0), "0 B");
    assert.equal(formatBytes(1024), "1.0 KB");
    assert.equal(formatBytes(8 * 1024 ** 3), "8.0 GB");
    assert.equal(formatBytes(undefined), "?");
    assert.equal(formatBytes(-5), "?");
});

test("formatDuration renders minutes and hours", () => {
    assert.equal(formatDuration(0), "0 min");
    assert.equal(formatDuration(42), "42 min");
    assert.equal(formatDuration(60), "1 h");
    assert.equal(formatDuration(65), "1 h 5 min");
    assert.equal(formatDuration("nope"), "?");
});

test("formatPhoneStatus prints the full summary", () => {
    const payload = {
        online: true,
        lastSeenAt: "2026-11-24T11:59:48.000Z",
        latest: {
            receivedAt: "2026-11-24T11:59:48.000Z",
            battery: { percent: 87, charging: true, temperatureC: 31.24 },
            signal: { level: 4, dbm: -78, operator: "Claro", networkType: "LTE" },
            network: { kind: "wifi", ssid: "Casa", rssiDbm: -55 },
            memory: { usedBytes: 4 * 1024 ** 3, totalBytes: 8 * 1024 ** 3 },
            storage: { usedBytes: 48 * 1024 ** 3, totalBytes: 128 * 1024 ** 3 },
            foregroundApp: { label: "WhatsApp", packageName: "com.whatsapp" },
            topAppsToday: [
                { label: "WhatsApp", minutes: 42 },
                { label: "Chrome", minutes: 65 },
            ],
        },
    };

    const text = formatPhoneStatus(payload, Date.parse("2026-11-24T12:00:00.000Z"));
    assert.match(text, /^Teléfono: en línea \(visto hace 12 s\)$/m);
    assert.match(text, /^Batería: 87% \(cargando\) 31\.2 °C$/m);
    assert.match(text, /^Señal: 4\/5 · -78 dBm · Claro · LTE$/m);
    assert.match(text, /^Red: Wi-Fi "Casa" \(-55 dBm\)$/m);
    assert.match(text, /^Memoria: 4\.0 GB de 8\.0 GB \(50%\)$/m);
    assert.match(text, /^Almacenamiento: 48\.0 GB de 128\.0 GB \(38%\)$/m);
    assert.match(text, /^App en primer plano: WhatsApp \(com\.whatsapp\)$/m);
    assert.match(text, /Más usadas hoy:\n  - WhatsApp: 42 min\n  - Chrome: 1 h 5 min/);
});

test("formatPhoneStatus handles missing data and offline phones", () => {
    assert.equal(formatPhoneStatus({ latest: null }), "Sin datos del teléfono todavía.");
    assert.equal(formatPhoneStatus(null), "Sin datos del teléfono todavía.");

    const offline = formatPhoneStatus(
        {
            online: false,
            lastSeenAt: "2026-11-24T11:50:00.000Z",
            latest: {
                receivedAt: "2026-11-24T11:50:00.000Z",
                battery: { percent: 40, charging: false },
                network: { kind: "mobile" },
            },
        },
        Date.parse("2026-11-24T12:00:00.000Z"),
    );
    assert.match(offline, /^Teléfono: sin conexión \(visto hace 10 min\)$/m);
    assert.match(offline, /^Batería: 40%$/m);
    assert.match(offline, /^Red: datos móviles$/m);
    assert.doesNotMatch(offline, /Señal:/);
    assert.doesNotMatch(offline, /Memoria:/);
});

test("formatCommandResult reports each terminal state", () => {
    assert.equal(
        formatCommandResult({ state: "done", result: { message: "WhatsApp abierto" } }),
        "Listo: WhatsApp abierto",
    );
    assert.equal(
        formatCommandResult({ state: "done", result: { text: "pantalla" } }),
        "pantalla",
    );
    assert.equal(formatCommandResult({ state: "done", result: null }), "Listo.");
    assert.equal(
        formatCommandResult({ state: "failed", error: "no node matched" }),
        "El teléfono falló: no node matched",
    );
    assert.match(formatCommandResult({ state: "expired" }), /expiró/);
    assert.match(
        formatCommandResult({ state: "delivered" }),
        /El teléfono no respondió: ¿está activo el control desde la PC\?/,
    );
    assert.match(formatCommandResult(null), /El teléfono no respondió/);
});

test("formatCommandList prints recent commands and failures", () => {
    assert.equal(formatCommandList([]), "No hay comandos.");
    assert.equal(formatCommandList(null), "No hay comandos.");

    const text = formatCommandList([
        {
            id: "phc-aaaaaaaaaaaa",
            state: "done",
            type: "screen",
            createdAt: "2026-11-24T12:00:00.000Z",
        },
        {
            id: "phc-bbbbbbbbbbbb",
            state: "failed",
            type: "tap",
            createdAt: "2026-11-24T12:01:00.000Z",
            error: "no node matched",
        },
    ]);
    assert.match(text, /^Comandos recientes \(2\):$/m);
    assert.match(text, /phc-aaaaaaaaaaaa\s+done\s+screen\s+2026-11-24T12:00:00\.000Z/);
    assert.match(text, /phc-bbbbbbbbbbbb\s+failed\s+tap\s+2026-11-24T12:01:00\.000Z — no node matched/);
});
