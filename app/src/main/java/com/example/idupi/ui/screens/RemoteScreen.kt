package com.example.idupi.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.idupi.domain.model.PadMode
import com.example.idupi.domain.model.PadZone
import com.example.idupi.domain.model.KeyPress
import com.example.idupi.domain.model.PAD_EDGE_ZONE_DP
import com.example.idupi.domain.model.ScreenInputEvent
import com.example.idupi.domain.model.ScreenMonitor
import com.example.idupi.domain.model.SpecialKey
import com.example.idupi.domain.model.padCursorDelta
import com.example.idupi.domain.model.padIsPinchStep
import com.example.idupi.domain.model.padScrollNotches
import com.example.idupi.domain.model.padZoneAt
import com.example.idupi.domain.model.padTwoFingerMode
import com.example.idupi.domain.model.padWheelDelta
import com.example.idupi.domain.model.keyboardDiffs
import com.example.idupi.domain.model.touchToMonitorFraction
import com.example.idupi.domain.model.touchToMonitorFractionCropped
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
import kotlin.math.hypot

import com.example.idupi.viewmodel.RemoteScreenUiState
import com.example.idupi.viewmodel.RemoteScreenViewModel
import com.example.idupi.ui.components.FloatingBubble
import com.example.idupi.ui.components.SplitKeyboard

/**
 * Remote screen viewer (brief hito 5): pick a monitor, watch it move.
 *
 * The two pacing rules live in [RemoteScreenViewModel]; this UI only feeds it
 * real device pixels -- requesting MORE pixels than the phone displays would
 * waste encode, wire and decode on the hottest path in the system.
 *
 * Hito 10: landscape shows the picture full-bleed with the controls floating
 * over it (a collapsible bottom card), and the picture gains LOCAL pan/zoom:
 * one finger still drives the remote mouse, two fingers pan/zoom the image.
 * Portrait keeps the classic stacked layout unchanged.
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
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Pad mode (owner decision): the trackpad is always visible below the
    // controls so the user can move the cursor precisely WITHOUT a finger
    // covering the picture they are watching. Zoom is LOCAL image scaling;
    // pan (hito 10) rides alongside it so a zoomed picture can be moved.
    var imageScale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    // Landscape overlays the controls; this collapses them so the whole
    // screen belongs to the picture.
    var controlsVisible by remember { mutableStateOf(true) }
    // YouTube-style manual lock (owner request): a small corner button pins
    // the session to landscape fullscreen without waiting for the user to
    // physically rotate the phone. Survives rotation because the activity
    // no longer recreates (configChanges in the manifest).
    var orientationLocked by remember { mutableStateOf(false) }
    val useLandscape = isLandscape || orientationLocked
    // Fullscreen owns everything when watching wide -- by physical rotation
    // or by the corner button. The owner found that the second entry into
    // fullscreen flashed the full touchpad over the picture. Cause: immersive
    // was useLandscape && streaming, and the orientation change restarts the
    // stream (streaming false for ~300ms), so the second entry opened in the
    // non-immersive branch. Fix: stay immersive while a frame exists -- the
    // restart is invisible and the exit via the bubble is what really leaves.
    val immersive = useLandscape && (state.streaming || state.currentFrame != null)

    fun resetTransform() {
        imageScale = 1f
        panOffset = Offset.Zero
    }

    // Trackpad pinch zooms the LOCAL image. Defined here so it captures the
    // mutable state holders (not values): Trackpad's gesture detector may hold
    // a stale lambda, but stale or not it always reads/writes the live state.
    val onTrackpadZoom: (Float) -> Unit = { factor ->
        val newScale = (imageScale * factor).coerceIn(1f, 5f)
        imageScale = newScale
        // At 1x there is nowhere to pan; intermediate scales are re-clamped
        // at draw time and on the next touch.
        if (newScale <= 1f) panOffset = Offset.Zero
    }

    // A paused render stops the acks, the ack-paced server goes silent, and
    // the socket dies at its idle timeout: watching this screen must keep the
    // display on or the phone's own screen timeout freezes the session at
    // exactly that minute mark.
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Leaving this screen must also hand orientation back to the
            // system, or the whole app stays stuck sideways.
            view.context.findActivity()?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // The corner button's promise: locked means LANDSCAPE no matter how the
    // phone is held; unlocked means the system decides again.
    DisposableEffect(orientationLocked) {
        view.context.findActivity()?.requestedOrientation =
            if (orientationLocked) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { }
    }

    // Immersive fullscreen (owner request): while STREAMING in landscape the
    // system bars step aside exactly like a video player -- swipe from an
    // edge brings them back transiently. Leaving landscape or stopping the
    // stream restores them; leaving this screen restores them too.
    DisposableEffect(immersive) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val controller = WindowInsetsControllerCompat(window, view)
            if (immersive) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    // The picker has nothing to show until the monitors are fetched, and the
    // screen is the only thing that knows it is being looked at.
    LaunchedEffect(Unit) {
        viewModel.refreshMonitors()
        viewModel.refreshConfig()
    }

    // A fresh monitor or a fresh stream shows a fresh, untransformed picture.
    LaunchedEffect(state.selectedMonitorId) { resetTransform() }
    LaunchedEffect(state.streaming) { if (state.streaming) resetTransform() }
    // Rotation swaps the box's aspect: the streamed viewport is recomputed by
    // [RemoteImageArea] during layout, and the stream restarts so the server
    // sends a frame sized for what is now displayed. The user's quality
    // choice rides along (it lives in the ViewModel state).
    LaunchedEffect(configuration.orientation) {
        resetTransform()
        if (state.streaming) {
            viewModel.startStreaming(
                viewportW = viewModel.viewportForCurrentBox.first,
                viewportH = viewModel.viewportForCurrentBox.second,
            )
        }
    }

    Scaffold(
        containerColor = if (immersive)
            androidx.compose.ui.graphics.Color.Black
        else MaterialTheme.colorScheme.background,
        topBar = {
            // Fullscreen hides the app bar too: every pixel belongs to the
            // stream, YouTube-style. The back gesture still works.
            if (!immersive) {
                TopAppBar(
                    title = { Text("Pantalla Remota") },
                    navigationIcon = {
                        IconButton(onClick = onMenuClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Menú")
                        }
                    }
                )
            }
        }
    ) { padding ->
        // In fullscreen the Scaffold padding (zero-height bar anyway) and any
        // inset-driven spacing would only shrink the picture: ignore it.
        val outerPadding = if (immersive) Modifier else Modifier.padding(padding)
        if (useLandscape) {
            // Landscape (hito 10): the picture owns the whole content area and
            // the controls float OVER it -- a compact status row on top and a
            // collapsible card at the bottom. Nothing permanently shrinks the
            // screen.
            val controlsScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(outerPadding)
                    .imePadding()
            ) {
                RemoteImageArea(
                    state = state,
                    viewModel = viewModel,
                    density = density,
                    fillAvailable = true,
                    imageScale = imageScale,
                    panOffset = panOffset,
                    onTransform = { scale, pan ->
                        imageScale = scale
                        panOffset = pan
                    },
                    modifier = Modifier.fillMaxSize(),
                    fillScreen = immersive,
                )

                if (immersive) {
                    // CLEAN FULLSCREEN (owner correction): rotating shows
                    // ONLY the picture -- no status pills, no trackpad card,
                    // nothing riding on top. The corner toggle and the
                    // draggable floating bubble are the only residents; the
                    // bubble's menu summons the split keyboard or the mini
                    // pad on demand, and "salir" hands orientation back.
                    var keyboardOpen by remember { mutableStateOf(false) }
                    var miniPadOpen by remember { mutableStateOf(false) }
                    FullscreenToggleButton(
                        locked = true,
                        onToggle = {
                            orientationLocked = false
                            resetTransform()
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                    FloatingBubble(
                        onKeyboard = {
                            keyboardOpen = !keyboardOpen
                            if (keyboardOpen) miniPadOpen = false
                        },
                        onPad = {
                            miniPadOpen = !miniPadOpen
                            if (miniPadOpen) keyboardOpen = false
                        },
                        onExitFullscreen = {
                            orientationLocked = false
                            keyboardOpen = false
                            miniPadOpen = false
                            resetTransform()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (miniPadOpen) {
                        MiniPad(
                            viewModel = viewModel,
                            padMonitor = state.selectedMonitorId ?: 0,
                            onClose = { miniPadOpen = false },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp),
                        )
                    }
                    if (keyboardOpen) {
                        SplitKeyboard(
                            onKey = { viewModel.sendKey(it) },
                            onClose = { keyboardOpen = false },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                        )
                    }
                } else {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (state.remoteInputEnabled)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    ) {
                        Text(
                            text = buildString {
                                append("${state.fps} fps")
                                append(if (state.remoteInputEnabled) " · input activo" else " · input apagado")
                                state.activeQuality?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.remoteInputEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    if (imageScale > 1f || panOffset != Offset.Zero) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            modifier = Modifier.clickable(onClick = { resetTransform() }),
                        ) {
                            Text(
                                text = "Zoom %.1fx · reset".format(imageScale),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    FilterChip(
                        selected = controlsVisible,
                        onClick = { controlsVisible = !controlsVisible },
                        label = { Text("Controles") }
                    )
                }

                // YouTube's corner promise (owner request): tap to lock
                // landscape fullscreen without rotating; tap again to let
                // the phone decide.
                FullscreenToggleButton(
                    locked = orientationLocked,
                    onToggle = {
                        orientationLocked = !orientationLocked
                        resetTransform()
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )

                if (controlsVisible) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(8.dp)
                            .heightIn(max = 340.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(controlsScroll)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            MonitorPickerRow(state = state, viewModel = viewModel)
                            ScreenControls(
                                state = state,
                                viewModel = viewModel,
                                onQualitySelect = { viewModel.setScreenQuality(it) },
                                imageScale = imageScale,
                                panOffset = panOffset,
                                onZoom = onTrackpadZoom,
                                onResetTransform = { resetTransform() },
                                scrollState = controlsScroll,
                            )
                        }
                    }
                }
                }
            }
        } else {
            val controlsScroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(controlsScroll)
                    .imePadding()
                    .then(outerPadding)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Monitor picker: one chip per monitor; primary is marked with a star.
                MonitorPickerRow(state = state, viewModel = viewModel)

                Box(modifier = Modifier.fillMaxWidth()) {
                    RemoteImageArea(
                        state = state,
                        viewModel = viewModel,
                        density = density,
                        fillAvailable = false,
                        imageScale = imageScale,
                        panOffset = panOffset,
                        onTransform = { scale, pan ->
                            imageScale = scale
                            panOffset = pan
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FullscreenToggleButton(
                        locked = orientationLocked,
                        onToggle = {
                            orientationLocked = !orientationLocked
                            resetTransform()
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }

                ScreenControls(
                    state = state,
                    viewModel = viewModel,
                    onQualitySelect = { viewModel.setScreenQuality(it) },
                    imageScale = imageScale,
                    panOffset = panOffset,
                    onZoom = onTrackpadZoom,
                    onResetTransform = { resetTransform() },
                    scrollState = controlsScroll,
                )
            }
        }
    }
}

/**
 * The picture plus ALL its touch handling. One finger drives the remote mouse
 * exactly as before; a SECOND finger switches the same surface to LOCAL
 * pan/zoom (hito 10). The transform detector is declared FIRST and consumes
 * its two-finger events, which cancels the remote drag detector declared
 * after it -- and that cancel path releases the remote button, so a pinch
 * that starts with one finger can never wedge the physical mouse.
 *
 * Touch positions are inverse-mapped through the current scale/pan before
 * becoming monitor fractions: graphicsLayer transforms never move the layout
 * bounds that pointer hit-testing reports against.
 */
@Composable
private fun RemoteImageArea(
    state: RemoteScreenUiState,
    viewModel: RemoteScreenViewModel,
    density: Density,
    fillAvailable: Boolean,
    imageScale: Float,
    panOffset: Offset,
    onTransform: (Float, Offset) -> Unit,
    modifier: Modifier = Modifier,
    fillScreen: Boolean = false,
) {
    BoxWithConstraints(
        modifier = modifier,
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

            // Gesture detectors are created once per key and live across many
            // frames, so they must read the CURRENT transform, not the one
            // from the composition that created them.
            val currentScale by rememberUpdatedState(imageScale)
            val currentPan by rememberUpdatedState(panOffset)
            val currentOnTransform by rememberUpdatedState(onTransform)

            val baseImageModifier = when {
                // Fullscreen prioriza ver TODO el escritorio con borde negro
                // y NADA recortado. 19:9 PC en 19.5:9 S24 Ultra: achicado al
                // 80% (era 85%, dueño pidió otro 5% para ver los bordes).
                fillScreen -> Modifier.fillMaxSize(0.80f).aspectRatio(aspect)
                fillAvailable -> Modifier.fillMaxSize().aspectRatio(aspect)
                else -> Modifier.fillMaxWidth().aspectRatio(aspect)
            }
            val imageModifier = baseImageModifier
                .clipToBounds()
                // Touchpad navigation (owner model, like a notebook's panel):
                // the moment a SECOND finger lands, the gesture belongs to the
                // view -- sliding both fingers pans in ANY direction, pinching
                // zooms. Listens on the INITIAL pass: being first-declared,
                // Main pass would reach the remote drag detector first and the
                // mouse would move instead. Consuming here makes every
                // later handler see the changes as taken. ONE finger stays
                // remote -- its events pass through unconsumed.
                .pointerInput(state.remoteInputEnabled, state.selectedMonitorId) {
                    if (!state.remoteInputEnabled) return@pointerInput
                    val nodeW = size.width.toFloat()
                    val nodeH = size.height.toFloat()
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val scale = currentScale
                                val pan = currentPan
                                val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                val ratio = if (scale > 0f) newScale / scale else 1f
                                // Keep the point under the fingers fixed:
                                // graphicsLayer scales about the node centre,
                                // so the pan must compensate for the centroid's
                                // distance from it.
                                val pivotX = nodeW / 2f
                                val pivotY = nodeH / 2f
                                val newPan = Offset(
                                    x = (centroid.x - pivotX) * (1f - ratio) + pan.x * ratio + panChange.x,
                                    y = (centroid.y - pivotY) * (1f - ratio) + pan.y * ratio + panChange.y,
                                )
                                currentOnTransform(newScale, clampPan(newPan, newScale, nodeW, nodeH))
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(state.remoteInputEnabled, state.selectedMonitorId) {
                    if (!state.remoteInputEnabled) return@pointerInput
                    val monitor = state.selectedMonitorId ?: 0
                    fun fractionAt(pos: Offset) =
                        frameFractionAt(pos, size.width.toFloat(), size.height.toFloat(), currentScale, currentPan)
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
                            val f = frameFractionAt(pos, size.width.toFloat(), size.height.toFloat(), currentScale, currentPan) ?: return@detectDragGestures
                            viewModel.sendInput(ScreenInputEvent(type = "move", monitor = monitor, x = f.first, y = f.second))
                            viewModel.sendInput(ScreenInputEvent(type = "down", monitor = monitor, x = f.first, y = f.second))
                            buttonHeld = true
                        },
                        onDrag = { change, _ ->
                            val f = frameFractionAt(change.position, size.width.toFloat(), size.height.toFloat(), currentScale, currentPan) ?: return@detectDragGestures
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
                            // state. Two-finger takeovers land here.
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
                    // Defensive clamp at DRAW time: the stored pan can only
                    // come from paths that clamp (pinch) or reset (1x), but
                    // clamping here AND in frameFractionAt keeps the drawn
                    // picture and the touch mapping consistent forever.
                    if (imageScale <= 1f) {
                        translationX = 0f
                        translationY = 0f
                    } else {
                        val maxX = (imageScale - 1f) * size.width / 2f
                        val maxY = (imageScale - 1f) * size.height / 2f
                        translationX = panOffset.x.coerceIn(-maxX, maxX)
                        translationY = panOffset.y.coerceIn(-maxY, maxY)
                    }
                }
            )
        } else {
            Card(
                modifier = (if (fillAvailable) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
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
}

/**
 * Monitor picker: one chip per monitor; primary is marked with a star.
 * Shared by the portrait column and the landscape overlay.
 */
@Composable
private fun MonitorPickerRow(
    state: RemoteScreenUiState,
    viewModel: RemoteScreenViewModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.monitors.forEach { monitor ->
            FilterChip(
                selected = state.selectedMonitorId == monitor.id,
                onClick = { viewModel.selectMonitor(monitor.id) },
                label = { Text(monitorLabel(monitor)) }
            )
        }
    }
}

/**
 * Everything under the picture: stream/auto chips, metrics, zoom reset,
 * realtime keyboard, trackpad, error card. Used verbatim by the portrait
 * column and inside the landscape overlay card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScreenControls(
    state: RemoteScreenUiState,
    viewModel: RemoteScreenViewModel,
    onQualitySelect: (String) -> Unit,
    imageScale: Float,
    panOffset: Offset,
    onZoom: (Float) -> Unit,
    onResetTransform: () -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Two separate rows: controls on their own line, metrics below as
        // quiet pills -- cramming them into one row made them collide.
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
                        )
                    }
                },
                label = { Text(if (state.streaming) "Detener" else "Ver pantalla") }
            )
            // Quality as a proper MENU (owner request): one compact chip
            // shows the current choice; opening it lists every preset in a
            // tidy, ordered dropdown instead of a row that overflows.
            var qualityMenuOpen by remember { mutableStateOf(false) }
            val qualityOptions = listOf("auto", "baja", "media", "alta", "ultra")
            Box {
                FilterChip(
                    selected = true,
                    onClick = { qualityMenuOpen = !qualityMenuOpen },
                    label = {
                        Text(
                            "Calidad: " +
                                state.selectedQuality.replaceFirstChar { it.uppercase() }
                        )
                    },
                )
                DropdownMenu(
                    expanded = qualityMenuOpen,
                    onDismissRequest = { qualityMenuOpen = false },
                ) {
                    qualityOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                onQualitySelect(option)
                                qualityMenuOpen = false
                            },
                            trailingIcon = if (state.selectedQuality == option) {
                                @Composable { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else null,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusLine(state.lastRenderMs, state.lastFrameBytes, state.fps, state.frameMeta?.helperMs ?: 0),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = if (state.remoteInputEnabled)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Text(
                    text = buildString {
                        append(if (state.remoteInputEnabled) "input activo" else "input apagado")
                        state.activeQuality?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.remoteInputEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        // Local zoom reset: the pad itself is always visible below, docked BELOW
        // the picture instead of floating over it -- the whole point is not
        // covering what the user is looking at. Reset clears pan too (hito 10).
        if (imageScale > 1f || panOffset != Offset.Zero) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onResetTransform) {
                    Text("Zoom %.1fx".format(imageScale))
                }
            }
        }

        // Realtime typing (hito 7): every keystroke travels AS IT IS
        // PRESSED, never on IME commit. The buffer SHOWS what was typed --
        // the user wants to see it -- and ENTER both sends and cleans:
        // that is the natural end of a sentence. Past seven lines the
        // field scrolls instead of growing forever.
        if (state.remoteInputEnabled) {
            var keyText by remember { mutableStateOf("") }
            // Keep the field above the IME and in view: the outer column is
            // padded by the keyboard, and when the field gains focus or grows
            // past a couple of lines its container scrolls to reveal it --
            // WhatsApp-style, so what is being typed never hides underneath.
            var textFieldFocused by remember { mutableStateOf(false) }
            LaunchedEffect(textFieldFocused, keyText) {
                if (textFieldFocused && scrollState.maxValue > 0) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
            }
            OutlinedTextField(
                value = keyText,
                onValueChange = { new ->
                    val presses = keyboardDiffs(keyText, new)
                    presses.forEach { viewModel.sendKey(it) }
                    keyText =
                        if (presses.any { it == KeyPress.special(SpecialKey.ENTER) }) ""
                        else new
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { textFieldFocused = it.isFocused }
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
                            if (special == SpecialKey.ENTER) keyText = ""
                            // The preview consumes the event, so the field never
                            // sees the backspace: mirror the deletion here or the
                            // buffer desyncs from what the server received.
                            if (special == SpecialKey.BACKSPACE && keyText.isNotEmpty()) {
                                keyText = keyText.dropLast(1)
                            }
                            true
                        } else {
                            false
                        }
                    },
                label = { Text("Teclado") },
                minLines = 1,
                maxLines = 7,
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        }

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
            onScroll = { notches, axis ->
                viewModel.sendInput(
                    ScreenInputEvent(
                        type = "scroll",
                        monitor = padMonitor,
                        delta = padWheelDelta(notches),
                        axis = axis,
                    )
                )
            },
            onZoom = onZoom,
        )

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

private fun monitorLabel(monitor: ScreenMonitor): String =
    "${monitor.name} (${monitor.width}x${monitor.height})" + if (monitor.primary) " ★" else ""

/**
 * The small corner control the owner asked for, YouTube-style: enter
 * landscape fullscreen on tap, leave on the next tap. A translucent pill so
 * it reads over any picture without covering much of it.
 */
@Composable
private fun FullscreenToggleButton(
    locked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        modifier = modifier,
    ) {
        IconButton(onClick = onToggle) {
            Icon(
                if (locked) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = if (locked) "Volver a vertical" else "Pantalla completa horizontal",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Owner's fullscreen "Pad" option: a SMALL translucent touchpad whose only
 * jobs are scrolling (both axes), left click and right click -- not a second
 * control center. It reuses the same Trackpad gestures as the docked one.
 */
@Composable
private fun MiniPad(
    viewModel: RemoteScreenViewModel,
    padMonitor: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = modifier.width(190.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Pad",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cerrar pad")
                }
            }
            Trackpad(
                enabled = true,
                onRelativeMove = { dx, dy ->
                    viewModel.sendInput(ScreenInputEvent(type = "relmove", monitor = padMonitor, dx = dx, dy = dy))
                },
                onClick = { viewModel.sendInput(ScreenInputEvent(type = "click", monitor = padMonitor)) },
                onRightClick = {
                    viewModel.sendInput(ScreenInputEvent(type = "click", monitor = padMonitor, button = "right"))
                },
                onScroll = { notches, axis ->
                    viewModel.sendInput(
                        ScreenInputEvent(type = "scroll", monitor = padMonitor, delta = padWheelDelta(notches), axis = axis)
                    )
                },
                onZoom = {},
            )
        }
    }
}

/**
 * The floating trackpad surface, styled as one contained card: a NOTEBOOK
 * touchpad (owner model, mirroring the Windows precision-touchpad table) --
 * one finger moves, tap clicks, two-finger slide scrolls BOTH axes, pinch
 * zooms the LOCAL image, two-finger tap right-clicks. Mode maths lives in
 * the domain model (ScreenPad.kt) so it is testable without a screen.
 */
@Composable
private fun Trackpad(
    enabled: Boolean,
    onRelativeMove: (Double, Double) -> Unit,
    onClick: () -> Unit,
    onRightClick: () -> Unit,
    onScroll: (Int, String) -> Unit,
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
                        val padH = size.height.toFloat()
                        val edgePx = PAD_EDGE_ZONE_DP * density
                        val tapSlopPx = viewConfiguration.touchSlop * 2f
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val zone = padZoneAt(down.position.x, down.position.y, padW, padH, edgePx)
                            var lastX = down.position.x
                            var lastY = down.position.y
                            var maxTravelFromDown = 0f
                            var accUp = 0f
                            var accRight = 0f
                            var emittedV = 0
                            var emittedH = 0

                            when (zone) {
                                PadZone.SCROLL_V -> {
                                    // Slide along the right strip: vertical wheel.
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break
                                        val pos = pressed[0].position
                                        accUp += lastY - pos.y
                                        lastX = pos.x
                                        lastY = pos.y
                                        val notches = padScrollNotches(accUp)
                                        if (notches != emittedV) {
                                            onScroll(notches - emittedV, "v")
                                            emittedV = notches
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                                PadZone.SCROLL_H -> {
                                    // Slide along the bottom strip: horizontal wheel.
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break
                                        val pos = pressed[0].position
                                        accRight += pos.x - lastX
                                        lastX = pos.x
                                        lastY = pos.y
                                        val notches = padScrollNotches(accRight)
                                        if (notches != emittedH) {
                                            onScroll(notches - emittedH, "h")
                                            emittedH = notches
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                                PadZone.CURSOR -> {
                                    // The notebook-touchpad centre: one finger moves,
                                    // two fingers scroll both axes / pinch zoom,
                                    // two-finger tap right-clicks.
                                    var sawSecondFinger = false
                                    var prevDist = 0f
                                    var prevCx = 0f
                                    var prevCy = 0f
                                    var accVUp = 0f
                                    var accHRight = 0f
                                    var emittedV2 = 0
                                    var emittedH2 = 0
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break

                                        if (!sawSecondFinger && pressed.size >= 2) {
                                            sawSecondFinger = true
                                            val a = pressed[0].position
                                            val b = pressed[1].position
                                            prevDist = hypot(a.x - b.x, a.y - b.y)
                                            prevCx = (a.x + b.x) / 2f
                                            prevCy = (a.y + b.y) / 2f
                                            accVUp = 0f
                                            accHRight = 0f
                                            emittedV2 = 0
                                            emittedH2 = 0
                                        }

                                        if (sawSecondFinger && pressed.size >= 2) {
                                            val a = pressed[0].position
                                            val b = pressed[1].position
                                            val dist = hypot(a.x - b.x, a.y - b.y)
                                            val cx = (a.x + b.x) / 2f
                                            val cy = (a.y + b.y) / 2f
                                            if (padIsPinchStep(prevDist, dist)) {
                                                if (prevDist > 0f) onZoom(dist / prevDist)
                                                prevDist = dist
                                                accVUp = 0f
                                                accHRight = 0f
                                                emittedV2 = 0
                                                emittedH2 = 0
                                            } else {
                                                accVUp += prevCy - cy
                                                accHRight += cx - prevCx
                                                prevCx = cx
                                                prevCy = cy
                                                val notchesV = padScrollNotches(accVUp)
                                                if (notchesV != emittedV2) {
                                                    onScroll(notchesV - emittedV2, "v")
                                                    emittedV2 = notchesV
                                                }
                                                val notchesH = padScrollNotches(accHRight)
                                                if (notchesH != emittedH2) {
                                                    onScroll(notchesH - emittedH2, "h")
                                                    emittedH2 = notchesH
                                                }
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
                                    // Release semantics for the cursor zone only:
                                    // single tap left-clicks, two-finger tap right-
                                    // clicks -- Windows precision-touchpad table.
                                    if (maxTravelFromDown <= tapSlopPx) {
                                        if (sawSecondFinger) onRightClick() else onClick()
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Edge-zone stripes: quiet but discoverable affordances.
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    ) {
                        Text(
                            text = "⇕",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 34.dp),
                        )
                    }
                }
                Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        modifier = Modifier.padding(horizontal = 52.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "⇔",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 3.dp),
                        )
                    }
                }
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
                            text = "tap = clic · bordes ⇕ ⇔ = scroll · 2 dedos = pellizco",
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
private fun statusLine(renderMs: Long, bytes: Int, fps: Int, helperMs: Int): String =
    "$fps fps · ${bytes / 1024} KB · srv ${helperMs} ms · decode ${renderMs} ms"

/**
 * Bounds for a panned/scaled picture: at scale S the image sticks out
 * (S-1)/2 of the node on every side, so a translation beyond that exposes
 * empty space at one edge -- clamp it. At 1x there is nothing to pan.
 */
private fun clampPan(offset: Offset, scale: Float, nodeW: Float, nodeH: Float): Offset {
    if (scale <= 1f) return Offset.Zero
    val maxX = (scale - 1f) * nodeW / 2f
    val maxY = (scale - 1f) * nodeH / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

/**
 * Inverse-maps a touch in the (possibly scaled/panned) image node back to
 * UNTRANSFORMED frame coordinates before [touchToMonitorFraction]: the frame
 * fills the node exactly at 1x, but graphicsLayer scales about the node centre
 * and translates, while hit-testing still reports positions in the untouched
 * layout bounds -- so a tap near the node's edge is NOT the frame's edge once
 * zoomed. Same pivot and clamp model as the graphicsLayer below, so what is
 * drawn and what a tap targets can never disagree.
 */
private fun frameFractionAt(
    pos: Offset,
    nodeW: Float,
    nodeH: Float,
    scale: Float,
    pan: Offset,
): Pair<Double, Double>? {
    val clamped = clampPan(pan, scale, nodeW, nodeH)
    val pivotX = nodeW / 2f
    val pivotY = nodeH / 2f
    val fx = pivotX + (pos.x - clamped.x - pivotX) / scale
    val fy = pivotY + (pos.y - clamped.y - pivotY) / scale
    return touchToMonitorFraction(fx, fy, nodeW, nodeH)
}

private fun frameFractionAtCropped(
    pos: Offset,
    nodeW: Float,
    nodeH: Float,
    scale: Float,
    pan: Offset,
    frameW: Float,
    frameH: Float,
): Pair<Double, Double>? {
    val clamped = clampPan(pan, scale, nodeW, nodeH)
    val pivotX = nodeW / 2f
    val pivotY = nodeH / 2f
    val fx = pivotX + (pos.x - clamped.x - pivotX) / scale
    val fy = pivotY + (pos.y - clamped.y - pivotY) / scale
    return touchToMonitorFractionCropped(fx, fy, nodeW, nodeH, frameW, frameH)
}

/** Unwraps ContextWrapper chains to the Activity, for window flag changes. */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
