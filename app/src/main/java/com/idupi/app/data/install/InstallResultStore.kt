package com.idupi.app.data.install

import android.content.pm.PackageInstaller
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Final verdict of one PackageInstaller session, already localized for the UI. */
data class InstallOutcome(
    val deliveryId: String?,
    val success: Boolean,
    val message: String
)

/**
 * In-process holder for install verdicts.
 *
 * The system delivers the result to [InstallResultReceiver] in this same
 * process; the deliveries screen subscribes while it is visible. A verdict
 * that arrives with no subscriber (app process gone, screen closed) is
 * dropped on purpose: surviving a dead process is roadmap step 3, out of
 * scope for this task. Keeping it a [SharedFlow] with no replay also stops an
 * old "APK instalada" from reappearing when the screen is reopened.
 */
class InstallResultStore {

    private val _outcomes = MutableSharedFlow<InstallOutcome>(extraBufferCapacity = 8)
    val outcomes: SharedFlow<InstallOutcome> = _outcomes.asSharedFlow()

    fun publish(outcome: InstallOutcome) {
        _outcomes.tryEmit(outcome)
    }

    companion object {
        /** Process-wide instance shared by the receiver and the screen. */
        val shared: InstallResultStore = InstallResultStore()
    }
}

/**
 * Maps a [PackageInstaller] `STATUS_*` code to the short Spanish message the
 * deliveries screen shows. Pure on purpose: every branch is pinned by a JVM
 * test, including the conflict case that means "wrong signing key".
 */
fun installStatusMessage(status: Int): String = when (status) {
    PackageInstaller.STATUS_SUCCESS -> "APK instalada"
    PackageInstaller.STATUS_FAILURE_ABORTED -> "Instalación cancelada"
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "El sistema bloqueó la instalación"
    PackageInstaller.STATUS_FAILURE_CONFLICT -> "La firma no coincide con la app instalada"
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "El APK no es compatible con este teléfono"
    PackageInstaller.STATUS_FAILURE_INVALID -> "El APK está dañado o no es válido"
    PackageInstaller.STATUS_FAILURE_STORAGE -> "No hay espacio suficiente en el teléfono"
    else -> "No se pudo instalar el APK"
}
