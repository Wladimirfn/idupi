package com.idupi.app.data.phone.tailscale

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the Tailscale VPN (the server link) alive without the owner touching
 * the Tailscale app (phone-shell T9).
 *
 * When Tailscale is installed and no VPN network is up, it sends Tailscale's
 * explicit `CONNECT_VPN` broadcast. One broadcast at most every
 * [TailscaleLinkPolicy.COOLDOWN_MS] and single-flight through [mutex], because
 * the accessibility service, a network change, the boot flow and transport
 * timeouts can all ask at once. The first call in a process also points
 * `always_on_vpn_app` at Tailscale when that setting is empty, so Android
 * itself restores the link on later boots.
 *
 * Every decision is logged under [TAG]; it never carries a token or traffic.
 */
object TailscaleLink {

    private const val TAG = "IdupiPhoneShell"
    private const val ALWAYS_ON_VPN_APP = "always_on_vpn_app"
    private const val VPN_POLL_MS = 1_000L

    private val mutex = Mutex()

    @Volatile
    private var lastBroadcastAtMillis: Long? = null

    @Volatile
    private var alwaysOnDone = false

    @Volatile
    private var alwaysOnNoPermissionLogged = false

    /**
     * Ensures the VPN is up (or asks Tailscale to bring it up). Never throws:
     * a missing package, a rejected broadcast or a platform error is logged and
     * reported as [TailscaleDecision.NotInstalled] / cooldown / connect.
     */
    suspend fun ensureConnected(
        context: Context,
        trigger: String,
    ): TailscaleDecision {
        val app = context.applicationContext
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val installed = isInstalled(app)
                maybeConfigureAlwaysOn(app, installed)
                val vpnUp = isVpnUp(app)
                val now = System.currentTimeMillis()
                val cooldown = lastBroadcastAtMillis
                    ?.let { last -> now - last < TailscaleLinkPolicy.COOLDOWN_MS } == true
                val decision = TailscaleLinkPolicy.decide(installed, vpnUp, cooldown)
                when (decision) {
                    TailscaleDecision.NotInstalled -> log(trigger, "not installed")
                    TailscaleDecision.AlreadyUp -> log(trigger, "already up")
                    TailscaleDecision.Cooldown -> log(trigger, "cooldown")
                    TailscaleDecision.Connect -> {
                        if (sendConnect(app)) {
                            lastBroadcastAtMillis = now
                            log(trigger, "connected")
                        } else {
                            log(trigger, "failed")
                        }
                    }
                }
                decision
            }
        }
    }

    /**
     * Waits for a VPN network to appear, up to [timeoutMillis]. The boot flow
     * uses it after asking Tailscale to connect; it returns immediately when
     * Tailscale is not installed. Always fails closed (false) on timeout.
     */
    suspend fun awaitConnected(context: Context, timeoutMillis: Long): Boolean {
        val app = context.applicationContext
        return withContext(Dispatchers.IO) {
            if (!isInstalled(app)) return@withContext false
            if (isVpnUp(app)) return@withContext true
            withTimeoutOrNull(timeoutMillis) {
                while (!isVpnUp(app)) delay(VPN_POLL_MS)
                true
            } ?: false
        }
    }

    private fun sendConnect(context: Context): Boolean = runCatching {
        context.sendBroadcast(
            Intent(TailscaleLinkPolicy.CONNECT_ACTION).setComponent(
                ComponentName(
                    TailscaleLinkPolicy.TAILSCALE_PACKAGE,
                    TailscaleLinkPolicy.RECEIVER,
                )
            )
        )
        true
    }.getOrElse { error ->
        Log.w(TAG, "tailscale(broadcast): failed: ${error.javaClass.simpleName}")
        false
    }

    /**
     * Sets `always_on_vpn_app` once per process, only when it is empty and the
     * secure-settings grant is present. `always_on_vpn_lockdown` is deliberately
     * left untouched, and another app's non-empty value is never overridden.
     */
    private fun maybeConfigureAlwaysOn(context: Context, installed: Boolean) {
        if (alwaysOnDone || !installed) return
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, ALWAYS_ON_VPN_APP)
        }.getOrNull()
        val writeSecureSettings =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        when (TailscaleLinkPolicy.alwaysOnDecision(installed, current, writeSecureSettings)) {
            AlwaysOnDecision.NotInstalled -> Unit
            AlwaysOnDecision.AlreadySet -> {
                alwaysOnDone = true
                Log.i(TAG, "tailscale(always-on): already ${TailscaleLinkPolicy.TAILSCALE_PACKAGE}")
            }
            AlwaysOnDecision.KeepOther -> {
                alwaysOnDone = true
                Log.i(TAG, "tailscale(always-on): keeping another app")
            }
            AlwaysOnDecision.NoPermission -> {
                if (!alwaysOnNoPermissionLogged) {
                    alwaysOnNoPermissionLogged = true
                    Log.i(TAG, "tailscale(always-on): skipped (no WRITE_SECURE_SETTINGS)")
                }
            }
            AlwaysOnDecision.Set -> {
                val written = runCatching {
                    Settings.Secure.putString(
                        context.contentResolver,
                        ALWAYS_ON_VPN_APP,
                        TailscaleLinkPolicy.TAILSCALE_PACKAGE,
                    )
                }.getOrDefault(false)
                if (written) {
                    alwaysOnDone = true
                    Log.i(
                        TAG,
                        "tailscale(always-on): set ${TailscaleLinkPolicy.TAILSCALE_PACKAGE}",
                    )
                } else {
                    Log.w(TAG, "tailscale(always-on): write failed")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(TailscaleLinkPolicy.TAILSCALE_PACKAGE, 0)
    }.isSuccess

    /**
     * `allNetworks` is the only point-in-time list the platform offers; the
     * deprecation only points at callbacks that track the same information.
     * A VPN that is not the default network still appears here with
     * `TRANSPORT_VPN`, which is exactly what Tailscale exposes while connected.
     */
    @Suppress("DEPRECATION")
    private fun isVpnUp(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.allNetworks.any { network ->
            manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun log(trigger: String, detail: String) {
        Log.i(TAG, "tailscale($trigger): $detail")
    }
}
