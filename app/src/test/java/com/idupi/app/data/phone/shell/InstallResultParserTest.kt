package com.idupi.app.data.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Package-manager output parsing (phone-shell T3b). A false "Success" would
 * start a bridge that does not exist, so failure lines always win.
 */
class InstallResultParserTest {

    @Test
    fun `recognizes success with surrounding whitespace and CRLF`() {
        assertEquals(InstallResult.Success, InstallResultParser.parse("Success\n"))
        assertEquals(InstallResult.Success, InstallResultParser.parse("\r\n Success \r\n"))
    }

    @Test
    fun `extracts the reason from a failure`() {
        assertEquals(
            InstallResult.Failure("INSTALL_FAILED_INVALID_APK"),
            InstallResultParser.parse("Failure [INSTALL_FAILED_INVALID_APK]\n"),
        )
        assertEquals(
            InstallResult.Failure("INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match"),
            InstallResultParser.parse(
                "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]\r\n"
            ),
        )
    }

    @Test
    fun `a bare failure reports an unknown reason`() {
        assertEquals(
            InstallResult.Failure("error desconocido"),
            InstallResultParser.parse("Failure"),
        )
    }

    @Test
    fun `empty and unknown output is a failure`() {
        assertEquals(InstallResult.Failure("sin resultado"), InstallResultParser.parse(""))
        assertEquals(InstallResult.Failure("sin resultado"), InstallResultParser.parse("\n\r\n"))
        assertEquals(
            InstallResult.Failure("sin resultado"),
            InstallResultParser.parse("Something unexpected"),
        )
    }

    @Test
    fun `a failure line wins over a success line`() {
        val result = InstallResultParser.parse("Success\nFailure [INSTALL_FAILED_VERSION_DOWNGRADE]\n")
        assertTrue(result is InstallResult.Failure)
        assertEquals("INSTALL_FAILED_VERSION_DOWNGRADE", (result as InstallResult.Failure).reason)
    }
}
