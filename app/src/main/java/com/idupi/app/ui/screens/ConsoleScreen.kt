package com.idupi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idupi.app.domain.repository.TerminalSessionItem
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.AccentPurple
import com.idupi.app.ui.theme.IDUPITheme
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBg
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.StatusConnected
import com.idupi.app.ui.theme.StatusError
import com.idupi.app.ui.theme.StatusTerminal
import com.idupi.app.ui.theme.StatusWorking
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary
import com.idupi.app.viewmodel.ConsoleViewModel

// Type filter tabs
private enum class TerminalFilter(val label: String, val icon: ImageVector) {
    ALL("Todos", Icons.Default.Widgets),
    AGENT("Agentes", Icons.Default.SmartToy),
    SERVER("Servidores", Icons.Default.Dns),
    SHELL("Shells", Icons.Default.Terminal)
}

/**
 * "Procesos": the log-stream processes (agents, servers, shells) the PC server
 * exposes. The process list is the main content; the live log and command
 * input live behind the log toggle in the app bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    viewModel: ConsoleViewModel,
    onMenuClick: () -> Unit
) {
    val logs by viewModel.logs.collectAsState()
    val terminals by viewModel.terminals.collectAsState()
    val selectedTerminalId by viewModel.selectedTerminalId.collectAsState()
    val isExecuting by viewModel.isExecuting.collectAsState()

    var commandInput by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    // Type filter
    var activeFilter by remember { mutableStateOf(TerminalFilter.ALL) }

    // Auto-refresh and live polling
    DisposableEffect(Unit) {
        viewModel.refreshTerminals()
        viewModel.startPolling()
        onDispose {
            viewModel.stopPolling()
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // Derive the selected terminal object for display
    val selectedTerminal = terminals.find { it.id == selectedTerminalId }

    // Filtered terminals
    val filteredTerminals = remember(terminals, activeFilter) {
        when (activeFilter) {
            TerminalFilter.ALL -> terminals
            TerminalFilter.AGENT -> terminals.filter { it.type == "agent" }
            TerminalFilter.SERVER -> terminals.filter { it.type == "server" }
            TerminalFilter.SHELL -> terminals.filter { it.type == "shell" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            "Procesos",
                            style = AppTypography.appBarTitle,
                            color = TextPrimary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selectedTerminal?.status == "running")
                                            StatusConnected else StatusError
                                    )
                            )
                            Text(
                                text = if (showLogs) {
                                    selectedTerminal?.name ?: "Sin selección"
                                } else {
                                    "${terminals.size} en tu PC"
                                },
                                style = AppTypography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
                    // Spawns a log-stream shell (not an interactive PTY: those
                    // live in their own screen).
                    IconButton(onClick = { viewModel.spawnNewTerminal() }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Nueva shell",
                            tint = TextPrimary
                        )
                    }
                    IconButton(onClick = { viewModel.refreshTerminals() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refrescar",
                            tint = TextPrimary
                        )
                    }
                    IconButton(onClick = { showLogs = !showLogs }) {
                        Icon(
                            imageVector = if (showLogs) Icons.Default.ViewList else Icons.Default.Subject,
                            contentDescription = if (showLogs) "Ver procesos" else "Ver logs",
                            tint = if (showLogs) PrimaryIndigo else TextPrimary
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
            if (showLogs) {
                if (selectedTerminal != null) {
                    SelectedProcessCard(
                        terminal = selectedTerminal,
                        onRestart = { viewModel.restartTerminal(selectedTerminal.id) },
                        onStop = { viewModel.cancelTask() }
                    )
                }

                // Console output area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(AppShapes.card)
                        .background(IDUPITheme.colors.terminalBg)
                        .border(1.dp, SlateBorder, AppShapes.card)
                ) {
                    if (logs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                            ) {
                                Icon(
                                    Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = StatusTerminal.copy(alpha = 0.3f),
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    "Sin salidas recientes",
                                    style = AppTypography.codeMono,
                                    color = StatusTerminal.copy(alpha = 0.4f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(AppSpacing.sm),
                            contentPadding = PaddingValues(bottom = AppSpacing.sm)
                        ) {
                            items(logs) { log ->
                                val logColor = when {
                                    log.contains("[ERROR]") -> StatusError
                                    log.contains("[WARNING]") -> StatusWorking
                                    log.contains("[System]") -> PrimaryIndigo
                                    log.contains("[TOOL]") -> AccentPurple
                                    log.startsWith("\$") -> Color(0xFF38BDF8)
                                    else -> StatusTerminal
                                }
                                Text(
                                    text = log,
                                    style = AppTypography.codeMono.copy(fontSize = 11.sp, lineHeight = 16.sp),
                                    color = logColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 1.dp, horizontal = AppSpacing.xs)
                                )
                            }
                        }
                    }
                }

                // Command input bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = AppShapes.input,
                        color = IDUPITheme.colors.terminalBg,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (commandInput.isNotBlank()) PrimaryIndigo.copy(alpha = 0.5f) else SlateBorder
                        )
                    ) {
                        TextField(
                            value = commandInput,
                            onValueChange = { commandInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                focusedTextColor = StatusTerminal,
                                unfocusedTextColor = StatusTerminal,
                                cursorColor = StatusTerminal
                            ),
                            placeholder = {
                                Text(
                                    "Escribir comando...",
                                    style = AppTypography.codeMono.copy(fontSize = 12.sp),
                                    color = StatusTerminal.copy(alpha = 0.4f)
                                )
                            },
                            textStyle = AppTypography.codeMono.copy(fontSize = 13.sp),
                            leadingIcon = {
                                Text(
                                    "$",
                                    style = AppTypography.codeMono.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    ),
                                    color = PrimaryIndigo
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (commandInput.isNotBlank()) {
                                    viewModel.sendTerminalCommand(commandInput)
                                    commandInput = ""
                                }
                            })
                        )
                    }

                    FilledIconButton(
                        onClick = {
                            if (commandInput.isNotBlank()) {
                                viewModel.sendTerminalCommand(commandInput)
                                commandInput = ""
                            }
                        },
                        enabled = commandInput.isNotBlank() && !isExecuting,
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (commandInput.isNotBlank() && !isExecuting) PrimaryIndigo else SlateCard,
                            contentColor = if (commandInput.isNotBlank() && !isExecuting) Color.White else TextSecondary
                        )
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Enviar",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Bottom action buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.clearLogs() },
                        modifier = Modifier.weight(1f),
                        shape = AppShapes.button,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Limpiar", style = AppTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                    }

                    OutlinedButton(
                        onClick = { showLogs = false },
                        modifier = Modifier.weight(1f),
                        shape = AppShapes.button,
                        border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryIndigo.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryIndigo),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.ViewList, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Procesos", style = AppTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            } else {
                // Filter tabs
                ScrollableTabRow(
                    selectedTabIndex = TerminalFilter.values().indexOf(activeFilter),
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = Color.Transparent,
                    contentColor = PrimaryIndigo,
                    edgePadding = AppSpacing.sm,
                    divider = {
                        HorizontalDivider(color = SlateBorder, thickness = 0.5.dp)
                    }
                ) {
                    TerminalFilter.values().forEach { filter ->
                        val count = when (filter) {
                            TerminalFilter.ALL -> terminals.size
                            TerminalFilter.AGENT -> terminals.count { it.type == "agent" }
                            TerminalFilter.SERVER -> terminals.count { it.type == "server" }
                            TerminalFilter.SHELL -> terminals.count { it.type == "shell" }
                        }
                        Tab(
                            selected = activeFilter == filter,
                            onClick = { activeFilter = filter },
                            modifier = Modifier.height(44.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    filter.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (activeFilter == filter) PrimaryIndigo else TextSecondary
                                )
                                Text(
                                    "${filter.label} ($count)",
                                    style = AppTypography.labelSmall.copy(
                                        fontWeight = if (activeFilter == filter) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp
                                    ),
                                    color = if (activeFilter == filter) PrimaryIndigo else TextSecondary
                                )
                            }
                        }
                    }
                }

                if (filteredTerminals.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                        ) {
                            Icon(
                                Icons.Default.SearchOff,
                                contentDescription = null,
                                tint = TextSecondary.copy(alpha = 0.4f),
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                "Sin procesos de tipo\n\"${activeFilter.label}\"",
                                style = AppTypography.labelSmall,
                                color = TextSecondary.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(vertical = AppSpacing.xs),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                    ) {
                        items(filteredTerminals, key = { it.id }) { term ->
                            TerminalListItem(
                                terminal = term,
                                isSelected = term.id == selectedTerminalId,
                                onClick = {
                                    viewModel.selectTerminal(term.id)
                                    showLogs = true
                                },
                                onRestart = { viewModel.restartTerminal(term.id) },
                                onStop = { viewModel.cancelTask() }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact header of the terminal whose log is on screen, with its two actions.
 */
@Composable
private fun SelectedProcessCard(
    terminal: TerminalSessionItem,
    onRestart: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.card,
        color = SlateCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = terminalIcon(terminal.type),
                    contentDescription = null,
                    tint = PrimaryIndigo,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = terminal.name,
                        style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "PID: ${terminal.pid} · ${terminal.type}",
                        style = AppTypography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(
                    onClick = onRestart,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.RestartAlt,
                        contentDescription = "Reiniciar",
                        tint = StatusWorking,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Detener",
                        tint = StatusError,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Single vertical process item card for the list.
 */
@Composable
private fun TerminalListItem(
    terminal: TerminalSessionItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit
) {
    val highlightBorder = if (isSelected) PrimaryIndigo else SlateBorder
    val highlightBg = if (isSelected) PrimaryIndigo.copy(alpha = 0.08f) else Color.Transparent

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.card,
        color = highlightBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, highlightBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            // Type icon with status dot
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) PrimaryIndigo.copy(alpha = 0.15f)
                            else SlateCard
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = terminalIcon(terminal.type),
                        contentDescription = null,
                        tint = if (isSelected) PrimaryIndigo else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(SlateBg)
                        .padding(1.dp)
                        .clip(CircleShape)
                        .background(
                            if (terminal.status == "running") StatusConnected else StatusError
                        )
                )
            }

            // Name + metadata
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = terminal.name,
                    style = AppTypography.bodySmall.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    color = if (isSelected) TextPrimary else TextPrimary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Type badge
                    Surface(
                        shape = AppShapes.small,
                        color = typeBadgeColor(terminal.type).copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = terminal.type.replaceFirstChar { it.uppercase() },
                            style = AppTypography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                            color = typeBadgeColor(terminal.type),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = "PID ${terminal.pid}",
                        style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                        color = TextSecondary
                    )
                    if (terminal.logCount > 0) {
                        Text(
                            text = "· ${terminal.logCount} logs",
                            style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                            color = TextSecondary
                        )
                    }
                }
            }

            // Per-process actions
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(
                    onClick = onRestart,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.RestartAlt,
                        contentDescription = "Reiniciar",
                        tint = StatusWorking,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Detener",
                        tint = StatusError,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Selection indicator
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = PrimaryIndigo,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Returns the correct icon for the terminal type.
 */
@Composable
private fun terminalIcon(type: String): ImageVector {
    return when (type) {
        "agent" -> Icons.Default.SmartToy
        "server" -> Icons.Default.Dns
        else -> Icons.Default.Terminal
    }
}

/**
 * Returns a badge color for the terminal type.
 */
@Composable
private fun typeBadgeColor(type: String): Color {
    return when (type) {
        "agent" -> AccentPurple
        "server" -> StatusWorking
        else -> StatusTerminal
    }
}
