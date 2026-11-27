package com.idupi.app.data.phone.tailscale

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision table for the T9 server-link recovery: when to broadcast, which
 * hosts are Tailscale CGNAT addresses, the always-on setting rule, the
 * consecutive-failure gate and what counts as a transport failure.
 */
class TailscaleLinkPolicyTest {

    // --- connect decision ------------------------------------------------

    @Test
    fun `an installed Tailscale with no VPN connects`() {
        assertEquals(
            TailscaleDecision.Connect,
            TailscaleLinkPolicy.decide(installed = true, vpnUp = false, cooldownActive = false),
        )
    }

    @Test
    fun `an already-up VPN wins over the cooldown`() {
        assertEquals(
            TailscaleDecision.AlreadyUp,
            TailscaleLinkPolicy.decide(installed = true, vpnUp = true, cooldownActive = true),
        )
    }

    @Test
    fun `a recent broadcast is still in cooldown`() {
        assertEquals(
            TailscaleDecision.Cooldown,
            TailscaleLinkPolicy.decide(installed = true, vpnUp = false, cooldownActive = true),
        )
    }

    @Test
    fun `a missing Tailscale is never started`() {
        assertEquals(
            TailscaleDecision.NotInstalled,
            TailscaleLinkPolicy.decide(installed = false, vpnUp = false, cooldownActive = false),
        )
    }

    // --- Tailscale host range --------------------------------------------

    @Test
    fun `the CGNAT range is 100_64 through 100_127`() {
        assertTrue(TailscaleLinkPolicy.isTailscaleHost("100.64.0.0"))
        assertTrue(TailscaleLinkPolicy.isTailscaleHost("100.73.94.118"))
        assertTrue(TailscaleLinkPolicy.isTailscaleHost("100.127.255.255"))
    }

    @Test
    fun `addresses outside the range are not Tailscale`() {
        assertFalse(TailscaleLinkPolicy.isTailscaleHost("100.63.255.255"))
        assertFalse(TailscaleLinkPolicy.isTailscaleHost("100.128.0.0"))
        assertFalse(TailscaleLinkPolicy.isTailscaleHost("99.100.1.1"))
        assertFalse(TailscaleLinkPolicy.isTailscaleHost("101.64.0.1"))
        assertFalse(TailscaleLinkPolicy.isTailscaleHost("192.168.1.10"))
    }

    @Test
    fun `blank, host names and malformed values are not Tailscale`() {
        assertFalse(TailscaleLinkPolicy.isTailscaleHost(null))
        assertFalse(TailscaleLinkPolicy.isTailscaleHost(""))
        assertFalse(TailscaleLinkPolicy.isTailscaleHost("   "))
        assertFalse(TailscaleLinkPolicy.isTailscaleHost("server.local"))
        assertFalse(TailscaleLinkPolicy.isTailscaleHost("100.73.94"))
        assertFalse(TailscaleLinkPolicy.isTailscaleHost("100.73.94.118:8787"))
        assertFalse(TailscaleLinkPolicy.isTailscaleHost("100.999.1.1"))
        assertFalse(TailscaleLinkPolicy.isTailscaleHost("100.-1.1.1"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertTrue(TailscaleLinkPolicy.isTailscaleHost(" 100.73.94.118 "))
    }

    // --- always-on VPN setting -------------------------------------------

    @Test
    fun `an empty always-on setting is set when the grant exists`() {
        assertEquals(
            AlwaysOnDecision.Set,
            TailscaleLinkPolicy.alwaysOnDecision(
                installed = true,
                current = null,
                writeSecureSettings = true,
            ),
        )
        assertEquals(
            AlwaysOnDecision.Set,
            TailscaleLinkPolicy.alwaysOnDecision(
                installed = true,
                current = "",
                writeSecureSettings = true,
            ),
        )
    }

    @Test
    fun `an empty always-on setting without the grant is skipped`() {
        assertEquals(
            AlwaysOnDecision.NoPermission,
            TailscaleLinkPolicy.alwaysOnDecision(
                installed = true,
                current = null,
                writeSecureSettings = false,
            ),
        )
    }

    @Test
    fun `another app's always-on VPN is never overridden`() {
        assertEquals(
            AlwaysOnDecision.KeepOther,
            TailscaleLinkPolicy.alwaysOnDecision(
                installed = true,
                current = "com.example.othervpn",
                writeSecureSettings = true,
            ),
        )
    }

    @Test
    fun `Tailscale already set is left alone`() {
        assertEquals(
            AlwaysOnDecision.AlreadySet,
            TailscaleLinkPolicy.alwaysOnDecision(
                installed = true,
                current = TailscaleLinkPolicy.TAILSCALE_PACKAGE,
                writeSecureSettings = true,
            ),
        )
    }

    @Test
    fun `a missing Tailscale never touches the setting`() {
        assertEquals(
            AlwaysOnDecision.NotInstalled,
            TailscaleLinkPolicy.alwaysOnDecision(
                installed = false,
                current = null,
                writeSecureSettings = true,
            ),
        )
    }

    // --- consecutive transport failures ----------------------------------

    @Test
    fun `two consecutive failures cross the gate`() {
        val gate = TailscaleFailureGate()

        assertFalse(gate.onFailure())
        assertTrue(gate.onFailure())
        assertTrue(gate.onFailure())
    }

    @Test
    fun `a successful request resets the gate`() {
        val gate = TailscaleFailureGate()
        assertFalse(gate.onFailure())

        gate.onSuccess()

        assertFalse(gate.onFailure())
        assertTrue(gate.onFailure())
    }

    // --- what counts as a transport failure ------------------------------

    @Test
    fun `connect, socket and IO failures are transport failures`() {
        assertTrue(TailscaleLinkPolicy.isTransportFailure(ConnectException("refused")))
        assertTrue(TailscaleLinkPolicy.isTransportFailure(SocketTimeoutException("timeout")))
        assertTrue(TailscaleLinkPolicy.isTransportFailure(IOException("socket closed")))
    }

    @Test
    fun `a wrapped transport failure is still a transport failure`() {
        val wrapped = IllegalStateException("request failed", ConnectException("refused"))
        assertTrue(TailscaleLinkPolicy.isTransportFailure(wrapped))
    }

    @Test
    fun `an application error is not a transport failure`() {
        assertFalse(TailscaleLinkPolicy.isTransportFailure(IllegalStateException("http 500")))
    }
}
