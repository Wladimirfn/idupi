// Tests for the phone inbox HTTP routes (phone-inbox, T1).
//
// A real http.Server on an ephemeral port wires the handler exactly as
// index.mjs does (minus auth, which index.mjs owns upstream), and the agent
// runner is a fake so the tests never spawn a CLI.
//
// Run (from repo root):
//   node --test idupi-server/test/inbox-routes.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { createInboxStore } from "../lib/inbox-store.mjs";
import { createInboxDigestStore } from "../lib/inbox-digest.mjs";
import { createInboxRoutes } from "../lib/inbox-routes.mjs";
import { supportsReadOnlyInteraction } from "../lib/inbox-agent.mjs";
import { createPhoneCalendarStore } from "../lib/phone-calendar-store.mjs";

const silentLog = { log() {}, info() {}, warn() {}, error() {} };

const RESULT = {
    importance: "high",
    category: "Trabajo",
    summary: "Ana pregunta si llegaste.",
    suggestedReply: "Sí, ya estoy.",
};

const DIGEST_RESULT = {
    summary: "Acordaron la reunión del martes; Ana lleva el informe.",
    pendingForOwner: ["¿Puedes enviar el informe el lunes?"],
    suggestedReply: "Sí, lo envío el lunes.",
};

function message(overrides = {}) {
    return {
        app: "whatsapp",
        appLabel: "WhatsApp",
        packageName: "com.whatsapp",
        sender: "Ana",
        conversation: "Equipo",
        text: "Hola, ¿llegaste?",
        postedAt: "2026-11-24T09:59:00.000Z",
        notificationKey: "key-1",
        ...overrides,
    };
}

async function waitFor(check, timeoutMs = 3000) {
    const started = Date.now();
    for (;;) {
        const value = await check();
        if (value) return value;
        if (Date.now() - started > timeoutMs) throw new Error("timed out waiting for condition");
        await new Promise((resolve) => setTimeout(resolve, 10));
    }
}

async function startHarness(t, {
    runner = null,
    openCodeModels = () => [],
    calendarStore = null,
    interactionRunner = null,
    projects = () => [],
} = {}) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-inbox-routes-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const store = createInboxStore({ dataDir: dir, log: silentLog });
    const digestStore = createInboxDigestStore({
        dataDir: dir,
        log: silentLog,
        now: () => new Date("2026-11-24T12:00:00.000Z"),
    });
    const fakeRunner = runner || {
        run: async () => ({ ...RESULT }),
        runPrompt: async () => JSON.stringify(DIGEST_RESULT),
    };
    const handle = createInboxRoutes({
        store,
        digestStore,
        runner: fakeRunner,
        calendarStore,
        interactionRunner,
        projects,
        now: () => new Date("2026-11-24T12:00:00.000Z"),
        openCodeModels,
    });

    const server = http.createServer(async (req, res) => {
        try {
            const pathname = new URL(req.url, "http://localhost").pathname;
            if (await handle(req, res, pathname)) return;
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
    return { base, store, digestStore, runner: fakeRunner };
}

function postJson(base, path, body) {
    return fetch(base + path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: typeof body === "string" ? body : JSON.stringify(body),
    });
}

function putJson(base, path, body) {
    return fetch(base + path, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
}

async function postMessage(base, overrides = {}) {
    const res = await postJson(base, "/api/v1/inbox/messages", message(overrides));
    return { res, body: await res.json() };
}

async function waitReady(base, id) {
    return waitFor(async () => {
        const res = await fetch(base + "/api/v1/inbox?limit=200");
        const { items } = await res.json();
        const item = items.find((candidate) => candidate.id === id);
        return item && item.status !== "analyzing" ? item : null;
    });
}

test("POST returns 202 and the serialized analysis later lands as ready", async (t) => {
    const { base } = await startHarness(t);

    const { res, body } = await postMessage(base);
    assert.equal(res.status, 202);
    assert.equal(body.item.status, "analyzing");
    assert.equal(body.item.state, "open");

    const item = await waitReady(base, body.item.id);
    assert.equal(item.status, "ready");
    assert.equal(item.importance, "high");
    assert.equal(item.category, "Trabajo");
    assert.equal(item.summary, RESULT.summary);
    assert.equal(item.suggestedReply, RESULT.suggestedReply);
    assert.equal(item.promotion, false);
    assert.equal(item.error, null);
});

test("a promotion analysis stores the flag so the app can collapse it", async (t) => {
    const promotionResult = {
        importance: "low",
        category: "Promociones",
        summary: "Oferta de la tienda hasta el domingo.",
        suggestedReply: "",
        promotion: true,
    };
    const runner = { run: async () => ({ ...promotionResult }) };
    const { base } = await startHarness(t, { runner });

    const { body } = await postMessage(base);
    const item = await waitReady(base, body.item.id);

    assert.equal(item.status, "ready");
    assert.equal(item.promotion, true);
    assert.equal(item.importance, "low");
    assert.equal(item.suggestedReply, "");
});

test("a failing analysis marks the item failed with a short single-line error", async (t) => {
    const runner = {
        run: async () => {
            throw new Error("provider rejected the request\nstack line that should not leak");
        },
    };
    const { base } = await startHarness(t, { runner });

    const { body } = await postMessage(base);
    const item = await waitReady(base, body.item.id);

    assert.equal(item.status, "failed");
    assert.match(item.error, /provider rejected the request/);
    assert.equal(item.error.includes("\n"), false);
    assert.ok(item.error.length <= 300);
});

test("dedup returns 200 with the existing item and never analyzes it twice", async (t) => {
    let calls = 0;
    const runner = {
        run: async () => {
            calls += 1;
            return { ...RESULT };
        },
    };
    const { base } = await startHarness(t, { runner });

    const first = await postMessage(base);
    await waitReady(base, first.body.item.id);

    const second = await postMessage(base);

    assert.equal(second.res.status, 200);
    assert.equal(second.body.item.id, first.body.item.id);
    assert.equal(calls, 1);
});

test("the analysis queue never runs two agent processes at once", async (t) => {
    const releases = [];
    let active = 0;
    let maxActive = 0;
    const runner = {
        run: async () => {
            active += 1;
            maxActive = Math.max(maxActive, active);
            await new Promise((resolve) => releases.push(resolve));
            active -= 1;
            return { ...RESULT };
        },
    };
    const { base } = await startHarness(t, { runner });

    const first = await postMessage(base);
    const second = await postMessage(base, { notificationKey: "key-2" });

    await waitFor(() => releases.length === 1);
    assert.equal(maxActive, 1);
    releases[0]();
    await waitFor(() => releases.length === 2);
    assert.equal(maxActive, 1);
    releases[1]();

    await waitReady(base, first.body.item.id);
    await waitReady(base, second.body.item.id);
});

test("a followed package name is accepted and its label round-trips through GET", async (t) => {
    const { base } = await startHarness(t);

    const { res, body } = await postMessage(base, {
        app: "org.telegram.messenger",
        appLabel: "  Telegram  ",
        packageName: "org.telegram.messenger",
        notificationKey: "tg-1",
    });

    assert.equal(res.status, 202);
    assert.equal(body.item.app, "org.telegram.messenger");
    assert.equal(body.item.appLabel, "Telegram");

    const listed = await (await fetch(base + "/api/v1/inbox")).json();
    assert.equal(listed.items[0].app, "org.telegram.messenger");
    assert.equal(listed.items[0].appLabel, "Telegram");
});

test("missing or malformed app ids are rejected; appLabel is optional and clipped", async (t) => {
    const { base } = await startHarness(t);

    for (const app of ["", "tele gram", "telegram!", "x".repeat(121)]) {
        const { res, body } = await postMessage(base, {
            app,
            notificationKey: `bad-${app.length}`,
        });
        assert.equal(res.status, 400, `app=${JSON.stringify(app)} must be rejected`);
        assert.equal(body.code, "INVALID_APP");
    }

    const missing = await postJson(base, "/api/v1/inbox/messages", {
        text: "hola",
        notificationKey: "no-app",
    });
    assert.equal(missing.status, 400);
    assert.equal((await missing.json()).code, "INVALID_APP");

    const noLabel = await postMessage(base, { appLabel: "", notificationKey: "no-label" });
    assert.equal(noLabel.res.status, 202);
    assert.equal(noLabel.body.item.appLabel, "");

    const longLabel = await postMessage(base, {
        appLabel: "L".repeat(90),
        notificationKey: "long-label",
    });
    assert.equal(longLabel.res.status, 202);
    assert.equal(longLabel.body.item.appLabel.length, 60);

    const badLabel = await postJson(base, "/api/v1/inbox/messages", {
        ...message({ notificationKey: "bad-label" }),
        appLabel: 42,
    });
    assert.equal(badLabel.status, 400);
    assert.equal((await badLabel.json()).code, "INVALID_MESSAGE");
});

test("state changes persist, validate and 404 unknown ids", async (t) => {
    const { base } = await startHarness(t);
    const { body } = await postMessage(base);
    await waitReady(base, body.item.id);

    let res = await postJson(base, `/api/v1/inbox/${body.item.id}/state`, { state: "handled" });
    assert.equal(res.status, 200);
    assert.equal((await res.json()).item.state, "handled");

    res = await postJson(base, `/api/v1/inbox/${body.item.id}/state`, { state: "done" });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_STATE");

    res = await postJson(base, "/api/v1/inbox/inb-ffffffffffff/state", { state: "handled" });
    assert.equal(res.status, 404);
    assert.equal((await res.json()).code, "NOT_FOUND");

    res = await postJson(base, "/api/v1/inbox/not-an-id/state", { state: "handled" });
    assert.equal(res.status, 404);
});

test("retry re-queues an item and returns it as analyzing", async (t) => {
    let calls = 0;
    const runner = {
        run: async () => {
            calls += 1;
            return { ...RESULT };
        },
    };
    const { base } = await startHarness(t, { runner });

    const { body } = await postMessage(base);
    await waitReady(base, body.item.id);
    assert.equal(calls, 1);

    const res = await postJson(base, `/api/v1/inbox/${body.item.id}/retry`, {});
    assert.equal(res.status, 202);
    assert.equal((await res.json()).item.status, "analyzing");

    await waitReady(base, body.item.id);
    assert.equal(calls, 2);

    const missing = await postJson(base, "/api/v1/inbox/inb-ffffffffffff/retry", {});
    assert.equal(missing.status, 404);
});

test("config GET returns the defaults plus the catalogs and PUT validates and persists", async (t) => {
    const { base } = await startHarness(t);

    let res = await fetch(base + "/api/v1/inbox/config");
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), {
        agent: "opencode",
        model: "opencode-go/deepseek-v4.1-flash",
        effort: "max",
        proactive: true,
        agents: ["opencode", "claude", "codex", "pi"],
        efforts: ["low", "medium", "high", "max"],
    });

    res = await putJson(base, "/api/v1/inbox/config", {
        agent: "claude",
        model: "claude-haiku-4-5",
        effort: "low",
    });
    assert.equal(res.status, 200);
    assert.equal((await res.json()).agent, "claude");

    res = await fetch(base + "/api/v1/inbox/config");
    assert.equal((await res.json()).model, "claude-haiku-4-5");

    res = await putJson(base, "/api/v1/inbox/config", { agent: "aider", model: "x", effort: "low" });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_CONFIG");

    res = await putJson(base, "/api/v1/inbox/config", { agent: "opencode", model: "x", effort: "ultra" });
    assert.equal(res.status, 400);

    res = await putJson(base, "/api/v1/inbox/config", { agent: "opencode", model: " ", effort: "low" });
    assert.equal(res.status, 400);
});

test("config keeps proactive on when omitted and validates the flag when sent", async (t) => {
    const { base } = await startHarness(t);

    // An older app sends only agent/model/effort: the setting must not flip.
    let res = await putJson(base, "/api/v1/inbox/config", {
        agent: "claude",
        model: "claude-haiku-4-5",
        effort: "low",
    });
    assert.equal(res.status, 200);
    assert.equal((await res.json()).proactive, true);

    res = await putJson(base, "/api/v1/inbox/config", {
        agent: "claude",
        model: "claude-haiku-4-5",
        effort: "low",
        proactive: false,
    });
    assert.equal(res.status, 200);
    assert.equal((await res.json()).proactive, false);

    res = await fetch(base + "/api/v1/inbox/config");
    assert.equal((await res.json()).proactive, false);

    res = await putJson(base, "/api/v1/inbox/config", {
        agent: "claude",
        model: "claude-haiku-4-5",
        effort: "low",
        proactive: "yes",
    });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_CONFIG");
    assert.equal((await (await fetch(base + "/api/v1/inbox/config")).json()).proactive, false);
});

test("models reuses the injected opencode catalog and returns [] for the others", async (t) => {
    const { base } = await startHarness(t, {
        openCodeModels: () => ["opencode-go/deepseek-v4.1-flash", 42, ""],
    });

    let res = await fetch(base + "/api/v1/inbox/models?agent=opencode");
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { models: ["opencode-go/deepseek-v4.1-flash"] });

    res = await fetch(base + "/api/v1/inbox/models?agent=claude");
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { models: [] });

    res = await fetch(base + "/api/v1/inbox/models?agent=aider");
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "INVALID_AGENT");
});

test("models degrades to an empty list when the catalog call fails", async (t) => {
    const { base } = await startHarness(t, {
        openCodeModels: () => {
            throw new Error("cli missing");
        },
    });

    const res = await fetch(base + "/api/v1/inbox/models?agent=opencode");
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { models: [] });
});

test("GET inbox validates the limit and returns newest first", async (t) => {
    const { base } = await startHarness(t);
    await postMessage(base, { notificationKey: "one", text: "one" });
    await postMessage(base, { notificationKey: "two", text: "two" });
    await postMessage(base, { notificationKey: "three", text: "three" });

    let res = await fetch(base + "/api/v1/inbox");
    assert.equal(res.status, 200);
    assert.equal((await res.json()).items.length, 3);

    res = await fetch(base + "/api/v1/inbox?limit=2");
    const limited = await res.json();
    assert.equal(limited.items.length, 2);
    assert.equal(limited.items[0].text, "three");

    for (const bad of ["0", "201", "abc", "1.5"]) {
        res = await fetch(base + `/api/v1/inbox?limit=${bad}`);
        assert.equal(res.status, 400, `limit=${bad} must be rejected`);
        assert.equal((await res.json()).code, "INVALID_LIMIT");
    }
});

test("wrong methods answer 405 with an Allow header", async (t) => {
    const { base } = await startHarness(t);

    let res = await fetch(base + "/api/v1/inbox/messages");
    assert.equal(res.status, 405);
    assert.equal(res.headers.get("allow"), "POST");

    res = await putJson(base, "/api/v1/inbox/messages", {});
    assert.equal(res.status, 405);

    res = await fetch(base + "/api/v1/inbox", { method: "DELETE" });
    assert.equal(res.status, 405);
    assert.equal(res.headers.get("allow"), "GET, HEAD");
});

// --- extracted calendar event (phone-calendar, T1) -------------------------

const EVENT_RESULT = {
    title: "Reunión con Ana",
    start: "2026-11-25T13:00:00.000Z",
    end: "2026-11-25T14:00:00.000Z",
    allDay: false,
    location: "Oficina",
};

async function makeCalendarStore(t, events = []) {
    const dir = await mkdtemp(join(tmpdir(), "idupi-inbox-calendar-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const calendarStore = createPhoneCalendarStore({ dataDir: dir, log: silentLog });
    if (events.length > 0) {
        calendarStore.recordSnapshot({
            capturedAt: "2026-11-24T12:00:00.000Z",
            rangeStart: "2026-11-24T00:00:00.000Z",
            rangeEnd: "2026-12-08T00:00:00.000Z",
            tzOffsetMinutes: -180,
            calendars: [],
            events,
        });
    }
    return calendarStore;
}

test("analysis stores the extracted event and warns about snapshot conflicts", async (t) => {
    const calendarStore = await makeCalendarStore(t, [{
        id: "ev-1",
        calendarId: "cal-1",
        title: "Almuerzo",
        start: "2026-11-25T13:30:00.000Z",
        end: "2026-11-25T14:30:00.000Z",
        allDay: false,
        location: "",
    }]);
    const runner = { run: async () => ({ ...RESULT, event: { ...EVENT_RESULT } }) };
    const { base } = await startHarness(t, { runner, calendarStore });

    const { body } = await postMessage(base);
    const item = await waitReady(base, body.item.id);

    assert.deepEqual(item.event, EVENT_RESULT);
    assert.deepEqual(item.eventConflicts, [{
        title: "Almuerzo",
        start: "2026-11-25T13:30:00.000Z",
        end: "2026-11-25T14:30:00.000Z",
    }]);
});

test("an event without a snapshot is kept and has no conflict warnings", async (t) => {
    const runner = {
        run: async () => ({
            ...RESULT,
            event: {
                title: "Feriado",
                start: "2026-12-25T00:00:00.000Z",
                end: "2026-12-26T00:00:00.000Z",
                allDay: true,
                location: "",
            },
        }),
    };
    const { base } = await startHarness(t, { runner });

    const { body } = await postMessage(base);
    const item = await waitReady(base, body.item.id);

    assert.equal(item.event.title, "Feriado");
    assert.equal(item.event.allDay, true);
    assert.equal(item.event.end, "2026-12-26T00:00:00.000Z");
    assert.deepEqual(item.eventConflicts, []);
});

test("a message without a date stores a null event", async (t) => {
    const { base } = await startHarness(t);
    const { body } = await postMessage(base);
    const item = await waitReady(base, body.item.id);
    assert.equal(item.event, null);
    assert.deepEqual(item.eventConflicts, []);
});

// --- Interactuar (alerts-flow A3) -----------------------------------------

function fakeInteractionRunner({ answer = "No te afecta: revisé package.json.", error = null } = {}) {
    const calls = [];
    return {
        calls,
        supported: true,
        supports() {
            return this.supported;
        },
        async runInteraction(config, input) {
            calls.push({ config, input });
            if (error) throw error;
            return answer;
        },
    };
}

async function postInteraction(base, id, body) {
    const res = await postJson(base, `/api/v1/inbox/${id}/interact`, body);
    return { res, body: await res.json() };
}

test("POST interact answers 202 running and stores the answer on the item", async (t) => {
    const projectDir = await mkdtemp(join(tmpdir(), "idupi-interact-project-"));
    t.after(() => rm(projectDir, { recursive: true, force: true }));
    const interactionRunner = fakeInteractionRunner();
    const { base, store } = await startHarness(t, {
        interactionRunner,
        projects: () => [{ id: "idupi", name: "IDUPI", path: projectDir }],
    });

    const { body: created } = await postMessage(base);
    const id = created.item.id;

    const { res, body } = await postInteraction(base, id, {
        instruction: "revisa si este error de Render afecta mis proyectos",
        projectId: "idupi",
    });

    assert.equal(res.status, 202);
    assert.equal(body.interaction.status, "running");
    assert.equal(body.interaction.instruction, "revisa si este error de Render afecta mis proyectos");
    assert.equal(body.interaction.projectId, "idupi");
    assert.equal(body.interaction.projectName, "IDUPI");
    assert.equal(body.interaction.answer, "");
    assert.equal(body.interaction.finishedAt, null);
    assert.match(body.interaction.startedAt, /^\d{4}-\d{2}-\d{2}T/);

    const finished = await waitFor(async () => {
        const res = await fetch(base + "/api/v1/inbox?limit=200");
        const { items } = await res.json();
        const item = items.find((candidate) => candidate.id === id);
        return item?.interaction?.status === "done" ? item : null;
    });

    assert.equal(finished.interaction.answer, "No te afecta: revisé package.json.");
    assert.equal(finished.interaction.projectName, "IDUPI");
    assert.match(finished.interaction.finishedAt, /^\d{4}-\d{2}-\d{2}T/);
    assert.equal(finished.interaction.error, null);
    assert.equal(store.get(id).interaction.status, "done");

    assert.equal(interactionRunner.calls.length, 1);
    assert.equal(interactionRunner.calls[0].input.item.id, id);
    assert.equal(interactionRunner.calls[0].input.project.path, projectDir);
    assert.equal(
        interactionRunner.calls[0].input.instruction,
        "revisa si este error de Render afecta mis proyectos",
    );
});

test("POST interact without a project stores null project fields", async (t) => {
    const interactionRunner = fakeInteractionRunner({ answer: "No pude determinarlo." });
    const { base } = await startHarness(t, { interactionRunner });
    const { body: created } = await postMessage(base);

    const { res, body } = await postInteraction(base, created.item.id, {
        instruction: "  ¿esto me afecta?  ",
    });

    assert.equal(res.status, 202);
    assert.equal(body.interaction.instruction, "¿esto me afecta?");
    assert.equal(body.interaction.projectId, null);
    assert.equal(body.interaction.projectName, null);
    assert.equal(interactionRunner.calls[0].input.project, null);
});

test("POST interact answers 404 for an unknown item", async (t) => {
    const { base } = await startHarness(t, { interactionRunner: fakeInteractionRunner() });

    const { res, body } = await postInteraction(base, "inb-0123456789ab", {
        instruction: "revisa esto",
    });

    assert.equal(res.status, 404);
    assert.equal(body.code, "NOT_FOUND");
});

test("POST interact answers 400 for an invalid instruction", async (t) => {
    const { base } = await startHarness(t, { interactionRunner: fakeInteractionRunner() });
    const { body: created } = await postMessage(base);

    for (const instruction of [undefined, "", "   ", "x".repeat(2001), 42, {}]) {
        const { res, body } = await postInteraction(base, created.item.id, { instruction });
        assert.equal(res.status, 400, JSON.stringify(instruction));
        assert.equal(body.code, "INVALID_INSTRUCTION", JSON.stringify(instruction));
    }
});

test("POST interact answers 400 for an unknown or unusable project", async (t) => {
    const dir = await mkdtemp(join(tmpdir(), "idupi-interact-project-"));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const filePath = join(dir, "not-a-directory.txt");
    await writeFile(filePath, "x", "utf8");
    const { base } = await startHarness(t, {
        interactionRunner: fakeInteractionRunner(),
        projects: () => [
            { id: "ok", name: "OK", path: dir },
            { id: "file", name: "File", path: filePath },
        ],
    });
    const { body: created } = await postMessage(base);

    const unknown = await postInteraction(base, created.item.id, {
        instruction: "revisa",
        projectId: "nope",
    });
    assert.equal(unknown.res.status, 400);
    assert.equal(unknown.body.code, "INVALID_PROJECT");

    const notDir = await postInteraction(base, created.item.id, {
        instruction: "revisa",
        projectId: "file",
    });
    assert.equal(notDir.res.status, 400);
    assert.equal(notDir.body.code, "INVALID_PROJECT");

    const wrongType = await postInteraction(base, created.item.id, {
        instruction: "revisa",
        projectId: 7,
    });
    assert.equal(wrongType.res.status, 400);
    assert.equal(wrongType.body.code, "INVALID_PROJECT");
});

test("POST interact answers 400 when the configured agent cannot interact", async (t) => {
    const interactionRunner = fakeInteractionRunner();
    interactionRunner.supported = false;
    const { base } = await startHarness(t, { interactionRunner });
    const { body: created } = await postMessage(base);

    const { res, body } = await postInteraction(base, created.item.id, {
        instruction: "revisa esto",
    });

    assert.equal(res.status, 400);
    assert.equal(body.code, "INTERACTION_UNSUPPORTED");
    assert.equal(
        body.error,
        "Interactuar solo funciona con OpenCode o Claude. Cambia el agente en Ajustes > Alertas.",
    );
    assert.equal(interactionRunner.calls.length, 0);
});

test("POST interact answers 400 for codex and pi, keeping their analysis profiles", async (t) => {
    const interactionRunner = {
        supports: (agent) => supportsReadOnlyInteraction(agent),
        calls: [],
        async runInteraction(config, input) {
            this.calls.push({ config, input });
            return "should never run";
        },
    };
    const { base } = await startHarness(t, { interactionRunner });
    const { body: created } = await postMessage(base);

    for (const agent of ["codex", "pi"]) {
        const put = await putJson(base, "/api/v1/inbox/config", {
            agent,
            model: agent === "pi" ? "opencode-go/deepseek-v4.1-flash" : "gpt-6.2-sol",
            effort: "low",
        });
        assert.equal(put.status, 200, `${agent} must stay a valid analysis agent`);

        const { res, body } = await postInteraction(base, created.item.id, {
            instruction: "revisa esto",
        });
        assert.equal(res.status, 400, agent);
        assert.equal(body.code, "INTERACTION_UNSUPPORTED", agent);
        assert.equal(
            body.error,
            "Interactuar solo funciona con OpenCode o Claude. Cambia el agente en Ajustes > Alertas.",
            agent,
        );
    }
    assert.equal(interactionRunner.calls.length, 0);
});

test("superseded interaction jobs never spawn a process for the replaced request", async (t) => {
    const pending = [];
    const interactionRunner = {
        supports: () => true,
        calls: [],
        async runInteraction(config, input) {
            this.calls.push(input.instruction);
            return new Promise((resolve) => pending.push(resolve));
        },
    };
    const { base } = await startHarness(t, { interactionRunner });
    const { body: created } = await postMessage(base);
    const id = created.item.id;

    await postInteraction(base, id, { instruction: "primera" });
    await waitFor(() => pending.length === 1);

    await postInteraction(base, id, { instruction: "segunda" });
    await postInteraction(base, id, { instruction: "tercera" });

    pending[0]("respuesta vieja");
    await waitFor(() => pending.length === 2);

    assert.deepEqual(interactionRunner.calls, ["primera", "tercera"]);

    pending[1]("respuesta nueva");
    const finished = await waitFor(async () => {
        const res = await fetch(base + "/api/v1/inbox?limit=200");
        const { items } = await res.json();
        const item = items.find((candidate) => candidate.id === id);
        return item?.interaction?.status === "done" ? item : null;
    });

    assert.equal(finished.interaction.instruction, "tercera");
    assert.equal(finished.interaction.answer, "respuesta nueva");
});

test("interaction jobs run one at a time", async (t) => {
    const pending = [];
    let active = 0;
    let maxActive = 0;
    const interactionRunner = {
        supports: () => true,
        runInteraction: async () => {
            active += 1;
            maxActive = Math.max(maxActive, active);
            try {
                await new Promise((resolve) => pending.push(resolve));
                return "listo";
            } finally {
                active -= 1;
            }
        },
    };
    const { base } = await startHarness(t, { interactionRunner });
    const first = (await postMessage(base, { notificationKey: "k1" })).body.item;
    const second = (await postMessage(base, { notificationKey: "k2" })).body.item;

    await postInteraction(base, first.id, { instruction: "primera" });
    await waitFor(() => pending.length === 1);
    await postInteraction(base, second.id, { instruction: "segunda" });
    await new Promise((resolve) => setTimeout(resolve, 30));

    assert.equal(pending.length, 1, "the second job waits for the first");
    assert.equal(maxActive, 1);

    pending[0]();
    await waitFor(() => pending.length === 2);
    pending[1]();
    await waitFor(async () => {
        const res = await fetch(base + "/api/v1/inbox?limit=200");
        const { items } = await res.json();
        return items.find((item) => item.id === second.id)?.interaction?.status === "done";
    });
    assert.equal(maxActive, 1);
});

test("a failed interaction stores a short safe error", async (t) => {
    const interactionRunner = fakeInteractionRunner({
        error: new Error("agent failed: provider rejected the request"),
    });
    const { base } = await startHarness(t, { interactionRunner });
    const { body: created } = await postMessage(base);

    await postInteraction(base, created.item.id, {
        instruction: "revisa si esto afecta al proyecto",
    });

    const failed = await waitFor(async () => {
        const res = await fetch(base + "/api/v1/inbox?limit=200");
        const { items } = await res.json();
        const item = items.find((candidate) => candidate.id === created.item.id);
        return item?.interaction?.status === "failed" ? item : null;
    });

    assert.equal(failed.interaction.answer, "");
    assert.equal(failed.interaction.error, "agent failed: provider rejected the request");
    assert.equal(failed.interaction.error.includes("revisa si esto"), false);
});

test("a new interaction replaces the previous one and a late result never wins", async (t) => {
    const pending = [];
    const interactionRunner = {
        supports: () => true,
        runInteraction: async () => {
            const answer = await new Promise((resolve) => pending.push(resolve));
            return answer;
        },
    };
    const { base } = await startHarness(t, { interactionRunner });
    const { body: created } = await postMessage(base);

    await postInteraction(base, created.item.id, { instruction: "primera" });
    await waitFor(() => pending.length === 1);
    const { body: replaced } = await postInteraction(base, created.item.id, {
        instruction: "segunda",
    });
    assert.equal(replaced.interaction.status, "running");
    assert.equal(replaced.interaction.instruction, "segunda");

    pending[0]("respuesta vieja");
    await new Promise((resolve) => setTimeout(resolve, 30));
    const stillRunning = (await (await fetch(base + "/api/v1/inbox?limit=200")).json())
        .items.find((candidate) => candidate.id === created.item.id);
    assert.equal(stillRunning.interaction.status, "running");
    assert.equal(stillRunning.interaction.instruction, "segunda");

    await waitFor(() => pending.length === 2);
    pending[1]("respuesta nueva");
    const finished = await waitFor(async () => {
        const res = await fetch(base + "/api/v1/inbox?limit=200");
        const { items } = await res.json();
        const item = items.find((candidate) => candidate.id === created.item.id);
        return item?.interaction?.status === "done" ? item : null;
    });

    assert.equal(finished.interaction.answer, "respuesta nueva");
    assert.equal(finished.interaction.instruction, "segunda");
});

// --- Proactive technical alerts (alerts-flow A4) --------------------------

const TECHNICAL_RESULT = {
    importance: "high",
    category: "Infra",
    summary: "Render reportó un fallo del deploy.",
    suggestedReply: "",
    technical: true,
    projectHint: "idupi-server",
};

function technicalRunner(overrides = {}) {
    return { run: async () => ({ ...TECHNICAL_RESULT, ...overrides }) };
}

async function projectDirs(t, names = ["idupi-server"]) {
    const dirs = [];
    for (const name of names) {
        const dir = await mkdtemp(join(tmpdir(), "idupi-proactive-project-"));
        t.after(() => rm(dir, { recursive: true, force: true }));
        dirs.push({ id: name, name, path: dir });
    }
    return dirs;
}

async function waitInteraction(base, id, status = "running") {
    return waitFor(async () => {
        const res = await fetch(base + "/api/v1/inbox?limit=200");
        const { items } = await res.json();
        const item = items.find((candidate) => candidate.id === id);
        return item?.interaction?.status === status ? item : null;
    });
}

async function autoInteractionSetup(t, { runner = technicalRunner(), projects = null, config = null } = {}) {
    const dirs = projects ?? await projectDirs(t);
    const interactionRunner = {
        calls: [],
        // Mirrors production: only the agents with a verified read-only
        // profile can run the investigation.
        supports: (agent) => supportsReadOnlyInteraction(agent),
        async runInteraction(config, input) {
            this.calls.push({ config, input });
            return "No te afecta: revisé el deploy.";
        },
    };
    const harness = await startHarness(t, { runner, interactionRunner, projects: () => dirs });
    if (config) {
        const res = await putJson(harness.base, "/api/v1/inbox/config", config);
        assert.equal(res.status, 200);
    }
    return { ...harness, interactionRunner, dirs };
}

test("a high technical alert auto-runs the investigation on the single matching project", async (t) => {
    const interactionRunner = fakeInteractionRunner();
    // The first answer waits so the stored `running` state is observable.
    const releases = [];
    const answerOnce = interactionRunner.runInteraction.bind(interactionRunner);
    interactionRunner.runInteraction = async (config, input) => {
        await new Promise((resolve) => releases.push(resolve));
        return answerOnce(config, input);
    };
    const dirs = await projectDirs(t);
    const { base, store } = await startHarness(t, {
        runner: technicalRunner(),
        interactionRunner,
        projects: () => dirs,
    });

    const { body } = await postMessage(base);
    const item = await waitInteraction(base, body.item.id);

    assert.equal(item.interaction.status, "running");
    assert.equal(item.interaction.auto, true);
    assert.equal(item.interaction.projectId, "idupi-server");
    assert.equal(
        item.interaction.instruction,
        "Revisa si esta alerta afecta a este proyecto: qué falla, dónde mirar en el código y qué harías para resolverlo. No modifiques nada.",
    );

    await waitFor(() => releases.length === 1);
    releases[0]();
    const finished = await waitInteraction(base, body.item.id, "done");
    assert.equal(finished.interaction.auto, true);
    assert.equal(store.get(body.item.id).interaction.auto, true);

    // The investigation is the same read-only job A3 runs.
    assert.equal(interactionRunner.calls.length, 1);
    assert.equal(interactionRunner.calls[0].input.project.id, "idupi-server");
    assert.equal(interactionRunner.calls[0].input.project.path, dirs[0].path);
    assert.equal(interactionRunner.calls[0].input.item.id, body.item.id);
});

test("the item text matches a project when the agent left the hint empty", async (t) => {
    const { base } = await autoInteractionSetup(t, {
        runner: technicalRunner({ projectHint: "" }),
    });

    const { body } = await postMessage(base, {
        app: "gmail",
        sender: "Render",
        conversation: "Render",
        text: "Deploy failed for idupi-server: build exited with 1",
        notificationKey: "render-1",
    });
    const item = await waitInteraction(base, body.item.id, "done");

    assert.equal(item.interaction.auto, true);
    assert.equal(item.interaction.projectId, "idupi-server");
});

test("no auto interaction for medium importance, promotions or unsupported agents", async (t) => {
    const medium = await autoInteractionSetup(t, {
        runner: technicalRunner({ importance: "medium" }),
    });
    const created = await postMessage(medium.base);
    const mediumItem = await waitReady(medium.base, created.body.item.id);
    assert.equal(mediumItem.interaction, null);
    assert.equal(medium.interactionRunner.calls.length, 0);

    const promotion = await autoInteractionSetup(t, {
        runner: technicalRunner({ promotion: true, importance: "low" }),
    });
    const promoCreated = await postMessage(promotion.base);
    const promoItem = await waitReady(promotion.base, promoCreated.body.item.id);
    assert.equal(promoItem.promotion, true);
    assert.equal(promoItem.interaction, null);
    assert.equal(promotion.interactionRunner.calls.length, 0);

    const unsupported = await autoInteractionSetup(t, {
        config: { agent: "codex", model: "gpt-6.2-sol", effort: "low" },
    });
    const codexCreated = await postMessage(unsupported.base);
    const codexItem = await waitReady(unsupported.base, codexCreated.body.item.id);
    assert.equal(codexItem.interaction, null);
    assert.equal(unsupported.interactionRunner.calls.length, 0);
});

test("proactive off, no match, two matches or an existing interaction never auto-run", async (t) => {
    const off = await autoInteractionSetup(t, {
        config: { agent: "opencode", model: "opencode-go/deepseek-v4.1-flash", effort: "max", proactive: false },
    });
    const offCreated = await postMessage(off.base);
    const offItem = await waitReady(off.base, offCreated.body.item.id);
    assert.equal(offItem.interaction, null);
    assert.equal(off.interactionRunner.calls.length, 0);

    const noMatch = await autoInteractionSetup(t, {
        runner: technicalRunner({ projectHint: "acme-web" }),
    });
    const noMatchCreated = await postMessage(noMatch.base, { text: "Deploy failed", notificationKey: "n-1" });
    const noMatchItem = await waitReady(noMatch.base, noMatchCreated.body.item.id);
    assert.equal(noMatchItem.interaction, null);
    assert.equal(noMatch.interactionRunner.calls.length, 0);

    const twoMatches = await autoInteractionSetup(t, {
        projects: await projectDirs(t, ["idupi-server", "idupi-server-web"]),
        runner: technicalRunner({ projectHint: "idupi-server" }),
    });
    const twoCreated = await postMessage(twoMatches.base, { text: "Deploy failed", notificationKey: "t-1" });
    const twoItem = await waitReady(twoMatches.base, twoCreated.body.item.id);
    assert.equal(twoItem.interaction, null);
    assert.equal(twoMatches.interactionRunner.calls.length, 0);

    // A manual instruction already on the item wins; a retry does not replace it.
    const existing = await autoInteractionSetup(t, {
        runner: technicalRunner({ technical: false }),
    });
    const existingCreated = await postMessage(existing.base, { text: "Deploy failed", notificationKey: "e-1" });
    const manual = await postInteraction(existing.base, existingCreated.body.item.id, {
        instruction: "revisa tú esto",
    });
    assert.equal(manual.body.interaction.auto, false);
    await waitFor(() => existing.interactionRunner.calls.length === 1);

    existing.runner.run = async () => ({ ...TECHNICAL_RESULT });
    const retry = await fetch(existing.base + `/api/v1/inbox/${existingCreated.body.item.id}/retry`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
    });
    assert.equal(retry.status, 202);
    const existingItem = await waitFor(async () => {
        const res = await fetch(existing.base + "/api/v1/inbox?limit=200");
        const { items } = await res.json();
        const item = items.find((candidate) => candidate.id === existingCreated.body.item.id);
        return item?.technical === true ? item : null;
    });
    assert.equal(existingItem.interaction.instruction, "revisa tú esto");
    assert.equal(existing.interactionRunner.calls.length, 1);
});
