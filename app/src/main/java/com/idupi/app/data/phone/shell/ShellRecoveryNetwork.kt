package com.idupi.app.data.phone.shell

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wi-Fi checks for the bridge recovery (phone-shell T6/T7).
 *
 * "Home Wi-Fi" is not a stored SSID: the phone reaches its own adbd over
 * loopback, so any Wi-Fi transport is enough. Mobile data (through the VPN, the
 * off-Wi-Fi evidence) is not.
 */
object ShellRecoveryNetwork {

    private const val WIFI_POLL_MS = 1_000L

    fun isOnWifi(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return isWifi(manager, manager.activeNetwork)
    }

    /**
     * Waits for a Wi-Fi transport to become active, up to [timeoutMillis].
     * Used by the boot trigger, where the radio can take a while after
     * BOOT_COMPLETED. Polling keeps the wait short-lived and leaves no
     * network callback behind.
     */
    suspend fun awaitWifi(context: Context, timeoutMillis: Long): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        if (isWifi(manager, manager.activeNetwork)) return true
        return withTimeoutOrNull(timeoutMillis) {
            while (!isWifi(manager, manager.activeNetwork)) {
                delay(WIFI_POLL_MS)
            }
            true
        } ?: false
    }

    private fun isWifi(manager: ConnectivityManager, network: Network?): Boolean {
        if (network == null) return false
        return manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
