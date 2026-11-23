package com.idupi.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.idupi.app.domain.model.DeliveryInfo
import com.idupi.app.domain.model.formatDeliverySize
import com.idupi.app.domain.model.relativeDeliveryTime
import com.idupi.app.ui.theme.*
import com.idupi.app.viewmodel.DeliveriesViewModel
import com.idupi.app.viewmodel.DeliveryInstallState

/**
 * Entregas: APKs pushed from the PC with `idupi-push <archivo.apk>`.
 *
 * The unknown-sources gate is re-checked on every resume, so coming back from
 * the system settings screen unlocks "Instalar" without reloading the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveriesScreen(
    viewModel: DeliveriesViewModel,
    onMenuClick: () -> Unit,
) {
    val deliveries by viewModel.deliveries.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val installStates by viewModel.installStates.collectAsState()
    val permissionGranted by viewModel.permissionGranted.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermission()
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openInstallSettings: () -> Unit = {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        // A stripped ROM may not ship the screen; the gate text still explains
        // what to enable, so a failure here must not crash the app.
        runCatching { context.startActivity(intent) }
        Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Entregas",
                            style = AppTypography.appBarTitle,
                            color = TextPrimary
                        )
                        Text(
                            if (deliveries.isEmpty()) {
                                "APKs desde la PC"
                            } else {
                                "${deliveries.size} APK(s) desde la PC"
                            },
                            style = AppTypography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menú", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refrescar",
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
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            if (!permissionGranted) {
                InstallPermissionCard(onOpenSettings = openInstallSettings)
            }

            when {
                isLoading && deliveries.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PrimaryIndigo)
                    }
                }

                deliveries.isEmpty() -> EmptyDeliveries()

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        items(deliveries, key = { it.id }) { delivery ->
                            DeliveryRow(
                                delivery = delivery,
                                state = installStates[delivery.id] ?: DeliveryInstallState.Idle,
                                onInstall = { viewModel.install(delivery) },
                                onDelete = { viewModel.delete(delivery) },
                                canInstall = permissionGranted
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Unknown-sources gate. Android asks once per app; until it is granted no
 * session can be committed, so this card sits above the list.
 */
@Composable
private fun InstallPermissionCard(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.card,
        color = SlateCard,
        border = BorderStroke(1.dp, StatusWorking.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(StatusWorking.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        tint = StatusWorking,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "Permití instalar apps desde IDUPI",
                    style = AppTypography.bodySmall.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                    color = TextPrimary
                )
            }
            Text(
                text = "Android necesita este permiso para instalar las APKs que llegan desde la PC.",
                style = AppTypography.labelSmall,
                color = TextSecondary
            )
            OutlinedButton(
                onClick = onOpenSettings,
                shape = AppShapes.button,
                border = BorderStroke(1.dp, StatusWorking.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusWorking)
            ) {
                Text(
                    "Abrir ajustes",
                    style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun EmptyDeliveries() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Icon(
                Icons.Default.FileDownload,
                contentDescription = null,
                tint = TextSecondary.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp)
            )
            Text(
                "No hay APKs enviadas. En la PC: idupi-push <archivo.apk>",
                style = AppTypography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = AppSpacing.lg)
            )
        }
    }
}

@Composable
private fun DeliveryRow(
    delivery: DeliveryInfo,
    state: DeliveryInstallState,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
    canInstall: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.card,
        color = SlateCard,
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(PrimaryIndigo.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        tint = PrimaryIndigo,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = delivery.name,
                        style = AppTypography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        ),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${formatDeliverySize(delivery.size)} · " +
                            relativeDeliveryTime(delivery.createdAt),
                        style = AppTypography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = delivery.sourcePath,
                        style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                        color = TextSecondary.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp),
                    enabled = state !is DeliveryInstallState.Downloading &&
                        state !is DeliveryInstallState.Installing
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Borrar",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            when (state) {
                DeliveryInstallState.Idle -> {
                    Button(
                        onClick = onInstall,
                        enabled = canInstall,
                        shape = AppShapes.button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryIndigo,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "Instalar",
                            style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                is DeliveryInstallState.Downloading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        Text(
                            "Descargando…",
                            style = AppTypography.labelSmall,
                            color = PrimaryIndigo
                        )
                        val progress = state.progress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp),
                                color = PrimaryIndigo,
                                trackColor = SlateBorder
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp),
                                color = PrimaryIndigo,
                                trackColor = SlateBorder
                            )
                        }
                    }
                }

                DeliveryInstallState.Installing -> {
                    Text(
                        "Esperando confirmación del sistema…",
                        style = AppTypography.labelSmall,
                        color = StatusWorking
                    )
                }

                DeliveryInstallState.Installed -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = StatusConnected,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "APK instalada",
                            style = AppTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = StatusConnected
                        )
                    }
                }

                is DeliveryInstallState.Failed -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = StatusError,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            state.message,
                            style = AppTypography.labelSmall,
                            color = StatusError
                        )
                    }
                }
            }
        }
    }
}
