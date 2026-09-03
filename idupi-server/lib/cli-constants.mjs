// ============================================================================
// CLI timing constants used by the chat bridge.
//
// These live in their own module so the timer-vs-backstop invariant is one
// comparison, not a hunt through a 4000-line file. The grace window below MUST
// stay strictly less than the 300s `AGENT_CLI_TIMEOUT_MS` taskkill backstop
// declared in `index.mjs`. The assertion at the bottom of this module is the
// only place we hard-fail that contract; everything else just reads the
// constant.
// ============================================================================

/**
 * Grace window given to the app to answer a `select` / `confirm` / `input`
 * request before the registry's blanket auto-approve fires. MUST stay strictly
 * below the 300s `AGENT_CLI_TIMEOUT_MS` backstop so the timer resolves the
 * request before the taskkill ever has a chance to.
 *
 * Phase A: even with no client speaking, the registry auto-approves after this
 * delay, so old APKs and quiet clients never trigger the 300s backstop.
 */
export const UI_REQUEST_DEADLINE_MS = 120_000;

/**
 * Hard ceiling for any per-turn deadline. Matches the existing
 * `AGENT_CLI_TIMEOUT_MS` declared in `index.mjs` (module-scope test asserts
 * its column-0 placement there). We re-state the value here as a reference so
 * the invariant check below is self-contained, and so anyone editing
 * `index.mjs` immediately sees the contract this file enforces.
 */
export const TASKKILL_BACKSTOP_MS = 5 * 60 * 1000;

/**
 * Predicate used by tests and by `PendingUiRequestRegistry` to fail fast if a
 * future edit tries to push the grace window above the backstop. Kept as a
 * named function (rather than an inline comparison) so the call sites read as
 * the intent — "is this deadline still safe under the backstop?" — not as a
 * bare `<`.
 */
export function isWithinTaskkillBackstop(deadlineMs, backstopMs = TASKKILL_BACKSTOP_MS) {
    return Number.isFinite(deadlineMs) && Number.isFinite(backstopMs) && deadlineMs < backstopMs;
}

const safe = isWithinTaskkillBackstop(UI_REQUEST_DEADLINE_MS);
if (!safe) {
    // Hard-fail at module load. A timer equal to or greater than the taskkill
    // backstop is a contract violation: the registry's auto-approve would
    // race the 300s kill, and the spec ("resolve exactly once before it")
    // becomes impossible to keep. Refusing to start is louder than silently
    // shipping the bug.
    throw new Error(
        `[cli-constants] UI_REQUEST_DEADLINE_MS (${UI_REQUEST_DEADLINE_MS}ms) ` +
        `is not strictly less than the 300s taskkill backstop. ` +
        `Edit lib/cli-constants.mjs and lower UI_REQUEST_DEADLINE_MS.`,
    );
}
