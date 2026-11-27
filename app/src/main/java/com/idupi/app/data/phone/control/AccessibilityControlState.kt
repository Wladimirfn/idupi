package com.idupi.app.data.phone.control

/**
 * Whether IDUPI's accessibility service is bound right now (phone-shell T7b).
 *
 * Settings.Secure says the owner enabled it; this flag says the system actually
 * bound it. The kick only runs when the first is true and this one is false,
 * because a reinstall or a helper restart can leave the grant recorded while
 * the service never rebinds until a fresh UiAutomation connects.
 */
object AccessibilityControlState {

    @Volatile
    var connected: Boolean = false
        private set

    fun update(connected: Boolean) {
        this.connected = connected
    }
}
