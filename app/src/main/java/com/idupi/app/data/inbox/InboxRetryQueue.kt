package com.idupi.app.data.inbox

import com.idupi.app.data.remote.IduPiHttpException
import com.idupi.app.domain.model.InboxMessageRequest
import kotlinx.serialization.Serializable

/**
 * One captured notification the PC has not accepted yet (inbox capture fix T1).
 *
 * The message content lives here on purpose -- and nowhere else -- because a
 * retry has to resend it verbatim. The queue is app-private storage, bounded to
 * [InboxRetryQueue.MAX_ENTRIES], expires after [InboxRetryQueue.TTL_MILLIS] and
 * is dropped entry by entry as soon as the server accepts or permanently
 * rejects it.
 */
@Serializable
data class PendingInboxUpload(
    val app: String,
    val packageName: String,
    val sender: String,
    val conversation: String,
    val text: String,
    val postedAt: String,
    val notificationKey: String,
    val queuedAtMillis: Long,
) {
    /** The wire request rebuilt from the persisted capture. */
    fun toRequest(): InboxMessageRequest = InboxMessageRequest(
        app = app,
        packageName = packageName,
        sender = sender,
        conversation = conversation,
        text = text,
        postedAt = postedAt,
        notificationKey = notificationKey
    )

    companion object {
        fun from(captured: CapturedMessage, queuedAtMillis: Long): PendingInboxUpload =
            PendingInboxUpload(
                app = captured.app,
                packageName = captured.packageName,
                sender = captured.sender,
                conversation = captured.conversation,
                text = captured.text,
                postedAt = captured.postedAt,
                notificationKey = captured.notificationKey,
                queuedAtMillis = queuedAtMillis
            )
    }
}

/**
 * Pure retry-queue rules (inbox capture fix T1): dedupe identity, TTL, bound
 * and drain selection. No IO here so every rule is unit-testable with an
 * injected clock; [InboxRetryRepository] adds storage and a mutex.
 *
 * The queue holds the oldest entries first, which is also the order the drain
 * sends them in: the owner sees a conversation's messages in arrival order
 * once the PC is back.
 */
object InboxRetryQueue {

    /** Hard bound: beyond this the oldest entries are dropped. */
    const val MAX_ENTRIES = 200

    /** After this age an undelivered entry is dropped instead of retried. */
    const val TTL_MILLIS = 24L * 60L * 60L * 1000L

    /** Entries attempted per drain so a long queue cannot stall the tick. */
    const val DRAIN_BATCH = 20

    /** Dedupe identity: the server also dedupes on notificationKey + text. */
    private data class Identity(val notificationKey: String, val text: String)

    private fun PendingInboxUpload.identity() = Identity(notificationKey, text)

    fun isExpired(
        entry: PendingInboxUpload,
        nowMillis: Long,
        ttlMillis: Long = TTL_MILLIS
    ): Boolean = nowMillis - entry.queuedAtMillis > ttlMillis

    /**
     * Drops expired entries, keeps the first occurrence of each
     * notificationKey+text pair and returns the survivors oldest first, capped
     * at [maxEntries] (the oldest beyond the bound are the ones dropped).
     */
    fun normalize(
        entries: List<PendingInboxUpload>,
        nowMillis: Long,
        maxEntries: Int = MAX_ENTRIES,
        ttlMillis: Long = TTL_MILLIS
    ): List<PendingInboxUpload> {
        val live = entries.filterNot { isExpired(it, nowMillis, ttlMillis) }
        val seen = HashSet<Identity>(live.size)
        val deduped = live.filter { seen.add(it.identity()) }
        val ordered = deduped.sortedBy { it.queuedAtMillis }
        return if (ordered.size <= maxEntries) ordered else ordered.takeLast(maxEntries)
    }

    /**
     * Adds a capture unless the exact same notificationKey+text is already
     * queued (an update of the same notification is not a new message). A
     * duplicate keeps the original queue time, so dedupe never extends the TTL
     * indefinitely.
     */
    fun enqueue(
        entries: List<PendingInboxUpload>,
        entry: PendingInboxUpload,
        nowMillis: Long,
        maxEntries: Int = MAX_ENTRIES,
        ttlMillis: Long = TTL_MILLIS
    ): List<PendingInboxUpload> {
        val normalized = normalize(entries, nowMillis, maxEntries, ttlMillis)
        if (normalized.any { it.identity() == entry.identity() }) return normalized
        return normalize(normalized + entry, nowMillis, maxEntries, ttlMillis)
    }

    /** Removes every entry with the same notificationKey+text identity. */
    fun remove(
        entries: List<PendingInboxUpload>,
        target: PendingInboxUpload
    ): List<PendingInboxUpload> = entries.filterNot { it.identity() == target.identity() }

    /**
     * The next entries to send: live ones, oldest first, capped at [limit].
     * Expired entries never reach the network.
     */
    fun selectBatch(
        entries: List<PendingInboxUpload>,
        nowMillis: Long,
        limit: Int = DRAIN_BATCH,
        maxEntries: Int = MAX_ENTRIES,
        ttlMillis: Long = TTL_MILLIS
    ): List<PendingInboxUpload> {
        if (limit <= 0) return emptyList()
        return normalize(entries, nowMillis, maxEntries, ttlMillis).take(limit)
    }
}

/**
 * Upload rejection rules (inbox capture fix T1).
 *
 * Retrying is only useful for transport failures, timeouts and server errors.
 * A 4xx rejection other than 408/429 means the PC understood the request and
 * refused it, so the entry is dropped instead of retried for 24 h.
 */
object InboxUploadRules {

    fun statusCodeOf(error: Throwable?): Int? = (error as? IduPiHttpException)?.statusCode

    fun isPermanentRejection(statusCode: Int?): Boolean =
        statusCode != null && statusCode in 400..499 && statusCode != 408 && statusCode != 429
}
