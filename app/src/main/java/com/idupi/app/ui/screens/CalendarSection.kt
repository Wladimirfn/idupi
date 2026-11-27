package com.idupi.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.idupi.app.data.calendar.CalendarPermissions
import com.idupi.app.data.calendar.CalendarReportState
import com.idupi.app.data.calendar.CalendarReporting
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
import com.idupi.app.viewmodel.CalendarSettingsReportState
import com.idupi.app.viewmodel.CalendarSettingsViewModel

/**
 * Ajustes > CALENDARIO (phone-calendar T2): the two runtime permissions, the
 * "share my agenda" switch, the Outlook-sync hint, the last report and the
 * manual send.
 *
 * Permissions are platform state: they are re-read on every resume and after
 * the system dialog, never cached in the ViewModel across grants.
 */
@Composable
fun CalendarSection(viewModel: CalendarSettingsViewModel) {
    val shareEnabled by viewModel.shareEnabled.collectAsState()
    val permission by viewModel.permission.collectAsState()
    val report by viewModel.reportState.collectAsState()
    val feedback by viewModel.feedback.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Idempotent process-wide start: "Enviar ahora" must work before any
    // service hosts the reporter in this process.
    LaunchedEffect(Unit) { CalendarReporting.start(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.updatePermission(CalendarPermissions.current(context))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // The dialog result and the real grant can differ (for example when
        // the system auto-denies without showing a dialog); read the platform.
        viewModel.updatePermission(CalendarPermissions.current(context))
    }

    SettingsSection {
        CalendarSharingCard(
            shareEnabled = shareEnabled,
            canRead = permission.canRead,
            canWrite = permission.canWrite,
            report = report,
            feedback = feedback,
            onShareChange = viewModel::setShareEnabled,
            onRequestPermissions = { permissionLauncher.launch(CalendarPermissions.REQUEST) },
            onSendNow = viewModel::sendNow,
        )
    }
}

@Composable
private fun CalendarSharingCard(
    shareEnabled: Boolean,
    canRead: Boolean,
    canWrite: Boolean,
    report: CalendarReportState,
    feedback: CalendarSettingsReportState,
    onShareChange: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onSendNow: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val statusColor = if (canRead) StatusConnected else StatusError
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
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = statusColor,
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.lg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Calendario del teléfono",
                        style = AppTypography.bodyLarge,
                        color = TextPrimary,
                    )
                    Text(
                        text = if (canRead) "Activado" else "Desactivado",
                        style = AppTypography.bodySmall,
                        color = statusColor,
                    )
                }
            }

            if (!canRead) {
                Text(
                    text = "IDUPI lee los eventos de los próximos 14 días y puede " +
                        "crear eventos cuando tú lo confirmas.",
                    style = AppTypography.bodySmall,
                    color = TextSecondary,
                )
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Dar acceso al calendario", style = AppTypography.bodySmall)
                }
            } else if (!canWrite) {
                Text(
                    text = "Falta el permiso para crear eventos en el calendario.",
                    style = AppTypography.bodySmall,
                    color = StatusError,
                )
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Permitir crear eventos", style = AppTypography.bodySmall)
                }
            }

            Text(
                text = "Para ver tu calendario de Outlook aquí, activa " +
                    "\"Sincronizar calendarios\" en la app de Outlook.",
                style = AppTypography.bodySmall,
                color = TextSecondary,
            )

            Divider(color = SlateBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Compartir mi agenda con el PC",
                        style = AppTypography.bodyLarge,
                        color = TextPrimary,
                    )
                    Text(
                        text = "Eventos de los próximos 14 días. Se envía como mucho " +
                            "cada 15 minutos.",
                        style = AppTypography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Switch(
                    checked = shareEnabled,
                    onCheckedChange = onShareChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrimaryIndigo,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = SlateBg,
                        uncheckedBorderColor = SlateBorder,
                    ),
                )
            }

            Text(
                text = feedback.message ?: calendarLastReportLabel(report, now),
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
                    enabled = !feedback.isSending && canRead,
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
