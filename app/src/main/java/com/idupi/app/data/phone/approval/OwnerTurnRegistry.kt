package com.idupi.app.data.phone.approval

/**
 * Bubble turns started ON THIS PHONE that are still queued or running
 * (phone-bubble, T18).
 *
 * The server marks an owner-request approval as approved on its own, so the
 * wire alone cannot prove the owner asked for that step from this device. The
 * bubble controller registers a turn id right after a successful POST and
 * removes it on any terminal status, on close and when a new turn supersedes
 * it. [ApprovalGate] accepts an owner-request approval only while its turn is
 * in this registry; anything else stays `APPROVAL_NOT_GRANTED_ON_PHONE`.
 *
 * Process-wide and thread-safe: the accessibility executor (the gate) and the
 * bubble controller run on different threads inside the same app process.
 */
object OwnerTurnRegistry {

    private val active = mutableSetOf<String>()

    /** Owner-request approvals already turned into a local grant. Bounded. */
    private val issuedApprovals = ArrayDeque<String>()

    /** Remembers [turnId]. Blank ids are ignored. */
    @Synchronized
    fun register(turnId: String) {
        val id = turnId.trim()
        if (id.isEmpty()) return
        active.add(id)
    }

    /** Forgets [turnId]. A no-op when it was never active. */
    @Synchronized
    fun remove(turnId: String) {
        active.remove(turnId.trim())
    }

    /** True while the bubble that started [turnId] still owns it on this phone. */
    @Synchronized
    fun isActive(turnId: String): Boolean {
        val id = turnId.trim()
        return id.isNotEmpty() && id in active
    }

    /**
     * Claims [approvalId] for materialization and returns true exactly once, so
     * a replayed command cannot mint a second grant from the same approval. The
     * gate records a one-use ledger grant right after a successful claim.
     */
    @Synchronized
    fun claimOwnerApproval(approvalId: String): Boolean {
        val id = approvalId.trim()
        if (id.isEmpty()) return false
        if (id in issuedApprovals) return false
        issuedApprovals.addLast(id)
        while (issuedApprovals.size > MAX_ISSUED_APPROVALS) issuedApprovals.removeFirst()
        return true
    }

    /** Drops every id. Only tests need it. */
    @Synchronized
    fun clear() {
        active.clear()
        issuedApprovals.clear()
    }

    private const val MAX_ISSUED_APPROVALS = 100
}
