package com.idupi.app.data.inbox

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What Ajustes > ALERTAS can tell the owner about capture (inbox capture fix
 * T1): whether the listener is really connected, when the last notification was
 * read and from which app, the last upload result, how many retries wait and
 * how many notifications the last scan selected.
 *
 * Privacy: app ids, timestamps, counts and exception class names only. Message
 * text, sender and conversation never enter this state, and no message content
 * is ever logged.
 */
data class InboxCaptureState(
    val listenerConnected: Boolean = false,
    val connectedSinceMillis: Long? = null,
    val lastCaptureAtMillis: Long? = null,
    /** Followed app id of the last capture (preset id or package), never text. */
    val lastCaptureApp: String? = null,
    val lastUploadOkAtMillis: Long? = null,
    val lastUploadErrorClass: String? = null,
    val lastUploadErrorAtMillis: Long? = null,
    val pendingRetryCount: Int = 0,
    /** Notifications selected by the last active scan; null before any scan. */
    val lastScanCount: Int? = null,
)

/**
 * Process-wide capture diagnostics shared by the notification listener (writer)
 * and Ajustes (reader). A single object because the listener and the Activity
 * live in the same process, and the state must survive ViewModel recreation.
 */
object InboxCaptureDiagnostics {

    private val _state = MutableStateFlow(InboxCaptureState())
    val state: StateFlow<InboxCaptureState> = _state.asStateFlow()

    fun onListenerConnected(nowMillis: Long = System.currentTimeMillis()) {
        _state.update {
            it.copy(listenerConnected = true, connectedSinceMillis = nowMillis)
        }
    }

    fun onListenerDisconnected() {
        _state.update {
            it.copy(listenerConnected = false, connectedSinceMillis = null)
        }
    }

    fun onCaptured(app: String, nowMillis: Long = System.currentTimeMillis()) {
        _state.update {
            it.copy(lastCaptureAtMillis = nowMillis, lastCaptureApp = app)
        }
    }

    fun onUploadSucceeded(nowMillis: Long = System.currentTimeMillis()) {
        _state.update {
            it.copy(
                lastUploadOkAtMillis = nowMillis,
                lastUploadErrorClass = null,
                lastUploadErrorAtMillis = null
            )
        }
    }

    fun onUploadFailed(
        errorClass: String,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        _state.update {
            it.copy(lastUploadErrorClass = errorClass, lastUploadErrorAtMillis = nowMillis)
        }
    }

    fun onPendingCount(count: Int) {
        _state.update { it.copy(pendingRetryCount = count.coerceAtLeast(0)) }
    }

    fun onScanCompleted(count: Int) {
        _state.update { it.copy(lastScanCount = count.coerceAtLeast(0)) }
    }

    /** Test isolation: production never clears diagnostics while the process lives. */
    fun reset() {
        _state.value = InboxCaptureState()
    }
}
