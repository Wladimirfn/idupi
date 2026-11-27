package com.idupi.app.data.phone.shell

import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The helper provider could not be reached, refused the caller, or timed out. */
class BridgeUnavailableException(
    message: String = "puente no disponible",
    cause: Throwable? = null,
) : Exception(message, cause)

/** Whether the helper instrumentation currently holds the shell identity. */
data class BridgeStatus(val active: Boolean)

/** One helper response: owner-facing text plus the execution path. */
data class BridgeResult(
    val ok: Boolean,
    val text: String,
    val path: String? = null,
    val freeBefore: Long? = null,
    val freeAfter: Long? = null,
)

/**
 * Client for `content://com.idupi.shell.bridge` (phone-shell T4).
 *
 * Every call runs off the main thread with a hard deadline: a Binder call that
 * blocks past it is abandoned so the accessibility worker never hangs. A
 * missing provider, a signature refusal or a timeout all map to "puente no
 * disponible".
 */
class ShellBridgeClient private constructor(private val appContext: Context) {

    private val resolver = appContext.contentResolver
    private val workers: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "idupi-bridge").apply { isDaemon = true }
    }

    suspend fun status(): Result<BridgeStatus> = resultOf {
        val bundle = call(BRIDGE_URI, METHOD_STATUS, null, null, STATUS_TIMEOUT_MS)
            ?: throw BridgeUnavailableException()
        BridgeStatus(active = bundle.getBoolean(EXTRA_ACTIVE, false))
    }

    suspend fun execute(operation: ShellOperation): Result<BridgeResult> = resultOf {
        val extras = Bundle().apply {
            putString(EXTRA_ACTION, operation.wireAction())
            operation.packageName()?.let { putString(EXTRA_PACKAGE, it) }
            operation.mode()?.let { putString(EXTRA_MODE, it.wire) }
            operation.topic()?.let { putString(EXTRA_TOPIC, it.wire) }
        }
        val aggregate = operation is ShellOperation.TrimCaches
        val uninstall = operation is ShellOperation.Uninstall
        val bundle = call(
            BRIDGE_URI,
            METHOD_SHELL,
            null,
            extras,
            when {
                aggregate -> TRIM_TIMEOUT_MS
                uninstall -> UNINSTALL_TIMEOUT_MS
                else -> DEFAULT_TIMEOUT_MS
            },
        ) ?: throw BridgeUnavailableException()
        bundle.toResult()
    }

    suspend fun kickAccessibility(): Result<BridgeResult> = resultOf {
        val bundle = call(BRIDGE_URI, METHOD_KICK, null, null, KICK_TIMEOUT_MS)
            ?: throw BridgeUnavailableException()
        bundle.toResult()
    }

    private fun Bundle.toResult(): BridgeResult {
        val ok = getBoolean(EXTRA_OK, false)
        val text = getString(EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            ?: if (ok) "Listo." else "No se pudo ejecutar."
        return BridgeResult(
            ok = ok,
            text = text,
            path = getString(EXTRA_PATH),
            freeBefore = longOrNull(EXTRA_FREE_BEFORE),
            freeAfter = longOrNull(EXTRA_FREE_AFTER),
        )
    }

    private fun Bundle.longOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null

    private suspend fun call(
        uri: Uri,
        method: String,
        arg: String?,
        extras: Bundle?,
        timeoutMs: Long,
    ): Bundle? = withContext(Dispatchers.IO) {
        val future = workers.submit(Callable { resolver.call(uri, method, arg, extras) })
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw BridgeUnavailableException()
        } catch (e: ExecutionException) {
            throw BridgeUnavailableException(cause = e.cause ?: e)
        } catch (e: InterruptedException) {
            future.cancel(true)
            throw CancellationException("bridge call was cancelled").apply { initCause(e) }
        }
    }

    private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(if (e is BridgeUnavailableException) e else BridgeUnavailableException(cause = e))
    }

    companion object {
        private val BRIDGE_URI: Uri = Uri.parse("content://com.idupi.shell.bridge")

        private const val METHOD_STATUS = "status"
        private const val METHOD_SHELL = "shell"
        private const val METHOD_KICK = "kick-accessibility"

        private const val EXTRA_ACTION = "action"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TOPIC = "topic"

        private const val EXTRA_OK = "ok"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_ACTIVE = "active"
        private const val EXTRA_FREE_BEFORE = "freeBefore"
        private const val EXTRA_FREE_AFTER = "freeAfter"

        private const val STATUS_TIMEOUT_MS = 10_000L
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val UNINSTALL_TIMEOUT_MS = 60_000L
        private const val TRIM_TIMEOUT_MS = 70_000L
        private const val KICK_TIMEOUT_MS = 10_000L

        private val LOCK = Any()

        @Volatile
        private var instance: ShellBridgeClient? = null

        fun get(context: Context): ShellBridgeClient {
            instance?.let { return it }
            synchronized(LOCK) {
                instance?.let { return it }
                return ShellBridgeClient(context.applicationContext).also { instance = it }
            }
        }
    }
}
