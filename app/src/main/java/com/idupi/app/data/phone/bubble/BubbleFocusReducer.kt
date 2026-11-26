package com.idupi.app.data.phone.bubble

/** One thing that can change the panel's keyboard/focus state. */
enum class BubbleFocusEvent { BACK_PRESSED, OUTSIDE_TOUCH, FIELD_TAPPED, HIDE_KEYBOARD }

/**
 * Keyboard and window-focus state of the expanded panel (T16).
 *
 * @property expanded the panel is open (the collapsed pill never takes keys).
 * @property imeVisible the owner's keyboard is showing, as tracked from the
 *   actions the bubble itself issues: a tap on the field shows it, BACK, the
 *   hide-keyboard control or an outside touch hide it.
 * @property windowFocusable whether the window drops `FLAG_NOT_FOCUSABLE`.
 *   While true it receives BACK; while false the app behind and the system
 *   navigation own the keys again.
 */
data class BubbleFocusState(
    val expanded: Boolean = false,
    val imeVisible: Boolean = false,
    val windowFocusable: Boolean = true,
)

/** One side effect the overlay must apply on the main thread. */
sealed interface BubbleFocusAction {
    data object HideKeyboard : BubbleFocusAction
    data object ClearFieldFocus : BubbleFocusAction
    data object ReleaseWindowFocus : BubbleFocusAction
    data object AcquireWindowFocus : BubbleFocusAction
    data object RequestFieldFocus : BubbleFocusAction
    data object ShowKeyboard : BubbleFocusAction
    data object CollapsePanel : BubbleFocusAction
}

/**
 * @property state the next state, unchanged when [handled] is false.
 * @property actions side effects in the order the overlay must apply them.
 * @property handled whether the event belongs to the bubble; BACK must fall
 *   through to the system when false.
 */
data class BubbleFocusDecision(
    val state: BubbleFocusState,
    val actions: List<BubbleFocusAction>,
    val handled: Boolean,
)

/**
 * Pure decision table for the keyboard and BACK behaviour (T16).
 *
 * The rules, in the owner's words: while typing, BACK first hides the keyboard
 * and releases the field; a second BACK collapses the bubble. A touch outside
 * the panel hides the keyboard and makes the window non-focusable so the app
 * behind and system navigation work again; tapping the field takes focus back
 * and shows the keyboard. The hide-keyboard control behaves like an outside
 * touch because it is the explicit version of the same intent.
 */
object BubbleFocusReducer {

    /** Accessible name of the input-row control; neutral Spanish, no voseo. */
    const val HIDE_KEYBOARD_DESCRIPTION = "Ocultar teclado"

    fun reduce(state: BubbleFocusState, event: BubbleFocusEvent): BubbleFocusDecision {
        if (!state.expanded) {
            return BubbleFocusDecision(state, emptyList(), handled = false)
        }
        return when (event) {
            BubbleFocusEvent.BACK_PRESSED -> when {
                state.imeVisible -> BubbleFocusDecision(
                    state = state.copy(imeVisible = false),
                    actions = listOf(
                        BubbleFocusAction.HideKeyboard,
                        BubbleFocusAction.ClearFieldFocus,
                    ),
                    handled = true,
                )
                else -> BubbleFocusDecision(
                    state = BubbleFocusState(expanded = false),
                    actions = listOf(
                        BubbleFocusAction.HideKeyboard,
                        BubbleFocusAction.ClearFieldFocus,
                        BubbleFocusAction.CollapsePanel,
                    ),
                    handled = true,
                )
            }
            BubbleFocusEvent.OUTSIDE_TOUCH -> release(state)
            BubbleFocusEvent.HIDE_KEYBOARD -> release(state)
            BubbleFocusEvent.FIELD_TAPPED -> BubbleFocusDecision(
                state = state.copy(imeVisible = true, windowFocusable = true),
                actions = listOf(
                    BubbleFocusAction.AcquireWindowFocus,
                    BubbleFocusAction.RequestFieldFocus,
                    BubbleFocusAction.ShowKeyboard,
                ),
                handled = true,
            )
        }
    }

    /** Hides the keyboard and gives the keys back to the system. */
    private fun release(state: BubbleFocusState): BubbleFocusDecision = BubbleFocusDecision(
        state = state.copy(imeVisible = false, windowFocusable = false),
        actions = listOf(
            BubbleFocusAction.HideKeyboard,
            BubbleFocusAction.ClearFieldFocus,
            BubbleFocusAction.ReleaseWindowFocus,
        ),
        handled = true,
    )
}
