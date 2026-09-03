package com.idupi.app.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural coverage for the session-resume navigation contract.
 *
 * Pre-test audit: entering a session passed ONLY the session id, discarding
 * the session's engine -- so when the server switched `activeEngine` on
 * resume, the shared selector stayed on the previously-selected engine and
 * the model list stayed stale. The fix threads `session.engine` through
 * `onSessionSelect` so the caller can sync the selector to the engine the
 * resumed session actually runs on.
 *
 * The host build ships without `androidx.compose.ui:ui-test-junit4`, so we
 * pin the contract structurally (same convention as `UiRequestCardTest`):
 * regex assertions over a verbatim snapshot of `SessionsScreen.kt` at
 * `src/test/resources/SessionsScreen.kt.txt`. A regression that drops the
 * engine from the callback signature OR from the `SessionCard` invocation
 * will fail these tests even though the composable is never rendered.
 */
class SessionsScreenNavigationTest {

    private fun source(): String =
        javaClass.classLoader!!.getResourceAsStream("SessionsScreen.kt.txt")
            ?.bufferedReader()?.use { it.readText() }
            ?: error(
                "SessionsScreen.kt.txt must be on the test classpath; " +
                    "see app/src/test/resources/SessionsScreen.kt.txt (snapshot of " +
                    "app/src/main/java/com/idupi/app/ui/screens/SessionsScreen.kt).",
            )

    @Test
    fun `onSessionSelect accepts the session engine alongside the session id`() {
        val src = source()
        // The callback type must carry both: (sessionId, engine).
        assertTrue(
            "onSessionSelect must accept (sessionId: String, engine: String?)",
            Regex(
                """onSessionSelect:\s*\(sessionId:\s*String,\s*engine:\s*String\?\)\s*->\s*Unit""",
            ).containsMatchIn(src),
        )
    }

    @Test
    fun `SessionCard forwards both the session id and its engine`() {
        val src = source()
        // The card's tap must pass the session's engine (not discard it).
        assertTrue(
            "SessionCard must call onSessionSelect(session.id, session.engine)",
            Regex(
                """onSessionSelect\s*=\s*\{\s*onSessionSelect\(\s*session\.id\s*,\s*session\.engine\s*\)\s*\}""",
            ).containsMatchIn(src),
        )
    }
}