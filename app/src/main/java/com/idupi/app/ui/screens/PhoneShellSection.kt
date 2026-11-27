package com.idupi.app.ui.screens

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.idupi.app.data.phone.shell.PhoneShellStage
import com.idupi.app.data.phone.shell.isPaired
import com.idupi.app.data.phone.shell.phoneShellStatusLabel
import com.idupi.app.ui.screens.settings.SettingsCard
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
import com.idupi.app.viewmodel.PhoneShellViewModel

/**
 * Ajustes > Acceso avanzado del teléfono (phone-shell T3/T3b): the one-time
 * Wireless debugging pairing plus the helper bootstrap, with the owner-facing
 * status and the two actions.
 */
@Composable
fun PhoneShellSection(viewModel: PhoneShellViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var notificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled =
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val unavailable = state.stage == PhoneShellStage.UNAVAILABLE
    val paired = state.stage.isPaired()

    SettingsSection {
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
                            tint = statusColor(state.stage)
                        )
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.lg))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Canal de shell",
                            style = AppTypography.bodyLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = phoneShellStatusLabel(state.stage),
                            style = AppTypography.bodySmall,
                            color = statusColor(state.stage)
                        )
                    }
                }

                Text(
                    text = "IDUPI usa la depuración inalámbrica de este teléfono " +
                        "para ejecutar comandos del sistema sin PC.",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )

                if (!unavailable) {
                    Text(
                        text = "Toca Vincular y abre Depuración inalámbrica; elige " +
                            "«Vincular dispositivo con código de vinculación» y escribe " +
                            "el código en la notificación de IDUPI.",
                        style = AppTypography.bodySmall,
                        color = TextSecondary
                    )
                }

                if (!unavailable && !notificationsEnabled) {
                    Divider(color = SlateBorder)
                    Text(
                        text = "Activa las notificaciones de IDUPI: el código de " +
                            "vinculación llega como notificación.",
                        style = AppTypography.bodySmall,
                        color = StatusError
                    )
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                        shape = AppShapes.button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryIndigo,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Abrir notificaciones", style = AppTypography.bodySmall)
                    }
                }

                if (state.message != null) {
                    Text(
                        text = state.message.orEmpty(),
                        style = AppTypography.bodySmall,
                        color = TextSecondary
                    )
                }

                Divider(color = SlateBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !unavailable && !state.busy,
                        onClick = viewModel::startPairing,
                        shape = AppShapes.button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryIndigo,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Vincular", style = AppTypography.bodySmall)
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = paired && !unavailable && !state.busy,
                        onClick = viewModel::startBridge,
                        shape = AppShapes.button,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextPrimary
                        )
                    ) {
                        Text("Iniciar puente", style = AppTypography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColor(stage: PhoneShellStage): Color = when (stage) {
    PhoneShellStage.UNAVAILABLE -> StatusError
    PhoneShellStage.CONNECTED, PhoneShellStage.BRIDGE_ACTIVE -> StatusConnected
    else -> TextSecondary
}
