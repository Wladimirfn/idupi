package com.idupi.app.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.IduPiApp
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.inbox.ReplyActionRegistry
import com.idupi.app.data.inbox.inboxConversationKey
import com.idupi.app.data.notification.SupervisorNotificationManager
import com.idupi.app.domain.model.AlertCategory
import com.idupi.app.domain.model.AlertSeverity
import com.idupi.app.domain.model.InboxImportance
import com.idupi.app.domain.model.InboxItem
import com.idupi.app.domain.model.InboxItemState
import com.idupi.app.domain.model.InboxItemStatus
import com.idupi.app.domain.model.SupervisorAlert
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The three Alertas views; Pendientes is the daily working list. */
enum class InboxFilter(val label: String) {
    PENDING("Pendientes"),
    ALL("Todas"),
    IMPORTANT("Importantes"),
}

/**
 * State for the Alertas inbox (phone-inbox T5).
 *
 * The server's `/api/v1/alerts` list is a stub (`[]`), so this ViewModel reads
 * the inbox instead and polls it every [POLL_INTERVAL_MS] while
 * [AlertsScreen][com.idupi.app.ui.screens.AlertsScreen] is visible. Sending a
 * suggested or edited reply goes through the notification's live RemoteInput
 * action ([ReplyActionRegistry]), never through the PC: the owner's phone is
 * the only device that can answer WhatsApp.
 */
class AlertsViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider,
    private val hasReplyAction: (String) -> Boolean = ReplyActionRegistry::has,
    private val sendReply: (String, String) -> Boolean = { conversationKey, text ->
        runCatching { ReplyActionRegistry.send(IduPiApp.instance, conversationKey, text) }
            .getOrDefault(false)
    },
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

    private var pollingJob: Job? = null

    /** Items matching the active [filter], derived for the screen. */
    val visibleItems: List<InboxItem>
        get() = filterItems(_items.value, _filter.value)

    init {
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

    private fun sendReplyText(item: InboxItem, text: String) {
        if (text.isEmpty()) {
            _errorMessage.value = "Escribí un texto antes de enviar"
            return
        }
        val conversationKey = conversationKeyFor(item)
        if (!hasReplyAction(conversationKey)) {
            _errorMessage.value =
                "La notificación original ya no está disponible. Respondé desde la app."
            return
        }
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
                _errorMessage.value =
                    "No se pudo enviar la respuesta. Respondé desde la app original."
                return@launch
            }
            try {
                client.setInboxItemState(item.id, InboxItemState.REPLIED)
                publish(_items.value.map { if (it.id == item.id) it.copy(state = InboxItemState.REPLIED) else it })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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

    fun triggerTestNotification(context: Context) {
        val testAlert = SupervisorAlert(
            id = "test-${System.currentTimeMillis()}",
            title = "Alerta de prueba",
            description = "El sistema de notificaciones de IDUPI funciona correctamente. Recibirás alertas en tiempo real.",
            severity = AlertSeverity.WARNING,
            category = AlertCategory.GOVERNANCE_GATE,
            timestamp = "Ahora",
            isRead = false,
            actionRequired = null
        )
        SupervisorNotificationManager(context).showNotification(testAlert)
    }

    private fun load(reportErrors: Boolean, showLoading: Boolean) {
        viewModelScope.launch {
            if (showLoading) _isLoading.value = true
            try {
                publish(client.getInboxItems(PAGE_SIZE))
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

    private fun changeState(item: InboxItem, state: String, failureMessage: String) {
        viewModelScope.launch {
            try {
                client.setInboxItemState(item.id, state)
                publish(_items.value.map { if (it.id == item.id) it.copy(state = state) else it })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set inbox item ${item.id} to $state", e)
                _errorMessage.value = "$failureMessage: ${e.localizedMessage}"
            }
        }
    }

    private fun publish(items: List<InboxItem>) {
        _items.value = items
        _unreadCount.value = items.count {
            it.state == InboxItemState.OPEN && it.importance == InboxImportance.HIGH
        }
    }

    companion object {
        private const val TAG = "AlertsViewModel"

        /** Items requested per poll; the server returns newest first. */
        const val PAGE_SIZE = 50

        private const val POLL_INTERVAL_MS = 10_000L

        fun conversationKeyFor(item: InboxItem): String =
            inboxConversationKey(item.app, item.conversation)

        fun filterItems(items: List<InboxItem>, filter: InboxFilter): List<InboxItem> =
            when (filter) {
                InboxFilter.PENDING -> items.filter { it.state == InboxItemState.OPEN }
                InboxFilter.ALL -> items
                InboxFilter.IMPORTANT -> items.filter { it.importance == InboxImportance.HIGH }
            }
    }
}
