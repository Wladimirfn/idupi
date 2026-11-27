package com.idupi.app.data.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision table for the T6/T7 bridge recovery: preconditions, cooldown,
 * which global settings are touched/restored, and the retry-once policy after
 * an unavailable `shell` command. No Android framework is involved.
 */
class ShellBridgeRecoveryPolicyTest {

    // --- preconditions ---------------------------------------------------

    @Test
    fun `a paired phone on Wi-Fi with the grant proceeds`() {
        assertEquals(
            ShellBridgeRecoveryPolicy.Preflight.Proceed,
            ShellBridgeRecoveryPolicy.preflight(
                paired = true,
                bridgeActive = false,
                writeSecureSettings = true,
                onWifi = true,
            ),
        )
    }

    @Test
    fun `an active bridge wins over every other precondition`() {
        assertEquals(
            ShellBridgeRecoveryPolicy.Preflight.AlreadyActive,
            ShellBridgeRecoveryPolicy.preflight(
                paired = false,
                bridgeActive = true,
                writeSecureSettings = false,
                onWifi = false,
            ),
        )
    }

    @Test
    fun `the preconditions are checked in order`() {
        assertEquals(
            ShellBridgeRecoveryPolicy.Preflight.Skip(ShellBridgeRecoverySkip.NOT_PAIRED),
            ShellBridgeRecoveryPolicy.preflight(
                paired = false,
                bridgeActive = false,
                writeSecureSettings = false,
                onWifi = false,
            ),
        )
        assertEquals(
            ShellBridgeRecoveryPolicy.Preflight.Skip(
                ShellBridgeRecoverySkip.NO_WRITE_SECURE_SETTINGS
            ),
            ShellBridgeRecoveryPolicy.preflight(
                paired = true,
                bridgeActive = false,
                writeSecureSettings = false,
                onWifi = false,
            ),
        )
        assertEquals(
            ShellBridgeRecoveryPolicy.Preflight.Skip(ShellBridgeRecoverySkip.NOT_ON_WIFI),
            ShellBridgeRecoveryPolicy.preflight(
                paired = true,
                bridgeActive = false,
                writeSecureSettings = true,
                onWifi = false,
            ),
        )
    }

    // --- cooldown --------------------------------------------------------

    @Test
    fun `the first attempt is always allowed`() {
        assertTrue(
            ShellBridgeRecoveryPolicy.canAttempt(
                nowMillis = 1_000_000L,
                lastAttemptAtMillis = null,
                forced = false,
            )
        )
    }

    @Test
    fun `two real attempts need the full cooldown`() {
        val last = 1_000_000L
        assertFalse(
            ShellBridgeRecoveryPolicy.canAttempt(
                nowMillis = last + ShellBridgeRecoveryPolicy.COOLDOWN_MS - 1,
                lastAttemptAtMillis = last,
                forced = false,
            )
        )
        assertTrue(
            ShellBridgeRecoveryPolicy.canAttempt(
                nowMillis = last + ShellBridgeRecoveryPolicy.COOLDOWN_MS,
                lastAttemptAtMillis = last,
                forced = false,
            )
        )
    }

    @Test
    fun `the Ajustes button forces an attempt inside the cooldown`() {
        assertTrue(
            ShellBridgeRecoveryPolicy.canAttempt(
                nowMillis = 1_000_000L,
                lastAttemptAtMillis = 999_999L,
                forced = true,
            )
        )
    }

    @Test
    fun `a clock that went backwards keeps the cooldown`() {
        assertFalse(
            ShellBridgeRecoveryPolicy.canAttempt(
                nowMillis = 999_000L,
                lastAttemptAtMillis = 1_000_000L,
                forced = false,
            )
        )
    }

    // --- settings restore plan -------------------------------------------

    @Test
    fun `settings the owner had off are enabled and restored`() {
        val plan = ShellBridgeRecoveryPolicy.settingsPlan(
            previousAdbWifi = 0,
            previousDevelopmentSettings = 0,
        )

        assertTrue(plan.shouldEnableAdbWifi)
        assertTrue(plan.shouldEnableDevelopmentSettings)
        assertTrue(plan.shouldRestoreAdbWifi)
        assertTrue(plan.shouldRestoreDevelopmentSettings)
    }

    @Test
    fun `only development settings are touched when adb was already on`() {
        val plan = ShellBridgeRecoveryPolicy.settingsPlan(
            previousAdbWifi = 1,
            previousDevelopmentSettings = 0,
        )

        assertFalse(plan.shouldEnableAdbWifi)
        assertTrue(plan.shouldEnableDevelopmentSettings)
        assertFalse(plan.shouldRestoreAdbWifi)
        assertTrue(plan.shouldRestoreDevelopmentSettings)
    }

    @Test
    fun `only adb is touched when development settings were already on`() {
        val plan = ShellBridgeRecoveryPolicy.settingsPlan(
            previousAdbWifi = 0,
            previousDevelopmentSettings = 1,
        )

        assertTrue(plan.shouldEnableAdbWifi)
        assertFalse(plan.shouldEnableDevelopmentSettings)
        assertTrue(plan.shouldRestoreAdbWifi)
        assertFalse(plan.shouldRestoreDevelopmentSettings)
    }

    @Test
    fun `nothing is changed or restored when both settings were already on`() {
        val plan = ShellBridgeRecoveryPolicy.settingsPlan(
            previousAdbWifi = 1,
            previousDevelopmentSettings = 1,
        )

        assertFalse(plan.shouldEnableAdbWifi)
        assertFalse(plan.shouldEnableDevelopmentSettings)
        assertFalse(plan.shouldRestoreAdbWifi)
        assertFalse(plan.shouldRestoreDevelopmentSettings)
    }

    // --- retry-once after an unavailable command -------------------------

    @Test
    fun `a recovery that worked retries the command once`() {
        assertEquals(
            ShellBridgeRecoveryPolicy.RetryDecision.RetryOnce,
            ShellBridgeRecoveryPolicy.retryDecision(ShellBridgeRecoveryOutcome.Recovered),
        )
    }

    @Test
    fun `an active bridge retries the command once`() {
        assertEquals(
            ShellBridgeRecoveryPolicy.RetryDecision.RetryOnce,
            ShellBridgeRecoveryPolicy.retryDecision(ShellBridgeRecoveryOutcome.AlreadyActive),
        )
    }

    @Test
    fun `off Wi-Fi the command says the bridge comes back at home`() {
        val decision = ShellBridgeRecoveryPolicy.retryDecision(
            ShellBridgeRecoveryOutcome.Skipped(ShellBridgeRecoverySkip.NOT_ON_WIFI)
        ) as ShellBridgeRecoveryPolicy.RetryDecision.Fail

        assertEquals(ShellBridgeRecoveryPolicy.OFF_WIFI_MESSAGE, decision.message)
        assertTrue(decision.message.contains("Wi-Fi de casa"))
        assertTrue(decision.message.contains("caído"))
    }

    @Test
    fun `a link or permission skip tells the owner how to repair it`() {
        val notPaired = ShellBridgeRecoveryPolicy.retryDecision(
            ShellBridgeRecoveryOutcome.Skipped(ShellBridgeRecoverySkip.NOT_PAIRED)
        ) as ShellBridgeRecoveryPolicy.RetryDecision.Fail
        assertTrue(notPaired.message.contains("Ajustes > Acceso avanzado"))

        val noPermission = ShellBridgeRecoveryPolicy.retryDecision(
            ShellBridgeRecoveryOutcome.Skipped(ShellBridgeRecoverySkip.NO_WRITE_SECURE_SETTINGS)
        ) as ShellBridgeRecoveryPolicy.RetryDecision.Fail
        assertTrue(noPermission.message.contains("vincular"))
    }

    @Test
    fun `a cooldown skip does not retry immediately`() {
        val decision = ShellBridgeRecoveryPolicy.retryDecision(
            ShellBridgeRecoveryOutcome.Skipped(ShellBridgeRecoverySkip.COOLDOWN)
        ) as ShellBridgeRecoveryPolicy.RetryDecision.Fail

        assertEquals(ShellBridgeRecoveryPolicy.COOLDOWN_MESSAGE, decision.message)
    }

    @Test
    fun `a failed recovery surfaces its own message`() {
        val decision = ShellBridgeRecoveryPolicy.retryDecision(
            ShellBridgeRecoveryOutcome.Failed("vuelve a vincular en Ajustes")
        ) as ShellBridgeRecoveryPolicy.RetryDecision.Fail

        assertEquals("vuelve a vincular en Ajustes", decision.message)
    }

    @Test
    fun `a second unavailable result never starts another retry`() {
        assertEquals(
            ShellBridgeRecoveryPolicy.RetryDecision.Fail(
                ShellBridgeRecoveryPolicy.RETRY_FAILED_MESSAGE
            ),
            ShellBridgeRecoveryPolicy.retryDecision(
                ShellBridgeRecoveryOutcome.Recovered,
                alreadyRetried = true,
            ),
        )
    }

    // --- what counts as an unavailable bridge ----------------------------

    @Test
    fun `a failed helper call is unavailable`() {
        assertTrue(
            ShellBridgeAvailability.isUnavailable(
                Result.failure(BridgeUnavailableException())
            )
        )
    }

    @Test
    fun `a successful helper call is available`() {
        assertFalse(
            ShellBridgeAvailability.isUnavailable(
                Result.success(BridgeResult(ok = true, text = "Almacenamiento: 10 GB libres."))
            )
        )
    }

    @Test
    fun `an uninstrumented helper answer is unavailable`() {
        assertTrue(
            ShellBridgeAvailability.isUnavailable(
                Result.success(
                    BridgeResult(
                        ok = false,
                        text = "El puente no está activo. Inícialo desde IDUPI.",
                    )
                )
            )
        )
    }

    @Test
    fun `a real command failure is not a bridge outage`() {
        assertFalse(
            ShellBridgeAvailability.isUnavailable(
                Result.success(
                    BridgeResult(
                        ok = false,
                        text = "No se pudo desinstalar com.example.app.",
                    )
                )
            )
        )
    }
}
