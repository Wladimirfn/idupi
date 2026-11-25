package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.inbox.InboxConversationGroups
import com.idupi.app.data.settings.InMemorySettingsRepository
import com.idupi.app.data.settings.SettingsRepository
import com.idupi.app.domain.model.InboxDigest
import com.idupi.app.domain.model.InboxInteraction
import com.idupi.app.domain.model.InboxInteractionStatus
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxItemStatus
import com.idupi.app.domain.model.InboxSettings
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
        promotion: Boolean = false,
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
        state = state,
        promotion = promotion
    )

    private fun viewModel(
        hasReplyAction: (String) -> Boolean = { true },
        sendReply: (String, String) -> Boolean = { _, _ -> true },
        settings: SettingsRepository = InMemorySettingsRepository(),
        notifyAgentAnswer: (InboxItem) -> Unit = {},
    ) = AlertsViewModel(FakeClientSource(fake), hasReplyAction, sendReply, settings, notifyAgentAnswer)

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
    }

    @Test
    fun `closed items never reach the visible list`() = runTest {
        fake.inboxItemsToReturn = listOf(
            sampleItem(id = "open"),
            sampleItem(id = "replied", state = InboxItemState.REPLIED),
            sampleItem(id = "auto", state = InboxItemState.AUTO_REPLIED),
            sampleItem(id = "handled", state = InboxItemState.HANDLED),
            sampleItem(id = "dismissed", state = InboxItemState.DISMISSED)
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("open"), viewModel.items.value.map { it.id })
        viewModel.setFilter(InboxFilter.IMPORTANT)
        assertEquals(listOf("open"), viewModel.visibleItems.map { it.id })
    }

    @Test
    fun `important filter shows open high items only`() = runTest {
        fake.inboxItemsToReturn = listOf(
            sampleItem(id = "high-open"),
            sampleItem(id = "high-handled", state = InboxItemState.HANDLED),
            sampleItem(id = "medium", importance = "medium")
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setFilter(InboxFilter.IMPORTANT)

        assertEquals(listOf("high-open"), viewModel.visibleItems.map { it.id })
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
    fun `markHandled removes the card immediately and sends handled`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.markHandled(viewModel.items.value.single())

        // Optimistic removal happens before the request leaves the dispatcher.
        assertTrue(viewModel.items.value.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf("i1" to InboxItemState.HANDLED), fake.inboxStateChanges)
        assertTrue(viewModel.items.value.isEmpty())
    }

    @Test
    fun `dismiss removes the card immediately and sends dismissed`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.dismiss(viewModel.items.value.single())
        assertTrue(viewModel.items.value.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf("i1" to InboxItemState.DISMISSED), fake.inboxStateChanges)
        assertTrue(viewModel.items.value.isEmpty())
    }

    @Test
    fun `a failed state change restores the card and reports an error`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val viewModel = viewModel()
        advanceUntilIdle()

        fake.failWith = RuntimeException("state rejected")
        viewModel.markHandled(viewModel.items.value.single())
        assertTrue(viewModel.items.value.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf("i1"), viewModel.items.value.map { it.id })
        assertEquals(InboxItemState.OPEN, viewModel.items.value.single().state)
        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("state rejected"))
    }

    @Test
    fun `sendSuggestion uses the live action, removes the card and marks it replied`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val sent = mutableListOf<Pair<String, String>>()
        val viewModel = viewModel(
            hasReplyAction = { true },
            sendReply = { key, text -> sent += key to text; true }
        )
        advanceUntilIdle()

        viewModel.sendSuggestion(viewModel.items.value.single())
        assertTrue(viewModel.items.value.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf("whatsapp|Ana" to "Ahí lo veo"), sent)
        assertEquals(listOf("i1" to InboxItemState.REPLIED), fake.inboxStateChanges)
        assertTrue(viewModel.items.value.isEmpty())
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
    fun `a failed delivery restores the card and reports an error`() = runTest {
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
    fun `a sent reply whose state update fails restores the card and explains`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val viewModel = viewModel(sendReply = { _, _ -> true })
        advanceUntilIdle()

        fake.failWith = RuntimeException("state rejected")
        viewModel.sendSuggestion(viewModel.items.value.single())
        assertTrue(viewModel.items.value.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf("i1"), viewModel.items.value.map { it.id })
        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("no se pudo actualizar"))
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

    // --- conversation grouping and digest (inbox-management T4) -------------

    private fun groupItems(conversation: String = "Ana", count: Int = 3): List<InboxItem> =
        (1..count).map { index ->
            sampleItem(id = "i$index", conversation = conversation)
        }

    @Test
    fun `three open messages of one chat request the digest automatically`() = runTest {
        fake.inboxItemsToReturn = groupItems(count = 3)
        fake.inboxDigestToReturn = InboxDigest(
            app = "whatsapp",
            conversation = "Ana",
            itemCount = 3,
            status = "analyzing"
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("whatsapp" to "Ana"), fake.requestedInboxDigests)
        assertEquals(1, viewModel.visibleGroups.size)
        assertEquals(3, viewModel.visibleGroups.single().count)
        assertEquals("analyzing", viewModel.digests.value["whatsapp|Ana"]?.status)
    }

    @Test
    fun `two open messages never request a digest automatically`() = runTest {
        fake.inboxItemsToReturn = groupItems(count = 2)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(fake.requestedInboxDigests.isEmpty())
        assertEquals(2, viewModel.visibleGroups.single().count)
    }

    @Test
    fun `an analyzing digest is fetched on the next load`() = runTest {
        fake.inboxItemsToReturn = groupItems(count = 3)
        fake.inboxDigestToReturn = InboxDigest(
            app = "whatsapp",
            conversation = "Ana",
            itemCount = 3,
            status = "analyzing"
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        fake.inboxDigestToReturn = InboxDigest(
            app = "whatsapp",
            conversation = "Ana",
            itemCount = 3,
            summary = "Acordaron la reunión del martes.",
            pendingForOwner = listOf("¿Puedes enviar el informe?"),
            suggestedReply = "Sí, lo envío el lunes.",
            status = "ready"
        )
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf("whatsapp" to "Ana"), fake.fetchedInboxDigests)
        val digest = viewModel.digests.value.getValue("whatsapp|Ana")
        assertEquals("ready", digest.status)
        assertEquals("Acordaron la reunión del martes.", digest.summary)
        assertEquals(listOf("¿Puedes enviar el informe?"), digest.pendingForOwner)
    }

    @Test
    fun `a failing digest request leaves an inline retryable failure`() = runTest {
        fake.inboxItemsToReturn = groupItems(count = 3)
        fake.failInboxDigestWith = RuntimeException("pc off")

        val viewModel = viewModel()
        advanceUntilIdle()

        val digest = viewModel.digests.value.getValue("whatsapp|Ana")
        assertEquals("failed", digest.status)
        assertTrue(digest.error!!.contains("pc off"))
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `a manual digest request works with fewer than three messages`() = runTest {
        fake.inboxItemsToReturn = groupItems(count = 2)
        fake.inboxDigestToReturn = InboxDigest(
            app = "whatsapp",
            conversation = "Ana",
            itemCount = 2,
            summary = "Resumen de dos mensajes.",
            status = "ready"
        )
        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(fake.requestedInboxDigests.isEmpty())

        viewModel.requestDigest(viewModel.visibleGroups.single())
        advanceUntilIdle()

        assertEquals(listOf("whatsapp" to "Ana"), fake.requestedInboxDigests)
        assertEquals("Resumen de dos mensajes.", viewModel.digests.value.getValue("whatsapp|Ana").summary)
    }

    @Test
    fun `markGroupHandled removes the chat and closes it in one bulk call`() = runTest {
        fake.inboxItemsToReturn = groupItems(count = 2) + sampleItem(
            id = "other",
            conversation = "Beto"
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        val group = viewModel.visibleGroups.first { it.conversation == "Ana" }
        viewModel.markGroupHandled(group)

        assertEquals(listOf("other"), viewModel.items.value.map { it.id })
        advanceUntilIdle()

        assertEquals(
            listOf(Triple("whatsapp", "Ana", InboxItemState.HANDLED)),
            fake.inboxConversationStates
        )
        assertTrue(fake.inboxStateChanges.isEmpty())
    }

    @Test
    fun `a failed bulk close restores the chat items`() = runTest {
        fake.inboxItemsToReturn = groupItems(count = 2)
        val viewModel = viewModel()
        advanceUntilIdle()

        fake.failWith = RuntimeException("state rejected")
        viewModel.markGroupHandled(viewModel.visibleGroups.single())
        assertTrue(viewModel.items.value.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf("i1", "i2"), viewModel.items.value.map { it.id }.sorted())
        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("state rejected"))
    }

    @Test
    fun `a group suggestion sends through the live action and closes the chat`() = runTest {
        fake.inboxItemsToReturn = groupItems(count = 3)
        fake.inboxDigestToReturn = InboxDigest(
            app = "whatsapp",
            conversation = "Ana",
            itemCount = 3,
            summary = "Resumen.",
            suggestedReply = "Sí, lo envío el lunes.",
            status = "ready"
        )
        val sent = mutableListOf<Pair<String, String>>()
        val viewModel = viewModel(sendReply = { key, text -> sent += key to text; true })
        advanceUntilIdle()

        viewModel.sendGroupSuggestion(viewModel.visibleGroups.single())
        advanceUntilIdle()

        assertEquals(listOf("whatsapp|Ana" to "Sí, lo envío el lunes."), sent)
        assertEquals(
            listOf(Triple("whatsapp", "Ana", InboxItemState.HANDLED)),
            fake.inboxConversationStates
        )
        assertTrue(viewModel.items.value.isEmpty())
    }

    @Test
    fun `a group reply without a live action is rejected inline`() = runTest {
        fake.inboxItemsToReturn = groupItems(count = 2)
        fake.inboxDigestToReturn = InboxDigest(
            app = "whatsapp",
            conversation = "Ana",
            itemCount = 2,
            suggestedReply = "Sí.",
            status = "ready"
        )
        val sent = mutableListOf<Pair<String, String>>()
        val viewModel = viewModel(
            hasReplyAction = { false },
            sendReply = { key, text -> sent += key to text; true }
        )
        advanceUntilIdle()
        viewModel.requestDigest(viewModel.visibleGroups.single())
        advanceUntilIdle()

        assertFalse(viewModel.canReply(viewModel.visibleGroups.single()))
        viewModel.sendGroupSuggestion(viewModel.visibleGroups.single())
        advanceUntilIdle()

        assertTrue(sent.isEmpty())
        assertTrue(fake.inboxConversationStates.isEmpty())
        assertNotNull(viewModel.errorMessage.value)
    }

    @Test
    fun `silenced conversations come from the settings flow`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        val settings = InMemorySettingsRepository(
            initialInbox = InboxSettings(silencedConversations = setOf("whatsapp|Ana"))
        )
        val viewModel = viewModel(settings = settings)
        advanceUntilIdle()

        assertTrue(viewModel.isSilenced("whatsapp|Ana"))

        viewModel.setConversationSilenced("whatsapp|Ana", silenced = false)
        advanceUntilIdle()

        assertFalse(viewModel.isSilenced("whatsapp|Ana"))
    }

    // --- promotions (alerts-flow A2) ---------------------------------------

    @Test
    fun `promotions are not summarized automatically`() = runTest {
        fake.inboxItemsToReturn = listOf(
            sampleItem(id = "p1", promotion = true, importance = "low"),
            sampleItem(id = "p2", promotion = true, importance = "low"),
            sampleItem(id = "p3", promotion = true, importance = "low")
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(0, fake.promotionsDigestRequests)
        val promotions = viewModel.visibleGroups.single()
        assertEquals(InboxConversationGroups.PROMOTIONS_KEY, promotions.conversationKey)
    }

    @Test
    fun `requestPromotionsDigest stores the summary under the promotions key`() = runTest {
        fake.inboxItemsToReturn = listOf(
            sampleItem(id = "p1", app = "gmail", promotion = true, importance = "low"),
            sampleItem(id = "p2", app = "telegram", promotion = true, importance = "low")
        )
        fake.inboxPromotionsDigestToReturn = InboxDigest(
            app = "scope",
            conversation = "promotions",
            scope = "promotions",
            itemCount = 2,
            summary = "• Tienda: 50% hasta el domingo.",
            status = "ready"
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.requestPromotionsDigest()
        advanceUntilIdle()

        assertEquals(1, fake.promotionsDigestRequests)
        val digest = viewModel.digests.value[InboxConversationGroups.PROMOTIONS_KEY]
        assertEquals("• Tienda: 50% hasta el domingo.", digest?.summary)
        assertTrue(viewModel.requestingDigests.value.isEmpty())
    }

    @Test
    fun `the important filter never shows promotions`() = runTest {
        fake.inboxItemsToReturn = listOf(
            sampleItem(
                id = "promo",
                conversation = "Ofertas",
                promotion = true,
                importance = "high"
            ),
            sampleItem(id = "real", importance = "high")
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setFilter(InboxFilter.IMPORTANT)

        assertEquals(listOf("real"), viewModel.visibleItems.map { it.id })
        assertEquals(listOf("whatsapp|Ana"), viewModel.visibleGroups.map { it.conversationKey })
    }

    @Test
    fun `markPromotionsHandled closes every promotion in one bulk call`() = runTest {
        fake.inboxItemsToReturn = listOf(
            sampleItem(id = "p1", app = "gmail", promotion = true, importance = "low"),
            sampleItem(id = "p2", app = "telegram", promotion = true, importance = "low"),
            sampleItem(id = "normal", importance = "high")
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        val promotions = viewModel.visibleGroups.last()
        viewModel.markPromotionsHandled(promotions)
        advanceUntilIdle()

        assertEquals(listOf(InboxItemState.HANDLED), fake.inboxPromotionsStates)
        assertEquals(listOf("normal"), viewModel.items.value.map { it.id })
        assertNull(viewModel.digests.value[InboxConversationGroups.PROMOTIONS_KEY])
    }

    @Test
    fun `a failed bulk close puts the promotions back`() = runTest {
        fake.inboxItemsToReturn = listOf(
            sampleItem(id = "p1", promotion = true, importance = "low")
        )
        fake.failInboxPromotionsStateWith = RuntimeException("server off")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.markPromotionsHandled(viewModel.visibleGroups.single())
        advanceUntilIdle()

        assertEquals(listOf("p1"), viewModel.items.value.map { it.id })
        assertNotNull(viewModel.errorMessage.value)
    }

    // --- Interactuar (alerts-flow A3) --------------------------------------

    private fun runningInteraction(instruction: String = "revisa esto") = InboxInteraction(
        status = InboxInteractionStatus.RUNNING,
        instruction = instruction,
        startedAt = "2026-11-24T10:00:00.000Z"
    )

    private fun doneInteraction(
        instruction: String = "revisa esto",
        answer: String = "No te afecta: revisé package.json."
    ) = InboxInteraction(
        status = InboxInteractionStatus.DONE,
        instruction = instruction,
        projectId = "idupi",
        projectName = "IDUPI",
        answer = answer,
        startedAt = "2026-11-24T10:00:00.000Z",
        finishedAt = "2026-11-24T10:02:00.000Z"
    )

    @Test
    fun `interacting posts the instruction and project and shows the running state`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        fake.interactionResult = sampleItem().copy(interaction = runningInteraction())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.interact("i1", "revisa esto", "idupi")
        advanceUntilIdle()

        val request = fake.interactionRequests.single()
        assertEquals("i1", request.id)
        assertEquals("revisa esto", request.instruction)
        assertEquals("idupi", request.projectId)
        assertEquals(
            InboxInteractionStatus.RUNNING,
            viewModel.items.value.single().interaction?.status
        )
    }

    @Test
    fun `a running to done transition posts the answer notification once`() = runTest {
        val notified = mutableListOf<InboxItem>()
        fake.inboxItemsToReturn = listOf(sampleItem().copy(interaction = runningInteraction()))
        val viewModel = viewModel(notifyAgentAnswer = { notified += it })
        advanceUntilIdle()
        assertTrue(notified.isEmpty())

        fake.inboxItemsToReturn = listOf(
            sampleItem().copy(interaction = doneInteraction())
        )
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(1, notified.size)
        assertEquals("i1", notified.single().id)
        assertEquals("revisa esto", notified.single().interaction?.instruction)

        // The next poll sees the same done item: no second ping.
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, notified.size)
    }

    @Test
    fun `an interaction already done on the first load does not notify`() = runTest {
        val notified = mutableListOf<InboxItem>()
        fake.inboxItemsToReturn = listOf(sampleItem().copy(interaction = doneInteraction()))

        val viewModel = viewModel(notifyAgentAnswer = { notified += it })
        advanceUntilIdle()

        assertTrue(notified.isEmpty())
    }

    @Test
    fun `a failed interaction request keeps the item and shows an inline error`() = runTest {
        fake.inboxItemsToReturn = listOf(sampleItem())
        fake.failInteractionWith = RuntimeException("server off")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.interact("i1", "revisa esto", null)
        advanceUntilIdle()

        assertEquals("i1", viewModel.items.value.single().id)
        assertEquals(
            "No se pudo iniciar la interacción: server off",
            viewModel.errorMessage.value
        )
    }

    @Test
    fun `retrying a failed interaction reuses its instruction and project`() = runTest {
        val failed = sampleItem().copy(
            interaction = InboxInteraction(
                status = InboxInteractionStatus.FAILED,
                instruction = "revisa si esto afecta al proyecto",
                projectId = "idupi",
                projectName = "IDUPI",
                error = "agent failed"
            )
        )
        fake.inboxItemsToReturn = listOf(failed)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.retryInteraction(failed)
        advanceUntilIdle()

        val request = fake.interactionRequests.single()
        assertEquals("i1", request.id)
        assertEquals("revisa si esto afecta al proyecto", request.instruction)
        assertEquals("idupi", request.projectId)
    }

    @Test
    fun `refreshProjects exposes the linked projects for the picker`() = runTest {
        fake.projectsToReturn = listOf(
            com.idupi.app.domain.model.Project(
                id = "idupi",
                name = "IDUPI",
                path = "C:\\dev\\idupi",
                isActive = false
            )
        )
        val viewModel = viewModel()

        viewModel.refreshProjects()
        advanceUntilIdle()

        assertEquals(listOf("idupi"), viewModel.projects.value.map { it.id })
    }
}
