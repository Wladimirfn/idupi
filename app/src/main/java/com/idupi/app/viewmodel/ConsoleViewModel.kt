package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.domain.model.ChatEvent
import com.idupi.app.domain.repository.IduPiClientSource
import com.idupi.app.domain.repository.TerminalSessionItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConsoleViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider
) : ViewModel() {

    private val client get() = clientSource.client

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _terminals = MutableStateFlow<List<TerminalSessionItem>>(emptyList())
    val terminals = _terminals.asStateFlow()

    private val _selectedTerminalId = MutableStateFlow<String>("pi-cli")
    val selectedTerminalId = _selectedTerminalId.asStateFlow()

    private val _activeTask = MutableStateFlow<String?>("Ninguna")
    val activeTask = _activeTask.asStateFlow()

    private val _queueSize = MutableStateFlow(0)
    val queueSize = _queueSize.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting = _isExecuting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    private var pollingJob: Job? = null

    init {
        refreshTerminals()
        observeLogs()
    }

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val terminalLogs = client.getTerminalLogs(_selectedTerminalId.value)
                    _logs.value = terminalLogs
                } catch (e: Exception) {
                    // Ignore transient network errors during polling
                }
                delay(1500)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun refreshTerminals() {
        viewModelScope.launch {
            try {
                val list = client.getTerminals()
                _terminals.value = list
                if (list.none { it.id == _selectedTerminalId.value } && list.isNotEmpty()) {
                    _selectedTerminalId.value = list.first().id
                }
                loadLogsForSelectedTerminal()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh terminals", e)
                _errorMessage.value = "No se pudieron cargar las terminales: ${e.localizedMessage}"
            }
        }
    }

    fun selectTerminal(id: String) {
        _selectedTerminalId.value = id
        loadLogsForSelectedTerminal()
    }

    fun loadLogsForSelectedTerminal() {
        viewModelScope.launch {
            try {
                val terminalLogs = client.getTerminalLogs(_selectedTerminalId.value)
                _logs.value = terminalLogs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load logs for terminal ${_selectedTerminalId.value}", e)
                _errorMessage.value = "No se pudieron cargar los logs: ${e.localizedMessage}"
            }
        }
    }

    fun spawnNewTerminal(customName: String? = null) {
        viewModelScope.launch {
            addLog("[System] Creando nueva sesión de terminal PowerShell en tu PC...")
            try {
                val newTerm = client.spawnTerminal(customName)
                if (newTerm != null) {
                    refreshTerminals()
                    selectTerminal(newTerm.id)
                    addLog("[System] Terminal '${newTerm.name}' creada exitosamente.")
                } else {
                    addLog("[ERROR] No se pudo crear la nueva terminal.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to spawn terminal", e)
                addLog("[ERROR] No se pudo crear la nueva terminal: ${e.localizedMessage}")
            }
        }
    }

    fun sendTerminalCommand(command: String) {
        if (command.isBlank()) return
        viewModelScope.launch {
            _isExecuting.value = true
            addLog("\$ $command")
            try {
                val result = client.sendTerminalCommand(_selectedTerminalId.value, command)
                if (result.isNotBlank() && !result.startsWith("Comando enviado")) {
                    addLog(result)
                }
                loadLogsForSelectedTerminal()
            } catch (e: Exception) {
                addLog("[ERROR] Falló ejecución: ${e.localizedMessage}")
            } finally {
                _isExecuting.value = false
            }
        }
    }

    fun restartServer() {
        viewModelScope.launch {
            addLog("[System] Reiniciando servidor/proceso de terminal '${_selectedTerminalId.value}'...")
            try {
                val msg = client.restartTerminalServer(_selectedTerminalId.value)
                addLog("[System] $msg")
                refreshTerminals()
            } catch (e: Exception) {
                addLog("[ERROR] Error al reiniciar: ${e.localizedMessage}")
            }
        }
    }

    fun closeSelectedTerminal() {
        viewModelScope.launch {
            val currentId = _selectedTerminalId.value
            if (currentId == "pi-cli" || currentId == "idupi-server") {
                addLog("[WARNING] No se puede cerrar la terminal principal del sistema.")
                return@launch
            }
            try {
                client.closeTerminal(currentId)
                addLog("[System] Terminal $currentId cerrada.")
                refreshTerminals()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close terminal $currentId", e)
                addLog("[ERROR] No se pudo cerrar la terminal $currentId: ${e.localizedMessage}")
            }
        }
    }

    private fun observeLogs() {
        viewModelScope.launch {
            client.connectChat().collect { event ->
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                when (event) {
                    is ChatEvent.StatusChanged -> {
                        _queueSize.value = event.status.queueSize
                        _activeTask.value = if (event.status.busy) "Procesando en Pi CLI" else "Ninguna"
                    }
                    is ChatEvent.ToolStarted -> {
                        if (_selectedTerminalId.value == "pi-cli") {
                            addLog("[$time] [TOOL] ${event.toolName}: ${event.message}")
                        }
                    }
                    is ChatEvent.ErrorOccurred -> {
                        addLog("[$time] [ERROR] ${event.error}")
                    }
                    else -> {}
                }
            }
        }
    }

    fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedLog = if (message.startsWith("[")) message else "[$time] $message"
        _logs.value = _logs.value + formattedLog
    }

    fun clearLogs() {
        _logs.value = emptyList()
        addLog("Consola limpia.")
    }

    fun cancelTask() {
        viewModelScope.launch {
            try {
                client.cancelCurrentTask()
                addLog("[System] Detención/Cancelación enviada.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel current task", e)
                addLog("[ERROR] No se pudo cancelar la tarea: ${e.localizedMessage}")
            }
        }
    }

    companion object {
        private const val TAG = "ConsoleViewModel"
    }
}
