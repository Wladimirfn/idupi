package com.example.idupi.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.idupi.domain.repository.TerminalSessionItem
import com.example.idupi.ui.theme.*
import com.example.idupi.viewmodel.ConsoleViewModel

// Type filter tabs
private enum class TerminalFilter(val label: String, val icon: ImageVector) {
    ALL("Todos", Icons.Default.Widgets),
    AGENT("Agentes", Icons.Default.SmartToy),
    SERVER("Servidores", Icons.Default.Dns),
    SHELL("Shells", Icons.Default.Terminal)
}

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
    val activeTask by viewModel.activeTask.collectAsState()

    var commandInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Right-side drawer visibility
    var showTerminalDrawer by remember { mutableStateOf(false) }

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

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column(modifier = Modifier.padding(end = 8.dp)) {
                            Text(
                                "Terminal CLI",
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
                                    text = selectedTerminal?.name ?: "Sin selección",
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
                        // Terminal count badge
                        BadgedBox(
                            badge = {
                                if (terminals.isNotEmpty()) {
                                    Badge(
                                        containerColor = PrimaryIndigo,
                                        contentColor = Color.White
                                    ) {
                                        Text(
                                            terminals.size.toString(),
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        ) {
                            IconButton(onClick = { showTerminalDrawer = !showTerminalDrawer }) {
                                Icon(
                                    imageVector = Icons.Default.ViewList,
                                    contentDescription = "Ver Procesos",
                                    tint = if (showTerminalDrawer) PrimaryIndigo else TextPrimary
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.refreshTerminals() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
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
                // Selected terminal info card (compact)
                selectedTerminal?.let { term ->
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
                                    imageVector = terminalIcon(term.type),
                                    contentDescription = null,
                                    tint = PrimaryIndigo,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = term.name,
                                        style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "PID: ${term.pid} · ${term.type}",
                                        style = AppTypography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                            // Quick actions for selected terminal
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(
                                    onClick = { viewModel.restartServer() },
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
                                    onClick = { viewModel.cancelTask() },
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

                // Console output area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(AppShapes.card)
                        .background(IDUPITheme.colors.matrixBg)
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
                        color = IDUPITheme.colors.matrixBg,
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

                    Button(
                        onClick = { viewModel.spawnNewTerminal() },
                        modifier = Modifier.weight(1f),
                        shape = AppShapes.button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryIndigo,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Nueva Shell", style = AppTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                    }

                    OutlinedButton(
                        onClick = { showTerminalDrawer = true },
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
            }
        }

        // Right-side terminal/process drawer overlay
        AnimatedVisibility(
            visible = showTerminalDrawer,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it })
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Scrim — tap to close
                Box(
                    modifier = Modifier
                        .weight(0.15f)
                        .fillMaxHeight()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { showTerminalDrawer = false }
                )

                // Drawer content
                Surface(
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight(),
                    color = SlateCard,
                    shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                    shadowElevation = 16.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Drawer header
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            IDUPITheme.colors.gradientIndigo.copy(alpha = 0.3f),
                                            IDUPITheme.colors.gradientPurple.copy(alpha = 0.15f)
                                        )
                                    )
                                )
                                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        "Procesos de tu PC",
                                        style = AppTypography.bodySmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        ),
                                        color = TextPrimary
                                    )
                                    Text(
                                        "${terminals.size} detectados",
                                        style = AppTypography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    IconButton(
                                        onClick = { viewModel.refreshTerminals() },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Refrescar",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { showTerminalDrawer = false },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Cerrar",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

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

                        // Vertical terminal list
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
                                contentPadding = PaddingValues(
                                    horizontal = AppSpacing.sm,
                                    vertical = AppSpacing.sm
                                ),
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                            ) {
                                items(filteredTerminals, key = { it.id }) { term ->
                                    TerminalListItem(
                                        terminal = term,
                                        isSelected = term.id == selectedTerminalId,
                                        onClick = {
                                            viewModel.selectTerminal(term.id)
                                            showTerminalDrawer = false
                                        }
                                    )
                                }
                            }
                        }

                        // Drawer footer — new shell button
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = SlateBg,
                            border = androidx.compose.foundation.BorderStroke(
                                width = 0.5.dp,
                                color = SlateBorder
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(AppSpacing.sm),
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                            ) {
                                Button(
                                    onClick = {
                                        viewModel.spawnNewTerminal()
                                        showTerminalDrawer = false
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = AppShapes.button,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryIndigo,
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(vertical = 10.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Abrir Nueva Shell",
                                        style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single vertical terminal item card for the drawer list.
 */
@Composable
private fun TerminalListItem(
    terminal: TerminalSessionItem,
    isSelected: Boolean,
    onClick: () -> Unit
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
