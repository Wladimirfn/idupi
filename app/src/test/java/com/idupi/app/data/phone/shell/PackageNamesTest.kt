package com.idupi.app.data.phone.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android package-name validation (phone-shell T2). Anything that passes this
 * rule is still checked against the helper's typed allowlist; this is the
 * shape check that keeps shell metacharacters out of `pm`/`cmd` arguments.
 */
class PackageNamesTest {

    @Test
    fun `accepts real Android package names`() {
        listOf(
            "com.idupi.app",
            "com.idupi.shell",
            "com.whatsapp",
            "a.b",
            "com.example.app2",
            "com.example.my_app",
            "com.Example.App",
        ).forEach { pkg ->
            assertTrue("expected valid: $pkg", PackageNames.isValid(pkg))
        }
    }

    @Test
    fun `rejects one-segment names`() {
        assertFalse(PackageNames.isValid("whatsapp"))
        assertFalse(PackageNames.isValid("a"))
    }

    @Test
    fun `rejects empty segments`() {
        assertFalse(PackageNames.isValid(""))
        assertFalse(PackageNames.isValid("com..app"))
        assertFalse(PackageNames.isValid(".com.app"))
        assertFalse(PackageNames.isValid("com.app."))
        assertFalse(PackageNames.isValid("com."))
    }

    @Test
    fun `rejects segments that do not start with a letter`() {
        assertFalse(PackageNames.isValid("1com.app"))
        assertFalse(PackageNames.isValid("com.2app"))
        assertFalse(PackageNames.isValid("com._app"))
        assertFalse(PackageNames.isValid("com.app2.9x"))
    }

    @Test
    fun `rejects characters outside the package-name alphabet`() {
        assertFalse(PackageNames.isValid("com.app-2"))
        assertFalse(PackageNames.isValid("com.app/x"))
        assertFalse(PackageNames.isValid("com app"))
        assertFalse(PackageNames.isValid("com.app;id"))
        assertFalse(PackageNames.isValid("com.app\n"))
        assertFalse(PackageNames.isValid("com.añejo"))
        assertFalse(PackageNames.isValid("com.app\$x"))
    }

    @Test
    fun `rejects null and blank values`() {
        assertFalse(PackageNames.isValid(null))
        assertFalse(PackageNames.isValid(" "))
        assertFalse(PackageNames.isValid(" com.app"))
        assertFalse(PackageNames.isValid("com.app "))
    }
}
