package com.idupi.app.data.inbox

import com.idupi.app.data.remote.IduPiHttpException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retry-queue rules and persistence (inbox capture fix T1): dedupe, TTL,
 * bound, drain selection and permanent-rejection classification. The clock is
 * injected everywhere so nothing sleeps.
 */
class InboxRetryQueueTest {

    private fun captured(
        key: String = "key-1",
        text: String = "hola",
        app: String = "whatsapp",
        appLabel: String = ""
    ) = CapturedMessage(
        app = app,
        packageName = "com.whatsapp",
        sender = "Ana",
        conversation = "Ana",
        text = text,
        postedAt = "2026-11-24T12:00:00Z",
        notificationKey = key,
        appLabel = appLabel
    )

    private fun pending(key: String = "key-1", text: String = "hola", at: Long = 1_000L) =
        PendingInboxUpload.from(captured(key, text), at)

    // --- Pure rules ---

    @Test
    fun `normalize drops entries older than the ttl`() {
        val now = 2 * InboxRetryQueue.TTL_MILLIS
        val entries = listOf(
            pending("old", at = 0),
            pending("fresh", at = now - 1_000)
        )

        val normalized = InboxRetryQueue.normalize(entries, nowMillis = now)

        assertEquals(listOf("fresh"), normalized.map { it.notificationKey })
    }

    @Test
    fun `normalize drops the oldest entries beyond the bound`() {
        val entries = (1..201).map { index -> pending("k$index", at = index.toLong()) }

        val normalized = InboxRetryQueue.normalize(
            entries,
            nowMillis = 1_000_000,
            maxEntries = 200
        )

        assertEquals(200, normalized.size)
        assertEquals("k2", normalized.first().notificationKey)
        assertEquals("k201", normalized.last().notificationKey)
    }

    @Test
    fun `enqueue dedupes the same notification key and text`() {
        val first = pending(at = 1_000)
        val duplicate = pending(at = 9_000)

        val queued = InboxRetryQueue.enqueue(emptyList(), first, nowMillis = 1_000)
        val afterDuplicate = InboxRetryQueue.enqueue(queued, duplicate, nowMillis = 9_000)

        assertEquals(1, afterDuplicate.size)
        assertEquals(1_000, afterDuplicate.single().queuedAtMillis)
    }

    @Test
    fun `enqueue keeps the same key when the text changed`() {
        val first = pending(text = "hola", at = 1_000)
        val update = pending(text = "chau", at = 2_000)

        val queued = InboxRetryQueue.enqueue(emptyList(), first, nowMillis = 1_000)
        val afterUpdate = InboxRetryQueue.enqueue(queued, update, nowMillis = 2_000)

        assertEquals(listOf("hola", "chau"), afterUpdate.map { it.text })
    }

    @Test
    fun `selectBatch is oldest first and limited`() {
        val entries = listOf(
            pending("c", at = 3_000),
            pending("a", at = 1_000),
            pending("b", at = 2_000)
        )

        val selected = InboxRetryQueue.selectBatch(entries, nowMillis = 5_000, limit = 2)

        assertEquals(listOf("a", "b"), selected.map { it.notificationKey })
        assertTrue(InboxRetryQueue.selectBatch(entries, nowMillis = 5_000, limit = 0).isEmpty())
    }

    @Test
    fun `remove drops the matching notification key and text`() {
        val keep = pending("k1", "uno", at = 1_000)
        val drop = pending("k2", "dos", at = 2_000)

        val remaining = InboxRetryQueue.remove(listOf(keep, drop), drop)

        assertEquals(listOf("k1"), remaining.map { it.notificationKey })
    }

    // --- Persistence with an injected clock and an in-memory store ---

    private class InMemoryStore(
        initial: List<PendingInboxUpload> = emptyList()
    ) : InboxRetryStorage {
        var entries: List<PendingInboxUpload> = initial
        var writes = 0

        override suspend fun read(): List<PendingInboxUpload> = entries

        override suspend fun write(entries: List<PendingInboxUpload>) {
            this.entries = entries
            writes++
        }
    }

    @Test
    fun `repository persists queued captures and a new instance reloads them`() = runTest {
        val store = InMemoryStore()
        var now = 10_000L
        val repository = InboxRetryRepository(store, clock = { now })

        repository.enqueue(captured("k1", "uno"))
        now += 5_000
        repository.enqueue(captured("k2", "dos"))

        assertEquals(2, repository.size())
        assertEquals(2, store.entries.size)

        val reloaded = InboxRetryRepository(store, clock = { now })
        assertEquals(listOf("k1", "k2"), reloaded.entries().map { it.notificationKey })
    }

    @Test
    fun `a retried capture keeps its package label on the wire`() = runTest {
        val store = InMemoryStore()
        val repository = InboxRetryRepository(store, clock = { 10_000L })

        repository.enqueue(
            captured("tg", "hola", app = "org.telegram.messenger", appLabel = "Telegram")
        )

        val request = repository.entries().single().toRequest()
        assertEquals("org.telegram.messenger", request.app)
        assertEquals("Telegram", request.appLabel)
    }

    @Test
    fun `repository drops expired entries on read and persists the prune`() = runTest {
        val store = InMemoryStore(listOf(pending("old", at = 0)))
        var now = InboxRetryQueue.TTL_MILLIS + 1
        val repository = InboxRetryRepository(store, clock = { now })

        assertEquals(0, repository.size())
        assertTrue(store.entries.isEmpty())

        now += 1
        assertEquals(0, InboxRetryRepository(store, clock = { now }).size())
    }

    @Test
    fun `repository remove drops an accepted entry`() = runTest {
        val store = InMemoryStore()
        var now = 10_000L
        val repository = InboxRetryRepository(store, clock = { now })
        repository.enqueue(captured("k1", "uno"))

        val entry = repository.entries().single()
        now += 1_000
        repository.remove(entry)

        assertEquals(0, repository.size())
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `repository binds the queue at 200 entries`() = runTest {
        val store = InMemoryStore()
        var now = 1_000L
        val repository = InboxRetryRepository(store, clock = { now })

        repeat(201) { index ->
            now += 1
            repository.enqueue(captured("k$index", "texto $index"))
        }

        assertEquals(200, repository.size())
        assertFalse(repository.entries().any { it.notificationKey == "k0" })
    }

    // --- Upload rejection classification ---

    @Test
    fun `permanent 4xx rejections are not retried`() {
        assertTrue(InboxUploadRules.isPermanentRejection(400))
        assertTrue(InboxUploadRules.isPermanentRejection(404))
        assertTrue(InboxUploadRules.isPermanentRejection(409))
        assertTrue(InboxUploadRules.isPermanentRejection(422))
    }

    @Test
    fun `timeouts rate limits and server errors stay retryable`() {
        assertFalse(InboxUploadRules.isPermanentRejection(408))
        assertFalse(InboxUploadRules.isPermanentRejection(429))
        assertFalse(InboxUploadRules.isPermanentRejection(500))
        assertFalse(InboxUploadRules.isPermanentRejection(503))
        assertFalse(InboxUploadRules.isPermanentRejection(null))
    }

    @Test
    fun `status code comes from the http exception only`() {
        assertEquals(
            409,
            InboxUploadRules.statusCodeOf(IduPiHttpException(409, "http://pc/api", "conflict"))
        )
        assertEquals(null, InboxUploadRules.statusCodeOf(RuntimeException("offline")))
        assertEquals(null, InboxUploadRules.statusCodeOf(null))
    }

    // --- Silence but read (inbox-management T4) ----------------------------

    @Test
    fun `a digestOnly capture keeps the flag across the queue and the request`() {
        val silenced = captured("key-silenced").copy(digestOnly = true)

        val entry = PendingInboxUpload.from(silenced, queuedAtMillis = 1_000L)

        assertTrue(entry.digestOnly)
        assertTrue(entry.toRequest().digestOnly)
    }

    @Test
    fun `a normal capture stays a per-message upload`() {
        val entry = PendingInboxUpload.from(captured("key-normal"), queuedAtMillis = 1_000L)

        assertFalse(entry.digestOnly)
        assertFalse(entry.toRequest().digestOnly)
    }
}
