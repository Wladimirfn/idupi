package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.domain.model.OrchestratorStatus
import com.idupi.app.domain.model.ProviderModelItem
import com.idupi.app.domain.model.SddProfileItem
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OrchestratorTab(val title: String) {
    SDD_PHASES("Fases SDD"),
    OPENCODE_MODELS("Modelos OpenCode"),
    CLAUDE_MODELS("Modelos Claude"),
    SDD_PROFILES("Perfiles SDD"),
    ECOSYSTEM_TOOLS("Herramientas & Sinc")
}

class OrchestratorViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider
) : ViewModel() {

    private val client get() = clientSource.client

    private val _status = MutableStateFlow<OrchestratorStatus?>(null)
    val status: StateFlow<OrchestratorStatus?> = _status.asStateFlow()

    private val _activeTab = MutableStateFlow(OrchestratorTab.SDD_PHASES)
    val activeTab: StateFlow<OrchestratorTab> = _activeTab.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isActionRunning = MutableStateFlow(false)
    val isActionRunning: StateFlow<Boolean> = _isActionRunning.asStateFlow()

    private val _actionOutput = MutableStateFlow<String?>(null)
    val actionOutput: StateFlow<String?> = _actionOutput.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _providerModels = MutableStateFlow<Map<String, List<ProviderModelItem>>>(emptyMap())
    val providerModels: StateFlow<Map<String, List<ProviderModelItem>>> = _providerModels.asStateFlow()

    init {
        refreshStatus()
    }

    fun selectTab(tab: OrchestratorTab) {
        _activeTab.value = tab
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
