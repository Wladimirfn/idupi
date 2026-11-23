package com.idupi.app.data

import com.idupi.app.data.remote.RealIduPiClient
import com.idupi.app.domain.model.ConnectionMode
import com.idupi.app.domain.model.ConnectionProfile
import com.idupi.app.domain.model.TransportType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Borrar credenciales" clears the persisted profile; the in-memory client is
 * a separate store and used to keep the old host and token until the process
 * restarted.
 */
class IduPiClientProviderTest {

    private fun profile() = ConnectionProfile(
        name = "casa",
        mode = ConnectionMode.LOCAL_LAN,
        host = "192.168.0.10",
        port = 9000,
        token = "secret-token",
        useHttps = true,
        transportType = TransportType.AUTO
    )

    @After
    fun tearDown() {
        IduPiClientProvider.clear()
    }

    @Test
    fun `clear resets the in-memory client to the local defaults`() {
        IduPiClientProvider.configureRealClient(profile())

        val configured = (IduPiClientProvider.client as RealIduPiClient).connectionSnapshot()
        assertEquals("192.168.0.10", configured.host)
        assertEquals(9000, configured.port)
        assertEquals("secret-token", configured.token)
        assertEquals(true, configured.useHttps)

        IduPiClientProvider.clear()

        val cleared = (IduPiClientProvider.client as RealIduPiClient).connectionSnapshot()
        assertEquals("10.0.2.2", cleared.host)
        assertEquals(8788, cleared.port)
        assertEquals("", cleared.token)
        assertEquals(false, cleared.useHttps)
    }
}
