package com.idupi.app.ui.screens

import android.content.Intent
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
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.idupi.app.data.phone.PhoneStatusReporting
import com.idupi.app.data.phone.isUsageAccessGranted
import com.idupi.app.data.phone.usageAccessSettingsIntent
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
import com.idupi.app.viewmodel.PhoneAgentReportState
import com.idupi.app.viewmodel.PhoneAgentSettingsViewModel

/**
 * Ajustes > ASISTENTE DEL TELÉFONO (phone-agent T2): status reporting switch,
 * the usage-access special permission and an on-demand snapshot.
 *
 * T3 extends this very section with the "control from the PC" switch and the
 * recent command log, so the section composable and its cards stay here instead
 * of growing inside [SettingsScreen].
 */
@Composable
fun PhoneAgentSection(viewModel: PhoneAgentSettingsViewModel) {
    val enabled by viewModel.phoneStatusEnabled.collectAsState()
    val report by viewModel.reportState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var usageAccess by remember { mutableStateOf(isUsageAccessGranted(context)) }
    // Usage access is granted in the system settings: re-check every time the
    // owner comes back instead of trusting the value from the first render.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageAccess = isUsageAccessGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Idempotent process-wide start: it makes "Enviar ahora" work even when no
    // service is connected in this process yet. The listener owns the lifecycle.
    LaunchedEffect(Unit) { PhoneStatusReporting.start(context) }

    SettingsSection("ASISTENTE DEL TELÉFONO") {
        StatusReportingCard(
            enabled = enabled,
            usageAccessGranted = usageAccess,
            report = report,
            onEnabledChange = viewModel::setPhoneStatusEnabled,
            onOpenUsageAccess = {
                val intent = usageAccessSettingsIntent()
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
            onSendNow = viewModel::sendNow
        )
    }
}

@Composable
private fun StatusReportingCard(
    enabled: Boolean,
    usageAccessGranted: Boolean,
    report: PhoneAgentReportState,
    onEnabledChange: (Boolean) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onSendNow: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(SlateBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = if (enabled) StatusConnected else TextSecondary
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.lg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enviar estado del teléfono a la PC",
                        style = AppTypography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "Batería, señal, red, memoria, almacenamiento y uso de hoy.",
                        style = AppTypography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrimaryIndigo,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = SlateBg,
                        uncheckedBorderColor = SlateBorder
                    )
                )
            }

            Divider(color = SlateBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Acceso a datos de uso",
                        style = AppTypography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = if (usageAccessGranted) "Activado" else "Desactivado",
                        style = AppTypography.bodySmall,
                        color = if (usageAccessGranted) StatusConnected else StatusError
                    )
                }
            }
            if (!usageAccessGranted) {
                Text(
                    text = "Necesario para el tiempo por app y la app en primer plano.",
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
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Button(
                    onClick = onSendNow,
                    enabled = !report.isSending,
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White
                    )
                ) {
                    Text("Enviar ahora", style = AppTypography.bodySmall)
                }
                if (report.isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = PrimaryIndigo
                    )
                }
            }

            report.message?.let { message ->
                Text(
                    text = message,
                    style = AppTypography.bodySmall,
                    color = if (report.success == true) StatusConnected else StatusError
                )
            }
        }
    }
}
