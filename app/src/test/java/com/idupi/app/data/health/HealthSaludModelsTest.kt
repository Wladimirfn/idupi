package com.idupi.app.data.health

import com.idupi.app.domain.model.HealthDaySummary
import com.idupi.app.domain.model.HealthDigest
import com.idupi.app.domain.model.HealthSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Salud screen mappers (phone-health T3): today cards, seven-day trends and the
 * neutral Spanish formatting. Pure JVM, no Compose and no Android.
 */
class HealthSaludModelsTest {

    private val today = "2026-11-24"

    private fun day(
        date: String,
        metrics: HealthMetrics,
        sources: Map<String, String> = emptyMap(),
    ) = HealthDaySummary(date = date, metrics = metrics, sources = sources)

    private fun summary(vararg days: HealthDaySummary) = HealthSummary(
        rangeDays = 7,
        tzOffsetMinutes = -180,
        days = days.toList(),
        latest = days.lastOrNull(),
    )

    @Test
    fun `today cards cover every metric with its source label`() {
        val view = HealthSalud.map(
            summary(
                day(
                    today,
                    HealthMetrics(
                        steps = 12400,
                        stepsGoal = 10000,
                        sleepMinutes = 509,
                        sleepGoalMinutes = 480,
                        sleepStages = HealthSleepStages(deep = 90, light = 300, rem = 89, awake = 30),
                        heartRateBpm = 72,
                        restingHeartRateBpm = 58,
                        heartRateMin = 55,
                        heartRateMax = 120,
                        stress = 35,
                        stressLabel = "Bajo",
                        spo2Pct = 97.0,
                        weightKg = 72.5,
                        exerciseMinutes = 45,
                        exerciseGoalMinutes = 30,
                        waterMl = 1500,
                        waterGoalMl = 2000,
                        screenTimeMinutes = 192,
                        unlocks = 44,
                        nightScreenMinutes = 12,
                        topApps = listOf(
                            HealthTopApp("YouTube", 70),
                            HealthTopApp("WhatsApp", 25),
                            HealthTopApp("Chrome", 15),
                            HealthTopApp("Maps", 5),
                        ),
                    ),
                    sources = mapOf(
                        "steps" to "huawei_health",
                        "sleepMinutes" to "health_connect",
                        "screenTimeMinutes" to "wellbeing",
                        "topApps" to "wellbeing",
                    ),
                ),
            ),
            todayKey = today,
            label = "lunes 24 de noviembre",
        )

        assertEquals("lunes 24 de noviembre", view.dateLabel)
        assertTrue(view.hasData)
        assertEquals(
            listOf(
                "steps", "sleep", "heartRate", "stress", "spo2", "weight",
                "exercise", "water", "screenTime", "topApps",
            ),
            view.cards.map { it.key },
        )

        val steps = view.cards.first { it.key == "steps" }
        assertEquals("12,400", steps.value)
        assertEquals("de 10,000 pasos", steps.detail)
        assertEquals("Huawei Health", steps.sourceLabel)
        assertEquals(1f, steps.progress!!, 0.0001f)

        val sleep = view.cards.first { it.key == "sleep" }
        assertEquals("8 h 29 min", sleep.value)
        assertTrue(sleep.detail!!.contains("meta 8 h"))
        assertTrue(sleep.detail!!.contains("Profundo 1 h 30 min"))
        assertTrue(sleep.detail!!.contains("REM 1 h 29 min"))
        assertEquals("Health Connect", sleep.sourceLabel)

        val heart = view.cards.first { it.key == "heartRate" }
        assertEquals("72 lpm", heart.value)
        assertTrue(heart.detail!!.contains("Reposo 58 lpm"))
        assertTrue(heart.detail!!.contains("Rango 55-120 lpm"))

        val stress = view.cards.first { it.key == "stress" }
        assertEquals("35", stress.value)
        assertEquals("Bajo", stress.detail)

        val spo2 = view.cards.first { it.key == "spo2" }
        assertEquals("97%", spo2.value)

        val weight = view.cards.first { it.key == "weight" }
        assertEquals("72.5 kg", weight.value)

        val exercise = view.cards.first { it.key == "exercise" }
        assertEquals("45 min", exercise.value)
        assertEquals("meta 30 min", exercise.detail)

        val water = view.cards.first { it.key == "water" }
        assertEquals("1,500 ml", water.value)
        assertEquals(0.75f, water.progress!!, 0.0001f)

        val screen = view.cards.first { it.key == "screenTime" }
        assertEquals("3 h 12 min", screen.value)
        assertEquals("44 desbloqueos · 12 min de noche", screen.detail)
        assertEquals("Bienestar digital", screen.sourceLabel)

        val apps = view.cards.first { it.key == "topApps" }
        assertEquals("YouTube 1 h 10 min · WhatsApp 25 min · Chrome 15 min", apps.value)
    }

    @Test
    fun `metrics without data are hidden and yesterday is never shown as today`() {
        val view = HealthSalud.map(
            summary(
                day("2026-11-23", HealthMetrics(steps = 5000, sleepMinutes = 400)),
                day(today, HealthMetrics(weightKg = 72.0)),
            ),
            todayKey = today,
            label = "hoy",
        )

        assertEquals(listOf("weight"), view.cards.map { it.key })
        assertEquals("72 kg", view.cards.single().value)
    }

    @Test
    fun `a day that only has resting heart rate still shows the heart card`() {
        val view = HealthSalud.map(
            summary(day(today, HealthMetrics(restingHeartRateBpm = 60))),
            todayKey = today,
            label = "hoy",
        )
        val card = view.cards.single()
        assertEquals("heartRate", card.key)
        assertEquals("60 lpm en reposo", card.value)
        assertNull(card.detail)
    }

    @Test
    fun `a day without samples renders the empty state`() {
        val view = HealthSalud.map(summary(), todayKey = today, label = "hoy")
        assertFalse(view.hasData)
        assertTrue(view.cards.isEmpty())
        assertTrue(view.trends.isEmpty())
    }

    @Test
    fun `trends keep seven days, nulls for missing days and only series with data`() {
        val days = (18..24).map { dayNumber ->
            val date = "2026-11-$dayNumber"
            when (dayNumber) {
                18 -> day(date, HealthMetrics(steps = 6100, sleepMinutes = 420))
                19 -> day(date, HealthMetrics(steps = 7200))
                20 -> day(date, HealthMetrics(restingHeartRateBpm = 59))
                21 -> day(date, HealthMetrics(steps = 9100, screenTimeMinutes = 120))
                else -> day(date, HealthMetrics())
            }
        }
        val view = HealthSalud.map(summary(*days.toTypedArray()), todayKey = today, label = "hoy")

        assertEquals(listOf("steps", "sleep", "restingHeartRate", "screenTime"), view.trends.map { it.key })
        val steps = view.trends.first { it.key == "steps" }
        assertEquals(7, steps.points.size)
        assertEquals(listOf(6100f, 7200f, null, 9100f, null, null, null), steps.points)
        assertEquals(9100f, steps.maxValue, 0.0001f)
        assertEquals(HealthTrendStyle.BARS, steps.style)

        val sleep = view.trends.first { it.key == "sleep" }
        assertEquals(7f, sleep.points[0]!!, 0.0001f) // 420 min
        assertEquals(HealthTrendStyle.SPARKLINE, sleep.style)

        val screen = view.trends.first { it.key == "screenTime" }
        assertEquals(2f, screen.points[3]!!, 0.0001f) // 120 min
    }

    @Test
    fun `formatting is deterministic and neutral`() {
        assertEquals("12,400", HealthSalud.formatCount(12400))
        assertEquals("0", HealthSalud.formatCount(0))
        assertEquals("72", HealthSalud.formatDecimal(72.0))
        assertEquals("72.5", HealthSalud.formatDecimal(72.45))
        assertEquals("45 min", HealthSalud.formatMinutes(45))
        assertEquals("1 h", HealthSalud.formatMinutes(60))
        assertEquals("8 h 29 min", HealthSalud.formatMinutes(509))
        assertEquals("0 min", HealthSalud.formatMinutes(-5))
        assertEquals("8.5 h", HealthSalud.formatTrendValue(8.5f, "h"))
        assertEquals("58 lpm", HealthSalud.formatTrendValue(58f, "lpm"))
        assertEquals("martes 24 de noviembre", HealthSalud.dateLabel(LocalDate.of(2026, 11, 24)))
        assertEquals("domingo 1 de marzo", HealthSalud.dateLabel(LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun `source labels fall back to the id and blank is null`() {
        assertEquals("Huawei Health", HealthSalud.sourceLabel("huawei_health"))
        assertEquals("Bienestar digital", HealthSalud.sourceLabel("wellbeing"))
        assertEquals("custom_source", HealthSalud.sourceLabel("custom_source"))
        assertNull(HealthSalud.sourceLabel(null))
        assertNull(HealthSalud.sourceLabel("  "))
    }

    @Test
    fun `digest copy offers the right action and state`() {
        val empty = HealthDigest(available = true)
        assertEquals("Generar resumen", HealthDigestCopy.actionLabel(empty))
        assertEquals("No es consejo médico.", HealthDigestCopy.FOOTER)

        val filled = HealthDigest(summary = "Hoy caminaste más.")
        assertEquals("Actualizar resumen", HealthDigestCopy.actionLabel(filled))

        assertNull(HealthDigestCopy.stateText(filled))
        assertEquals("Analizando…", HealthDigestCopy.stateText(HealthDigest(status = "analyzing")))
        assertEquals(
            "agent timed out",
            HealthDigestCopy.stateText(HealthDigest(status = "failed", error = "agent timed out")),
        )
        assertEquals(
            "Sin datos de salud todavía.",
            HealthDigestCopy.stateText(HealthDigest(available = false)),
        )
    }
}
