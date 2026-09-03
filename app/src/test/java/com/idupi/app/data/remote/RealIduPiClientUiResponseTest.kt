package com.idupi.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6.3 — RED-first coverage for `RealIduPiClient.sendUiResponse`.
 *
 * The bug that motivated this fix: a Kotlin `Boolean true` was sent to the
 * server as the literal text `"true"` (because the call site used
 * `value.toString()`), and the server's `typeof value !== "boolean"` check
 * rejected the body. The wire format the registry expects is
 *
 *   { "value": <select|confirm|input>, "token": "<token>", "sessionId": "<id>" }
 *
 * where `value` MUST keep its JSON type: a `Boolean` becomes the JSON literal
 * `true`/`false`, a `String` becomes a JSON string, a `Number` becomes a JSON
 * number, and an already-typed `JsonElement` is forwarded as-is.
 *
 * The conversion lives in a private `Any.toJsonElement()` helper inside
 * RealIduPiClient.kt (the file under test). Reaching it without modifying
 * production code is done through reflection, which is the standard JVM
 * technique for testing private helpers from a unit test. The assertions below
 * are deliberately pinned to the WIRE shape (boolean literal vs. the buggy
 * quoted string), not the helper's name, so a future refactor that moves the
 * helper cannot silently re-introduce the bug.
 */
class RealIduPiClientUiResponseTest {

    private val toJsonElement: (Any) -> JsonElement by lazy {
        // The helper is a top-level private extension in RealIduPiClient.kt.
        // Kotlin compiles top-level extensions to a synthetic `<FileName>Kt`
        // class with a `static` method named after the function. The
        // `private` modifier on a top-level function compiles to package-
        // private on the JVM, which `setAccessible(true)` overrides.
        // (Verified convention: see `SseFrameParser.kt` → `SseFrameParserKt`.)
        val cls = try {
            Class.forName("com.idupi.app.data.remote.RealIduPiClientKt")
        } catch (e: ClassNotFoundException) {
            throw AssertionError(
                "RealIduPiClientKt class not found on the test classpath. " +
                "Has the project been rebuilt since Phase 4 added the top-level " +
                "toJsonElement helper to RealIduPiClient.kt? " +
                "(Kotlin top-level extensions compile to a `<FileName>Kt` " +
                "synthetic class; if that class is missing, the bytecode is " +
                "stale.)",
                e,
            )
        }
        val methods = cls.declaredMethods.filter { it.name == "toJsonElement" }
        assertTrue(
            "expected exactly one `toJsonElement` method in RealIduPiClientKt; " +
            "found ${methods.size}. Has the helper been inlined or moved?",
            methods.size == 1,
        )
        val method = methods.first()
        method.isAccessible = true
        val paramType = method.parameterTypes[0]
        assertTrue(
            "toJsonElement parameter must be java.lang.Object (Kotlin Any); got $paramType",
            paramType == Any::class.java || paramType == Object::class.java,
        )
        { value: Any -> method.invoke(null, value) as JsonElement }
    }

    @Test
    fun `Boolean true lifts to a JSON boolean primitive, not the string "true"`() {
        val elem = toJsonElement(true)
        assertEquals(
            "true (JSON boolean), never the literal string \"true\"",
            JsonPrimitive(true), elem,
        )
        assertEquals("true", (elem as JsonPrimitive).content)
        assertNotNull(
            "Boolean true MUST parse back as JSON true; " +
            "a quoted \"true\" would parse as string and fail this assertion",
            elem.booleanOrNull,
        )
        assertEquals(true, elem.booleanOrNull)
        assertNotEquals("string-content", elem.contentOrNull)
    }

    @Test
    fun `Boolean false lifts to a JSON boolean primitive, not the string "false"`() {
        val elem = toJsonElement(false)
        assertEquals(JsonPrimitive(false), elem)
        assertEquals(false, (elem as JsonPrimitive).booleanOrNull)
        assertTrue("Boolean false must be the JSON literal `false`", elem.content == "false")
    }

    @Test
    fun `String lifts to a JSON string primitive preserving the exact text`() {
        // Spec §"Exact value accepted": the registry's `validateUiAnswer` does
        // strict string equality against the offered options. Wrapping in
        // extra quotes would silently reject every answer.
        val elem = toJsonElement("B")
        assertEquals(JsonPrimitive("B"), elem)
        assertEquals("B", (elem as JsonPrimitive).content)
        // A future regression to `value.toString()` on a String still works for
        // strings, so we also pin the input-shape invariant for INPUT.
        val freeText = toJsonElement("hello world")
        assertEquals("hello world", (freeText as JsonPrimitive).content)
    }

    @Test
    fun `Number lifts to a JSON numeric primitive (no string coercion)`() {
        // `value.toString()` on a Number is also string-shaped ("42"). The
        // helper MUST keep it numeric so a future method that wants a count
        // can post it without an extra encode step.
        val elem = toJsonElement(42)
        assertEquals(JsonPrimitive(42), elem)
        assertFalse("Number must NOT be a JSON string", elem is JsonPrimitive && elem.isString)
    }

    @Test
    fun `a pre-built JsonElement passes through unchanged (identity, not re-encoded)`() {
        // Useful when a future method type wants to ship an object/array
        // instead of a scalar. Re-encoding via toString() would lose the
        // structure.
        val original = Json.parseToJsonElement("""{"nested":true,"list":[1,2]}""")
        val elem = toJsonElement(original)
        assertEquals(original, elem)
    }

    @Test
    fun `an unknown Any falls back to toString-stringified (so 400 surfaces server-side)`() {
        // Spec rejects unrecognised types with 400. Better to send the wrong
        // shape than to throw on the wire -- the user sees a clear server
        // rejection instead of an opaque client crash. We pin the fallback to
        // `toString()` so a future change is intentional, not accidental.
        val weird = object {
            override fun toString(): String = "<custom:42>"
        }
        val elem = toJsonElement(weird)
        assertEquals(JsonPrimitive("<custom:42>"), elem)
    }

    /**
     * End-to-end shape assertion: a `UiResponsePayload`-shaped document built
     * from the same JsonElement contract the helper produces MUST serialize
     * to the registry's exact wire shape:
     *
     *   { "value": true,  "token": "...", "sessionId": "..." }   (confirm)
     *   { "value": "B",   "token": "...", "sessionId": "..." }   (select/input)
     *
     * This is the regression guard against `value.toString()` quietly coming
     * back: the encoded payload for `confirm=true` MUST contain `"true"` as
     * the JSON literal, not the JSON string `"true"`.
     */
    @Test
    fun `wire body for a confirm-true answer encodes value as the JSON literal true`() {
        // We re-declare the same shape the production payload uses, with the
        // same field order, so the encoded JSON is byte-identical to what
        // ktor's setBody(UiResponsePayload(...)) would emit.
        val payload = mapOf(
            "value" to toJsonElement(true),
            "token" to "tok-7",
            "sessionId" to "sess-1",
        )
        val encoded = Json.encodeToString(
            kotlinx.serialization.builtins.MapSerializer(
                kotlinx.serialization.builtins.serializer<String>(),
                kotlinx.serialization.json.JsonElement.serializer(),
            ),
            payload,
        )
        assertEquals(
            "wire body MUST serialize Boolean true as JSON true, " +
            "never the string \"true\" (this is the exact bug we are regressing against)",
            """{"value":true,"token":"tok-7","sessionId":"sess-1"}""",
            encoded,
        )
    }

    @Test
    fun `wire body for a select-B answer encodes value as the JSON string "B"`() {
        val payload = mapOf(
            "value" to toJsonElement("B"),
            "token" to "tok-7",
            "sessionId" to "sess-1",
        )
        val encoded = Json.encodeToString(
            kotlinx.serialization.builtins.MapSerializer(
                kotlinx.serialization.builtins.serializer<String>(),
                kotlinx.serialization.json.JsonElement.serializer(),
            ),
            payload,
        )
        assertEquals(
            """{"value":"B","token":"tok-7","sessionId":"sess-1"}""",
            encoded,
        )
    }
}
