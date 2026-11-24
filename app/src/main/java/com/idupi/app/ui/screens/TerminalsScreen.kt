package com.idupi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idupi.app.domain.model.PtyInfo
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
import com.idupi.app.viewmodel.TerminalsViewModel

/**
 * Live interactive terminals hosted on the PC. Polls while visible, creates a
 * new terminal from the header action, and opens one full screen on tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalsScreen(
    viewModel: TerminalsViewModel,
    onMenuClick: () -> Unit,
    onOpenTerminal: (String) -> Unit
) {
    val ptys by viewModel.ptys.collectAsState()
    val isCreating by viewModel.isCreating.collectAsState()
    val loadFailed by viewModel.loadFailed.collectAsState()

    // A PTY can exit (or be created elsewhere) without this app acting, so the
    // list refreshes while the screen is on screen and stops when it leaves.
    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    // A terminal created here was already spawned on the PC; opening it must
    // attach to that id instead of creating a second one.
    val currentOnOpenTerminal by rememberUpdatedState(onOpenTerminal)
    LaunchedEffect(Unit) {
        for (id in viewModel.createdTerminalIds) {
            currentOnOpenTerminal(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            "Terminales",
                            style = AppTypography.appBarTitle,
                            color = TextPrimary
                        )
                        Text(
                            text = "${ptys.count { it.alive }} activas de ${ptys.size}",
                            style = AppTypography.labelSmall,
                            color = TextSecondary
                        )
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
                    IconButton(onClick = { viewModel.refresh() }) {
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
            Button(
                onClick = { viewModel.createTerminal() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCreating,
                shape = AppShapes.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryIndigo,
                    contentColor = Color.White
                )
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isCreating) "Creando terminal..." else "Nueva terminal",
                    style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            when {
                loadFailed && ptys.isEmpty() -> ErrorState(onRetry = { viewModel.refresh() })

                ptys.isEmpty() -> EmptyState()

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = AppSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    items(ptys, key = { it.id }) { pty ->
                        PtyListItem(pty = pty, onClick = { onOpenTerminal(pty.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.EmptyState() {
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
                Icons.Default.Terminal,
                contentDescription = null,
                tint = TextSecondary.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp)
            )
            Text(
                "No hay terminales abiertas.\nCreá una con \"Nueva terminal\".",
                style = AppTypography.labelSmall,
                color = TextSecondary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ColumnScope.ErrorState(onRetry: () -> Unit) {
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
                Icons.Default.CloudOff,
                contentDescription = null,
                tint = StatusError.copy(alpha = 0.6f),
                modifier = Modifier.size(40.dp)
            )
            Text(
                "No se pudieron cargar las terminales de tu PC.",
                style = AppTypography.labelSmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                shape = AppShapes.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryIndigo,
                    contentColor = Color.White
                )
            ) {
                Text(
                    "Reintentar",
                    style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun PtyListItem(pty: PtyInfo, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = AppShapes.card,
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
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PrimaryIndigo.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = PrimaryIndigo,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = shellNameOf(pty),
                    style = AppTypography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pty.shell ?: "shell",
                    style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!pty.cwd.isNullOrBlank()) {
                    Text(
                        text = pty.cwd,
                        style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                        color = TextSecondary.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = buildString {
                        append("PID ${pty.pid} · ${pty.cols} x ${pty.rows}")
                        if (pty.alive) {
                            append(" · Activa")
                        } else {
                            append(" · Finalizada")
                            pty.exitCode?.let { append(" (código $it)") }
                        }
                    },
                    style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                    color = TextSecondary
                )
                if (!pty.alive && !pty.error.isNullOrBlank()) {
                    Text(
                        text = pty.error,
                        style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                        color = StatusError,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (pty.alive) StatusConnected else StatusError)
            )
        }
    }
}

/**
 * The shell's executable name (the terminal's title): the first token of the
 * command, without its directory. Falls back to "Terminal" when the server did
 * not report the shell.
 */
private fun shellNameOf(pty: PtyInfo): String {
    val executable = pty.shell
        ?.substringBefore(' ')
        ?.substringAfterLast('\\')
        ?.substringAfterLast('/')
        ?.ifBlank { null }
    return executable ?: "Terminal"
}
