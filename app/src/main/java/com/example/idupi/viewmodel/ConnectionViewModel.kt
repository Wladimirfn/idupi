package com.example.idupi.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.idupi.data.IduPiClientProvider
import com.example.idupi.data.connection.ConnectionStorage
import com.example.idupi.data.remote.RealIduPiClient
import com.example.idupi.domain.model.ConnectionMode
import com.example.idupi.domain.model.ConnectionProfile
import com.example.idupi.domain.model.TransportType
import com.example.idupi.domain.model.sanitizeAuthToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Deliberately does NOT take an [com.example.idupi.domain.repository.IduPiClientSource].
 * This screen configures a connection that does not exist yet, so [testConnection]
 * builds a throwaway client aimed at the candidate host rather than reusing the
 * app-wide one.
 */
class ConnectionViewModel : ViewModel() {

    private val _profileName = MutableStateFlow("Mi Servidor Principal")
    val profileName = _profileName.asStateFlow()

    private val _connectionMode = MutableStateFlow(ConnectionMode.TAILSCALE)
    val connectionMode = _connectionMode.asStateFlow()

    private val _transportType = MutableStateFlow(TransportType.AUTO)
    val transportType = _transportType.asStateFlow()

    private val _host = MutableStateFlow("")
    val host = _host.asStateFlow()

    private val _port = MutableStateFlow("8788")
    val port = _port.asStateFlow()

    private val _token = MutableStateFlow("")
    val token = _token.asStateFlow()

    private val _useHttps = MutableStateFlow(false)
    val useHttps = _useHttps.asStateFlow()

    private val _testingConnection = MutableStateFlow(false)
    val testingConnection = _testingConnection.asStateFlow()

    private val _testSuccess = MutableStateFlow<Boolean?>(null)
    val testSuccess = _testSuccess.asStateFlow()

    private val _testErrorMessage = MutableStateFlow<String?>(null)
    val testErrorMessage = _testErrorMessage.asStateFlow()

    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess = _saveSuccess.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    fun updateName(value: String) { _profileName.value = value }
    fun updateMode(value: ConnectionMode) { _connectionMode.value = value }
    fun updateTransport(value: TransportType) { _transportType.value = value }
    fun updateHost(value: String) { _host.value = value }
    fun updatePort(value: String) { _port.value = value }
    // Sanitized on input so the cleaned value is what gets stored, not just what
    // gets sent. A pasted token routinely carries invisible characters.
    fun updateToken(value: String) { _token.value = sanitizeAuthToken(value) }
    fun updateUseHttps(value: Boolean) { _useHttps.value = value }

    fun loadProfile(context: Context) {
        val storage = ConnectionStorage(context)
        val saved = storage.getProfile()
        if (saved != null) {
            _profileName.value = saved.name
            _connectionMode.value = saved.mode
            _transportType.value = saved.transportType
            _host.value = saved.host
            _port.value = saved.port.toString()
            _token.value = saved.token
            _useHttps.value = saved.useHttps
        } else {
            _host.value = "10.0.2.2" // IP estándar de host en emulador Android
        }
    }

    /**
     * Prueba REAL de conexión HTTP contra el servidor en el host y puerto especificados.
     */
    fun testConnection() {
        viewModelScope.launch {
            _testingConnection.value = true
            _testSuccess.value = null
            _testErrorMessage.value = null

            val targetHost = _host.value.ifBlank { "10.0.2.2" }
            val portInt = _port.value.toIntOrNull() ?: 8788

            val tempClient = RealIduPiClient()
            tempClient.configure(
                host = targetHost,
                port = portInt,
                token = _token.value,
                useHttps = _useHttps.value
            )

            try {
                val status = tempClient.getStatus()
                if (status.connected) {
                    _testSuccess.value = true
                    _testErrorMessage.value = null
                } else {
                    _testSuccess.value = false
                    _testErrorMessage.value = status.cliTask
                }
            } catch (e: Exception) {
                _testSuccess.value = false
                _testErrorMessage.value = "Error de red: ${e.localizedMessage}"
            } finally {
                _testingConnection.value = false
            }
        }
    }

    fun saveProfile(context: Context) {
        val portInt = _port.value.toIntOrNull() ?: 8788
        val profile = ConnectionProfile(
            name = _profileName.value,
            mode = _connectionMode.value,
            host = _host.value.ifBlank { "10.0.2.2" },
            port = portInt,
            token = _token.value,
            useHttps = _useHttps.value,
            transportType = _transportType.value
        )
        val fullySaved = try {
            ConnectionStorage(context).saveProfile(profile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist connection profile", e)
            _errorMessage.value = "No se pudo guardar el perfil: ${e.localizedMessage}"
            return
        }

        if (!fullySaved) {
            // The profile was stored but the token could not be encrypted, so it
            // was dropped. Reporting success here would be a lie.
            Log.w(TAG, "Connection profile saved without its token: encryption failed")
            _errorMessage.value =
                "El perfil se guardó, pero el token no pudo cifrarse. Volvé a ingresarlo."
            return
        }

        IduPiClientProvider.configureRealClient(profile)
        _saveSuccess.value = true
    }

    private companion object {
        const val TAG = "ConnectionViewModel"
    }

    fun resetSaveStatus() {
        _saveSuccess.value = false
    }
}
