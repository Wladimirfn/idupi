package com.example.idupi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Invisible characters are written as escapes throughout: a literal zero-width
 * space in a test is unreviewable, and a test nobody can read is a test nobody
 * can trust.
 */
class AuthTokenTest {

    private val sampleToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `leaves a clean token untouched`() {
        assertEquals(sampleToken, sanitizeAuthToken(sampleToken))
    }

    @Test
    fun `strips surrounding whitespace and newlines`() {
        assertEquals(sampleToken, sanitizeAuthToken("  \n\t$sampleToken\r\n "))
    }

    @Test
    fun `strips a zero width space that trim would leave behind`() {
        val poisoned = sampleToken + '\u200B'

        // Guard the premise: this is precisely why trim() was not enough.
        assertEquals(65, poisoned.length)
        assertEquals(65, poisoned.trim().length)

        assertEquals(sampleToken, sanitizeAuthToken(poisoned))
    }

    @Test
    fun `strips a byte order mark embedded in the middle`() {
        val poisoned = sampleToken.substring(0, 20) + '\uFEFF' + sampleToken.substring(20)

        assertEquals(65, poisoned.length)
        assertEquals(sampleToken, sanitizeAuthToken(poisoned))
    }

    @Test
    fun `strips control characters`() {
        val poisoned = sampleToken + '\u0007'

        // A control character is not whitespace either, so trim() keeps it.
        assertEquals(65, poisoned.trim().length)
        assertEquals(sampleToken, sanitizeAuthToken(poisoned))
    }

    @Test
    fun `returns empty for a value made only of invisible characters`() {
        assertEquals("", sanitizeAuthToken(" \n\u200B\uFEFF "))
    }
}
