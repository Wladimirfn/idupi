package com.idupi.app.data.health

import com.idupi.app.domain.model.HealthDaySummary
import com.idupi.app.domain.model.HealthRefreshJob
import com.idupi.app.domain.model.HealthSourcesResponse
import com.idupi.app.domain.model.HealthSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire contract of the health upload and the read models (phone-health T2).
 * The names are read by the PC's strict schema, so a rename here is a 400 on
 * the other machine, not a compile error.
 */
class HealthSerializationTest {

    private val json = Json

    private fun upload() = HealthSamplesUpload(
        tzOffsetMinutes = -180,
        samples = listOf(
            HealthSample(
                date = "2026-11-24",
                source = "health_connect",
                sourceApp = "",
                capturedAt = "2026-11-24T15:00:00Z",
                metrics = HealthMetrics(
                    steps = 8123,
                    sleepMinutes = 280,
                    sleepStages = HealthSleepStages(deep = 60, light = 120, rem = 20, awake = 10),
                    workouts = listOf(
                        HealthWorkout("running", 45, "2026-11-24T12:00:00Z", 380.4),
                    ),
                    weightKg = 79.5,
                    spo2Pct = 97.2,
                    screenTimeMinutes = 120,
                    unlocks = 42,
                    nightScreenMinutes = 10,
                    topApps = listOf(HealthTopApp("WhatsApp", 40)),
                ),
            ),
        ),
        installedApps = listOf(
            HealthInstalledApp("com.sec.android.app.shealth", "Samsung Health", "health_connect"),
        ),
    )

    @Test
    fun `top-level and sample field names match the server contract`() {
        val element = json.encodeToJsonElement(HealthSamplesUpload.serializer(), upload()).jsonObject

        assertEquals(setOf("tzOffsetMinutes", "samples", "installedApps"), element.keys)
        val sample = element["samples"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("date", "source", "sourceApp", "capturedAt", "metrics"), sample.keys)
        assertEquals(
            setOf("packageName", "label", "route"),
            element["installedApps"]!!.jsonArray.single().jsonObject.keys,
        )
    }

    @Test
    fun `only the present metric keys are encoded`() {
        val element = json.encodeToJsonElement(HealthSamplesUpload.serializer(), upload()).jsonObject
        val metrics = element["samples"]!!.jsonArray.single()
            .jsonObject["metrics"]!!.jsonObject

        assertEquals(
            setOf(
                "steps",
                "sleepMinutes",
                "sleepStages",
                "workouts",
                "weightKg",
                "spo2Pct",
                "screenTimeMinutes",
                "unlocks",
                "nightScreenMinutes",
                "topApps",
            ),
            metrics.keys,
        )
        assertEquals(
            setOf("deep", "light", "rem", "awake"),
            metrics["sleepStages"]!!.jsonObject.keys,
        )
        assertEquals(
            setOf("type", "minutes", "start", "kcal"),
            metrics["workouts"]!!.jsonArray.single().jsonObject.keys,
        )
        assertEquals(
            setOf("label", "minutes"),
            metrics["topApps"]!!.jsonArray.single().jsonObject.keys,
        )
    }

    @Test
    fun `an empty metrics bag serializes as an empty object without nulls`() {
        val encoded = json.encodeToString(
            HealthSamplesUpload.serializer(),
            HealthSamplesUpload(
                tzOffsetMinutes = -180,
                samples = listOf(
                    HealthSample(date = "2026-11-24", source = "wellbeing", metrics = HealthMetrics()),
                ),
            ),
        )

        assertFalse(encoded.contains(":null"))
        assertTrue(encoded.contains("\"metrics\":{}"))
        assertFalse(encoded.contains("installedApps"))
    }

    @Test
    fun `the documented server example decodes into the upload model`() {
        val raw = """
            {
              "tzOffsetMinutes": -180,
              "samples": [
                {
                  "date": "2026-11-24",
                  "source": "health_connect",
                  "sourceApp": "",
                  "capturedAt": "2026-11-24T10:00:00.000Z",
                  "metrics": { "steps": 2444, "heartRateBpm": 72, "sleepMinutes": 509 }
                }
              ],
              "installedApps": [
                { "packageName": "com.huawei.health", "label": "Huawei Health", "route": "hidden_display" }
              ]
            }
        """.trimIndent()

        val decoded = json.decodeFromString(HealthSamplesUpload.serializer(), raw)

        assertEquals(-180, decoded.tzOffsetMinutes)
        assertEquals(2444, decoded.samples.single().metrics.steps)
        assertEquals("hidden_display", decoded.installedApps.single().route)
    }

    @Test
    fun `our sources match the server source pattern`() {
        val pattern = Regex("^[a-z][a-z0-9_]{0,39}$")
        // Literals on purpose: this is the wire contract, not an internal id.
        assertTrue(pattern.matches("health_connect"))
        assertTrue(pattern.matches("wellbeing"))
    }

    @Test
    fun `summary, sources and refresh decode from the server shapes`() {
        val summary = json.decodeFromString(
            HealthSummary.serializer(),
            """
            {
              "rangeDays": 7,
              "tzOffsetMinutes": -180,
              "days": [
                {
                  "date": "2026-11-24",
                  "metrics": { "steps": 8123, "sleepMinutes": 280 },
                  "sources": { "steps": "health_connect", "sleepMinutes": "health_connect" },
                  "capturedAt": "2026-11-24T15:00:00.000Z"
                }
              ],
              "latest": {
                "date": "2026-11-24",
                "metrics": { "steps": 8123 },
                "sources": { "steps": "health_connect" },
                "capturedAt": "2026-11-24T15:00:00.000Z"
              }
            }
            """.trimIndent(),
        )
        assertEquals(8123, summary.days.single().metrics.steps)
        assertEquals("health_connect", summary.days.single().sources["steps"])
        assertEquals(8123, summary.latest?.metrics?.steps)

        val sources = Json { ignoreUnknownKeys = true }.decodeFromString(
            HealthSourcesResponse.serializer(),
            """
            {
              "sources": [
                { "id": "health_connect", "label": "Health Connect", "lastCaptureAt": "2026-11-24T15:00:00.000Z", "lastDate": "2026-11-24" }
              ],
              "installedApps": [
                { "packageName": "com.mi.health", "label": "Mi Fitness", "route": "health_connect", "reportedAt": "2026-11-24T15:00:00.000Z" }
              ],
              "tzOffsetMinutes": -180
            }
            """.trimIndent(),
        )
        assertEquals("health_connect", sources.sources.single().id)
        assertEquals("Mi Fitness", sources.installedApps.single().label)

        val job = json.decodeFromString(
            HealthRefreshJob.serializer(),
            """
            {
              "id": "job-1",
              "status": "done",
              "sources": ["huawei_health"],
              "results": [
                { "source": "huawei_health", "ok": true, "date": "2026-11-24", "metrics": 17, "stored": 1, "merged": 0 }
              ]
            }
            """.trimIndent(),
        )
        assertEquals("done", job.status)
        assertEquals(17, job.results.single().metrics)
    }

    @Test
    fun `a day summary without metrics decodes with an empty bag`() {
        val day = json.decodeFromString(
            HealthDaySummary.serializer(),
            """{"date":"2026-11-20","metrics":{},"sources":{},"capturedAt":null}""",
        )

        assertEquals("2026-11-20", day.date)
        assertEquals(HealthMetrics(), day.metrics)
        assertEquals(null, day.metrics.steps)
    }

    @Test
    fun `the ints of a metrics bag keep their JSON number shape`() {
        val element = json.encodeToJsonElement(HealthMetrics.serializer(), HealthMetrics(unlocks = 42))
            .jsonObject

        assertEquals(42, element["unlocks"]!!.jsonPrimitive.int)
    }
}
