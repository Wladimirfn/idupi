package com.idupi.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.idupi.app.MainActivity
import com.idupi.app.data.phone.shell.ShellBridgeRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class IduPiForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectivity: ConnectivityManager? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        registerWifiRecovery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_SERVICE) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        val taskMessage = intent?.getStringExtra(EXTRA_TASK_MESSAGE) ?: "Ejecutando tarea Pi CLI en tu PC..."
        val notification = createNotification(taskMessage)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IDUPI::BackgroundExecutionLock").apply {
                acquire(10 * 60 * 1000L) // 10 minutos max
            }
        } catch (e: Exception) {
            // Ignore if not permitted
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
        wakeLock = null
    }

    private fun stopForegroundService() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        unregisterWifiRecovery()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Phone-shell T7: while this foreground service runs, a Wi-Fi connection
     * becoming available is a recovery trigger. Registering the callback also
     * fires it once when Wi-Fi is already up; the recovery's cooldown and
     * active-bridge check make that a cheap no-op.
     */
    private fun registerWifiRecovery() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        connectivity = manager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = manager.getNetworkCapabilities(network) ?: return
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
                serviceScope.launch {
                    ShellBridgeRecovery.recover(applicationContext, trigger = "wifi")
                }
            }
        }
        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                callback,
            )
            wifiCallback = callback
        }.onFailure { error ->
            Log.w(TAG, "recovery(wifi): could not register callback: ${error.javaClass.simpleName}")
        }
    }

    private fun unregisterWifiRecovery() {
        val manager = connectivity ?: return
        val callback = wifiCallback ?: return
        wifiCallback = null
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IDUPI Ejecución en Segundo Plano",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene activa la conexión con Pi CLI cuando minimizas la app"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🤖 IDUPI: Procesando en Segundo Plano")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "idupi_background_channel"
        const val NOTIFICATION_ID = 8788
        const val ACTION_START_SERVICE = "ACTION_START_IDUPI_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_IDUPI_SERVICE"
        const val EXTRA_TASK_MESSAGE = "EXTRA_TASK_MESSAGE"

        private const val TAG = "IdupiPhoneShell"

        fun start(context: Context, taskMessage: String = "Procesando en tu PC...") {
            val intent = Intent(context, IduPiForegroundService::class.java).apply {
                action = ACTION_START_SERVICE
                putExtra(EXTRA_TASK_MESSAGE, taskMessage)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, IduPiForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
