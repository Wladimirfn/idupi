package com.idupi.app.data.phone.approval

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One approval the owner granted ON THIS PHONE (phone-approvals T2).
 *
 * @property approvalId server approval id (`apr-…`), never the command id: the
 *   server may rebuild the command after a restart, so the approval is the only
 *   stable identity.
 * @property target node text the approval is bound to, when the reason has one.
 * @property expiresAtMillis local deadline, taken from the approval at the
 *   moment the owner approved it.
 */
@Serializable
data class ApprovalGrant(
    val approvalId: String = "",
    val reason: String = "",
    val target: String? = null,
    val expiresAtMillis: Long = 0L,
    val grantedAtMillis: Long = 0L,
)

/**
 * Local approval ledger: the only proof that the owner approved something here.
 *
 * The PC bearer token also reaches the decision route, so a server-side
 * "approved" is not evidence of the owner's tap. The executor consumes a grant
 * from this ledger, exactly once, before acting.
 */
interface ApprovalLedger {

    /** Stores a grant; blank or already-expired grants are ignored. */
    suspend fun record(grant: ApprovalGrant)

    /** Removes and returns the grant, or null when absent or expired. */
    suspend fun consume(approvalId: String, nowMillis: Long): ApprovalGrant?

    suspend fun remove(approvalId: String)

    suspend fun expire(nowMillis: Long)
}

/**
 * JSON-file ledger, bounded to [maxEntries] and pruned on every operation.
 *
 * A missing or corrupt file loads as empty (fail closed): the phone then
 * refuses approved commands instead of running one it cannot justify. Tests
 * pass `file = null` for an in-memory ledger.
 */
class FileApprovalLedger(
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : ApprovalLedger {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val grants = LinkedHashMap<String, ApprovalGrant>()
    private var loaded = false

    override suspend fun record(grant: ApprovalGrant): Unit = mutex.withLock {
        if (grant.approvalId.isBlank()) return@withLock
        loadLocked()
        val now = clock()
        pruneLocked(now)
        if (grant.expiresAtMillis <= now) return@withLock
        grants[grant.approvalId] = grant
        trimLocked()
        persistLocked()
    }

    override suspend fun consume(approvalId: String, nowMillis: Long): ApprovalGrant? =
        mutex.withLock {
            if (approvalId.isBlank()) return@withLock null
            loadLocked()
            pruneLocked(nowMillis)
            val grant = grants.remove(approvalId) ?: return@withLock null
            persistLocked()
            grant
        }

    override suspend fun remove(approvalId: String): Unit = mutex.withLock {
        loadLocked()
        if (grants.remove(approvalId) != null) persistLocked()
    }

    override suspend fun expire(nowMillis: Long): Unit = mutex.withLock {
        loadLocked()
        if (pruneLocked(nowMillis)) persistLocked()
    }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val source = file ?: return
        val raw = runCatching { source.readText() }.getOrNull() ?: return
        if (raw.isBlank()) return
        val document = runCatching {
            json.decodeFromString(ApprovalLedgerFile.serializer(), raw)
        }.getOrNull() ?: return
        document.grants.forEach { grant ->
            if (grant.approvalId.isNotBlank()) grants[grant.approvalId] = grant
        }
        pruneLocked(clock())
        trimLocked()
    }

    private fun pruneLocked(nowMillis: Long): Boolean {
        val before = grants.size
        grants.entries.removeAll { it.value.expiresAtMillis <= nowMillis }
        return grants.size != before
    }

    private fun trimLocked() {
        while (grants.size > maxEntries) {
            val oldest = grants.values.minByOrNull { it.grantedAtMillis }?.approvalId ?: return
            grants.remove(oldest)
        }
    }

    private fun persistLocked() {
        val destination = file ?: return
        val payload = json.encodeToString(
            ApprovalLedgerFile.serializer(),
            ApprovalLedgerFile(grants = grants.values.toList()),
        )
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".tmp")
        temp.writeText(payload)
        if (!temp.renameTo(destination)) {
            // Windows cannot rename over an existing file.
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 100
        const val FILE_NAME = "phone-approval-ledger.json"
    }
}

@Serializable
private data class ApprovalLedgerFile(
    val version: Int = 1,
    val grants: List<ApprovalGrant> = emptyList(),
)
