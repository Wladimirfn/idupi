import test from "node:test";
import assert from "node:assert/strict";

import {
    QUALITY_LADDER,
    createLadderController,
} from "../lib/screen-quality.mjs";

// The Auto control loop (brief §4.4): step down FAST on congestion -- one bad
// frame is enough, reacting late ruins the session -- and step UP slowly only
// after sustained headroom, because climbing eagerly re-breaks it. Same shape
// as TCP congestion control / adaptive bitrate.

test("the ladder carries the three documented presets", () => {
    assert.deepEqual(
        QUALITY_LADDER.map((p) => p.name),
        ["baja", "media", "alta"],
    );
    assert.deepEqual(
        QUALITY_LADDER.map((p) => [p.scale, p.jpegQuality, p.maxFps]),
        [
            [0.4, 40, 10],
            [0.7, 55, 15],
            [1.0, 75, 24],
        ],
    );
});

test("auto starts at MEDIA by default", () => {
    const c = createLadderController();
    assert.equal(c.preset().name, "media");
});

test("one congested frame steps DOWN immediately and resets the streak", () => {
    const c = createLadderController(); // media
    const decision = c.observe({ renderMs: 400 });
    assert.equal(decision.direction, "down");
    assert.equal(c.preset().name, "baja");

    // The streak must be gone: one good frame after the fall does NOT climb.
    c.observe({ renderMs: 10 });
    assert.equal(c.preset().name, "baja");
});

test("stepping up requires several consecutive good frames", () => {
    const c = createLadderController(); // media
    for (let i = 0; i < c.GOOD_FRAMES_TO_STEP_UP - 1; i++) {
        const d = c.observe({ renderMs: 8 });
        assert.equal(d.direction, "stay");
    }
    const last = c.observe({ renderMs: 8 });
    assert.equal(last.direction, "up");
    assert.equal(c.preset().name, "alta");
});

test("alta is the ceiling and baja is the floor", () => {
    const c = createLadderController({ startIndex: QUALITY_LADDER.length - 1 });
    for (let i = 0; i < 20; i++) c.observe({ renderMs: 5 });
    assert.equal(c.preset().name, "alta"); // never climbs past the top

    for (let i = 0; i < 20; i++) c.observe({ renderMs: 900 });
    assert.equal(c.preset().name, "baja"); // never falls below the bottom
});

test("the threshold is what separates a good frame from a congested one", () => {
    const c = createLadderController();
    // Just under the line: good.
    assert.equal(c.observe({ renderMs: c.BAD_RENDER_MS - 1 }).direction, "stay");
    // Just over it: congested.
    assert.equal(c.observe({ renderMs: c.BAD_RENDER_MS + 1 }).direction, "down");
});
