package com.example.idupi.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.idupi.data.IduPiClientProvider
import com.example.idupi.domain.model.*
import com.example.idupi.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider
) : ViewModel() {

    private val client get() = clientSource.client

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming = _isStreaming.asStateFlow()

    private val _activeTool = MutableStateFlow<String?>(null)
    val activeTool = _activeTool.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking = _isThinking.asStateFlow()

    private val _activeUiRequest = MutableStateFlow<UiRequest?>(null)
    val activeUiRequest = _activeUiRequest.asStateFlow()

    // Subagentes en ejecución y completados
    private val _subagents = MutableStateFlow<List<SubagentLiveState>>(emptyList())
    val subagents = _subagents.asStateFlow()

    /**
     * Live CLI/MCP operations, correlated by the server's stable activity id.
     * Terminal entries are kept so the chat can still show what ran and how it
     * finished; the server tombstones its own copy after two minutes.
     */
    private val _activities = MutableStateFlow<List<ActivityUiState>>(emptyList())
    val activities = _activities.asStateFlow()

    private val _selectedSubagent = MutableStateFlow<SubagentLiveState?>(null)
    val selectedSubagent = _selectedSubagent.asStateFlow()

    fun selectSubagent(subagent: SubagentLiveState?) {
        _selectedSubagent.value = subagent
    }

    fun dismissSubagentConsole() {
        _selectedSubagent.value = null
    }

    // Sync available commands based on active project
    private val _availableCommands = MutableStateFlow<List<QuickCommand>>(emptyList())
    val availableCommands = _availableCommands.asStateFlow()

    private val _availableModels = MutableStateFlow<List<com.example.idupi.domain.repository.AiModelItem>>(emptyList())
    val availableModels = _availableModels.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    init {
        _messages.value = listOf(greetingMessage(null))
        observeChatEvents()
        refreshCommands()
        refreshModels()
    }

    private fun greetingMessage(activeEngine: String?) =
        ChatMessage(sender = MessageSender.PI, text = greetingFor(activeEngine))

    /**
     * Rewrites the opening greeting once the active engine is known, so a Claude
     * or OpenCode session is not greeted by Pi.
     *
     * Only while the chat is untouched: the greeting is the whole list at that
     * point, and rewriting it after a conversation has started would edit
     * history the user has already read.
     */
    fun applyActiveEngine(activeEngine: String?) {
        val current = _messages.value
        if (current.size != 1) return
        val only = current.first()
        if (only.sender != MessageSender.PI) return
        val greeting = greetingFor(activeEngine)
        if (only.text == greeting) return
        _messages.value = listOf(only.copy(text = greeting))
    }

    fun refreshModels() {
        viewModelScope.launch {
            try {
                _availableModels.value = client.getAvailableModels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load available models", e)
                _errorMessage.value = "No se pudieron cargar los modelos disponibles: ${e.localizedMessage}"
            }
        }
    }

    /**
     * @param onSwitched runs only after the server confirmed the change. The
     *   header reads the model from the server status, which nothing refreshed
     *   after a switch -- so the badge kept showing the previous model even
     *   though the new one was already answering.
     */
    fun switchModel(modelName: String, provider: String? = null, onSwitched: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                client.switchModel(modelName, provider)
                refreshModels()
                onSwitched()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to switch model to $modelName", e)
                _errorMessage.value = "No se pudo cambiar el modelo: ${e.localizedMessage}"
            }
        }
    }

    fun refreshCommands() {
        viewModelScope.launch {
            try {
                _availableCommands.value = client.getAvailableCommands()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load available commands", e)
                _errorMessage.value = "No se pudieron cargar los comandos disponibles: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Applies [transform] to the tracked activity with [id], if there is one.
     * A frame for an id we never saw start is dropped rather than materialising
     * a half-known entry out of it.
     */
    private fun updateActivity(id: String, transform: (ActivityUiState) -> ActivityUiState) {
        if (_activities.value.none { it.id == id }) return
        _activities.value = _activities.value.map { if (it.id == id) transform(it) else it }
    }

    private fun observeChatEvents() {
        viewModelScope.launch {
            client.connectChat().collect { event ->
                when (event) {
                    is ChatEvent.StatusChanged -> {
                        // Global status updated
                    }
                    is ChatEvent.ActivityStarted -> {
                        // The id is the server's correlation key. A repeat for an
                        // id we already track is a duplicate frame, not a second
                        // operation, so it must not open another entry.
                        if (_activities.value.none { it.id == event.id }) {
                            _activities.value = _activities.value + ActivityUiState(
                                id = event.id,
                                streamId = event.streamId,
                                kind = event.kind,
                                name = event.name,
                                server = event.server,
                                detail = event.detail,
                            )
                        }
                    }
                    is ChatEvent.ActivityUpdated -> {
                        // Additive: `server` and `detail` enrich what is already
                        // shown, they never replace the original name or id, and a
                        // null in the frame never erases a value already known.
                        updateActivity(event.id) { current ->
                            current.copy(
                                server = event.server ?: current.server,
                                detail = event.detail ?: current.detail,
                                sinceLastUpdateMs = 0L,
                            )
                        }
                    }
                    is ChatEvent.ActivityHeartbeat -> {
                        // Only meaningful while open: a heartbeat that arrives after
                        // a terminal frame must never reopen the operation.
                        updateActivity(event.id) { current ->
                            if (!current.isRunning) current
                            else current.copy(
                                elapsedMs = event.elapsedMs,
                                sinceLastUpdateMs = event.sinceLastUpdateMs,
                            )
                        }
                    }
                    is ChatEvent.ActivityEnded -> {
                        updateActivity(event.id) { current ->
                            current.copy(
                                status = if (event.ok) ActivityStatus.OK else ActivityStatus.FAILED,
                                server = event.server ?: current.server,
                                detail = event.detail ?: current.detail,
                            )
                        }
                    }
                    is ChatEvent.ActivityFailed -> {
                        updateActivity(event.id) { current ->
                            current.copy(
                                status = ActivityStatus.FAILED,
                                server = event.server ?: current.server,
                                detail = event.detail ?: current.detail,
                            )
                        }
                    }
                    is ChatEvent.ActivityTimedOut -> {
                        updateActivity(event.id) { current ->
                            current.copy(
                                status = ActivityStatus.TIMED_OUT,
                                server = event.server ?: current.server,
                                detail = event.detail ?: current.detail,
                            )
                        }
                    }
                    is ChatEvent.ToolStarted -> {
                        _activeTool.value = event.toolName
                        val toolId = event.id.ifBlank { null }
                        val isDuplicate = toolId != null && _messages.value.any { it.sender == MessageSender.TOOL && it.toolId == toolId }
                        if (!isDuplicate) {
                            if (event.toolName == "system") {
                                _messages.value = _messages.value + ChatMessage(
                                    sender = MessageSender.SYSTEM,
                                    text = event.message,
                                    toolName = event.toolName
                                )
                            } else {
                                _messages.value = _messages.value + ChatMessage(
                                    sender = MessageSender.TOOL,
                                    text = event.message,
                                    toolName = event.toolName,
                                    toolId = toolId,
                                    toolOk = null
                                )
                            }
                        }
                    }
                    is ChatEvent.ToolEnded -> {
                        if (_activeTool.value == event.toolName) {
                            _activeTool.value = null
                        }
                        _messages.value = _messages.value.map { msg ->
                            if (msg.sender == MessageSender.TOOL && msg.toolId == event.id && msg.toolOk == null) {
                                msg.copy(toolOk = event.ok)
                            } else msg
                        }
                    }
                    is ChatEvent.SubagentStarted -> {
                        val newAgent = SubagentLiveState(
                            id = event.id,
                            name = event.name,
                            task = event.task,
                            isRunning = true
                        )
                        val existing = _subagents.value
                        _subagents.value = (existing.filterNot { it.id == event.id } + newAgent)
                        if (_selectedSubagent.value == null || _selectedSubagent.value?.id == event.id) {
                            _selectedSubagent.value = newAgent
                        }
                        val isDuplicate = _messages.value.any { it.sender == MessageSender.SUBAGENT && it.toolId == event.id }
                        if (!isDuplicate) {
                            _messages.value = _messages.value + ChatMessage(
                                sender = MessageSender.SUBAGENT,
                                text = event.task ?: "Delegando tarea a subagente ${event.name}...",
                                toolName = event.name,
                                toolId = event.id,
                                toolOk = null
                            )
                        }
                    }
                    is ChatEvent.SubagentUpdated -> {
                        _subagents.value = _subagents.value.map { agent ->
                            if (agent.id == event.id) {
                                val updated = agent.copy(output = agent.output + event.delta)
                                if (_selectedSubagent.value?.id == event.id) {
                                    _selectedSubagent.value = updated
                                }
                                updated
                            } else agent
                        }
                    }
                    is ChatEvent.SubagentEnded -> {
                        _subagents.value = _subagents.value.map { agent ->
                            if (agent.id == event.id) {
                                val updated = agent.copy(
                                    isRunning = false,
                                    output = if (event.summary != null && agent.output.isBlank()) event.summary else agent.output
                                )
                                if (_selectedSubagent.value?.id == event.id) {
                                    _selectedSubagent.value = updated
                                }
                                updated
                            } else agent
                        }
                        _messages.value = _messages.value.map { msg ->
                            if (msg.sender == MessageSender.SUBAGENT && msg.toolId == event.id && msg.toolOk == null) {
                                msg.copy(toolOk = true, text = event.summary ?: msg.text)
                            } else msg
                        }
                    }
                    is ChatEvent.Thinking -> {
                        _isThinking.value = event.active
                    }
                    is ChatEvent.AssistantDelta -> {
                        _isStreaming.value = true
                        val currentList = _messages.value
                        val lastStreamingIndex = currentList.indexOfLast { it.sender == MessageSender.PI && it.isStreaming }
                        if (lastStreamingIndex != -1) {
                            val updated = currentList.toMutableList()
                            // A delta is the next fragment, not the message. This
                            // replaced the bubble with each fragment as it came,
                            // so a message being written live showed only its last
                            // chunk -- for a sentence ending in a period, literally
                            // a single ".", until message_end replaced it wholesale.
                            updated[lastStreamingIndex] = updated[lastStreamingIndex].copy(
                                text = updated[lastStreamingIndex].text + event.delta
                            )
                            _messages.value = updated
                        } else {
                            _messages.value = currentList + ChatMessage(
                                sender = MessageSender.PI,
                                text = event.delta,
                                isStreaming = true
                            )
                        }
                    }
                    is ChatEvent.UiRequestReceived -> {
                        _activeUiRequest.value = event.request
                        _messages.value = _messages.value + ChatMessage(
                            sender = MessageSender.SYSTEM,
                            text = "Pi solicita tu confirmación para proceder.",
                            uiRequest = event.request
                        )
                    }
                    is ChatEvent.MessageEnded -> {
                        _isStreaming.value = false
                        _activeTool.value = null
                        _isThinking.value = false
                        val currentList = _messages.value
                        val lastStreamingIndex = currentList.indexOfLast { it.sender == MessageSender.PI && it.isStreaming }
                        if (lastStreamingIndex != -1) {
                            val updated = currentList.toMutableList()
                            updated[lastStreamingIndex] = updated[lastStreamingIndex].copy(
                                text = event.finalMessage.ifBlank { updated[lastStreamingIndex].text },
                                isStreaming = false
                            )
                            _messages.value = updated
                        } else {
                            val lastPiIndex = currentList.indexOfLast { it.sender == MessageSender.PI }
                            if (lastPiIndex != -1 && currentList[lastPiIndex].text.trim() == event.finalMessage.trim()) {
                                // Ya está presente con el mismo texto, no duplicar
                            } else {
                                _messages.value = currentList + ChatMessage(
                                    sender = MessageSender.PI,
                                    text = event.finalMessage,
                                    isStreaming = false
                                )
                            }
                        }
                    }
                    is ChatEvent.ErrorOccurred -> {
                        _isStreaming.value = false
                        _activeTool.value = null
                        _isThinking.value = false
                        _messages.value = _messages.value + ChatMessage(
                            sender = MessageSender.ERROR,
                            text = "Error: ${event.error}"
                        )
                    }
                    is ChatEvent.SupervisorAlertReceived -> {
                        _messages.value = _messages.value + ChatMessage(
                            sender = MessageSender.SYSTEM,
                            text = "🛡️ Alerta del Supervisor: ${event.alert.title}\n${event.alert.description}"
                        )
                    }
                }
            }
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val textToSend = _inputText.value.trim()
        if (textToSend.isEmpty()) return

        _messages.value = _messages.value + ChatMessage(
            sender = MessageSender.USER,
            text = textToSend
        )
        _inputText.value = ""

        viewModelScope.launch {
            try {
                client.sendMessage(textToSend)
            } catch (e: Exception) {
                _isThinking.value = false
                _isStreaming.value = false
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.ERROR,
                    text = "No se pudo enviar el mensaje: ${e.localizedMessage}"
                )
            }
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _subagents.value = emptyList()
        _selectedSubagent.value = null
        _activeTool.value = null
    }

    fun respondToUiRequest(requestId: String, value: Any) {
        _activeUiRequest.value = null
        viewModelScope.launch {
            try {
                client.sendUiResponse(requestId, value)
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.ERROR,
                    text = "No se pudo enviar la confirmación: ${e.localizedMessage}"
                )
            }
        }
    }

    private var sessionJob: kotlinx.coroutines.Job? = null

    fun loadSessionHistory(sessionId: String) {
        sessionJob?.cancel()
        _subagents.value = emptyList()
        _selectedSubagent.value = null
        _activeTool.value = null
        sessionJob = viewModelScope.launch {
            _messages.value = listOf(
                ChatMessage(sender = MessageSender.SYSTEM, text = "Reanudando sesión $sessionId en tu PC...")
            )
            try {
                client.resumeSession(sessionId)
                val history = client.getSessionHistory(sessionId)
                val mappedMessages = history.map { item ->
                    ChatMessage(
                        sender = if (item.role.lowercase() == "user") MessageSender.USER else MessageSender.PI,
                        text = item.text,
                        timestamp = item.timestamp
                    )
                }
                if (mappedMessages.isNotEmpty()) {
                    _messages.value = mappedMessages
                } else {
                    _messages.value = listOf(
                        ChatMessage(sender = MessageSender.PI, text = "Sesión reanudada con éxito. ¿En qué continuamos?")
                    )
                }

                // Sincronizar estado de ejecución activa al entrar a la sesión
                try {
                    val status = client.getStatus()
                    _isThinking.value = status.busy
                } catch (e: Exception) {}
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _messages.value = listOf(
                        ChatMessage(sender = MessageSender.ERROR, text = "Error al reanudar la sesión: ${e.localizedMessage}")
                    )
                }
            }
        }
    }

    fun switchModel(modelName: String) {
        viewModelScope.launch {
            try {
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.SYSTEM,
                    text = "🤖 Cambiando modelo activo en Pi CLI a: $modelName..."
                )
                client.switchModel(modelName)
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.ERROR,
                    text = "Error al cambiar de modelo: ${e.localizedMessage}"
                )
            }
        }
    }

    fun cancelTask() {
        viewModelScope.launch {
            try {
                client.cancelCurrentTask()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel current task", e)
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.ERROR,
                    text = "No se pudo cancelar la tarea: ${e.localizedMessage}"
                )
            } finally {
                _isStreaming.value = false
                _activeTool.value = null
                _activeUiRequest.value = null
                _isThinking.value = false
            }
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }
}
