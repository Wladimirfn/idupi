package com.idupi.app.data.phone.shell

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide status holder for Ajustes > Acceso avanzado del teléfono
 * (phone-shell T3). The pairing service and the bootstrap feed it while they
 * work; the ViewModel only observes it.
 *
 * [refresh] is the read-only path used when the screen opens: it never starts
 * a connection, it only checks the already-open one (and the helper process
 * behind it) so a status can never be invented from stale data.
 */
object PhoneShellStatus {

    private val _state = MutableStateFlow(PhoneShellState())
    val state: StateFlow<PhoneShellState> = _state.asStateFlow()

    private val refreshLock = Mutex()

    /** A pairing or bootstrap run started or advanced one step. */
    fun working(message: String) {
        _state.update { it.copy(busy = true, message = message) }
    }

    /** A run finished successfully at [stage]. */
    fun settled(stage: PhoneShellStage, message: String? = null) {
        _state.value = PhoneShellState(stage = stage, busy = false, message = message)
    }

    /** A run failed; [stage] is what was already true before the failure. */
    fun failed(stage: PhoneShellStage, message: String) {
        _state.value = PhoneShellState(stage = stage, busy = false, message = message)
    }

    /**
     * Re-reads the status without side effects: API availability, the
     * persisted pairing, the live connection and the helper process. A busy
     * run is never overwritten.
     */
    suspend fun refresh(context: Context) {
        if (_state.value.busy) return
        refreshLock.withLock {
            if (_state.value.busy) return@withLock
            val stage = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> PhoneShellStage.UNAVAILABLE
                !PhoneShellStore(context).paired -> PhoneShellStage.NOT_PAIRED
                else -> connectedStage(context)
            }
            _state.value = PhoneShellState(stage = stage)
        }
    }

    private suspend fun connectedStage(context: Context): PhoneShellStage {
        val client = AdbShellClient.get(context)
        if (!client.isConnected()) return PhoneShellStage.PAIRED
        // The helper's own status is authoritative: a live process started by a
        // provider call without the instrumentation is not a bridge.
        val active = ShellBridgeClient.get(context).status().getOrNull()?.active == true
        return if (active) PhoneShellStage.BRIDGE_ACTIVE else PhoneShellStage.CONNECTED
    }
}
