package com.idupi.app.viewmodel

import com.idupi.app.MainDispatcherRule
import com.idupi.app.domain.model.ConnectionMode
import com.idupi.app.domain.model.TransportType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * ConnectionViewModel takes no client source on purpose: it configures a server
 * that is not reachable yet, so `testConnection()` builds a throwaway client for
 * the candidate host and `saveProfile()` writes through ConnectionStorage.
 *
 * Both of those paths need real Android storage and network, so they are covered
 * by instrumented tests rather than here. These tests cover the deterministic,
 * JVM-testable surface.
 */
class ConnectionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `field setters update exposed state`() = runTest {
        val viewModel = ConnectionViewModel()

        viewModel.updateName("My Server")
        viewModel.updateMode(ConnectionMode.LOCAL_LAN)
        viewModel.updateTransport(TransportType.WEBSOCKET)
        viewModel.updateHost("192.168.1.10")
        viewModel.updatePort("9000")
        viewModel.updateToken("secret-token")
        viewModel.updateUseHttps(true)

        assertEquals("My Server", viewModel.profileName.value)
        assertEquals(ConnectionMode.LOCAL_LAN, viewModel.connectionMode.value)
        assertEquals(TransportType.WEBSOCKET, viewModel.transportType.value)
        assertEquals("192.168.1.10", viewModel.host.value)
        assertEquals("9000", viewModel.port.value)
        assertEquals("secret-token", viewModel.token.value)
        assertEquals(true, viewModel.useHttps.value)
    }
}
