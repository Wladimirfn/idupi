package com.example.idupi.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import android.app.Activity
import android.content.Context
import android.view.WindowManager
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.idupi.domain.model.PadMode
import com.example.idupi.domain.model.KeyPress
import com.example.idupi.domain.model.ScreenInputEvent
import com.example.idupi.domain.model.ScreenMonitor
import com.example.idupi.domain.model.SpecialKey
import com.example.idupi.domain.model.padCursorDelta
import com.example.idupi.domain.model.padScrollNotches
import com.example.idupi.domain.model.padTwoFingerMode
import com.example.idupi.domain.model.padWheelDelta
import com.example.idupi.domain.model.keyboardDiffs
import com.example.idupi.domain.model.touchToMonitorFraction
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.hypot

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

    // Pad mode (owner decision): the direct screen stays, but a toggleable
    // trackpad lets the user move the cursor precisely WITHOUT a finger
    // covering the picture they are watching. Zoom is LOCAL image scaling.
    var padVisible by remember { mutableStateOf(false) }
    var imageScale by remember { mutableStateOf(1f) }
    // Auto hands the server's ladder the wheel (hito 9); off = manual 55.
    var qualityAuto by remember { mutableStateOf(true) }

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
    LaunchedEffect(Unit) {
        viewModel.refreshMonitors()
        viewModel.refreshConfig()
    }

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
                    // Tracks whether a remote press is currently held: leaving
                    // this screen mid-drag cancels the gesture coroutine
                    // WITHOUT firing onDragEnd/onDragCancel, so disposal is
                    // the last line of defence against wedging the user's
                    // physical mouse.
                    var buttonHeld by remember { mutableStateOf(false) }
                    val heldMonitor = state.selectedMonitorId ?: 0
                    DisposableEffect(Unit) {
                        onDispose {
                            if (buttonHeld) {
                                viewModel.sendInput(ScreenInputEvent(type = "up", monitor = heldMonitor))
                                buttonHeld = false
                            }
                        }
                    }
                    val imageModifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .pointerInput(state.remoteInputEnabled, state.selectedMonitorId) {
                            if (!state.remoteInputEnabled) return@pointerInput
                            val monitor = state.selectedMonitorId ?: 0
                            fun fractionAt(pos: Offset) =
                                touchToMonitorFraction(pos.x, pos.y, size.width.toFloat(), size.height.toFloat())
                            fun send(type: String, f: Pair<Double, Double>?, button: String) {
                                if (f == null) return
                                viewModel.sendInput(
                                    ScreenInputEvent(
                                        type = type,
                                        monitor = monitor,
                                        x = f.first,
                                        y = f.second,
                                        button = button,
                                    )
                                )
                            }
                            detectTapGestures(
                                onTap = { pos ->
                                    val f = fractionAt(pos)
                                    // Atomic click: down+up inside ONE helper
                                    // command -- separate wire events wedged
                                    // the user's physical mouse when the
                                    // release was lost.
                                    send("click", f, "left")
                                },
                                onLongPress = { pos ->
                                    val f = fractionAt(pos)
                                    send("move", f, "right")
                                    send("down", f, "right")
                                    send("up", f, "right")
                                },
                            )
                        }
                        .pointerInput(state.remoteInputEnabled, state.selectedMonitorId) {
                            if (!state.remoteInputEnabled) return@pointerInput
                            val monitor = state.selectedMonitorId ?: 0
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val f = touchToMonitorFraction(pos.x, pos.y, size.width.toFloat(), size.height.toFloat()) ?: return@detectDragGestures
                                    viewModel.sendInput(ScreenInputEvent(type = "move", monitor = monitor, x = f.first, y = f.second))
                                    viewModel.sendInput(ScreenInputEvent(type = "down", monitor = monitor, x = f.first, y = f.second))
                                    buttonHeld = true
                                },
                                onDrag = { change, _ ->
                                    val f = touchToMonitorFraction(change.position.x, change.position.y, size.width.toFloat(), size.height.toFloat()) ?: return@detectDragGestures
                                    viewModel.sendInput(ScreenInputEvent(type = "move", monitor = monitor, x = f.first, y = f.second))
                                },
                                onDragEnd = {
                                    viewModel.sendInput(ScreenInputEvent(type = "up", monitor = monitor))
                                    buttonHeld = false
                                },
                                onDragCancel = {
                                    // A cancelled drag MUST release the button:
                                    // this exact missing 'up' wedged the user's
                                    // physical mouse in a permanently-pressed
                                    // state.
                                    viewModel.sendInput(ScreenInputEvent(type = "up", monitor = monitor))
                                    buttonHeld = false
                                },
                            )
                        }
                    Image(
                        bitmap = frame,
                        contentDescription = "Escritorio remoto",
                        contentScale = ContentScale.FillBounds,
                        modifier = imageModifier.graphicsLayer {
                            scaleX = imageScale
                            scaleY = imageScale
                        }
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
                                viewportH = viewModel.viewportForCurrentBox.second,
                                quality = if (qualityAuto) "auto" else "55",
                            )
                        }
                    },
                    label = { Text(if (state.streaming) "Detener" else "Ver pantalla") }
                )
                FilterChip(
                    selected = qualityAuto,
                    onClick = { qualityAuto = !qualityAuto },
                    label = { Text("Auto") }
                )
                Text(
                    text = statusLine(state.lastRenderMs, state.lastFrameBytes, state.fps),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = buildString {
                        append(if (state.remoteInputEnabled) "· input activo" else "· input apagado en el server")
                        state.activeQuality?.let { append(" · calidad: $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.remoteInputEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Pad toggle + local zoom reset: the pad docks BELOW the picture
            // instead of floating over it -- the whole point is not covering
            // what the user is looking at.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = padVisible,
                    onClick = { padVisible = !padVisible },
                    label = { Text(if (padVisible) "Trackpad ON" else "Trackpad") }
                )
                if (imageScale > 1f) {
                    TextButton(onClick = { imageScale = 1f }) {
                        Text("Zoom %.1fx".format(imageScale))
                    }
                }
            }

            // Realtime typing (hito 7): every keystroke travels AS IT IS
            // PRESSED, never on IME commit. The field is a ONE-CHARACTER
            // conduit over a space sentinel: diffs against the shadow send
            // the keys, then the value resets -- so nothing the user typed
            // ever stays behind, and soft-keyboard BACKSPACE still works
            // because removing the sentinel IS a deletion to the diff.
            if (state.remoteInputEnabled) {
                val keyField = remember { mutableStateOf(TextFieldValue(" ", TextRange(1))) }
                OutlinedTextField(
                    value = keyField.value,
                    onValueChange = { new ->
                        keyboardDiffs(keyField.value.text, new.text).forEach { viewModel.sendKey(it) }
                        keyField.value = TextFieldValue(" ", TextRange(1))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val special = when (event.key) {
                                Key.Enter, Key.NumPadEnter -> SpecialKey.ENTER
                                Key.Backspace -> SpecialKey.BACKSPACE
                                Key.Tab -> SpecialKey.TAB
                                Key.Escape -> SpecialKey.ESCAPE
                                Key.DirectionLeft, Key.SystemNavigationLeft -> SpecialKey.ARROW_LEFT
                                Key.DirectionRight, Key.SystemNavigationRight -> SpecialKey.ARROW_RIGHT
                                Key.DirectionUp, Key.SystemNavigationUp -> SpecialKey.ARROW_UP
                                Key.DirectionDown, Key.SystemNavigationDown -> SpecialKey.ARROW_DOWN
                                else -> null
                            }
                            if (special != null) {
                                viewModel.sendKey(KeyPress.special(special))
                                true
                            } else {
                                false
                            }
                        },
                    label = { Text("Escribí acá — viaja al toque y no queda nada") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }

            if (padVisible) {
                val padMonitor = state.selectedMonitorId ?: 0
                Trackpad(
                    enabled = state.remoteInputEnabled,
                    onRelativeMove = { dx, dy ->
                        viewModel.sendInput(
                            ScreenInputEvent(type = "relmove", monitor = padMonitor, dx = dx, dy = dy)
                        )
                    },
                    onClick = {
                        // Atomic coordless click: press AND release inside one
                        // helper command -- never two wire events apart.
                        viewModel.sendInput(ScreenInputEvent(type = "click", monitor = padMonitor))
                    },
                    onRightClick = {
                        viewModel.sendInput(
                            ScreenInputEvent(type = "click", monitor = padMonitor, button = "right")
                        )
                    },
                    onScroll = { notches ->
                        viewModel.sendInput(
                            ScreenInputEvent(type = "scroll", monitor = padMonitor, delta = padWheelDelta(notches))
                        )
                    },
                    onZoom = { factor ->
                        imageScale = (imageScale * factor).coerceIn(1f, 5f)
                    },
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
 * The floating trackpad surface, styled as one contained card: gesture pad on
 * top (1 finger moves, tap clicks at the current cursor position, two fingers
 * scroll, pinch zooms the LOCAL image), explicit click buttons below -- a tap
 * is easy to miss; a labelled button never is. Mode arbitration maths lives
 * in the domain model (ScreenPad.kt) so it is testable without a screen.
 */
@Composable
private fun Trackpad(
    enabled: Boolean,
    onRelativeMove: (Double, Double) -> Unit,
    onClick: () -> Unit,
    onRightClick: () -> Unit,
    onScroll: (Int) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        val padW = size.width.toFloat()
                        val tapSlopPx = viewConfiguration.touchSlop * 2f
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var lastX = down.position.x
                            var lastY = down.position.y
                            var maxTravelFromDown = 0f
                            var sawSecondFinger = false
                            var mode = PadMode.MOVE
                            var startDist = 0f
                            var lastDist = 0f
                            var lastCentroidY = 0f
                            var scrollAccumPx = 0f
                            var emittedNotches = 0
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break

                                if (!sawSecondFinger && pressed.size >= 2) {
                                    sawSecondFinger = true
                                    val a = pressed[0].position
                                    val b = pressed[1].position
                                    startDist = hypot(a.x - b.x, a.y - b.y)
                                    lastDist = startDist
                                    lastCentroidY = (a.y + b.y) / 2f
                                    scrollAccumPx = 0f
                                    emittedNotches = 0
                                }

                                if (sawSecondFinger && pressed.size >= 2) {
                                    val a = pressed[0].position
                                    val b = pressed[1].position
                                    val dist = hypot(a.x - b.x, a.y - b.y)
                                    val centroidY = (a.y + b.y) / 2f
                                    // Fingers travelling UP scroll UP (Windows wheel:
                                    // positive delta scrolls away from the user).
                                    scrollAccumPx += lastCentroidY - centroidY
                                    lastCentroidY = centroidY
                                    mode = padTwoFingerMode(
                                        current = mode,
                                        distanceRatio = if (startDist > 0f) dist / startDist else 1f,
                                        travelledPx = scrollAccumPx,
                                    )
                                    when (mode) {
                                        PadMode.PINCH -> {
                                            if (lastDist > 0f) onZoom(dist / lastDist)
                                            lastDist = dist
                                        }
                                        PadMode.SCROLL -> {
                                            val notches = padScrollNotches(scrollAccumPx)
                                            if (notches != emittedNotches) {
                                                onScroll(notches - emittedNotches)
                                                emittedNotches = notches
                                            }
                                        }
                                        PadMode.MOVE -> Unit
                                    }
                                } else if (!sawSecondFinger) {
                                    val change = pressed[0]
                                    val pos = change.position
                                    val (dx, dy) = padCursorDelta(pos.x - lastX, pos.y - lastY, padW)
                                    lastX = pos.x
                                    lastY = pos.y
                                    maxTravelFromDown = maxOf(
                                        maxTravelFromDown,
                                        hypot(pos.x - down.position.x, pos.y - down.position.y),
                                    )
                                    onRelativeMove(dx, dy)
                                }

                                event.changes.forEach { it.consume() }
                            }
                            // A quick single-finger touch with no travel is a click at
                            // wherever the cursor already sits -- never a move first,
                            // or precise aiming would be undone by the click itself.
                            if (!sawSecondFinger && maxTravelFromDown <= tapSlopPx) onClick()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (enabled) "Deslizá para mover el cursor"
                        else "Input apagado en el server",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (enabled) {
                        Text(
                            text = "tap = clic · 2 dedos = scroll · pellizco = zoom",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onClick,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("Clic") }
                FilledTonalButton(
                    onClick = onRightClick,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("Clic derecho") }
            }
        }
    }
}

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
