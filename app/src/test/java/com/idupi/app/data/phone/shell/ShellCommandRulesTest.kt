package com.idupi.app.data.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone-side shell risk and protected packages (phone-shell T4). Both sides
 * classify the same way so a free command never asks and a destructive one
 * never slips through.
 */
class ShellCommandRulesTest {

    @Test
    fun `info and enable are free`() {
        assertEquals(ShellRisk.FREE, ShellCommandRules.riskOf(ShellOperation.Info(ShellTopic.STORAGE)))
        assertEquals(
            ShellRisk.FREE,
            ShellCommandRules.riskOf(ShellOperation.Enable("com.example.app")),
        )
        assertEquals(ShellRisk.FREE, ShellCommandRules.riskOf(ShellOperation.Scan))
    }

    @Test
    fun `trim-caches, force-stop and background are optimization`() {
        assertEquals(ShellRisk.OPTIMIZATION, ShellCommandRules.riskOf(ShellOperation.TrimCaches))
        assertEquals(
            ShellRisk.OPTIMIZATION,
            ShellCommandRules.riskOf(ShellOperation.ForceStop("com.example.app")),
        )
        assertEquals(
            ShellRisk.OPTIMIZATION,
            ShellCommandRules.riskOf(
                ShellOperation.Background("com.example.app", ShellMode.ALLOW)
            ),
        )
    }

    @Test
    fun `disable and uninstall are destructive`() {
        assertEquals(
            ShellRisk.DESTRUCTIVE,
            ShellCommandRules.riskOf(ShellOperation.Disable("com.example.app")),
        )
        assertEquals(
            ShellRisk.DESTRUCTIVE,
            ShellCommandRules.riskOf(ShellOperation.Uninstall("com.example.app")),
        )
    }

    @Test
    fun `a batch is a single plan risk`() {
        assertEquals(
            ShellRisk.PLAN,
            ShellCommandRules.riskOf(
                ShellOperation.Batch(
                    listOf(ShellOperation.TrimCaches, ShellOperation.Uninstall("com.example.app"))
                )
            ),
        )
    }

    @Test
    fun `the protected list matches the contract`() {
        val expected = setOf(
            "com.idupi.app",
            "com.idupi.shell",
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.providers.settings",
            "com.google.android.gms",
            "com.sec.android.app.launcher",
            "com.samsung.android.honeyboard",
        )
        assertEquals(expected, ShellCommandRules.PROTECTED_PACKAGES)
        expected.forEach { assertTrue(ShellCommandRules.isProtected(it)) }
        assertFalse(ShellCommandRules.isProtected("com.example.app"))
    }

    @Test
    fun `wire actions and summaries stay in the contract`() {
        assertEquals("trim-caches", ShellOperation.TrimCaches.wireAction())
        assertEquals("force-stop", ShellOperation.ForceStop("com.example.app").wireAction())
        assertEquals(
            "background",
            ShellOperation.Background("com.example.app", ShellMode.IGNORE).wireAction(),
        )
        assertEquals("disable", ShellOperation.Disable("com.example.app").wireAction())
        assertEquals("enable", ShellOperation.Enable("com.example.app").wireAction())
        assertEquals("info", ShellOperation.Info(ShellTopic.PACKAGES).wireAction())

        assertEquals(
            "background com.example.app ignore",
            ShellOperation.Background("com.example.app", ShellMode.IGNORE).summary(),
        )
        assertEquals("force-stop com.example.app", ShellOperation.ForceStop("com.example.app").summary())
        assertEquals("info packages", ShellOperation.Info(ShellTopic.PACKAGES).summary())
    }
}
