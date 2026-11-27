package com.idupi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.idupi.app.data.phone.PhoneAppStorage
import com.idupi.app.data.phone.PhoneDashboardSnapshot
import com.idupi.app.data.phone.PhoneMemory
import com.idupi.app.data.phone.PhoneBattery
import com.idupi.app.data.phone.PhoneNetwork
import com.idupi.app.data.phone.PhoneSignal
import com.idupi.app.data.phone.PhoneStorage
import com.idupi.app.data.phone.batteryChargingLabel
import com.idupi.app.data.phone.bubble.BubbleRequestHost
import com.idupi.app.data.phone.formatPhoneBytes
import com.idupi.app.data.phone.formatPhoneUsageBytes
import com.idupi.app.data.phone.networkKindLabel
import com.idupi.app.data.phone.phoneUsagePercent
import com.idupi.app.data.phone.signalLevelLabel
import com.idupi.app.domain.model.formatMetricCelsius
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.StatusError
import com.idupi.app.ui.theme.StatusWorking
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary
import com.idupi.app.viewmodel.PhoneDashboardViewModel

/** What the "Optimizar teléfono" button asks the bubble agent to do. */
internal const val OPTIMIZE_PHONE_REQUEST = "Optimiza mi teléfono"

/**
 * Shown when the bubble cannot take the request because the accessibility
 * service is not bound: without it there is no panel for progress/approvals,
 * and starting the turn any other way would bypass the owner-turn registry.
 */
internal const val OPTIMIZE_ACCESSIBILITY_HINT =
    "Activa la accesibilidad de IDUPI para optimizar desde la burbuja."

/**
 * Page 1 of the Dashboard: this phone's own status (phone-dashboard T2).
 *
 * Every card reuses the PC page's visual language ([MetricCard], [MetricRow],
 * [UsageHeadline]) so swiping between pages feels like one dashboard. The data
 * comes from [PhoneDashboardViewModel], which reads the platform locally; this
 * page never talks to the PC.
 */
@Composable
internal fun PhoneDashboardPage(
    state: PhoneDashboardViewModel.UiState,
    onRetry: () -> Unit,
    onOpenUsageAccess: () -> Unit,
) {
    val snapshot = state.snapshot
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap)
    ) {
        if (snapshot == null) {
            if (state.isLoading) LoadingCard() else PhoneErrorCard(state.errorMessage, onRetry)
            return@Column
        }

        if (state.refreshFailed) {
            Text(
                text = "No se pudo actualizar el estado del teléfono: se muestran los últimos datos.",
                style = AppTypography.bodySmall,
                color = StatusWorking
            )
        }

        PhoneStorageCard(snapshot.storage)
        PhoneRamBatteryCards(snapshot)
        PhoneNetworkCard(snapshot.network, snapshot.signal)
        PhoneAppStorageCard(snapshot, onOpenUsageAccess)
        PhoneTopAppsCard(snapshot)
        OptimizePhoneButton()
    }
}

@Composable
private fun PhoneStorageCard(storage: PhoneStorage?) {
    MetricCard(title = "Almacenamiento", modifier = Modifier.fillMaxWidth()) {
        UsageHeadline(percent = phoneUsagePercent(storage?.usedBytes, storage?.totalBytes))
        MetricRow(
            label = "Usada",
            value = formatPhoneUsageBytes(storage?.usedBytes, storage?.totalBytes)
        )
    }
}

/** RAM and battery share a row where the content is wide enough, like CPU/RAM. */
@Composable
private fun PhoneRamBatteryCards(snapshot: PhoneDashboardSnapshot) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = dashboardGridColumns(maxWidth.value)
        if (columns == 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)
            ) {
                PhoneRamCard(snapshot.memory, snapshot.lowMemory, Modifier.weight(1f).fillMaxHeight())
                PhoneBatteryCard(snapshot.battery, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                PhoneRamCard(snapshot.memory, snapshot.lowMemory, Modifier.fillMaxWidth())
                PhoneBatteryCard(snapshot.battery, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PhoneRamCard(memory: PhoneMemory?, lowMemory: Boolean, modifier: Modifier) {
    MetricCard(title = "RAM", modifier = modifier) {
        UsageHeadline(percent = phoneUsagePercent(memory?.usedBytes, memory?.totalBytes))
        MetricRow(
            label = "Usada",
            value = formatPhoneUsageBytes(memory?.usedBytes, memory?.totalBytes)
        )
        if (lowMemory) {
            Text(
                text = "Memoria baja",
                style = AppTypography.labelSmall,
                color = StatusError
            )
        }
    }
}

@Composable
private fun PhoneBatteryCard(battery: PhoneBattery?, modifier: Modifier) {
    MetricCard(title = "Batería", modifier = modifier) {
        UsageHeadline(percent = battery?.percent?.toDouble())
        MetricRow(label = "Cargando", value = batteryChargingLabel(battery?.charging))
        MetricRow(
            label = "Temperatura",
            value = formatMetricCelsius(battery?.temperatureC),
            valueColor = temperatureColor(battery?.temperatureC)
        )
    }
}

@Composable
private fun PhoneNetworkCard(network: PhoneNetwork?, signal: PhoneSignal?) {
    MetricCard(title = "Red", modifier = Modifier.fillMaxWidth()) {
        MetricRow(label = "Tipo", value = networkKindLabel(network?.kind))
        MetricRow(label = "Señal", value = signalLevelLabel(signal?.level))
    }
}

@Composable
private fun PhoneAppStorageCard(
    snapshot: PhoneDashboardSnapshot,
    onOpenUsageAccess: () -> Unit,
) {
    MetricCard(title = "Apps que más ocupan", modifier = Modifier.fillMaxWidth()) {
        if (!snapshot.usageAccessGranted) {
            Text(
                text = "Necesario para leer el tamaño y la caché de tus apps.",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )
            Button(
                onClick = onOpenUsageAccess,
                modifier = Modifier.fillMaxWidth(),
                shape = AppShapes.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryIndigo,
                    contentColor = Color.White
                )
            ) {
                Text("Dar acceso a datos de uso", style = AppTypography.bodySmall)
            }
            return@MetricCard
        }

        val summary = snapshot.appStorage
        if (summary == null) {
            Text(
                text = "Leyendo el tamaño de tus apps…",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )
            return@MetricCard
        }

        MetricRow(label = "Caché total", value = formatPhoneBytes(summary.totalCacheBytes))
        if (summary.apps.isEmpty()) {
            Text(
                text = "Sin apps para mostrar.",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )
        } else {
            summary.apps.forEach { app -> PhoneAppStorageRow(app) }
        }
    }
}

@Composable
private fun PhoneAppStorageRow(app: PhoneAppStorage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label.ifBlank { app.packageName },
                style = AppTypography.bodySmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Caché ${formatPhoneBytes(app.cacheBytes) ?: "—"}",
                style = AppTypography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sm))
        Text(
            text = formatPhoneBytes(app.totalBytes) ?: "—",
            style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
            maxLines = 1
        )
    }
}

@Composable
private fun PhoneTopAppsCard(snapshot: PhoneDashboardSnapshot) {
    MetricCard(title = "Más usadas hoy", modifier = Modifier.fillMaxWidth()) {
        if (!snapshot.usageAccessGranted) {
            Text(
                text = "Activa el acceso a datos de uso para ver el tiempo por app.",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )
            return@MetricCard
        }
        if (snapshot.topAppsToday.isEmpty()) {
            Text(
                text = "Sin uso registrado hoy.",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )
            return@MetricCard
        }
        snapshot.topAppsToday.forEach { app ->
            MetricRow(
                label = app.label.ifBlank { app.packageName },
                value = "${app.minutes} min"
            )
        }
    }
}

/**
 * Starts the optimization as an owner bubble turn. The overlay expands the
 * panel and sends through its normal path, so progress and approvals show in
 * the bubble and `OwnerTurnRegistry` treats the turn as owner-initiated. With
 * accessibility off nothing is started and the owner is told how to enable it.
 */
@Composable
private fun OptimizePhoneButton() {
    var showAccessibilityHint by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        Button(
            onClick = {
                showAccessibilityHint = !BubbleRequestHost.send(OPTIMIZE_PHONE_REQUEST)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.button,
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryIndigo,
                contentColor = Color.White
            )
        ) {
            Text(
                text = "Optimizar teléfono",
                style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
            )
        }
        if (showAccessibilityHint) {
            Text(
                text = OPTIMIZE_ACCESSIBILITY_HINT,
                style = AppTypography.bodySmall,
                color = StatusWorking
            )
        }
    }
}

@Composable
private fun PhoneErrorCard(message: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(SlateCard)
            .border(1.dp, SlateBorder, AppShapes.card)
            .padding(AppSpacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        Text(
            text = "No se pudo leer el estado del teléfono",
            style = AppTypography.titleMedium,
            color = TextPrimary
        )
        Text(
            text = message ?: "Revisa los permisos del teléfono e intenta de nuevo.",
            style = AppTypography.bodySmall,
            color = TextSecondary
        )
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryIndigo,
                contentColor = Color.White
            )
        ) {
            Text(text = "Reintentar", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}
