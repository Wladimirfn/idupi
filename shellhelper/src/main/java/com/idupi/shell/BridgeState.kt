package com.idupi.shell

import android.app.UiAutomation
import android.util.Log

/**
 * Process-wide bridge state (phone-shell T4).
 *
 * The instrumentation is the only writer: it marks the bridge active after it
 * holds the shell permission identity, and inactive when that identity is lost.
 * A helper process started by a provider call without instrumentation has no
 * instrumentation here, so `status` reports inactive and the executor runs
 * nothing.
 */
object BridgeState {

    private const val TAG = "IdupiShellBridge"

    @Volatile
    private var instrumentation: ShellBridgeInstrumentation? = null

    @Volatile
    private var automation: UiAutomation? = null

    @Volatile
    private var active = false

    @Synchronized
    fun attach(instrumentation: ShellBridgeInstrumentation, automation: UiAutomation) {
        this.instrumentation = instrumentation
        this.automation = automation
        this.active = true
    }

    @Synchronized
    fun markInactive() {
        active = false
    }

    fun isActive(): Boolean = active && automation != null

    /** Kicks accessibility only while the bridge holds the identity. */
    fun kickAccessibility(): Boolean {
        if (!isActive()) return false
        val current = instrumentation ?: return false
        return runCatching { current.kickAccessibility() }
            .onFailure { Log.w(TAG, "accessibility kick failed: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }
}
