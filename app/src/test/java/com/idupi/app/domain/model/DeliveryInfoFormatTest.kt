package com.idupi.app.domain.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveryInfoFormatTest {

    @Test
    fun `size renders in megabytes with one decimal`() {
        assertEquals("0.0 MB", formatDeliverySize(0L))
        assertEquals("1.5 MB", formatDeliverySize((1.5 * 1024 * 1024).toLong()))
        assertEquals("12.0 MB", formatDeliverySize(12L * 1024 * 1024))
    }

    @Test
    fun `relative time uses short Spanish buckets`() {
        val createdAt = "2026-11-23T10:00:00Z"
        val base = Instant.parse(createdAt).toEpochMilli()

        assertEquals("ahora", relativeDeliveryTime(createdAt, base + 30_000L))
        assertEquals("hace 5 min", relativeDeliveryTime(createdAt, base + 5 * 60_000L))
        assertEquals("hace 2 h", relativeDeliveryTime(createdAt, base + 2 * 3_600_000L))
        assertEquals("hace 3 d", relativeDeliveryTime(createdAt, base + 3 * 86_400_000L))
    }

    @Test
    fun `missing or malformed createdAt renders empty`() {
        assertEquals("", relativeDeliveryTime(null))
        assertEquals("", relativeDeliveryTime("not-a-date"))
    }
}
