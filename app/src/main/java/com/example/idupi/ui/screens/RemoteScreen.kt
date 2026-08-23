package com.example.idupi.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.example.idupi.domain.model.ScreenMonitor
import androidx.lifecycle.viewmodel.compose.viewModel

import com.example.idupi.viewmodel.RemoteScreenViewModel

/**
 * Remote screen viewer (brief hito 5): pick a monitor, watch it move.
 *
 * The two pacing rules live in [RemoteScreenViewModel]; this UI only feeds it
 * real device pixels -- requesting MORE pixels than the phone displays would
 * waste encode, wire and decode on the hottest path in the system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    onMenuClick: () -> Unit,
    viewModel: RemoteScreenViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val density = LocalDensity.current
    val view = LocalView.current

    // A paused render stops the acks, the ack-paced server goes silent, and
    // the socket dies at its idle timeout: watching this screen must keep the
    // display on or the phone's own screen timeout freezes the session at
    // exactly that minute mark.
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // The picker has nothing to show until the monitors are fetched, and the
    // screen is the only thing that knows it is being looked at.
    LaunchedEffect(Unit) { viewModel.refreshMonitors() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pantalla Remota") },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Menú")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Monitor picker: one chip per monitor; primary is marked with a star.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.monitors.forEach { monitor ->
                    FilterChip(
                        selected = state.selectedMonitorId == monitor.id,
                        onClick = { viewModel.selectMonitor(monitor.id) },
                        label = { Text(monitorLabel(monitor)) }
                    )
                }
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val boxWpx = with(density) { maxWidth.toPx() }.toInt()
                val boxHpx = with(density) { maxHeight.toPx() }.toInt().coerceAtLeast(200)

                // Local vals: state is a delegated property, so a smart cast
                // between the null checks below is impossible on its fields.
                val frame = state.currentFrame
                val meta = state.frameMeta
                val aspect = if (frame != null && meta != null) {
                    meta.w.toFloat() / meta.h.toFloat()
                } else {
                    16f / 9f
                }

                if (frame != null && meta != null) {
                    Image(
                        bitmap = frame,
                        contentDescription = "Escritorio remoto",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                    )
                } else {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    ) {
                        BoxWithConstraints(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text(
                                text = if (state.streaming) "Esperando el primer frame..."
                                else "Elegí un monitor y tocá Ver pantalla",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // Feed REAL device pixels to the server so it never captures,
                // converts or encodes more than this box displays (brief §4.2).
                viewModel.updateViewport(boxWpx, boxHpx)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = state.streaming,
                    onClick = {
                        if (state.streaming) {
                            viewModel.stopStreaming()
                        } else {
                            viewModel.startStreaming(
                                viewportW = viewModel.viewportForCurrentBox.first,
                                viewportH = viewModel.viewportForCurrentBox.second
                            )
                        }
                    },
                    label = { Text(if (state.streaming) "Detener" else "Ver pantalla") }
                )
                Text(
                    text = statusLine(state.lastRenderMs, state.lastFrameBytes, state.fps),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            state.error?.let { error ->
                Card {
                    Text(
                        text = error,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun monitorLabel(monitor: ScreenMonitor): String =
    "${monitor.name} (${monitor.width}x${monitor.height})" + if (monitor.primary) " ★" else ""

/**
 * A running frame COUNT answered nothing -- 400 frames reads the same over ten
 * seconds or ten minutes. The rate is what says whether the stream is healthy,
 * and it drops the moment the stream does.
 */
private fun statusLine(renderMs: Long, bytes: Int, fps: Int): String =
    "$fps fps · ${bytes / 1024} KB · $renderMs ms decode"
/** Unwraps ContextWrapper chains to the Activity, for window flag changes. */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
