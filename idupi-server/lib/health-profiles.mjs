// Health app profiles (phone-health, T1).
//
// The hidden-display reader returns a uiautomator dump. This module turns the
// nodes of one "today" screen into the metric object the store understands.
// Everything is pure: profiles are unit-tested with synthetic nodes built from
// the resource ids recorded in odd/tasks/phone-health.md, never from a real
// owner dump.
//
// Huawei Health and Honor Health share the same resource-id names
// (`<package>:id/<name>`); Honor is marked tentative until the owner's device
// confirms it. Unknown or missing values stay null; parsing never throws.

/** Allowlisted health packages and the source each one feeds. */
export const HEALTH_PACKAGES = {
    "com.huawei.health": {
        label: "Huawei Health",
        source: "huawei_health",
        tentative: false,
    },
    "com.hihonor.health": {
        label: "Honor Health",
        source: "huawei_health",
        tentative: true,
    },
};

export const HEALTH_PACKAGE_LIST = Object.keys(HEALTH_PACKAGES);

export function healthPackageInfo(packageName) {
    return HEALTH_PACKAGES[packageName] || null;
}

export function isHealthPackage(packageName) {
    return Object.prototype.hasOwnProperty.call(HEALTH_PACKAGES, packageName);
}

const BLOCK_START_IDS = new Set([
    "step_text",
    "title_text",
    "function_set_items_title",
    "quick_entry_text",
]);

const VALUE_IDS = new Set([
    "value_text",
    "goal_text",
    "value_des",
    "function_set_items_data",
    "function_set_items_unit",
    "function_set_bottom_left",
    "function_set_bottom_right",
]);

/** Last path segment of a resource id (`pkg:id/name` -> `name`). */
export function resourceIdName(resourceId) {
    const text = typeof resourceId === "string" ? resourceId : "";
    if (!text) return "";
    const slash = text.lastIndexOf("/");
    return slash >= 0 ? text.slice(slash + 1) : text;
}

function normalizeLabel(value) {
    return String(value ?? "")
        .normalize("NFD")
        .replace(/[\u0300-\u036f]/g, "")
        .replace(/\s+/g, " ")
        .trim()
        .toLowerCase();
}

function nodeTop(node) {
    const rect = node && node.rect;
    return rect && Number.isFinite(rect.y1) ? rect.y1 : Number.MAX_SAFE_INTEGER;
}

function nodeLeft(node) {
    const rect = node && node.rect;
    return rect && Number.isFinite(rect.x1) ? rect.x1 : Number.MAX_SAFE_INTEGER;
}

/** Reading order: top to bottom, then left to right. Never mutates input. */
export function sortNodesInReadingOrder(nodes) {
    return [...(Array.isArray(nodes) ? nodes : [])].sort((left, right) => {
        const dy = nodeTop(left) - nodeTop(right);
        if (dy !== 0) return dy;
        return nodeLeft(left) - nodeLeft(right);
    });
}

/**
 * True when the node has a real on-screen rectangle. Items the app laid out
 * outside the visible area are dumped with `[0,0][0,0]` and must not be placed
 * by geometry.
 */
function hasGeometry(node) {
    const rect = node && node.rect;
    return Boolean(
        rect &&
            [rect.x1, rect.y1, rect.x2, rect.y2].every(Number.isFinite) &&
            rect.x2 > rect.x1 &&
            rect.y2 > rect.y1,
    );
}

/** How far below its title a value may sit and still belong to it (px). */
const MAX_VALUE_DROP_PX = 560;

/**
 * Groups the dump into item blocks. Huawei lays items out in columns (four
 * rings in a row, two cards per row), so reading order alone pairs a title with
 * its neighbour's value. A value with geometry joins the title that sits above
 * it (within MAX_VALUE_DROP_PX) and is horizontally closest. A value without
 * geometry (`[0,0][0,0]`, laid out off screen) joins the nearest preceding
 * title in document order, which is how the app nests each card. Only known
 * value ids join a block: decorative texts (plan banners, tabs) are ignored.
 */
export function buildItemBlocks(nodes) {
    const list = Array.isArray(nodes) ? nodes : [];
    const blocks = [];
    const byNode = new Map();
    let lastInDocument = null;
    for (const node of list) {
        if (!BLOCK_START_IDS.has(resourceIdName(node.resourceId))) continue;
        const block = {
            kind: resourceIdName(node.resourceId),
            title: typeof node.text === "string" ? node.text : "",
            geometry: hasGeometry(node),
            top: hasGeometry(node) ? node.rect.y1 : null,
            centerX: hasGeometry(node) ? (node.rect.x1 + node.rect.x2) / 2 : null,
            values: [],
        };
        blocks.push(block);
        byNode.set(node, block);
    }

    for (const node of list) {
        const name = resourceIdName(node.resourceId);
        if (byNode.has(node)) {
            lastInDocument = byNode.get(node);
            continue;
        }
        if (!VALUE_IDS.has(name)) continue;
        const value = { id: name, text: typeof node.text === "string" ? node.text : "" };
        let owner = null;
        if (hasGeometry(node)) {
            const top = node.rect.y1;
            const centerX = (node.rect.x1 + node.rect.x2) / 2;
            let best = Infinity;
            for (const block of blocks) {
                if (!block.geometry || block.top > top) continue;
                if (top - block.top > MAX_VALUE_DROP_PX) continue;
                const distance = Math.abs(block.centerX - centerX);
                if (distance < best) {
                    best = distance;
                    owner = block;
                }
            }
        }
        (owner || lastInDocument)?.values.push(value);
    }

    // Reading order for the result keeps the parse deterministic; blocks laid
    // out off screen keep their document order after the visible ones.
    const visible = blocks.filter((block) => block.geometry)
        .sort((left, right) => left.top - right.top || left.centerX - right.centerX);
    const offscreen = blocks.filter((block) => !block.geometry);
    return [...visible, ...offscreen].map(({ kind, title, values }) => ({ kind, title, values }));
}

// --- number and unit parsing ----------------------------------------------

/**
 * Parses a localized number: thousands separators (`2,444`, `2.444`) and
 * decimal comma or point (`15,42`, `15.42`). Returns null when nothing numeric
 * is left.
 */
export function parseHealthNumber(raw) {
    if (typeof raw === "number") return Number.isFinite(raw) ? raw : null;
    if (typeof raw !== "string") return null;
    let text = raw.replace(/[^\d.,-]/g, "").trim();
    if (!text || !/\d/.test(text)) return null;
    const lastComma = text.lastIndexOf(",");
    const lastDot = text.lastIndexOf(".");
    if (lastComma >= 0 && lastDot >= 0) {
        if (lastComma > lastDot) {
            text = text.replace(/\./g, "").replace(",", ".");
        } else {
            text = text.replace(/,/g, "");
        }
    } else if (lastComma >= 0) {
        const decimals = text.length - lastComma - 1;
        text = decimals === 3 ? text.replace(/,/g, "") : text.replace(",", ".");
    } else if (lastDot >= 0) {
        const dots = (text.match(/\./g) || []).length;
        if (dots > 1) text = text.replace(/\./g, "");
        else if (text.length - lastDot - 1 === 3) text = text.replace(".", "");
    }
    const value = Number(text);
    return Number.isFinite(value) ? value : null;
}

/** `"2,444/10,000 pasos"` -> `{value:2444, goal:10000}`. */
export function parseStepsValue(raw) {
    const text = String(raw ?? "");
    const pair = /([\d.,]+)\s*\/\s*([\d.,]+)/.exec(text);
    if (pair) {
        return {
            value: parseHealthNumber(pair[1]),
            goal: parseHealthNumber(pair[2]),
        };
    }
    return { value: parseHealthNumber(text), goal: null };
}

/** `"8 h 29 min"` -> 509; `"45 min"` -> 45; `"8 h"` -> 480. */
export function parseSleepMinutes(raw) {
    const text = String(raw ?? "");
    const hours = /(\d+(?:[.,]\d+)?)\s*h(?:oras?)?\b/i.exec(text);
    const minutes = /(\d+(?:[.,]\d+)?)\s*min\b/i.exec(text);
    if (hours || minutes) {
        const h = hours ? parseHealthNumber(hours[1]) : 0;
        const m = minutes ? parseHealthNumber(minutes[1]) : 0;
        if (!Number.isFinite(h) && !Number.isFinite(m)) return null;
        return Math.round((Number.isFinite(h) ? h : 0) * 60 + (Number.isFinite(m) ? m : 0));
    }
    const plain = parseHealthNumber(text);
    return plain === null ? null : Math.round(plain);
}

/** `"07:15"` -> `"07:15"`; anything else -> null. */
export function parseClock(raw) {
    const match = /(\d{1,2}):(\d{2})/.exec(String(raw ?? ""));
    if (!match) return null;
    const hours = Number(match[1]);
    const minutes = Number(match[2]);
    if (!Number.isInteger(hours) || hours > 23) return null;
    if (!Number.isInteger(minutes) || minutes > 59) return null;
    return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`;
}

function roundMinutes(value) {
    return value === null ? null : Math.round(value);
}

function deriveStressLabel(stress) {
    if (stress === null) return null;
    if (stress < 40) return "bajo";
    if (stress < 60) return "moderado";
    if (stress < 80) return "alto";
    return "muy alto";
}

const UNIT_WORDS = /^(min|mins|minuto|minutos|h|hr|hrs|hora|horas|kg|g|lb|ml|l|bpm|ppm|pasos|steps|kcal|cal|p\.?\s*p\.?\s*m\.?)$/i;

const EMPTY_METRICS = () => ({
    steps: null,
    stepsGoal: null,
    wakeTime: null,
    wakeGoal: null,
    exerciseMinutes: null,
    exerciseGoalMinutes: null,
    stress: null,
    stressLabel: null,
    waterMl: null,
    waterGoalMl: null,
    bedtimeGoal: null,
    heartRateBpm: null,
    restingHeartRateBpm: null,
    spo2Pct: null,
    weightKg: null,
    sleepMinutes: null,
    lastWorkout: null,
});

function firstValue(block, id) {
    const entry = block.values.find((value) => value.id === id && value.text.trim());
    return entry ? entry.text.trim() : null;
}

function firstNumber(block) {
    for (const value of block.values) {
        const number = parseHealthNumber(value.text);
        if (number !== null) return number;
    }
    return null;
}

function numbersIn(block) {
    return block.values
        .map((value) => parseHealthNumber(value.text))
        .filter((number) => number !== null);
}

/**
 * Parses one Huawei Health "Hoy" screen. Returns every known metric key with
 * null for anything the screen did not show. Never throws.
 */
export function parseHuaweiHealthHoy(nodes) {
    const metrics = EMPTY_METRICS();
    const blocks = buildItemBlocks(nodes);

    for (const block of blocks) {
        const label = normalizeLabel(block.title);
        const texts = block.values.map((value) => value.text).filter(Boolean);

        if (block.kind === "step_text" || label.includes("paso")) {
            const valueText = firstValue(block, "value_text");
            const goalText = firstValue(block, "goal_text");
            const steps = valueText
                ? { value: parseHealthNumber(valueText), goal: parseHealthNumber(goalText) }
                : parseStepsValue([block.title, ...texts].join(" "));
            if (steps.value !== null) metrics.steps = Math.round(steps.value);
            if (steps.goal !== null) metrics.stepsGoal = Math.round(steps.goal);
            continue;
        }

        if (label === "levantarse" || label.startsWith("levantarse")) {
            const times = texts.map(parseClock).filter(Boolean);
            metrics.wakeTime = parseClock(firstValue(block, "value_text")) || times[0] || null;
            metrics.wakeGoal = parseClock(firstValue(block, "goal_text")) || times[1] || null;
            continue;
        }

        if (label === "ejercicio" || label.startsWith("ejercicio")) {
            const values = numbersIn(block);
            metrics.exerciseMinutes = roundMinutes(values[0] ?? null);
            metrics.exerciseGoalMinutes = roundMinutes(values[1] ?? null);
            continue;
        }

        if (label.startsWith("estres")) {
            const values = numbersIn(block);
            metrics.stress = values.length > 0 ? Math.round(values[0]) : null;
            const descriptive = texts.find((text) => !/\d/.test(text));
            metrics.stressLabel = descriptive
                ? descriptive.trim()
                : deriveStressLabel(metrics.stress);
            continue;
        }

        if (label.includes("agua")) {
            const values = numbersIn(block);
            metrics.waterMl = values.length > 0 ? Math.round(values[0]) : null;
            metrics.waterGoalMl = values.length > 1 ? Math.round(values[1]) : null;
            continue;
        }

        if (label.includes("registros de ejercicio") || label.includes("registro de ejercicio")) {
            const minutes = parseSleepMinutes(firstValue(block, "function_set_items_data") || "");
            // The card footer holds the workout date (left) and type (right).
            const type = firstValue(block, "function_set_bottom_right");
            if (minutes !== null || type) {
                metrics.lastWorkout = {
                    minutes,
                    type: type && !UNIT_WORDS.test(type) ? type : null,
                    date: firstValue(block, "function_set_bottom_left"),
                };
            }
            continue;
        }

        if (label.startsWith("corazon")) {
            const number = parseHealthNumber(firstValue(block, "function_set_items_data"));
            metrics.heartRateBpm = number === null ? null : Math.round(number);
            // Footer: "FC en reposo promedio reciente: 62 p. p. m."
            const resting = texts.find((text) => /reposo/i.test(text));
            const restingValue = resting ? parseHealthNumber(resting) : null;
            metrics.restingHeartRateBpm = restingValue === null ? null : Math.round(restingValue);
            continue;
        }

        if (label.startsWith("spo")) {
            const number = parseHealthNumber(firstValue(block, "function_set_items_data"));
            metrics.spo2Pct = number === null ? null : Math.round(number);
            continue;
        }

        if (label.startsWith("peso")) {
            metrics.weightKg = firstNumber(block);
            continue;
        }

        if (label.startsWith("sueno")) {
            const candidates = [firstValue(block, "function_set_items_data"), ...texts]
                .filter(Boolean);
            for (const candidate of candidates) {
                const minutes = parseSleepMinutes(candidate);
                if (minutes !== null) {
                    metrics.sleepMinutes = minutes;
                    break;
                }
            }
            continue;
        }

        if (label.includes("dormir") || label.includes("acostarse")) {
            // The shortcut row carries the clock in its own title
            // ("Hora de acostarse: 23:00").
            const times = [block.title, ...texts].map(parseClock).filter(Boolean);
            const clock = parseClock(firstValue(block, "value_text")) || times[0] || null;
            if (clock) metrics.bedtimeGoal = clock;
        }
    }

    return metrics;
}

/** Honor Health reuses the Huawei ids; the screen is the same shape. */
export function parseHonorHealthHoy(nodes) {
    return parseHuaweiHealthHoy(nodes);
}

const PARSERS = {
    huawei_health: parseHuaweiHealthHoy,
};

/**
 * Parses a dump for one allowlisted package.
 *
 * @returns {{source: string, sourceApp: string, tentative: boolean, metrics: object} | null}
 */
export function parseHealthApp(packageName, nodes) {
    const info = healthPackageInfo(packageName);
    if (!info) return null;
    const parser = packageName === "com.hihonor.health"
        ? parseHonorHealthHoy
        : PARSERS[info.source] || parseHuaweiHealthHoy;
    return {
        source: info.source,
        sourceApp: packageName,
        tentative: info.tentative === true,
        metrics: parser(nodes),
    };
}

/** True when at least one metric has a value. */
export function hasAnyMetric(metrics) {
    if (!metrics || typeof metrics !== "object") return false;
    return Object.values(metrics).some((value) => {
        if (value === null || value === undefined) return false;
        if (typeof value === "object") {
            return Object.values(value).some((inner) => inner !== null && inner !== undefined);
        }
        return true;
    });
}

/**
 * Maps a profile result to the store schema. `lastWorkout` is a profile-only
 * convenience; the store keeps a `workouts` array instead.
 */
export function toStoreMetrics(metrics) {
    const source = metrics && typeof metrics === "object" ? metrics : {};
    const out = {};
    for (const [key, value] of Object.entries(source)) {
        if (key === "lastWorkout") continue;
        if (value === null || value === undefined) continue;
        out[key] = value;
    }
    const workout = source.lastWorkout;
    if (workout && typeof workout === "object") {
        const entry = {};
        if (workout.type) entry.type = String(workout.type);
        if (workout.minutes !== null && workout.minutes !== undefined) {
            entry.minutes = Math.round(workout.minutes);
        }
        if (Object.keys(entry).length > 0) out.workouts = [entry];
    }
    return out;
}
