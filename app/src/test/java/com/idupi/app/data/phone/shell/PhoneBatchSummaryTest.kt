package com.idupi.app.data.phone.shell

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One-plan summary (phone-shell T4b): per-step ok/fail, the helper text and the
 * freed space for trim-caches; a failed step never aborts the summary.
 */
class PhoneBatchSummaryTest {

    private val goodTrim = BatchStepOutcome(
        step = ShellOperation.TrimCaches,
        ok = true,
        text = "Caché liberada. Espacio libre: 1,0 GB -> 2,5 GB. Liberaste 1,5 GB.",
    )
    private val goodStop = BatchStepOutcome(
        step = ShellOperation.ForceStop("com.example.a"),
        ok = true,
        text = "App detenida: com.example.a.",
    )
    private val badUninstall = BatchStepOutcome(
        step = ShellOperation.Uninstall("com.example.b"),
        ok = false,
        text = "No se pudo desinstalar com.example.b.",
    )

    @Test
    fun `renders one summary with every step and the counts`() {
        val text = PhoneBatchSummary.render(listOf(goodTrim, goodStop, badUninstall))

        assertTrue(text.startsWith("Plan ejecutado: 3 pasos, 2 bien, 1 con error."))
        assertTrue(text.contains("1. trim-caches: Caché liberada."))
        assertTrue(text.contains("Liberaste 1,5 GB"))
        assertTrue(text.contains("2. force-stop com.example.a: App detenida"))
        assertTrue(text.contains("3. Error en uninstall com.example.b: No se pudo desinstalar"))
    }

    @Test
    fun `renders an all-ok plan`() {
        val text = PhoneBatchSummary.render(listOf(goodTrim, goodStop))
        assertTrue(text.startsWith("Plan ejecutado: 2 pasos, 2 bien, 0 con error."))
    }

    @Test
    fun `an empty plan is explicit`() {
        assertEquals("El plan no tenía pasos.", PhoneBatchSummary.render(emptyList()))
    }

    @Test
    fun `json carries one object per step`() {
        val root = Json.parseToJsonElement(
            PhoneBatchSummary.json(listOf(goodTrim, badUninstall)).toString()
        ).jsonArray

        assertEquals(2, root.size)
        assertEquals("trim-caches", root[0].jsonObject["step"]!!.jsonPrimitive.content)
        assertEquals(true, root[0].jsonObject["ok"]!!.jsonPrimitive.boolean)
        assertEquals("uninstall com.example.b", root[1].jsonObject["step"]!!.jsonPrimitive.content)
        assertEquals(false, root[1].jsonObject["ok"]!!.jsonPrimitive.boolean)
        assertTrue(root[1].jsonObject["text"]!!.jsonPrimitive.content.contains("No se pudo"))
    }
}
