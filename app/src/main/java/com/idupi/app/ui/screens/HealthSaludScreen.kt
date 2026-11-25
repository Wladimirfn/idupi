package com.idupi.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.idupi.app.data.health.HealthDigestCopy
import com.idupi.app.data.health.HealthMetricCard
import com.idupi.app.data.health.HealthSalud
import com.idupi.app.data.health.HealthTrend
import com.idupi.app.data.health.HealthTrendStyle
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBg
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.StatusConnected
import com.idupi.app.ui.theme.StatusError
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary
import com.idupi.app.viewmodel.HealthSaludViewModel

/**
 * Salud (phone-health T3): today's metrics with their source, simple Canvas
 * trends for the last seven days and the agent digest. "Actualizar ahora" asks
 * the PC to read Huawei Health on a hidden display and then re-reads the
 * phone-side Health Connect/wellbeing report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthSaludScreen(
    viewModel: HealthSaludViewModel,
    onMenuClick: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage, state.digestMessage) {
        val message = state.errorMessage ?: state.digestMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
        viewModel.clearDigestMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Salud", style = AppTypography.appBarTitle, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menú", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateCard),
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                androidx.compose.material3.Snackbar(
                    containerColor = SlateCard,
                    contentColor = TextPrimary,
                    shape = AppShapes.card,
                ) {
                    Text(data.visuals.message, color = TextPrimary)
                }
            }
        },
        containerColor = SlateBg,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = AppSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            HealthHeader(
                dateLabel = state.view.dateLabel,
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refreshNow,
            )

            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = PrimaryIndigo)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    contentPadding = PaddingValues(bottom = AppSpacing.xl),
                ) {
                    if (!state.view.hasData) {
                        item { EmptyHealthState() }
                    } else {
                        item {
                            Text(
                                text = "Hoy",
                                style = AppTypography.titleSmall,
                                color = TextSecondary,
                            )
                        }
                        items(state.view.cards.size) { index ->
                            MetricCard(state.view.cards[index])
                        }
                        if (state.view.trends.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Últimos 7 días",
                                    style = AppTypography.titleSmall,
                                    color = TextSecondary,
                                )
                            }
                            items(state.view.trends.size) { index ->
                                TrendCard(state.view.trends[index])
                            }
                        }
                    }
                    item {
                        DigestCard(
                            state = state,
                            onGenerate = viewModel::generateDigest,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthHeader(
    dateLabel: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dateLabel.ifBlank { "Hoy" },
                style = AppTypography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            if (isRefreshing) {
                Text(
                    text = HealthSaludViewModel.MESSAGE_READING,
                    style = AppTypography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = PrimaryIndigo)
        }
        Button(
            onClick = onRefresh,
            enabled = !isRefreshing,
            shape = AppShapes.button,
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryIndigo,
                contentColor = Color.White,
            ),
        ) {
            Text("Actualizar ahora", style = AppTypography.bodySmall)
        }
    }
}

@Composable
private fun EmptyHealthState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(SlateBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = TextSecondary)
                }
                Spacer(modifier = Modifier.width(AppSpacing.lg))
                Text(
                    text = HealthDigestCopy.EMPTY_TITLE,
                    style = AppTypography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
            }
            Text(
                text = HealthDigestCopy.EMPTY_BODY,
                style = AppTypography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun MetricCard(card: HealthMetricCard) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            Text(
                text = card.title,
                style = AppTypography.titleSmall,
                color = TextSecondary,
            )
            Text(
                text = card.value,
                style = AppTypography.metricHeadline,
                color = TextPrimary,
            )
            card.detail?.let { detail ->
                Text(text = detail, style = AppTypography.bodySmall, color = TextSecondary)
            }
            card.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = StatusConnected,
                    trackColor = SlateBorder,
                )
            }
            card.sourceLabel?.let { source ->
                Text(
                    text = source,
                    style = AppTypography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun TrendCard(trend: HealthTrend) {
    val lastValue = trend.points.lastOrNull { it != null }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = trend.title,
                    style = AppTypography.titleSmall,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (lastValue != null) {
                    Text(
                        text = HealthSalud.formatTrendValue(lastValue, trend.unit),
                        style = AppTypography.bodyLarge,
                        color = PrimaryIndigo,
                    )
                }
            }
            TrendChart(trend = trend, color = PrimaryIndigo)
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = trend.labels.firstOrNull()?.takeLast(5).orEmpty(),
                    style = AppTypography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = trend.labels.lastOrNull()?.takeLast(5).orEmpty(),
                    style = AppTypography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** Hand-drawn seven-day chart; no chart library (phone-health T3). */
@Composable
private fun TrendChart(trend: HealthTrend, color: Color) {
    val values = trend.points.filterNotNull()
    val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    val min = values.minOrNull() ?: 0f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        val count = trend.points.size.coerceAtLeast(1)
        val slot = size.width / count
        when (trend.style) {
            HealthTrendStyle.BARS -> {
                trend.points.forEachIndexed { index, point ->
                    if (point == null) return@forEachIndexed
                    val ratio = (point / max).coerceIn(0f, 1f)
                    drawRect(
                        color = color.copy(alpha = 0.75f),
                        topLeft = Offset(index * slot + slot * 0.2f, size.height - ratio * size.height),
                        size = Size(slot * 0.6f, ratio * size.height),
                    )
                }
            }
            HealthTrendStyle.SPARKLINE -> {
                val span = (max - min).takeIf { it > 0.0001f } ?: 1f
                var previous: Offset? = null
                trend.points.forEachIndexed { index, point ->
                    if (point == null) {
                        previous = null
                        return@forEachIndexed
                    }
                    val x = index * slot + slot / 2f
                    val y = size.height -
                        ((point - min) / span).coerceIn(0f, 1f) * size.height
                    val current = Offset(x, y)
                    previous?.let { start ->
                        drawLine(
                            color = color,
                            start = start,
                            end = current,
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                    drawCircle(color = color, radius = 2.5.dp.toPx(), center = current)
                    previous = current
                }
            }
        }
    }
}

@Composable
private fun DigestCard(
    state: com.idupi.app.viewmodel.HealthSaludState,
    onGenerate: () -> Unit,
) {
    val digest = state.digest
    val busy = state.isDigestBusy || digest.isAnalyzing
    val stateText = HealthDigestCopy.stateText(digest)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Resumen del día",
                    style = AppTypography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = PrimaryIndigo,
                    )
                }
            }

            if (stateText != null) {
                Text(
                    text = stateText,
                    style = AppTypography.bodySmall,
                    color = if (digest.isFailed) StatusError else TextSecondary,
                )
            }

            if (digest.hasContent) {
                Text(
                    text = digest.summary,
                    style = AppTypography.bodyLarge,
                    color = TextPrimary,
                )
            }

            DigestList("Destacados", digest.highlights)
            DigestList("Recomendaciones", digest.recommendations)
            DigestList("Agenda", digest.agendaNotes)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                if (digest.available && !busy) {
                    Button(
                        onClick = onGenerate,
                        enabled = !state.isDigestBusy,
                        shape = AppShapes.button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryIndigo,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(
                            text = if (digest.isFailed) {
                                "Reintentar"
                            } else {
                                HealthDigestCopy.actionLabel(digest)
                            },
                            style = AppTypography.bodySmall,
                        )
                    }
                }
            }

            Divider(color = SlateBorder)
            Text(
                text = HealthDigestCopy.FOOTER,
                style = AppTypography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun DigestList(title: String, entries: List<String>) {
    if (entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
        Text(text = title, style = AppTypography.titleSmall, color = TextSecondary)
        entries.forEach { entry ->
            Text(text = "• $entry", style = AppTypography.bodySmall, color = TextPrimary)
        }
    }
}
