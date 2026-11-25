package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxItemStatus
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Alertas inbox behavior (phone-inbox T5): load, filters, the state-changing
 * actions and the reply path. Polling itself is not advanced here because the
 * loop is time-driven and belongs to the on-device check.
 */
class AlertsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
    }

    private fun sampleItem(
        id: String = "i1",
        app: String = "whatsapp",
        conversation: String = "Ana",
        state: String = InboxItemState.OPEN,
        importance: String? = "high",
        suggestedReply: String = "Ahí lo veo",
        status: String = InboxItemStatus.READY,
    ) = InboxItem(
        id = id,
        app = app,
        packageName = "com.whatsapp",
        sender = "Ana",
        conversation = conversation,
        text = "Hola",
        receivedAt = "2026-11-24T10:00:00Z",
        notificationKey = "key-$id",
        status = status,
        importance = importance,
        category = "Trabajo",
        summary = "resumen",
        suggestedReply = suggestedReply,
        state = state
    )

    private fun viewModel(
        hasReplyAction: (String) -> Boolean = { true },
        sendReply: (String, String) -> Boolean = { _, _ -> true },
    ) = AlertsViewModel(FakeClientSource(fake), hasReplyAction, sendReply)

    @Test
    fun `init loads the inbox and requests the page size`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(fake.inboxItemsToReturn, viewModel.items.value)
        assertEquals(AlertsViewModel.PAGE_SIZE, fake.lastRequestedInboxLimit)
        assertEquals(false, viewModel.isLoading.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `load failure sets a message and keeps the list empty`() = runTest {
        fake.failWith = RuntimeException("server off")

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("server off"))
        assertTrue(viewModel.items.value.isEmpty())
    }

    @Test
    fun `refresh reloads the list`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val viewModel = viewModel()
        advanceUntilIdle()

        fake.inboxItemsToReturn = listOf(sampleItem(id = "i2"))
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf("i2"), viewModel.items.value.map { it.id })
    }

    @Test
    fun `pending filter shows only open items`() = runTest {
        fake.inboxItemsToReturn = listOf(
            sampleItem(id = "open"),
            sampleItem(id = "handled", state = InboxItemState.HANDLED)
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(InboxFilter.PENDING, viewModel.filter.value)
        assertEquals(listOf("open"), viewModel.visibleItems.map { it.id })

        viewModel.setFilter(InboxFilter.ALL)
        assertEquals(listOf("open", "handled"), viewModel.visibleItems.map { it.id })
    }

    @Test
    fun `important filter shows high items in any state`() = runTest {
        fake.inboxItemsToReturn = listOf(
            sampleItem(id = "high-open"),
            sampleItem(id = "high-handled", state = InboxItemState.HANDLED),
            sampleItem(id = "medium", importance = "medium")
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setFilter(InboxFilter.IMPORTANT)

        assertEquals(listOf("high-open", "high-handled"), viewModel.visibleItems.map { it.id })
    }

    @Test
    fun `unread count counts open high items only`() = runTest {
        fake.inboxItemsToReturn = listOf(
            sampleItem(id = "a"),
            sampleItem(id = "b", importance = "medium"),
            sampleItem(id = "c", state = InboxItemState.REPLIED),
            sampleItem(id = "d")
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(2, viewModel.unreadCount.value)
    }

    @Test
    fun `markHandled sends handled and updates the item locally`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.markHandled(viewModel.items.value.single())
        advanceUntilIdle()

        assertEquals(listOf("i1" to InboxItemState.HANDLED), fake.inboxStateChanges)
        assertEquals(InboxItemState.HANDLED, viewModel.items.value.single().state)
    }

    @Test
    fun `dismiss sends dismissed`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.dismiss(viewModel.items.value.single())
        advanceUntilIdle()

        assertEquals(listOf("i1" to InboxItemState.DISMISSED), fake.inboxStateChanges)
        assertEquals(InboxItemState.DISMISSED, viewModel.items.value.single().state)
    }

    @Test
    fun `a failed state change reports an error`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val viewModel = viewModel()
        advanceUntilIdle()

        fake.failWith = RuntimeException("state rejected")
        viewModel.markHandled(viewModel.items.value.single())
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("state rejected"))
    }

    @Test
    fun `sendSuggestion uses the live action and marks the item replied`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val sent = mutableListOf<Pair<String, String>>()
        val viewModel = viewModel(
            hasReplyAction = { true },
            sendReply = { key, text -> sent += key to text; true }
        )
        advanceUntilIdle()

        viewModel.sendSuggestion(viewModel.items.value.single())
        advanceUntilIdle()

        assertEquals(listOf("whatsapp|Ana" to "Ahí lo veo"), sent)
        assertEquals(listOf("i1" to InboxItemState.REPLIED), fake.inboxStateChanges)
        assertEquals(InboxItemState.REPLIED, viewModel.items.value.single().state)
    }

    @Test
    fun `sendEdited sends the edited text`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val sent = mutableListOf<Pair<String, String>>()
        val viewModel = viewModel(sendReply = { key, text -> sent += key to text; true })
        advanceUntilIdle()

        viewModel.sendEdited(viewModel.items.value.single(), "  Texto editado  ")
        advanceUntilIdle()

        assertEquals(listOf("whatsapp|Ana" to "Texto editado"), sent)
    }

    @Test
    fun `a blank edited reply is rejected before sending`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val sent = mutableListOf<Pair<String, String>>()
        val viewModel = viewModel(sendReply = { key, text -> sent += key to text; true })
        advanceUntilIdle()

        viewModel.sendEdited(viewModel.items.value.single(), "   ")
        advanceUntilIdle()

        assertTrue(sent.isEmpty())
        assertNotNull(viewModel.errorMessage.value)
        assertTrue(fake.inboxStateChanges.isEmpty())
    }

    @Test
    fun `without a live action nothing is sent and the hint explains why`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem(app = "gmail"))
        val sent = mutableListOf<Pair<String, String>>()
        val viewModel = viewModel(
            hasReplyAction = { false },
            sendReply = { key, text -> sent += key to text; true }
        )
        advanceUntilIdle()

        assertFalse(viewModel.canReply(viewModel.items.value.single()))
        viewModel.sendSuggestion(viewModel.items.value.single())
        advanceUntilIdle()

        assertTrue(sent.isEmpty())
        assertTrue(fake.inboxStateChanges.isEmpty())
        assertNotNull(viewModel.errorMessage.value)
    }

    @Test
    fun `a failed delivery reports an error and does not mark the item replied`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val viewModel = viewModel(sendReply = { _, _ -> false })
        advanceUntilIdle()

        viewModel.sendSuggestion(viewModel.items.value.single())
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(fake.inboxStateChanges.isEmpty())
        assertEquals(InboxItemState.OPEN, viewModel.items.value.single().state)
    }

    @Test
    fun `retryAnalysis calls the server and shows the item analyzing again`() = runTest {
        fake.inboxItemsToReturn = listOf(
            sampleItem(status = InboxItemStatus.FAILED)
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.retryAnalysis(viewModel.items.value.single())
        advanceUntilIdle()

        assertEquals(listOf("i1"), fake.retriedInboxIds)
        assertEquals(InboxItemStatus.ANALYZING, viewModel.items.value.single().status)
        assertNull(viewModel.items.value.single().error)
    }

    @Test
    fun `clearError resets the message`() = runTest {
        fake.failWith = RuntimeException("boom")
        val viewModel = viewModel()
        advanceUntilIdle()
        assertNotNull(viewModel.errorMessage.value)

        viewModel.clearError()

        assertNull(viewModel.errorMessage.value)
    }
}
