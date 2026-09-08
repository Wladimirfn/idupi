package com.idupi.app.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 3 / Task 4.3 — structural coverage for the new `AutoApproveSection`
 * inside `SettingsScreen.kt`.
 *
 * The host build ships without `androidx.compose.ui:ui-test-junit4`, so we
 * pin the composable contract structurally (same convention as
 * `SessionsScreenNavigationTest` and `UiRequestCardTest`): regex
 * assertions over a verbatim snapshot of `SettingsScreen.kt` at
 * `src/test/resources/SettingsScreen.kt.txt`.
 *
 * A regression that drops the section, drops the Switch wiring, or stops
 * binding to `MainViewModel.setOpencodeAutoApprove` fails these tests
 * even though the composable is never rendered. The structural pin is the
 * only thing keeping a future "delete the section because it stopped
 * showing up in the UI" regression from going unnoticed.
 *
 * Behaviour under test (mirrors `opencode-permission-sidecar/spec.md`
 * "Auto-Approve Toggle" / "Toggle OFF" scenario):
 *
 *   * the toggle defaults to OFF (the `opencodeAutoApprove` StateFlow is
 *     seeded `false` in MainViewModel; the Switch's checked state MUST
 *     bind to that flow directly, not to a hard-coded `true`);
 *   * flipping the Switch MUST call `MainViewModel.setOpencodeAutoApprove`;
 *   * the section MUST live inside the SettingsScreen Scaffold (visible
 *     to the user, not a dead function).
 */
class SettingsScreenAutoApproveSectionTest {

    private fun source(): String =
        javaClass.classLoader!!.getResourceAsStream("SettingsScreen.kt.txt")
            ?.bufferedReader()?.use { it.readText() }
            ?: error(
                "SettingsScreen.kt.txt must be on the test classpath; " +
                    "see app/src/test/resources/SettingsScreen.kt.txt (snapshot of " +
                    "app/src/main/java/com/idupi/app/ui/screens/SettingsScreen.kt).",
            )

    @Test
    fun `AutoApproveSection private composable exists and follows the GeneralSection signature`() {
        val src = source()
        // Mirrors the GeneralSection parameter shape at L148-150:
        //   private fun GeneralSection(darkThemeEnabled: Boolean, onDarkThemeToggle: (Boolean) -> Unit)
        // so the Switch + persistence wiring looks the same and the existing
        // helper (SwitchDefaults.colors) can be reused without further changes.
        // The trailing `,` after `Unit` is allowed (Kotlin permits trailing
        // commas in multi-line signatures).
        assertTrue(
            "AutoApproveSection MUST be a private composable with the " +
                "GeneralSection-shaped signature (autoApprove: Boolean, " +
                "onAutoApproveToggle: (Boolean) -> Unit) so it slots into " +
                "the same Switch + Card layout",
            Regex(
                """private\s+fun\s+AutoApproveSection\s*\(\s*autoApprove:\s*Boolean\s*,\s*onAutoApproveToggle:\s*\(Boolean\)\s*->\s*Unit\s*,?\s*\)""",
            ).containsMatchIn(src),
        )
    }

    @Test
    fun `AutoApproveSection Switch binds checked to autoApprove and onCheckedChange to the toggle callback`() {
        val src = source()
        // Switch(checked = darkThemeEnabled, onCheckedChange = onDarkThemeToggle, ...)
        // is the GeneralSection shape (L169-173). The new section MUST match.
        assertTrue(
            "AutoApproveSection MUST wire Switch(checked = autoApprove, " +
                "onCheckedChange = onAutoApproveToggle) — pinning the wire " +
                "shape prevents a regression where the Switch stays " +
                "decoratively visible but no longer drives the toggle",
            Regex(
                """Switch\s*\(\s*checked\s*=\s*autoApprove\s*,\s*onCheckedChange\s*=\s*onAutoApproveToggle\b""",
            ).containsMatchIn(src),
        )
    }

    @Test
    fun `SettingsScreen composable destructures the StateFlow and renders AutoApproveSection`() {
        val src = source()
        // Compose idiom: a StateFlow<Boolean> from the ViewModel is consumed
        // via `by collectAsState()` so the call site can pass a plain
        // Boolean. Without the destructure the section would receive a
        // StateFlow (compile error: `autoApprove: Boolean` does not match).
        // Pinning both halves of the wire keeps the contract visible.
        assertTrue(
            "SettingsScreen MUST destructure the toggle StateFlow via " +
                "`by mainViewModel.opencodeAutoApprove.collectAsState()` " +
                "before passing it to AutoApproveSection; otherwise the " +
                "Boolean parameter type does not match and the section " +
                "silently never reflects the persisted value",
            Regex(
                """val\s+opencodeAutoApprove\s+by\s+mainViewModel\.opencodeAutoApprove\.collectAsState\s*\(\s*\)""",
            ).containsMatchIn(src),
        )
        assertTrue(
            "SettingsScreen MUST render AutoApproveSection with the " +
                "destructured toggle + a callback that calls " +
                "mainViewModel.setOpencodeAutoApprove(it); otherwise the " +
                "user's toggle never reaches the chat header or the repo",
            Regex(
                """AutoApproveSection\s*\(\s*autoApprove\s*=\s*opencodeAutoApprove\s*,\s*onAutoApproveToggle\s*=\s*\{\s*mainViewModel\.setOpencodeAutoApprove\(it\)\s*\}\s*\)""",
            ).containsMatchIn(src),
        )
    }

    @Test
    fun `AutoApproveSection title uses the Spanish Aprobacion automatica label`() {
        // Spec scope is OpenCode-only, but the SettingsScreen copy is in
        // Spanish (every other section uses Spanish labels: "GENERAL",
        // "NOTIFICACIONES", "ACCIONES Y SEGURIDAD"). Pinning the label
        // here is a small UX regression guard.
        val src = source()
        assertTrue(
            "AutoApproveSection MUST use the Spanish label matching the " +
                "rest of the SettingsScreen (other sections: GENERAL, " +
                "NOTIFICACIONES, ACCIONES Y SEGURIDAD); a stray English " +
                "label here would be the only one in the screen",
            Regex(
                """Text\s*\(\s*"Aprobaci\u00f3n autom\u00e1tica"""",
            ).containsMatchIn(src),
        )
    }
}
