package com.idupi.app.data.inbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/** Result of an on-demand "read notifications now" action. */
sealed interface InboxScanResult {

    /**
     * [captured] notifications were selected by the active-notification scan;
     * [sent] uploads were accepted by the PC during the action (new captures
     * plus retries drained from the queue).
     */
    data class Completed(val captured: Int, val sent: Int) : InboxScanResult

    /** No connected listener is available to read the shade. */
    data object NotConnected : InboxScanResult
}

/**
 * What the connected listener offers to the process (inbox capture fix T1).
 * Kept as an interface so [InboxCaptureController] never loads the Android
 * service class in a plain JVM test.
 */
interface InboxScanHost {
    fun isCaptureConnected(): Boolean
    suspend fun scanActiveNow(): InboxScanResult
}

/**
 * What Ajustes needs from the capture layer. The ViewModel depends on this
 * seam, so its states and actions are unit-tested with a fake instead of the
 * real service.
 */
interface InboxCaptureGateway {
    val status: StateFlow<InboxCaptureState>
    suspend fun scanNow(): InboxScanResult
    suspend fun refreshPendingCount()
}

/**
 * Process-wide bridge between Ajustes and the connected listener (inbox
 * capture fix T1).
 *
 * The listener attaches itself on connect and detaches on disconnect/destroy;
 * holding it weakly means the controller can never keep a dead service alive.
 * When no listener is attached, [scanNow] reports [InboxScanResult.NotConnected]
 * and the UI offers "Reconectar" instead.
 */
object InboxCaptureController : InboxCaptureGateway {

    @Volatile
    private var host: WeakReference<InboxScanHost>? = null

    override val status: StateFlow<InboxCaptureState> = InboxCaptureDiagnostics.state

    fun attach(listener: InboxScanHost) {
        host = WeakReference(listener)
    }

    fun detach(listener: InboxScanHost) {
        if (host?.get() === listener) host = null
    }

    override suspend fun scanNow(): InboxScanResult {
        val listener = host?.get() ?: return InboxScanResult.NotConnected
        if (!listener.isCaptureConnected()) return InboxScanResult.NotConnected
        // `activeNotifications` is a binder call and the capture path parses
        // notification extras: never run it on the caller's (main) thread.
        return withContext(Dispatchers.IO) { listener.scanActiveNow() }
    }

    override suspend fun refreshPendingCount() {
        try {
            InboxCaptureDiagnostics.onPendingCount(InboxRetryProvider.get().size())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Storage unavailable: keep the last known count instead of lying
            // with a zero. No content logged.
        }
    }
}
