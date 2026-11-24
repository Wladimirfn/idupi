package com.idupi.app.data.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire contract of the status snapshot (phone-agent T2). These names are what
 * the PC CLI reads (`idupi-server/docs/phone.md`, "Status snapshot contract"),
 * so a rename here is a silent break on the other machine.
 */
class PhoneStatusSerializationTest {

    private val json = Json

    private fun fullStatus() = PhoneStatus(
        battery = PhoneBattery(percent = 87, charging = true, temperatureC = 31.2),
        signal = PhoneSignal(level = 4, dbm = -78, operator = "Claro", networkType = "LTE"),
        network = PhoneNetwork(kind = "wifi"),
        memory = PhoneMemory(usedBytes = 4_294_967_296, totalBytes = 8_589_934_592),
        storage = PhoneStorage(usedBytes = 51_539_607_552, totalBytes = 137_438_953_472),
        foregroundApp = PhoneAppRef(label = "WhatsApp", packageName = "com.whatsapp"),
        topAppsToday = listOf(PhoneTopApp("WhatsApp", "com.whatsapp", 42)),
        collectedAt = "2026-11-24T12:00:00Z",
        appVersion = "1.41-phone-agent",
    )

    @Test
    fun `top-level field names match the server contract`() {
        val element = json.encodeToJsonElement(PhoneStatus.serializer(), fullStatus()).jsonObject

        assertEquals(
            setOf(
                "battery",
                "signal",
                "network",
                "memory",
                "storage",
                "foregroundApp",
                "topAppsToday",
                "collectedAt",
                "appVersion",
            ),
            element.keys
        )
    }

    @Test
    fun `nested field names match the server contract`() {
        val element = json.encodeToJsonElement(PhoneStatus.serializer(), fullStatus()).jsonObject

        assertEquals(
            setOf("percent", "charging", "temperatureC"),
            element["battery"]!!.jsonObject.keys
        )
        assertEquals(
            setOf("level", "dbm", "operator", "networkType"),
            element["signal"]!!.jsonObject.keys
        )
        assertEquals(setOf("kind"), element["network"]!!.jsonObject.keys)
        assertEquals(
            setOf("usedBytes", "totalBytes"),
            element["memory"]!!.jsonObject.keys
        )
        assertEquals(
            setOf("usedBytes", "totalBytes"),
            element["storage"]!!.jsonObject.keys
        )
        assertEquals(
            setOf("label", "packageName"),
            element["foregroundApp"]!!.jsonObject.keys
        )
        assertEquals(
            setOf("label", "packageName", "minutes"),
            element["topAppsToday"]!!.jsonArray.single().jsonObject.keys
        )
    }

    @Test
    fun `values keep their wire types`() {
        val element = json.encodeToJsonElement(PhoneStatus.serializer(), fullStatus()).jsonObject
        val battery = element["battery"]!!.jsonObject
        val memory = element["memory"]!!.jsonObject

        assertEquals(87, battery["percent"]!!.jsonPrimitive.int)
        assertEquals(31.2, battery["temperatureC"]!!.jsonPrimitive.double, 0.0001)
        assertTrue(battery["charging"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(8_589_934_592L, memory["totalBytes"]!!.jsonPrimitive.content.toLong())
        assertEquals("2026-11-24T12:00:00Z", element["collectedAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing sections are omitted instead of sent as null`() {
        val encoded = json.encodeToString(
            PhoneStatus.serializer(),
            PhoneStatus(collectedAt = "2026-11-24T12:00:00Z"),
        )

        assertEquals("""{"collectedAt":"2026-11-24T12:00:00Z"}""", encoded)
        assertFalse(encoded.contains("battery"))
        assertFalse(encoded.contains("topAppsToday"))
    }

    @Test
    fun `the documented example decodes into the model`() {
        val raw = """
            {
              "battery": { "percent": 87, "charging": true, "temperatureC": 31.2 },
              "signal": { "level": 4, "dbm": -78, "operator": "Claro", "networkType": "LTE" },
              "network": { "kind": "wifi", "ssid": "Casa", "rssiDbm": -55 },
              "memory": { "usedBytes": 4294967296, "totalBytes": 8589934592 },
              "storage": { "usedBytes": 51539607552, "totalBytes": 137438953472 },
              "foregroundApp": { "label": "WhatsApp", "packageName": "com.whatsapp" },
              "topAppsToday": [
                { "label": "WhatsApp", "packageName": "com.whatsapp", "minutes": 42 }
              ],
              "collectedAt": "2026-11-24T12:00:00Z",
              "appVersion": "1.41-phone-agent"
            }
        """.trimIndent()

        val status = json.decodeFromString(PhoneStatus.serializer(), raw)

        assertEquals(87, status.battery?.percent)
        assertEquals(true, status.battery?.charging)
        assertEquals(31.2, status.battery?.temperatureC!!, 0.0001)
        assertEquals("Claro", status.signal?.operator)
        assertEquals("LTE", status.signal?.networkType)
        assertEquals("wifi", status.network?.kind)
        assertEquals(8_589_934_592L, status.memory?.totalBytes)
        assertEquals("com.whatsapp", status.foregroundApp?.packageName)
        assertEquals(42, status.topAppsToday?.single()?.minutes)
    }

    @Test
    fun `an unknown server field is ignored on decode`() {
        val raw = """{"collectedAt":"2026-11-24T12:00:00Z","extra":"ignored"}"""
        val lenient = Json { ignoreUnknownKeys = true }

        val status = lenient.decodeFromString(PhoneStatus.serializer(), raw)

        assertEquals("2026-11-24T12:00:00Z", status.collectedAt)
    }
}
