package com.idupi.app.data.phone.shell

/**
 * Freshness rule for the bridge heartbeat (phone-shell T3b).
 *
 * The helper writes the global setting `idupi_shell_probe` every ~5 s. A
 * bridge is alive when the value read after starting differs from the value
 * read before; `null` (the setting does not exist yet) is never fresh.
 */
object BridgeProbe {

    private const val ABSENT_VALUE = "null"

    fun isFresh(baseline: String?, current: String?): Boolean {
        val value = current?.trim() ?: return false
        if (value.isEmpty() || value == ABSENT_VALUE) return false
        return value != baseline?.trim()
    }
}
