package com.idupi.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.idupi.app.domain.model.CpuMetrics
import com.idupi.app.domain.model.DiskMetrics
import com.idupi.app.domain.model.DiskUsageLevel
import com.idupi.app.domain.model.GpuMetrics
import com.idupi.app.domain.model.MemoryMetrics
import com.idupi.app.domain.model.PowerSummary
import com.idupi.app.domain.model.SystemMetrics
import com.idupi.app.domain.model.diskUsageLevel
import com.idupi.app.domain.model.formatMemoryUsageBytes
import com.idupi.app.domain.model.formatMetricCelsius
import com.idupi.app.domain.model.formatMetricGigabytes
import com.idupi.app.domain.model.formatMetricPercent
import com.idupi.app.domain.model.formatMetricWatts
import com.idupi.app.domain.model.knownPowerSummary
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBg
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.StatusConnected
import com.idupi.app.ui.theme.StatusError
import com.idupi.app.ui.theme.StatusWorking
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary
import com.idupi.app.viewmodel.DashboardViewModel
import com.idupi.app.viewmodel.MainViewModel

private const val USAGE_WARN_PERCENT = 60.0
private const val USAGE_HOT_PERCENT = 80.0
private const val TEMPERATURE_WARN_C = 60.0
private const val TEMPERATURE_HOT_C = 80.0

/**
 * Dashboard: how the PC is doing right now.
 *
 * Polling follows the screen lifecycle (started on composition/ON_START,
 * stopped on dispose/ON_STOP) so a backgrounded app does not keep the server
 * sampling nvidia-smi every two seconds. Missing sensors render "— no
 * disponible"; the server's hints explain how to enable them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    mainViewModel: MainViewModel,
    viewModel: DashboardViewModel,
    onMenuClick: () -> Unit
) {
    val status by mainViewModel.status.collectAsState()
    val uiState by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Poll only while this screen is visible. The screen is composed while the
    // activity is already RESUMED, so ON_START will not fire again: start here
    // and stop both on ON_STOP (app backgrounded) and on dispose (navigated).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startPolling()
                Lifecycle.Event.ON_STOP -> viewModel.stopPolling()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.startPolling()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopPolling()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Dashboard",
                            style = AppTypography.appBarTitle,
                            color = TextPrimary
                        )
                        Text(
                            "Estado de la PC en vivo",
                            style = AppTypography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menú",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshNow() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Actualizar",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateCard)
            )
        },
        containerColor = SlateBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(AppSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap)
        ) {
            ConnectionHeader(
                connected = status?.connected,
                pcName = status?.pcName,
                project = status?.project,
                agent = status?.agent,
                busy = status?.busy ?: false,
                queueSize = status?.queueSize ?: 0
            )

            val metrics = uiState.metrics
            when {
                metrics != null -> MetricsContent(
                    metrics = metrics,
                    refreshFailed = uiState.refreshFailed
                )

                uiState.isLoading -> LoadingCard()

                else -> MetricsErrorCard(
                    message = uiState.errorMessage,
                    onRetry = { viewModel.refreshNow() }
                )
            }
        }
    }
}

/** Compact version of [com.idupi.app.ui.components.StatusCard] for the header. */
@Composable
private fun ConnectionHeader(
    connected: Boolean?,
    pcName: String?,
    project: String?,
    agent: String?,
    busy: Boolean,
    queueSize: Int
) {
    val statusColor = when {
        connected == false -> StatusError
        connected == null -> TextSecondary
        busy -> StatusWorking
        else -> StatusConnected
    }
    val statusText = when {
        connected == null -> "Consultando estado…"
        !connected -> "Desconectado"
        busy -> "Trabajando…"
        else -> "Conectado · Libre"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(SlateCard)
            .border(1.dp, SlateBorder, AppShapes.card)
            .padding(AppSpacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = pcName ?: "PC sin identificar",
                style = AppTypography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(AppShapes.pill)
                        .background(statusColor)
                )
                Text(
                    text = statusText,
                    style = AppTypography.labelSmall,
                    color = statusColor
                )
            }
        }
        Text(
            text = listOfNotNull(project, agent).joinToString(" · ").ifBlank { "—" },
            style = AppTypography.bodySmall,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (queueSize > 0) {
            Text(
                text = "$queueSize tarea(s) en cola",
                style = AppTypography.labelSmall,
                color = StatusWorking
            )
        }
    }
}

/**
 * Dashboard grid width rule: two cards per row only when the content area is
 * wide enough for a readable half-width card. Below this the cards stack, which
 * is what stops the CPU/RAM rows from being squeezed on small phones.
 */
internal fun dashboardGridColumns(availableWidthDp: Float): Int =
    if (availableWidthDp < MIN_TWO_COLUMN_WIDTH_DP) 1 else 2

private const val MIN_TWO_COLUMN_WIDTH_DP = 360f

@Composable
private fun MetricsContent(metrics: SystemMetrics, refreshFailed: Boolean) {
    // CPU and RAM always exist; one card per GPU follows. Two per row keeps
    // the phone layout dense without hiding any sensor behind a tap; a narrow
    // content area drops to one column instead of compressing the text.
    val cards: List<@Composable (Modifier) -> Unit> = buildList<@Composable (Modifier) -> Unit> {
        add { modifier -> CpuCard(metrics.cpu, modifier) }
        add { modifier -> RamCard(metrics.memory, modifier) }
        metrics.gpus.forEachIndexed { index, gpu ->
            val title = if (metrics.gpus.size > 1) "GPU ${index + 1}" else "GPU"
            add { modifier -> GpuCard(title, gpu, modifier) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
        if (refreshFailed) {
            Text(
                text = "No se pudieron actualizar las métricas: se muestran los últimos datos.",
                style = AppTypography.bodySmall,
                color = StatusWorking
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = dashboardGridColumns(maxWidth.value)
            val rows = cards.chunked(columns)
            // BoxWithConstraints stacks its children like a Box: without this
            // Column every grid row is drawn on top of the previous one, and
            // the GPU row hid the CPU/RAM row.
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                rows.forEach { rowCards ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)
                    ) {
                        rowCards.forEach { card ->
                            card(Modifier.weight(1f).fillMaxHeight())
                        }
                        // An odd number of cards leaves the last row half-width.
                        if (rowCards.size == 1 && columns == 2) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        StorageCard(metrics.disks)
        PowerSummaryCard(metrics)
        if (metrics.hints.isNotEmpty()) {
            HintsCard(metrics.hints)
        }
    }
}

@Composable
private fun CpuCard(cpu: CpuMetrics, modifier: Modifier) {
    MetricCard(title = "CPU", modifier = modifier) {
        UsageHeadline(percent = cpu.usagePercent)
        Text(
            text = cpu.name ?: "— no disponible",
            style = AppTypography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        MetricRow(
            label = "Temperatura",
            value = formatMetricCelsius(cpu.temperatureC),
            valueColor = temperatureColor(cpu.temperatureC)
        )
        MetricRow(label = "Consumo", value = formatMetricWatts(cpu.powerW))
    }
}

@Composable
private fun RamCard(memory: MemoryMetrics, modifier: Modifier) {
    MetricCard(title = "RAM", modifier = modifier) {
        UsageHeadline(percent = memory.usagePercent)
        MetricRow(
            label = "Usada",
            value = formatMemoryUsageBytes(memory.usedBytes, memory.totalBytes)
        )
        MetricRow(
            label = "Temperatura",
            value = formatMetricCelsius(memory.temperatureC),
            valueColor = temperatureColor(memory.temperatureC)
        )
    }
}

@Composable
private fun GpuCard(title: String, gpu: GpuMetrics, modifier: Modifier) {
    MetricCard(title = title, modifier = modifier) {
        UsageHeadline(percent = gpu.usagePercent)
        Text(
            text = gpu.name ?: "— no disponible",
            style = AppTypography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        MetricRow(
            label = "Temperatura",
            value = formatMetricCelsius(gpu.temperatureC),
            valueColor = temperatureColor(gpu.temperatureC)
        )
        MetricRow(label = "Consumo", value = formatMetricWatts(gpu.powerW))
        MetricRow(
            label = "VRAM",
            value = formatMemoryUsageBytes(gpu.memoryUsedBytes, gpu.memoryTotalBytes)
        )
    }
}

/**
 * Full-width "Almacenamiento" card: one swipeable page per fixed disk.
 *
 * The pager state survives the 2 s polling updates because the pages are keyed
 * by mount point; a new sample replaces the numbers, not the page identity, so
 * the owner does not get thrown back to C: while reading D:.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StorageCard(disks: List<DiskMetrics>) {
    MetricCard(title = "Almacenamiento", modifier = Modifier.fillMaxWidth()) {
        if (disks.isEmpty()) {
            Text(
                text = "Sin discos",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )
            return@MetricCard
        }

        val pagerState = rememberPagerState(pageCount = { disks.size })
        HorizontalPager(
            state = pagerState,
            key = { index -> disks[index].mount },
            pageSpacing = AppSpacing.md,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            DiskPage(disks[page])
        }

        if (disks.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppSpacing.xs),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(disks.size) { index ->
                    val selected = index == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = AppSpacing.xxs)
                            .size(if (selected) 8.dp else 6.dp)
                            .clip(AppShapes.pill)
                            .background(if (selected) PrimaryIndigo else SlateBorder)
                    )
                }
            }
        }
    }
}

@Composable
private fun DiskPage(disk: DiskMetrics) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = disk.mount.ifBlank { "Disco" },
                style = AppTypography.titleMedium,
                color = TextPrimary,
                maxLines = 1
            )
            if (!disk.label.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(AppSpacing.sm))
                Text(
                    text = disk.label,
                    style = AppTypography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        val percent = disk.usagePercent
        if (percent != null && percent.isFinite()) {
            val color = diskColor(diskUsageLevel(percent))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Uso del disco",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    text = formatMetricPercent(percent) ?: "—",
                    style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = color
                )
            }
            LinearProgressIndicator(
                progress = (percent / 100.0).toFloat().coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(AppShapes.pill),
                color = color,
                trackColor = SlateBorder
            )
        }

        MetricRow(
            label = "Usado",
            value = formatMemoryUsageBytes(disk.usedBytes, disk.totalBytes)
        )
        MetricRow(label = "Libre", value = formatMetricGigabytes(disk.freeBytes))
        MetricRow(label = "Sistema de archivos", value = disk.fileSystem)
    }
}

@Composable
private fun diskColor(level: DiskUsageLevel): Color = when (level) {
    DiskUsageLevel.OK -> StatusConnected
    DiskUsageLevel.WARN -> StatusWorking
    DiskUsageLevel.CRITICAL -> StatusError
    DiskUsageLevel.UNKNOWN -> TextSecondary
}

@Composable
private fun PowerSummaryCard(metrics: SystemMetrics) {
    val summary = metrics.knownPowerSummary()
    MetricCard(title = "Consumo total CPU+GPU", modifier = Modifier.fillMaxWidth()) {
        if (summary == null) {
            Text(
                text = "— no disponible",
                style = AppTypography.labelSmall,
                color = TextSecondary
            )
        } else {
            Text(
                text = formatMetricWatts(summary.totalW) ?: "— no disponible",
                style = AppTypography.displayLarge,
                color = TextPrimary
            )
            if (summary.partial) {
                Text(
                    text = partialPowerLabel(summary),
                    style = AppTypography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

private fun partialPowerLabel(summary: PowerSummary): String = when {
    summary.missingCpu && summary.missingGpu -> "Parcial: faltan los sensores de CPU y GPU"
    summary.missingCpu -> "Parcial: falta el consumo del CPU"
    else -> "Parcial: falta el consumo de una GPU"
}

@Composable
private fun HintsCard(hints: List<String>) {
    MetricCard(title = "Sensores no disponibles", modifier = Modifier.fillMaxWidth()) {
        hints.forEach { hint ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                Text(text = "•", style = AppTypography.bodySmall, color = TextSecondary)
                Text(
                    text = hint,
                    style = AppTypography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(AppShapes.card)
            .background(SlateCard)
            .border(1.dp, SlateBorder, AppShapes.card),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = PrimaryIndigo)
    }
}

@Composable
private fun MetricsErrorCard(message: String?, onRetry: () -> Unit) {
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
            text = "No se pudieron cargar las métricas",
            style = AppTypography.titleMedium,
            color = TextPrimary
        )
        Text(
            text = message
                ?: "Revisa que el servidor esté corriendo y que el token de Conexión sea correcto.",
            style = AppTypography.bodySmall,
            color = TextSecondary
        )
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
        ) {
            Text(text = "Reintentar", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(SlateCard)
            .border(1.dp, SlateBorder, AppShapes.card)
            .padding(AppSpacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        Text(
            text = title.uppercase(),
            style = AppTypography.titleSmall,
            color = TextSecondary
        )
        content()
    }
}

@Composable
private fun UsageHeadline(percent: Double?) {
    val text = formatMetricPercent(percent)
    if (text == null || percent == null) {
        Text(
            text = "—",
            style = AppTypography.metricHeadline,
            color = TextSecondary
        )
        Text(
            text = "no disponible",
            style = AppTypography.labelSmall,
            color = TextSecondary.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    } else {
        val color = usageColor(percent)
        Text(text = text, style = AppTypography.metricHeadline, color = color, maxLines = 1)
        LinearProgressIndicator(
            progress = (percent / 100.0).toFloat().coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(AppShapes.pill),
            color = color,
            trackColor = SlateBorder
        )
    }
}

/**
 * One label/value line inside a metric card. The label takes the flexible half
 * and the value never wraps: before this, a long value like "8.9 GB / 15.9 GB"
 * and its label both claimed the full row width and overlapped on a half-width
 * card. Both ends ellipsize instead of spilling over each other.
 */
@Composable
private fun MetricRow(
    label: String,
    value: String?,
    valueColor: Color = TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = AppTypography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(AppSpacing.sm))
        if (value == null) {
            Text(
                text = "— no disponible",
                style = AppTypography.labelSmall,
                color = TextSecondary.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                text = value,
                style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun usageColor(percent: Double): Color = when {
    percent < USAGE_WARN_PERCENT -> StatusConnected
    percent < USAGE_HOT_PERCENT -> StatusWorking
    else -> StatusError
}

@Composable
private fun temperatureColor(celsius: Double?): Color = when {
    celsius == null -> TextSecondary
    celsius < TEMPERATURE_WARN_C -> StatusConnected
    celsius < TEMPERATURE_HOT_C -> StatusWorking
    else -> StatusError
}
