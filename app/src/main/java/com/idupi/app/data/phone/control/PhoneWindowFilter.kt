package com.idupi.app.data.phone.control

/**
 * One accessibility window the filter can inspect: the platform type, the
 * package name of its root and whether it currently holds the focus. Android
 * types are plain integers so the decision is pure (phone-dashboard T7).
 */
data class PhoneWindowRef(
    val type: Int,
    val packageName: String?,
    val active: Boolean,
)

/**
 * Window selection for screen reads and taps (phone-dashboard T7).
 *
 * The bubble panel is a focusable `TYPE_ACCESSIBILITY_OVERLAY` window of
 * IDUPI's own package. While it is open it can take the active-window slot, so
 * reading `rootInActiveWindow` showed the agent its own panel and let it tap
 * "MINIMIZAR" while the owner asked to optimize the phone (owner live check,
 * turn pat-9ee16fd0e46a, and pat-f0ede6586408 about YouTube). IDUPI's own
 * overlay windows are therefore never read: the app behind them is.
 */
object PhoneWindowFilter {

    /** `AccessibilityWindowInfo.TYPE_APPLICATION`. */
    const val TYPE_APPLICATION = 1

    /** `AccessibilityWindowInfo.TYPE_INPUT_METHOD`. */
    const val TYPE_INPUT_METHOD = 2

    /** `AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY`. */
    const val TYPE_ACCESSIBILITY_OVERLAY = 4

    /**
     * True only for an accessibility overlay that belongs to IDUPI: the
     * bubble. A normal window of the same package (its activity) is not one.
     */
    fun isOwnOverlay(type: Int, packageName: String?, ownPackage: String): Boolean =
        type == TYPE_ACCESSIBILITY_OVERLAY &&
            packageName != null &&
            packageName == ownPackage

    /**
     * Indices of the windows to read, in the order given. When a readable
     * window holds the focus it is the whole screen; when IDUPI's own panel
     * took the focus, every readable window is returned so the app behind the
     * panel is still what the agent sees.
     */
    fun readIndices(windows: List<PhoneWindowRef>, ownPackage: String): List<Int> {
        val readable = windows.indices.filter { index ->
            val window = windows[index]
            !isOwnOverlay(window.type, window.packageName, ownPackage)
        }
        val active = readable.filter { windows[it].active }
        return if (active.isNotEmpty()) active else readable
    }
}
