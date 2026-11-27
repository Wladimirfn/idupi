package com.idupi.app.data.phone.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wireless-debugging pairing code validation (phone-shell T2): exactly six
 * ASCII digits, nothing else.
 */
class PairingCodeTest {

    @Test
    fun `accepts exactly six digits`() {
        assertTrue(PairingCode.isValid("000000"))
        assertTrue(PairingCode.isValid("123456"))
        assertTrue(PairingCode.isValid("999999"))
    }

    @Test
    fun `rejects a different number of digits`() {
        assertFalse(PairingCode.isValid("12345"))
        assertFalse(PairingCode.isValid("1234567"))
        assertFalse(PairingCode.isValid(""))
    }

    @Test
    fun `rejects non-digit characters`() {
        assertFalse(PairingCode.isValid("12345a"))
        assertFalse(PairingCode.isValid("12 456"))
        assertFalse(PairingCode.isValid("12-456"))
        assertFalse(PairingCode.isValid("123456\n"))
        // Non-ASCII digits must not reach SPAKE2.
        assertFalse(PairingCode.isValid("١٢٣٤٥٦"))
    }

    @Test
    fun `rejects null`() {
        assertFalse(PairingCode.isValid(null))
    }
}
