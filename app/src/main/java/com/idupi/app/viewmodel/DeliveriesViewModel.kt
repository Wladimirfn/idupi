package com.idupi.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idupi.app.IduPiApp
import com.idupi.app.data.IduPiClientProvider
import com.idupi.app.data.install.ApkInstaller
import com.idupi.app.data.install.InstallOutcome
import com.idupi.app.data.install.InstallResultStore
import com.idupi.app.data.install.PackageInstallerApkInstaller
import com.idupi.app.domain.model.DeliveryInfo
import com.idupi.app.domain.repository.IduPiClientSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Per-row install state shown by the deliveries list. */
sealed interface DeliveryInstallState {
    data object Idle : DeliveryInstallState

    /** [progress] is 0..1, or `null` when the delivery did not report a size. */
    data class Downloading(val progress: Float?) : DeliveryInstallState

    /** Session committed; the system dialog / install is in flight. */
    data object Installing : DeliveryInstallState

    data object Installed : DeliveryInstallState

    data class Failed(val message: String) : DeliveryInstallState
}

/**
 * State for the Entregas screen: list, per-delivery install progress, and the
 * unknown-sources gate.
 *
 * Installs are serialized on purpose -- the system owns one confirmation
 * dialog at a time, and the final verdict arrives asynchronously through
 * [InstallResultStore].
 */
class DeliveriesViewModel(
    private val clientSource: IduPiClientSource = IduPiClientProvider,
    private val installerProvider: () -> ApkInstaller = {
        PackageInstallerApkInstaller(IduPiApp.instance.applicationContext) { id ->
            clientSource.client.downloadDelivery(id)
        }
    },
    private val resultStore: InstallResultStore = InstallResultStore.shared,
) : ViewModel() {

    private val client get() = clientSource.client

    // Lazy so JVM tests can inject a fake provider without touching IduPiApp.
    private val installer: ApkInstaller by lazy { installerProvider() }

    private val _deliveries = MutableStateFlow<List<DeliveryInfo>>(emptyList())
    val deliveries: StateFlow<List<DeliveryInfo>> = _deliveries.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _installStates =
        MutableStateFlow<Map<String, DeliveryInstallState>>(emptyMap())
    val installStates: StateFlow<Map<String, DeliveryInstallState>> = _installStates.asStateFlow()

    private val _permissionGranted = MutableStateFlow(true)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Delivery with an install in flight; installs are serialized. */
    private var activeInstallId: String? = null

    init {
        refreshPermission()
        refresh()
        viewModelScope.launch {
            resultStore.outcomes.collect { outcome -> onInstallOutcome(outcome) }
        }
    }

    /**
     * Re-reads `canRequestPackageInstalls()`. The screen calls this on resume
     * so returning from the system's "install unknown apps" settings updates
     * the gate without a reload.
     */
    fun refreshPermission() {
        _permissionGranted.value = installer.canInstallPackages()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _deliveries.value = client.listDeliveries()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load deliveries", e)
                _errorMessage.value = "No se pudieron cargar las entregas: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun install(delivery: DeliveryInfo) {
        if (activeInstallId != null) return
        if (_installStates.value[delivery.id].isInFlight()) return
        if (!installer.canInstallPackages()) {
            _permissionGranted.value = false
            return
        }

        activeInstallId = delivery.id
        setInstallState(delivery.id, DeliveryInstallState.Downloading(initialProgress(delivery)))

        viewModelScope.launch {
            try {
                installer.install(delivery) { written ->
                    setInstallState(
                        delivery.id,
                        DeliveryInstallState.Downloading(progressOf(delivery, written))
                    )
                }
                setInstallState(delivery.id, DeliveryInstallState.Installing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to install ${delivery.id}", e)
                activeInstallId = null
                setInstallState(
                    delivery.id,
                    DeliveryInstallState.Failed(
                        e.localizedMessage ?: "No se pudo instalar el APK"
                    )
                )
            }
        }
    }

    fun delete(delivery: DeliveryInfo) {
        if (activeInstallId == delivery.id) return
        viewModelScope.launch {
            try {
                client.deleteDelivery(delivery.id)
                _deliveries.update { list -> list.filterNot { it.id == delivery.id } }
                _installStates.update { states -> states - delivery.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete ${delivery.id}", e)
                _errorMessage.value = "No se pudo borrar la entrega: ${e.localizedMessage}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun onInstallOutcome(outcome: InstallOutcome) {
        val deliveryId = outcome.deliveryId ?: activeInstallId ?: return
        if (activeInstallId == deliveryId) {
            activeInstallId = null
        }
        setInstallState(
            deliveryId,
            if (outcome.success) {
                DeliveryInstallState.Installed
            } else {
                DeliveryInstallState.Failed(outcome.message)
            }
        )
    }

    private fun setInstallState(deliveryId: String, state: DeliveryInstallState) {
        _installStates.update { states -> states + (deliveryId to state) }
    }

    private fun initialProgress(delivery: DeliveryInfo): Float? =
        if (delivery.size > 0L) 0f else null

    private fun progressOf(delivery: DeliveryInfo, written: Long): Float? =
        if (delivery.size > 0L) {
            (written.toFloat() / delivery.size.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }

    private fun DeliveryInstallState?.isInFlight(): Boolean =
        this is DeliveryInstallState.Downloading || this is DeliveryInstallState.Installing

    companion object {
        private const val TAG = "DeliveriesViewModel"
    }
}
