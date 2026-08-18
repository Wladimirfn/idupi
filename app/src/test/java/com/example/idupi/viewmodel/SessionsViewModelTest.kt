package com.example.idupi.viewmodel

import com.example.idupi.FakeClientSource
import com.example.idupi.FakeIduPiClient
import com.example.idupi.MainDispatcherRule
import com.example.idupi.SessionRequest
import com.example.idupi.domain.model.SessionCounts
import com.example.idupi.domain.model.SessionCountsResponse
import com.example.idupi.domain.model.SessionEngineFailure
import com.example.idupi.domain.model.SessionItem
import com.example.idupi.domain.model.SessionsPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SessionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    private fun sampleSession(id: String = "s1", favorite: Boolean = false) = SessionItem(
        id = id,
        title = "Session $id",
        project = "proj",
        date = "today",
        messageCount = null,
        preview = "preview text",
        isFavorite = favorite
    )

    private fun samplePage(vararg sessions: SessionItem, nextCursor: String? = null) = SessionsPage(
        sessions = sessions.toList(),
        nextCursor = nextCursor,
        partial = false,
        failures = emptyList()
    )

    @Test
    fun `init loads sessions from client`() = runTest {
        fake.sessionsToReturn = samplePage(sampleSession())

        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(fake.sessionsToReturn.sessions, viewModel.sessions.value)
        assertEquals(false, viewModel.isLoading.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `refreshSessions sets errorMessage when client throws`() = runTest {
        fake.failWith = RuntimeException("sessions unavailable")

        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("sessions unavailable"))
    }

    @Test
    fun `toggleFavorite flips isFavorite for the matching session only`() = runTest {
        fake.sessionsToReturn = samplePage(sampleSession(id = "s1"), sampleSession(id = "s2"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        viewModel.toggleFavorite("s1")

        val updated = viewModel.sessions.value.associateBy { it.id }
        assertEquals(true, updated.getValue("s1").isFavorite)
        assertEquals(false, updated.getValue("s2").isFavorite)
    }

    @Test
    fun `clearError resets errorMessage to null`() = runTest {
        fake.failWith = RuntimeException("boom")
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertNotNull(viewModel.errorMessage.value)

        viewModel.clearError()

        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `init loads counts from client`() = runTest {
        fake.countsToReturn = SessionCountsResponse(
            counts = SessionCounts(piCli = 5, opencode = 10, claude = 3, all = 18)
        )

        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(fake.countsToReturn.counts, viewModel.counts.value)
    }

    @Test
    fun `counts partial flag reflects partial counts response`() = runTest {
        fake.countsToReturn = SessionCountsResponse(
            counts = SessionCounts(piCli = 5, opencode = 10, claude = 3, all = 18),
            partial = true
        )

        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(true, viewModel.countsPartial.value)
        assertEquals(fake.countsToReturn.counts, viewModel.counts.value)
    }

    @Test
    fun `counts partial flag is false for a complete counts response`() = runTest {
        fake.countsToReturn = SessionCountsResponse(
            counts = SessionCounts(piCli = 5, opencode = 10, claude = 3, all = 18),
            partial = false
        )

        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(false, viewModel.countsPartial.value)
    }

    @Test
    fun `partial counts with an omitted engine key surfaces partial and keeps remaining counts`() = runTest {
        fake.countsToReturn = SessionCountsResponse(
            counts = SessionCounts(piCli = 5, opencode = null, claude = 3, all = 8),
            partial = true,
            failures = listOf(SessionEngineFailure("opencode", "scan failed"))
        )

        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(true, viewModel.countsPartial.value)
        assertEquals(5, viewModel.counts.value?.piCli)
        assertEquals(null, viewModel.counts.value?.opencode)
        assertEquals(8, viewModel.counts.value?.all)
    }

    @Test
    fun `init issues exactly one sessions request for all engine with default page size`() = runTest {
        fake.sessionsToReturn = samplePage(sampleSession(id = "s1"))

        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(listOf(SessionRequest("all", null, 30)), fake.sessionRequestHistory)
        assertEquals(listOf("s1"), viewModel.sessions.value.map { it.id })
    }

    @Test
    fun `selectEngine requests the engine and replaces the list`() = runTest {
        fake.sessionsToReturn = samplePage(sampleSession(id = "s1"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.sessionsToReturn = samplePage(sampleSession(id = "s2"), sampleSession(id = "s3"))
        viewModel.selectEngine("opencode")
        advanceUntilIdle()

        assertEquals(SessionRequest("opencode", null, 30), fake.sessionRequestHistory.last())
        assertEquals(listOf("s2", "s3"), viewModel.sessions.value.map { it.id })
    }

    @Test
    fun `selectEngine sets errorMessage when client throws`() = runTest {
        fake.sessionsToReturn = samplePage(sampleSession(id = "s1"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.failWith = RuntimeException("engine unavailable")
        viewModel.selectEngine("claude")
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("engine unavailable"))
    }

    @Test
    fun `loadMore appends next page for selected engine using returned cursor`() = runTest {
        fake.sessionsQueue.add(samplePage(sampleSession(id = "s1"), nextCursor = "cursor-1"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.sessionsQueue.add(samplePage(sampleSession(id = "s2"), nextCursor = "cursor-2"))
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("s1", "s2"), viewModel.sessions.value.map { it.id })
        assertEquals(SessionRequest("all", "cursor-1", 30), fake.sessionRequestHistory.last())
    }

    @Test
    fun `loadMore does not request when nextCursor is null`() = runTest {
        fake.sessionsToReturn = samplePage(sampleSession(id = "s1"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        val requestsBefore = fake.sessionRequestHistory.size

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(requestsBefore, fake.sessionRequestHistory.size)
        assertEquals(listOf("s1"), viewModel.sessions.value.map { it.id })
    }

    @Test
    fun `refreshSessions replaces the list instead of appending`() = runTest {
        fake.sessionsQueue.add(samplePage(sampleSession(id = "s1"), nextCursor = "cursor-1"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertEquals(listOf("s1"), viewModel.sessions.value.map { it.id })

        fake.sessionsQueue.add(samplePage(sampleSession(id = "s2")))
        viewModel.refreshSessions()
        advanceUntilIdle()

        assertEquals(listOf("s2"), viewModel.sessions.value.map { it.id })
        assertEquals(SessionRequest("all", null, 30), fake.sessionRequestHistory.last())
    }

    @Test
    fun `refreshSessions sets errorMessage when counts request fails`() = runTest {
        fake.failWith = RuntimeException("counts unavailable")

        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("counts unavailable"))
    }

    @Test
    fun `loadMore sets errorMessage when client throws`() = runTest {
        fake.sessionsQueue.add(samplePage(sampleSession(id = "s1"), nextCursor = "cursor-1"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.failWith = RuntimeException("load more failed")
        viewModel.loadMore()
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("load more failed"))
    }

    @Test
    fun `counts failure still loads sessions with exactly one all request`() = runTest {
        fake.failCountsWith = RuntimeException("counts unavailable")
        fake.sessionsToReturn = samplePage(sampleSession(id = "s1"), sampleSession(id = "s2"))

        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(listOf(SessionRequest("all", null, 30)), fake.sessionRequestHistory)
        assertEquals(listOf("s1", "s2"), viewModel.sessions.value.map { it.id })
    }

    @Test
    fun `double immediate loadMore produces one request and one appended row`() = runTest {
        fake.sessionsQueue.add(samplePage(sampleSession(id = "s1"), nextCursor = "cursor-1"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.sessionsQueue.add(samplePage(sampleSession(id = "s2")))
        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("s1", "s2"), viewModel.sessions.value.map { it.id })
        assertEquals(2, fake.sessionRequestHistory.size)
        assertEquals(SessionRequest("all", "cursor-1", 30), fake.sessionRequestHistory.last())
    }

    @Test
    fun `delayed old engine response cannot overwrite newer selected engine response`() = runTest {
        val oldPage = CompletableDeferred<SessionsPage>()
        val newPage = CompletableDeferred<SessionsPage>()

        fake.sessionHandlers.add { _, _, _ -> oldPage.await() }
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        fake.sessionHandlers.add { _, _, _ -> newPage.await() }
        viewModel.selectEngine("opencode")
        advanceUntilIdle()

        newPage.complete(samplePage(sampleSession(id = "new-1")))
        advanceUntilIdle()
        assertEquals(listOf("new-1"), viewModel.sessions.value.map { it.id })

        oldPage.complete(samplePage(sampleSession(id = "old-1")))
        advanceUntilIdle()
        assertEquals(listOf("new-1"), viewModel.sessions.value.map { it.id })
    }

    @Test
    fun `failed selection leaves no old engine rows under the new engine`() = runTest {
        fake.sessionsToReturn = samplePage(sampleSession(id = "old-engine-row"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertEquals(listOf("old-engine-row"), viewModel.sessions.value.map { it.id })

        fake.sessionHandlers.add { _, _, _ -> throw RuntimeException("engine unavailable") }
        viewModel.selectEngine("opencode")
        advanceUntilIdle()

        assertEquals(SessionRequest("opencode", null, 30), fake.sessionRequestHistory.last())
        assertEquals(emptyList<SessionItem>(), viewModel.sessions.value)
        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("engine unavailable"))
    }

    @Test
    fun `stale loadMore with same cursor cannot append or clear loading after refresh replaced the page`() = runTest {
        val firstPage = CompletableDeferred<SessionsPage>()
        val loadMorePage = CompletableDeferred<SessionsPage>()
        val refreshedPage = CompletableDeferred<SessionsPage>()

        fake.sessionHandlers.add { _, _, _ -> firstPage.await() }
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        firstPage.complete(samplePage(sampleSession(id = "r1"), nextCursor = "cursor-C"))
        advanceUntilIdle()
        assertEquals(listOf("r1"), viewModel.sessions.value.map { it.id })
        assertEquals(false, viewModel.isLoading.value)

        fake.sessionHandlers.add { _, _, _ -> loadMorePage.await() }
        viewModel.loadMore()
        advanceUntilIdle()
        assertTrue(viewModel.isLoading.value)

        fake.sessionHandlers.add { _, _, _ -> refreshedPage.await() }
        viewModel.refreshSessions()
        advanceUntilIdle()
        assertTrue(viewModel.isLoading.value)

        refreshedPage.complete(samplePage(sampleSession(id = "r2"), nextCursor = "cursor-C"))
        advanceUntilIdle()
        assertEquals(listOf("r2"), viewModel.sessions.value.map { it.id })
        assertEquals(false, viewModel.isLoading.value)

        loadMorePage.complete(samplePage(sampleSession(id = "stale-old")))
        advanceUntilIdle()

        assertEquals(listOf("r2"), viewModel.sessions.value.map { it.id })
        assertEquals(false, viewModel.isLoading.value)
    }

    @Test
    fun `stale loadMore completion cannot clear the guard of a newer loadMore after refresh`() = runTest {
        val firstPage = CompletableDeferred<SessionsPage>()
        val staleLoadMore = CompletableDeferred<SessionsPage>()
        val refreshedPage = CompletableDeferred<SessionsPage>()
        val freshLoadMore = CompletableDeferred<SessionsPage>()

        fake.sessionHandlers.add { _, _, _ -> firstPage.await() }
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        firstPage.complete(samplePage(sampleSession(id = "r1"), nextCursor = "cursor-C"))
        advanceUntilIdle()

        fake.sessionHandlers.add { _, _, _ -> staleLoadMore.await() }
        viewModel.loadMore()
        advanceUntilIdle()

        fake.sessionHandlers.add { _, _, _ -> refreshedPage.await() }
        viewModel.refreshSessions()
        advanceUntilIdle()
        refreshedPage.complete(samplePage(sampleSession(id = "r2"), nextCursor = "cursor-C"))
        advanceUntilIdle()
        assertEquals(listOf("r2"), viewModel.sessions.value.map { it.id })
        assertEquals(false, viewModel.isLoading.value)

        fake.sessionHandlers.add { _, _, _ -> freshLoadMore.await() }
        viewModel.loadMore()
        advanceUntilIdle()
        assertTrue(viewModel.isLoading.value)

        staleLoadMore.complete(samplePage(sampleSession(id = "stale-old")))
        advanceUntilIdle()

        val requestsBefore = fake.sessionRequestHistory.size
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(requestsBefore, fake.sessionRequestHistory.size)

        freshLoadMore.complete(samplePage(sampleSession(id = "r3")))
        advanceUntilIdle()

        assertEquals(listOf("r2", "r3"), viewModel.sessions.value.map { it.id })
        assertEquals(false, viewModel.isLoading.value)
    }

    @Test
    fun `stale first page completion cannot clear loading of a newer request`() = runTest {
        val stalePage = CompletableDeferred<SessionsPage>()
        val freshPage = CompletableDeferred<SessionsPage>()

        fake.sessionHandlers.add { _, _, _ -> stalePage.await() }
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertTrue(viewModel.isLoading.value)

        fake.sessionHandlers.add { _, _, _ -> freshPage.await() }
        viewModel.refreshSessions()
        advanceUntilIdle()
        assertTrue(viewModel.isLoading.value)

        stalePage.complete(samplePage(sampleSession(id = "stale")))
        advanceUntilIdle()
        assertTrue(viewModel.isLoading.value)
        assertEquals(emptyList<SessionItem>(), viewModel.sessions.value)

        freshPage.complete(samplePage(sampleSession(id = "fresh")))
        advanceUntilIdle()
        assertEquals(false, viewModel.isLoading.value)
        assertEquals(listOf("fresh"), viewModel.sessions.value.map { it.id })
    }

    // PR6 correction 1: refresh must synchronously flip to first-page loading and
    // invalidate the cursor BEFORE launching, so an immediate loadMore refuses
    // instead of firing a stale-cursor request.
    @Test
    fun `refresh sets loading synchronously, invalidates the cursor, and an immediate loadMore issues no stale-cursor request`() = runTest {
        fake.sessionsQueue.add(samplePage(sampleSession(id = "s1"), nextCursor = "cursor-1"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertEquals(listOf("s1"), viewModel.sessions.value.map { it.id })

        fake.sessionsQueue.add(samplePage(sampleSession(id = "s2")))
        viewModel.refreshSessions()

        // Loading state flips synchronously, before any launched coroutine runs.
        assertEquals(true, viewModel.isLoading.value)

        val requestsBefore = fake.sessionRequestHistory.size
        viewModel.loadMore()
        advanceUntilIdle()

        // Exactly one new request happens: the refresh. Never a stale-cursor loadMore.
        assertEquals(requestsBefore + 1, fake.sessionRequestHistory.size)
        assertEquals(SessionRequest("all", null, 30), fake.sessionRequestHistory.last())
        assertEquals(listOf("s2"), viewModel.sessions.value.map { it.id })
    }

    // PR6 correction 2: selectedEngine is ViewModel state, readable and updated synchronously.
    @Test
    fun `init exposes selectedEngine as all and selectEngine updates it synchronously`() = runTest {
        fake.sessionsToReturn = samplePage(sampleSession(id = "s1"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals("all", viewModel.selectedEngine.value)

        viewModel.selectEngine("opencode")

        // Synchronous update: no advanceUntilIdle needed before reading.
        assertEquals("opencode", viewModel.selectedEngine.value)
        advanceUntilIdle()
        assertEquals("opencode", viewModel.selectedEngine.value)
        assertEquals(listOf("s1"), viewModel.sessions.value.map { it.id })
    }

    // PR6 correction 3: canLoadMore tracks cursor state so the empty state can offer an explicit retry.
    @Test
    fun `canLoadMore is false when the first page has no next cursor`() = runTest {
        fake.sessionsToReturn = samplePage(sampleSession(id = "s1"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(false, viewModel.canLoadMore.value)
    }

    @Test
    fun `canLoadMore is true after a page that returns a next cursor`() = runTest {
        fake.sessionsToReturn = samplePage(sampleSession(id = "s1"), nextCursor = "cursor-1")
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(true, viewModel.canLoadMore.value)
    }

    @Test
    fun `empty page with a non-null cursor still allows loading more`() = runTest {
        fake.sessionsToReturn = samplePage(nextCursor = "cursor-1")
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()

        assertEquals(emptyList<SessionItem>(), viewModel.sessions.value)
        assertEquals(true, viewModel.canLoadMore.value)
    }

    @Test
    fun `refresh resets canLoadMore when it invalidates the cursor`() = runTest {
        fake.sessionsQueue.add(samplePage(sampleSession(id = "s1"), nextCursor = "cursor-1"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertEquals(true, viewModel.canLoadMore.value)

        fake.sessionsQueue.add(samplePage(sampleSession(id = "s2")))
        viewModel.refreshSessions()
        advanceUntilIdle()

        assertEquals(listOf("s2"), viewModel.sessions.value.map { it.id })
        assertEquals(false, viewModel.canLoadMore.value)
    }

    @Test
    fun `loadMore updates canLoadMore from the returned page cursor`() = runTest {
        fake.sessionsQueue.add(samplePage(sampleSession(id = "s1"), nextCursor = "cursor-1"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertEquals(true, viewModel.canLoadMore.value)

        fake.sessionsQueue.add(samplePage(sampleSession(id = "s2")))
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("s1", "s2"), viewModel.sessions.value.map { it.id })
        assertEquals(false, viewModel.canLoadMore.value)
    }

    // PR6 correction 4: counts failure for the current request clears stale badges;
    // the listing still proceeds.
    @Test
    fun `counts failure for the current request clears stale counts and keeps listing`() = runTest {
        fake.countsToReturn = SessionCountsResponse(
            counts = SessionCounts(piCli = 5, opencode = 10, claude = 3, all = 18)
        )
        fake.sessionsToReturn = samplePage(sampleSession(id = "s1"))
        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle()
        assertEquals(fake.countsToReturn.counts, viewModel.counts.value)
        assertEquals(false, viewModel.countsPartial.value)

        fake.failCountsWith = RuntimeException("counts unavailable")
        fake.sessionsToReturn = samplePage(sampleSession(id = "s2"))
        viewModel.refreshSessions()
        advanceUntilIdle()

        assertNull(viewModel.counts.value)
        assertEquals(true, viewModel.countsPartial.value)
        // Listing still proceeds for the current refresh.
        assertEquals(listOf("s2"), viewModel.sessions.value.map { it.id })
        assertEquals(SessionRequest("all", null, 30), fake.sessionRequestHistory.last())
    }

    // PR6 correction blocker 1: a stale counts success must NOT resurrect badges
    // after a newer counts failure already cleared them. loadCounts must guard the
    // success commit with requestId == firstPageRequestId.
    @Test
    fun `stale counts success cannot resurrect badges after a newer counts failure cleared them`() = runTest {
        val releaseStaleCounts = CompletableDeferred<SessionCountsResponse>()
        fake.countsHandlers.addLast { releaseStaleCounts.await() }
        fake.countsHandlers.addLast { throw RuntimeException("counts down") }

        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle() // requestId=1 parked on the deferred counts response

        viewModel.refreshSessions() // requestId=2 supersedes; its counts fail
        advanceUntilIdle()

        // The newer request cleared the badges to the honest null/unknown state.
        assertNull(viewModel.counts.value)
        assertEquals(true, viewModel.countsPartial.value)

        // The OLD request finally returns success — it must NOT overwrite the newer state.
        releaseStaleCounts.complete(
            SessionCountsResponse(SessionCounts(piCli = 10, opencode = 50, claude = 40, all = 100))
        )
        advanceUntilIdle()

        assertNull(viewModel.counts.value)
        assertEquals(true, viewModel.countsPartial.value)
    }

    // PR6 correction blocker 2: an obsolete refresh whose counts were superseded by a
    // selectEngine must issue NO sessions request. refreshSessions must capture the
    // engine synchronously and re-check requestId == firstPageRequestId before fetchFirstPage.
    @Test
    fun `obsolete refresh after selectEngine issues no sessions request`() = runTest {
        val releaseCounts = CompletableDeferred<SessionCountsResponse>()
        fake.countsHandlers.addLast { releaseCounts.await() }
        val selectEnginePage = CompletableDeferred<SessionsPage>()
        fake.sessionHandlers.add { _, _, _ -> selectEnginePage.await() }

        val viewModel = SessionsViewModel(FakeClientSource(fake))
        advanceUntilIdle() // requestId=1 parked on deferred counts

        viewModel.selectEngine("opencode") // requestId=2 supersedes; its own fetch is allowed
        advanceUntilIdle()

        // Exactly one sessions request so far: the selectEngine listing.
        assertEquals(listOf(SessionRequest("opencode", null, 30)), fake.sessionRequestHistory)

        // The obsolete request's counts finally complete.
        releaseCounts.complete(SessionCountsResponse(SessionCounts(piCli = 1, opencode = 2, claude = 3, all = 6)))
        advanceUntilIdle()

        // The obsolete requestId=1 must NOT have issued its own sessions request.
        assertEquals(listOf(SessionRequest("opencode", null, 30)), fake.sessionRequestHistory)

        // The current (requestId=2) listing still wins when it finishes.
        selectEnginePage.complete(samplePage(sampleSession(id = "new-1")))
        advanceUntilIdle()
        assertEquals(listOf("new-1"), viewModel.sessions.value.map { it.id })
    }
}
