// Tests for the idupi-phone CLI helpers (phone-agent, T1): argument parsing
// and formatting only, no server and no network.
//
// Run (from repo root):
//   node --test idupi-server/test/idupi-phone-cli.test.mjs

import test from "node:test";
import assert from "node:assert/strict";

import {
    failureMessage,
    formatApprovals,
    formatApprovalWait,
    formatBytes,
    formatCalendarAgenda,
    formatCalendarFree,
    formatCalendarProposals,
    formatCommandList,
    formatCommandResult,
    formatDuration,
    formatHealthAdb,
    formatHealthDigest,
    formatHealthMetrics,
    formatHealthRefresh,
    formatHealthSources,
    formatHealthSummary,
    formatPhoneStatus,
    formatProposalCreated,
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

// --- calendar CLI (phone-calendar, T1) -------------------------------------

test("parseArgs handles the calendar subcommands and their options", () => {
    assert.deepEqual(parseArgs(["calendar"]), {
        command: "calendar",
        action: "agenda",
        days: 7,
        json: false,
    });
    assert.deepEqual(parseArgs(["calendar", "agenda", "--days", "3", "--json"]), {
        command: "calendar",
        action: "agenda",
        days: 3,
        json: true,
    });
    // "agenda" is optional before flags.
    assert.deepEqual(parseArgs(["calendar", "--days", "3"]), {
        command: "calendar",
        action: "agenda",
        days: 3,
        json: false,
    });
    assert.deepEqual(parseArgs(["calendar", "free"]), {
        command: "calendar",
        action: "free",
        days: 7,
        min: 30,
    });
    assert.deepEqual(parseArgs(["calendar", "free", "--days", "2", "--min", "60"]), {
        command: "calendar",
        action: "free",
        days: 2,
        min: 60,
    });
    assert.deepEqual(parseArgs(["calendar", "proposals"]), {
        command: "calendar",
        action: "proposals",
    });

    assert.deepEqual(
        parseArgs([
            "calendar", "propose",
            "--title", "Almuerzo con Ana",
            "--start", "2026-11-25T13:00:00-03:00",
            "--end", "2026-11-25T14:00:00-03:00",
            "--all-day",
            "--location", "Centro",
            "--notes", "Llevar el informe",
        ]),
        {
            command: "calendar",
            action: "propose",
            title: "Almuerzo con Ana",
            start: "2026-11-25T13:00:00-03:00",
            end: "2026-11-25T14:00:00-03:00",
            allDay: true,
            location: "Centro",
            notes: "Llevar el informe",
        },
    );
});

test("parseArgs rejects invalid calendar commands and options", () => {
    for (const argv of [
        ["calendar", "what"],
        ["calendar", "agenda", "--days", "0"],
        ["calendar", "agenda", "--days", "31"],
        ["calendar", "agenda", "--bogus"],
        ["calendar", "free", "--min", "0"],
        ["calendar", "free", "--min", "1441"],
        ["calendar", "proposals", "extra"],
        ["calendar", "propose"],
        ["calendar", "propose", "--title", "x"],
        ["calendar", "propose", "--title", "x", "--start", "mañana"],
        ["calendar", "propose", "--title", "x", "--start", "2026-11-25T13:00:00Z", "--end", "nope"],
        ["calendar", "propose", "--title", "x", "--start", "2026-11-25T13:00:00Z", "--bogus"],
    ]) {
        const parsed = parseArgs(argv);
        assert.equal(parsed.command, "help", argv.join(" "));
        assert.ok(parsed.error, argv.join(" "));
    }
});

test("agenda formatter groups by local day and flags conflicts", () => {
    const text = formatCalendarAgenda({
        capturedAt: "2026-11-24T12:00:00.000Z",
        tzOffsetMinutes: -180,
        calendars: [{ id: "cal-1", name: "Personal" }],
        events: [
            {
                id: "ev-1",
                calendarId: "cal-1",
                title: "Reunión",
                start: "2026-11-24T13:00:00.000Z",
                end: "2026-11-24T14:00:00.000Z",
                allDay: false,
                location: "Oficina",
            },
            {
                id: "ev-2",
                calendarId: "cal-1",
                title: "Almuerzo",
                start: "2026-11-24T13:30:00.000Z",
                end: "2026-11-24T14:30:00.000Z",
                allDay: false,
                location: "",
            },
            {
                id: "ev-3",
                calendarId: "cal-1",
                title: "Feriado",
                start: "2026-11-25T03:00:00.000Z",
                end: "2026-11-26T03:00:00.000Z",
                allDay: true,
                location: "",
            },
        ],
        conflicts: [{
            a: { id: "ev-1", title: "Reunión", start: "2026-11-24T13:00:00.000Z", end: "2026-11-24T14:00:00.000Z" },
            b: { id: "ev-2", title: "Almuerzo", start: "2026-11-24T13:30:00.000Z", end: "2026-11-24T14:30:00.000Z" },
            start: "2026-11-24T13:30:00.000Z",
            end: "2026-11-24T14:00:00.000Z",
        }],
    });

    const lines = text.split("\n");
    assert.equal(lines[0], "Agenda: 3 eventos (capturada mar 24 nov 09:00)");
    assert.ok(lines.includes("mar 24 nov"));
    assert.ok(lines.includes("mié 25 nov"));
    assert.ok(lines.some((line) => line.includes("10:00-11:00") && line.includes("Reunión")));
    assert.ok(lines.some((line) => line.includes("Reunión") && line.includes("(conflicto)")));
    assert.ok(lines.some((line) => line.includes("todo el día") && line.includes("Feriado")));
    assert.ok(lines.some((line) => line.startsWith("Conflictos: 1")));
    assert.ok(lines.some((line) => line.includes('"Reunión"') && line.includes('"Almuerzo"')));

    assert.match(
        formatCalendarAgenda({ capturedAt: null }),
        /Sin datos del calendario todavía/,
    );
    assert.match(
        formatCalendarAgenda({ capturedAt: "2026-11-24T12:00:00.000Z", events: [], conflicts: [] }),
        /No hay eventos en el rango\./,
    );
});

test("free formatter shows slots per day and the no-snapshot message", () => {
    const text = formatCalendarFree({
        from: "2026-11-24T11:00:00.000Z",
        to: "2026-11-24T23:00:00.000Z",
        minMinutes: 30,
        tzOffsetMinutes: -180,
        hasSnapshot: true,
        slots: [
            { start: "2026-11-24T11:00:00.000Z", end: "2026-11-24T13:00:00.000Z", minutes: 120 },
            { start: "2026-11-24T14:30:00.000Z", end: "2026-11-24T23:00:00.000Z", minutes: 510 },
        ],
    });
    assert.match(text, /^Espacios libres \(mínimo 30 min\):$/m);
    assert.ok(text.includes("mar 24 nov"));
    assert.ok(text.includes("08:00-10:00 (2 h)"));
    assert.ok(text.includes("11:30-20:00 (8 h 30 min)"));

    assert.match(formatCalendarFree({ hasSnapshot: false }), /Sin datos del calendario todavía/);
    assert.match(
        formatCalendarFree({ hasSnapshot: true, minMinutes: 30, slots: [] }),
        /No hay espacios libres en el rango\./,
    );
});

test("proposal formatters show the pending state, conflicts and status labels", () => {
    const created = formatProposalCreated({
        tzOffsetMinutes: -180,
        proposal: {
            id: "phr-aaaaaaaaaaaa",
            title: "Almuerzo",
            start: "2026-11-24T13:30:00.000Z",
            end: "2026-11-24T14:30:00.000Z",
            allDay: false,
            location: "Centro",
            status: "pending",
            conflicts: [{
                title: "Reunión",
                start: "2026-11-24T13:00:00.000Z",
                end: "2026-11-24T14:00:00.000Z",
            }],
        },
    });
    assert.ok(created.includes("Propuesta creada: phr-aaaaaaaaaaaa"));
    assert.ok(created.includes("10:30-11:30"));
    assert.ok(created.includes("pendiente"));
    assert.ok(created.includes("apruebas en el teléfono"));
    assert.ok(created.includes('Conflictos: 1'));
    assert.ok(created.includes('"Reunión"'));

    const list = formatCalendarProposals({
        tzOffsetMinutes: -180,
        proposals: [
            {
                id: "phr-aaaaaaaaaaaa",
                title: "Almuerzo",
                start: "2026-11-24T13:30:00.000Z",
                end: "2026-11-24T14:30:00.000Z",
                allDay: false,
                status: "pending",
                conflicts: [{ title: "Reunión", start: "2026-11-24T13:00:00.000Z", end: "2026-11-24T14:00:00.000Z" }],
            },
            {
                id: "phr-bbbbbbbbbbbb",
                title: "Revisión",
                start: "2026-11-25T13:00:00.000Z",
                end: "2026-11-25T14:00:00.000Z",
                allDay: false,
                status: "approved",
                conflicts: [],
            },
        ],
    });
    assert.match(list, /^Propuestas \(2\):$/m);
    assert.ok(list.includes("pendiente"));
    assert.ok(list.includes("aprobada"));
    assert.ok(list.includes("(1 conflicto)"));

    assert.equal(formatCalendarProposals({ proposals: [] }), "No hay propuestas.");
});

// --- health CLI (phone-health, T1) ----------------------------------------

test("parseArgs handles health summary, sources, refresh and adb", () => {
    assert.deepEqual(parseArgs(["health"]), {
        command: "health",
        action: "summary",
        days: 7,
        json: false,
    });
    assert.deepEqual(parseArgs(["health", "summary", "--days", "30", "--json"]), {
        command: "health",
        action: "summary",
        days: 30,
        json: true,
    });
    assert.deepEqual(parseArgs(["health", "--days", "3"]), {
        command: "health",
        action: "summary",
        days: 3,
        json: false,
    });
    assert.deepEqual(parseArgs(["health", "sources"]), {
        command: "health",
        action: "sources",
        json: false,
    });
    assert.deepEqual(parseArgs(["health", "refresh", "--wait"]), {
        command: "health",
        action: "refresh",
        wait: true,
        json: false,
    });
    assert.deepEqual(parseArgs(["health", "adb"]), {
        command: "health",
        action: "adb",
        adbAction: "status",
    });
    assert.deepEqual(parseArgs(["health", "adb", "pair", "192.168.1.50:37123", "123456"]), {
        command: "health",
        action: "adb",
        adbAction: "pair",
        hostPort: "192.168.1.50:37123",
        code: "123456",
    });
    assert.deepEqual(parseArgs(["health", "adb", "connect", "100.101.1.2:41000"]), {
        command: "health",
        action: "adb",
        adbAction: "connect",
        hostPort: "100.101.1.2:41000",
    });
});

test("parseArgs rejects malformed health commands", () => {
    for (const argv of [
        ["health", "summary", "--days", "0"],
        ["health", "summary", "--days", "500"],
        ["health", "summary", "--nope"],
        ["health", "sources", "extra"],
        ["health", "refresh", "--nope"],
        ["health", "adb", "pair", "192.168.1.50:37123"],
        ["health", "adb", "pair", "not-a-host", "123456"],
        ["health", "adb", "pair", "192.168.1.50:37123", "12ab"],
        ["health", "adb", "connect"],
        ["health", "adb", "connect", "bad host:1"],
        ["health", "adb", "unknown"],
        ["health", "unknown"],
    ]) {
        const parsed = parseArgs(argv);
        assert.equal(parsed.command, "help", argv.join(" "));
        assert.ok(parsed.error, argv.join(" "));
    }
});

test("formatHealthSummary renders days with units and goals", () => {
    const payload = {
        rangeDays: 7,
        tzOffsetMinutes: -180,
        days: [
            { date: "2026-11-23", metrics: {}, sources: {}, capturedAt: null },
            {
                date: "2026-11-24",
                metrics: {
                    steps: 2444,
                    stepsGoal: 10000,
                    sleepMinutes: 509,
                    heartRateBpm: 72,
                    exerciseMinutes: 15,
                    exerciseGoalMinutes: 30,
                    waterMl: 2000,
                    waterGoalMl: 3000,
                    stress: 35,
                    stressLabel: "bajo",
                    weightKg: 75.5,
                    screenTimeMinutes: 200,
                    unlocks: 40,
                },
                sources: { steps: "huawei_health" },
                capturedAt: "2026-11-24T10:00:00.000Z",
            },
        ],
        latest: null,
    };
    const text = formatHealthSummary(payload, Date.parse("2026-11-24T15:00:00.000Z"));
    assert.match(text, /^Salud \(7 días\):$/m);
    assert.match(text, /^Hoy \(2026-11-24\)$/m);
    assert.ok(text.includes("Pasos: 2,444 de 10,000"));
    assert.ok(text.includes("Sueño: 8 h 29 min"));
    assert.ok(text.includes("Frecuencia cardíaca: 72 lpm"));
    assert.ok(text.includes("Ejercicio: 15 min de 30 min"));
    assert.ok(text.includes("Agua: 2,000 de 3,000 ml"));
    assert.ok(text.includes("Estrés: 35 (bajo)"));
    assert.ok(text.includes("Peso: 75.5 kg"));
    assert.ok(text.includes("Pantalla: 3 h 20 min · 40 desbloqueos"));

    assert.equal(
        formatHealthSummary({ days: [] }),
        "Sin datos de salud todavía. Ejecuta: idupi-phone health refresh",
    );
    assert.deepEqual(formatHealthMetrics({}), []);
});

test("formatHealthSources lists sources and installed apps", () => {
    const text = formatHealthSources({
        sources: [
            { id: "huawei_health", label: "Huawei Health", lastCaptureAt: "2026-11-24T10:00:00.000Z" },
            { id: "health_connect", label: "Health Connect", lastCaptureAt: null },
        ],
        installedApps: [
            { packageName: "com.huawei.health", label: "Huawei Health", route: "hidden_display" },
        ],
    });
    assert.match(text, /^Fuentes de salud:$/m);
    assert.ok(text.includes("huawei_health (Huawei Health): última captura"));
    assert.ok(text.includes("health_connect (Health Connect): sin datos"));
    assert.ok(text.includes("com.huawei.health — Huawei Health (hidden_display)"));
});

test("formatHealthRefresh and formatHealthAdb summarize the PC state", () => {
    const refresh = formatHealthRefresh({
        id: "hgj-0123456789ab",
        status: "done",
        results: [{ source: "huawei_health", ok: true, metrics: 8, sourceApp: "com.huawei.health" }],
    });
    assert.ok(refresh.includes("Actualización de salud: listo"));
    assert.ok(refresh.includes("huawei_health: 8 métricas (com.huawei.health)"));

    const adb = formatHealthAdb({
        adb: { path: "C:\\sdk\\adb.exe", found: true },
        scrcpy: { path: "C:\\tools\\scrcpy.exe", found: true, version: "5.0.1" },
        connected: true,
        serial: "192.168.1.50:37123",
        via: "cached",
        targets: [{ serial: "192.168.1.50:37123", network: "lan", model: "SM-S928B" }],
    });
    assert.ok(adb.includes("ADB: encontrado"));
    assert.ok(adb.includes("scrcpy: 5.0.1"));
    assert.ok(adb.includes("Conexión: 192.168.1.50:37123 (cached)"));
    assert.ok(adb.includes("192.168.1.50:37123 · lan · SM-S928B"));
});


// --- health digest CLI (phone-health, T3) ---------------------------------

test("parseArgs handles health digest with and without --refresh", () => {
    assert.deepEqual(parseArgs(["health", "digest"]), {
        command: "health",
        action: "digest",
        refresh: false,
        json: false,
    });
    assert.deepEqual(parseArgs(["health", "digest", "--refresh"]), {
        command: "health",
        action: "digest",
        refresh: true,
        json: false,
    });
    assert.deepEqual(parseArgs(["health", "digest", "--refresh", "--json"]), {
        command: "health",
        action: "digest",
        refresh: true,
        json: true,
    });
    const malformed = parseArgs(["health", "digest", "--nope"]);
    assert.equal(malformed.command, "help");
    assert.ok(malformed.error);
});

test("formatHealthDigest renders the card and the medical footer", () => {
    const text = formatHealthDigest({
        date: "2026-11-24",
        status: "ready",
        summary: "Hoy caminaste más que el resto de la semana.",
        highlights: ["Pasos sobre el promedio"],
        recommendations: ["Camina después de almuerzo"],
        agendaNotes: ["Mañana tienes tres reuniones"],
        available: true,
    });
    assert.match(text, /^Resumen de salud \(2026-11-24\): listo$/m);
    assert.ok(text.includes("Hoy caminaste más que el resto de la semana."));
    assert.ok(text.includes("Destacados:"));
    assert.ok(text.includes("Recomendaciones:"));
    assert.ok(text.includes("Agenda:"));
    assert.match(text, /No es consejo médico\.$/);

    const analyzing = formatHealthDigest({ status: "analyzing", date: "2026-11-24" });
    assert.ok(analyzing.includes("analizando"));
    assert.ok(analyzing.includes("todavía está escribiendo"));

    const failed = formatHealthDigest({
        status: "failed",
        date: "2026-11-24",
        error: "agent timed out",
        available: true,
    });
    assert.ok(failed.includes("falló"));
    assert.ok(failed.includes("Error: agent timed out"));

    const noData = formatHealthDigest({
        status: "ready",
        date: "2026-11-24",
        summary: "",
        available: false,
    });
    assert.ok(noData.includes("Sin datos de salud todavía"));
});

test("formatHealthSources lists future sources", () => {
    const text = formatHealthSources({
        sources: [],
        installedApps: [],
        futureSources: [{
            id: "huawei_health_kit",
            label: "Huawei Health Kit",
            enabled: false,
            configured: true,
            reason: "registered_but_disabled",
        }],
    });
    assert.ok(text.includes("Fuentes futuras:"));
    assert.ok(text.includes("huawei_health_kit (Huawei Health Kit): deshabilitada"));
});

// --- approvals CLI (phone-approvals, T1) ----------------------------------

test("parseArgs handles the approvals command with and without --json", () => {
    assert.deepEqual(parseArgs(["approvals"]), { command: "approvals", json: false });
    assert.deepEqual(parseArgs(["approvals", "--json"]), { command: "approvals", json: true });
    assert.equal(parseArgs(["approvals", "extra"]).command, "help");
    assert.ok(parseArgs(["approvals", "extra"]).error);
    assert.equal(parseArgs(["approvals", "--nope"]).command, "help");
});

test("formatApprovalWait explains the reason and the remaining minutes", () => {
    const nowMs = Date.parse("2026-11-24T12:00:00.000Z");
    assert.equal(
        formatApprovalWait(
            {
                approvalReason: "risky_text",
                approvalTarget: "Enviar",
                approvalExpiresAt: "2026-11-24T12:10:00.000Z",
            },
            nowMs,
        ),
        'Esperando tu aprobación en el teléfono (toque en "Enviar", vence en 10 min)…',
    );
    assert.match(
        formatApprovalWait(
            { approvalReason: "type_submit", approvalExpiresAt: "2026-11-24T12:05:00.000Z" },
            nowMs,
        ),
        /envío de texto, vence en 5 min/,
    );
    assert.match(
        formatApprovalWait({ approvalReason: "sensitive_app" }, nowMs),
        /acción en una app sensible, vence en 10 min/,
    );
    assert.match(
        formatApprovalWait(
            {
                approvalReason: "phone_detected_risky_target",
                approvalTarget: "Pagar",
                approvalExpiresAt: "2026-11-24T12:10:00.000Z",
            },
            nowMs,
        ),
        /toque en "Pagar"/,
    );
    // A stale timestamp still shows a minimum of one minute.
    assert.match(
        formatApprovalWait(
            { approvalReason: "risky_text", approvalExpiresAt: "2026-11-24T11:00:00.000Z" },
            nowMs,
        ),
        /vence en 1 min/,
    );
});

test("formatApprovals lists pending approvals with their target and reason", () => {
    assert.equal(formatApprovals({ approvals: [] }), "No hay aprobaciones pendientes.");
    assert.equal(formatApprovals(null), "No hay aprobaciones pendientes.");

    const text = formatApprovals(
        {
            approvals: [
                {
                    id: "apr-aaaaaaaaaaaa",
                    state: "pending",
                    command: { type: "tap", args: { text: "Enviar" } },
                    reason: "risky_text",
                    target: "Enviar",
                    foregroundApp: { packageName: "com.whatsapp", label: "WhatsApp" },
                    expiresAt: "2026-11-24T12:10:00.000Z",
                },
                {
                    id: "apr-bbbbbbbbbbbb",
                    state: "pending",
                    command: { type: "type", args: { text: "hola", submit: true } },
                    reason: "type_submit",
                    target: null,
                    expiresAt: "2026-11-24T12:08:00.000Z",
                },
            ],
        },
        Date.parse("2026-11-24T12:00:00.000Z"),
    );
    assert.match(text, /^Aprobaciones pendientes \(2\):$/m);
    assert.ok(text.includes("apr-aaaaaaaaaaaa"));
    assert.ok(text.includes('tap "Enviar"'));
    assert.ok(text.includes("WhatsApp (com.whatsapp)"));
    assert.ok(text.includes("vence en 10 min"));
    assert.ok(text.includes("apr-bbbbbbbbbbbb"));
    assert.ok(text.includes("envío de texto"));
});

test("a rejected or expired approval prints a clear Spanish result", () => {
    assert.equal(
        failureMessage({ state: "failed", error: "APPROVAL_REJECTED" }),
        "Rechazado en el teléfono.",
    );
    assert.equal(
        failureMessage({ state: "failed", error: "APPROVAL_EXPIRED" }),
        "La aprobación venció; no se hizo nada.",
    );
    assert.equal(
        formatCommandResult({ state: "failed", error: "APPROVAL_REJECTED" }),
        "Rechazado en el teléfono.",
    );
    assert.equal(
        formatCommandResult({ state: "failed", error: "APPROVAL_EXPIRED" }),
        "La aprobación venció; no se hizo nada.",
    );
    assert.equal(
        failureMessage({ state: "failed", error: "no node matched" }),
        "El teléfono falló: no node matched",
    );
});
