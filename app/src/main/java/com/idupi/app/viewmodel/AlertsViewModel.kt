package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.IduPiApp
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.inbox.InboxConversationGroup
import com.idupi.app.data.inbox.InboxConversationGroups
import com.idupi.app.data.inbox.InboxNotifier
import com.idupi.app.data.inbox.InboxReadyReply
import com.idupi.app.data.inbox.ReplyActionRegistry
import com.idupi.app.data.inbox.inboxConversationKey
import com.idupi.app.data.phone.approval.ApprovalCardText
import com.idupi.app.data.phone.approval.ApprovalDecisionFlow
import com.idupi.app.data.phone.approval.PendingApprovalsStore
import com.idupi.app.data.phone.approval.PhoneApprovals
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.data.settings.SettingsRepository
import com.idupi.app.domain.model.InboxDigest
import com.idupi.app.domain.model.InboxDigestScope
import com.idupi.app.domain.model.InboxDigestStatus
import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxInteraction
import com.idupi.app.domain.model.InboxInteractionStatus
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxItemStatus
import com.idupi.app.domain.model.PhoneApproval
import com.idupi.app.domain.model.PhoneApprovalDecisionResult
import com.idupi.app.domain.model.Project
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The two Alertas views; closed items never appear in either. */
enum class InboxFilter(val label: String) {
    PENDING("Pendientes"),
    IMPORTANT("Importantes"),
}

/**
 * State for the Alertas inbox (phone-inbox T5 + inbox-management T4).
 *
 * The server's `/api/v1/alerts` list is a stub (`[]`), so this ViewModel reads
 * the inbox instead and polls it every [POLL_INTERVAL_MS] while
 * [AlertsScreen][com.idupi.app.ui.screens.AlertsScreen] is visible. Sending a
 * suggested or edited reply goes through the notification's live RemoteInput
 * action ([ReplyActionRegistry]), never through the PC: the owner's phone is
 * the only device that can answer WhatsApp.
 *
 * Closed items (replied, auto-replied, handled, dismissed) are dropped as soon
 * as they are observed. A single item card removes itself optimistically and
 * comes back, with an inline error, only when the call that failed is the live
 * reply or the server state update.
 *
 * Conversations (inbox-management T4): open items group per app + chat. A chat
 * with three or more open items gets its digest requested automatically the
 * first time it is seen (and again when new messages arrive); the digest is
 * fetched while the server reports `analyzing`. A suggested reply on a
 * conversation card closes the whole chat through the bulk state endpoint.
 */
class AlertsViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider,
    private val hasReplyAction: (String) -> Boolean = ReplyActionRegistry::has,
    private val sendReply: (String, String) -> Boolean = { conversationKey, text ->
        runCatching { ReplyActionRegistry.send(IduPiApp.instance, conversationKey, text) }
            .getOrDefault(false)
    },
    private val settingsRepository: SettingsRepository = SettingsProvider.repository,
    private val notifyAgentAnswer: (InboxItem) -> Unit = { item ->
        // alerts-flow A3: the investigation finished while the owner was away.
        // Posting is a side effect; a shade that refuses the notification must
        // never break the polling loop.
        runCatching { InboxNotifier(IduPiApp.instance).showAgentAnswer(item) }
            .onFailure { Log.w(TAG, "could not post the agent answer notification") }
    },
    private val notifyReadyReply: (InboxItem) -> Unit = { item ->
        // alerts-flow A4: a high-importance message arrived with its draft
        // ready. Same rule as above: a refused post must not stop polling.
        runCatching { InboxNotifier(IduPiApp.instance).showReadyReply(item) }
            .onFailure { Log.w(TAG, "could not post the ready reply notification") }
    },
    private val approvalsStore: PendingApprovalsStore = PhoneApprovals.pendingStore,
    private val approvalDecision: () -> ApprovalDecisionFlow = {
        PhoneApprovals.decisionFlow(IduPiApp.instance)
    },
    private val refreshPendingApprovals: suspend () -> Boolean = {
        PhoneApprovals.refreshPending()
    },
) : ViewModel() {

    private val client get() = clientSource.client

    /**
     * Pending phone approvals (phone-approvals T4): the section on top of the
     * list reads the process-wide store, so a decision made from the
     * notification or the Ajustes card removes the row here too.
     */
    val pendingApprovals: StateFlow<List<PhoneApproval>> = approvalsStore.pending

    private val _decidingApprovals = MutableStateFlow<Set<String>>(emptySet())

    /** Ids with a decision in flight; their buttons are disabled. */
    val decidingApprovals: StateFlow<Set<String>> = _decidingApprovals.asStateFlow()

    private val _approvalMessage = MutableStateFlow<String?>(null)

    /** Short inline line after a decision ("Aprobado.", "Ya se decidió o venció."). */
    val approvalMessage: StateFlow<String?> = _approvalMessage.asStateFlow()

    private val _items = MutableStateFlow<List<InboxItem>>(emptyList())
    val items: StateFlow<List<InboxItem>> = _items.asStateFlow()

    private val _filter = MutableStateFlow(InboxFilter.PENDING)
    val filter: StateFlow<InboxFilter> = _filter.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Open high-importance items; shown as the drawer badge. */
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    /** Latest digest per conversation key; absent until requested. */
    private val _digests = MutableStateFlow<Map<String, InboxDigest>>(emptyMap())
    val digests: StateFlow<Map<String, InboxDigest>> = _digests.asStateFlow()

    /** Conversations with a digest request in flight (button spinner). */
    private val _requestingDigests = MutableStateFlow<Set<String>>(emptySet())
    val requestingDigests: StateFlow<Set<String>> = _requestingDigests.asStateFlow()

    /** Silenced conversations, from the persisted settings. */
    private val _silencedConversations = MutableStateFlow<Set<String>>(emptySet())
    val silencedConversations: StateFlow<Set<String>> = _silencedConversations.asStateFlow()

    /** Linked PC projects the Interactuar sheet offers; empty until loaded. */
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    /**
     * Last interaction status seen per item id, for the one-shot
     * "Respuesta del agente lista" ping (alerts-flow A3). A done answer that
     * was already done on the first load is history, not news.
     */
    private val interactionStatuses = mutableMapOf<String, String>()

    /**
     * Item ids already announced as "Respuesta lista para revisar"
     * (alerts-flow A4). A bounded in-memory set is enough: one ping per item
     * for the life of this ViewModel.
     */
    private val announcedReadyReplies = mutableSetOf<String>()

    private var pollingJob: Job? = null

    /** Items matching the active [filter], derived for the screen. */
    val visibleItems: List<InboxItem>
        get() = filterItems(_items.value, _filter.value)

    /** `visibleItems` grouped per conversation, newest chat first. */
    val visibleGroups: List<InboxConversationGroup>
        get() = InboxConversationGroups.group(visibleItems)

    init {
        viewModelScope.launch {
            try {
                settingsRepository.inboxSettings.collect { settings ->
                    _silencedConversations.value = settings.silencedConversations
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not observe inbox settings: ${e.javaClass.simpleName}")
            }
        }
        refresh()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun setFilter(filter: InboxFilter) {
        _filter.value = filter
    }

    /** Manual refresh (top-bar icon); keeps the list while it reloads. */
    fun refresh() {
        load(reportErrors = true, showLoading = _items.value.isEmpty())
    }

    /**
     * Polling is tied to the screen's composition: [startPolling] refreshes
     * right away, then every [POLL_INTERVAL_MS]; [stopPolling] cancels it when
     * the owner leaves Alertas. Poll failures stay silent -- the PC may simply
     * be off, and a snackbar every 10 s would be noise.
     */
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            // Opening Alertas must not wait 10 s for the first approvals poll.
            refreshApprovalsNow()
            load(reportErrors = false, showLoading = _items.value.isEmpty())
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                load(reportErrors = false, showLoading = false)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // --- Pending approvals (phone-approvals T4) ---------------------------

    /**
     * One immediate pending-refresh outside the poll. Failures stay silent: the
     * PC may be off and the last list is still the best information available.
     */
    fun refreshApprovals() {
        viewModelScope.launch { refreshApprovalsNow() }
    }

    /** Swallows a refused refresh so the last list survives the PC being off. */
    private suspend fun refreshApprovalsNow() {
        try {
            refreshPendingApprovals()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pending approval refresh failed: ${e.javaClass.simpleName}")
        }
    }

    /** Approves from the Alertas card through the shared decision path. */
    fun approveApproval(approval: PhoneApproval) = decideApproval(approval, approve = true)

    /** Rejects from the Alertas card through the shared decision path. */
    fun rejectApproval(approval: PhoneApproval) = decideApproval(approval, approve = false)

    fun clearApprovalMessage() {
        _approvalMessage.value = null
    }

    private fun decideApproval(approval: PhoneApproval, approve: Boolean) {
        val id = approval.id
        if (id.isBlank() || id in _decidingApprovals.value) return
        _decidingApprovals.update { it + id }
        viewModelScope.launch {
            try {
                val result = approvalDecision().decide(approval, approve)
                _approvalMessage.value = when (result) {
                    is PhoneApprovalDecisionResult.Decided ->
                        if (approve) ApprovalCardText.MESSAGE_APPROVED
                        else ApprovalCardText.MESSAGE_REJECTED
                    is PhoneApprovalDecisionResult.Conflict,
                    PhoneApprovalDecisionResult.NotFound,
                    -> ApprovalCardText.MESSAGE_NO_LONGER_VALID
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "approval decision failed: ${e.javaClass.simpleName}")
                _approvalMessage.value = ApprovalCardText.MESSAGE_DECISION_FAILED
            } finally {
                _decidingApprovals.update { it - id }
            }
        }
    }

    fun canReply(item: InboxItem): Boolean = hasReplyAction(conversationKeyFor(item))

    fun canReply(group: InboxConversationGroup): Boolean =
        hasReplyAction(group.conversationKey)

    fun isSilenced(conversationKey: String): Boolean =
        conversationKey in _silencedConversations.value

    /** Persists "silence but read" for one chat; the collector updates the UI. */
    fun setConversationSilenced(conversationKey: String, silenced: Boolean) {
        if (conversationKey.isBlank()) return
        viewModelScope.launch {
            try {
                val current = settingsRepository.inboxSettings.first().silencedConversations
                settingsRepository.setInboxSilencedConversations(
                    if (silenced) current + conversationKey else current - conversationKey
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not update silenced conversations", e)
                _errorMessage.value = "No se pudo cambiar el silencio del chat"
            }
        }
    }

    fun markHandled(item: InboxItem) {
        changeState(item, InboxItemState.HANDLED, "No se pudo marcar la alerta como atendida")
    }

    fun dismiss(item: InboxItem) {
        changeState(item, InboxItemState.DISMISSED, "No se pudo descartar la alerta")
    }

    /** Sends the agent's suggestion exactly as shown. */
    fun sendSuggestion(item: InboxItem) {
        val text = item.suggestedReply.trim()
        if (text.isEmpty()) {
            _errorMessage.value = "No hay una sugerencia para enviar"
            return
        }
        sendReplyText(item, text)
    }

    /** Sends the text the owner edited in the dialog. */
    fun sendEdited(item: InboxItem, text: String) {
        sendReplyText(item, text.trim())
    }

    /** Sends the digest's suggested reply and closes the whole chat. */
    fun sendGroupSuggestion(group: InboxConversationGroup) {
        val text = _digests.value[group.conversationKey]?.suggestedReply?.trim().orEmpty()
        if (text.isEmpty()) {
            _errorMessage.value = "No hay una sugerencia para enviar"
            return
        }
        sendGroupReplyText(group, text)
    }

    fun sendGroupEdited(group: InboxConversationGroup, text: String) {
        sendGroupReplyText(group, text.trim())
    }

    /**
     * Requests the digest for one chat. The button ("Resumir"/"Reintentar")
     * calls this; the automatic request uses the same path.
     */
    fun requestDigest(group: InboxConversationGroup) {
        if (group.conversationKey in _requestingDigests.value) return
        viewModelScope.launch { requestDigestNow(group) }
    }

    /**
     * Requests the one promotions summary (alerts-flow A2). Unlike a chat, it
     * never runs automatically: the owner asks for it with "Resumir".
     */
    fun requestPromotionsDigest() {
        val key = InboxConversationGroups.PROMOTIONS_KEY
        if (key in _requestingDigests.value) return
        viewModelScope.launch { requestPromotionsDigestNow() }
    }

    /**
     * Closes every open promotion in one call (alerts-flow A2). The card
     * leaves immediately and comes back only if the call fails.
     */
    fun markPromotionsHandled(group: InboxConversationGroup) {
        val ids = group.items.mapTo(mutableSetOf()) { it.id }
        val previous = _items.value
        publish(previous.filterNot { it.id in ids })
        removeDigest(InboxConversationGroups.PROMOTIONS_KEY)
        viewModelScope.launch {
            try {
                client.setInboxPromotionsState(InboxItemState.HANDLED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close promotions", e)
                publish(previous)
                _errorMessage.value =
                    "No se pudo marcar las promociones como revisadas: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Closes every open item of one chat in a single request (inbox-management
     * T4). The card leaves immediately and comes back only if the call fails.
     */
    fun markGroupHandled(group: InboxConversationGroup) {
        val ids = group.items.mapTo(mutableSetOf()) { it.id }
        val previous = _items.value
        publish(previous.filterNot { it.id in ids })
        removeDigest(group.conversationKey)
        viewModelScope.launch {
            try {
                client.setInboxConversationState(
                    group.app,
                    group.conversation,
                    InboxItemState.HANDLED
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close conversation ${group.conversationKey}", e)
                publish(previous)
                _errorMessage.value =
                    "No se pudo marcar la conversación como atendida: ${e.localizedMessage}"
            }
        }
    }

    private fun sendReplyText(item: InboxItem, text: String) {
        if (text.isEmpty()) {
            _errorMessage.value = "Escribe un texto antes de enviar"
            return
        }
        val conversationKey = conversationKeyFor(item)
        if (!hasReplyAction(conversationKey)) {
            _errorMessage.value =
                "La notificación original ya no está disponible. Responde desde la app."
            return
        }
        val index = removeOptimistically(item)
        viewModelScope.launch {
            val sent = try {
                sendReply(conversationKey, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reply delivery threw: ${e.javaClass.simpleName}")
                false
            }
            if (!sent) {
                if (index >= 0) restore(item, index)
                _errorMessage.value =
                    "No se pudo enviar la respuesta. Responde desde la app original."
                return@launch
            }
            try {
                client.setInboxItemState(item.id, InboxItemState.REPLIED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (index >= 0) restore(item, index)
                _errorMessage.value =
                    "La respuesta se envió, pero no se pudo actualizar el estado: ${e.localizedMessage}"
            }
        }
    }

    private fun sendGroupReplyText(group: InboxConversationGroup, text: String) {
        if (text.isEmpty()) {
            _errorMessage.value = "Escribe un texto antes de enviar"
            return
        }
        if (!hasReplyAction(group.conversationKey)) {
            _errorMessage.value =
                "La notificación original ya no está disponible. Responde desde la app."
            return
        }
        val ids = group.items.mapTo(mutableSetOf()) { it.id }
        val previous = _items.value
        publish(previous.filterNot { it.id in ids })
        removeDigest(group.conversationKey)
        viewModelScope.launch {
            val sent = try {
                sendReply(group.conversationKey, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "group reply delivery threw: ${e.javaClass.simpleName}")
                false
            }
            if (!sent) {
                publish(previous)
                _errorMessage.value =
                    "No se pudo enviar la respuesta. Responde desde la app original."
                return@launch
            }
            try {
                // The bulk endpoint only accepts handled|dismissed; a chat the
                // owner answered leaves the list as handled.
                client.setInboxConversationState(
                    group.app,
                    group.conversation,
                    InboxItemState.HANDLED
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish(previous)
                _errorMessage.value =
                    "La respuesta se envió, pero no se pudo actualizar el estado: ${e.localizedMessage}"
            }
        }
    }

    fun retryAnalysis(item: InboxItem) {
        viewModelScope.launch {
            try {
                client.retryInboxItem(item.id)
                publish(
                    _items.value.map {
                        if (it.id == item.id) {
                            it.copy(status = InboxItemStatus.ANALYZING, error = null)
                        } else {
                            it
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to retry inbox item ${item.id}", e)
                _errorMessage.value = "No se pudo reintentar el análisis: ${e.localizedMessage}"
            }
        }
    }

    // --- Interactuar (alerts-flow A3) --------------------------------------

    /**
     * Hands one owner instruction to the PC agent (alerts-flow A3). The card
     * switches to `running` immediately; the server's answered item replaces
     * that optimistic block, and polling carries the finished answer.
     */
    fun interact(itemId: String, instruction: String, projectId: String?) {
        val trimmed = instruction.trim()
        if (trimmed.isEmpty()) {
            _errorMessage.value = "Escribe una instrucción antes de enviar"
            return
        }
        val item = _items.value.firstOrNull { it.id == itemId } ?: return
        startInteraction(item, trimmed, projectId)
    }

    /** Re-sends a failed interaction with the same instruction and project. */
    fun retryInteraction(item: InboxItem) {
        val interaction = item.interaction ?: return
        val instruction = interaction.instruction.trim()
        if (instruction.isEmpty()) {
            _errorMessage.value = "Escribe una instrucción antes de enviar"
            return
        }
        val current = _items.value.firstOrNull { it.id == item.id } ?: item
        startInteraction(current, instruction, interaction.projectId)
    }

    /**
     * Loads the linked PC projects for the sheet's "Proyecto (opcional)"
     * picker. Failures stay silent: the sheet still works with "Ninguno".
     */
    fun refreshProjects() {
        viewModelScope.launch {
            try {
                _projects.value = client.getProjects()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load projects for Interactuar", e)
            }
        }
    }

    private fun startInteraction(item: InboxItem, instruction: String, projectId: String?) {
        val previous = item.interaction
        val optimistic = InboxInteraction(
            status = InboxInteractionStatus.RUNNING,
            instruction = instruction,
            projectId = projectId,
            projectName = projectNameOf(projectId)
        )
        updateInteraction(item.id, optimistic)
        viewModelScope.launch {
            try {
                val updated = client.interactWithInboxItem(item.id, instruction, projectId)
                updated.interaction?.let { updateInteraction(item.id, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start interaction for ${item.id}", e)
                updateInteraction(item.id, previous)
                _errorMessage.value =
                    "No se pudo iniciar la interacción: ${e.localizedMessage}"
            }
        }
    }

    private fun updateInteraction(itemId: String, interaction: InboxInteraction?) {
        publish(
            _items.value.map {
                if (it.id == itemId) it.copy(interaction = interaction) else it
            }
        )
    }

    private fun projectNameOf(projectId: String?): String? =
        projectId?.let { id -> _projects.value.firstOrNull { it.id == id }?.name }

    private fun load(reportErrors: Boolean, showLoading: Boolean) {
        viewModelScope.launch {
            if (showLoading) _isLoading.value = true
            try {
                publish(client.getInboxItems(PAGE_SIZE))
                refreshDigests()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load inbox items", e)
                if (reportErrors) {
                    _errorMessage.value =
                        "No se pudieron cargar las alertas: ${e.localizedMessage}"
                }
            } finally {
                if (showLoading) _isLoading.value = false
            }
        }
    }

    /**
     * One pass over the visible conversations: fetch digests the server is
     * still building, and request the ones a card needs. Sequential on purpose
     * -- each request is a small HTTP call and a chat rarely crosses the
     * automatic threshold.
     */
    private suspend fun refreshDigests() {
        val groups = InboxConversationGroups.group(_items.value)
        if (groups.isEmpty()) {
            _digests.value = emptyMap()
            return
        }
        val keys = groups.mapTo(mutableSetOf()) { it.conversationKey }
        _digests.update { current -> current.filterKeys { it in keys } }

        for (group in groups) {
            if (group.conversationKey == InboxConversationGroups.PROMOTIONS_KEY) {
                // The promotions digest only runs when the owner asks for it
                // (alerts-flow A2); a run already in flight is just fetched.
                if (_digests.value[group.conversationKey]?.status == InboxDigestStatus.ANALYZING) {
                    fetchPromotionsDigest()
                }
                continue
            }
            val existing = _digests.value[group.conversationKey]
            when {
                existing?.status == InboxDigestStatus.ANALYZING -> fetchDigest(group)
                group.count >= AUTO_DIGEST_MIN_MESSAGES && needsDigest(group, existing) ->
                    requestDigestNow(group)
            }
        }
    }

    /** A digest is stale when it has not covered every message the card lists. */
    private fun needsDigest(group: InboxConversationGroup, digest: InboxDigest?): Boolean =
        digest == null || digest.itemCount < group.count

    private suspend fun fetchDigest(group: InboxConversationGroup) {
        try {
            val digest = client.getInboxDigest(group.app, group.conversation)
            _digests.update { it + (group.conversationKey to digest) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The PC may be off; the analyzing state stays and the next poll
            // tries again instead of dropping the card into an error.
            Log.w(TAG, "Failed to fetch digest for ${group.conversationKey}", e)
        }
    }

    private suspend fun requestDigestNow(group: InboxConversationGroup) {
        val key = group.conversationKey
        if (key in _requestingDigests.value) return
        _requestingDigests.update { it + key }
        try {
            val digest = client.requestInboxDigest(group.app, group.conversation)
            _digests.update { it + (key to digest) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request digest for $key", e)
            // Inline failure on the card, not a global error: the owner can
            // press Reintentar without losing the rest of the list.
            _digests.update {
                it + (key to InboxDigest(
                    app = group.app,
                    conversation = group.conversation,
                    itemCount = group.count,
                    status = InboxDigestStatus.FAILED,
                    error = "No se pudo resumir la conversación: ${e.localizedMessage}"
                ))
            }
        } finally {
            _requestingDigests.update { it - key }
        }
    }

    private fun removeDigest(conversationKey: String) {
        _digests.update { it - conversationKey }
    }

    /** Fetches the promotions digest while the server still reports analyzing. */
    private suspend fun fetchPromotionsDigest() {
        try {
            val digest = client.getInboxPromotionsDigest()
            _digests.update { it + (InboxConversationGroups.PROMOTIONS_KEY to digest) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The PC may be off; the next poll tries again.
            Log.w(TAG, "Failed to fetch the promotions digest", e)
        }
    }

    private suspend fun requestPromotionsDigestNow() {
        val key = InboxConversationGroups.PROMOTIONS_KEY
        if (key in _requestingDigests.value) return
        _requestingDigests.update { it + key }
        val count = InboxConversationGroups.promotionItems(_items.value).size
        try {
            val digest = client.requestInboxPromotionsDigest()
            _digests.update { it + (key to digest) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request the promotions digest", e)
            _digests.update {
                it + (key to InboxDigest(
                    app = "scope",
                    conversation = InboxDigestScope.PROMOTIONS,
                    scope = InboxDigestScope.PROMOTIONS,
                    itemCount = count,
                    status = InboxDigestStatus.FAILED,
                    error = "No se pudo resumir las promociones: ${e.localizedMessage}"
                ))
            }
        } finally {
            _requestingDigests.update { it - key }
        }
    }

    private fun changeState(item: InboxItem, state: String, failureMessage: String) {
        val index = removeOptimistically(item)
        viewModelScope.launch {
            try {
                client.setInboxItemState(item.id, state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set inbox item ${item.id} to $state", e)
                if (index >= 0) restore(item, index)
                _errorMessage.value = "$failureMessage: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Drops the item from the visible list right away and returns its previous
     * position, or -1 when it was not there. A failed call passes that
     * position to [restore].
     */
    private fun removeOptimistically(item: InboxItem): Int {
        val current = _items.value
        val index = current.indexOfFirst { it.id == item.id }
        if (index < 0) return -1
        publish(current.filterNot { it.id == item.id })
        return index
    }

    /** Puts an optimistically removed item back at its previous position. */
    private fun restore(item: InboxItem, index: Int) {
        val current = _items.value
        if (current.any { it.id == item.id }) return
        publish(
            current.toMutableList().apply {
                add(index.coerceIn(0, size), item)
            }
        )
    }

    /**
     * One-shot "Respuesta del agente lista" ping (alerts-flow A3). Only a
     * running -> done transition while Alertas is observing it counts as
     * news: an answer that was already done when the list first loaded would
     * otherwise re-ping on every app start, and a failed job stays silent
     * because its error is shown on the card.
     */
    private fun announceFinishedInteractions(
        previous: List<InboxItem>,
        next: List<InboxItem>
    ) {
        val previousById = previous.associateBy { it.id }
        val presentIds = next.mapTo(mutableSetOf()) { it.id }
        interactionStatuses.keys.retainAll(presentIds)
        for (item in next) {
            val status = item.interaction?.status ?: continue
            val before = interactionStatuses[item.id]
                ?: previousById[item.id]?.interaction?.status
            interactionStatuses[item.id] = status
            if (status == InboxInteractionStatus.DONE &&
                before == InboxInteractionStatus.RUNNING
            ) {
                notifyAgentAnswer(item)
            }
        }
    }

    /**
     * One-shot "Respuesta lista para revisar" ping per item (alerts-flow A4).
     * The first poll that sees an item qualifying pings it and remembers the
     * id, so a repeated poll never pings twice -- not even if the card leaves
     * the list and comes back after a failed state change. The set is capped
     * at [MAX_ANNOUNCED_READY_REPLIES] by dropping the oldest ids.
     */
    private fun announceReadyReplies(items: List<InboxItem>) {
        for (item in items) {
            if (item.id in announcedReadyReplies) continue
            if (!InboxReadyReply.qualifies(item, hasReplyAction(conversationKeyFor(item)))) continue
            announcedReadyReplies += item.id
            if (announcedReadyReplies.size > MAX_ANNOUNCED_READY_REPLIES) {
                announcedReadyReplies.remove(announcedReadyReplies.first())
            }
            notifyReadyReply(item)
        }
    }

    /** Closed items leave Alertas; they are never part of the working list. */
    private fun publish(items: List<InboxItem>) {
        announceFinishedInteractions(_items.value, items)
        _items.value = items.filter { it.state == InboxItemState.OPEN }
        announceReadyReplies(_items.value)
        _unreadCount.value = _items.value.count {
            it.state == InboxItemState.OPEN &&
                it.importance == InboxImportance.HIGH &&
                !it.promotion
        }
        val keys = InboxConversationGroups.group(_items.value)
            .mapTo(mutableSetOf()) { it.conversationKey }
        _digests.update { current -> current.filterKeys { it in keys } }
    }

    companion object {
        private const val TAG = "AlertsViewModel"

        /** Items requested per poll; the server returns newest first. */
        const val PAGE_SIZE = 50

        /** From this many open messages a shown card requests its digest. */
        const val AUTO_DIGEST_MIN_MESSAGES = 3

        /**
         * How many announced ready replies the one-shot set remembers before
         * dropping the oldest id (alerts-flow A4).
         */
        private const val MAX_ANNOUNCED_READY_REPLIES = 200

        private const val POLL_INTERVAL_MS = 10_000L

        fun conversationKeyFor(item: InboxItem): String =
            inboxConversationKey(item.app, item.conversation)

        fun filterItems(items: List<InboxItem>, filter: InboxFilter): List<InboxItem> =
            items.filter { item ->
                item.state == InboxItemState.OPEN &&
                    when (filter) {
                        // Promotions are never important, even if an older
                        // server marked one high (alerts-flow A2).
                        InboxFilter.IMPORTANT ->
                            item.importance == InboxImportance.HIGH && !item.promotion
                        InboxFilter.PENDING -> true
                    }
            }
    }
}
