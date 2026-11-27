package com.idupi.app.data.phone.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keyboard and navigation decisions for the expanded bubble (T16).
 *
 * The owner's complaint: while typing there was no way to hide the keyboard and
 * BACK/HOME never reached the system. The reducer owns the event → action table
 * so the service only applies actions; the window flags themselves are pinned
 * in [BubbleOverlayModelTest].
 */
class BubbleFocusReducerTest {

    private val typing = BubbleFocusState(
        expanded = true,
        imeVisible = true,
        windowFocusable = true,
    )

    @Test
    fun `back with the keyboard up hides it and keeps the panel open`() {
        val decision = BubbleFocusReducer.reduce(typing, BubbleFocusEvent.BACK_PRESSED)

        assertTrue(decision.handled)
        assertEquals(typing.copy(imeVisible = false), decision.state)
        assertEquals(
            listOf(BubbleFocusAction.HideKeyboard, BubbleFocusAction.ClearFieldFocus),
            decision.actions,
        )
    }

    @Test
    fun `a second back collapses the panel after the keyboard is gone`() {
        val keyboardHidden = BubbleFocusReducer
            .reduce(typing, BubbleFocusEvent.BACK_PRESSED)
            .state

        val decision = BubbleFocusReducer.reduce(keyboardHidden, BubbleFocusEvent.BACK_PRESSED)

        assertTrue(decision.handled)
        assertFalse(decision.state.expanded)
        assertTrue("collapsing returns focus to the system", decision.state.windowFocusable)
        assertTrue(BubbleFocusAction.CollapsePanel in decision.actions)
    }

    @Test
    fun `back is left alone while the panel is collapsed`() {
        val decision = BubbleFocusReducer.reduce(
            BubbleFocusState(expanded = false),
            BubbleFocusEvent.BACK_PRESSED,
        )

        assertFalse(decision.handled)
        assertTrue(decision.actions.isEmpty())
    }

    @Test
    fun `an outside touch hides the keyboard and releases the window without collapsing`() {
        val decision = BubbleFocusReducer.reduce(typing, BubbleFocusEvent.OUTSIDE_TOUCH)

        assertTrue(decision.handled)
        assertEquals(
            BubbleFocusState(expanded = true, imeVisible = false, windowFocusable = false),
            decision.state,
        )
        assertEquals(
            listOf(
                BubbleFocusAction.HideKeyboard,
                BubbleFocusAction.ClearFieldFocus,
                BubbleFocusAction.ReleaseWindowFocus,
            ),
            decision.actions,
        )
    }

    @Test
    fun `tapping the field takes focus back and shows the keyboard`() {
        val released = BubbleFocusState(
            expanded = true,
            imeVisible = false,
            windowFocusable = false,
        )

        val decision = BubbleFocusReducer.reduce(released, BubbleFocusEvent.FIELD_TAPPED)

        assertTrue(decision.handled)
        assertEquals(typing, decision.state)
        assertEquals(
            listOf(
                BubbleFocusAction.AcquireWindowFocus,
                BubbleFocusAction.RequestFieldFocus,
                BubbleFocusAction.ShowKeyboard,
            ),
            decision.actions,
        )
    }

    @Test
    fun `the hide keyboard control releases focus like an outside touch`() {
        val decision = BubbleFocusReducer.reduce(typing, BubbleFocusEvent.HIDE_KEYBOARD)

        assertTrue(decision.handled)
        assertFalse(decision.state.imeVisible)
        assertFalse(decision.state.windowFocusable)
        assertTrue(BubbleFocusAction.HideKeyboard in decision.actions)
        assertTrue(BubbleFocusAction.ReleaseWindowFocus in decision.actions)
    }

    @Test
    fun `outside touches and field taps are ignored while collapsed`() {
        val collapsed = BubbleFocusState(expanded = false)

        assertFalse(
            BubbleFocusReducer.reduce(collapsed, BubbleFocusEvent.OUTSIDE_TOUCH).handled,
        )
        assertFalse(
            BubbleFocusReducer.reduce(collapsed, BubbleFocusEvent.FIELD_TAPPED).handled,
        )
    }

    @Test
    fun `the hide keyboard affordance uses neutral Spanish copy`() {
        assertEquals("Ocultar teclado", BubbleFocusReducer.HIDE_KEYBOARD_DESCRIPTION)
    }
}
