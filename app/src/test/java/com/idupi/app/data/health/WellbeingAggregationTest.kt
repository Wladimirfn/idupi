package com.idupi.app.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Digital wellbeing aggregation (phone-health T2). Synthetic UsageEvents lists
 * pin the rules: midnight split, night slice, exclusions, unlock counting and
 * the top-five ranking.
 */
class WellbeingAggregationTest {

    private val tz = -180
    private val days = listOf("2026-11-23", "2026-11-24")

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun event(pkg: String, fromIso: String, toIso: String): List<WellbeingEvent> =
        listOf(
            WellbeingEvent(pkg, WellbeingEventType.FOREGROUND, ms(fromIso)),
            WellbeingEvent(pkg, WellbeingEventType.BACKGROUND, ms(toIso)),
        )

    private fun aggregate(
        events: List<WellbeingEvent>,
        now: String = "2026-11-24T10:00:00Z",
        ownPackage: String? = "com.idupi.app",
        excluded: Set<String> = emptySet(),
        labelFor: (String) -> String? = { null },
    ): List<WellbeingDay> = WellbeingAggregation.aggregate(
        events = events,
        dayKeys = days,
        tzOffsetMinutes = tz,
        ownPackage = ownPackage,
        excludedPackages = excluded,
        nowMillis = ms(now),
        labelFor = labelFor,
    )

    @Test
    fun `an interval crossing local midnight is split between the two days`() {
        val result = aggregate(event("com.a", "2026-11-24T02:00:00Z", "2026-11-24T04:30:00Z"))

        assertEquals(60, result[0].screenTimeMinutes)
        assertEquals(0, result[0].nightScreenMinutes)
        assertEquals(90, result[1].screenTimeMinutes)
        assertEquals(90, result[1].nightScreenMinutes)
    }

    @Test
    fun `unlocks are counted on their owner day`() {
        val events = listOf(
            WellbeingEvent("android", WellbeingEventType.KEYGUARD_HIDDEN, ms("2026-11-23T15:00:00Z")),
            WellbeingEvent("android", WellbeingEventType.KEYGUARD_HIDDEN, ms("2026-11-24T03:00:00Z")),
            WellbeingEvent("android", WellbeingEventType.KEYGUARD_HIDDEN, ms("2026-11-24T04:00:00Z")),
        )

        val result = aggregate(events)

        assertEquals(1, result[0].unlocks)
        assertEquals(2, result[1].unlocks)
    }

    @Test
    fun `IDUPI and the launchers are excluded from totals and ranking`() {
        val events = event("com.idupi.app", "2026-11-24T05:00:00Z", "2026-11-24T06:00:00Z") +
            event("com.android.launcher", "2026-11-24T06:00:00Z", "2026-11-24T07:00:00Z") +
            event("com.real.app", "2026-11-24T07:00:00Z", "2026-11-24T07:30:00Z")

        val result = aggregate(
            events,
            excluded = setOf("com.android.launcher"),
        )

        assertEquals(30, result[1].screenTimeMinutes)
        assertEquals(listOf("com.real.app"), result[1].topApps.map { it.label })
    }

    @Test
    fun `top apps rank by minutes then label and stop at five`() {
        val packages = listOf("com.a", "com.b", "com.c", "com.d", "com.e", "com.f")
        val events = packages.flatMapIndexed { index, pkg ->
            // 10, 20, 20, 10, 10, 10 minutes: ties resolve by label.
            val minutes = if (index in listOf(1, 2)) 20 else 10
            val start = ms("2026-11-24T09:00:00Z") // 06:00 local, after the night slice
            listOf(
                WellbeingEvent(pkg, WellbeingEventType.FOREGROUND, start),
                WellbeingEvent(
                    pkg,
                    WellbeingEventType.BACKGROUND,
                    start + minutes * 60_000L,
                ),
            )
        }

        val result = aggregate(
            events,
            labelFor = { pkg -> "Label $pkg" },
        )

        val top = result[1].topApps
        assertEquals(5, top.size)
        assertEquals(20, top[0].minutes)
        assertEquals(20, top[1].minutes)
        assertEquals("Label com.b", top[0].label)
        assertEquals("Label com.c", top[1].label)
        assertTrue(top.drop(2).all { it.minutes == 10 })
    }

    @Test
    fun `an interval still open at now counts up to now`() {
        val result = aggregate(
            listOf(
                WellbeingEvent(
                    "com.a",
                    WellbeingEventType.FOREGROUND,
                    ms("2026-11-24T09:00:00Z"),
                ),
            ),
        )

        assertEquals(60, result[1].screenTimeMinutes)
        assertEquals(0, result[1].nightScreenMinutes)
    }

    @Test
    fun `sub-minute use is not a top app but still floors into screen time`() {
        val events = listOf(
            WellbeingEvent("com.quick", WellbeingEventType.FOREGROUND, ms("2026-11-24T08:00:00Z")),
            WellbeingEvent("com.quick", WellbeingEventType.BACKGROUND, ms("2026-11-24T08:00:30Z")),
            WellbeingEvent("com.real", WellbeingEventType.FOREGROUND, ms("2026-11-24T08:01:00Z")),
            WellbeingEvent("com.real", WellbeingEventType.BACKGROUND, ms("2026-11-24T08:02:00Z")),
        )

        val result = aggregate(events)

        assertEquals(1, result[1].screenTimeMinutes)
        assertEquals(listOf("com.real"), result[1].topApps.map { it.label })
    }

    @Test
    fun `no events yields zeroed days instead of nothing`() {
        val result = aggregate(emptyList())

        assertEquals(2, result.size)
        assertTrue(result.all { it.screenTimeMinutes == 0 && it.unlocks == 0 })
        assertTrue(result.all { it.topApps.isEmpty() })
    }

    @Test
    fun `labels fall back to the package name`() {
        val result = aggregate(
            event("com.mystery.app", "2026-11-24T05:00:00Z", "2026-11-24T05:30:00Z"),
            labelFor = { null },
        )

        assertEquals("com.mystery.app", result[1].topApps.single().label)
    }
}
