package com.idupi.app.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.idupi.app.ui.components.StatusCard
import com.idupi.app.ui.theme.*
import com.idupi.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onMenuClick: () -> Unit,
    onNavigateToChat: () -> Unit,
    onNavigateToConsole: () -> Unit
) {
    val status by viewModel.status.collectAsState()
    val scrollState = rememberScrollState()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard IDUPI", style = AppTypography.appBarTitle, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "Menú", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshStatus() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Actualizar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SlateCard
                )
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
            Text(
                text = "Consola industrial activa. Supervisá el estado de los agentes autogestores y los hilos en ejecución en tiempo real.",
                style = AppTypography.bodySmall,
                color = TextSecondary
            )

            // Live status card
            status?.let {
                StatusCard(
                    connected = it.connected,
                    pcName = it.pcName,
                    project = it.project,
                    agent = it.agent,
                    busy = it.busy,
                    queueSize = it.queueSize
                )
            } ?: Box(
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

            // Quick Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                ActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Ir al Chat",
                    icon = Icons.Default.Chat,
                    color = PrimaryIndigo,
                    onClick = onNavigateToChat
                )
                ActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Ver Consola",
                    icon = Icons.Default.Terminal,
                    color = AccentPurple,
                    onClick = onNavigateToConsole
                )
            }

            // Operating AI
            Text(
                text = "IA Orquestadora Activa",
                style = AppTypography.titleMedium,
                color = TextPrimary
            )
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.card)
                    .background(SlateCard)
                    .border(1.dp, SlateBorder, AppShapes.card)
                    .padding(AppSpacing.cardPadding)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(AppShapes.chip)
                            .background(PrimaryIndigo.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Psychology, contentDescription = "Orquestador", tint = PrimaryIndigo, modifier = Modifier.size(28.dp))
                    }
                    Column {
                        Text(
                            text = status?.operatingAi ?: "Gemini 3.5 Pro",
                            style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Controlando el ruteo de agentes, parsing de herramientas y cola RPC",
                            style = AppTypography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Agents Lab & Multi-Engine Selector
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Laboratorio de Agentes",
                    style = AppTypography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Selecciona con cuál terminal/motor de IA trabajarás en Sesiones y Chat:",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                data class EngineItem(val id: String, val name: String, val subtitle: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
                
                val engineList = listOf(
                    EngineItem("pi-cli", "Pi CLI RPC Engine", "Motor RPC Pi Coding Agent", Icons.Default.SmartToy),
                    EngineItem("claude", "Claude CLI Engine", "Agente Anthropic Claude CLI", Icons.Default.Psychology),
                    EngineItem("opencode", "OpenCode Engine", "Agente Multi-Modelo OpenCode", Icons.Default.Terminal)
                )

                val activeEngineId = status?.activeEngine ?: "pi-cli"

                engineList.forEach { engine ->
                    val isActive = engine.id == activeEngineId

                    Surface(
                        onClick = { viewModel.selectEngine(engine.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.card,
                        color = if (isActive) PrimaryIndigo.copy(alpha = 0.08f) else SlateCard,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isActive) PrimaryIndigo else SlateBorder
                        )
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(AppSpacing.cardPadding)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(AppShapes.chip)
                                        .background(if (isActive) PrimaryIndigo.copy(alpha = 0.2f) else SlateBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = engine.icon,
                                        contentDescription = null,
                                        tint = if (isActive) PrimaryIndigo else TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = engine.name,
                                        style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = engine.subtitle,
                                        style = AppTypography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }

                            if (isActive) {
                                Button(
                                    onClick = { },
                                    enabled = false,
                                    colors = ButtonDefaults.buttonColors(
                                        disabledContainerColor = StatusConnected.copy(alpha = 0.15f),
                                        disabledContentColor = StatusConnected
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = AppShapes.chip
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(StatusConnected.copy(alpha = pulseAlpha))
                                        )
                                        Text(
                                            "ACTIVO",
                                            style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.selectEngine(engine.id) },
                                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryIndigo.copy(alpha = 0.5f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryIndigo),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = AppShapes.chip
                                ) {
                                    Text(
                                        "SELECCIONAR",
                                        style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.xxxl))
        }
    }
}

@Composable
private fun ActionCard(
    modifier: Modifier,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = AppShapes.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder)
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Text(title, style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
        }
    }
}
