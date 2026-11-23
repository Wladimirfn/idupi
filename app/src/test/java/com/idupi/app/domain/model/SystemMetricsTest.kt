package com.idupi.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape tests for `GET /api/v1/system/metrics`.
 *
 * The JSON below mirrors the server payload byte-for-byte in shape (see
 * `idupi-server/lib/system-metrics.mjs`): null sensors are normal, and the
 * decoder must never turn them into 0.
 */
class SystemMetricsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `decodes a full payload with every sensor present`() {
        val payload = """
            {
              "sampledAt": "2026-11-23T12:00:00.000Z",
              "cpu": {
                "name": "AMD Ryzen 5 5600X 6-Core Processor",
                "usagePercent": 12.5,
                "temperatureC": 45.3,
                "powerW": 65.2,
                "cores": 12
              },
              "memory": {
                "usedBytes": 12832247808,
                "totalBytes": 17096540160,
                "usagePercent": 75.1,
                "temperatureC": 38.0
              },
              "gpus": [
                {
                  "name": "NVIDIA GeForce RTX 3060",
                  "usagePercent": 4,
                  "temperatureC": 39.97,
                  "powerW": 13.36,
                  "memoryUsedBytes": 1055916032,
                  "memoryTotalBytes": 12884901888
                }
              ],
              "sources": { "gpu": "nvidia-smi", "sensors": "librehardwaremonitor" },
              "hints": []
            }
        """.trimIndent()

        val metrics = json.decodeFromString<SystemMetrics>(payload)

        assertEquals("2026-11-23T12:00:00.000Z", metrics.sampledAt)
        assertEquals("AMD Ryzen 5 5600X 6-Core Processor", metrics.cpu.name)
        assertEquals(12.5, metrics.cpu.usagePercent!!, 0.0001)
        assertEquals(45.3, metrics.cpu.temperatureC!!, 0.0001)
        assertEquals(65.2, metrics.cpu.powerW!!, 0.0001)
        assertEquals(12, metrics.cpu.cores)
        assertEquals(12832247808L, metrics.memory.usedBytes)
        assertEquals(75.1, metrics.memory.usagePercent!!, 0.0001)
        assertEquals(1, metrics.gpus.size)
        assertEquals("NVIDIA GeForce RTX 3060", metrics.gpus[0].name)
        assertEquals(12884901888L, metrics.gpus[0].memoryTotalBytes)
        assertEquals("nvidia-smi", metrics.sources.gpu)
        assertEquals("librehardwaremonitor", metrics.sources.sensors)
        assertTrue(metrics.hints.isEmpty())
    }

    @Test
    fun `explicit null sensors stay null instead of becoming zero`() {
        val payload = """
            {
              "sampledAt": "2026-11-23T19:36:13.613Z",
              "cpu": {
                "name": "AMD Ryzen 5 5600X 6-Core Processor",
                "usagePercent": 5.9,
                "temperatureC": null,
                "powerW": null,
                "cores": 12
              },
              "memory": {
                "usedBytes": 12832247808,
                "totalBytes": 17096540160,
                "usagePercent": 75.1,
                "temperatureC": null
              },
              "gpus": [
                {
                  "name": "NVIDIA GeForce RTX 3060",
                  "usagePercent": 0,
                  "temperatureC": 39.69,
                  "powerW": 13.02,
                  "memoryUsedBytes": 1055916032,
                  "memoryTotalBytes": 12884901888
                }
              ],
              "sources": { "gpu": "nvidia-smi", "sensors": null },
              "hints": [
                "Para temperatura y consumo del CPU, abrí LibreHardwareMonitor como administrador"
              ]
            }
        """.trimIndent()

        val metrics = json.decodeFromString<SystemMetrics>(payload)

        assertNull(metrics.cpu.temperatureC)
        assertNull(metrics.cpu.powerW)
        assertNull(metrics.memory.temperatureC)
        assertNull(metrics.sources.sensors)
        assertEquals(1, metrics.hints.size)
        // A GPU at 0% is a real reading, not a missing one.
        assertEquals(0.0, metrics.gpus[0].usagePercent!!, 0.0001)
    }

    @Test
    fun `missing sections and unknown keys fall back to defaults`() {
        val metrics = json.decodeFromString<SystemMetrics>(
            """{ "cpu": { "name": "x" }, "futureField": true }"""
        )

        assertNull(metrics.cpu.usagePercent)
        assertNull(metrics.memory.totalBytes)
        assertTrue(metrics.gpus.isEmpty())
        assertNull(metrics.sources.gpu)
        assertTrue(metrics.hints.isEmpty())
        assertEquals("", metrics.sampledAt)
    }

    @Test
    fun `decodes the disks array with sizes, labels and file systems`() {
        val payload = """
            {
              "sampledAt": "2026-11-23T12:00:00.000Z",
              "cpu": { "name": "AMD Ryzen 5 5600X" },
              "disks": [
                {
                  "mount": "C:",
                  "label": "Windows",
                  "fileSystem": "NTFS",
                  "totalBytes": 511000000000,
                  "freeBytes": 123000000000,
                  "usedBytes": 388000000000,
                  "usagePercent": 75.9
                },
                {
                  "mount": "D:",
                  "label": null,
                  "fileSystem": null,
                  "totalBytes": 1000000000000,
                  "freeBytes": 900000000000,
                  "usedBytes": 100000000000,
                  "usagePercent": 10.0
                }
              ]
            }
        """.trimIndent()

        val metrics = json.decodeFromString<SystemMetrics>(payload)

        assertEquals(2, metrics.disks.size)
        val system = metrics.disks[0]
        assertEquals("C:", system.mount)
        assertEquals("Windows", system.label)
        assertEquals("NTFS", system.fileSystem)
        assertEquals(511000000000L, system.totalBytes)
        assertEquals(388000000000L, system.usedBytes)
        assertEquals(75.9, system.usagePercent!!, 0.0001)
        assertNull(metrics.disks[1].label)
        assertNull(metrics.disks[1].fileSystem)
    }

    @Test
    fun `a missing disks section decodes to an empty list`() {
        val metrics = json.decodeFromString<SystemMetrics>("""{ "cpu": { "name": "x" } }""")

        assertTrue(metrics.disks.isEmpty())
    }

    @Test
    fun `disk usage level follows the 75 and 90 percent thresholds`() {
        assertEquals(DiskUsageLevel.OK, diskUsageLevel(0.0))
        assertEquals(DiskUsageLevel.OK, diskUsageLevel(74.9))
        assertEquals(DiskUsageLevel.WARN, diskUsageLevel(75.0))
        assertEquals(DiskUsageLevel.WARN, diskUsageLevel(89.9))
        assertEquals(DiskUsageLevel.CRITICAL, diskUsageLevel(90.0))
        assertEquals(DiskUsageLevel.CRITICAL, diskUsageLevel(100.0))
        assertEquals(DiskUsageLevel.UNKNOWN, diskUsageLevel(null))
    }

    @Test
    fun `summary sums known watts and flags the missing CPU sensor`() {
        val gpuOnly = SystemMetrics(
            gpus = listOf(GpuMetrics(powerW = 13.02), GpuMetrics(powerW = 8.0))
        )
        val summary = gpuOnly.knownPowerSummary()!!

        assertEquals(21.02, summary.totalW, 0.0001)
        assertTrue(summary.missingCpu)
        assertFalse(summary.missingGpu)
        assertTrue(summary.partial)

        val full = SystemMetrics(
            cpu = CpuMetrics(powerW = 65.2),
            gpus = listOf(GpuMetrics(powerW = 13.36))
        ).knownPowerSummary()!!
        assertEquals(78.56, full.totalW, 0.0001)
        assertFalse(full.partial)

        assertNull(SystemMetrics().knownPowerSummary())
    }

    @Test
    fun `metric formatting is deterministic and null-safe`() {
        assertEquals("12.0 GB", formatMetricGigabytes(12884901888L))
        assertEquals("12.0 GB / 15.9 GB", formatMemoryUsageBytes(12832247808L, 17096540160L))
        assertNull(formatMemoryUsageBytes(null, null))
        assertEquals("63 %", formatMetricPercent(62.5))
        assertEquals("40 °C", formatMetricCelsius(39.97))
        assertEquals("13 W", formatMetricWatts(13.36))
        assertNull(formatMetricPercent(null))
        assertNull(formatMetricCelsius(null))
        assertNull(formatMetricWatts(null))
        assertNull(formatMetricGigabytes(null))
    }
}
