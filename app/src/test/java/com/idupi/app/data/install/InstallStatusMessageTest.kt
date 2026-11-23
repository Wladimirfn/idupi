package com.idupi.app.data.install

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The deliveries screen shows these strings verbatim; pinning them here keeps
 * a PackageInstaller status from ever surfacing as a raw number or a generic
 * "error" the owner cannot act on.
 */
class InstallStatusMessageTest {

    @Test
    fun `success reports the installed APK`() {
        assertEquals("APK instalada", installStatusMessage(PackageInstaller.STATUS_SUCCESS))
    }

    @Test
    fun `conflict explains the signature mismatch`() {
        assertEquals(
            "La firma no coincide con la app instalada",
            installStatusMessage(PackageInstaller.STATUS_FAILURE_CONFLICT)
        )
    }

    @Test
    fun `storage failure explains the lack of space`() {
        assertEquals(
            "No hay espacio suficiente en el teléfono",
            installStatusMessage(PackageInstaller.STATUS_FAILURE_STORAGE)
        )
    }

    @Test
    fun `blocked failure names the system block`() {
        assertEquals(
            "El sistema bloqueó la instalación",
            installStatusMessage(PackageInstaller.STATUS_FAILURE_BLOCKED)
        )
    }

    @Test
    fun `aborted failure reports the cancellation`() {
        assertEquals(
            "Instalación cancelada",
            installStatusMessage(PackageInstaller.STATUS_FAILURE_ABORTED)
        )
    }

    @Test
    fun `unknown status falls back to a generic message`() {
        assertEquals(
            "No se pudo instalar el APK",
            installStatusMessage(PackageInstaller.STATUS_FAILURE)
        )
        assertEquals("No se pudo instalar el APK", installStatusMessage(-12345))
    }
}
