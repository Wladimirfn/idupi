// Tests for the health app profiles (phone-health, T1). Every fixture is
// synthetic: the values are the documented formats from odd/tasks/phone-health.md,
// never a real owner dump.
//
// Run (from repo root):
//   node --test idupi-server/test/health-profiles.test.mjs

import test from "node:test";
import assert from "node:assert/strict";

import {
    HEALTH_PACKAGES,
    buildItemBlocks,
    hasAnyMetric,
    parseClock,
    parseHealthApp,
    parseHealthNumber,
    parseHuaweiHealthHoy,
    parseSleepMinutes,
    parseStepsValue,
    resourceIdName,
    sortNodesInReadingOrder,
    toStoreMetrics,
} from "../lib/health-profiles.mjs";

const HUAWEI = "com.huawei.health";
const HONOR = "com.hihonor.health";

let rowCounter = 0;

/** Synthetic node in the shape parseUiXml produces. */
function node({ packageName = HUAWEI, id = "", text = "", y = null, x = 0 } = {}) {
    const top = y === null ? (rowCounter += 100) : y;
    return {
        text,
        resourceId: id ? `${packageName}:id/${id}` : "",
        contentDesc: "",
        packageName,
        bounds: `[${x},${top}][${x + 500},${top + 60}]`,
        rect: { x1: x, y1: top, x2: x + 500, y2: top + 60 },
    };
}

/** The Huawei Health "Hoy" screen, using only the ids recorded in the task. */
function huaweiHoy(packageName = HUAWEI) {
    rowCounter = 0;
    return [
        node({ packageName, id: "step_text", text: "2,444/10,000 pasos", y: 0 }),
        node({ packageName, id: "title_text", text: "Levantarse", y: 100 }),
        node({ packageName, id: "value_text", text: "07:15", y: 100, x: 200 }),
        node({ packageName, id: "goal_text", text: "07:00", y: 100, x: 350 }),
        node({ packageName, id: "title_text", text: "Ejercicio", y: 200 }),
        node({ packageName, id: "value_text", text: "15.42 min", y: 200, x: 200 }),
        node({ packageName, id: "goal_text", text: "30 min", y: 200, x: 350 }),
        node({ packageName, id: "title_text", text: "Estrés", y: 300 }),
        node({ packageName, id: "value_text", text: "35", y: 300, x: 200 }),
        node({ packageName, id: "goal_text", text: "<60", y: 300, x: 350 }),
        node({ packageName, id: "title_text", text: "Consumo de agua", y: 400 }),
        node({ packageName, id: "value_text", text: "2,000 ml", y: 400, x: 200 }),
        node({ packageName, id: "goal_text", text: "3,000 ml", y: 400, x: 350 }),
        node({ packageName, id: "function_set_items_title", text: "Registros de ejercicio", y: 500 }),
        node({ packageName, id: "function_set_items_data", text: "45 min", y: 500, x: 200 }),
        node({ packageName, id: "function_set_items_unit", text: "Caminar", y: 500, x: 350 }),
        node({ packageName, id: "function_set_items_title", text: "Corazón", y: 600 }),
        node({ packageName, id: "function_set_items_data", text: "72", y: 600, x: 200 }),
        node({ packageName, id: "function_set_items_unit", text: "p. p. m.", y: 600, x: 350 }),
        node({ packageName, id: "function_set_items_title", text: "Peso", y: 700 }),
        node({ packageName, id: "function_set_items_data", text: "75,5", y: 700, x: 200 }),
        node({ packageName, id: "function_set_items_unit", text: "kg", y: 700, x: 350 }),
        node({ packageName, id: "function_set_items_title", text: "Sueño", y: 800 }),
        node({ packageName, id: "function_set_items_data", text: "8 h 29 min", y: 800, x: 200 }),
        node({ packageName, id: "function_set_items_unit", text: "", y: 800, x: 350 }),
    ];
}

test("parseHealthNumber handles thousands and decimal separators", () => {
    assert.equal(parseHealthNumber("2,444"), 2444);
    assert.equal(parseHealthNumber("2.444"), 2444);
    assert.equal(parseHealthNumber("10,000"), 10000);
    assert.equal(parseHealthNumber("15.42"), 15.42);
    assert.equal(parseHealthNumber("15,42"), 15.42);
    assert.equal(parseHealthNumber("1.234,5"), 1234.5);
    assert.equal(parseHealthNumber("72 p. p. m."), 72);
    assert.equal(parseHealthNumber("sin datos"), null);
    assert.equal(parseHealthNumber(null), null);
});

test("parseStepsValue splits value and goal", () => {
    assert.deepEqual(parseStepsValue("2,444/10,000 pasos"), { value: 2444, goal: 10000 });
    assert.deepEqual(parseStepsValue("2,444"), { value: 2444, goal: null });
});

test("parseSleepMinutes understands hours and minutes", () => {
    assert.equal(parseSleepMinutes("8 h 29 min"), 509);
    assert.equal(parseSleepMinutes("45 min"), 45);
    assert.equal(parseSleepMinutes("8 h"), 480);
    assert.equal(parseSleepMinutes("nada"), null);
});

test("parseClock normalizes valid times and rejects garbage", () => {
    assert.equal(parseClock("07:15"), "07:15");
    assert.equal(parseClock("7:05"), "07:05");
    assert.equal(parseClock("25:00"), null);
    assert.equal(parseClock("dormir"), null);
});

test("resourceIdName and reading order work on the raw dump shape", () => {
    assert.equal(resourceIdName("com.huawei.health:id/step_text"), "step_text");
    assert.equal(resourceIdName(""), "");
    const nodes = [
        node({ text: "b", y: 200 }),
        node({ text: "a", y: 100 }),
    ];
    assert.deepEqual(sortNodesInReadingOrder(nodes).map((entry) => entry.text), ["a", "b"]);
});

test("buildItemBlocks pairs a title with its value/goal nodes", () => {
    const blocks = buildItemBlocks(huaweiHoy());
    const agua = blocks.find((block) => block.title === "Consumo de agua");
    assert.ok(agua);
    assert.deepEqual(
        agua.values.map((value) => value.text),
        ["2,000 ml", "3,000 ml"],
    );
    assert.equal(blocks.filter((block) => block.kind === "title_text").length, 4);
});

test("parseHuaweiHealthHoy extracts the whole Hoy screen", () => {
    const metrics = parseHuaweiHealthHoy(huaweiHoy());
    assert.equal(metrics.steps, 2444);
    assert.equal(metrics.stepsGoal, 10000);
    assert.equal(metrics.wakeTime, "07:15");
    assert.equal(metrics.wakeGoal, "07:00");
    assert.equal(metrics.exerciseMinutes, 15);
    assert.equal(metrics.exerciseGoalMinutes, 30);
    assert.equal(metrics.stress, 35);
    assert.equal(metrics.stressLabel, "bajo");
    assert.equal(metrics.waterMl, 2000);
    assert.equal(metrics.waterGoalMl, 3000);
    assert.equal(metrics.heartRateBpm, 72);
    assert.equal(metrics.weightKg, 75.5);
    assert.equal(metrics.sleepMinutes, 509);
    assert.deepEqual(metrics.lastWorkout, { minutes: 45, type: "Caminar", date: null });
    assert.equal(metrics.bedtimeGoal, null);
});

test("parseHuaweiHealthHoy never throws on an empty or unrelated dump", () => {
    for (const nodes of [[], [node({ id: "other", text: "Hola" })], null, undefined]) {
        const metrics = parseHuaweiHealthHoy(nodes);
        assert.equal(metrics.steps, null);
        assert.equal(metrics.sleepMinutes, null);
        assert.equal(metrics.lastWorkout, null);
    }
});

test("a bedtime block fills bedtimeGoal when the screen has one", () => {
    rowCounter = 0;
    const metrics = parseHuaweiHealthHoy([
        node({ id: "title_text", text: "Dormir", y: 0 }),
        node({ id: "value_text", text: "23:00", y: 0, x: 200 }),
    ]);
    assert.equal(metrics.bedtimeGoal, "23:00");
});

test("Honor Health reuses the Huawei ids and is marked tentative", () => {
    const parsed = parseHealthApp(HONOR, huaweiHoy(HONOR));
    assert.equal(parsed.source, "huawei_health");
    assert.equal(parsed.sourceApp, HONOR);
    assert.equal(parsed.tentative, true);
    assert.equal(parsed.metrics.steps, 2444);

    const huawei = parseHealthApp(HUAWEI, huaweiHoy());
    assert.equal(huawei.tentative, false);
    assert.equal(HEALTH_PACKAGES[HUAWEI].tentative, false);
    assert.equal(parseHealthApp("com.example.health", []), null);
});

test("toStoreMetrics drops profile-only fields and maps lastWorkout to workouts", () => {
    const metrics = parseHuaweiHealthHoy(huaweiHoy());
    const stored = toStoreMetrics(metrics);
    assert.equal("lastWorkout" in stored, false);
    assert.deepEqual(stored.workouts, [{ type: "Caminar", minutes: 45 }]);
    assert.equal(stored.steps, 2444);
    assert.equal("wakeTime" in stored, true);
    assert.equal(hasAnyMetric(stored), true);
    assert.equal(hasAnyMetric({ steps: null, sleepMinutes: null }), false);
});
