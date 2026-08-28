package com.idupi.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Realtime typing (hito 7): every key travels the moment it is pressed,
 * never on IME commit. The soft keyboard only reports text deltas, so the
 * domain turns old->new value pairs into ordered key presses; hardware keys
 * map straight to Windows virtual keys. All decidable without a screen.
 */
class ScreenKeyboardTest {

    // --- typed characters ---

    @Test
    fun `a plain character becomes a unicode press of its UTF-16 code`() {
        assertEquals(
            listOf(KeyPress.char('a')),
            keyboardDiffs("", "a"),
        )
        assertEquals(
            listOf(KeyPress.char('W')),
            keyboardDiffs("", "W"),
        )
    }

    @Test
    fun `appended characters arrive in order`() {
        assertEquals(
            listOf(KeyPress.char('h'), KeyPress.char('o'), KeyPress.char('l'), KeyPress.char('a')),
            keyboardDiffs("", "hola"),
        )
    }

    @Test
    fun `an insertion into existing text emits only the inserted chars`() {
        // "hl" -> "hol": 'o' inserted before the trailing common suffix "l".
        assertEquals(
            listOf(KeyPress.char('o')),
            keyboardDiffs("hl", "hol"),
        )
    }

    // --- deletions ---

    @Test
    fun `removing one char becomes one backspace`() {
        assertEquals(
            listOf(KeyPress.special(SpecialKey.BACKSPACE)),
            keyboardDiffs("hol", "ho"),
        )
    }

    @Test
    fun `deleting several chars becomes that many backspaces`() {
        val presses = keyboardDiffs("hola", "")
        assertEquals(4, presses.size)
        presses.forEach { assertEquals(SpecialKey.BACKSPACE, it.asSpecial()) }
    }

    // --- replacement = backspaces then chars ---

    @Test
    fun `replacing a selection deletes first then types`() {
        // "cat" -> "cut" (autocorrect-style single-char swap)
        assertEquals(
            listOf(
                KeyPress.special(SpecialKey.BACKSPACE),
                KeyPress.char('u'),
            ),
            keyboardDiffs("cat", "cut"),
        )
    }

    @Test
    fun `identical values produce nothing`() {
        assertEquals(emptyList<KeyPress>(), keyboardDiffs("same", "same"))
    }

    // --- control characters ride virtual keys, not unicode ---

    @Test
    fun `newline becomes ENTER and tab becomes TAB`() {
        assertEquals(
            listOf(KeyPress.special(SpecialKey.ENTER)),
            keyboardDiffs("", "\n"),
        )
        assertEquals(
            listOf(KeyPress.special(SpecialKey.TAB)),
            keyboardDiffs("", "\t"),
        )
    }

    // --- special keys carry the right Windows VK codes ---

    @Test
    fun `special keys expose their documented VK codes`() {
        assertEquals(0x08, SpecialKey.BACKSPACE.vk)
        assertEquals(0x0D, SpecialKey.ENTER.vk)
        assertEquals(0x09, SpecialKey.TAB.vk)
        assertEquals(0x1B, SpecialKey.ESCAPE.vk)
        assertEquals(0x25, SpecialKey.ARROW_LEFT.vk)
        assertEquals(0x26, SpecialKey.ARROW_UP.vk)
        assertEquals(0x27, SpecialKey.ARROW_RIGHT.vk)
        assertEquals(0x28, SpecialKey.ARROW_DOWN.vk)
        assertEquals(0x2E, SpecialKey.DELETE.vk)
    }

    // --- wire actions ---

    @Test
    fun `char presses travel as keychar with their code`() {
        val p = KeyPress.char('ñ')
        assertEquals("keychar", p.wireAction)
        assertEquals('ñ'.code, p.code)
    }

    @Test
    fun `vk presses travel as keyvk with the vk code`() {
        val p = KeyPress.special(SpecialKey.ENTER)
        assertEquals("keyvk", p.wireAction)
        assertEquals(0x0D, p.code)
    }
}
