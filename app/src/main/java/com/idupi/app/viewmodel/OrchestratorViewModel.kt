package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.domain.model.ClaudePhaseConfig
import com.idupi.app.domain.model.OpenCodeModelAssignment
import com.idupi.app.domain.model.OrchestratorStatus
import com.idupi.app.domain.model.PiPhaseConfig
import com.idupi.app.domain.model.ProviderModelItem
import com.idupi.app.domain.model.SddProfileItem
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ONE shared function-tab set driven by the top motor selector — owner-confirmed
 * vision (see memory `idupi/orchestrator-ui-shared-tabs`). Selecting an engine
 * does NOT swap which tabs exist; the active engine drives WHICH data populates
 * the shared tabs beneath it. This was a deliberate refinement away from
 * per-engine tab swaps like `MODELS_OPENCODE` / `MODELS_CLAUDE`.
 */
enum class OrchestratorTab(val title: String) {
    FASES("Fases"),
    MODELOS("Modelos"),
    PERFILES("Perfiles"),
    HERRAMIENTAS("Herramientas")
}

/**
 * Supported engines, kept as constants rather than free strings so the UI
 * cannot drift away from the contract. The orchestrator-server accepts `pi`,
 * `pi-cli`, `opencode`, `claude` (and the aliases resolve to the canonical
 * engine id); these constants are the canonical Android-side names.
 */
object OrchestratorEngine {
    const val PI = "pi"
    const val OPENCODE = "opencode"
    const val CLAUDE = "claude"

    /** All engines the UI can offer the user, in display order. */
    val ALL: List<String> = listOf(PI, OPENCODE, CLAUDE)

    fun isKnown(engine: String): Boolean = engine in ALL
}

class OrchestratorViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider
) : ViewModel() {

    private val client get() = clientSource.client

    private val _status = MutableStateFlow<OrchestratorStatus?>(null)
    val status: StateFlow<OrchestratorStatus?> = _status.asStateFlow()

    /**
     * Currently active engine driving the shared function tabs. The motor
     * selector chips mutate this; selecting an unknown engine is a no-op
     * (we keep the last known good engine rather than silently falling back,
     * so the UI keeps rendering against valid data).
     */
    private val _activeEngine = MutableStateFlow(OrchestratorEngine.OPENCODE)
    val activeEngine: StateFlow<String> = _activeEngine.asStateFlow()

    private val _activeTab = MutableStateFlow(OrchestratorTab.FASES)
    val activeTab: StateFlow<OrchestratorTab> = _activeTab.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isActionRunning = MutableStateFlow(false)
    val isActionRunning: StateFlow<Boolean> = _isActionRunning.asStateFlow()

    private val _actionOutput = MutableStateFlow<String?>(null)
    val actionOutput: StateFlow<String?> = _actionOutput.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Live `gentleAiDetected` mirror of [OrchestratorStatus.gentleAiDetected],
     * kept as its own StateFlow so the UI banner does not need to introspect
     * the (possibly null) status envelope. Refreshed inside [refreshStatus].
     */
    private val _gentleAiDetected = MutableStateFlow(false)
    val gentleAiDetected: StateFlow<Boolean> = _gentleAiDetected.asStateFlow()

    private val _providerModels = MutableStateFlow<Map<String, List<ProviderModelItem>>>(emptyMap())
    val providerModels: StateFlow<Map<String, List<ProviderModelItem>>> = _providerModels.asStateFlow()

    init {
        refreshStatus()
    }

    fun selectTab(tab: OrchestratorTab) {
        _activeTab.value = tab
    }

    /**
     * Switches the active engine. Unknown ids are ignored on purpose so the
     * motor selector cannot drift into a contract the server does not honour.
     * The shared tabs beneath remain valid against the new selection.
     * Also notifies the server (universal, works for any Pi/OpenCode/Claude install)
     * so that getAvailableModels() and session handling stay in sync with the UI.
     */
    fun selectEngine(engine: String) {
        if (!OrchestratorEngine.isKnown(engine)) {
            Log.w(TAG, "Ignored unknown engine id: $engine")
            return
        }
        _activeEngine.value = engine
        // Sync with server - universal, not hardcoded to this machine's path
        viewModelScope.launch {
            try {
                client.selectEngine(engine)
                refreshStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync engine selection to server: $engine", e)
                // Keep local selection even if server sync fails - UI stays responsive
            }
        }
    }

    /**
     * Syncs local activeEngine with server's activeEngine (called after
     * session resume or status refresh). Ensures Pi/OpenCode/Claude each
     * show their own models independently, as before.
     */
    fun syncEngineFromStatus(status: OrchestratorStatus?) {
        val serverEngine = status?.activeEngine?.let { raw ->
            when {
                raw == "pi-cli" -> OrchestratorEngine.PI
                OrchestratorEngine.isKnown(raw) -> raw
                else -> null
            }
        }
        if (serverEngine != null && serverEngine != _activeEngine.value) {
            _activeEngine.value = serverEngine
        }
    }

    /**
     * Local-only engine sync (NO server write), used when the app already
     * knows the engine from the session being resumed (threaded through
     * navigation) so the orchestrator selector flips immediately instead of
     * waiting for a status round-trip. Mirrors the canonical-id mapping in
     * [syncEngineFromStatus]; an unknown/null engine is a no-op.
     */
    fun syncEngine(engine: String?) {
        val canonical = when {
            engine == "pi-cli" -> OrchestratorEngine.PI
            engine != null && OrchestratorEngine.isKnown(engine) -> engine
            else -> null
        }
        if (canonical != null && canonical != _activeEngine.value) {
            _activeEngine.value = canonical
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearActionOutput() {
        _actionOutput.value = null
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val st = client.getOrchestratorStatus()
                _status.value = st
                _gentleAiDetected.value = st.gentleAiDetected
                syncEngineFromStatus(st)
                // Preload models for configured providers
                st.providers.take(3).forEach { prov ->
                    loadProviderModels(prov)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load orchestrator status", e)
                _errorMessage.value = "No se pudo cargar el orquestador: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Returns the Pi (`~/.pi/subagents.json`) per-phase assignments. The
     * active engine decides whether the UI shows this map or one of the
     * other engines' maps; this helper stays engine-specific so call sites
     * can rely on the typed shape.
     */
    fun piAssignmentsFor(status: OrchestratorStatus?): Map<String, PiPhaseConfig> {
        return status?.piPhaseAssignments ?: emptyMap()
    }

    /** Typed helper for the OpenCode engine view (engine-aware). */
    fun openCodeAssignmentsFor(status: OrchestratorStatus?): Map<String, OpenCodeModelAssignment> {
        return status?.modelAssignments ?: emptyMap()
    }

    /** Typed helper for the Claude engine view (engine-aware). */
    fun claudeAssignmentsFor(status: OrchestratorStatus?): Map<String, ClaudePhaseConfig> {
        return status?.claudePhaseAssignments ?: emptyMap()
    }

    fun loadProviderModels(providerId: String) {
        if (_providerModels.value.containsKey(providerId) && _providerModels.value[providerId]?.isNotEmpty() == true) {
            return
        }
        viewModelScope.launch {
            try {
                val models = client.getProviderModels(providerId)
                _providerModels.value = _providerModels.value + (providerId to models)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load models for provider $providerId", e)
            }
        }
    }

    fun updateModel(
        engine: String,
        phase: String,
        modelId: String,
        providerId: String? = null,
        effort: String? = null,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val ok = client.updateOrchestratorModel(engine, phase, modelId, providerId, effort)
                if (ok) {
                    refreshStatus()
                    onSuccess()
                } else {
                    _errorMessage.value = "No se pudo actualizar el modelo para $phase"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update model assignment", e)
                _errorMessage.value = "Error al actualizar modelo: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun applyProfile(profileId: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val ok = client.applySddProfile(profileId)
                if (ok) {
                    refreshStatus()
                    onSuccess()
                } else {
                    _errorMessage.value = "No se pudo aplicar el perfil SDD '$profileId'"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply profile", e)
                _errorMessage.value = "Error al aplicar perfil: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveProfile(profile: SddProfileItem, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val ok = client.saveSddProfile(profile)
                if (ok) {
                    refreshStatus()
                    onSuccess()
                } else {
                    _errorMessage.value = "No se pudo guardar el perfil '${profile.name}'"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save profile", e)
                _errorMessage.value = "Error al guardar perfil: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteProfile(profileId: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val ok = client.deleteSddProfile(profileId)
                if (ok) {
                    refreshStatus()
                    onSuccess()
                } else {
                    _errorMessage.value = "No se pudo eliminar el perfil '$profileId'"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete profile", e)
                _errorMessage.value = "Error al eliminar perfil: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun runAction(action: String) {
        viewModelScope.launch {
            _isActionRunning.value = true
            _actionOutput.value = "Ejecutando '$action' en tu PC a través de Gentle-AI..."
            try {
                val res = client.runOrchestratorAction(action)
                _actionOutput.value = res.output.ifBlank { "Acción completada con éxito." }
                refreshStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to execute orchestrator action $action", e)
                _actionOutput.value = "Error al ejecutar acción: ${e.localizedMessage}"
            } finally {
                _isActionRunning.value = false
            }
        }
    }

    companion object {
        private const val TAG = "OrchestratorViewModel"
    }
}