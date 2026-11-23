package com.idupi.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.idupi.app.data.notification.DeliveryNotifications
import com.idupi.app.ui.navigation.AppNavigation
import com.idupi.app.ui.navigation.AppNavigationIntents
import com.idupi.app.ui.navigation.Screen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Auto-cargar perfil de conexión guardado si existe
        val savedProfile = com.idupi.app.data.connection.ConnectionStorage(this).getProfile()
        if (savedProfile != null && savedProfile.host.isNotBlank()) {
            com.idupi.app.data.IduPiClientProvider.configureRealClient(savedProfile)
        }
        // The delivery poller needs the client configured first.
        (application as IduPiApp).startDeliveryWatcher()

        openRequestedScreen(intent)

        setContent {
            // Theme wrapping happens inside AppNavigation, which resolves the
            // persisted ThemeSettings into the palette IDUPITheme applies.
            AppNavigation()
        }
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
