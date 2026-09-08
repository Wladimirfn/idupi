package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.settings.InMemorySettingsRepository
import com.idupi.app.data.settings.SettingsRepository
import com.idupi.app.domain.model.ServerStatus
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
    private val settingsRepository: SettingsRepository = InMemorySettingsRepository(),
) : ViewModel() {

    private val client get() = clientSource.client

    private val _status = MutableStateFlow<ServerStatus?>(null)
    val status = _status.asStateFlow()

    // Global customization states
    private val _darkThemeEnabled = MutableStateFlow(true)
    val darkThemeEnabled = _darkThemeEnabled.asStateFlow()

    private val _selectedWallpaper = MutableStateFlow("Telegram")
    val selectedWallpaper = _selectedWallpaper.asStateFlow()

    // Notification states
    private val _notificationsMuted = MutableStateFlow(false)
    val notificationsMuted = _notificationsMuted.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * PR 3 / Tasks 4.2 + 4.3 wire-up: the OpenCode auto-approve toggle
     * surfaced on Settings ("Aprobación automática"). Default `false` means
     * the spawn path uses the sidecar and surfaces permission cards; `true`
     * re-enables legacy `opencode run --auto` (autopilot).
     *
     * The SettingsRepository is the source of truth across process restarts
     * (DataStore-backed); this StateFlow is the source of truth within a
     * single process so the SettingsScreen Switch stays in sync with the
     * chat header the server reads.
     */
    val opencodeAutoApprove: StateFlow<Boolean> =
        settingsRepository.opencodeAutoApprove.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    fun clearError() {
        _errorMessage.value = null
    }

    init {
        refreshStatus()
    }

    fun toggleDarkTheme(value: Boolean) {
        _darkThemeEnabled.value = value
    }

    fun setWallpaper(wallpaperName: String) {
        _selectedWallpaper.value = wallpaperName
    }

    fun toggleNotificationsMuted(value: Boolean) {
        _notificationsMuted.value = value
    }

    /**
     * PR 3 / Task 4.3 wire-up: persists the user's OpenCode auto-approve
     * choice AND pushes the new value to `IduPiClientProvider` so the next
     * `/api/v1/chat/message` POST carries the matching `X-OpenCode-Auto-Approve`
     * header. Without the provider push, the SettingsScreen and the chat
     * header would silently drift apart on the first toggle.
     */
    fun setOpencodeAutoApprove(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOpencodeAutoApprove(value)
            IduPiClientProvider.setOpencodeAutoApprove(value)
        }
    }

    fun selectEngine(engineId: String) {
        viewModelScope.launch {
            try {
                client.selectEngine(engineId)
                refreshStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to select engine $engineId", e)
                _errorMessage.value = "No se pudo seleccionar el motor: ${e.localizedMessage}"
            }
        }
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
