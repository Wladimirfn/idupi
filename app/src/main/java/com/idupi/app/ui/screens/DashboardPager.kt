package com.idupi.app.ui.screens

/**
 * The Dashboard's two pages (phone-dashboard T2): the PC's live metrics and
 * this phone's own status. [label] is the chip, [subtitle] the line under the
 * "Dashboard" title.
 *
 * The ordinal is the pager index, so chip selection, swipe position and the
 * page content all agree on one mapping (pinned by DashboardPagerTest).
 */
enum class DashboardPage(val label: String, val subtitle: String) {
    PC("PC", "Estado de la PC en vivo"),
    PHONE("Teléfono", "Estado del teléfono");

    companion object {
        /** Pager index for a page; out-of-range indexes fall back to [PC]. */
        fun fromIndex(index: Int): DashboardPage = entries.getOrNull(index) ?: PC
    }
}
