// The Auto quality ladder (brief §4.4), as a PURE module: presets, the
// good/congested threshold, and the step controller. No sockets, no timers --
// the stream plugs telemetry in and follows the decisions out.

export const QUALITY_LADDER = [
    { name: "baja", scale: 0.4, jpegQuality: 40, maxFps: 10 },
    { name: "media", scale: 0.7, jpegQuality: 55, maxFps: 15 },
    { name: "alta", scale: 1.0, jpegQuality: 75, maxFps: 24 },
    // Owner target: 30fps sustained on a good link. Same viewport as alta --
    // the extra headroom goes to frame RATE, not to more pixels.
    { name: "ultra", scale: 1.0, jpegQuality: 80, maxFps: 30 },
];

export function createLadderController({ startIndex = 1 } = {}) {
    let index = Math.min(Math.max(startIndex, 0), QUALITY_LADDER.length - 1);
    let goodStreak = 0;

    return {
        /** Render time over which a frame counts as congested. */
        BAD_RENDER_MS: 200,

        /** Consecutive good frames required before trusting the link again. */
        GOOD_FRAMES_TO_STEP_UP: 8,

        preset() {
            return QUALITY_LADDER[index];
        },

        /**
         * Feeds one ack's telemetry and returns { direction }:
         * "down" on the FIRST congested frame, "up" only after
         * GOOD_FRAMES_TO_STEP_UP consecutive good ones, else "stay".
         */
        observe({ renderMs }) {
            const bad =
                typeof renderMs === "number" && renderMs > this.BAD_RENDER_MS;
            if (bad) {
                if (index > 0) {
                    index -= 1;
                    goodStreak = 0;
                    return { direction: "down", to: this.preset().name };
                }
                goodStreak = 0;
                return { direction: "stay", to: this.preset().name };
            }
            goodStreak += 1;
            if (
                goodStreak >= this.GOOD_FRAMES_TO_STEP_UP &&
                index < QUALITY_LADDER.length - 1
            ) {
                index += 1;
                goodStreak = 0;
                return { direction: "up", to: this.preset().name };
            }
            return { direction: "stay", to: this.preset().name };
        },
    };
}
