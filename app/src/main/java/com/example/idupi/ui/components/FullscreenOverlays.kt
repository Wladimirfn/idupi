package com.example.idupi.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.idupi.domain.model.KeyPress
import com.example.idupi.domain.model.SpecialKey
import com.example.idupi.domain.model.snapBubbleToEdge
import kotlin.math.roundToInt

@Composable
fun FloatingBubble(
    onKeyboard: () -> Unit,
    onPad: () -> Unit,
    onExitFullscreen: () -> Unit,
    monitors: List<com.example.idupi.domain.model.ScreenMonitor> = emptyList(),
    selectedMonitorId: Int? = null,
    onSelectMonitor: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val bubbleSize = with(LocalDensity.current) { 48.dp.toPx() }
        val innerW = (constraints.maxWidth - bubbleSize).coerceAtLeast(0f)
        val innerH = (constraints.maxHeight - bubbleSize).coerceAtLeast(0f)
        var pos by remember(innerW, innerH) { mutableStateOf(innerW to innerH * 0.55f) }
        var menuOpen by remember { mutableStateOf(false) }

        // Draggable bubble handle
        Box(
            modifier = Modifier
                .offset { IntOffset(pos.first.roundToInt(), pos.second.roundToInt()) }
                .size(48.dp)
                .pointerInput(innerW, innerH) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            pos = ((pos.first + dragAmount.x).coerceIn(0f, innerW)) to
                                ((pos.second + dragAmount.y).coerceIn(0f, innerH))
                        },
                        onDragEnd = {
                            val (x, y) = snapBubbleToEdge(pos.first, pos.second, innerW, innerH)
                            pos = x to y
                        },
                    )
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { menuOpen = !menuOpen }) },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(10.dp),
                    ) {}
                }
            }
        }

        // Menu as sibling - not inside draggable Box, so taps are not consumed
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Teclado") },
                leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
                onClick = { menuOpen = false; onKeyboard() },
            )
            DropdownMenuItem(
                text = { Text("Pad") },
                leadingIcon = { Icon(Icons.Filled.Mouse, contentDescription = null) },
                onClick = { menuOpen = false; onPad() },
            )
            if (monitors.isNotEmpty() && onSelectMonitor != null) {
                androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Monitores",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                monitors.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.name + if (m.primary) " ★" else "") },
                        trailingIcon = if (m.id == selectedMonitorId) {
                            { Icon(Icons.Filled.Close, contentDescription = null) }
                        } else null,
                        onClick = { menuOpen = false; onSelectMonitor(m.id) },
                    )
                }
            }
            androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text("Salir pantalla completa") },
                leadingIcon = { Icon(Icons.Filled.FullscreenExit, contentDescription = null) },
                onClick = { menuOpen = false; onExitFullscreen() },
            )
        }
    }
}

@Composable
fun SplitKeyboard(
    onKey: (KeyPress) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shift by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    fun letter(c: Char) {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        onKey(KeyPress.char(if (shift) c.uppercaseChar() else c))
        shift = false
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Teclado", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cerrar teclado")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyboardRow { for (c in "qwert") KeyCap("${c.uppercaseChar()}", Modifier.weight(1f)) { letter(c) } }
                    KeyboardRow { for (c in "asdfg") KeyCap("${c.uppercaseChar()}", Modifier.weight(1f)) { letter(c) } }
                    KeyboardRow {
                        KeyCap("⇧", Modifier.weight(1f), highlighted = shift) {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            shift = !shift
                        }
                        for (c in "zxcvb") KeyCap("${c.uppercaseChar()}", Modifier.weight(1f)) { letter(c) }
                    }
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyboardRow { for (c in "yuiop") KeyCap("${c.uppercaseChar()}", Modifier.weight(1f)) { letter(c) } }
                    KeyboardRow { for (c in "hjkñl") KeyCap("${c.uppercaseChar()}", Modifier.weight(1f)) { letter(c) } }
                    KeyboardRow {
                        for (c in "nm,.") KeyCap("${c.uppercaseChar()}", Modifier.weight(1f)) { letter(c) }
                        KeyCap("⌫", Modifier.weight(1f)) {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            onKey(KeyPress.special(SpecialKey.BACKSPACE))
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyCap("Espacio", Modifier.weight(2.5f)) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onKey(KeyPress.char(' '))
                }
                KeyCap("↵ Entrar", Modifier.weight(1f), highlighted = true) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onKey(KeyPress.special(SpecialKey.ENTER))
                }
            }
        }
    }
}

@Composable
private fun KeyboardRow(content: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun KeyCap(
    label: String,
    modifier: Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 1.dp,
        modifier = modifier.heightIn(min = 32.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
