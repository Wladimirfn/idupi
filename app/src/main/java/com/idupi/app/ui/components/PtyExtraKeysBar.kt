package com.idupi.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBg
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.TextPrimary

/**
 * Keys a soft keyboard cannot produce, plus one-shot Ctrl/Alt chords.
 *
 * Ctrl and Alt arm the JS side (`window.idupi.setModifiers`) for exactly one
 * xterm keystroke; xterm clears them and calls `onModifiersConsumed` so the
 * highlight follows what the terminal will actually apply. Every other key
 * sends its VT sequence straight through the same input path the keyboard uses
 * (`onKey`), and disarms the modifiers first: a sticky Ctrl armed for a chord
 * that was never typed must not leak into the next key.
 *
 * Layout: a single horizontally scrollable row, sitting above the soft keyboard
 * (the screen applies `imePadding()`), so landscape phones can still reach
 * every key.
 *
 * "Ancho" toggles wide mode: the page pins at least [WIDE_MODE_COLS] columns
 * and pans horizontally instead of reflowing, which is the width Claude Code,
 * OpenCode and other agent TUIs are laid out for. A phone in portrait only
 * fits ~46 columns.
 */
@Composable
fun PtyExtraKeysBar(
    ctrlArmed: Boolean,
    altArmed: Boolean,
    wideMode: Boolean,
    fontSize: Int,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onWideModeToggle: () -> Unit,
    onKey: (String) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SlateCard,
        border = BorderStroke(0.5.dp, SlateBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyChip(label = "Esc") { onKey(VT_ESC) }
            KeyChip(label = "Tab") { onKey(VT_TAB) }
            // Back-tab: Claude Code cycles its modes with it, TUIs move focus back.
            KeyChip(label = "⇧Tab") { onKey(VT_BACKTAB) }
            // Direct interrupt, so stopping a run does not need a Ctrl chord.
            KeyChip(label = "^C") { onKey(VT_INTERRUPT) }
            KeyChip(label = "Ctrl", armed = ctrlArmed, onClick = onCtrlToggle)
            KeyChip(label = "Alt", armed = altArmed, onClick = onAltToggle)
            KeyChip(label = "←") { onKey(VT_LEFT) }
            KeyChip(label = "↑") { onKey(VT_UP) }
            KeyChip(label = "↓") { onKey(VT_DOWN) }
            KeyChip(label = "→") { onKey(VT_RIGHT) }
            KeyChip(label = "PgUp") { onKey(VT_PGUP) }
            KeyChip(label = "PgDn") { onKey(VT_PGDN) }
            KeyChip(label = "Home") { onKey(VT_HOME) }
            KeyChip(label = "End") { onKey(VT_END) }
            KeyChip(label = "|") { onKey("|") }
            KeyChip(label = "~") { onKey("~") }
            KeyChip(label = "/") { onKey("/") }
            KeyChip(label = "-") { onKey("-") }
            KeyChip(label = "_") { onKey("_") }
            KeyChip(label = ":") { onKey(":") }
            KeyChip(label = ".") { onKey(".") }

            // Wide mode: 80 columns with horizontal panning, so agent TUIs
            // keep their expected layout instead of reflowing at ~46 columns.
            KeyChip(label = "Ancho", armed = wideMode, onClick = onWideModeToggle)

            // Font size: readability on a phone beats fitting 200 columns.
            KeyChip(label = "A-") {
                onFontSizeChange((fontSize - 1).coerceAtLeast(MIN_FONT_SIZE))
            }
            KeyChip(label = "A+") {
                onFontSizeChange((fontSize + 1).coerceAtMost(MAX_FONT_SIZE))
            }
        }
    }
}

@Composable
private fun KeyChip(
    label: String,
    armed: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = AppShapes.small,
        color = if (armed) PrimaryIndigo else SlateBg,
        border = BorderStroke(1.dp, if (armed) PrimaryIndigo else SlateBorder),
        modifier = Modifier.height(34.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = AppTypography.codeMono.copy(
                    fontSize = 12.sp,
                    fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal
                ),
                color = if (armed) Color.White else TextPrimary
            )
        }
    }
}

// VT100/xterm sequences. Fixed strings, not locale- or keyboard-dependent.
const val VT_ESC: String = "\u001b"
const val VT_TAB: String = "\t"
const val VT_BACKTAB: String = "\u001b[Z"
const val VT_INTERRUPT: String = "\u0003"
const val VT_UP: String = "\u001b[A"
const val VT_DOWN: String = "\u001b[B"
const val VT_RIGHT: String = "\u001b[C"
const val VT_LEFT: String = "\u001b[D"
const val VT_HOME: String = "\u001b[H"
const val VT_END: String = "\u001b[F"
const val VT_PGUP: String = "\u001b[5~"
const val VT_PGDN: String = "\u001b[6~"

// Wide mode pins the grid at the width agent TUIs are designed for.
const val WIDE_MODE_COLS: Int = 80

const val MIN_FONT_SIZE: Int = 9
const val MAX_FONT_SIZE: Int = 28
