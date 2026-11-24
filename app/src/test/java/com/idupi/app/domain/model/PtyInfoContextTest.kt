package com.idupi.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire parsing of `GET /api/v1/pty` items after T2 added the live `context`
 * object. Same Json configuration the app's client uses, so these tests pin
 * what a real server answer (and an older one without `context`) decodes to.
 */
class PtyInfoContextTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Mirrors the client's private `PtyListResponse` wrapper. */
    @Serializable
    private data class TestPtyList(val terminals: List<PtyInfo> = emptyList())

    @Test
    fun `parses a listed terminal with live context`() {
        val payload = """
            {"terminals":[{
              "id":"pty-1","pid":42,"cols":100,"rows":30,
              "cwd":"C:\\Users\\owner","shell":"powershell.exe -NoLogo",
              "createdAt":"2026-11-23T10:00:00Z","alive":true,
              "context":{
                "cwd":"C:\\Users\\owner\\AndroidStudioProjects\\IDUPI",
                "project":"IDUPI",
                "branch":"feat/terminal-context",
                "detached":false,
                "agent":{"id":"claude","name":"Claude Code","pid":777}
              }
            }]}
        """.trimIndent()

        val info = json.decodeFromString<TestPtyList>(payload).terminals.single()

        assertEquals("pty-1", info.id)
        assertEquals("C:\\Users\\owner", info.cwd)
        val context = info.context
        assertNotNull(context)
        assertEquals("C:\\Users\\owner\\AndroidStudioProjects\\IDUPI", context?.cwd)
        assertEquals("IDUPI", context?.project)
        assertEquals("feat/terminal-context", context?.branch)
        assertFalse(context?.detached ?: true)
        assertEquals("claude", context?.agent?.id)
        assertEquals("Claude Code", context?.agent?.name)
        assertEquals(777, context?.agent?.pid)
    }

    @Test
    fun `an older item without context decodes with null context`() {
        val payload = """
            {"id":"pty-2","pid":7,"cols":80,"rows":24,
             "cwd":"C:\\Users\\owner","shell":"pwsh.exe","alive":true}
        """.trimIndent()

        val info = json.decodeFromString<PtyInfo>(payload)

        assertEquals("pty-2", info.id)
        assertNull(info.context)
    }

    @Test
    fun `a context with every piece missing decodes with defaults`() {
        val payload = """{"id":"pty-3","context":{}}"""

        val context = json.decodeFromString<PtyInfo>(payload).context

        assertNotNull(context)
        assertNull(context?.cwd)
        assertNull(context?.project)
        assertNull(context?.branch)
        assertFalse(context?.detached ?: true)
        assertNull(context?.agent)
    }

    @Test
    fun `a shell-only context keeps the null agent`() {
        val payload = """
            {"id":"pty-4","context":{
              "cwd":"C:\\work","project":"work","branch":null,"detached":false,"agent":null
            }}
        """.trimIndent()

        val context = json.decodeFromString<PtyInfo>(payload).context

        assertEquals("work", context?.project)
        assertNull(context?.branch)
        assertNull(context?.agent)
    }

    @Test
    fun `unknown context fields do not break decoding`() {
        val payload = """
            {"id":"pty-5","context":{
              "cwd":"C:\\work","project":"work","branch":"main","detached":false,
              "agent":{"id":"opencode","name":"OpenCode","pid":10,"future":"x"},
              "battery":93
            }}
        """.trimIndent()

        val context = json.decodeFromString<PtyInfo>(payload).context

        assertEquals("opencode", context?.agent?.id)
        assertEquals("main", context?.branch)
        assertTrue(context?.detached == false)
    }
}
