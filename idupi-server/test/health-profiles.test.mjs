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

/** Synthetic node with an explicit rectangle (`[0,0][0,0]` = laid out off screen). */
function at(packageName, id, text, x1, y1, x2, y2) {
    return {
        text,
        resourceId: id ? `${packageName}:id/${id}` : "",
        contentDesc: "",
        packageName,
        bounds: `[${x1},${y1}][${x2},${y2}]`,
        rect: { x1, y1, x2, y2 },
    };
}

/**
 * The Huawei Health "Hoy" screen with the real layout recorded live on
 * 2026-11-24 (four rings in columns, two cards per row, cards below the fold
 * dumped with zero rectangles) and invented values.
 */
function huaweiHoy(packageName = HUAWEI) {
    const n = (id, text, x1, y1, x2, y2) => at(packageName, id, text, x1, y1, x2, y2);
    return [
        n("action_bar_title", "Hoy", 44, 136, 177, 211),
        n("step_text", "2,444/10,000 pasos", 105, 254, 499, 313),
        n("title_text", "Levantarse", 95, 835, 258, 918),
        n("value_text", "07:15", 73, 926, 252, 992),
        n("goal_text", "07:00", 120, 1007, 204, 1046),
        n("title_text", "Ejercicio", 365, 835, 490, 918),
        n("value_text", "12", 393, 926, 433, 992),
        n("goal_text", "/30 min", 356, 1007, 471, 1046),
        n("title_text", "Estrés", 632, 835, 725, 918),
        n("value_text", "35", 567, 926, 647, 992),
        n("value_des", "Normal", 647, 950, 756, 989),
        n("goal_text", "<60", 637, 1007, 692, 1046),
        n("title_text", "Consumo de agua", 825, 835, 1036, 918),
        n("value_text", "450", 896, 926, 936, 992),
        n("goal_text", "2,000 ml", 852, 1007, 980, 1046),
        n("quick_entry_text", "Hora de acostarse: 22:30", 155, 1156, 651, 1255),
        n("function_set_items_title", "Registros de ejercicio", 66, 1365, 427, 1417),
        n("function_set_items_data", "45 min", 66, 1423, 263, 1504),
        n("function_set_bottom_left", "20/11", 68, 1853, 158, 1899),
        n("function_set_bottom_right", "Caminar", 180, 1853, 441, 1899),
        n("theme", "Plan de entrenamiento", 121, 1868, 463, 1912),
        n("function_set_items_title", "Corazón", 66, 2011, 208, 2063),
        n("function_set_items_data", "Frecuencia cardíaca: 72 p. p. m.", 66, 2069, 491, 2185),
        n("function_set_bottom_left", "FC en reposo promedio reciente: 58 p. p. m.", 0, 0, 0, 0),
        n("function_set_items_title", "Peso", 589, 2011, 675, 2063),
        n("function_set_items_data", "75,5", 589, 2069, 726, 2144),
        n("function_set_items_unit", "kg", 737, 2089, 773, 2133),
        n("function_set_items_title", "Sueño", 0, 0, 0, 0),
        n("function_set_items_data", "7 h 32 min", 0, 0, 0, 0),
        n("function_set_items_title", "SpO₂", 0, 0, 0, 0),
        n("function_set_items_data", "97%", 0, 0, 0, 0),
        n("function_set_bottom_left", "24/11", 0, 0, 0, 0),
        n("content", "Hoy", 83, 2218, 132, 2262),
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
        ["450", "2,000 ml"],
    );
    assert.equal(blocks.filter((block) => block.kind === "title_text").length, 4);
});

test("parseHuaweiHealthHoy extracts the whole Hoy screen", () => {
    const metrics = parseHuaweiHealthHoy(huaweiHoy());
    assert.equal(metrics.steps, 2444);
    assert.equal(metrics.stepsGoal, 10000);
    assert.equal(metrics.wakeTime, "07:15");
    assert.equal(metrics.wakeGoal, "07:00");
    assert.equal(metrics.exerciseMinutes, 12);
    assert.equal(metrics.exerciseGoalMinutes, 30);
    assert.equal(metrics.stress, 35);
    assert.equal(metrics.stressLabel, "Normal");
    assert.equal(metrics.waterMl, 450);
    assert.equal(metrics.waterGoalMl, 2000);
    assert.equal(metrics.bedtimeGoal, "22:30");
    // Two-column cards: the heart value must not leak into the weight card.
    assert.equal(metrics.heartRateBpm, 72);
    assert.equal(metrics.restingHeartRateBpm, 58);
    assert.equal(metrics.weightKg, 75.5);
    // Off-screen cards ([0,0][0,0]) are paired in document order.
    assert.equal(metrics.sleepMinutes, 452);
    assert.equal(metrics.spo2Pct, 97);
    assert.deepEqual(metrics.lastWorkout, { minutes: 45, type: "Caminar", date: "20/11" });
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

test("a split steps block pairs the value and goal nodes", () => {
    rowCounter = 0;
    const metrics = parseHuaweiHealthHoy([
        node({ id: "title_text", text: "Pasos", y: 0 }),
        node({ id: "value_text", text: "2,444", y: 0, x: 200 }),
        node({ id: "goal_text", text: "10,000", y: 0, x: 350 }),
    ]);
    assert.equal(metrics.steps, 2444);
    assert.equal(metrics.stepsGoal, 10000);
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
    assert.equal(stored.spo2Pct, 97);
    assert.equal(stored.restingHeartRateBpm, 58);
    assert.equal(stored.steps, 2444);
    assert.equal("wakeTime" in stored, true);
    assert.equal(hasAnyMetric(stored), true);
    assert.equal(hasAnyMetric({ steps: null, sleepMinutes: null }), false);
});
