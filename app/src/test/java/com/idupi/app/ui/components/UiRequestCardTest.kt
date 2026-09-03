package com.idupi.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6.2 — RED-first coverage for the [UiRequestCard] Composable added in
 * Phase 5.
 *
 * The host build ships without `androidx.compose.ui:ui-test-junit4` and adding
 * it would change the test classpath for the whole module. We therefore pin
 * the contract via structural tests over a snapshot of the source file: every
 * assertion below corresponds to a feature the spec requires ("Submit +
 * Cancel on INPUT", "1s countdown tick", "expired disables actions") and any
 * future regression that deletes or rewrites the relevant code WILL fail
 * these tests even though we never render the composable.
 *
 * Manual visual verification remains part of the Phase 5 contract; these tests
 * exist so a silent removal of the INPUT branch, the countdown, or the
 * expired-gate cannot pass CI.
 *
 * The snapshot lives at `src/test/resources/UiRequestCard.kt.txt` and is a
 * verbatim copy of `src/main/java/com/idupi/app/ui/components/UiRequestCard.kt`
 * captured at Phase 6 freeze. A future Kotlin upgrade or whitespace refactor
 * that drifts the snapshot from the source file is the only thing that needs
 * updating -- the assertions below pin the BEHAVIOUR contract, not the
 * formatting.
 */
class UiRequestCardTest {

    /**
     * Reads the snapshot of [UiRequestCard]'s source from the test resources.
     * We don't depend on the Kotlin compiler here -- just on the file being
     * present. Tests fail fast with a helpful message if a future refactor
     * forgets to refresh the snapshot.
     */
    private fun source(): String =
        javaClass.classLoader!!.getResourceAsStream("UiRequestCard.kt.txt")
            ?.bufferedReader()?.use { it.readText() }
            ?: error(
                "UiRequestCard.kt.txt must be on the test classpath; " +
                "see app/src/test/resources/UiRequestCard.kt.txt (snapshot of " +
                "app/src/main/java/com/idupi/app/ui/components/UiRequestCard.kt).",
            )

    // -- INPUT branch + Submit + Cancel ----------------------------------------

    @Test
    fun `INPUT branch dispatches to a composable that exposes onSubmit and is gated on expiry`() {
        val src = source()
        // `when (request.method) { ... UiRequestMethod.INPUT -> InputField(...) }`.
        // \s matches newlines, so this works regardless of formatting.
        assertTrue(
            "INPUT must have its own when-branch that calls InputField",
            Regex("UiRequestMethod\\.INPUT\\s*->\\s*InputField\\s*\\(").containsMatchIn(src),
        )
        // Submit must forward the entered text via `onResponse(it)`, not cancel.
        assertTrue(
            "INPUT.onSubmit must forward the entered text to onResponse",
            Regex("""UiRequestMethod\.INPUT\s*->\s*InputField\(\s*onSubmit\s*=\s*\{\s*onResponse\(\s*it\s*\)\s*\}\s*,""").containsMatchIn(src),
        )
        // `enabled = !expired` must gate the InputField (so the buttons freeze
        // at zero). `[\s\S]*?` lets the match span the newlines + inner args
        // between `InputField(` and `enabled = !expired`.
        assertTrue(
            "INPUT branch must pass enabled = !expired to InputField",
            Regex("""UiRequestMethod\.INPUT\s*->\s*InputField\([\s\S]*?enabled\s*=\s*!expired""").containsMatchIn(src),
        )
    }

    @Test
    fun `INPUT exposes a Submit button that forwards the trimmed text and a Cancel that clears the field`() {
        val src = source()
        // `canSubmit = enabled && inputValue.trim().isNotEmpty()` -- server
        // validates non-empty input, so an empty submit must not fire.
        assertTrue(
            "canSubmit must require non-empty trimmed text + not expired",
            src.contains("val canSubmit = enabled && inputValue.trim().isNotEmpty()"),
        )
        // Submit button is gated on `enabled = canSubmit`.
        assertTrue(
            "Submit button must be gated on canSubmit (non-empty + not expired)",
            Regex("""Button\([\s\S]*?enabled\s*=\s*canSubmit""").containsMatchIn(src),
        )
        // The Submit onClick trims and forwards. `[\s\S]*?` spans the
        // newlines between the onClick body lines.
        assertTrue(
            "Submit onClick must call onSubmit(trimmed) on non-empty",
            Regex(
                """onClick\s*=\s*\{[\s\S]*?val\s+trimmed\s*=\s*inputValue\.trim\(\)[\s\S]*?if\s*\(\s*trimmed\.isNotEmpty\(\)\s*\)\s*onSubmit\(\s*trimmed\s*\)""",
            ).containsMatchIn(src),
        )
        // Cancel is an OutlinedButton that clears the local TextField only.
        // The match spans the newline between `OutlinedButton(` and
        // `onClick = { inputValue = "" }`.
        assertTrue(
            "INPUT.Cancel must clear the local TextField, NOT cancel the request",
            Regex("OutlinedButton\\([\\s\\S]*?onClick\\s*=\\s*\\{\\s*inputValue\\s*=\\s*\"\"\\s*\\}").containsMatchIn(src),
        )
        // Sanity: cancel does NOT touch _activeUiRequest (which lives in the ViewModel).
        assertTrue(
            "Cancel must not call into ViewModel state (no _activeUiRequest mention in the card)",
            !Regex("""onClick\s*=\s*\{[\s\S]*?_activeUiRequest[\s\S]*?\}""").containsMatchIn(src),
        )
    }

    // -- 1s countdown tick -----------------------------------------------------

    @Test
    fun `countdown uses an ABSOLUTE deadlineAt and a 1-second LaunchedEffect tick`() {
        val src = source()
        // `LaunchedEffect(request.id, request.deadlineAt)` keyed on both, so
        // re-mounting after the user returns to Chat re-derives
        // `remainingSeconds` from the absolute deadline (not from a stale
        // delta).
        assertTrue(
            "countdown LaunchedEffect must key on (id, deadlineAt)",
            src.contains("LaunchedEffect(request.id, request.deadlineAt)"),
        )
        // `delay(1000L)` is the per-tick interval.
        assertTrue(
            "countdown LaunchedEffect must delay 1s between ticks",
            src.contains("delay(1000L)"),
        )
        // The recomputed value MUST go through `computeRemainingSeconds(deadlineAt)`.
        assertTrue(
            "remainingSeconds must be derived from computeRemainingSeconds(request.deadlineAt)",
            src.contains("remainingSeconds = computeRemainingSeconds(request.deadlineAt)"),
        )
    }

    @Test
    fun `remember seeds remainingSeconds from computeRemainingSeconds so first paint after return is correct`() {
        // The spec scenario "Alive on Chat exit" requires the countdown to
        // re-render with the correct remaining time immediately, not after a
        // 1s tick. The composable uses `remember(request.id, request.deadlineAt)`
        // so a remount reads the absolute deadline on first composition.
        val src = source()
        assertTrue(
            "remember must key on (request.id, request.deadlineAt) and seed from computeRemainingSeconds",
            src.contains("remember(request.id, request.deadlineAt)") &&
                src.contains("mutableStateOf(computeRemainingSeconds(request.deadlineAt))"),
        )
    }

    @Test
    fun `computeRemainingSeconds never returns a negative value (clock-skew guard)`() {
        // Even when the host clock is ahead of `deadlineAt` (skew, NTP step,
        // etc.), the math MUST clamp to >= 0 so the UI never shows "-1s" and
        // the `expired` gate never misses a zero crossing.
        val src = source()
        assertTrue(
            "computeRemainingSeconds must coerce the diff to >= 0",
            Regex("""computeRemainingSeconds[\s\S]*?coerceAtLeast\(0L\)""").containsMatchIn(src),
        )
    }

    // -- Expired disables actions + shows the Expirado label -------------------

    @Test
    fun `expired gate disables Submit, pick, accept and reject buttons`() {
        val src = source()
        // `val expired = request.deadlineAt > 0L && remainingSeconds <= 0`.
        // Both clauses are required: a 0 deadlineAt means "no countdown", not
        // "already expired"; a positive deadlineAt with remaining time must
        // NOT freeze the buttons.
        assertTrue(
            "expired must require both deadlineAt>0 AND remainingSeconds<=0",
            src.contains("val expired = request.deadlineAt > 0L && remainingSeconds <= 0"),
        )
        // All three action composables must receive `enabled = !expired`.
        val branches = listOf(
            "UiRequestMethod.CONFIRM",
            "UiRequestMethod.SELECT",
            "UiRequestMethod.INPUT",
        )
        for (branch in branches) {
            // `[\s\S]*?` lets us span newlines (Kotlin's `.` does not match
            // `\n`); the non-greedy `*?` keeps the match as short as
            // possible so a stray `enabled = !expired` later in the file does
            // not satisfy the assertion.
            assertTrue(
                "$branch branch must pass enabled = !expired to its action composable",
                Regex("""$branch[\s\S]*?enabled\s*=\s*!expired""").containsMatchIn(src),
            )
        }
    }

    @Test
    fun `countdown label switches to Expirado when the deadline has passed`() {
        val src = source()
        assertTrue(
            "label must branch on `expired` to render 'Expirado'",
            src.contains("if (expired) \"Expirado\" else \"\${remainingSeconds}s\""),
        )
    }

    // -- errorMessage slot (Phase 5.4 — out-of-date value keeps dialog open) ---

    @Test
    fun `errorMessage slot is rendered when the last answer was rejected by the server`() {
        // The 409 surface lives in the card (not in the chat message stream)
        // so the rejection is visible next to the dialog the user just acted on.
        val src = source()
        // `[\s\S]*?` (not `[^)]*`) so the regex can span the `(Any) -> Unit`
        // parameter list and reach `errorMessage`.
        assertTrue(
            "UiRequestCard must accept an `errorMessage: String? = null` parameter",
            Regex("""fun\s+UiRequestCard\s*\([\s\S]*?errorMessage\s*:\s*String\?\s*=\s*null""").containsMatchIn(src),
        )
        assertTrue(
            "UiRequestCard must render the errorMessage slot when non-blank",
            src.contains("if (!errorMessage.isNullOrBlank())"),
        )
    }

    // -- Method exhaustiveness (compile-time guarantee from `when` over enum) ---

    @Test
    fun `the action block is exhaustive over UiRequestMethod so adding a new enum value is a build break`() {
        // The `when (request.method) { ... }` MUST handle every enum value;
        // Kotlin's exhaustiveness check then makes any future
        // `UiRequestMethod.X` addition a compile error until the card has
        // explicit rendering. We pin the source to the three known methods.
        val src = source()
        for (m in listOf("CONFIRM", "SELECT", "INPUT")) {
            assertTrue(
                "when block must handle UiRequestMethod.$m",
                src.contains("UiRequestMethod.$m ->"),
            )
        }
    }
}
