package com.example.idupi.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.idupi.data.connection.ConnectionStorage
import com.example.idupi.ui.theme.*
import com.example.idupi.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    onMenuClick: () -> Unit,
    onNavigateToConnection: () -> Unit,
    onNavigateToWelcome: () -> Unit
) {
    val darkThemeEnabled by mainViewModel.darkThemeEnabled.collectAsState()
    val selectedWallpaper by mainViewModel.selectedWallpaper.collectAsState()
    val notificationsMuted by mainViewModel.notificationsMuted.collectAsState()

    val context = LocalContext.current
    val connectionStorage = remember { ConnectionStorage(context) }
    var savedProfile by remember { mutableStateOf(connectionStorage.getProfile()) }

    LaunchedEffect(Unit) {
        savedProfile = connectionStorage.getProfile()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes", style = AppTypography.appBarTitle, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, "Menu", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateBg)
            )
        },
        containerColor = SlateBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap)
        ) {
            ConnectionSection(
                profileName = savedProfile?.name ?: "Sin configurar",
                profileHost = savedProfile?.let { "${it.host}:${it.port}" } ?: "—",
                onNavigateToConnection = onNavigateToConnection
            )
            
            GeneralSection(
                darkThemeEnabled = darkThemeEnabled,
                onDarkThemeToggle = { mainViewModel.toggleDarkTheme(it) }
            )
            
            WallpaperSection(
                selectedWallpaper = selectedWallpaper,
                onWallpaperSelect = { mainViewModel.setWallpaper(it) }
            )
            
            NotificationSection(
                notificationsMuted = notificationsMuted,
                onMuteToggle = { mainViewModel.toggleNotificationsMuted(it) }
            )

            ActionsSection(
                onDiagnostics = {
                    Toast.makeText(context, "Conectado a: ${savedProfile?.host ?: "Ninguno"}", Toast.LENGTH_SHORT).show()
                },
                onClearCredentials = {
                    connectionStorage.clear()
                    onNavigateToWelcome()
                }
            )

            Text(
                text = "IDUPI v1.2.0",
                style = AppTypography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(AppSpacing.xxxl))
        }
    }
}

@Composable
private fun ConnectionSection(
    profileName: String,
    profileHost: String,
    onNavigateToConnection: () -> Unit
) {
    Text("CONEXIÓN AL ORQUESTADOR", style = AppTypography.titleSmall, color = TextSecondary)
    Spacer(modifier = Modifier.height(AppSpacing.sm))
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onNavigateToConnection() },
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.cardPadding).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(SlateBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Dns, contentDescription = null, tint = PrimaryIndigo)
            }
            Spacer(modifier = Modifier.width(AppSpacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text("Perfil: $profileName", style = AppTypography.bodyLarge, color = TextPrimary)
                Text("Host: $profileHost", style = AppTypography.bodySmall, color = TextSecondary)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
        }
    }
}

@Composable
private fun GeneralSection(
    darkThemeEnabled: Boolean,
    onDarkThemeToggle: (Boolean) -> Unit
) {
    Text("GENERAL", style = AppTypography.titleSmall, color = TextSecondary)
    Spacer(modifier = Modifier.height(AppSpacing.sm))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card
    ) {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Tema Oscuro", style = AppTypography.bodyLarge, color = TextPrimary)
                    Text("Modo industrial por defecto", style = AppTypography.bodySmall, color = TextSecondary)
                }
                Switch(
                    checked = darkThemeEnabled,
                    onCheckedChange = onDarkThemeToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = PrimaryIndigo, checkedTrackColor = PrimaryIndigo.copy(alpha = 0.5f))
                )
            }
        }
    }
}

@Composable
private fun WallpaperSection(
    selectedWallpaper: String,
    onWallpaperSelect: (String) -> Unit
) {
    Text("FONDO DEL CHAT", style = AppTypography.titleSmall, color = TextSecondary)
    Spacer(modifier = Modifier.height(AppSpacing.sm))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val wallpapers = listOf(
            "Telegram" to IDUPITheme.colors.wallpaperTelegramStart,
            "Matrix" to IDUPITheme.colors.matrixGreen.copy(alpha = 0.2f),
            "Gradient" to IDUPITheme.colors.gradientIndigo,
            "Slate" to SlateBg
        )
        wallpapers.forEach { (name, color) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onWallpaperSelect(name) }
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(AppShapes.card)
                        .background(color)
                        .border(
                            width = if (selectedWallpaper == name) 2.dp else 1.dp,
                            color = if (selectedWallpaper == name) PrimaryIndigo else SlateBorder,
                            shape = AppShapes.card
                        )
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = name,
                    style = AppTypography.labelSmall,
                    color = if (selectedWallpaper == name) PrimaryIndigo else TextSecondary
                )
            }
        }
    }
}

@Composable
private fun NotificationSection(
    notificationsMuted: Boolean,
    onMuteToggle: (Boolean) -> Unit
) {
    Text("NOTIFICACIONES", style = AppTypography.titleSmall, color = TextSecondary)
    Spacer(modifier = Modifier.height(AppSpacing.sm))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.cardPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Silenciar Todo", style = AppTypography.bodyLarge, color = TextPrimary)
                Text("Pausa todas las notificaciones push", style = AppTypography.bodySmall, color = TextSecondary)
            }
            Switch(
                checked = notificationsMuted,
                onCheckedChange = onMuteToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = PrimaryIndigo, checkedTrackColor = PrimaryIndigo.copy(alpha = 0.5f))
            )
        }
    }
}

@Composable
private fun ActionsSection(
    onDiagnostics: () -> Unit,
    onClearCredentials: () -> Unit
) {
    Text("ACCIONES Y SEGURIDAD", style = AppTypography.titleSmall, color = TextSecondary)
    Spacer(modifier = Modifier.height(AppSpacing.sm))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card
    ) {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
            Text(
                text = "Ejecutar Autodiagnóstico",
                style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = PrimaryIndigo,
                modifier = Modifier.fillMaxWidth().clickable { onDiagnostics() }.padding(vertical = AppSpacing.sm)
            )
            Divider(color = SlateBorder)
            Text(
                text = "Borrar Credenciales y Salir",
                style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = StatusError,
                modifier = Modifier.fillMaxWidth().clickable { onClearCredentials() }.padding(vertical = AppSpacing.sm)
            )
        }
    }
}
