package com.idupi.app.ui.screens.settings

import com.idupi.app.domain.model.ThemeMode

/**
 * Expansion of the Personalizado colour editor (settings redesign T3).
 *
 * Pure decisions, so the owner's complaint ("the editor cannot be hidden
 * without changing the theme") is JVM-tested without Compose. The screen owns
 * the state with `rememberSaveable`: it survives rotation but is forgotten
 * when Apariencia closes, which is why every visit starts collapsed.
 */

/**
 * Apariencia opens with the editor collapsed, even when the stored mode is
 * already Personalizado; the screen must fit without the colour block.
 */
internal const val COLORS_EXPANDED_ON_ENTRY: Boolean = false

/**
 * Expansion after the mode selector moves from [previousMode] to [newMode].
 *
 * Choosing Personalizado from another mode opens the editor immediately, so the
 * owner sees the colours they just asked for. Staying in Personalizado keeps
 * the owner's own toggle. Leaving Personalizado collapses the editor, so a
 * later return starts from a predictable expanded state.
 */
internal fun nextColorsExpanded(
    previousMode: ThemeMode,
    newMode: ThemeMode,
    expanded: Boolean,
): Boolean = when {
    newMode != ThemeMode.CUSTOM -> false
    previousMode != ThemeMode.CUSTOM -> true
    else -> expanded
}
