package com.idupi.app.data.phone.shell

/**
 * Wireless-debugging pairing codes are exactly six ASCII digits. Kept as a
 * separate rule so the UI and the client validate the same thing before the
 * code is handed to the pairing handshake.
 */
object PairingCode {

    private val PATTERN = Regex("^[0-9]{6}$")

    fun isValid(code: String?): Boolean = code != null && PATTERN.matches(code)
}
