package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.inbox.AndroidInstalledApps
import com.idupi.app.data.inbox.InboxCaptureController
import com.idupi.app.data.inbox.InboxCaptureGateway
import com.idupi.app.data.inbox.InboxCaptureState
import com.idupi.app.data.inbox.InboxScanResult
import com.idupi.app.data.inbox.InstalledAppInventory
import com.idupi.app.data.inbox.InstalledAppsSource
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.data.settings.SettingsRepository
import com.idupi.app.domain.model.InboxSettings
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Agent/model/effort the PC uses to analyze inbox messages, plus the
 * screen's save/load feedback (phone-inbox T5).
 */
data class InboxAgentConfigState(
    val agent: String = DEFAULT_AGENT,
    val model: String = "",
    val effort: String = DEFAULT_EFFORT,
    val agents: List<String> = DEFAULT_AGENTS,
    val efforts: List<String> = DEFAULT_EFFORTS,
    /** Model ids cached from the server; only meaningful for opencode. */
    val models: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingModels: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
) {
    companion object {
        const val DEFAULT_AGENT = "opencode"
        const val DEFAULT_EFFORT = "max"

        /** Mirrors the server's supported agents; used until GET /config answers. */
        val DEFAULT_AGENTS = listOf("opencode", "claude", "codex", "pi")

        /** Mirrors `EFFORTS` in idupi-server/lib/inbox-agent.mjs. */
        val DEFAULT_EFFORTS = listOf("low", "medium", "high", "max")

        /** The only agent with a server-cached model catalog. */
        const val OPENCODE = "opencode"
    }
}

/**
 * State of the "Leer notificaciones ahora" action (inbox capture fix T1).
 * [scanMessage] is the short result under the button.
 */
data class InboxCaptureActionState(
    val isScanning: Boolean = false,
    val scanMessage: String? = null,
)

/**
 * Installed launchable apps offered in Ajustes > Alertas (phone-inbox T3).
 * [inventory] splits presets (top) from the rest of the phone; both groups are
 * already sorted by label.
 */
data class InstalledAppsState(
    val inventory: InstalledAppInventory = InstalledAppInventory(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** "Ver todas las apps" panel; collapsed by default (phone-calendar T5). */
    val expanded: Boolean = false,
)

/**
 * Ajustes > Alertas (phone-inbox T5): listener access is a platform check the
 * screen owns, while everything else lives here.
 *
 * Phone-side capture/reminder/auto-reply choices are persisted through
 * [SettingsRepository] (DataStore in production, in-memory in tests); the
 * agent/model/effort lives on the PC and is read/saved through the client, so
 * it is loaded only when the section appears instead of at app start.
 *
 * Capture reliability (inbox capture fix T1): [captureStatus] mirrors the
 * process-wide listener diagnostics and [scanNow] asks the connected listener
 * to read the notifications already in the shade plus drain the retry queue.
 * The gateway is injected so both are tested with a fake.
 *
 * Followed apps (phone-inbox T3): [installedApps] is the launchable inventory,
 * loaded through an injected [InstalledAppsSource]; [setAppFollowed] persists
 * a preset id or a package name in the same DataStore key as before.
 */
class InboxSettingsViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider,
    private val settingsRepository: SettingsRepository = SettingsProvider.repository,
    private val captureGateway: InboxCaptureGateway = InboxCaptureController,
    private val installedAppsSource: InstalledAppsSource = AndroidInstalledApps,
) : ViewModel() {

    private val client get() = clientSource.client

    val inboxSettings: StateFlow<InboxSettings> = settingsRepository.inboxSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, InboxSettings())

    /** Live listener/upload/retry state owned by the capture layer. */
    val captureStatus: StateFlow<InboxCaptureState> = captureGateway.status

    private val _captureAction = MutableStateFlow(InboxCaptureActionState())
    val captureAction: StateFlow<InboxCaptureActionState> = _captureAction.asStateFlow()

    private val _agentConfig = MutableStateFlow(InboxAgentConfigState())
    val agentConfig: StateFlow<InboxAgentConfigState> = _agentConfig.asStateFlow()

    private val _installedApps = MutableStateFlow(InstalledAppsState())
    val installedApps: StateFlow<InstalledAppsState> = _installedApps.asStateFlow()

    private var configLoaded = false

    private var installedAppsLoaded = false

    /**
     * Reads the PC's current agent configuration. The section calls this from
     * a `LaunchedEffect`, so a repeated recomposition does not refetch; pass
     * [force] after a successful save or for an explicit reload.
     */
    fun loadConfig(force: Boolean = false) {
        if (_agentConfig.value.isLoading) return
        if (!force && configLoaded) return
        viewModelScope.launch {
            _agentConfig.update {
                it.copy(isLoading = true, errorMessage = null, successMessage = null)
            }
            try {
                val config = client.getInboxConfig()
                configLoaded = true
                _agentConfig.update { state ->
                    state.copy(
                        agent = config.agent.ifBlank { state.agent },
                        model = config.model,
                        effort = config.effort.ifBlank { state.effort },
                        agents = config.agents.ifEmpty { InboxAgentConfigState.DEFAULT_AGENTS },
                        efforts = config.efforts.ifEmpty { InboxAgentConfigState.DEFAULT_EFFORTS },
                        isLoading = false
                    )
                }
                loadModels(_agentConfig.value.agent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load inbox config", e)
                _agentConfig.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "No se pudo cargar la configuración: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun setAgent(agent: String) {
        _agentConfig.update { it.copy(agent = agent) }
        if (agent != InboxAgentConfigState.OPENCODE) {
            _agentConfig.update { it.copy(models = emptyList(), isLoadingModels = false) }
            return
        }
        loadModels(agent)
    }

    fun setModel(model: String) {
        _agentConfig.update { it.copy(model = model) }
    }

    fun setEffort(effort: String) {
        _agentConfig.update { it.copy(effort = effort) }
    }

    fun clearFeedback() {
        _agentConfig.update { it.copy(errorMessage = null, successMessage = null) }
    }

    // --- Capture reliability (inbox capture fix T1) ---

    /** Refreshes the pending retry count shown in Ajustes. */
    fun refreshCaptureStatus() {
        viewModelScope.launch {
            try {
                captureGateway.refreshPendingCount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not refresh inbox capture status", e)
            }
        }
    }

    /**
     * Asks the connected listener to read the shade and retry queued uploads.
     * [InboxCaptureActionState.scanMessage] is the short result the owner sees.
     */
    fun scanNow() {
        if (_captureAction.value.isScanning) return
        _captureAction.update { it.copy(isScanning = true, scanMessage = null) }
        viewModelScope.launch {
            val message = try {
                when (val result = captureGateway.scanNow()) {
                    is InboxScanResult.Completed -> scanResultMessage(result.sent)
                    InboxScanResult.NotConnected -> "Lector no conectado"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Inbox capture scan failed", e)
                "No se pudo leer las notificaciones"
            }
            _captureAction.update { it.copy(isScanning = false, scanMessage = message) }
        }
    }

    private fun scanResultMessage(sent: Int): String =
        if (sent == 1) "1 notificación enviada" else "$sent notificaciones enviadas"

    fun saveConfig() {
        val state = _agentConfig.value
        if (state.isSaving) return

        val agent = state.agent.trim()
        val model = state.model.trim()
        val effort = state.effort.trim()
        if (agent.isEmpty() || model.isEmpty() || effort.isEmpty()) {
            _agentConfig.update {
                it.copy(
                    errorMessage = "Completa agente, modelo y esfuerzo",
                    successMessage = null
                )
            }
            return
        }

        viewModelScope.launch {
            _agentConfig.update {
                it.copy(isSaving = true, errorMessage = null, successMessage = null)
            }
            try {
                val config = client.updateInboxConfig(agent, model, effort)
                _agentConfig.update {
                    it.copy(
                        agent = config.agent.ifBlank { agent },
                        model = config.model.ifBlank { model },
                        effort = config.effort.ifBlank { effort },
                        agents = config.agents.ifEmpty { it.agents },
                        efforts = config.efforts.ifEmpty { it.efforts },
                        isSaving = false,
                        successMessage = "Configuración guardada"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save inbox config", e)
                _agentConfig.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "No se pudo guardar la configuración: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    // --- Phone-side settings, persisted immediately ---

    /**
     * Reads the phone's launchable apps for "Apps a seguir". The section calls
     * this from a `LaunchedEffect`, so a recomposition does not requery; pass
     * [force] after an install or for an explicit reload.
     */
    fun loadInstalledApps(force: Boolean = false) {
        if (_installedApps.value.isLoading) return
        if (!force && installedAppsLoaded) return
        viewModelScope.launch {
            _installedApps.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val inventory = installedAppsSource.load()
                installedAppsLoaded = true
                _installedApps.value = InstalledAppsState(inventory = inventory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not list installed apps", e)
                _installedApps.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "No se pudieron leer las apps instaladas"
                    )
                }
            }
        }
    }

    fun setAppFollowed(appId: String, followed: Boolean) {
        viewModelScope.launch {
            // Read the latest stored set inside the coroutine: two quick
            // toggles must not both start from the same stale snapshot.
            val current = settingsRepository.inboxSettings.first().followedApps
            val updated = if (followed) current + appId else current - appId
            settingsRepository.setInboxFollowedApps(updated)
        }
    }

    /**
     * Collapsed "Apps a seguir" (phone-calendar T5): the card shows only the
     * followed apps until the owner asks for the full installed list.
     */
    fun toggleAppsExpanded() {
        _installedApps.update { it.copy(expanded = !it.expanded) }
    }

    /** Removes (or restores) one conversation from the silenced list. */
    fun setConversationSilenced(conversationKey: String, silenced: Boolean) {
        if (conversationKey.isBlank()) return
        viewModelScope.launch {
            val current = settingsRepository.inboxSettings.first().silencedConversations
            settingsRepository.setInboxSilencedConversations(
                if (silenced) current + conversationKey else current - conversationKey
            )
        }
    }

    fun setReminderInterval(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.setInboxReminderIntervalMinutes(minutes)
        }
    }

    fun setRemindMinImportance(importance: String) {
        viewModelScope.launch {
            settingsRepository.setInboxRemindMinImportance(importance)
        }
    }

    fun setAutoReplyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setInboxAutoReplyEnabled(enabled)
        }
    }

    fun setAutoReplyDelay(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.setInboxAutoReplyDelayMinutes(minutes)
        }
    }

    fun setAutoReplyTemplate(template: String) {
        viewModelScope.launch {
            settingsRepository.setInboxAutoReplyTemplate(template)
        }
    }

    /** Fetches the opencode model catalog for the dropdown; failure keeps free text. */
    private fun loadModels(agent: String) {
        if (agent != InboxAgentConfigState.OPENCODE) return
        viewModelScope.launch {
            _agentConfig.update { it.copy(isLoadingModels = true) }
            try {
                val models = client.getInboxModels(agent)
                _agentConfig.update { it.copy(models = models, isLoadingModels = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load inbox models", e)
                // The catalog is a convenience: keep the free-text field.
                _agentConfig.update { it.copy(models = emptyList(), isLoadingModels = false) }
            }
        }
    }

    companion object {
        private const val TAG = "InboxSettingsVM"
    }
}
