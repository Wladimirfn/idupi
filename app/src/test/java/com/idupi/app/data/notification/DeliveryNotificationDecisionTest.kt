package com.idupi.app.data.notification

import com.idupi.app.domain.model.DeliveryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryNotificationDecisionTest {

    private fun delivery(id: String, createdAt: String?) = DeliveryInfo(
        id = id,
        name = "$id.apk",
        size = 1024L,
        sha256 = "a".repeat(64),
        sourcePath = "C:\\build\\$id.apk",
        createdAt = createdAt
    )

    @Test
    fun `first poll bootstraps the marker without notifying`() {
        val list = listOf(
            delivery("dlv-bbbbbbbbbbbb", "2026-11-23T10:00:00.000Z"),
            delivery("dlv-aaaaaaaaaaaa", "2026-11-23T09:00:00.000Z")
        )

        val decision = deliveryNotificationDecision(list, emptySet(), newestSeenCreatedAt = null)

        assertTrue(decision.toNotify.isEmpty())
        assertTrue(decision.bootstrapped)
        assertEquals(
            setOf("dlv-aaaaaaaaaaaa", "dlv-bbbbbbbbbbbb"),
            decision.seenIds
        )
        assertEquals("2026-11-23T10:00:00.000Z", decision.newestCreatedAt)
    }

    @Test
    fun `an empty bootstrap is remembered so the first later delivery notifies`() {
        // An empty first poll has no date to persist: the explicit flag is the
        // only thing that tells the next poll the bootstrap already happened.
        val bootstrap = deliveryNotificationDecision(
            deliveries = emptyList(),
            seenIds = emptySet(),
            newestSeenCreatedAt = null
        )

        assertTrue(bootstrap.toNotify.isEmpty())
        assertTrue(bootstrap.bootstrapped)
        assertEquals(null, bootstrap.newestCreatedAt)

        val after = deliveryNotificationDecision(
            deliveries = listOf(delivery("dlv-aaaaaaaaaaaa", "2026-11-23T10:00:00.000Z")),
            seenIds = bootstrap.seenIds,
            newestSeenCreatedAt = bootstrap.newestCreatedAt,
            bootstrapped = bootstrap.bootstrapped
        )

        assertEquals(listOf("dlv-aaaaaaaaaaaa"), after.toNotify.map { it.id })
    }

    @Test
    fun `a non-empty bootstrap does not notify the deliveries already on the PC`() {
        val list = listOf(
            delivery("dlv-bbbbbbbbbbbb", "2026-11-23T10:00:00.000Z"),
            delivery("dlv-aaaaaaaaaaaa", "2026-11-23T09:00:00.000Z")
        )

        val bootstrap = deliveryNotificationDecision(list, emptySet(), newestSeenCreatedAt = null)

        assertTrue(bootstrap.toNotify.isEmpty())
        assertTrue(bootstrap.bootstrapped)
        assertEquals("2026-11-23T10:00:00.000Z", bootstrap.newestCreatedAt)
    }

    @Test
    fun `a persisted marker alone counts as bootstrapped`() {
        // Migration path for installs that only stored the marker.
        val decision = deliveryNotificationDecision(
            deliveries = listOf(delivery("dlv-bbbbbbbbbbbb", "2026-11-23T11:00:00.000Z")),
            seenIds = emptySet(),
            newestSeenCreatedAt = "2026-11-23T10:00:00.000Z",
            bootstrapped = false
        )

        assertEquals(listOf("dlv-bbbbbbbbbbbb"), decision.toNotify.map { it.id })
    }

    @Test
    fun `a delivery newer than the marker notifies`() {
        val list = listOf(
            delivery("dlv-bbbbbbbbbbbb", "2026-11-23T10:00:00.000Z"),
            delivery("dlv-aaaaaaaaaaaa", "2026-11-23T09:00:00.000Z")
        )

        val decision = deliveryNotificationDecision(
            list,
            seenIds = setOf("dlv-aaaaaaaaaaaa"),
            newestSeenCreatedAt = "2026-11-23T09:00:00.000Z"
        )

        assertEquals(listOf("dlv-bbbbbbbbbbbb"), decision.toNotify.map { it.id })
        assertEquals("2026-11-23T10:00:00.000Z", decision.newestCreatedAt)
    }

    @Test
    fun `restart does not replay deliveries already below the marker`() {
        // seenIds lost the old id (trimmed), the marker alone must keep it quiet.
        val decision = deliveryNotificationDecision(
            deliveries = listOf(delivery("dlv-bbbbbbbbbbbb", "2026-11-23T08:00:00.000Z")),
            seenIds = emptySet(),
            newestSeenCreatedAt = "2026-11-23T10:00:00.000Z"
        )

        assertTrue(decision.toNotify.isEmpty())
        assertEquals("2026-11-23T10:00:00.000Z", decision.newestCreatedAt)
    }

    @Test
    fun `a seen id does not notify twice`() {
        val decision = deliveryNotificationDecision(
            deliveries = listOf(delivery("dlv-bbbbbbbbbbbb", "2026-11-23T10:00:00.000Z")),
            seenIds = setOf("dlv-bbbbbbbbbbbb"),
            newestSeenCreatedAt = "2026-11-23T09:00:00.000Z"
        )

        assertTrue(decision.toNotify.isEmpty())
    }

    @Test
    fun `an unseen delivery at the marker instant notifies`() {
        val decision = deliveryNotificationDecision(
            deliveries = listOf(delivery("dlv-bbbbbbbbbbbb", "2026-11-23T10:00:00.000Z")),
            seenIds = emptySet(),
            newestSeenCreatedAt = "2026-11-23T10:00:00.000Z"
        )

        assertEquals(listOf("dlv-bbbbbbbbbbbb"), decision.toNotify.map { it.id })
    }

    @Test
    fun `an older list never moves the marker backwards`() {
        val decision = deliveryNotificationDecision(
            deliveries = listOf(delivery("dlv-bbbbbbbbbbbb", "2026-11-23T08:00:00.000Z")),
            seenIds = emptySet(),
            newestSeenCreatedAt = "2026-11-23T10:00:00.000Z"
        )

        assertEquals("2026-11-23T10:00:00.000Z", decision.newestCreatedAt)
    }

    @Test
    fun `delivery without createdAt still notifies by id`() {
        val decision = deliveryNotificationDecision(
            deliveries = listOf(delivery("dlv-bbbbbbbbbbbb", null)),
            seenIds = emptySet(),
            newestSeenCreatedAt = "2026-11-23T10:00:00.000Z"
        )

        assertEquals(listOf("dlv-bbbbbbbbbbbb"), decision.toNotify.map { it.id })
    }
}
