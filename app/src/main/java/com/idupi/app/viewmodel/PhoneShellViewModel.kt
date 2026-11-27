package com.idupi.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.IduPiApp
import com.idupi.app.data.phone.shell.PhoneShellService
import com.idupi.app.data.phone.shell.PhoneShellState
import com.idupi.app.data.phone.shell.PhoneShellStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Ajustes > Acceso avanzado del teléfono (phone-shell T3/T3b).
 *
 * The heavy work (pairing, install, bridge) lives in [PhoneShellService] so it
 * survives leaving the screen. This ViewModel only observes the process-wide
 * status holder and starts the short-lived service actions.
 */
class PhoneShellViewModel : ViewModel() {

    val state: StateFlow<PhoneShellState> = PhoneShellStatus.state

    private var refreshJob: Job? = null

    /** Re-reads the status without starting a connection. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            PhoneShellStatus.refresh(IduPiApp.instance)
        }
    }

    fun startPairing() {
        PhoneShellService.startPairing(IduPiApp.instance)
    }

    fun startBridge() {
        PhoneShellService.startBridge(IduPiApp.instance)
    }
}
