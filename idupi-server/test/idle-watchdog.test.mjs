// ============================================================================
// idupi-server/test/idle-watchdog.test.mjs
//
// The backstop that kills a stuck Pi process used to measure from the start of
// the message, so it killed real work: a turn running a long chain of tools was
// terminated at exactly five minutes while the log showed Pi executing bash.
//
//   [IDUPI Pi RPC Timeout] Sin 'agent_end' tras 300000ms, terminando (PID 11700)
//
// The limit has to be silence, not duration. Pi emits no periodic keepalive --
// every event corresponds to actual work -- so rearming on any event keeps a
// hung tool detectable while a busy one survives.
//
// Run (from repo root):
//   node --test idupi-server/test/idle-watchdog.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const SERVER_FILE = join(dirname(fileURLToPath(import.meta.url)), "..", "index.mjs");
const source = readFileSync(SERVER_FILE, "utf8");

function bodyOf(startPattern) {
    const at = source.search(startPattern);
    if (at === -1) return null;
    const open = source.indexOf("{", at);
    let depth = 0;
    for (let i = open; i < source.length; i++) {
        if (source[i] === "{") depth++;
        else if (source[i] === "}") {
            depth--;
            if (depth === 0) return source.slice(open, i + 1);
        }
    }
    return null;
}

test("the watchdog is armed in exactly one place", () => {
    const arms = source.match(/this\.pendingTimeoutTimer = setTimeout\(/g) || [];
    assert.equal(arms.length, 1, "dos temporizadores compitiendo es peor que ninguno");
    assert.match(source, /armIdleWatchdog\(\) \{/, "no existe armIdleWatchdog");
});

test("arming clears the previous timer instead of stacking timers", () => {
    const body = bodyOf(/armIdleWatchdog\(\) \{/);
    assert.ok(body, "no se encontró armIdleWatchdog");
    const clearAt = body.indexOf("clearTimeout(this.pendingTimeoutTimer)");
    const setAt = body.indexOf("this.pendingTimeoutTimer = setTimeout(");
    assert.notEqual(clearAt, -1, "rearmar sin limpiar deja temporizadores vivos");
    assert.ok(clearAt < setAt, "hay que limpiar ANTES de volver a armar");
});

test("arming does nothing when no message is waiting", () => {
    const body = bodyOf(/armIdleWatchdog\(\) \{/);
    assert.match(
        body,
        /if \(!this\.pendingResolve\) return;/,
        "sin ese guard, un evento suelto armaría un watchdog que no vigila nada",
    );
});

test("every Pi event rearms it, so a busy turn is never killed for being long", () => {
    const body = bodyOf(/handleRpcLine\(line\) \{/);
    assert.ok(body, "no se encontró handleRpcLine");
    const parseAt = body.indexOf("JSON.parse(line)");
    const armAt = body.indexOf("this.armIdleWatchdog()");
    assert.notEqual(armAt, -1, "ningún evento rearma el watchdog: sigue midiendo duración");
    assert.ok(parseAt < armAt, "hay que rearmar después de confirmar que la línea era un evento");
});

test("the timeout reports silence, not a missing agent_end", () => {
    const body = bodyOf(/armIdleWatchdog\(\) \{/);
    assert.match(body, /señales de vida/, "el mensaje debe decir lo que realmente pasó");
    assert.ok(
        !/Sin 'agent_end'/.test(body),
        "ya no es cierto que falte agent_end: lo que falta es actividad",
    );
});

test("a fired watchdog still kills the whole process tree", () => {
    const body = bodyOf(/armIdleWatchdog\(\) \{/);
    assert.match(
        body,
        /taskkill", \["\/F", "\/T", "\/PID"/,
        "sin /T quedan vivos los hijos que Pi haya lanzado",
    );
});
