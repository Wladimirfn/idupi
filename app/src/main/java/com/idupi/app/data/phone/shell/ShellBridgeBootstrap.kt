package com.idupi.app.data.phone.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A bootstrap step failed; [message] is owner-facing neutral Spanish. */
class ShellBootstrapException(
    message: String,
    /** True when the adb connection itself failed (T6: the key may have expired). */
    val connectFailed: Boolean = false,
    /** True when every connect attempt found no advertised adb service. */
    val serviceNotFound: Boolean = false,
) : Exception(message)

/**
 * Turns a paired adb session into an installed helper and a running bridge
 * (phone-shell T3b).
 *
 * Order: connect, grant WRITE_SECURE_SETTINGS (self-recovery for T6), install
 * the embedded helper when missing/older, start `am instrument` detached and
 * verify it with `pidof` plus a fresh `idupi_shell_probe` heartbeat.
 *
 * Every adb call has a timeout through [AdbShellClient]; the whole run is
 * additionally bounded by the service that owns it.
 */
class ShellBridgeBootstrap(
    private val context: Context,
    private val client: AdbShellClient = AdbShellClient.get(context),
    private val bridge: ShellBridgeClient = ShellBridgeClient.get(context),
    private val store: PhoneShellStore = PhoneShellStore(context),
    private val onStep: (String) -> Unit = {},
) {

    data class Outcome(
        val helperVersion: Int,
        val helperInstalled: Boolean,
        val installedViaStream: Boolean,
        val bridgeStarted: Boolean,
    )

    suspend fun run(): Result<Outcome> = try {
        Result.success(bootstrap())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun bootstrap(): Outcome {
        onStep("Conectando con la depuración inalámbrica…")
        val connectError = connectWithRetry()
        if (connectError != null) {
            throw ShellBootstrapException(
                "No se pudo conectar. Abre Depuración inalámbrica y vuelve a intentarlo.",
                connectFailed = true,
                serviceNotFound = connectError is AdbServiceNotFoundException,
            )
        }

        onStep("Concediendo el permiso de escritura…")
        val grant = exec(ShellCommands.grantWriteSecureSettings(), GRANT_TIMEOUT_MS)
        if (grant.exitCode != 0) {
            throw ShellBootstrapException("No se pudo conceder el permiso de escritura.")
        }

        onStep("Preparando el ayudante…")
        val embeddedVersion = withContext(Dispatchers.IO) {
            HelperApk.embeddedVersionCode(context)
        }
        if (embeddedVersion <= 0) {
            throw ShellBootstrapException("El ayudante incluido no es válido. Actualiza IDUPI.")
        }
        val install = installHelperIfNeeded(embeddedVersion)

        // Baseline before the start: the heartbeat is what proves the bridge
        // adopted the shell identity, not merely that a process exists.
        val probeBaseline = readProbe()
        val bridgeStarted = startBridgeIfNeeded()

        onStep("Verificando el puente…")
        if (!verifyBridge(probeBaseline)) {
            throw ShellBootstrapException(
                "El puente no arrancó. Vuelve a tocar «Iniciar puente»."
            )
        }
        return Outcome(
            helperVersion = embeddedVersion,
            helperInstalled = install.installed,
            installedViaStream = install.viaStream,
            bridgeStarted = bridgeStarted,
        )
    }

    /** Returns null when connected, otherwise the last connect error. */
    private suspend fun connectWithRetry(): Throwable? {
        var lastError: Throwable? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            val result = client.connect()
            if (result.isSuccess) return null
            lastError = result.exceptionOrNull()
            Log.w(TAG, "connect attempt ${attempt + 1} failed: $lastError")
            if (attempt < CONNECT_ATTEMPTS - 1) delay(CONNECT_RETRY_MS)
        }
        return lastError ?: IllegalStateException("connect failed")
    }

    /**
     * Installs only when `pm path` says the helper is missing or `dumpsys`
     * reports an older versionCode than the embedded APK. The stored version
     * skips the `dumpsys` round trip on the common path; `pm path` still runs
     * so a manually uninstalled helper is noticed.
     */
    private suspend fun installHelperIfNeeded(embeddedVersion: Int): InstallOutcome {
        val installedVersion = installedVersion(embeddedVersion)
        if (!HelperVersions.needsInstall(installedVersion, embeddedVersion)) {
            // Remember the current version so later runs skip `dumpsys` while
            // `pm path` still catches a manually uninstalled helper.
            if (installedVersion != null) store.installedHelperVersion = embeddedVersion
            return InstallOutcome(installed = false, viaStream = false)
        }

        onStep("Instalando el ayudante…")
        val bytes = try {
            withContext(Dispatchers.IO) { HelperApk.readBytes(context) }
        } catch (e: Exception) {
            throw ShellBootstrapException("No se pudo leer el ayudante incluido.")
        }
        val streamed = client.installPackage(bytes)
        if (streamed.isSuccess) {
            when (val parsed = InstallResultParser.parse(streamed.getOrThrow().output)) {
                InstallResult.Success -> {
                    store.installedHelperVersion = embeddedVersion
                    return InstallOutcome(installed = true, viaStream = true)
                }
                is InstallResult.Failure -> throw ShellBootstrapException(
                    "No se pudo instalar el ayudante (${parsed.reason})."
                )
            }
        }

        // The stream transport failed; the package manager did not reject the
        // APK. Retry through a file path the shell can read, as `adb install`
        // does when streaming is unavailable.
        onStep("Instalando el ayudante por archivo…")
        val file = withContext(Dispatchers.IO) { HelperApk.copyToExternal(context) }
            ?: throw ShellBootstrapException("No se pudo preparar el ayudante para instalar.")
        val result = exec(ShellCommands.installHelper(file.absolutePath), INSTALL_TIMEOUT_MS)
        when (val parsed = InstallResultParser.parse(result.output)) {
            InstallResult.Success -> {
                store.installedHelperVersion = embeddedVersion
                return InstallOutcome(installed = true, viaStream = false)
            }
            is InstallResult.Failure -> throw ShellBootstrapException(
                "No se pudo instalar el ayudante (${parsed.reason})."
            )
        }
    }

    private suspend fun installedVersion(embeddedVersion: Int): Int? {
        val path = exec("pm path $HELPER_PACKAGE", QUERY_TIMEOUT_MS).output
        if (path.isBlank()) return null
        if (store.installedHelperVersion == embeddedVersion) return embeddedVersion
        return HelperVersions.installedVersion(
            exec("dumpsys package $HELPER_PACKAGE", QUERY_TIMEOUT_MS).output
        )
    }

    /**
     * Starts the detached instrumentation unless the helper reports `active`.
     *
     * A live pid is not enough: a provider call can start the helper process
     * without the instrumentation, and that process must be restarted by `am
     * instrument` (which force-stops and relaunches it). [BridgeState] only
     * reports active after the instrumentation holds the shell identity. When
     * the provider is unavailable (for example a signature mismatch in a debug
     * build), the start is attempted anyway and the probe below is the verdict.
     */
    private suspend fun startBridgeIfNeeded(): Boolean {
        if (bridge.status().getOrNull()?.active == true) return false
        onStep("Iniciando el puente…")
        val started = client.exec(ShellCommands.startBridgeDetached(), START_TIMEOUT_MS)
        if (started.isFailure) {
            throw ShellBootstrapException("No se pudo iniciar el puente.")
        }
        return true
    }

    private suspend fun verifyBridge(baseline: String?): Boolean {
        repeat(PROBE_POLLS) {
            delay(PROBE_POLL_MS)
            if (BridgeProbe.isFresh(baseline, readProbe())) return true
        }
        return false
    }

    private suspend fun readProbe(): String? =
        client.exec("settings get global $PROBE_KEY", PROBE_TIMEOUT_MS)
            .getOrNull()
            ?.output
            ?.trim()

    private suspend fun exec(
        command: String,
        timeoutMs: Long = DEFAULT_EXEC_TIMEOUT_MS,
    ): ShellResult = client.exec(command, timeoutMs).getOrElse { error ->
        Log.w(TAG, "exec '${command.substringBefore(' ')}' failed: $error")
        throw ShellBootstrapException("El teléfono no respondió a tiempo.")
    }

    private data class InstallOutcome(
        val installed: Boolean,
        val viaStream: Boolean,
    )

    private companion object {
        private const val TAG = "IdupiPhoneShell"
        const val HELPER_PACKAGE = "com.idupi.shell"
        const val PROBE_KEY = "idupi_shell_probe"

        const val CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_MS = 2_000L
        const val GRANT_TIMEOUT_MS = 15_000L
        const val QUERY_TIMEOUT_MS = 10_000L
        const val INSTALL_TIMEOUT_MS = 120_000L
        const val START_TIMEOUT_MS = 15_000L
        const val PROBE_POLLS = 6
        const val PROBE_POLL_MS = 2_000L
        const val PROBE_TIMEOUT_MS = 5_000L
        const val DEFAULT_EXEC_TIMEOUT_MS = 15_000L
    }
}
