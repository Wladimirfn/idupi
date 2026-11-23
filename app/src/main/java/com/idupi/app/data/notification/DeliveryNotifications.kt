package com.idupi.app.data.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.idupi.app.MainActivity
import com.idupi.app.domain.model.DeliveryInfo

/**
 * "APK lista" notifications on their own channel, so the owner can mute
 * deliveries without muting supervisor alerts.
 *
 * Tapping opens [MainActivity] with [EXTRA_OPEN_DELIVERIES]; MainActivity
 * forwards it to the navigation state, which lands on the Entregas screen.
 */
class DeliveryNotifications(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    /**
     * `POST_NOTIFICATIONS` is a runtime permission on API 33+, and the user
     * may also have turned the channel off entirely. Deliveries still appear
     * in the list when posting is unavailable; only the ping is skipped.
     */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun show(delivery: DeliveryInfo) {
        if (!canPost()) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_DELIVERIES, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            delivery.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("APK lista: ${delivery.name}")
            .setContentText("Tocá para instalar en este teléfono")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(delivery.id.hashCode(), notification)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "APKs enviadas desde la PC con idupi-push"
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "idupi_deliveries"
        const val CHANNEL_NAME = "Entregas de la PC"

        /** Boolean extra MainActivity turns into "open the Entregas screen". */
        const val EXTRA_OPEN_DELIVERIES = "com.idupi.app.extra.OPEN_DELIVERIES"
    }
}
