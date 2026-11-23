package com.idupi.app.viewmodel

import com.idupi.app.FakeClientSource
import com.idupi.app.FakeIduPiClient
import com.idupi.app.MainDispatcherRule
import com.idupi.app.data.install.ApkInstallException
import com.idupi.app.data.install.ApkInstaller
import com.idupi.app.data.install.InstallOutcome
import com.idupi.app.data.install.InstallResultStore
import com.idupi.app.domain.model.DeliveryInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DeliveriesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeIduPiClient
    private lateinit var installer: FakeApkInstaller
    private lateinit var resultStore: InstallResultStore

    private val delivery = DeliveryInfo(
        id = "dlv-0123456789ab",
        name = "app-release.apk",
        size = 1024L,
        sha256 = "a".repeat(64),
        sourcePath = "C:\\build\\app-release.apk",
        createdAt = "2026-11-23T10:00:00.000Z"
    )

    @Before
    fun setUp() {
        fake = FakeIduPiClient()
        installer = FakeApkInstaller()
        resultStore = InstallResultStore()
    }

    private fun createViewModel() = DeliveriesViewModel(
        clientSource = FakeClientSource(fake),
        installerProvider = { installer },
        resultStore = resultStore,
    )

    @Test
    fun `init loads deliveries and reads the install permission`() = runTest {
        fake.deliveriesToReturn = listOf(delivery)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf(delivery), viewModel.deliveries.value)
        assertTrue(viewModel.permissionGranted.value)
        assertFalse(viewModel.isLoading.value)
        assertEquals(1, fake.listDeliveriesCalls)
    }

    @Test
    fun `list failure surfaces a Spanish error message`() = runTest {
        fake.failWith = RuntimeException("offline")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("offline"))
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun `install moves downloading then installing then installed`() = runTest {
        fake.deliveriesToReturn = listOf(delivery)
        installer.progressSteps = listOf(512L)
        installer.gate = CompletableDeferred()

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.install(delivery)
        runCurrent()

        assertEquals(
            DeliveryInstallState.Downloading(0.5f),
            viewModel.installStates.value[delivery.id]
        )
        assertEquals(1, installer.installCalls)
        assertEquals(delivery, installer.lastDelivery)

        installer.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(DeliveryInstallState.Installing, viewModel.installStates.value[delivery.id])

        resultStore.publish(InstallOutcome(delivery.id, success = true, message = "APK instalada"))
        advanceUntilIdle()
        assertEquals(DeliveryInstallState.Installed, viewModel.installStates.value[delivery.id])
    }

    @Test
    fun `install failure is reported on the row`() = runTest {
        fake.deliveriesToReturn = listOf(delivery)
        installer.failure = ApkInstallException("El APK descargado está dañado (SHA-256 no coincide).")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.install(delivery)
        advanceUntilIdle()

        val state = viewModel.installStates.value[delivery.id]
        assertTrue(state is DeliveryInstallState.Failed)
        assertEquals(
            "El APK descargado está dañado (SHA-256 no coincide).",
            (state as DeliveryInstallState.Failed).message
        )
    }

    @Test
    fun `install is refused when unknown sources are not allowed`() = runTest {
        installer.canInstall = false

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.permissionGranted.value)

        viewModel.install(delivery)
        advanceUntilIdle()

        assertEquals(0, installer.installCalls)
        assertEquals(null, viewModel.installStates.value[delivery.id])
    }

    @Test
    fun `a second install is ignored while one is in flight`() = runTest {
        fake.deliveriesToReturn = listOf(delivery, delivery.copy(id = "dlv-bbbbbbbbbbbb", name = "other.apk"))
        installer.gate = CompletableDeferred()

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.install(delivery)
        runCurrent()
        viewModel.install(delivery.copy(id = "dlv-bbbbbbbbbbbb", name = "other.apk"))
        runCurrent()

        assertEquals(1, installer.installCalls)
    }

    @Test
    fun `failure outcome from the receiver maps to the row message`() = runTest {
        fake.deliveriesToReturn = listOf(delivery)

        val viewModel = createViewModel()
        advanceUntilIdle()

        resultStore.publish(
            InstallOutcome(
                deliveryId = delivery.id,
                success = false,
                message = "La firma no coincide con la app instalada"
            )
        )
        advanceUntilIdle()

        assertEquals(
            DeliveryInstallState.Failed("La firma no coincide con la app instalada"),
            viewModel.installStates.value[delivery.id]
        )
    }

    @Test
    fun `delete removes the row and calls the client`() = runTest {
        fake.deliveriesToReturn = listOf(delivery)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.delete(delivery)
        advanceUntilIdle()

        assertEquals(listOf(delivery.id), fake.deletedDeliveryIds)
        assertTrue(viewModel.deliveries.value.isEmpty())
    }

    @Test
    fun `delete failure keeps the row and surfaces an error`() = runTest {
        fake.deliveriesToReturn = listOf(delivery)

        val viewModel = createViewModel()
        advanceUntilIdle()

        fake.failWith = RuntimeException("delete failed")
        viewModel.delete(delivery)
        advanceUntilIdle()

        assertEquals(listOf(delivery), viewModel.deliveries.value)
        assertNotNull(viewModel.errorMessage.value)
    }
}

/** Test double for the PackageInstaller-backed installer. */
private class FakeApkInstaller(
    var canInstall: Boolean = true,
    var progressSteps: List<Long> = emptyList(),
    var failure: Exception? = null,
) : ApkInstaller {

    /** When set, [install] suspends after reporting progress until completed. */
    var gate: CompletableDeferred<Unit>? = null
    var installCalls: Int = 0
    var lastDelivery: DeliveryInfo? = null

    override fun canInstallPackages(): Boolean = canInstall

    override suspend fun install(delivery: DeliveryInfo, onProgress: (Long) -> Unit) {
        installCalls++
        lastDelivery = delivery
        failure?.let { throw it }
        progressSteps.forEach(onProgress)
        gate?.await()
    }
}
