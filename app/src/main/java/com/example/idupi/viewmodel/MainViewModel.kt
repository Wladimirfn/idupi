package com.example.idupi.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.idupi.data.IduPiClientProvider
import com.example.idupi.domain.model.ServerStatus
import com.example.idupi.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider
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
