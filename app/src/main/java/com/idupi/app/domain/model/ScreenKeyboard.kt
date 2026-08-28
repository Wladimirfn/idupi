package com.idupi.app.domain.model

/**
 * Realtime keyboard (hito 7). The soft keyboard only reports text deltas, so
 * [keyboardDiffs] turns an old->new value pair into the ordered key presses
 * it represents; hardware keys map straight onto Windows virtual keys.
 * Everything here is decidable without a screen and unit-tested in
 * ScreenKeyboardTest.
 */

/** Windows virtual-key codes the remote keyboard needs (Win32 VK_* values). */
enum class SpecialKey(val vk: Int) {
    BACKSPACE(0x08),
    TAB(0x09),
    ENTER(0x0D),
    ESCAPE(0x1B),
    ARROW_LEFT(0x25),
    ARROW_UP(0x26),
    ARROW_RIGHT(0x27),
    ARROW_DOWN(0x28),
    DELETE(0x2E),
}

/**
 * One key press on the wire. Characters ride "keychar" as their UTF-16 code
 * unit; special keys ride "keyvk" with their Windows virtual-key code. The
 * Go helper performs the full down+up for each, so one wire event is one
 * complete keystroke -- half the round-trips of separate down/up events.
 */
data class KeyPress(val kind: Kind, val code: Int) {
    enum class Kind { CHAR, SPECIAL }

    val wireAction: String get() = if (kind == Kind.CHAR) "keychar" else "keyvk"

    fun asSpecial(): SpecialKey? =
        SpecialKey.entries.firstOrNull { it.vk == code }?.takeIf { kind == Kind.SPECIAL }

    companion object {
        fun char(c: Char) = KeyPress(Kind.CHAR, c.code)
        fun special(key: SpecialKey) = KeyPress(Kind.SPECIAL, key.vk)
    }
}

/** Control characters must ride virtual keys: Windows apps expect VK_ENTER, not unicode 10. */
private fun keyPressForControlChar(c: Char): KeyPress = when (c) {
    '\n', '\r' -> KeyPress.special(SpecialKey.ENTER)
    '\t' -> KeyPress.special(SpecialKey.TAB)
    else -> KeyPress.char(c)
}

/**
 * Diffs the soft keyboard's old->new text into ordered presses:
 * a common prefix and suffix are trimmed away, what remains deletes as
 * backspaces first and then types the inserted characters -- exactly what
 * autocorrect-style replacements need.
 */
fun keyboardDiffs(oldValue: String, newValue: String): List<KeyPress> {
    var start = 0
    val minEnd = minOf(oldValue.length, newValue.length)
    while (start < minEnd && oldValue[start] == newValue[start]) start++
    var oldEnd = oldValue.length
    var newEnd = newValue.length
    while (oldEnd > start && newEnd > start && oldValue[oldEnd - 1] == newValue[newEnd - 1]) {
        oldEnd--
        newEnd--
    }
    val removed = oldEnd - start
    // The inserted region is the middle span [start, newEnd) of the NEW
    // value -- not its tail -- because the suffix already matched beyond it.
    val inserted = if (start < newEnd) newValue.substring(start, newEnd) else ""
    val presses = ArrayList<KeyPress>(removed + inserted.length)
    repeat(removed) { presses.add(KeyPress.special(SpecialKey.BACKSPACE)) }
    inserted.forEach { presses.add(keyPressForControlChar(it)) }
    return presses
}
