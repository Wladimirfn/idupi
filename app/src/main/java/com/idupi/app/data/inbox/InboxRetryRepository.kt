package com.idupi.app.data.inbox

import com.idupi.app.IduPiApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Persistence seam for the retry queue; the tests inject an in-memory store. */
interface InboxRetryStorage {
    suspend fun read(): List<PendingInboxUpload>
    suspend fun write(entries: List<PendingInboxUpload>)
}

/**
 * App-private JSON file for the retry queue (inbox capture fix T1).
 *
 * A missing or corrupt file is treated as an empty queue: losing a retry is
 * better than crashing the notification listener, which Android would then
 * disable. The file is replaced atomically through a sibling temp file so a
 * process kill mid-write cannot leave half a JSON document behind.
 */
class FileInboxRetryStorage(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : InboxRetryStorage {

    override suspend fun read(): List<PendingInboxUpload> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString<List<PendingInboxUpload>>(file.readText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never log content; the parse failure class is enough.
            emptyList()
        }
    }

    override suspend fun write(entries: List<PendingInboxUpload>) = withContext(Dispatchers.IO) {
        val parent = file.parentFile
        parent?.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(json.encodeToString(entries))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }
}

/**
 * Persistent bounded retry queue (inbox capture fix T1).
 *
 * [InboxRetryQueue] owns every decision (dedupe, TTL, bound, selection); this
 * class only adds storage, a mutex so the listener's upload/scan/tick
 * coroutines cannot interleave read-modify-write, and the in-memory cache that
 * keeps a drain from re-reading the file per entry.
 *
 * The clock is injected so TTL behaviour is testable without sleeping.
 */
class InboxRetryRepository(
    private val storage: InboxRetryStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = InboxRetryQueue.MAX_ENTRIES,
    private val ttlMillis: Long = InboxRetryQueue.TTL_MILLIS,
) {

    private val mutex = Mutex()

    /** Loaded queue; null until the first read. Guarded by [mutex]. */
    private var cache: List<PendingInboxUpload>? = null

    suspend fun entries(): List<PendingInboxUpload> = mutex.withLock { current() }

    suspend fun size(): Int = mutex.withLock { current().size }

    /** Adds a failed upload; a duplicate notificationKey+text is folded in. */
    suspend fun enqueue(
        captured: CapturedMessage,
        queuedAtMillis: Long = clock()
    ): List<PendingInboxUpload> = mutex.withLock {
        val entry = PendingInboxUpload.from(captured, queuedAtMillis)
        val updated = InboxRetryQueue.enqueue(
            entries = current(),
            entry = entry,
            nowMillis = clock(),
            maxEntries = maxEntries,
            ttlMillis = ttlMillis
        )
        persist(updated)
        updated
    }

    /** Drops one entry after the server accepted or permanently rejected it. */
    suspend fun remove(entry: PendingInboxUpload): List<PendingInboxUpload> = mutex.withLock {
        val updated = InboxRetryQueue.remove(current(), entry)
        persist(updated)
        updated
    }

    /** The next entries to attempt, oldest first. */
    suspend fun selectBatch(
        limit: Int = InboxRetryQueue.DRAIN_BATCH
    ): List<PendingInboxUpload> = mutex.withLock {
        InboxRetryQueue.selectBatch(
            entries = current(),
            nowMillis = clock(),
            limit = limit,
            maxEntries = maxEntries,
            ttlMillis = ttlMillis
        )
    }

    private suspend fun current(): List<PendingInboxUpload> {
        cache?.let { return it }
        val stored = storage.read()
        val normalized = InboxRetryQueue.normalize(stored, clock(), maxEntries, ttlMillis)
        // Persist the prune so expired text does not outlive the TTL on disk.
        if (normalized != stored) storage.write(normalized)
        cache = normalized
        return normalized
    }

    private suspend fun persist(entries: List<PendingInboxUpload>) {
        val normalized = InboxRetryQueue.normalize(entries, clock(), maxEntries, ttlMillis)
        storage.write(normalized)
        cache = normalized
    }
}

/**
 * Process-wide retry queue used by the listener. The file lives under
 * `filesDir`, which is app-private, and is created lazily on first use.
 */
object InboxRetryProvider {

    const val FILE_NAME = "inbox-retry-queue.json"

    private val repository: InboxRetryRepository by lazy {
        InboxRetryRepository(
            FileInboxRetryStorage(File(IduPiApp.instance.filesDir, FILE_NAME))
        )
    }

    fun get(): InboxRetryRepository = repository
}
