package com.idupi.app

import android.app.Application
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.connection.ConnectionStorage
import com.idupi.app.data.notification.DeliveryWatcher
import com.idupi.app.data.phone.control.AccessibilityKick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class IduPiApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var deliveryWatcher: DeliveryWatcher? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // phone-shell T7b: after an app start the accessibility service may be
        // enabled in Settings but not bound. The request waits a short grace so
        // the system can bind it first, then the kick only runs if it did not.
        AccessibilityKick.request(this)
    }

    /**
     * Starts the APK-delivery poller once per process. MainActivity calls this
     * AFTER applying the saved connection profile to the client, so the first
     * poll cannot race the configuration and hit the default host.
     *
     * While no server is configured the watcher idles on its error backoff;
     * it starts polling as soon as a profile exists.
     */
    fun startDeliveryWatcher() {
        if (deliveryWatcher != null) return
        val connectionStorage = ConnectionStorage(this)
        deliveryWatcher = DeliveryWatcher(
            context = this,
            clientSource = IduPiClientProvider,
            isServerConfigured = connectionStorage::hasConfiguredServer
        ).also { it.start(appScope) }
    }

    /**
     * Stops the poller. "Borrar credenciales" calls this so the loop cannot
     * keep hitting the old server with the old token; [startDeliveryWatcher]
     * starts a fresh loop once a profile is saved again.
     */
    fun stopDeliveryWatcher() {
        deliveryWatcher?.stop()
        deliveryWatcher = null
    }

    companion object {
        lateinit var instance: IduPiApp
            private set
    }
}
