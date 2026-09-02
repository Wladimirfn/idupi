package com.idupi.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chat header said "Chat con Pi" no matter which engine the session was
 * actually talking to, so a Claude or OpenCode conversation was labelled as Pi.
 */
class EngineLabelTest {

    @Test
    fun `each engine is named as itself`() {
        assertEquals("Pi", engineLabel("pi-cli"))
        assertEquals("Claude", engineLabel("claude"))
        assertEquals("OpenCode", engineLabel("opencode"))
    }

    @Test
    fun `the header names the engine of the session`() {
        assertEquals("Chat con Claude", chatTitleFor("claude"))
        assertEquals("Chat con OpenCode", chatTitleFor("opencode"))
        assertEquals("Chat con Pi", chatTitleFor("pi-cli"))
    }

    @Test
    fun `an engine the app does not know shows its own id`() {
        // Falling back to "Pi" would label a Claude session as Pi all over again,
        // which is the defect. An unfamiliar name is honest; a wrong one is not.
        assertEquals("kimi", engineLabel("kimi"))
        assertEquals("Chat con kimi", chatTitleFor("kimi"))
    }

    @Test
    fun `an unknown engine defaults to Pi, which is the server default`() {
        assertEquals("Pi", engineLabel(null))
        assertEquals("Pi", engineLabel(""))
        assertEquals("Pi", engineLabel("   "))
    }

    @Test
    fun `the id is matched regardless of case or padding`() {
        assertEquals("Claude", engineLabel("  Claude  "))
        assertEquals("OpenCode", engineLabel("OPENCODE"))
    }
}
