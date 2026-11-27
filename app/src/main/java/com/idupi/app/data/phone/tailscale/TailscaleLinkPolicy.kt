package com.idupi.app.data.phone.tailscale

/**
 * Pure decisions for [TailscaleLink] (phone-shell T9).
 *
 * The Android glue only performs the platform calls; when to broadcast, which
 * hosts are Tailscale addresses, the always-on rule and the consecutive-failure
 * gate are unit-tested here.
 */

/** What the link should do for one `ensureConnected` call. */
sealed interface TailscaleDecision {
    /** Send the explicit CONNECT_VPN broadcast. */
    data object Connect : TailscaleDecision

    /** A VPN network is already up; nothing to do. */
    data object AlreadyUp : TailscaleDecision

    /** Tailscale is not installed (package query). */
    data object NotInstalled : TailscaleDecision

    /** A broadcast was sent less than [TailscaleLinkPolicy.COOLDOWN_MS] ago. */
    data object Cooldown : TailscaleDecision
}

/** The always-on VPN setting rule; never overrides another app's value. */
sealed interface AlwaysOnDecision {
    /** Tailscale is not installed, so there is nothing to point the setting at. */
    data object NotInstalled : AlwaysOnDecision

    /** The setting was empty and [TailscaleLinkPolicy.TAILSCALE_PACKAGE] is written. */
    data object Set : AlwaysOnDecision

    /** It already points at Tailscale. */
    data object AlreadySet : AlwaysOnDecision

    /** The owner configured a different always-on VPN; leave it untouched. */
    data object KeepOther : AlwaysOnDecision

    /** The setting is empty but the app lacks WRITE_SECURE_SETTINGS. */
    data object NoPermission : AlwaysOnDecision
}

object TailscaleLinkPolicy {

    const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
    const val CONNECT_ACTION = "com.tailscale.ipn.CONNECT_VPN"
    const val RECEIVER = "com.tailscale.ipn.IPNReceiver"

    /** Minimum gap between two CONNECT_VPN broadcasts. */
    const val COOLDOWN_MS = 60_000L

    /** Two consecutive transport failures with a Tailscale host trigger a retry. */
    const val FAILURE_THRESHOLD = 2

    /**
     * Order matters: an uninstalled Tailscale cannot be started even if some
     * other VPN is up; an already-up VPN wins over the cooldown so a successful
     * connection is reported instead of "cooldown".
     */
    fun decide(
        installed: Boolean,
        vpnUp: Boolean,
        cooldownActive: Boolean,
    ): TailscaleDecision = when {
        !installed -> TailscaleDecision.NotInstalled
        vpnUp -> TailscaleDecision.AlreadyUp
        cooldownActive -> TailscaleDecision.Cooldown
        else -> TailscaleDecision.Connect
    }

    /**
     * True when [host] is a plain IPv4 address inside the Tailscale CGNAT
     * range 100.64.0.0/10 (100.64.0.0 - 100.127.255.255), the addresses the
     * Tailscale server uses. Host names and IPv6 addresses are not matched;
     * the configured host is stored without a port.
     */
    fun isTailscaleHost(host: String?): Boolean {
        val value = host?.trim().orEmpty()
        if (value.isEmpty()) return false
        val parts = value.split('.')
        if (parts.size != 4) return false
        if (parts.any { part -> part.isEmpty() || part.any { !it.isDigit() } }) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    /**
     * Decides the `always_on_vpn_app` setting. It is set only when the value is
     * absent and Tailscale is installed; any other app's value is preserved.
     * `always_on_vpn_lockdown` is never part of this rule.
     */
    fun alwaysOnDecision(
        installed: Boolean,
        current: String?,
        writeSecureSettings: Boolean,
    ): AlwaysOnDecision {
        if (!installed) return AlwaysOnDecision.NotInstalled
        val value = current?.trim().orEmpty()
        return when {
            value == TAILSCALE_PACKAGE -> AlwaysOnDecision.AlreadySet
            value.isNotEmpty() -> AlwaysOnDecision.KeepOther
            !writeSecureSettings -> AlwaysOnDecision.NoPermission
            else -> AlwaysOnDecision.Set
        }
    }

    /**
     * True for a connect/timeout failure: the server could not be reached. An
     * HTTP rejection is an `IduPiHttpException` (not an [java.io.IOException]),
     * so it never counts as a link failure.
     */
    fun isTransportFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { cause ->
            cause is java.io.IOException ||
                cause.javaClass.simpleName in EXTRA_TRANSPORT_FAILURES
        }

    private val EXTRA_TRANSPORT_FAILURES = setOf(
        "ConnectTimeoutException",
        "HttpRequestTimeoutException",
        "SocketTimeoutException",
    )
}

/**
 * Counts consecutive transport failures; true once the threshold is reached and
 * on every failure after it. A successful request resets the count.
 */
class TailscaleFailureGate(private val threshold: Int = TailscaleLinkPolicy.FAILURE_THRESHOLD) {

    private var failures = 0

    @Synchronized
    fun onFailure(): Boolean {
        failures += 1
        return failures >= threshold
    }

    @Synchronized
    fun onSuccess() {
        failures = 0
    }
}
