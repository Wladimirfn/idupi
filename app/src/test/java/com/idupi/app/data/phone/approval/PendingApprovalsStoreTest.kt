package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Process-wide pending list (phone-approvals T4): the poller replaces it whole,
 * a decision removes one id, and only pending approvals are kept.
 */
class PendingApprovalsStoreTest {

    private fun approval(
        id: String,
        createdAt: String = "2026-11-26T12:00:00Z",
        state: String = PhoneApprovalState.PENDING,
    ) = PhoneApproval(id = id, state = state, createdAt = createdAt)

    @Test
    fun `replace keeps only pending approvals, newest first`() {
        val store = PendingApprovalsStore()

        store.replace(
            listOf(
                approval("apr-old", createdAt = "2026-11-26T11:00:00Z"),
                approval("apr-new", createdAt = "2026-11-26T13:00:00Z"),
                approval("apr-mid", createdAt = "2026-11-26T12:00:00Z"),
                approval("apr-approved", state = PhoneApprovalState.APPROVED),
            )
        )

        assertEquals(
            listOf("apr-new", "apr-mid", "apr-old"),
            store.pending.value.map { it.id },
        )
    }

    @Test
    fun `replace drops approvals that are no longer pending`() {
        val store = PendingApprovalsStore()
        store.replace(listOf(approval("apr-a"), approval("apr-b")))

        store.replace(listOf(approval("apr-a")))

        assertEquals(listOf("apr-a"), store.pending.value.map { it.id })
    }

    @Test
    fun `remove drops one id and keeps the order`() {
        val store = PendingApprovalsStore()
        store.replace(
            listOf(
                approval("apr-a", createdAt = "2026-11-26T13:00:00Z"),
                approval("apr-b", createdAt = "2026-11-26T12:00:00Z"),
                approval("apr-c", createdAt = "2026-11-26T11:00:00Z"),
            )
        )

        store.remove("apr-b")

        assertEquals(listOf("apr-a", "apr-c"), store.pending.value.map { it.id })
    }

    @Test
    fun `removing an unknown or blank id changes nothing`() {
        val store = PendingApprovalsStore()
        store.replace(listOf(approval("apr-a")))

        store.remove("apr-missing")
        store.remove("")

        assertEquals(listOf("apr-a"), store.pending.value.map { it.id })
    }

    @Test
    fun `duplicate ids collapse to one entry`() {
        val store = PendingApprovalsStore()

        store.replace(
            listOf(
                approval("apr-a", createdAt = "2026-11-26T13:00:00Z"),
                approval("apr-a", createdAt = "2026-11-26T13:00:00Z"),
            )
        )

        assertEquals(1, store.pending.value.size)
    }

    @Test
    fun `blank ids are never listed`() {
        val store = PendingApprovalsStore()

        store.replace(listOf(approval(""), approval("apr-a")))

        assertEquals(listOf("apr-a"), store.pending.value.map { it.id })
    }

    @Test
    fun `an empty poll empties the list`() {
        val store = PendingApprovalsStore()
        store.replace(listOf(approval("apr-a")))

        store.replace(emptyList())

        assertTrue(store.pending.value.isEmpty())
    }
}
