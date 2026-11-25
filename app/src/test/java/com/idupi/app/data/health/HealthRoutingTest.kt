package com.idupi.app.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source detection (phone-health T2): the catalog decides which health apps
 * the PC may read by ADB, and Health Connect origins are what prove an app
 * actually writes health data even when the catalog does not know it.
 */
class HealthRoutingTest {

    @Test
    fun `catalog routes the owner's apps the way the server expects`() {
        assertEquals(
            HealthRoute.HEALTH_CONNECT,
            HealthAppCatalog.entryFor("com.sec.android.app.shealth")?.route,
        )
        assertEquals(
            HealthRoute.HEALTH_CONNECT,
            HealthAppCatalog.entryFor("com.mi.health")?.route,
        )
        assertEquals(
            HealthRoute.HEALTH_CONNECT,
            HealthAppCatalog.entryFor("com.xiaomi.wearable")?.route,
        )
        assertEquals(
            HealthRoute.HEALTH_CONNECT,
            HealthAppCatalog.entryFor("com.fitbit.FitbitMobile")?.route,
        )
        assertEquals(
            HealthRoute.HIDDEN_DISPLAY,
            HealthAppCatalog.entryFor("com.huawei.health")?.route,
        )
        assertEquals(
            HealthRoute.HIDDEN_DISPLAY,
            HealthAppCatalog.entryFor("com.hihonor.health")?.route,
        )
        assertNull(HealthAppCatalog.entryFor("com.example.unknown"))
    }

    @Test
    fun `installed catalog apps plus unknown origins become the wire list`() {
        val installed = listOf(
            HealthAppCatalog.entryFor("com.huawei.health")!!,
            HealthAppCatalog.entryFor("com.sec.android.app.shealth")!!,
        )

        val result = HealthSourceDetection.build(
            catalogInstalled = installed,
            originPackages = listOf(
                "com.sec.android.app.shealth",
                "com.some.other.writer",
            ),
            labelFor = { pkg -> if (pkg == "com.some.other.writer") "Other Writer" else null },
        )

        assertEquals(listOf("com.huawei.health", "com.sec.android.app.shealth", "com.some.other.writer"), result.map { it.packageName })
        assertEquals(
            listOf("hidden_display", "health_connect", "health_connect"),
            result.map { it.route },
        )
        assertEquals("Huawei Health", result[0].label)
        assertEquals("Samsung Health", result[1].label)
        assertEquals("Other Writer", result[2].label)
    }

    @Test
    fun `an origin without a label falls back to the package name`() {
        val result = HealthSourceDetection.build(
            catalogInstalled = emptyList(),
            originPackages = listOf("com.unknown.health"),
            labelFor = { null },
        )

        assertEquals("com.unknown.health", result.single().label)
        assertEquals("health_connect", result.single().route)
    }

    @Test
    fun `a catalog route is not downgraded by a Health Connect origin`() {
        val result = HealthSourceDetection.build(
            catalogInstalled = listOf(HealthAppCatalog.entryFor("com.huawei.health")!!),
            originPackages = listOf("com.huawei.health"),
            labelFor = { null },
        )

        assertEquals("hidden_display", result.single().route)
    }

    @Test
    fun `invalid package names are filtered before the server rejects the upload`() {
        val result = HealthSourceDetection.build(
            catalogInstalled = emptyList(),
            originPackages = listOf("bad package", "com.valid.one", "", "con:colon"),
            labelFor = { null },
        )

        assertEquals(listOf("com.valid.one"), result.map { it.packageName })
    }

    @Test
    fun `the list is capped at the server limit`() {
        val origins = (1..60).map { "com.example.app$it" }

        val result = HealthSourceDetection.build(
            catalogInstalled = emptyList(),
            originPackages = origins,
            labelFor = { null },
        )

        assertEquals(40, result.size)
        assertTrue(result.all { it.route == "health_connect" })
    }

    @Test
    fun `labels are clipped to the contract width`() {
        val result = HealthSourceDetection.build(
            catalogInstalled = emptyList(),
            originPackages = listOf("com.long.label"),
            labelFor = { "L".repeat(100) },
        )

        assertEquals(80, result.single().label.length)
    }

    @Test
    fun `route labels are the neutral Spanish the UI shows`() {
        assertEquals("Health Connect", HealthRoute.HEALTH_CONNECT.label)
        assertEquals("Lectura desde el PC", HealthRoute.HIDDEN_DISPLAY.label)
        assertEquals("No compatible", HealthRoute.NOT_SUPPORTED.label)
    }
}
