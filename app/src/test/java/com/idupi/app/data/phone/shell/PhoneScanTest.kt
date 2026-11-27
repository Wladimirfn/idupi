package com.idupi.app.data.phone.shell

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `shell scan` ranking and report (phone-shell T4b). Pure: the Android
 * collector reads sizes/usage; these rules decide what the owner is offered.
 */
class PhoneScanTest {

    private val now = 10_000_000_000L
    private val day = 24L * 60L * 60L * 1_000L

    private fun app(
        pkg: String,
        cache: Long = 100,
        appBytes: Long = 1_000,
        dataBytes: Long = 2_000,
        lastUsedDaysAgo: Long? = 1,
    ) = ScannedApp(
        packageName = pkg,
        label = pkg.substringAfterLast('.'),
        cacheBytes = cache,
        appBytes = appBytes,
        dataBytes = dataBytes,
        lastUsedMillis = lastUsedDaysAgo?.let { now - it * day } ?: 0L,
    )

    @Test
    fun `unused means thirty days or never used`() {
        val selection = PhoneScanRanking.select(
            listOf(
                app("com.example.never", lastUsedDaysAgo = null),
                app("com.example.old", lastUsedDaysAgo = 30),
                app("com.example.older", lastUsedDaysAgo = 31),
                app("com.example.fresh", lastUsedDaysAgo = 29),
                app("com.example.today", lastUsedDaysAgo = 0),
            ),
            now,
        )

        assertEquals(
            listOf("com.example.never", "com.example.older", "com.example.old"),
            selection.unused.map { it.packageName },
        )
    }

    @Test
    fun `protected and own packages never appear`() {
        val selection = PhoneScanRanking.select(
            listOf(
                app("com.idupi.app", lastUsedDaysAgo = 100),
                app("com.idupi.shell", lastUsedDaysAgo = 100),
                app("com.android.settings", lastUsedDaysAgo = 100),
                app("com.example.app", lastUsedDaysAgo = 100),
            ),
            now,
        )

        assertEquals(listOf("com.example.app"), selection.apps.map { it.packageName })
        assertEquals(listOf("com.example.app"), selection.unused.map { it.packageName })
        assertEquals(listOf("com.example.app"), selection.heavyCache.map { it.packageName })
    }

    @Test
    fun `unused is capped at twenty and ordered oldest first`() {
        val apps = (1..25).map { index ->
            app("com.example.app$index", lastUsedDaysAgo = 30L + index)
        }
        val selection = PhoneScanRanking.select(apps, now)

        assertEquals(PhoneScanRanking.UNUSED_LIMIT, selection.unused.size)
        assertEquals("com.example.app25", selection.unused.first().packageName)
        assertEquals("com.example.app6", selection.unused.last().packageName)
    }

    @Test
    fun `heavy cache is capped at ten and ordered by cache`() {
        val apps = (1..15).map { index ->
            app("com.example.app$index", cache = index * 1_000L, lastUsedDaysAgo = 1)
        }
        val selection = PhoneScanRanking.select(apps, now)

        assertEquals(PhoneScanRanking.HEAVY_CACHE_LIMIT, selection.heavyCache.size)
        assertEquals("com.example.app15", selection.heavyCache.first().packageName)
        assertEquals("com.example.app6", selection.heavyCache.last().packageName)
        assertTrue(selection.heavyCache.all { it.cacheBytes > 0L })
    }

    @Test
    fun `total reclaimable cache sums the eligible apps`() {
        val selection = PhoneScanRanking.select(
            listOf(
                app("com.example.a", cache = 100),
                app("com.example.b", cache = 250),
                app("com.idupi.app", cache = 10_000),
            ),
            now,
        )

        assertEquals(350L, selection.totalCacheBytes)
    }

    @Test
    fun `the report carries a Spanish summary and a parseable json block`() {
        val selection = PhoneScanRanking.select(
            listOf(
                app("com.example.old", cache = 1_000, lastUsedDaysAgo = 40),
                app("com.example.fresh", cache = 2_000, lastUsedDaysAgo = 1),
            ),
            now,
        )
        val report = PhoneScanReport.render(selection)

        assertTrue(report.startsWith("Analicé 2 apps de terceros:"))
        assertTrue(report.contains("caché recuperable"))
        assertTrue(report.contains("1 sin usar hace 30+ días"))
        assertTrue(report.contains("```json"))

        val jsonText = report.substringAfter("```json\n").substringBefore("\n```")
        val root = Json.parseToJsonElement(jsonText).jsonObject
        assertEquals(2, root["appCount"]!!.jsonPrimitive.int)
        assertEquals(3_000L, root["totalCacheBytes"]!!.jsonPrimitive.long)
        assertEquals(1, root["unused"]!!.jsonArray.size)
        assertEquals(2, root["heavyCache"]!!.jsonArray.size)

        val unused = root["unused"]!!.jsonArray.first().jsonObject
        assertEquals("com.example.old", unused["package"]!!.jsonPrimitive.content)
        assertEquals("old", unused["label"]!!.jsonPrimitive.content)
        assertEquals(1_000L, unused["cacheBytes"]!!.jsonPrimitive.long)
        assertEquals(40L * day, now - unused["lastUsed"]!!.jsonPrimitive.long)
    }

    @Test
    fun `the report never exceeds the candidate caps`() {
        val apps = (1..40).map { index ->
            app("com.example.app$index", cache = index.toLong(), lastUsedDaysAgo = 31)
        }
        val selection = PhoneScanRanking.select(apps, now)
        val report = PhoneScanReport.render(selection)
        val jsonText = report.substringAfter("```json\n").substringBefore("\n```")
        val root = Json.parseToJsonElement(jsonText).jsonObject

        assertTrue((root["unused"] as JsonArray).size <= PhoneScanRanking.UNUSED_LIMIT)
        assertTrue((root["heavyCache"] as JsonArray).size <= PhoneScanRanking.HEAVY_CACHE_LIMIT)
    }

    @Test
    fun `an empty scan still renders a valid empty report`() {
        val selection = PhoneScanRanking.select(emptyList(), now)
        val report = PhoneScanReport.render(selection)
        val jsonText = report.substringAfter("```json\n").substringBefore("\n```")
        val root = Json.parseToJsonElement(jsonText).jsonObject

        assertEquals(0, root["appCount"]!!.jsonPrimitive.int)
        assertEquals(0L, root["totalCacheBytes"]!!.jsonPrimitive.long)
        assertFalse(report.contains("sin usar"))
    }

    @Test
    fun `json objects use the contract field names`() {
        val selection = PhoneScanRanking.select(listOf(app("com.example.a")), now)
        val root = Json.parseToJsonElement(PhoneScanReport.json(selection)).jsonObject
        val heavy = (root["heavyCache"] as JsonArray).first().jsonObject
        assertEquals(
            setOf("package", "label", "cacheBytes", "appBytes", "dataBytes", "lastUsed"),
            heavy.keys,
        )
    }
}
