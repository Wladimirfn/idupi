package com.idupi.shell

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One typed command's outcome, already shaped for the provider Bundle. */
data class ShellExecution(
    val ok: Boolean,
    val text: String,
    val path: String,
    val freeBefore: Long? = null,
    val freeAfter: Long? = null,
)

/**
 * Executes the typed allowlist (phone-shell T4).
 *
 * Fixed argv vectors only: no `sh -c`, no concatenation of untrusted input. The
 * helper adopted the shell permission identity; whether a child process
 * inherits it is device-dependent, so `force-stop` and `trim-caches` fall back
 * to the framework APIs in-process when a child reports a SecurityException.
 */
class ShellExecutor(private val context: Context) {

    fun execute(request: ShellRequest): ShellExecution {
        return when (request.action) {
            ShellAction.INFO -> info(request.topic ?: return failure("tema inválido"))
            ShellAction.TRIM_CACHES -> trimCaches()
            ShellAction.FORCE_STOP -> forceStop(
                request.packageName ?: return failure("falta el paquete")
            )
            ShellAction.BACKGROUND -> background(
                request.packageName ?: return failure("falta el paquete"),
                request.mode ?: return failure("modo inválido"),
            )
            ShellAction.DISABLE -> disable(
                request.packageName ?: return failure("falta el paquete")
            )
            ShellAction.ENABLE -> enable(
                request.packageName ?: return failure("falta el paquete")
            )
            ShellAction.UNINSTALL -> uninstall(
                request.packageName ?: return failure("falta el paquete")
            )
        }
    }

    // --- info ------------------------------------------------------------

    private fun info(topic: ShellTopic): ShellExecution = when (topic) {
        ShellTopic.STORAGE -> storageInfo()
        ShellTopic.MEMORY -> memoryInfo()
        ShellTopic.BATTERY -> batteryInfo()
        ShellTopic.PACKAGES -> packagesInfo()
    }

    private fun storageInfo(): ShellExecution {
        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
            ?: return failure("No se pudo leer el almacenamiento.", path = "api")
        val free = stat.availableBytes
        val total = stat.totalBytes
        val used = (total - free).coerceAtLeast(0)
        val percent = if (total > 0) used * 100 / total else 0
        return ShellExecution(
            ok = true,
            text = "Almacenamiento: ${format(free)} libres de ${format(total)} ($percent% usado).",
            path = "api",
        )
    }

    private fun memoryInfo(): ShellExecution {
        val lines = runCatching { File("/proc/meminfo").readLines() }
            .getOrElse { return failure("No se pudo leer la memoria.", path = "api") }
        val totalKb = memValueKb(lines, "MemTotal")
        val availableKb = memValueKb(lines, "MemAvailable")
            ?: memValueKb(lines, "MemFree")
        if (totalKb == null || availableKb == null) {
            return failure("No se pudo leer la memoria.", path = "api")
        }
        return ShellExecution(
            ok = true,
            text = "Memoria: ${format(availableKb * 1024L)} disponibles de ${format(totalKb * 1024L)}.",
            path = "api",
        )
    }

    private fun memValueKb(lines: List<String>, key: String): Long? =
        lines.firstOrNull { it.startsWith("$key:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()

    private fun batteryInfo(): ShellExecution {
        val dump = run(listOf("/system/bin/dumpsys", "battery"), TIMEOUT_MS)
        if (dump.ok) {
            batteryText(dump.output)?.let { text ->
                return ShellExecution(ok = true, text = text, path = "exec")
            }
        }
        batteryTextFromIntent()?.let { text ->
            return ShellExecution(ok = true, text = text, path = "api")
        }
        return failure("No se pudo leer la batería.", path = "exec")
    }

    private fun batteryText(dump: String): String? {
        val values = dump.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.substring(0, separator).trim() to
                    line.substring(separator + 1).trim()
            }
            .toMap()
        val level = values["level"]?.toIntOrNull() ?: return null
        val status = values["status"]?.toIntOrNull()
        val temperature = values["temperature"]?.toIntOrNull()
        return batterySentence(level, status, temperature)
    }

    @Suppress("DEPRECATION")
    private fun batteryTextFromIntent(): String? = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        batterySentence(
            level = level * 100 / scale,
            status = status,
            temperature = temperature.takeIf { it != Int.MIN_VALUE },
        )
    }.getOrNull()

    private fun batterySentence(level: Int, status: Int?, temperature: Int?): String {
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "cargando"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "descargando"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "no carga"
            BatteryManager.BATTERY_STATUS_FULL -> "llena"
            else -> "estado desconocido"
        }
        val temperatureText = temperature?.let {
            String.format(Locale.ROOT, "%.1f °C", it / 10.0)
        }
        return buildString {
            append("Batería: $level% · $statusText")
            if (temperatureText != null) append(" · $temperatureText")
            append('.')
        }
    }

    private fun packagesInfo(): ShellExecution {
        val dump = run(listOf("/system/bin/pm", "list", "packages", "-3"), TIMEOUT_MS)
        if (!dump.ok) {
            return failure("No se pudieron listar las apps: ${short(dump.output)}", path = "exec")
        }
        val names = dump.output.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .toList()
        if (names.isEmpty() && dump.output.isNotBlank()) {
            return failure("No se pudieron listar las apps: ${short(dump.output)}", path = "exec")
        }
        val shown = names.take(MAX_PACKAGE_NAMES)
        val suffix = if (names.size > MAX_PACKAGE_NAMES) "…" else ""
        return ShellExecution(
            ok = true,
            text = "Apps instaladas: ${names.size}. ${shown.joinToString(", ")}$suffix",
            path = "exec",
        )
    }

    // --- actions ---------------------------------------------------------

    private fun trimCaches(): ShellExecution {
        val before = freeBytes()
        val exec = run(
            listOf("/system/bin/cmd", "package", "trim-caches", TRIM_TARGET),
            TRIM_TIMEOUT_MS,
        )
        var path = "exec"
        var ok = exec.ok
        if (!exec.ok && exec.securityFailure) {
            path = "api"
            ok = trimCachesViaApi()
            if (ok) Thread.sleep(TRIM_SETTLE_MS)
        }
        val after = freeBytes()
        val freed = (after - before).coerceAtLeast(0)
        val text = if (ok) {
            buildString {
                append("Caché liberada. Espacio libre: ${format(before)} -> ${format(after)}.")
                if (freed > 0) append(" Liberaste ${format(freed)}.")
            }
        } else {
            "No se pudo liberar la caché. Espacio libre: ${format(before)} -> ${format(after)}." +
                shortSuffix(exec.output)
        }
        return ShellExecution(ok, text, path, freeBefore = before, freeAfter = after)
    }

    private fun forceStop(packageName: String): ShellExecution {
        val exec = run(listOf("/system/bin/am", "force-stop", packageName), TIMEOUT_MS)
        if (exec.ok) {
            return ShellExecution(true, "App detenida: $packageName.", "exec")
        }
        if (exec.securityFailure && forceStopViaApi(packageName)) {
            return ShellExecution(true, "App detenida: $packageName.", "api")
        }
        return failure(
            "No se pudo detener $packageName." + shortSuffix(exec.output),
            "exec",
        )
    }

    private fun background(packageName: String, mode: ShellMode): ShellExecution {
        val wire = mode.wire
        val exec = run(
            listOf(
                "/system/bin/cmd",
                "appops",
                "set",
                packageName,
                "RUN_ANY_IN_BACKGROUND",
                wire,
            ),
            TIMEOUT_MS,
        )
        if (!exec.ok) {
            return failure(
                "No se pudo cambiar el segundo plano de $packageName." +
                    shortSuffix(exec.output),
                "exec",
            )
        }
        val text = if (mode == ShellMode.ALLOW) {
            "Segundo plano permitido para $packageName."
        } else {
            "Segundo plano ignorado para $packageName."
        }
        return ShellExecution(true, text, "exec")
    }

    private fun disable(packageName: String): ShellExecution {
        val exec = run(
            listOf("/system/bin/pm", "disable-user", "--user", "0", packageName),
            TIMEOUT_MS,
        )
        return if (exec.ok) {
            ShellExecution(true, "App desactivada: $packageName.", "exec")
        } else {
            failure("No se pudo desactivar $packageName." + shortSuffix(exec.output), "exec")
        }
    }

    private fun enable(packageName: String): ShellExecution {
        val exec = run(
            listOf("/system/bin/pm", "enable", "--user", "0", packageName),
            TIMEOUT_MS,
        )
        return if (exec.ok) {
            ShellExecution(true, "App activada: $packageName.", "exec")
        } else {
            failure("No se pudo activar $packageName." + shortSuffix(exec.output), "exec")
        }
    }

    private fun uninstall(packageName: String): ShellExecution {
        val exec = run(
            listOf("/system/bin/pm", "uninstall", "--user", "0", packageName),
            TIMEOUT_MS,
        )
        return if (exec.ok) {
            ShellExecution(true, "App desinstalada: $packageName.", "exec")
        } else {
            failure("No se pudo desinstalar $packageName." + shortSuffix(exec.output), "exec")
        }
    }

    // --- fallbacks -------------------------------------------------------

    @Suppress("PrivateApi")
    private fun forceStopViaApi(packageName: String): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val method = manager.javaClass.getMethod("forceStopPackage", String::class.java)
        method.invoke(manager, packageName)
        true
    }.getOrDefault(false)

    @Suppress("PrivateApi")
    private fun trimCachesViaApi(): Boolean = runCatching {
        val manager = context.packageManager
        val observer = Class.forName("android.content.pm.IPackageDataObserver")
        val method = manager.javaClass.getMethod(
            "freeStorageAndNotify",
            java.lang.Long.TYPE,
            observer,
        )
        method.invoke(manager, Long.MAX_VALUE, null)
        true
    }.getOrDefault(false)

    // --- process helpers -------------------------------------------------

    private data class CommandOutput(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
    ) {
        val ok: Boolean get() = !timedOut && exitCode == 0
        val securityFailure: Boolean
            get() = output.contains("SecurityException", ignoreCase = true) ||
                output.contains("permission denied", ignoreCase = true)
    }

    private fun run(argv: List<String>, timeoutMs: Long): CommandOutput {
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return CommandOutput(-1, e.message.orEmpty(), timedOut = false)
        }
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        synchronized(output) { output.append(buffer, 0, read) }
                    }
                }
            } catch (_: Exception) {
                // The process died; whatever was read is what we report.
            }
        }.apply {
            isDaemon = true
            start()
        }

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        }
        runCatching { reader.join(1_000) }
        val text = synchronized(output) { output.toString().trim() }
        val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        return CommandOutput(code, text, timedOut = !finished)
    }

    private fun freeBytes(): Long = runCatching {
        StatFs(Environment.getDataDirectory().path).availableBytes
    }.getOrDefault(0L)

    private fun format(bytes: Long): String = Formatter.formatFileSize(context, bytes)

    private fun short(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_ERROR_CHARS)

    private fun shortSuffix(value: String): String =
        short(value).takeIf { it.isNotEmpty() }?.let { " ($it)." } ?: ""

    private fun failure(message: String, path: String = "exec") =
        ShellExecution(ok = false, text = message, path = path)

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val TRIM_TIMEOUT_MS = 60_000L
        const val TRIM_SETTLE_MS = 1_500L
        const val TRIM_TARGET = "4096G"
        const val MAX_PACKAGE_NAMES = 30
        const val MAX_ERROR_CHARS = 240
    }
}
