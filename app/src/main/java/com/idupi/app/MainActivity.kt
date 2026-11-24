package com.idupi.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.idupi.app.data.connection.ConnectionStorage
import com.idupi.app.data.notification.DeliveryNotifications
import com.idupi.app.data.notification.NotificationPermissionState
import com.idupi.app.data.notification.currentNotificationPermissionPlan
import com.idupi.app.ui.navigation.AppNavigation
import com.idupi.app.ui.navigation.AppNavigationIntents
import com.idupi.app.ui.navigation.Screen

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Granted or not, the one-time answer is what matters: the Entregas
        // banner takes over from here, including "Abrir ajustes".
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Auto-cargar perfil de conexión guardado si existe
        com.idupi.app.data.IduPiClientProvider.configureFromStorage(this)
        // The delivery poller needs the client configured first.
        (application as IduPiApp).startDeliveryWatcher()

        requestNotificationPermissionOnce()

        openRequestedScreen(intent)

        setContent {
            // Theme wrapping happens inside AppNavigation, which resolves the
            // persisted ThemeSettings into the palette IDUPITheme applies.
            AppNavigation()
        }
    }

    /**
     * Android 13+ makes `POST_NOTIFICATIONS` a runtime permission: without it
     * [DeliveryNotifications.show] silently skips every "APK lista" ping. Ask
     * once, on the first launch after a PC is configured -- asking before there
     * is a server to receive deliveries from is noise -- and never nag again:
     * the Entregas banner points at the system settings afterwards.
     */
    private fun requestNotificationPermissionOnce() {
        val plan = currentNotificationPermissionPlan(
            context = this,
            hasConfiguredServer = ConnectionStorage(this).hasConfiguredServer(),
            alreadyAsked = NotificationPermissionState.wasAsked(this)
        )
        if (!plan.requestOnLaunch) return

        NotificationPermissionState.markAsked(this)
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedScreen(intent)
    }

    /**
     * A delivery notification ("APK lista") carries
     * [DeliveryNotifications.EXTRA_OPEN_DELIVERIES]; honoring it here (cold
     * start and warm taps alike) is what lands the owner on the list instead
     * of the terminals.
     */
    private fun openRequestedScreen(intent: Intent?) {
        val openDeliveries =
            intent?.getBooleanExtra(DeliveryNotifications.EXTRA_OPEN_DELIVERIES, false) == true
        if (openDeliveries) {
            AppNavigationIntents.request(Screen.DELIVERIES)
        }
    }
}
