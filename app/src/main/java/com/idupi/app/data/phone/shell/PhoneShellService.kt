package com.idupi.app.data.phone.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.idupi.app.MainActivity
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Short-lived foreground service that pairs the phone and bootstraps the shell
 * helper (phone-shell T3/T3b).
 *
 * Pairing follows Shizuku's model: discovery of `_adb-tls-pairing._tcp`, a
 * notification with a [RemoteInput] for the six-digit code while the system's
 * Wireless debugging dialog stays open, then `pair` and the bootstrap. The
 * notification is the only place the code is typed; it is never logged.
 *
 * The same service handles "Iniciar puente", which only reconnects and starts
 * the helper. It stops after a success/failure or after five minutes.
 */
class PhoneShellService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Lazy: a Service has no base context until attachBaseContext, which runs
    // after construction; resolving it in an initializer crashed on start.
    private val notifications by lazy { NotificationManagerCompat.from(this) }
    private var flowJob: Job? = null
    private var codeChannel: Channel<String>? = null
    private var notificationTitle: String = PAIR_TITLE
    private var flowFinished = false
    @Volatile private var pairingHost: String? = null

    override fun onCreate() {
        super.onCreate()
        active = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAIR -> {
                if (flowJob?.isActive == true) return START_NOT_STICKY
                // Ready before the notification can be answered.
                codeChannel = Channel(Channel.UNLIMITED)
                notificationTitle = PAIR_TITLE
                flowFinished = false
                startForegroundNow(PAIR_TITLE, PAIR_INITIAL_TEXT, withCode = true)
                startPairingFlow()
            }
            ACTION_START_BRIDGE -> {
                if (flowJob?.isActive == true) return START_NOT_STICKY
                notificationTitle = BRIDGE_TITLE
                flowFinished = false
                startForegroundNow(BRIDGE_TITLE, BRIDGE_INITIAL_TEXT, withCode = false)
                startBridgeFlow()
            }
            ACTION_RECOVER_BOOT -> {
                if (flowJob?.isActive == true) return START_NOT_STICKY
                notificationTitle = RECOVERY_TITLE
                flowFinished = false
                startForegroundNow(RECOVERY_TITLE, RECOVERY_INITIAL_TEXT, withCode = false)
                startRecoveryFlow(boot = true)
            }
            ACTION_CODE -> {
                val code = intent.getStringExtra(EXTRA_CODE) ?: return START_NOT_STICKY
                codeChannel?.trySend(code)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        if (active === this) active = null
        flowFinished = true
        if (PhoneShellStatus.state.value.busy) {
            PhoneShellStatus.failed(
                PhoneShellStatus.state.value.stage,
                "La operación se interrumpió. Vuelve a intentarlo."
            )
        }
        super.onDestroy()
    }

    // --- pairing ---------------------------------------------------------

    private fun startPairingFlow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishFailure(UNAVAILABLE_TEXT, PhoneShellStage.UNAVAILABLE)
            return
        }
        PhoneShellStatus.working("Buscando la depuración inalámbrica…")
        flowJob = scope.launch {
            val result = withTimeoutOrNull(SERVICE_TIMEOUT_MS) { runPairingFlow() }
            if (result == null) {
                val stage = if (PhoneShellStore(this@PhoneShellService).paired) {
                    PhoneShellStage.PAIRED
                } else {
                    PhoneShellStage.NOT_PAIRED
                }
                finishFailure("Se agotó el tiempo. Vuelve a intentarlo.", stage)
            } else {
                when (result) {
                    is FlowResult.Success -> finishSuccess()
                    is FlowResult.Failure -> finishFailure(result.message, result.stage)
                }
            }
        }
    }

    private suspend fun runPairingFlow(): FlowResult = coroutineScope {
        val port = CompletableDeferred<Int>()
        val discovery = launch { discoverPairingPort(port) }
        val reminder = launch {
            delay(PORT_REMINDER_MS)
            if (!port.isCompleted && !flowFinished) {
                PhoneShellStatus.working(NO_PORT_TEXT)
                updateNotification(NO_PORT_TEXT, withCode = true)
            }
        }
        try {
            openDeveloperSettings()
            try {
                awaitPairing(port)
            } catch (e: ShellBootstrapException) {
                return@coroutineScope FlowResult.Failure(
                    e.message ?: GENERIC_ERROR,
                    PhoneShellStage.NOT_PAIRED,
                )
            }
            PhoneShellStatus.settled(PhoneShellStage.PAIRED, "Vinculado. Preparando el puente…")
            updateNotification("Vinculado. Preparando el puente…", withCode = false)
            val bootstrap = ShellBridgeBootstrap(this@PhoneShellService, onStep = ::step).run()
            bootstrap.fold(
                onSuccess = { FlowResult.Success },
                onFailure = { error ->
                    Log.w(TAG, "bootstrap failed: ${describe(error, "")}")
                    FlowResult.Failure(
                        message = error.message ?: GENERIC_ERROR,
                        stage = PhoneShellStage.PAIRED,
                    )
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pairing flow failed: ${describe(e, "")}")
            FlowResult.Failure(GENERIC_ERROR, PhoneShellStage.NOT_PAIRED)
        } finally {
            discovery.cancel()
            reminder.cancel()
        }
    }

    /**
     * Waits for a valid code and a discovered pairing port, then pairs. A code
     * typed before the port is known waits for discovery; a wrong code keeps
     * the RemoteInput alive so the owner can retry while the dialog stays open.
     */
    private suspend fun awaitPairing(port: CompletableDeferred<Int>) {
        var attempts = 0
        val channel = codeChannel ?: throw ShellBootstrapException(GENERIC_ERROR)
        while (true) {
            val code = channel.receive()
            if (!PairingCode.isValid(code)) {
                PhoneShellStatus.working(INVALID_CODE_TEXT)
                updateNotification(INVALID_CODE_TEXT, withCode = true)
                continue
            }
            if (!port.isCompleted) {
                // The owner typed the code before mDNS found the port: keep the
                // dialog open and wait instead of failing the attempt.
                PhoneShellStatus.working(NO_PORT_TEXT)
                updateNotification(NO_PORT_TEXT, withCode = true)
            }
            val pairingPort = port.await()
            PhoneShellStatus.working("Vinculando…")
            updateNotification("Vinculando…", withCode = false)
            if (pairOnAnyHost(pairingPort, code)) {
                PhoneShellStore(this).paired = true
                return
            }
            attempts += 1
            if (attempts >= MAX_PAIR_ATTEMPTS) {
                throw ShellBootstrapException(TOO_MANY_ATTEMPTS_TEXT)
            }
            PhoneShellStatus.working(WRONG_CODE_TEXT)
            updateNotification(WRONG_CODE_TEXT, withCode = true)
        }
    }

    /**
     * Pairs over loopback first (Shizuku's model), then over the address mDNS
     * resolved, in case this adbd's pairing server does not accept loopback.
     * Each failure is logged with its exception chain so a device report says
     * why; the code itself is masked out of every message.
     */
    private suspend fun pairOnAnyHost(port: Int, code: String): Boolean {
        val client = AdbShellClient.get(this)
        val hosts = listOfNotNull(IdupiAdbConnectionManager.LOOPBACK_ADDRESS, pairingHost).distinct()
        for (host in hosts) {
            val result = client.pair(port, code, host)
            if (result.isSuccess) {
                Log.i(TAG, "paired via $host:$port")
                return true
            }
            Log.w(TAG, "pair via $host:$port failed: ${describe(result.exceptionOrNull(), code)}")
        }
        return false
    }

    private fun describe(error: Throwable?, secret: String): String {
        val parts = generateSequence(error) { it.cause }.take(6).map { e ->
            "${e.javaClass.name}: ${e.message.orEmpty()}"
        }
        val text = parts.joinToString(" <- ")
        return if (secret.isEmpty()) text else text.replace(secret, "******")
    }

    /**
     * Resolves the pairing port advertised while the system dialog is open.
     * The port is all we need: the pairing handshake itself goes to loopback.
     */
    private suspend fun discoverPairingPort(port: CompletableDeferred<Int>) {
        try {
            val found = awaitPairingPort()
            if (flowFinished) return
            // Publish the ready message before waking the waiting code, so a
            // code typed early does not get its "Vinculando…" overwritten.
            PhoneShellStatus.working(PORT_READY_TEXT)
            updateNotification(PORT_READY_TEXT, withCode = true)
            port.complete(found)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            port.completeExceptionally(e)
        }
    }

    private suspend fun awaitPairingPort(): Int {
        val discovery = AtomicReference<AdbMdns?>(null)
        try {
            return suspendCancellableCoroutine { continuation ->
                val mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
                    if (port > 0 && continuation.isActive) {
                        pairingHost = host?.hostAddress
                        Log.i(TAG, "pairing service found at ${host?.hostAddress}:$port")
                        continuation.resume(port)
                    }
                }
                discovery.set(mdns)
                continuation.invokeOnCancellation { runCatching { mdns.stop() } }
                try {
                    mdns.start()
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        } finally {
            runCatching { discovery.get()?.stop() }
        }
    }

    private fun openDeveloperSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            runCatching {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    // --- bridge-only -----------------------------------------------------

    private fun startBridgeFlow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishFailure(UNAVAILABLE_TEXT, PhoneShellStage.UNAVAILABLE)
            return
        }
        if (!PhoneShellStore(this).paired) {
            finishFailure("Primero vincula el teléfono con Vincular.", PhoneShellStage.NOT_PAIRED)
            return
        }
        PhoneShellStatus.working("Preparando el puente…")
        startRecoveryFlow(boot = false)
    }

    /**
     * Runs the shared T6/T7 recovery. The boot trigger first waits up to two
     * minutes for Wi-Fi and stays quiet when there is nothing to report; the
     * Ajustes button is forced and mirrors every outcome in the notification.
     */
    private fun startRecoveryFlow(boot: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishFailure(UNAVAILABLE_TEXT, PhoneShellStage.UNAVAILABLE)
            return
        }
        flowJob = scope.launch {
            val outcome = withTimeoutOrNull(SERVICE_TIMEOUT_MS) {
                if (boot &&
                    !ShellRecoveryNetwork.awaitWifi(this@PhoneShellService, BOOT_WIFI_TIMEOUT_MS)
                ) {
                    Log.i(
                        TAG,
                        "recovery(boot): skipped: no wifi after ${BOOT_WIFI_TIMEOUT_MS / 1000}s",
                    )
                    ShellBridgeRecoveryOutcome.Skipped(ShellBridgeRecoverySkip.NOT_ON_WIFI)
                } else {
                    ShellBridgeRecovery.recover(
                        context = this@PhoneShellService,
                        forced = !boot,
                        trigger = if (boot) "boot" else "settings",
                        onStep = if (boot) {
                            { message -> updateNotification(message, withCode = false) }
                        } else {
                            ::step
                        },
                    )
                }
            }
            if (boot) {
                finishBootRecovery(outcome)
            } else {
                finishSettingsRecovery(outcome)
            }
        }
    }

    private fun finishBootRecovery(outcome: ShellBridgeRecoveryOutcome?) {
        when (outcome) {
            null -> stopQuietly()
            is ShellBridgeRecoveryOutcome.Failed -> {
                Log.w(TAG, "recovery(boot) failed: ${outcome.reason}")
                finishFailure(outcome.reason, PhoneShellStage.PAIRED)
            }
            else -> stopQuietly()
        }
    }

    private fun finishSettingsRecovery(outcome: ShellBridgeRecoveryOutcome?) {
        when (outcome) {
            null -> finishFailure(
                "Se agotó el tiempo. Vuelve a intentarlo.",
                PhoneShellStage.PAIRED,
            )
            ShellBridgeRecoveryOutcome.Recovered,
            ShellBridgeRecoveryOutcome.AlreadyActive,
            -> finishSuccess()
            is ShellBridgeRecoveryOutcome.Skipped -> {
                val stage = if (outcome.reason == ShellBridgeRecoverySkip.NOT_PAIRED) {
                    PhoneShellStage.NOT_PAIRED
                } else {
                    PhoneShellStage.PAIRED
                }
                finishFailure(ShellBridgeRecoveryPolicy.skippedMessage(outcome.reason), stage)
            }
            is ShellBridgeRecoveryOutcome.Failed ->
                finishFailure(outcome.reason, PhoneShellStage.PAIRED)
        }
    }

    private fun stopQuietly() {
        flowFinished = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- notification ----------------------------------------------------

    private fun step(message: String) {
        PhoneShellStatus.working(message)
        updateNotification(message, withCode = false)
    }

    private fun finishSuccess() {
        flowFinished = true
        PhoneShellStatus.settled(PhoneShellStage.BRIDGE_ACTIVE, SUCCESS_TEXT)
        postFinal(SUCCESS_TEXT, success = true)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun finishFailure(message: String, stage: PhoneShellStage) {
        flowFinished = true
        PhoneShellStatus.failed(stage, message)
        postFinal(message, success = false)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun startForegroundNow(title: String, text: String, withCode: Boolean) {
        val notification = buildNotification(title, text, withCode, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String, withCode: Boolean) {
        if (flowFinished) return
        runCatching {
            notifications.notify(
                NOTIFICATION_ID,
                buildNotification(notificationTitle, text, withCode, ongoing = true),
            )
        }
    }

    private fun postFinal(message: String, success: Boolean) {
        runCatching {
            notifications.notify(
                NOTIFICATION_ID,
                buildNotification(
                    title = if (success) SUCCESS_TITLE else FAILURE_TITLE,
                    text = message,
                    withCode = false,
                    ongoing = false,
                ),
            )
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        withCode: Boolean,
        ongoing: Boolean,
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(openAppIntent())
        if (withCode) builder.addAction(codeAction())
        return builder.build()
    }

    private fun codeAction(): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(RESULT_KEY)
            .setLabel("Código de vinculación")
            .build()
        val intent = Intent(this, PhoneShellPairingReceiver::class.java)
            .setAction(ACTION_REPLY)
        // Android 12+ requires a mutable PendingIntent for RemoteInput results.
        // The receiver is unexported, so only this app's PendingIntent can
        // reach it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(this, REQUEST_REPLY, intent, flags)
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Escribir código",
            pending,
        )
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Acceso avanzado del teléfono",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Vinculación con la depuración inalámbrica y puente de shell"
            }
        )
    }

    private sealed interface FlowResult {
        data object Success : FlowResult
        data class Failure(val message: String, val stage: PhoneShellStage) : FlowResult
    }

    companion object {
        const val ACTION_PAIR = "com.idupi.app.phone_shell.action.PAIR"
        const val ACTION_START_BRIDGE = "com.idupi.app.phone_shell.action.START_BRIDGE"
        const val ACTION_RECOVER_BOOT = "com.idupi.app.phone_shell.action.RECOVER_BOOT"
        const val ACTION_CODE = "com.idupi.app.phone_shell.action.CODE"
        const val ACTION_REPLY = "com.idupi.app.phone_shell.action.REPLY"
        const val EXTRA_CODE = "com.idupi.app.phone_shell.extra.CODE"
        const val RESULT_KEY = "com.idupi.app.phone_shell.extra.PAIRING_CODE"

        @Volatile
        private var active: PhoneShellService? = null

        private const val TAG = "IdupiPhoneShell"
        private const val CHANNEL_ID = "idupi_phone_shell"
        private const val NOTIFICATION_ID = 8790
        private const val REQUEST_OPEN = 8791
        private const val REQUEST_REPLY = 8792

        private const val SERVICE_TIMEOUT_MS = 5 * 60_000L
        private const val BOOT_WIFI_TIMEOUT_MS = 2 * 60_000L
        private const val PORT_REMINDER_MS = 30_000L
        private const val MAX_PAIR_ATTEMPTS = 5

        private const val PAIR_TITLE = "Vincula este teléfono"
        private const val PAIR_INITIAL_TEXT =
            "Abre Depuración inalámbrica, elige «Vincular dispositivo con código de vinculación» y escribe el código aquí."
        private const val PORT_READY_TEXT =
            "Puerto encontrado. Escribe el código de 6 dígitos que muestra el teléfono."
        private const val NO_PORT_TEXT =
            "No se encontró el puerto de vinculación. Mantén abierto «Vincular dispositivo con código de vinculación»."
        private const val INVALID_CODE_TEXT = "El código debe tener 6 dígitos."
        private const val WRONG_CODE_TEXT =
            "No se pudo vincular. Revisa el código y mantén abierto el diálogo de vinculación."
        private const val TOO_MANY_ATTEMPTS_TEXT =
            "No se pudo vincular. Cierra y abre de nuevo «Vincular dispositivo con código de vinculación»."

        private const val BRIDGE_TITLE = "Acceso avanzado del teléfono"
        private const val BRIDGE_INITIAL_TEXT = "Conectando con la depuración inalámbrica…"
        private const val RECOVERY_TITLE = "Recuperando el puente"
        private const val RECOVERY_INITIAL_TEXT = "Buscando Wi-Fi…"
        private const val SUCCESS_TITLE = "Puente activo"
        private const val SUCCESS_TEXT = "El puente de IDUPI está activo."
        private const val FAILURE_TITLE = "No se pudo completar"
        private const val UNAVAILABLE_TEXT = "No disponible en este teléfono (API < 30)."
        private const val GENERIC_ERROR = "No se pudo completar. Vuelve a intentarlo."

        fun startPairing(context: Context) =
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneShellService::class.java).setAction(ACTION_PAIR),
            )

        fun startBridge(context: Context) =
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneShellService::class.java).setAction(ACTION_START_BRIDGE),
            )

        /**
         * Boot trigger (phone-shell T6): the service waits for Wi-Fi and then
         * runs the shared recovery. A foreground service keeps the process
         * alive past the broadcast's short lifetime.
         */
        fun startBootRecovery(context: Context) =
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneShellService::class.java).setAction(ACTION_RECOVER_BOOT),
            )

        /**
         * Delivers a RemoteInput code to the live service. The foreground
         * service is what keeps the process alive, so the direct path is the
         * normal one; the startService fallback only covers a stale process.
         */
        fun deliverCode(context: Context, code: String) {
            val service = active
            if (service != null) {
                service.codeChannel?.trySend(code)
                return
            }
            runCatching {
                context.startService(
                    Intent(context, PhoneShellService::class.java)
                        .setAction(ACTION_CODE)
                        .putExtra(EXTRA_CODE, code)
                )
            }
        }
    }
}
