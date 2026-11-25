package com.idupi.app.data.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules of the daily usage summary (phone-agent T2). The collector only
 * translates `UsageEvents` into [UsageEvent]s; every ranking and timing rule is
 * pinned here because it cannot be exercised without a real device.
 */
class UsageAggregationTest {

    private fun fg(packageName: String, atMillis: Long) =
        UsageEvent(packageName, UsageEventType.FOREGROUND, atMillis)

    private fun bg(packageName: String, atMillis: Long) =
        UsageEvent(packageName, UsageEventType.BACKGROUND, atMillis)

    private fun aggregate(
        events: List<UsageEvent>,
        nowMillis: Long,
        ownPackage: String? = null,
        labelFor: (String) -> String? = { null },
    ) = UsageAggregation.aggregate(events, nowMillis, ownPackage, labelFor)

    @Test
    fun `a closed interval becomes whole floored minutes`() {
        val result = aggregate(
            events = listOf(fg("com.app", 0), bg("com.app", 150_000)),
            nowMillis = 150_000,
        )

        assertEquals(1, result.topApps.size)
        assertEquals(PhoneTopApp("com.app", "com.app", 2), result.topApps.single())
        assertEquals(PhoneAppRef("com.app", "com.app"), result.foregroundApp)
    }

    @Test
    fun `an interval still open is counted up to now`() {
        val result = aggregate(
            events = listOf(fg("com.app", 0)),
            nowMillis = 120_000,
        )

        assertEquals(2, result.topApps.single().minutes)
    }

    @Test
    fun `usage below one minute never appears`() {
        val result = aggregate(
            events = listOf(fg("com.app", 0), bg("com.app", 59_000)),
            nowMillis = 59_000,
        )

        assertTrue(result.topApps.isEmpty())
        // The app was still the last thing in front, even with no ranked time.
        assertEquals("com.app", result.foregroundApp?.packageName)
    }

    @Test
    fun `switching apps closes the previous interval`() {
        val result = aggregate(
            events = listOf(
                fg("com.a", 0),
                fg("com.b", 60_000),
            ),
            nowMillis = 120_000,
        )

        assertEquals(
            listOf(
                PhoneTopApp("com.a", "com.a", 1),
                PhoneTopApp("com.b", "com.b", 1),
            ),
            result.topApps
        )
    }

    @Test
    fun `repeated foreground events for the same app do not double count`() {
        val result = aggregate(
            events = listOf(
                fg("com.app", 0),
                fg("com.app", 30_000),
                bg("com.app", 120_000),
            ),
            nowMillis = 120_000,
        )

        assertEquals(2, result.topApps.single().minutes)
    }

    @Test
    fun `a background event for another package is ignored`() {
        val result = aggregate(
            events = listOf(
                fg("com.app", 0),
                bg("com.other", 60_000),
                bg("com.app", 120_000),
            ),
            nowMillis = 120_000,
        )

        assertEquals(2, result.topApps.single().minutes)
    }

    @Test
    fun `the foreground app is the last foreground event`() {
        val result = aggregate(
            events = listOf(
                fg("com.a", 0),
                bg("com.a", 10_000),
                fg("com.b", 20_000),
                bg("com.b", 30_000),
            ),
            nowMillis = 30_000,
        )

        assertEquals("com.b", result.foregroundApp?.packageName)
    }

    @Test
    fun `the reporting app is excluded from ranking and foreground`() {
        val result = aggregate(
            events = listOf(
                fg("com.other", 0),
                bg("com.other", 60_000),
                fg("com.idupi.app", 60_000),
            ),
            nowMillis = 300_000,
            ownPackage = "com.idupi.app",
        )

        assertEquals(listOf("com.other"), result.topApps.map { it.packageName })
        assertEquals("com.other", result.foregroundApp?.packageName)
    }

    @Test
    fun `labels come from the resolver and fall back to the package`() {
        val labels = mapOf("com.a" to "App A", "com.b" to "  ")
        val result = aggregate(
            events = listOf(fg("com.a", 0), fg("com.b", 60_000)),
            nowMillis = 180_000,
            labelFor = { labels[it] },
        )

        assertEquals(
            listOf(
                PhoneTopApp("com.b", "com.b", 2),
                PhoneTopApp("App A", "com.a", 1),
            ),
            result.topApps
        )
        // com.b is the latest foreground; its blank label falls back too.
        assertEquals(PhoneAppRef("com.b", "com.b"), result.foregroundApp)
    }

    @Test
    fun `ranking is limited to ten apps, longest first`() {
        var t = 0L
        val events = mutableListOf<UsageEvent>()
        for (i in 1..11) {
            val packageName = "p%02d".format(i)
            events += fg(packageName, t)
            t += i * 60_000L
            events += bg(packageName, t)
        }

        val result = aggregate(events = events, nowMillis = t)

        assertEquals(UsageAggregation.TOP_APPS_LIMIT, result.topApps.size)
        assertEquals("p11", result.topApps.first().packageName)
        assertEquals(11, result.topApps.first().minutes)
        assertEquals("p02", result.topApps.last().packageName)
        assertEquals(2, result.topApps.last().minutes)
        assertTrue(result.topApps.none { it.packageName == "p01" })
    }

    @Test
    fun `events out of order are sorted before aggregating`() {
        val result = aggregate(
            events = listOf(
                bg("com.app", 60_000),
                fg("com.app", 0),
            ),
            nowMillis = 60_000,
        )

        assertEquals(1, result.topApps.single().minutes)
    }

    @Test
    fun `no events yields no ranking and no foreground app`() {
        val result = aggregate(events = emptyList(), nowMillis = 1_000)

        assertTrue(result.topApps.isEmpty())
        assertNull(result.foregroundApp)
    }
}
