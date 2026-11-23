package com.idupi.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape and formatting tests for `GET /api/v1/os/processes` (T10).
 *
 * The JSON mirrors `idupi-server/lib/os-processes.mjs`: `protected` is a wire
 * name, `cpuPercent` is null until the server has two samples, and a process
 * without a main window has a null title.
 */
class OsProcessTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val payload = """
        {
          "sampledAt": "2026-11-23T18:00:00.000Z",
          "totalMemoryBytes": 17096540160,
          "cpuCount": 12,
          "processes": [
            {
              "pid": 1234,
              "parentPid": 4,
              "name": "notepad",
              "title": "Sin título - Notepad",
              "path": "C:\\Windows\\System32\\notepad.exe",
              "memoryBytes": 10485760,
              "cpuPercent": 0.5,
              "category": "apps",
              "protected": false
            },
            {
              "pid": 4,
              "parentPid": null,
              "name": "System",
              "title": null,
              "path": null,
              "memoryBytes": 0,
              "cpuPercent": null,
              "category": "windows",
              "protected": true
            },
            {
              "pid": 900,
              "parentPid": 1234,
              "name": "explorer",
              "title": null,
              "path": "C:\\Windows\\explorer.exe",
              "memoryBytes": 2147483648,
              "cpuPercent": 12.3,
              "category": "apps",
              "protected": false
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `decodes the process sample with categories and protected flags`() {
        val sample = json.decodeFromString<OsProcessSample>(payload)

        assertEquals(17_096_540_160L, sample.totalMemoryBytes)
        assertEquals(12, sample.cpuCount)
        assertEquals(3, sample.processes.size)

        val notepad = sample.processes[0]
        assertEquals(1234, notepad.pid)
        assertEquals(4, notepad.parentPid)
        assertEquals("notepad", notepad.name)
        assertEquals("Sin título - Notepad", notepad.title)
        assertEquals(10_485_760L, notepad.memoryBytes)
        assertEquals(0.5, notepad.cpuPercent!!, 0.0001)
        assertEquals(OS_CATEGORY_APPS, notepad.category)
        assertFalse(notepad.isProtected)

        val system = sample.processes[1]
        assertNull(system.parentPid)
        assertNull(system.title)
        assertNull(system.cpuPercent)
        assertTrue(system.isProtected)
        assertEquals(OS_CATEGORY_WINDOWS, system.category)

        assertTrue(sample.processes[2].isExplorer)
    }

    @Test
    fun `a missing processes array decodes to an empty sample`() {
        val sample = json.decodeFromString<OsProcessSample>("""{ "sampledAt": "x" }""")

        assertTrue(sample.processes.isEmpty())
        assertEquals(0L, sample.totalMemoryBytes)
        assertEquals(0, sample.cpuCount)
    }

    @Test
    fun `memory formatting switches from whole MB to one GB decimal`() {
        assertEquals("0 MB", formatProcessMemory(0))
        assertEquals("1 MB", formatProcessMemory(1024L * 1024L))
        assertEquals("512 MB", formatProcessMemory(512L * 1024L * 1024L))
        assertEquals("2.0 GB", formatProcessMemory(2L * 1024L * 1024L * 1024L))
        assertEquals("1.5 GB", formatProcessMemory(1536L * 1024L * 1024L))
    }

    @Test
    fun `cpu formatting keeps one decimal and renders null as null`() {
        assertEquals("12.3 %", formatProcessCpu(12.34))
        assertEquals("0.0 %", formatProcessCpu(0.0))
        assertNull(formatProcessCpu(null))
        assertNull(formatProcessCpu(Double.NaN))
    }

    @Test
    fun `explorer detection is case-insensitive and tolerates the exe suffix`() {
        assertTrue(OsProcess(name = "explorer").isExplorer)
        assertTrue(OsProcess(name = "EXPLORER.EXE").isExplorer)
        assertFalse(OsProcess(name = "explorer-helper").isExplorer)
        assertFalse(OsProcess(name = "chrome").isExplorer)
    }
}
