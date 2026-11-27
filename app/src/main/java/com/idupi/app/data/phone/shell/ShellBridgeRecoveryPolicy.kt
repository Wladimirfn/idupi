package com.idupi.app.data.phone.shell

/**
 * Pure decisions for [ShellBridgeRecovery] (phone-shell T6/T7).
 *
 * The recovery routine is Android glue around these rules, so the decision
 * table (when an attempt is allowed, which settings to change and restore, and
 * what to do after an unavailable shell command) is unit-tested without a
 * device.
 */

/** One recovery run's verdict. [Failed.reason] is owner-facing neutral Spanish. */
sealed interface ShellBridgeRecoveryOutcome {
    /** The bridge was down, the bootstrap ran, and the probe proved it alive. */
    data object Recovered : ShellBridgeRecoveryOutcome

    /** The helper already reported an active bridge; nothing was touched. */
    data object AlreadyActive : ShellBridgeRecoveryOutcome

    /** A precondition did not hold (or the cooldown rejected the attempt). */
    data class Skipped(val reason: ShellBridgeRecoverySkip) : ShellBridgeRecoveryOutcome

    /** The attempt ran but could not bring the bridge back. */
    data class Failed(val reason: String) : ShellBridgeRecoveryOutcome
}

enum class ShellBridgeRecoverySkip {
    NOT_PAIRED,
    NO_WRITE_SECURE_SETTINGS,
    NOT_ON_WIFI,
    COOLDOWN,
}

object ShellBridgeRecoveryPolicy {

    /** At most one real attempt in this window unless the owner forced it. */
    const val COOLDOWN_MS = 2 * 60_000L

    /** The retried command also found the bridge down. */
    const val RETRY_FAILED_MESSAGE = "El puente no responde. Vuelve a intentarlo."

    /** The command found the bridge down while the phone is off the home Wi-Fi. */
    const val OFF_WIFI_MESSAGE =
        "El puente está caído. Se recuperará solo cuando vuelvas a tu Wi-Fi de casa."

    /** Cooldown skip while a command waits: a real attempt just ran and failed. */
    const val COOLDOWN_MESSAGE =
        "El puente no responde. Espera un momento y vuelve a intentarlo."

    sealed interface Preflight {
        data object Proceed : Preflight
        data object AlreadyActive : Preflight
        data class Skip(val reason: ShellBridgeRecoverySkip) : Preflight
    }

    /**
     * Decides whether a recovery may run.
     *
     * An active bridge wins over every other check: the goal is already met,
     * even when the owner revoked a permission or left Wi-Fi. After that the
     * design's preconditions apply in order: pairing, the secure-settings
     * grant and Wi-Fi.
     */
    fun preflight(
        paired: Boolean,
        bridgeActive: Boolean,
        writeSecureSettings: Boolean,
        onWifi: Boolean,
    ): Preflight = when {
        bridgeActive -> Preflight.AlreadyActive
        !paired -> Preflight.Skip(ShellBridgeRecoverySkip.NOT_PAIRED)
        !writeSecureSettings -> Preflight.Skip(ShellBridgeRecoverySkip.NO_WRITE_SECURE_SETTINGS)
        !onWifi -> Preflight.Skip(ShellBridgeRecoverySkip.NOT_ON_WIFI)
        else -> Preflight.Proceed
    }

    /**
     * One real attempt per [COOLDOWN_MS]. A forced run (the Ajustes button)
     * always passes; skipped preconditions never consume the cooldown because
     * the caller only records the attempt when this returns true.
     */
    fun canAttempt(
        nowMillis: Long,
        lastAttemptAtMillis: Long?,
        forced: Boolean,
    ): Boolean {
        if (forced) return true
        val last = lastAttemptAtMillis ?: return true
        return nowMillis - last >= COOLDOWN_MS
    }

    /**
     * Which global settings this attempt changes and must restore.
     *
     * Only a setting that the owner had off is touched, and exactly those are
     * restored in the `finally`; a value the owner already had on is left
     * untouched.
     */
    data class SettingsPlan(
        val adbWifiWasOff: Boolean,
        val developmentSettingsWasOff: Boolean,
    ) {
        val shouldEnableAdbWifi: Boolean get() = adbWifiWasOff
        val shouldEnableDevelopmentSettings: Boolean get() = developmentSettingsWasOff
        val shouldRestoreAdbWifi: Boolean get() = adbWifiWasOff
        val shouldRestoreDevelopmentSettings: Boolean get() = developmentSettingsWasOff
    }

    fun settingsPlan(
        previousAdbWifi: Int,
        previousDevelopmentSettings: Int,
    ): SettingsPlan = SettingsPlan(
        adbWifiWasOff = previousAdbWifi == 0,
        developmentSettingsWasOff = previousDevelopmentSettings == 0,
    )

    sealed interface RetryDecision {
        data object RetryOnce : RetryDecision
        data class Fail(val message: String) : RetryDecision
    }

    /**
     * What a `shell` command does after [ShellBridgeAvailability] said the
     * bridge is down and one recovery ran.
     *
     * [alreadyRetried] enforces the retry-once rule: a second unavailable
     * result never starts another attempt.
     */
    fun retryDecision(
        outcome: ShellBridgeRecoveryOutcome,
        alreadyRetried: Boolean = false,
    ): RetryDecision {
        if (alreadyRetried) return RetryDecision.Fail(RETRY_FAILED_MESSAGE)
        return when (outcome) {
            ShellBridgeRecoveryOutcome.Recovered,
            ShellBridgeRecoveryOutcome.AlreadyActive,
            -> RetryDecision.RetryOnce

            is ShellBridgeRecoveryOutcome.Skipped -> when (outcome.reason) {
                ShellBridgeRecoverySkip.COOLDOWN -> RetryDecision.Fail(COOLDOWN_MESSAGE)
                ShellBridgeRecoverySkip.NOT_ON_WIFI -> RetryDecision.Fail(OFF_WIFI_MESSAGE)
                ShellBridgeRecoverySkip.NOT_PAIRED,
                ShellBridgeRecoverySkip.NO_WRITE_SECURE_SETTINGS,
                -> RetryDecision.Fail(skippedMessage(outcome.reason))
            }

            is ShellBridgeRecoveryOutcome.Failed -> RetryDecision.Fail(outcome.reason)
        }
    }

    /** Owner-facing text for a skipped recovery outside a command result. */
    fun skippedMessage(reason: ShellBridgeRecoverySkip): String = when (reason) {
        ShellBridgeRecoverySkip.NOT_PAIRED ->
            "Vincula el teléfono en Ajustes > Acceso avanzado del teléfono."
        ShellBridgeRecoverySkip.NO_WRITE_SECURE_SETTINGS ->
            "Falta el permiso de escritura. Vuelve a vincular en Ajustes > Acceso avanzado del teléfono."
        ShellBridgeRecoverySkip.NOT_ON_WIFI ->
            "Conéctate a tu Wi-Fi de casa y vuelve a intentarlo."
        ShellBridgeRecoverySkip.COOLDOWN ->
            "El puente se está recuperando. Espera un momento."
    }
}

/**
 * What "the bridge is down" means for one helper call (phone-shell T7).
 *
 * A dead or uninstrumented helper can answer successfully with `ok = false`
 * and the provider's "El puente no está activo" text instead of throwing, so
 * both shapes count as unavailable. A normal command failure (for example a
 * package that refused to uninstall) does not.
 */
object ShellBridgeAvailability {

    private const val NOT_ACTIVE_PREFIX = "El puente no está activo"

    fun isUnavailable(result: Result<BridgeResult>): Boolean {
        if (result.isFailure) return true
        val bridge = result.getOrNull() ?: return true
        return !bridge.ok && bridge.text.startsWith(NOT_ACTIVE_PREFIX)
    }
}
