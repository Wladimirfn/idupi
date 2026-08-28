package com.idupi.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.idupi.app.domain.model.ActivityStatus
import com.idupi.app.domain.model.liveActivities
import com.idupi.app.domain.model.orderedSubagents
import com.idupi.app.domain.model.ActivityUiState
import com.idupi.app.domain.model.ChatMessage
import com.idupi.app.domain.model.MessageSender
import com.idupi.app.domain.model.QuickCommand
import com.idupi.app.domain.model.chatTitleFor
import com.idupi.app.domain.model.shouldAnimateScroll
import com.idupi.app.domain.model.copyTextOf
import com.idupi.app.domain.model.toggled
import com.idupi.app.ui.components.MarkdownText
import com.idupi.app.ui.theme.*
import com.idupi.app.viewmodel.ChatViewModel
import com.idupi.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    mainViewModel: MainViewModel,
    onMenuClick: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val activeTool by viewModel.activeTool.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val activeUiRequest by viewModel.activeUiRequest.collectAsState()
    val availableCommands by viewModel.availableCommands.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val serverStatus by mainViewModel.status.collectAsState()
    val selectedWallpaper by mainViewModel.selectedWallpaper.collectAsState()

    val activities by viewModel.activities.collectAsState()
    val subagents by viewModel.subagents.collectAsState()
    val selectedSubagent by viewModel.selectedSubagent.collectAsState()

    var showCommandsSheet by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }

    // Long-pressing a message starts selection; tapping then toggles. An empty
    // set IS "not selecting", so there is no second flag that can disagree with
    // what is actually selected.
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    val selectionMode = selectedIds.isNotEmpty()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        viewModel.refreshModels()
    }

    // The opening greeting introduced itself as Pi whatever engine was active.
    // It is rewritten as soon as the engine is known, and only while the chat
    // is still untouched (see ChatViewModel.applyActiveEngine).
    LaunchedEffect(serverStatus?.activeEngine) {
        viewModel.applyActiveEngine(serverStatus?.activeEngine)
    }

    val matrixBgColor = IDUPITheme.colors.matrixBg
    val matrixGreenColor = IDUPITheme.colors.matrixGreen
    val teleStartColor = IDUPITheme.colors.wallpaperTelegramStart
    val teleEndColor = IDUPITheme.colors.wallpaperTelegramEnd
    val gradStartColor = IDUPITheme.colors.gradientIndigo
    val gradEndColor = IDUPITheme.colors.gradientPurple
    val slateBgColor = SlateBg

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                  if (selectionMode) {
                    Text(
                        text = if (selectedIds.size == 1) "1 mensaje seleccionado"
                               else "${selectedIds.size} mensajes seleccionados",
                        style = AppTypography.appBarTitle,
                        color = TextPrimary
                    )
                  } else {
                    Column {
                        // The header used to say "Chat con Pi" whatever engine was
                        // answering, so a Claude or OpenCode session was labelled Pi.
                        Text(
                            text = chatTitleFor(serverStatus?.activeEngine),
                            style = AppTypography.appBarTitle,
                            color = TextPrimary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                        ) {
                            val isConnected = serverStatus?.connected == true
                            val statusColor = if (isConnected) StatusConnected else StatusError
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = if (isConnected) "Orquestador listo" else "Desconectado",
                                style = AppTypography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                  }
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancelar selección", tint = TextPrimary)
                        }
                    } else {
                        IconButton(onClick = onMenuClick) {
                            Icon(Icons.Default.Menu, contentDescription = "Menú", tint = TextPrimary)
                        }
                    }
                },
                actions = {
                  if (selectionMode) {
                    IconButton(onClick = { selectedIds = messages.map { it.id }.toSet() }) {
                        Icon(Icons.Default.SelectAll, contentDescription = "Seleccionar todo", tint = TextSecondary)
                    }
                    IconButton(onClick = {
                        // copyTextOf reads the chat in screen order, so what is
                        // pasted matches what was read regardless of tap order.
                        val text = copyTextOf(messages, selectedIds)
                        if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
                        selectedIds = emptySet()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", tint = PrimaryIndigo)
                    }
                  } else {
                    IconButton(onClick = {
                        viewModel.refreshModels()
                        showModelDialog = true
                    }) {
                        Icon(Icons.Default.Tune, contentDescription = "Cambiar Modelo IA", tint = PrimaryIndigo)
                    }
                    IconButton(onClick = { viewModel.clearMessages() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Limpiar Chat", tint = TextSecondary)
                    }
                    if (isStreaming || activeTool != null) {
                        IconButton(onClick = { viewModel.cancelTask() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Detener", tint = StatusError)
                        }
                    }
                  }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateCard)
            )
        },
        containerColor = SlateBg
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Wallpaper Background
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                when (selectedWallpaper) {
                    "Telegram" -> {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(teleStartColor, teleEndColor)
                            )
                        )
                        for (i in 0..100) {
                            drawCircle(
                                color = Color.White.copy(alpha = 0.03f),
                                radius = 2.dp.toPx(),
                                center = Offset(
                                    x = (Math.random() * canvasWidth).toFloat(),
                                    y = (Math.random() * canvasHeight).toFloat()
                                )
                            )
                        }
                    }
                    "Matrix" -> {
                        drawRect(color = matrixBgColor)
                        for (i in 0..50) {
                            drawCircle(
                                color = matrixGreenColor.copy(alpha = 0.2f),
                                radius = 1.dp.toPx(),
                                center = Offset(
                                    x = (Math.random() * canvasWidth).toFloat(),
                                    y = (Math.random() * canvasHeight).toFloat()
                                )
                            )
                        }
                    }
                    "Gradient" -> {
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(gradStartColor, gradEndColor)
                            )
                        )
                    }
                    "Slate" -> {
                        drawRect(color = slateBgColor)
                    }
                }
            }

            val listState = rememberLazyListState()

            // Keyed on the last message's id as well as the size: switching to a
            // session that happens to have the same number of messages is a
            // different list, and keying on size alone never noticed.
            var previousCount by remember { mutableStateOf(0) }
            LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
                if (messages.isNotEmpty()) {
                    val target = messages.size - 1
                    // Animating a whole history means travelling through every
                    // message in it; see shouldAnimateScroll.
                    if (shouldAnimateScroll(previousCount, messages.size)) {
                        listState.animateScrollToItem(target)
                    } else {
                        listState.scrollToItem(target)
                    }
                }
                previousCount = messages.size
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // The model used to share one cramped line with the connection
                // status inside the app bar, where a full "provider/model" never
                // fit and was always cut to "openai-codex/gpt...". Its own
                // full-width row below the bar shows the whole name.
                val activeModelName = serverStatus?.operatingAi.orEmpty()
                val activeProviderName = serverStatus?.operatingProvider
                val modelText = when {
                    activeModelName.isBlank() -> "Modelo no informado"
                    !activeProviderName.isNullOrBlank() -> "$activeProviderName/$activeModelName"
                    else -> activeModelName
                }
                Surface(
                    onClick = {
                        viewModel.refreshModels()
                        showModelDialog = true
                    },
                    color = PrimaryIndigo.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                    ) {
                        Icon(
                            Icons.Default.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = PrimaryIndigo
                        )
                        Text(
                            text = modelText,
                            style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = PrimaryIndigo,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = AppSpacing.lg),
                    reverseLayout = false,
                    contentPadding = PaddingValues(vertical = AppSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatBubble(
                            message = msg,
                            selected = msg.id in selectedIds,
                            selectionMode = selectionMode,
                            onLongPress = { selectedIds = selectedIds.toggled(msg.id) },
                            onSelectTap = { selectedIds = selectedIds.toggled(msg.id) },
                            onSubagentClick = { name, id ->
                                val found = subagents.find { it.id == id || it.name == name }
                                if (found != null) {
                                    viewModel.selectSubagent(found)
                                } else {
                                    viewModel.selectSubagent(
                                        com.idupi.app.domain.model.SubagentLiveState(
                                            id = id ?: name,
                                            name = name,
                                            task = msg.text,
                                            output = msg.text,
                                            isRunning = msg.toolOk == null
                                        )
                                    )
                                }
                            }
                        )
                    }
                }

                if (isThinking) {
                    ThinkingIndicator()
                }

                if (activeTool != null) {
                    ActiveToolOverlay(toolName = activeTool!!)
                }

                val runningActivities = liveActivities(activities)
                if (runningActivities.isNotEmpty()) {
                    LiveActivityBar(activities = runningActivities)
                }

                if (subagents.isNotEmpty()) {
                    ActiveSubagentsBar(
                        subagents = subagents,
                        onSelectSubagent = { viewModel.selectSubagent(it) }
                    )
                }

                ChatInputBar(
                    inputText = inputText,
                    onTextChange = { viewModel.updateInputText(it) },
                    onSend = { viewModel.sendMessage() },
                    onShowCommands = { showCommandsSheet = true },
                    isStreaming = isStreaming
                )
            }

            // Quick Commands Modal Sheet
            if (showCommandsSheet) {
                QuickCommandsBottomSheet(
                    commands = availableCommands,
                    onCommandSelect = { cmd ->
                        viewModel.updateInputText(cmd)
                        showCommandsSheet = false
                    },
                    onDismiss = { showCommandsSheet = false }
                )
            }

            // Subagent Live Output Console Modal Sheet
            selectedSubagent?.let { agent ->
                SubagentLiveConsoleBottomSheet(
                    subagent = agent,
                    onDismiss = { viewModel.dismissSubagentConsole() }
                )
            }

            // AI Model Selection Dialog (Con Buscador en Tiempo Real y Selección Única por Par Modelo+Proveedor)
            if (showModelDialog) {
                ModelSelectionDialog(
                    currentModel = serverStatus?.operatingAi ?: "gpt-5.6-luna",
                    currentProvider = serverStatus?.operatingProvider,
                    availableModels = availableModels,
                    onModelSelect = { model, provider ->
                        // Without refreshing the status the header keeps showing
                        // the previous model even though the new one is answering.
                        viewModel.switchModel(model, provider) { mainViewModel.refreshStatus() }
                        showModelDialog = false
                    },
                    onDismiss = { showModelDialog = false }
                )
            }
        }
    }
}

@Composable
fun ModelSelectionDialog(
    currentModel: String,
    currentProvider: String?,
    availableModels: List<com.idupi.app.domain.repository.AiModelItem>,
    onModelSelect: (String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredModels = remember(searchQuery, availableModels) {
        if (searchQuery.isBlank()) {
            availableModels
        } else {
            val query = searchQuery.trim().lowercase()
            availableModels.filter { model ->
                model.id.lowercase().contains(query) ||
                model.name.lowercase().contains(query) ||
                model.provider.lowercase().contains(query)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.SmartToy, contentDescription = null, tint = PrimaryIndigo)
                Text("Modelos Detectados en tu PC", style = AppTypography.titleMedium, color = TextPrimary)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Text(
                    text = "Busca y selecciona cualquier modelo o proveedor detectado en tu Pi CLI (${availableModels.size} disponibles):",
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
                
                // Buscador en tiempo real por Nombre o Proveedor
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar por nombre o proveedor...", style = AppTypography.bodySmall, color = TextSecondary) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar", tint = PrimaryIndigo) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpiar", tint = TextSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryIndigo,
                        unfocusedBorderColor = SlateBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                if (filteredModels.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppSpacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                    ) {
                        Text(
                            text = "No se encontraron modelos con \"$searchQuery\"",
                            style = AppTypography.bodySmall,
                            color = TextSecondary
                        )
                        if (searchQuery.isNotBlank()) {
                            Button(
                                onClick = { onModelSelect(searchQuery.trim(), null) },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                                shape = AppShapes.small
                            ) {
                                Text("Usar \"${searchQuery.trim()}\" como personalizado", style = AppTypography.labelSmall, color = TextPrimary)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                        modifier = Modifier.heightIn(max = 280.dp)
                    ) {
                        items(filteredModels) { item ->
                            // Selección única comparando tanto el ID del modelo como su Proveedor exacto
                            val isSelected = currentModel.equals(item.id, ignoreCase = true) &&
                                    (currentProvider.isNullOrBlank() || currentProvider.equals(item.provider, ignoreCase = true))

                            Surface(
                                onClick = { onModelSelect(item.id, item.provider) },
                                shape = AppShapes.card,
                                color = if (isSelected) PrimaryIndigo.copy(alpha = 0.2f) else SlateBg,
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) PrimaryIndigo else SlateBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.id,
                                            style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Proveedor: ${item.provider} • ${item.name}",
                                            style = AppTypography.labelSmall,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Seleccionado",
                                            tint = PrimaryIndigo,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", style = AppTypography.labelSmall, color = TextSecondary)
            }
        },
        containerColor = SlateCard
    )
}

/**
 * @param selectionMode true while at least one message is selected. A tap then
 *   toggles selection instead of doing what it normally does -- opening a
 *   subagent console mid-selection would be a surprise, not a shortcut.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    message: ChatMessage,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onSelectTap: () -> Unit = {},
    onSubagentClick: (name: String, id: String?) -> Unit = { _, _ -> }
) {
    val selectionModifier = Modifier
        .fillMaxWidth()
        .background(if (selected) PrimaryIndigo.copy(alpha = 0.22f) else Color.Transparent)
        .combinedClickable(
            onClick = { if (selectionMode) onSelectTap() },
            onLongClick = onLongPress
        )

    Box(modifier = selectionModifier) {
        ChatBubbleContent(
            message = message,
            selectionMode = selectionMode,
            onSubagentClick = onSubagentClick
        )
        // A tint BEHIND the row only shows around a narrow bubble. An assistant
        // reply is full-width with an opaque background, so it covered the tint
        // completely and read as not selected. Drawn over the row instead, every
        // bubble looks selected the same way whatever its width or colour.
        if (selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(PrimaryIndigo.copy(alpha = 0.28f))
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(2.dp, PrimaryIndigo, AppShapes.card)
            )
        }
    }
}

@Composable
private fun ChatBubbleContent(
    message: ChatMessage,
    selectionMode: Boolean = false,
    onSubagentClick: (name: String, id: String?) -> Unit = { _, _ -> }
) {
    val isUser = message.sender == MessageSender.USER
    val isActivity = message.sender == MessageSender.TOOL || message.sender == MessageSender.SUBAGENT
    val isSystem = message.sender == MessageSender.SYSTEM || message.sender == MessageSender.ERROR

    when {
        // A tool/subagent card carries its own clickable Surface. Left enabled it
        // would swallow the long press that starts a selection, so during
        // selection it stops taking clicks and the gesture reaches the wrapper.
        isActivity -> ActivityBubble(
            message = message,
            interactive = !selectionMode,
            onSubagentClick = onSubagentClick
        )
        isSystem -> SystemBubble(message)
        else -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                Surface(
                    color = if (isUser) PrimaryIndigo else SlateCard,
                    shape = if (isUser) {
                        AppShapes.card.copy(topEnd = androidx.compose.foundation.shape.CornerSize(2.dp))
                    } else {
                        AppShapes.card.copy(topStart = androidx.compose.foundation.shape.CornerSize(2.dp))
                    },
                    border = if (!isUser) androidx.compose.foundation.BorderStroke(1.dp, SlateBorder) else null,
                    modifier = if (isUser) Modifier.widthIn(max = 280.dp) else Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.md)) {
                        if (isUser) {
                            Text(
                                text = message.text,
                                style = AppTypography.chatBody,
                                color = TextPrimary
                            )
                        } else {
                            // Assistant replies are rendered like a CLI transcript: fenced
                            // code, tables, lists and headings all get real structure
                            // instead of one flat Text of raw Markdown source.
                            MarkdownText(markdown = message.text, textColor = TextPrimary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A running/completed tool or subagent invocation, rendered as a compact
 * card distinct from a plain chat bubble -- and subagents are visually
 * distinct from ordinary tools (different accent color and label) so
 * delegation is obvious at a glance.
 */
@Composable
fun ActivityBubble(
    message: ChatMessage,
    interactive: Boolean = true,
    onSubagentClick: (name: String, id: String?) -> Unit = { _, _ -> }
) {
    val isSubagent = message.sender == MessageSender.SUBAGENT
    val accentColor = if (isSubagent) AccentPurple else PrimaryIndigo
    val label = if (isSubagent) "SUBAGENTE · ${message.toolName ?: ""}" else "HERRAMIENTA · ${message.toolName ?: ""}"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.xxs),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(
            onClick = {
                if (isSubagent && message.toolName != null) {
                    onSubagentClick(message.toolName, message.toolId)
                }
            },
            enabled = interactive,
            color = accentColor.copy(alpha = 0.1f),
            shape = AppShapes.card,
            border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                ToolStatusIcon(ok = message.toolOk, accentColor = accentColor)
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = label, style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold), color = accentColor)
                        if (isSubagent) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "Ver en vivo", tint = accentColor, modifier = Modifier.size(12.dp))
                        }
                    }
                    if (message.text.isNotBlank()) {
                        Text(
                            text = message.text,
                            style = AppTypography.bodySmall,
                            color = TextSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolStatusIcon(ok: Boolean?, accentColor: Color) {
    when (ok) {
        null -> CircularProgressIndicator(modifier = Modifier.size(14.dp), color = accentColor, strokeWidth = 2.dp)
        true -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = "Completado",
            tint = StatusConnected,
            modifier = Modifier.size(16.dp)
        )
        false -> Icon(
            Icons.Default.Error,
            contentDescription = "Falló",
            tint = StatusError,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** A SYSTEM/ERROR notice: a small centered pill, not part of the conversation flow. */
@Composable
fun SystemBubble(message: ChatMessage) {
    val isError = message.sender == MessageSender.ERROR
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = if (isError) StatusError.copy(alpha = 0.15f) else SlateCard,
            shape = AppShapes.card,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isError) StatusError.copy(alpha = 0.4f) else SlateBorder
            )
        ) {
            Text(
                text = message.text,
                style = AppTypography.labelSmall,
                color = if (isError) StatusError else TextSecondary,
                modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
            )
        }
    }
}

/** Visible "pensando…" indicator, shown while a `thinking` event is active. */
@Composable
fun ThinkingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = TextSecondary, strokeWidth = 2.dp)
        Text(text = "Pensando…", style = AppTypography.labelSmall, color = TextSecondary)
    }
}

@Composable
fun ActiveToolOverlay(toolName: String) {
    Surface(
        color = PrimaryIndigo.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryIndigo.copy(alpha = 0.3f)),
        shape = AppShapes.card,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs)
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = PrimaryIndigo, strokeWidth = 2.dp)
            Text(text = "Ejecutando herramienta: $toolName...", style = AppTypography.labelSmall, color = PrimaryIndigo)
        }
    }
}

@Composable
fun ChatInputBar(
    inputText: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onShowCommands: () -> Unit,
    isStreaming: Boolean
) {
    Surface(
        color = SlateCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            IconButton(onClick = onShowCommands) {
                Icon(Icons.Default.Terminal, contentDescription = "Comandos Rápidos", tint = PrimaryIndigo)
            }
            
            OutlinedTextField(
                value = inputText,
                onValueChange = onTextChange,
                placeholder = { Text("Escribe un mensaje o comando...", style = AppTypography.chatBody, color = TextSecondary) },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryIndigo,
                    unfocusedBorderColor = SlateBorder
                )
            )

            IconButton(
                onClick = onSend,
                enabled = inputText.isNotBlank() && !isStreaming
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Enviar",
                    tint = if (inputText.isNotBlank() && !isStreaming) PrimaryIndigo else TextSecondary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCommandsBottomSheet(
    commands: List<QuickCommand>,
    onCommandSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SlateCard,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            Text("Comandos Rápidos IDUPI", style = AppTypography.titleMedium, color = TextPrimary)
            Text("Selecciona una acción recomendada para ejecutar en Pi CLI:", style = AppTypography.bodySmall, color = TextSecondary)

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                modifier = Modifier.heightIn(max = 300.dp)
            ) {
                items(commands) { cmd ->
                    Surface(
                        onClick = { onCommandSelect(cmd.command) },
                        shape = AppShapes.card,
                        color = SlateBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(AppSpacing.cardPadding),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(cmd.command, style = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                                Text(cmd.description, style = AppTypography.labelSmall, color = TextSecondary)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveSubagentsBar(
    subagents: List<com.idupi.app.domain.model.SubagentLiveState>,
    onSelectSubagent: (com.idupi.app.domain.model.SubagentLiveState) -> Unit
) {
    Surface(
        color = SlateCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, AccentPurple.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.AccountTree, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(14.dp))
                Text("Subagentes en la sesión (toca para ver su salida en vivo):", style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold), color = AccentPurple)
            }
            Spacer(modifier = Modifier.height(4.dp))
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(orderedSubagents(subagents)) { agent ->
                    Surface(
                        onClick = { onSelectSubagent(agent) },
                        shape = AppShapes.small,
                        color = if (agent.isRunning) AccentPurple.copy(alpha = 0.2f) else SlateBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (agent.isRunning) AccentPurple else SlateBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (agent.isRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = AccentPurple)
                            } else {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusConnected, modifier = Modifier.size(12.dp))
                            }
                            Text(
                                text = agent.name,
                                style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentLiveConsoleBottomSheet(
    subagent: com.idupi.app.domain.model.SubagentLiveState,
    onDismiss: () -> Unit
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SlateCard,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = null,
                        tint = AccentPurple,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = subagent.name,
                            style = AppTypography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Subagente Autónomo",
                            style = AppTypography.labelSmall,
                            color = AccentPurple
                        )
                    }
                }

                // Status Badge
                Surface(
                    shape = AppShapes.small,
                    color = if (subagent.isRunning) PrimaryIndigo.copy(alpha = 0.15f) else StatusConnected.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (subagent.isRunning) PrimaryIndigo.copy(alpha = 0.4f) else StatusConnected.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (subagent.isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = PrimaryIndigo)
                            Text("En ejecución...", style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold), color = PrimaryIndigo)
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusConnected, modifier = Modifier.size(14.dp))
                            Text("Completado", style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold), color = StatusConnected)
                        }
                    }
                }
            }

            // Task Prompt / Mission
            if (!subagent.task.isNullOrBlank()) {
                Surface(
                    color = SlateBg,
                    shape = AppShapes.card,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.sm)) {
                        Text("🎯 Tarea asignada:", style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold), color = TextSecondary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(subagent.task, style = AppTypography.bodySmall, color = TextPrimary)
                    }
                }
            }

            // Live Output / Response Console
            Text("📜 Respuesta / Salida en Vivo:", style = AppTypography.titleSmall, color = TextPrimary)

            Surface(
                color = IDUPITheme.colors.matrixBg.copy(alpha = 0.8f),
                shape = AppShapes.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryIndigo.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp)
            ) {
                if (subagent.output.isNotBlank()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(AppSpacing.md)
                    ) {
                        item {
                            MarkdownText(markdown = subagent.output, textColor = TextPrimary)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(AppSpacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (subagent.isRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AccentPurple)
                                Text("El subagente está trabajando en tu PC...", style = AppTypography.bodySmall, color = TextSecondary)
                            } else {
                                Text("Sin salida adicional registrada.", style = AppTypography.bodySmall, color = TextSecondary)
                            }
                        }
                    }
                }
            }

            // Actions (Copiar y Cerrar)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (subagent.output.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(subagent.output))
                            copied = true
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder)
                    ) {
                        Icon(
                            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (copied) StatusConnected else TextSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (copied) "¡Copiado!" else "Copiar", color = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                ) {
                    Text("Cerrar")
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.md))
        }
    }
}

/**
 * Live CLI/MCP activity. Running operations come first so the thing happening
 * right now is not pushed off screen by what already finished.
 */
@Composable
fun LiveActivityBar(activities: List<ActivityUiState>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
    ) {
        activities.forEach { activity ->
            LiveActivityRow(activity = activity)
        }
    }
}

@Composable
private fun LiveActivityRow(activity: ActivityUiState) {
    val dotColor = when (activity.status) {
        ActivityStatus.OK -> StatusConnected
        ActivityStatus.FAILED -> StatusError
        ActivityStatus.TIMED_OUT -> StatusError
        ActivityStatus.RUNNING -> if (activity.isStale) StatusTerminal else StatusWorking
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        if (activity.isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = dotColor,
                strokeWidth = 2.dp
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }

        if (activity.kind == "mcp") {
            Text(text = "MCP", style = AppTypography.labelSmall, color = AccentPurple)
        }

        Text(
            text = activity.label,
            style = AppTypography.labelSmall,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = activityStatusText(activity),
            style = AppTypography.labelSmall,
            color = if (activity.isStale) StatusTerminal else TextSecondary,
            maxLines = 1
        )
    }
}

/**
 * The trailing status text. While an operation is open this reports how long it
 * has been running -- and, once the provider has gone quiet past the stale
 * threshold, says so explicitly rather than implying the process is healthy.
 */
private fun activityStatusText(activity: ActivityUiState): String = when (activity.status) {
    ActivityStatus.OK -> "listo"
    ActivityStatus.FAILED -> "falló"
    ActivityStatus.TIMED_OUT -> "sin respuesta"
    ActivityStatus.RUNNING -> {
        val seconds = activity.elapsedMs / 1000
        if (activity.isStale) "sin novedades ${activity.sinceLastUpdateMs / 1000}s" else "${seconds}s"
    }
}
