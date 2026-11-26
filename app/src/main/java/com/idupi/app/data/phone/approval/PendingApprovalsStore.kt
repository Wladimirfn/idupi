package com.idupi.app.data.phone.approval

import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide pending approval list (phone-approvals T4).
 *
 * [PhoneApprovalReporting] replaces it on every poll, and every deciding
 * surface (notification, Alertas, Ajustes) removes one id through
 * [ApprovalDecisionFlow]. One holder means a decided approval leaves all three
 * surfaces at once instead of each screen keeping its own copy.
 *
 * Not a singleton by construction: [PhoneApprovals] owns the process instance
 * and tests get an isolated one. Reads are a plain [StateFlow]; writes are
 * guarded so two surfaces cannot interleave a replace and a remove.
 */
class PendingApprovalsStore {

    private val lock = Any()

    private val _pending = MutableStateFlow<List<PhoneApproval>>(emptyList())
    val pending: StateFlow<List<PhoneApproval>> = _pending.asStateFlow()

    /**
     * Ids already decided here. A poll that still returns the server's pending
     * copy must not resurrect the row while the decision settles; an id is
     * forgotten as soon as a poll stops listing it, so the set stays bounded.
     */
    private val settledIds = mutableSetOf<String>()

    /**
     * Replaces the whole list with the pending approvals of one poll, newest
     * first. Approvals that are no longer pending, already decided here, have
     * no id, or repeat an earlier id are dropped.
     */
    fun replace(approvals: List<PhoneApproval>) {
        synchronized(lock) {
            val seen = mutableSetOf<String>()
            val incoming = approvals
                .filter { it.state == PhoneApprovalState.PENDING && it.id.isNotBlank() }
                .filter { seen.add(it.id) }
            settledIds.retainAll(seen)
            _pending.value = incoming
                .filterNot { it.id in settledIds }
                .sortedByDescending { it.createdAt }
        }
    }

    /**
     * Drops one approval after a decision. A blank id is a no-op so deciding an
     * approval a poll already dropped never fails; the id stays settled until a
     * poll confirms the server no longer lists it.
     */
    fun remove(id: String) {
        if (id.isBlank()) return
        synchronized(lock) {
            settledIds += id
            _pending.value = _pending.value.filterNot { it.id == id }
        }
    }
}
