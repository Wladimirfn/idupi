package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.settings.SettingsProvider
import com.idupi.app.data.settings.SettingsRepository
import com.idupi.app.domain.model.ServerStatus
import com.idupi.app.domain.model.ThemeColors
import com.idupi.app.domain.model.ThemeMode
import com.idupi.app.domain.model.ThemePreset
import com.idupi.app.domain.model.ThemeSettings
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider,
    private val settingsRepository: SettingsRepository = SettingsProvider.repository,
) : ViewModel() {

    private val client get() = clientSource.client

    private val _status = MutableStateFlow<ServerStatus?>(null)
    val status = _status.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Persisted theme choice (T9). AppNavigation resolves it into the palette
     * passed to IDUPITheme, so a change in Ajustes applies app-wide at once;
     * DataStore keeps it across restarts. The initial value is the default
     * theme, replaced by the first persisted emission.
     */
    val themeSettings: StateFlow<ThemeSettings> =
        settingsRepository.themeSettings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ThemeSettings(),
        )

    fun clearError() {
        _errorMessage.value = null
    }

    init {
        refreshStatus()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setThemePreset(preset: ThemePreset) {
        viewModelScope.launch { settingsRepository.setThemePreset(preset) }
    }

    fun setCustomThemeColors(colors: ThemeColors) {
        viewModelScope.launch { settingsRepository.setCustomColors(colors) }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            try {
                _status.value = client.getStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh server status", e)
                _errorMessage.value = "No se pudo cargar el estado: ${e.localizedMessage}"
            }
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
