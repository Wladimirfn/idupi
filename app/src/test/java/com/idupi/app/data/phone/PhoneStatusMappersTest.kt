package com.idupi.app.data.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure mappings behind the status snapshot (phone-agent T2): platform codes to
 * the short contract strings, battery math and the local-day boundary.
 */
class PhoneStatusMappersTest {

    @Test
    fun `signal level is clamped to the four-bar range`() {
        assertEquals(0, SignalMapping.clampLevel(-3))
        assertEquals(0, SignalMapping.clampLevel(0))
        assertEquals(3, SignalMapping.clampLevel(3))
        assertEquals(4, SignalMapping.clampLevel(4))
        assertEquals(4, SignalMapping.clampLevel(9))
    }

    @Test
    fun `an unavailable dbm reading is dropped`() {
        assertNull(SignalMapping.validDbm(null))
        assertNull(SignalMapping.validDbm(SignalMapping.UNAVAILABLE_DBM))
        assertEquals(-78, SignalMapping.validDbm(-78))
    }

    @Test
    fun `telephony network types map to short radio names`() {
        assertEquals("LTE", SignalMapping.networkTypeName(13))
        assertEquals("NR", SignalMapping.networkTypeName(20))
        assertEquals("HSPA+", SignalMapping.networkTypeName(15))
        assertNull(SignalMapping.networkTypeName(0))
        assertNull(SignalMapping.networkTypeName(999))
    }

    @Test
    fun `wifi wins over cellular when both transports are present`() {
        assertEquals(
            NetworkMapping.KIND_WIFI,
            NetworkMapping.kind(connected = true, wifi = true, mobile = true)
        )
    }

    @Test
    fun `no active network is none even with stale flags`() {
        assertEquals(
            NetworkMapping.KIND_NONE,
            NetworkMapping.kind(connected = false, wifi = true)
        )
    }

    @Test
    fun `vpn wins over cellular but not over wifi`() {
        assertEquals(
            NetworkMapping.KIND_VPN,
            NetworkMapping.kind(connected = true, mobile = true, vpn = true)
        )
        assertEquals(
            NetworkMapping.KIND_WIFI,
            NetworkMapping.kind(connected = true, wifi = true, vpn = true)
        )
    }

    @Test
    fun `remaining transports map to their own short words`() {
        assertEquals(
            NetworkMapping.KIND_MOBILE,
            NetworkMapping.kind(connected = true, mobile = true)
        )
        assertEquals(
            NetworkMapping.KIND_ETHERNET,
            NetworkMapping.kind(connected = true, ethernet = true)
        )
        assertEquals(
            NetworkMapping.KIND_BLUETOOTH,
            NetworkMapping.kind(connected = true, bluetooth = true)
        )
        assertEquals(
            NetworkMapping.KIND_OTHER,
            NetworkMapping.kind(connected = true)
        )
    }

    @Test
    fun `battery percent uses the reported scale and clamps`() {
        assertEquals(87, BatteryMapping.percent(level = 87, scale = 100))
        assertEquals(50, BatteryMapping.percent(level = 2048, scale = 4096))
        assertEquals(100, BatteryMapping.percent(level = 200, scale = 100))
        assertNull(BatteryMapping.percent(level = -1, scale = 100))
        assertNull(BatteryMapping.percent(level = 10, scale = 0))
    }

    @Test
    fun `temperature is tenths of a degree celsius`() {
        assertEquals(31.2, BatteryMapping.temperatureCelsius(312), 0.0001)
        assertEquals(0.0, BatteryMapping.temperatureCelsius(0), 0.0001)
    }

    @Test
    fun `the usage window starts at local midnight`() {
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        val afternoon = ZonedDateTime.of(2026, 11, 24, 15, 30, 0, 0, zone)
        val midnight = ZonedDateTime.of(2026, 11, 24, 0, 0, 0, 0, zone)

        assertEquals(
            midnight.toInstant().toEpochMilli(),
            UsageWindow.startOfDayMillis(afternoon.toInstant().toEpochMilli(), zone)
        )
    }
}
