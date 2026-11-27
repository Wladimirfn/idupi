package com.idupi.app.data.phone.shell

/**
 * Stages of the in-app adb channel, as shown in Ajustes > Acceso avanzado del
 * teléfono (phone-shell T3).
 *
 * Only [NOT_PAIRED] and [PAIRED] survive a process restart: the connection and
 * the bridge live in the helper process, so they are re-checked after a restart.
 */
enum class PhoneShellStage {
    CHECKING,
    UNAVAILABLE,
    NOT_PAIRED,
    PAIRED,
    CONNECTED,
    BRIDGE_ACTIVE,
}

/**
 * What the Ajustes row and its sub-screen render. [busy] covers a pairing or
 * bootstrap run; [message] carries the current step or the last failure.
 */
data class PhoneShellState(
    val stage: PhoneShellStage = PhoneShellStage.CHECKING,
    val busy: Boolean = false,
    val message: String? = null,
)

/** Owner-facing status line. Pure, so every mapping is JVM-tested. */
fun phoneShellStatusLabel(stage: PhoneShellStage): String = when (stage) {
    PhoneShellStage.CHECKING -> "Consultando…"
    PhoneShellStage.UNAVAILABLE -> "No disponible en este teléfono (API < 30)"
    PhoneShellStage.NOT_PAIRED -> "No vinculado"
    PhoneShellStage.PAIRED -> "Vinculado"
    PhoneShellStage.CONNECTED -> "Conectado"
    PhoneShellStage.BRIDGE_ACTIVE -> "Puente activo"
}

/** The adb key is already authorized, so the bridge can be started. */
fun PhoneShellStage.isPaired(): Boolean =
    this == PhoneShellStage.PAIRED ||
        this == PhoneShellStage.CONNECTED ||
        this == PhoneShellStage.BRIDGE_ACTIVE
