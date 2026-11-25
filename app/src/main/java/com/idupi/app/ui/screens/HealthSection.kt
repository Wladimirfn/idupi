package com.idupi.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.idupi.app.data.health.HealthConnectAvailability
import com.idupi.app.data.health.HealthConnectPermissions
import com.idupi.app.data.health.HealthReportState
import com.idupi.app.data.health.HealthReporting
import com.idupi.app.data.health.HealthRoute
import com.idupi.app.data.health.healthConnectInstallIntent
import com.idupi.app.data.health.healthConnectInstallWebIntent
import com.idupi.app.data.health.healthConnectSettingsIntent
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.StatusConnected
import com.idupi.app.ui.theme.StatusError
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary
import com.idupi.app.viewmodel.HealthSettingsFeedback
import com.idupi.app.viewmodel.HealthSettingsState
import com.idupi.app.viewmodel.HealthSettingsViewModel

/**
 * Ajustes > SALUD (phone-health T2): Health Connect status, the health
 * permissions, the detected sources with their route, the last report and the
 * manual send. T3 extends this section with today's metrics and trends.
 *
 * Platform state (availability, permissions) is re-read on every resume and
 * after the permission dialog instead of being cached at first composition.
 */
@Composable
fun HealthSection(viewModel: HealthSettingsViewModel) {
    val state by viewModel.state.collectAsState()
    val report by viewModel.reportState.collectAsState()
    val feedback by viewModel.feedback.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Idempotent process-wide start: "Enviar ahora" must work before any
    // service hosts the reporter in this process, and the source list needs
    // the reporter's context to read Health Connect origins.
    LaunchedEffect(Unit) {
        HealthReporting.start(context)
        viewModel.refresh()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        remember { HealthConnectPermissions.requestContract() },
    ) { granted ->
        // The dialog result and the real grant can differ (for example when
        // the system auto-denies without showing a dialog); the ViewModel
        // re-reads the platform after recording the answer.
        viewModel.onPermissionResult(granted)
    }

    SettingsSection("SALUD") {
        HealthCard(
            state = state,
            report = report,
            feedback = feedback,
            onRequestPermissions = {
                permissionLauncher.launch(HealthConnectPermissions.READ_PERMISSIONS)
            },
            onOpenHealthConnect = {
                runCatching { context.startActivity(healthConnectSettingsIntent()) }
            },
            onInstallHealthConnect = {
                runCatching { context.startActivity(healthConnectInstallIntent()) }
                    .onFailure {
                        runCatching { context.startActivity(healthConnectInstallWebIntent()) }
                    }
            },
            onSendNow = viewModel::sendNow,
        )
    }
}

@Composable
private fun HealthCard(
    state: HealthSettingsState,
    report: HealthReportState,
    feedback: HealthSettingsFeedback,
    onRequestPermissions: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    onSendNow: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val available = state.availability == HealthConnectAvailability.AVAILABLE
    val statusColor = when (state.availability) {
        HealthConnectAvailability.AVAILABLE -> StatusConnected
        HealthConnectAvailability.NEEDS_UPDATE -> StatusError
        HealthConnectAvailability.UNAVAILABLE -> TextSecondary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(SlateBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = statusColor)
                }
                Spacer(modifier = Modifier.width(AppSpacing.lg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = healthConnectStatusText(state.availability),
                        style = AppTypography.bodyLarge,
                        color = TextPrimary,
                    )
                    Text(
                        text = healthConnectStatusDetail(state.availability),
                        style = AppTypography.bodySmall,
                        color = statusColor,
                    )
                }
            }

            if (!available) {
                Button(
                    onClick = onInstallHealthConnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        text = if (state.availability == HealthConnectAvailability.NEEDS_UPDATE) {
                            "Actualizar Health Connect"
                        } else {
                            "Instalar Health Connect"
                        },
                        style = AppTypography.bodySmall,
                    )
                }
            } else {
                Text(
                    text = "Permisos de salud: ${state.grantedCount} de " +
                        "${state.requiredPermissions.size}",
                    style = AppTypography.bodySmall,
                    color = if (state.hasAllPermissions) StatusConnected else TextSecondary,
                )
                if (!state.hasAllPermissions) {
                    Text(
                        text = "IDUPI necesita permiso para leer pasos, distancia, " +
                            "calorías, frecuencia cardíaca, sueño, entrenamientos, " +
                            "peso y oxígeno en sangre.",
                        style = AppTypography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    if (!state.hasAllPermissions) {
                        Button(
                            onClick = onRequestPermissions,
                            modifier = Modifier.weight(1f),
                            shape = AppShapes.button,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryIndigo,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text("Dar permisos de salud", style = AppTypography.bodySmall)
                        }
                    }
                    Button(
                        onClick = onOpenHealthConnect,
                        modifier = Modifier.weight(1f),
                        shape = AppShapes.button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlateBorder,
                            contentColor = TextPrimary,
                        ),
                    ) {
                        Text("Abrir Health Connect", style = AppTypography.bodySmall)
                    }
                }
            }

            Divider(color = SlateBorder)

            Text(
                text = "Fuentes detectadas",
                style = AppTypography.titleSmall,
                color = TextSecondary,
            )
            if (state.sources.isEmpty()) {
                Text(
                    text = if (state.isLoadingSources) {
                        "Buscando apps de salud..."
                    } else {
                        "No se detectaron apps de salud en este teléfono."
                    },
                    style = AppTypography.bodySmall,
                    color = TextSecondary,
                )
            } else {
                for (source in state.sources) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = source.label,
                                style = AppTypography.bodyLarge,
                                color = TextPrimary,
                            )
                            Text(
                                text = healthRouteLabel(source.route),
                                style = AppTypography.bodySmall,
                                color = when (source.route) {
                                    HealthRoute.HEALTH_CONNECT.wire -> StatusConnected
                                    HealthRoute.HIDDEN_DISPLAY.wire -> TextSecondary
                                    else -> StatusError
                                },
                            )
                        }
                    }
                }
            }

            Divider(color = SlateBorder)

            Text(
                text = feedback.message ?: healthLastReportLabel(report, now),
                style = AppTypography.bodySmall,
                color = when {
                    feedback.message != null && feedback.success == false -> StatusError
                    feedback.message != null -> StatusConnected
                    report.success == false -> StatusError
                    else -> TextSecondary
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                Button(
                    onClick = onSendNow,
                    enabled = !feedback.isSending,
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Enviar ahora", style = AppTypography.bodySmall)
                }
                if (feedback.isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = PrimaryIndigo,
                    )
                }
            }
        }
    }
}
