package com.idupi.app.data.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules for the Dashboard phone page (phone-dashboard T1): which apps the
 * "Apps que más ocupan" section shows, how they are ordered, and the es-CL
 * formatting the owner reads.
 */
class PhoneDashboardSnapshotTest {

    private fun app(
        packageName: String,
        appBytes: Long = 0L,
        dataBytes: Long = 0L,
        cacheBytes: Long = 0L,
        label: String = packageName,
    ) = PhoneAppStorage(
        label = label,
        packageName = packageName,
        appBytes = appBytes,
        dataBytes = dataBytes,
        cacheBytes = cacheBytes,
    )

    @Test
    fun `summarize sorts apps by total size descending`() {
        val summary = PhoneAppStorageMapping.summarize(
            listOf(
                app("small", appBytes = 10, dataBytes = 20, cacheBytes = 30), // 60
                app("big", appBytes = 500, cacheBytes = 500), // 1000
                app("middle", dataBytes = 300), // 300
            )
        )

        assertEquals(listOf("big", "middle", "small"), summary.apps.map { it.packageName })
    }

    @Test
    fun `summarize breaks equal totals by package name`() {
        val summary = PhoneAppStorageMapping.summarize(
            listOf(app("zeta", appBytes = 100), app("alfa", appBytes = 100))
        )

        assertEquals(listOf("alfa", "zeta"), summary.apps.map { it.packageName })
    }

    @Test
    fun `summarize caps the list at the eight largest apps`() {
        val apps = (1..10).map { app("pkg$it", appBytes = it * 10L) }

        val summary = PhoneAppStorageMapping.summarize(apps)

        assertEquals(8, summary.apps.size)
        assertEquals("pkg10", summary.apps.first().packageName)
        assertEquals("pkg3", summary.apps.last().packageName)
    }

    @Test
    fun `total cache counts every app even outside the top eight`() {
        val apps = (1..10).map { app("pkg$it", appBytes = 100, cacheBytes = 100) }

        val summary = PhoneAppStorageMapping.summarize(apps)

        assertEquals(8, summary.apps.size)
        assertEquals(1_000L, summary.totalCacheBytes)
    }

    @Test
    fun `total includes app data and cache bytes`() {
        val entry = app("pkg", appBytes = 1, dataBytes = 2, cacheBytes = 3)

        assertEquals(6L, entry.totalBytes)
    }

    @Test
    fun `bytes format in es-CL style across units`() {
        assertEquals("1,2 GB", formatPhoneBytes(1_288_490_189L))
        assertEquals("1 GB", formatPhoneBytes(1_073_741_824L))
        assertEquals("340,5 MB", formatPhoneBytes(357_040_128L))
        assertEquals("1,5 kB", formatPhoneBytes(1_536L))
        assertEquals("512 B", formatPhoneBytes(512L))
        assertEquals("0 B", formatPhoneBytes(0L))
        assertNull(formatPhoneBytes(null))
        assertNull(formatPhoneBytes(-1L))
    }

    @Test
    fun `usage joins the known ends only`() {
        assertEquals(
            "42 GB / 128 GB",
            formatPhoneUsageBytes(45_097_156_608L, 137_438_953_472L)
        )
        assertEquals("42 GB", formatPhoneUsageBytes(45_097_156_608L, null))
        assertEquals("128 GB", formatPhoneUsageBytes(null, 137_438_953_472L))
        assertNull(formatPhoneUsageBytes(null, null))
    }

    @Test
    fun `usage percent is clamped and null when the total is unknown`() {
        assertEquals(50.0, phoneUsagePercent(50, 100)!!, 0.0001)
        assertEquals(100.0, phoneUsagePercent(120, 100)!!, 0.0001)
        assertNull(phoneUsagePercent(10, 0))
        assertNull(phoneUsagePercent(null, 100))
        assertNull(phoneUsagePercent(10, null))
    }

    @Test
    fun `network kinds map to the owner-facing labels`() {
        assertEquals("Wi-Fi", networkKindLabel("wifi"))
        assertEquals("Datos móviles", networkKindLabel("mobile"))
        assertEquals("Sin conexión", networkKindLabel("none"))
        assertEquals("Ethernet", networkKindLabel("ethernet"))
        assertEquals("VPN", networkKindLabel("vpn"))
        assertEquals("Bluetooth", networkKindLabel("bluetooth"))
        assertEquals("Otra", networkKindLabel("other"))
        assertNull(networkKindLabel(null))
        assertNull(networkKindLabel(""))
    }

    @Test
    fun `signal level maps to a fraction of four bars`() {
        assertEquals("4/4", signalLevelLabel(4))
        assertEquals("0/4", signalLevelLabel(0))
        assertEquals("5/4", signalLevelLabel(5))
        assertNull(signalLevelLabel(null))
    }

    @Test
    fun `charging state maps to si or no`() {
        assertEquals("Sí", batteryChargingLabel(true))
        assertEquals("No", batteryChargingLabel(false))
        assertNull(batteryChargingLabel(null))
    }

    @Test
    fun `an app storage refresh is due only after the cache window`() {
        assertTrue(AppStorageRefreshPolicy.isDue(null, nowMillis = 1_000L))
        assertFalse(AppStorageRefreshPolicy.isDue(1_000L, nowMillis = 1_000L))
        assertFalse(AppStorageRefreshPolicy.isDue(1_000L, nowMillis = 60_999L))
        assertTrue(AppStorageRefreshPolicy.isDue(1_000L, nowMillis = 61_000L))
    }
}
