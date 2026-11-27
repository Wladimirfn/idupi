package com.idupi.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Page/chip mapping for the Dashboard pager (phone-dashboard T2): two pages,
 * PC first, and the subtitle that follows the selected page.
 */
class DashboardPagerTest {

    @Test
    fun `page indexes map to the two dashboard pages`() {
        assertEquals(DashboardPage.PC, DashboardPage.fromIndex(0))
        assertEquals(DashboardPage.PHONE, DashboardPage.fromIndex(1))
    }

    @Test
    fun `out-of-range indexes fall back to the pc page`() {
        assertEquals(DashboardPage.PC, DashboardPage.fromIndex(-1))
        assertEquals(DashboardPage.PC, DashboardPage.fromIndex(2))
    }

    @Test
    fun `pages expose their chip label and top bar subtitle`() {
        assertEquals("PC", DashboardPage.PC.label)
        assertEquals("Teléfono", DashboardPage.PHONE.label)
        assertEquals("Estado de la PC en vivo", DashboardPage.PC.subtitle)
        assertEquals("Estado del teléfono", DashboardPage.PHONE.subtitle)
    }
}
