package com.example.idupi.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Compact split-thumb keyboard that MUST fit the 40% fullscreen strip with
 * NO scroll (owner spec): two 45% halves with a thumb gap, 3 letter rows + 1
 * control row, 27dp keys, 13sp labels -- the density of the HTML reference
 * (flex 1, 3px gaps, no vertical padding). Worst-case height on the smallest
 * landscape phone (360dp tall -> 144dp budget): 26 header + 3*27 letters +
 * 2*3 gaps + 27 control + 3 top pad = 143dp. */
private val KeyboardBg = Color(0xFF2B2B2B)
private val KeyboardKeyBg = Color(0xFF3C3C3C)
private val KeyboardKeyText = Color(0xFFECECEC)
private val KeyboardKeyHighlight = Color(0xFF6366F1)
private val KeyboardPreviewBg = Color(0xFF1E1E1E)
private val KeyboardPreviewHint = Color(0xFF8A8A8A)

@Composable
fun SplitKeyboard(
    onKey: (KeyPress) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    previewText: String = "",
) {
    var shift by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    fun letter(c: Char) {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        onKey(KeyPress.char(if (shift) c.uppercaseChar() else c))
        shift = false
    }
    Surface(
        color = KeyboardBg,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: one compact row (26dp) -- title + close, no extra spacer.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Teclado",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = KeyboardKeyText.copy(alpha = 0.75f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Cerrar teclado",
                        tint = KeyboardKeyText.copy(alpha = 0.75f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // Preview bar (owner request): a thin echo of what the user just
            // typed, sitting right above the keys. Lives inside the keyboard
            // surface so it scrolls with the panel if the 40% strip is ever
            // too short -- but at 22dp it never actually needs to: total
            // keyboard height 26 (header) + 22 (preview) + 3*27 (letter rows)
            // + 2*3 (gaps) + 27 (control) + 3 (top pad) = 165dp, the exact
            // ceiling the owner set. A longer echo truncates with ellipsis
            // so a runaway auto-repeat cannot blow the bar up.
            Surface(
                color = KeyboardPreviewBg,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = previewText.ifEmpty { "Escribiendo…" },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 12.sp,
                        fontWeight = if (previewText.isEmpty()) FontWeight.Normal else FontWeight.Medium,
                    ),
                    color = if (previewText.isEmpty()) KeyboardPreviewHint else KeyboardKeyText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .padding(horizontal = 6.dp),
                )
            }
            // Two 45% halves with a 12dp thumb gap; rows inside are 3dp apart.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    KeyboardRow { for (c in "qwert") KeyCap("${c.uppercaseChar()}", Modifier.weight(1f)) { letter(c) } }
                    KeyboardRow { for (c in "asdfg") KeyCap("${c.uppercaseChar()}", Modifier.weight(1f)) { letter(c) } }
                    KeyboardRow { for (c in "zxcv") KeyCap("${c.uppercaseChar()}", Modifier.weight(1f)) { letter(c) } }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    KeyboardRow { for (c in "yuiop") KeyCap("${c.uppercaseChar()}", Modifier.weight(1f)) { letter(c) } }
                    KeyboardRow { for (c in "hjkñl") KeyCap("${c.uppercaseChar()}", Modifier.weight(1f)) { letter(c) } }
                    KeyboardRow { for (c in "bnm,.") KeyCap("${c.uppercaseChar()}", Modifier.weight(1f)) { letter(c) } }
                }
            }
            // Control row: shift / wide space / backspace / enter, same 27dp.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                KeyCap("⇧", Modifier.weight(1f), highlighted = shift) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    shift = !shift
                }
                KeyCap("Espacio", Modifier.weight(2f)) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onKey(KeyPress.char(' '))
                }
                KeyCap("⌫", Modifier.weight(1f)) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onKey(KeyPress.special(SpecialKey.BACKSPACE))
                }
                KeyCap("⏎", Modifier.weight(1f), highlighted = true) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onKey(KeyPress.special(SpecialKey.ENTER))
                }
            }
        }
    }
}

@Composable
private fun KeyboardRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(27.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        content = content,
    )
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
        shape = RoundedCornerShape(6.dp),
        color = if (highlighted) KeyboardKeyHighlight else KeyboardKeyBg,
        modifier = modifier.height(27.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = KeyboardKeyText,
            )
        }
    }
}
