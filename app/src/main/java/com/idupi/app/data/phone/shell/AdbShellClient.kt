package com.idupi.app.data.phone.shell

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * mDNS returned no `_adb-tls-connect._tcp` service. Distinct from a rejected
 * key: right after boot adbd may fail to register it (mdnsd not running yet,
 * error -65563), while the TLS port itself is up.
 */
class AdbServiceNotFoundException(message: String) : IOException(message)

/** The adb operation did not finish inside its deadline. */
class AdbTimeoutException(message: String) : IOException(message)

/**
 * In-app adb client (phone-shell T2).
 *
 * It talks to the phone's own `adbd` over loopback, the same channel Shizuku
 * uses: pair once with the six-digit code from Wireless debugging, then open
 * `shell:` streams as uid 2000 to install/start the helper (T3b).
 *
 * Every blocking libadb call runs on a bounded worker thread:
 * - `pair` and `connect` get a hard deadline from the worker future; `connect`
 *   also uses the manager's adb handshake timeout;
 * - `exec` additionally closes the active stream on timeout, which unblocks
 *   the reader (`AdbStream.read` waits on a monitor) and terminates the remote
 *   command.
 *
 * The pairing code is passed straight to libadb and is never logged or put in
 * an error message.
 */
class AdbShellClient private constructor(private val appContext: Context) {

    private val workers: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "idupi-adb").apply { isDaemon = true }
    }

    /**
     * Pairs with the phone's adbd on the pairing port shown under Wireless
     * debugging. The daemon listens on loopback, so the host is 127.0.0.1.
     *
     * Pairing exists only from Android 11 (API 30); older phones fail with a
     * clear message instead of an obscure TLS error.
     */
    suspend fun pair(
        port: Int,
        code: String,
        host: String = IdupiAdbConnectionManager.LOOPBACK_ADDRESS,
    ): Result<Unit> = resultOf {
        requireApi30("pairing")
        requirePort(port)
        if (!PairingCode.isValid(code)) {
            throw IllegalArgumentException("The pairing code must be exactly six digits.")
        }
        blocking(PAIR_TIMEOUT_MS, "adb pair") {
            val paired = manager().pair(host, port, code)
            if (!paired) throw IOException("The phone rejected the adb pairing.")
        }
    }

    /**
     * Connects to the `_adb-tls-connect._tcp` service advertised by Wireless
     * debugging. Only the port comes from mDNS; the socket itself goes to
     * 127.0.0.1, exactly like a PC-less Shizuku-style client.
     */
    suspend fun connect(): Result<Unit> = resultOf {
        val connection = manager()
        if (connection.isConnected) return@resultOf
        blocking(CONNECT_TIMEOUT_MS, "adb connect") {
            val port = discoverConnectPort()
            connection.connect(port)
            if (!connection.isConnected) {
                throw IOException("Could not establish the adb connection on port $port.")
            }
        }
    }

    /** Connects to an explicit loopback port, for a known fixed adbd port. */
    suspend fun connect(port: Int): Result<Unit> = resultOf {
        requirePort(port)
        val connection = manager()
        if (connection.isConnected) return@resultOf
        blocking(CONNECT_TIMEOUT_MS, "adb connect") {
            connection.connect(port)
            if (!connection.isConnected) {
                throw IOException("Could not establish the adb connection on port $port.")
            }
        }
    }

    /**
     * Runs one command through the `shell:` service and returns its merged
     * stdout/stderr plus the exit status read from the appended marker.
     *
     * A timeout fails the call; the stream is closed so the remote command
     * does not keep running after the caller gave up.
     */
    suspend fun exec(
        command: String,
        timeoutMs: Long = DEFAULT_EXEC_TIMEOUT_MS,
    ): Result<ShellResult> = resultOf {
        require(command.isNotBlank()) { "command must not be blank" }
        require(timeoutMs > 0) { "timeout must be positive" }
        val connection = manager()
        if (!connection.isConnected) {
            throw IOException("Not connected to the phone's adb daemon.")
        }
        // Local to this call: a concurrent exec cannot close the wrong stream.
        val streamOnWorker = AtomicReference<AdbStream?>(null)
        blocking(
            timeoutMs = timeoutMs,
            label = "adb shell",
            onTimeout = { streamOnWorker.get()?.let { stream -> runCatching { stream.close() } } },
        ) {
            val stream = connection.openStream("shell:${ShellCommands.withExitStatus(command)}")
            streamOnWorker.set(stream)
            try {
                ShellOutputParser.parse(readAll(stream))
            } finally {
                streamOnWorker.set(null)
                runCatching { stream.close() }
            }
        }
    }

    /**
     * Installs or updates an APK by streaming it into `cmd package install`,
     * the same stdin protocol `adb install` uses (phone-shell T3b).
     *
     * The command runs on the raw `exec:` service so adbd wires our stream to
     * the process stdin. `-S <size>` makes the package manager read exactly
     * [apkBytes] and commit, so no half-close is needed. The stream is written
     * in negotiated max-data chunks: libadb waits for the peer's OKAY at the
     * start of every [AdbStream.write], which keeps each WRTE legal.
     *
     * The returned [ShellResult.output] is the package manager's verdict
     * (`Success` / `Failure [REASON]`), parsed by [InstallResultParser].
     */
    suspend fun installPackage(
        apkBytes: ByteArray,
        timeoutMs: Long = DEFAULT_INSTALL_TIMEOUT_MS,
    ): Result<ShellResult> = resultOf {
        require(apkBytes.isNotEmpty()) { "apk must not be empty" }
        require(timeoutMs > 0) { "timeout must be positive" }
        val connection = manager()
        if (!connection.isConnected) {
            throw IOException("Not connected to the phone's adb daemon.")
        }
        val command = ShellCommands.withExitStatus(
            ShellCommands.installStreamed(apkBytes.size.toLong())
        )
        val streamOnWorker = AtomicReference<AdbStream?>(null)
        blocking(
            timeoutMs = timeoutMs,
            label = "adb install",
            onTimeout = { streamOnWorker.get()?.let { stream -> runCatching { stream.close() } } },
        ) {
            val stream = connection.openStream("exec:$command")
            streamOnWorker.set(stream)
            try {
                val chunkSize = runCatching { connection.adbConnection?.maxData }
                    .getOrNull()
                    ?.coerceIn(MIN_STREAM_CHUNK, MAX_STREAM_CHUNK)
                    ?: DEFAULT_STREAM_CHUNK
                var offset = 0
                var writeError: IOException? = null
                while (offset < apkBytes.size) {
                    val length = minOf(chunkSize, apkBytes.size - offset)
                    try {
                        stream.write(apkBytes, offset, length)
                    } catch (e: IOException) {
                        // The package manager can reject the APK before the
                        // last byte; its verdict is still on the stream.
                        writeError = e
                        break
                    }
                    offset += length
                }
                val raw = try {
                    readAll(stream)
                } catch (e: IOException) {
                    if (writeError == null) throw e
                    ""
                }
                if (writeError != null && raw.isBlank()) throw writeError
                ShellOutputParser.parse(raw)
            } finally {
                streamOnWorker.set(null)
                runCatching { stream.close() }
            }
        }
    }

    suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        try {
            manager().isConnected
        } catch (e: Exception) {
            false
        }
    }

    suspend fun disconnect(): Result<Unit> = resultOf {
        withContext(Dispatchers.IO) { manager().disconnect() }
    }

    private fun manager(): AbsAdbConnectionManager =
        IdupiAdbConnectionManager.getInstance(appContext)

    /**
     * Resolves the `_adb-tls-connect._tcp` service and returns its port. The
     * listener gets the Wi-Fi address; only the port is used so the actual
     * connection stays on loopback.
     */
    private fun discoverConnectPort(): Int {
        val port = AtomicInteger(-1)
        val resolved = CountDownLatch(1)
        val discovery = AdbMdns(appContext, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { _, foundPort ->
            if (foundPort > 0) {
                port.set(foundPort)
                resolved.countDown()
            }
        }
        discovery.start()
        try {
            if (!resolved.await(DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS) || port.get() <= 0) {
                throw AdbServiceNotFoundException(
                    "No wireless-debugging adb service found. Turn on Wireless debugging and try again."
                )
            }
        } finally {
            runCatching { discovery.stop() }
        }
        return port.get()
    }

    private fun readAll(stream: AdbStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val read = try {
                stream.read(buffer, 0, buffer.size)
            } catch (e: IOException) {
                // libadb throws once the peer closed the stream instead of
                // always returning -1. The bytes already read are complete
                // output; anything else is a real failure.
                if (stream.isClosed) break
                throw e
            }
            if (read < 0) break
            output.write(buffer, 0, read)
            // Every command carries the exit marker; stop at it rather than
            // wait for the close (see ShellOutputParser.isComplete).
            if (ShellOutputParser.isComplete(output.toString(Charsets.UTF_8.name()))) break
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /**
     * Runs [block] on a worker thread with a hard deadline. Kotlin's timeout
     * cannot interrupt a blocking socket/handshake call, so the future itself
     * is bounded and cancelled.
     */
    private suspend fun <T> blocking(
        timeoutMs: Long,
        label: String,
        onTimeout: () -> Unit = {},
        block: () -> T,
    ): T = withContext(Dispatchers.IO) {
        val future = workers.submit(Callable { block() })
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            onTimeout()
            future.cancel(true)
            throw AdbTimeoutException("$label timed out after $timeoutMs ms.")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            onTimeout()
            future.cancel(true)
            throw CancellationException("$label was cancelled.").apply { initCause(e) }
        }
    }

    private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun requireApi30(operation: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException(
                "ADB $operation needs Android 11 (API 30) or newer; " +
                    "this phone reports API ${Build.VERSION.SDK_INT}."
            )
        }
    }

    private fun requirePort(port: Int) {
        require(port in 1..65535) { "port must be between 1 and 65535" }
    }

    companion object {
        const val DEFAULT_EXEC_TIMEOUT_MS = 15_000L

        private const val PAIR_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
        private const val DEFAULT_INSTALL_TIMEOUT_MS = 120_000L
        private const val READ_BUFFER_BYTES = 4096
        private const val DEFAULT_STREAM_CHUNK = 32 * 1024
        private const val MIN_STREAM_CHUNK = 4 * 1024
        private const val MAX_STREAM_CHUNK = 64 * 1024
        private val LOCK = Any()

        @Volatile
        private var instance: AdbShellClient? = null

        fun get(context: Context): AdbShellClient {
            instance?.let { return it }
            synchronized(LOCK) {
                instance?.let { return it }
                return AdbShellClient(context.applicationContext).also { instance = it }
            }
        }
    }
}
