package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.IduPiApp
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.inbox.InboxConversationGroup
import com.idupi.app.data.inbox.InboxConversationGroups
import com.idupi.app.data.inbox.ReplyActionRegistry
import com.idupi.app.data.inbox.inboxConversationKey
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.data.settings.SettingsRepository
import com.idupi.app.domain.model.InboxDigest
import com.idupi.app.domain.model.InboxDigestStatus
import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxItemStatus
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
) : ViewModel() {

    private val client get() = clientSource.client

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

    /** Closed items leave Alertas; they are never part of the working list. */
    private fun publish(items: List<InboxItem>) {
        _items.value = items.filter { it.state == InboxItemState.OPEN }
        _unreadCount.value = _items.value.count {
            it.state == InboxItemState.OPEN && it.importance == InboxImportance.HIGH
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

        private const val POLL_INTERVAL_MS = 10_000L

        fun conversationKeyFor(item: InboxItem): String =
            inboxConversationKey(item.app, item.conversation)

        fun filterItems(items: List<InboxItem>, filter: InboxFilter): List<InboxItem> =
            items.filter { item ->
                item.state == InboxItemState.OPEN &&
                    (filter == InboxFilter.PENDING || item.importance == InboxImportance.HIGH)
            }
    }
}
