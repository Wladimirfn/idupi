package com.idupi.app.data.phone.approval

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spanish plan copy for `shell` approvals (phone-shell T4b): the owner sees the
 * whole plan grouped in one sentence, with labels when they are resolvable.
 */
class ShellPlanTextTest {

    private val labels = mapOf(
        "com.example.a" to "App A",
        "com.example.b" to "App B",
        "com.example.c" to "App C",
        "com.example.d" to "App D",
        "com.example.e" to "App E",
    )
    private val labelFor: (String) -> String? = { labels[it] }

    private fun single(action: String, pkg: String? = null, mode: String? = null): JsonObject =
        buildJsonObject {
            put("action", action)
            pkg?.let { put("package", it) }
            mode?.let { put("mode", it) }
        }

    private fun batch(vararg steps: JsonObject): JsonObject = buildJsonObject {
        put("action", "batch")
        put("steps", buildJsonArray { steps.forEach { add(it) } })
    }

    @Test
    fun `single shell actions read as one sentence`() {
        assertEquals(
            "Borrar la caché de todas las apps",
            ShellPlanText.actionLine(single("trim-caches")),
        )
        assertEquals(
            "Detener la app «com.example.a»",
            ShellPlanText.actionLine(single("force-stop", "com.example.a")),
        )
        assertEquals(
            "Permitir el segundo plano de «com.example.a»",
            ShellPlanText.actionLine(single("background", "com.example.a", "allow")),
        )
        assertEquals(
            "Limitar el segundo plano de «com.example.a»",
            ShellPlanText.actionLine(single("background", "com.example.a", "ignore")),
        )
        assertEquals(
            "Desactivar la app «com.example.a»",
            ShellPlanText.actionLine(single("disable", "com.example.a")),
        )
        assertEquals(
            "Activar la app «com.example.a»",
            ShellPlanText.actionLine(single("enable", "com.example.a")),
        )
        assertEquals(
            "Desinstalar la app «com.example.a»",
            ShellPlanText.actionLine(single("uninstall", "com.example.a")),
        )
    }

    @Test
    fun `labels replace package ids when they resolve`() {
        assertEquals(
            "Detener la app «App A»",
            ShellPlanText.actionLine(single("force-stop", "com.example.a"), labelFor),
        )
        assertEquals(
            "Detener la app «com.example.unknown»",
            ShellPlanText.actionLine(single("force-stop", "com.example.unknown"), labelFor),
        )
    }

    @Test
    fun `a plan groups its steps the way the owner reads it`() {
        val plan = batch(
            single("uninstall", "com.example.c"),
            single("trim-caches"),
            single("force-stop", "com.example.a"),
            single("force-stop", "com.example.b"),
            single("uninstall", "com.example.d"),
            single("uninstall", "com.example.e"),
        )

        assertEquals(
            "Borrar la caché de todas las apps; detener 2 apps: App A, App B; " +
                "desinstalar 3 apps: App C, App D, App E",
            ShellPlanText.actionLine(plan, labelFor),
        )
    }

    @Test
    fun `a plan uses singular nouns for one app`() {
        val plan = batch(
            single("trim-caches"),
            single("disable", "com.example.a"),
        )
        assertEquals(
            "Borrar la caché de todas las apps; desactivar 1 app: App A",
            ShellPlanText.actionLine(plan, labelFor),
        )
    }

    @Test
    fun `a plan without labels still names the packages`() {
        val plan = batch(single("force-stop", "com.example.a"))
        assertEquals(
            "Detener 1 app: com.example.a",
            ShellPlanText.actionLine(plan),
        )
    }

    @Test
    fun `destructive detection covers single and plan steps`() {
        assertTrue(ShellPlanText.containsDestructive(single("uninstall", "com.example.a")))
        assertTrue(ShellPlanText.containsDestructive(single("disable", "com.example.a")))
        assertFalse(ShellPlanText.containsDestructive(single("trim-caches")))
        assertFalse(ShellPlanText.containsDestructive(single("enable", "com.example.a")))
        assertTrue(
            ShellPlanText.containsDestructive(
                batch(single("trim-caches"), single("uninstall", "com.example.a"))
            )
        )
        assertFalse(
            ShellPlanText.containsDestructive(
                batch(single("trim-caches"), single("force-stop", "com.example.a"))
            )
        )
    }

    @Test
    fun `step lines are numbered and include the background verb`() {
        val plan = batch(
            single("trim-caches"),
            single("background", "com.example.a", "ignore"),
            single("uninstall", "com.example.b"),
        )
        assertEquals(
            listOf(
                "1. Borrar la caché de todas las apps",
                "2. Limitar el segundo plano de App A",
                "3. Desinstalar App B",
            ),
            ShellPlanText.stepLines(plan, labelFor),
        )
    }

    @Test
    fun `reason labels follow the typed action`() {
        assertEquals("Plan de optimización", ShellPlanText.reasonLabel(batch(single("trim-caches"))))
        assertEquals("Acción destructiva", ShellPlanText.reasonLabel(single("uninstall", "com.example.a")))
        assertEquals("Optimización del teléfono", ShellPlanText.reasonLabel(single("trim-caches")))
    }

    @Test
    fun `batch detection is explicit`() {
        assertTrue(ShellPlanText.isBatch(batch(single("trim-caches"))))
        assertFalse(ShellPlanText.isBatch(single("trim-caches")))
        assertEquals(1, (batch(single("trim-caches"))["steps"] as JsonArray).size)
    }
}
