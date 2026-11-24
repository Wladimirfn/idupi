package com.idupi.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idupi.app.domain.model.ptyHeaderDisplay
import com.idupi.app.ui.components.PtyExtraKeysBar
import com.idupi.app.ui.components.PtyTerminalView
import com.idupi.app.ui.components.PtyTerminalWebController
import com.idupi.app.ui.components.WIDE_MODE_COLS
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.IDUPITheme
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.SlateBg
import com.idupi.app.ui.theme.SlateBorder
import com.idupi.app.ui.theme.SlateCard
import com.idupi.app.ui.theme.StatusConnected
import com.idupi.app.ui.theme.StatusError
import com.idupi.app.ui.theme.StatusWorking
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary
import com.idupi.app.viewmodel.PtyConnectionState
import com.idupi.app.viewmodel.PtyTerminalEvent
import com.idupi.app.viewmodel.PtyTerminalViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Header context cadence. The live cwd / branch / agent changes on the PC
 * without this app acting (`cd`, `git checkout`, `claude`), so the header is
 * refreshed while the screen is visible; 5 s is fresh enough for a label and
 * cheap against the terminal stream.
 */
private const val HEADER_REFRESH_MS = 5_000L

/**
 * Full-screen interactive terminal: xterm.js renders raw VT bytes streamed by
 * the ViewModel, keystrokes go back through it, and the extra-keys bar covers
 * the keys a phone keyboard lacks.
 *
 * Back (system or top-bar arrow) LEAVES the terminal running on the PC; only
 * the kill button (with confirmation) terminates the process tree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PtyTerminalScreen(
    viewModel: PtyTerminalViewModel,
    ptyId: String?,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val headerInfo by viewModel.headerInfo.collectAsState()
    val controller = remember { PtyTerminalWebController() }
    var ctrlArmed by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }
    var fontSize by remember { mutableIntStateOf(13) }
    var wideMode by rememberSaveable { mutableStateOf(false) }
    var showKillConfirm by remember { mutableStateOf(false) }
    val currentOnBack by rememberUpdatedState(onBack)
    val latestFontSize by rememberUpdatedState(fontSize)
    val latestWideMode by rememberUpdatedState(wideMode)

    // A fresh screen entry targets exactly the terminal navigation asked for
    // (null = create a new one). Leaving disposes the local session WITHOUT
    // killing the PC-side terminal.
    DisposableEffect(Unit) {
        viewModel.prepare(ptyId)
        onDispose { viewModel.detach() }
    }

    // The header context (project, branch, agent) changes on the PC without
    // this app acting, so it is read once on open and refreshed while the
    // screen stays visible. The effect dies with the composition, so nothing
    // polls after leaving the terminal.
    LaunchedEffect(Unit) {
        while (isActive) {
            viewModel.refreshHeader()
            delay(HEADER_REFRESH_MS)
        }
    }

    // Project / branch / agent replace the static PTY id: two terminals in the
    // same folder are told apart by the project and what runs in them.
    val header = ptyHeaderDisplay(headerInfo ?: uiState.pty, uiState.terminalId)

    // Kotlin -> xterm: raw VT chunks, base64 for a quoting-safe JS string.
    LaunchedEffect(controller) {
        for (chunk in viewModel.output) {
            controller.writeBase64(chunk)
        }
    }

    // One-shot navigation events (a kill completed).
    LaunchedEffect(Unit) {
        for (event in viewModel.events) {
            if (event is PtyTerminalEvent.Closed) currentOnBack()
        }
    }

    if (showKillConfirm) {
        KillConfirmDialog(
            onConfirm = {
                showKillConfirm = false
                viewModel.kill()
            },
            onDismiss = { showKillConfirm = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = header.title,
                            style = AppTypography.appBarTitle,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = header.subtitle,
                            style = AppTypography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    ConnectionStateChip(uiState.state)
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    IconButton(onClick = { showKillConfirm = true }) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Cerrar terminal",
                            tint = StatusError
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
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // The terminal keeps its own dark chrome even when the app
                    // uses a light theme: the PC shells emit light-on-dark ANSI
                    // output and xterm's palette is built for it.
                    .background(IDUPITheme.colors.terminalBg)
            ) {
                PtyTerminalView(
                    controller = controller,
                    onReady = { cols, rows ->
                        viewModel.onViewportReady(cols, rows)
                        // A recreated page starts at the JS defaults; reapply
                        // the size and wide mode the user chose this session.
                        controller.setFontSize(latestFontSize)
                        controller.setMinCols(if (latestWideMode) WIDE_MODE_COLS else 0)
                    },
                    onInput = { data -> viewModel.onInput(data) },
                    onResize = { cols, rows -> viewModel.onResize(cols, rows) },
                    onModifiersConsumed = {
                        ctrlArmed = false
                        altArmed = false
                    },
                    modifier = Modifier.fillMaxSize()
                )

                when (uiState.state) {
                    PtyConnectionState.CONNECTING,
                    PtyConnectionState.RECONNECTING -> ConnectingOverlay(uiState.state)
                    PtyConnectionState.EXITED,
                    PtyConnectionState.ERROR -> TerminalEndedOverlay(
                        state = uiState.state,
                        exitCode = uiState.exitCode,
                        error = uiState.error,
                        onBack = onBack
                    )
                    PtyConnectionState.CONNECTED -> Unit
                }
            }

            PtyExtraKeysBar(
                ctrlArmed = ctrlArmed,
                altArmed = altArmed,
                wideMode = wideMode,
                fontSize = fontSize,
                onCtrlToggle = {
                    val next = !ctrlArmed
                    ctrlArmed = next
                    controller.setModifiers(next, altArmed)
                },
                onAltToggle = {
                    val next = !altArmed
                    altArmed = next
                    controller.setModifiers(ctrlArmed, next)
                },
                onWideModeToggle = {
                    val next = !wideMode
                    wideMode = next
                    controller.setMinCols(if (next) WIDE_MODE_COLS else 0)
                },
                onKey = { sequence ->
                    // A bar key is not an xterm keystroke: disarm any waiting
                    // chord so it cannot leak into the next typed character.
                    if (ctrlArmed || altArmed) {
                        ctrlArmed = false
                        altArmed = false
                        controller.setModifiers(false, false)
                    }
                    viewModel.onInput(sequence)
                },
                onFontSizeChange = { size ->
                    fontSize = size
                    controller.setFontSize(size)
                }
            )
        }
    }
}

@Composable
private fun ConnectionStateChip(state: PtyConnectionState) {
    val (label, color) = when (state) {
        PtyConnectionState.CONNECTING -> "Conectando" to StatusWorking
        PtyConnectionState.CONNECTED -> "En vivo" to StatusConnected
        PtyConnectionState.RECONNECTING -> "Reconectando" to StatusWorking
        PtyConnectionState.EXITED -> "Finalizada" to TextSecondary
        PtyConnectionState.ERROR -> "Error" to StatusError
    }
    Surface(
        shape = AppShapes.pill,
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = label,
                style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                color = color
            )
        }
    }
}

@Composable
private fun ConnectingOverlay(state: PtyConnectionState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = AppShapes.card,
            color = SlateCard.copy(alpha = 0.9f),
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Text(
                text = if (state == PtyConnectionState.RECONNECTING) {
                    "Reconectando con tu PC…"
                } else {
                    "Conectando con tu PC…"
                },
                style = AppTypography.codeMono.copy(fontSize = 12.sp),
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
            )
        }
    }
}

@Composable
private fun TerminalEndedOverlay(
    state: PtyConnectionState,
    exitCode: Int?,
    error: String?,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.md),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
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
                Text(
                    text = if (state == PtyConnectionState.ERROR) {
                        "No se pudo conectar"
                    } else {
                        "La terminal terminó" + (exitCode?.let { " (código $it)" } ?: "")
                    },
                    style = AppTypography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (state == PtyConnectionState.ERROR) StatusError else TextPrimary
                )
                Text(
                    text = error ?: if (state == PtyConnectionState.EXITED) {
                        "El proceso en tu PC ya no está corriendo."
                    } else {
                        "Revisa la conexión y el token, o vuelve atrás."
                    },
                    style = AppTypography.bodySmall,
                    color = TextSecondary
                )
                Button(
                    onClick = onBack,
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryIndigo,
                        contentColor = Color.White
                    )
                ) {
                    Text("Volver")
                }
            }
        }
    }
}

@Composable
private fun KillConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Cerrar terminal", color = TextPrimary)
        },
        text = {
            Text(
                "Se cerrará la terminal en tu PC y todo su árbol de procesos. " +
                    "El resto de las terminales no se toca.",
                color = TextSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Cerrar terminal", color = StatusError)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextSecondary)
            }
        },
        containerColor = SlateCard,
        shape = AppShapes.dialog
    )
}
