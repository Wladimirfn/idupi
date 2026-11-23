package com.idupi.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Responsive rule for the Dashboard metric grid (T7): half-width cards only
 * where a label + value line still fits; below it the cards stack.
 */
class DashboardGridTest {

    @Test
    fun `content area below 360dp stacks the metric cards in one column`() {
        assertEquals(1, dashboardGridColumns(359f))
        assertEquals(1, dashboardGridColumns(320f))
        assertEquals(1, dashboardGridColumns(0f))
    }

    @Test
    fun `content area of 360dp or wider keeps two cards per row`() {
        assertEquals(2, dashboardGridColumns(360f))
        // 412dp phone minus the 20dp screen padding on each side.
        assertEquals(2, dashboardGridColumns(372f))
        assertEquals(2, dashboardGridColumns(720f))
    }
}
