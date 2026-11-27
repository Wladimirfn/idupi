package com.idupi.app.data.phone.tailscale

import android.content.Context
import android.util.Log
import com.idupi.app.data.connection.ConnectionStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Transport-failure hook for the phone pollers (phone-shell T9).
 *
 * The control, status and approval loops call [onFailure] from their catch
 * blocks and [onSuccess] when the server answers. A failure counts only when
 * the configured server host is a Tailscale address and two consecutive
 * transport failures happened, so a PC that is simply off never wakes the VPN
 * path. The actual link check runs off the poller's thread, and the shared
 * [TailscaleFailureGate] is reset by any successful request.
 */
object TailscaleTimeouts {

    private const val TAG = "IdupiPhoneShell"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = TailscaleFailureGate()

    fun onFailure(context: Context, error: Throwable) {
        if (!TailscaleLinkPolicy.isTransportFailure(error)) {
            // The request reached the server (or failed for an app reason):
            // the link itself is fine, so the consecutive count restarts.
            gate.onSuccess()
            return
        }
        val app = context.applicationContext
        if (!TailscaleLinkPolicy.isTailscaleHost(ConnectionStorage(app).configuredHost())) {
            // A non-Tailscale host cannot be fixed by waking the VPN.
            gate.onSuccess()
            return
        }
        if (!gate.onFailure()) return
        Log.i(TAG, "tailscale(timeout): consecutive transport failures, checking the link")
        scope.launch { TailscaleLink.ensureConnected(app, trigger = "timeout") }
    }

    fun onSuccess() {
        gate.onSuccess()
    }
}
