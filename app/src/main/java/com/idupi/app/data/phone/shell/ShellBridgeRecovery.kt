package com.idupi.app.data.phone.shell

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Brings the shell bridge back on its own (phone-shell T6/T7).
 *
 * The bridge depends on Wireless debugging, and Android turns it off on reboot.
 * When the helper is not active, IDUPI is paired, the secure-settings grant is
 * present and the phone is on Wi-Fi, this routine temporarily flips
 * `adb_wifi_enabled` on (plus developer options when they were off), runs
 * [ShellBridgeBootstrap] and always restores the settings it changed, so the
 * adb port is not left open on the LAN.
 *
 * Single-flight through [mutex]: triggers can overlap (boot, Wi-Fi callback,
 * a failing command and the Ajustes button), and only one bootstrap may run.
 * Real attempts are rate-limited by [ShellBridgeRecoveryPolicy.COOLDOWN_MS];
 * the Ajustes button passes `forced = true`. Every outcome is logged under
 * [TAG].
 */
object ShellBridgeRecovery {

    private const val TAG = "IdupiPhoneShell"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val RETOGGLE_OFF_MS = 1_500L
    private const val RETOGGLE_ON_MS = 2_000L
    private const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"

    private val mutex = Mutex()

    @Volatile
    private var lastAttemptAtMillis: Long? = null

    /**
     * Runs at most one recovery. [onStep] receives the bootstrap's progress
     * messages (the Ajustes service mirrors them in its notification).
     */
    suspend fun recover(
        context: Context,
        forced: Boolean = false,
        trigger: String,
        onStep: (String) -> Unit = {},
    ): ShellBridgeRecoveryOutcome {
        val app = context.applicationContext
        val outcome = mutex.withLock {
            val bridgeActive = ShellBridgeClient.get(app).status().getOrNull()?.active == true
            val preflight = ShellBridgeRecoveryPolicy.preflight(
                paired = PhoneShellStore(app).paired,
                bridgeActive = bridgeActive,
                writeSecureSettings = hasWriteSecureSettings(app),
                onWifi = ShellRecoveryNetwork.isOnWifi(app),
            )
            when (preflight) {
                ShellBridgeRecoveryPolicy.Preflight.AlreadyActive ->
                    ShellBridgeRecoveryOutcome.AlreadyActive

                is ShellBridgeRecoveryPolicy.Preflight.Skip ->
                    ShellBridgeRecoveryOutcome.Skipped(preflight.reason)

                ShellBridgeRecoveryPolicy.Preflight.Proceed -> {
                    val now = System.currentTimeMillis()
                    if (!ShellBridgeRecoveryPolicy.canAttempt(now, lastAttemptAtMillis, forced)) {
                        ShellBridgeRecoveryOutcome.Skipped(ShellBridgeRecoverySkip.COOLDOWN)
                    } else {
                        lastAttemptAtMillis = now
                        attempt(app, onStep)
                    }
                }
            }
        }
        log(trigger, outcome)
        return outcome
    }

    private suspend fun attempt(
        context: Context,
        onStep: (String) -> Unit,
    ): ShellBridgeRecoveryOutcome {
        val resolver = context.contentResolver
        val (previousAdb, previousDevelopment) = withContext(Dispatchers.IO) {
            readGlobal(resolver, ADB_WIFI_ENABLED) to
                readGlobal(resolver, DEVELOPMENT_SETTINGS_ENABLED)
        }
        val plan = ShellBridgeRecoveryPolicy.settingsPlan(previousAdb, previousDevelopment)
        return try {
            val adbOn = withContext(Dispatchers.IO) {
                if (plan.shouldEnableDevelopmentSettings) {
                    writeGlobal(resolver, DEVELOPMENT_SETTINGS_ENABLED, 1)
                }
                if (plan.shouldEnableAdbWifi) {
                    writeGlobal(resolver, ADB_WIFI_ENABLED, 1)
                }
                // A rejected write must not be reported as an expired key.
                !plan.shouldEnableAdbWifi || readGlobal(resolver, ADB_WIFI_ENABLED) != 0
            }
            if (!adbOn) {
                ShellBridgeRecoveryOutcome.Failed(
                    "No se pudo activar la depuración inalámbrica."
                )
            } else {
                // Flipping the toggle restarts adbd, so the manager may still
                // hold a socket it believes is connected. Drop it before the
                // bootstrap, otherwise a later recovery trusts `isConnected`
                // and every exec runs on a dead stream.
                AdbShellClient.get(context).disconnect()
                // The bootstrap's connect retry and mDNS discovery are what wait
                // for adbd to publish its TLS service after the toggle flips
                // (up to ~20 s); no separate sleep is needed.
                var result = ShellBridgeBootstrap(context, onStep = onStep).run()
                if (ShellBridgeRecoveryPolicy.shouldRetoggleAdbWifi(result.exceptionOrNull())) {
                    // Right after boot adbd opens its TLS port but cannot
                    // advertise it (mdnsd not running yet, error -65563), so
                    // discovery finds nothing (owner reboot 2026-11-27 12:56).
                    // Our failed discovery has started mdnsd by now: flipping
                    // the toggle makes adbd register the service again.
                    Log.i(TAG, "recovery: adb service not advertised, re-toggling wireless debugging")
                    withContext(Dispatchers.IO) { writeGlobal(resolver, ADB_WIFI_ENABLED, 0) }
                    delay(RETOGGLE_OFF_MS)
                    withContext(Dispatchers.IO) { writeGlobal(resolver, ADB_WIFI_ENABLED, 1) }
                    delay(RETOGGLE_ON_MS)
                    AdbShellClient.get(context).disconnect()
                    result = ShellBridgeBootstrap(context, onStep = onStep).run()
                }
                result.fold(
                    onSuccess = { ShellBridgeRecoveryOutcome.Recovered },
                    onFailure = { error -> ShellBridgeRecoveryOutcome.Failed(failureText(error)) },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ShellBridgeRecoveryOutcome.Failed("No se pudo recuperar el puente.")
        } finally {
            // Always restore, even when the service timeout cancelled us: the
            // adb port must never stay open because a run was interrupted.
            withContext(NonCancellable + Dispatchers.IO) {
                if (plan.shouldRestoreAdbWifi) {
                    runCatching { writeGlobal(resolver, ADB_WIFI_ENABLED, previousAdb) }
                }
                if (plan.shouldRestoreDevelopmentSettings) {
                    runCatching {
                        writeGlobal(resolver, DEVELOPMENT_SETTINGS_ENABLED, previousDevelopment)
                    }
                }
            }
        }
    }

    /**
     * A connect failure after the adb toggle is on means the persisted key was
     * not accepted: wireless debugging may have been reset or the authorization
     * expired, so the owner has to pair again.
     */
    private fun failureText(error: Throwable): String =
        if (error is ShellBootstrapException && error.serviceNotFound) {
            "La depuración inalámbrica no apareció en la red. Vuelve a intentarlo en un momento."
        } else if (error is ShellBootstrapException && error.connectFailed) {
            "No se pudo conectar. La autorización puede haber caducado: " +
                "vuelve a vincular en Ajustes > Acceso avanzado del teléfono."
        } else {
            error.message?.takeIf { it.isNotBlank() } ?: "No se pudo recuperar el puente."
        }

    private fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun readGlobal(resolver: ContentResolver, key: String): Int =
        runCatching { Settings.Global.getInt(resolver, key, 0) }.getOrDefault(0)

    private fun writeGlobal(resolver: ContentResolver, key: String, value: Int) {
        Settings.Global.putInt(resolver, key, value)
    }

    private fun log(trigger: String, outcome: ShellBridgeRecoveryOutcome) {
        val detail = when (outcome) {
            ShellBridgeRecoveryOutcome.Recovered -> "recovered"
            ShellBridgeRecoveryOutcome.AlreadyActive -> "already active"
            is ShellBridgeRecoveryOutcome.Skipped ->
                "skipped: ${outcome.reason.name.lowercase()}"
            is ShellBridgeRecoveryOutcome.Failed -> "failed: ${outcome.reason}"
        }
        Log.i(TAG, "recovery($trigger): $detail")
    }
}
