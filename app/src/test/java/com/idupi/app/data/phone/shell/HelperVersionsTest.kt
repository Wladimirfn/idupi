package com.idupi.app.data.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Helper install/version rules (phone-shell T3b): the embedded APK replaces a
 * missing or older helper and never replaces a newer one.
 */
class HelperVersionsTest {

    @Test
    fun `a missing helper needs the install`() {
        assertTrue(HelperVersions.needsInstall(installed = null, embedded = 1))
    }

    @Test
    fun `an older helper needs the install`() {
        assertTrue(HelperVersions.needsInstall(installed = 0, embedded = 1))
        assertTrue(HelperVersions.needsInstall(installed = 1, embedded = 2))
    }

    @Test
    fun `the same or a newer helper is left alone`() {
        assertFalse(HelperVersions.needsInstall(installed = 1, embedded = 1))
        assertFalse(HelperVersions.needsInstall(installed = 2, embedded = 1))
    }

    @Test
    fun `an unreadable embedded APK never triggers an install`() {
        assertFalse(HelperVersions.needsInstall(installed = null, embedded = 0))
        assertFalse(HelperVersions.needsInstall(installed = 0, embedded = -1))
    }

    @Test
    fun `reads versionCode from dumpsys`() {
        val dump = """
            Packages:
              Package [com.idupi.shell] (1234):
                userId=10480
                pkg=Package{abc com.idupi.shell}
                versionCode=1 minSdk=29 targetSdk=36
                signatures=...
        """.trimIndent()

        assertEquals(1, HelperVersions.installedVersion(dump))
        assertEquals(2, HelperVersions.installedVersion("versionCode=2 minSdk=29"))
    }

    @Test
    fun `returns null when dumpsys has no versionCode`() {
        assertNull(HelperVersions.installedVersion(""))
        assertNull(HelperVersions.installedVersion("Package [com.idupi.shell] not found"))
        assertNull(HelperVersions.installedVersion("versionCode=abc"))
    }
}
