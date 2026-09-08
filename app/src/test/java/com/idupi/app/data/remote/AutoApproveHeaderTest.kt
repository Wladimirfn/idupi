package com.idupi.app.data.remote

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.utils.EmptyContent
import io.ktor.http.takeFrom
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PR 3 / Task 4.1 — RED-first coverage for the
 * [attachOpenCodeAutoApproveHeader] extension on `HttpRequestBuilder`.
 *
 * The OpenCode auto-approve toggle is shipped to the server on every chat
 * request as the `X-OpenCode-Auto-Approve` header. Per design D8 the toggle
 * gates the spawn path only: ON → `opencode run --auto`, OFF → sidecar.
 * The header value MUST be the wire-format string `"0"` (false) or `"1"`
 * (true), never the literal text "true" / "false" (the same wire-shape
 * bug that bit `UiResponsePayload` and was pinned by
 * `RealIduPiClientUiResponseTest`).
 *
 * The extension is the testable seam. `RealIduPiClient.sendMessage` is a
 * one-line wiring of `attachOpenCodeAutoApproveHeader(opencodeAutoApprove)`
 * — code-review confirms the call site, this test pins the contract the
 * call site depends on.
 */
class AutoApproveHeaderTest {

    @Test
    fun `attachOpenCodeAutoApproveHeader sets X-OpenCode-Auto-Approve to 0 when autoApprove is false`() {
        val builder = HttpRequestBuilder()
        builder.url.takeFrom("http://localhost:8788/api/v1/chat/message")
        builder.method = io.ktor.http.HttpMethod.Post
        builder.setBody(EmptyContent)

        builder.attachOpenCodeAutoApproveHeader(autoApprove = false)

        // Ktor's headers builder exposes `get(name)` as `getOrNull`, returning
        // a list of values (the header may repeat). We only attach once here.
        val values = builder.headers.getAll(HEADER_OPENCODE_AUTO_APPROVE)
        assertEquals(
            "X-OpenCode-Auto-Approve MUST be the wire string \"0\" when " +
                "autoApprove is false (toggle OFF, sidecar-first path); " +
                "any other value would invert the gate the spec demands",
            listOf("0"),
            values,
        )
    }

    @Test
    fun `attachOpenCodeAutoApproveHeader sets X-OpenCode-Auto-Approve to 1 when autoApprove is true`() {
        val builder = HttpRequestBuilder()
        builder.url.takeFrom("http://localhost:8788/api/v1/chat/message")
        builder.method = io.ktor.http.HttpMethod.Post
        builder.setBody(EmptyContent)

        builder.attachOpenCodeAutoApproveHeader(autoApprove = true)

        val values = builder.headers.getAll(HEADER_OPENCODE_AUTO_APPROVE)
        assertEquals(
            "X-OpenCode-Auto-Approve MUST be the wire string \"1\" when " +
                "autoApprove is true (toggle ON, legacy run --auto path); " +
                "the literal string \"true\" would be rejected by the " +
                "server-side header parser the same way a JsonElement wire " +
                "bug bit UiResponsePayload",
            listOf("1"),
            values,
        )
    }

    @Test
    fun `autoApproveHeaderValue maps false to 0 and true to 1 (pure function pin)`() {
        // Pure-function pin: this is the value-mapping that
        // `autoApproveHeaderValue(autoApprove)` performs. Keeping it as a
        // top-level function (vs. inline in the extension) means the
        // mapping is testable without HttpRequestBuilder plumbing.
        assertEquals(
            "Boolean false MUST map to wire \"0\"",
            "0",
            autoApproveHeaderValue(autoApprove = false),
        )
        assertEquals(
            "Boolean true MUST map to wire \"1\"",
            "1",
            autoApproveHeaderValue(autoApprove = true),
        )
    }

    @Test
    fun `HEADER_OPENCODE_AUTO_APPROVE constant is the wire name X-OpenCode-Auto-Approve`() {
        // The contract with the server (`runOpenCodeCli` PR 2 / 3.2 reads
        // this header) MUST be a single string literal so a rename on one
        // side surfaces as a test failure on the other. We pin the spelling
        // here instead of re-deriving it from Ktor's headers API.
        assertEquals(
            "the wire header name MUST match the server-side reader " +
                "(\"X-OpenCode-Auto-Approve\"); a typo would silently drop " +
                "the toggle on every request and leave the spawn path stuck " +
                "on the sidecar default",
            "X-OpenCode-Auto-Approve",
            HEADER_OPENCODE_AUTO_APPROVE,
        )
    }
}
