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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.idupi.domain.model.KeyPress
import com.example.idupi.domain.model.SpecialKey
import com.example.idupi.domain.model.snapBubbleToEdge
import kotlin.math.roundToInt

/**
 * Owner design for immersive fullscreen: a small translucent bubble the user
 * drags anywhere -- on release it SNAPS to the nearest vertical edge -- and
 * taps open a compact menu with the only things fullscreen ever needs:
 * the split keyboard, the mini pad, and the way out.
 */
@Composable
fun FloatingBubble(
    onKeyboard: () -> Unit,
    onPad: () -> Unit,
    onExitFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val bubbleSize = with(density) { 48.dp.toPx() }
        // Draggable area excludes the bubble itself so "flush against the
        // edge" is exactly x=0 or x=innerW.
        val innerW = (constraints.maxWidth - bubbleSize).coerceAtLeast(0f)
        val innerH = (constraints.maxHeight - bubbleSize).coerceAtLeast(0f)

        // Docked right, below mid-height by default. Constraints reset this
        // on rotation, which is the desired behaviour anyway.
        var pos by remember(innerW, innerH) {
            mutableStateOf(innerW to innerH * 0.55f)
        }
        var menuOpen by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .offset { IntOffset(pos.first.roundToInt(), pos.second.roundToInt()) }
                .size(48.dp)
                .pointerInput(innerW, innerH) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            pos =
                                ((pos.first + dragAmount.x).coerceIn(0f, innerW)) to
                                    ((pos.second + dragAmount.y).coerceIn(0f, innerH))
                        },
                        onDragEnd = {
                            val (x, y) = snapBubbleToEdge(pos.first, pos.second, innerW, innerH)
                            pos = x to y
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { menuOpen = !menuOpen })
                },
            contentAlignment = Alignment.Center,
        ) {
            // The bubble itself: a translucent circle with a soft dot.
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

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Teclado") },
                leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onKeyboard()
                },
            )
            DropdownMenuItem(
                text = { Text("Pad") },
                leadingIcon = { Icon(Icons.Filled.Mouse, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onPad()
                },
            )
            DropdownMenuItem(
                text = { Text("Salir pantalla completa") },
                leadingIcon = { Icon(Icons.Filled.FullscreenExit, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onExitFullscreen()
                },
            )
        }
    }
}

/**
 * The owner's ergonomic split keyboard: two thumb halves with a gap between
 * them, OPAQUE (his correction -- translucency made the picture behind look
 * broken), covering roughly the lower 60% of the fullscreen. Every tap
 * leaves the phone immediately as a realtime keystroke; what you typed shows
 * up on the remote screen itself, so no local echo buffer is needed.
 *
 * Layer one: letters + punctuation + space/enter/backspace/shift. A numeric
 * layer can come later if the owner wants it.
 */
@Composable
fun SplitKeyboard(
    onKey: (KeyPress) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shift by remember { mutableStateOf(false) }

    fun letter(c: Char) {
        onKey(KeyPress.char(if (shift) c.uppercaseChar() else c))
        shift = false
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Teclado",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cerrar teclado")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // LEFT THUMB HALF
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KeyboardRow { "qwert".forEach { KeyCap("${it.uppercaseChar()}", Modifier.weight(1f)) { letter(it) } } }
                    KeyboardRow { "asdfg".forEach { KeyCap("${it.uppercaseChar()}", Modifier.weight(1f)) { letter(it) } } }
                    KeyboardRow {
                        KeyCap("⇧", Modifier.weight(1f), highlighted = shift) { shift = !shift }
                        "zxcvb".forEach { KeyCap("${it.uppercaseChar()}", Modifier.weight(1f)) { letter(it) } }
                    }
                }
                // The thumb gap: nothing lives in the middle.
                Spacer(Modifier.width(4.dp))
                // RIGHT THUMB HALF
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KeyboardRow { "yuiop".forEach { KeyCap("${it.uppercaseChar()}", Modifier.weight(1f)) { letter(it) } } }
                    KeyboardRow { "hjkñl".forEach { KeyCap("${it.uppercaseChar()}", Modifier.weight(1f)) { letter(it) } } }
                    KeyboardRow {
                        "nm,.".forEach { KeyCap("${it.uppercaseChar()}", Modifier.weight(1f)) { letter(it) } }
                        KeyCap("⌫", Modifier.weight(1f)) { onKey(KeyPress.special(SpecialKey.BACKSPACE)) }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KeyCap("espacio", Modifier.weight(4f)) { onKey(KeyPress.char(' ')) }
                KeyCap("⏎", Modifier.weight(1f)) { onKey(KeyPress.special(SpecialKey.ENTER)) }
            }
        }
    }
}

@Composable
private fun KeyboardRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
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
        shape = RoundedCornerShape(7.dp),
        color = if (highlighted)
            MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.heightIn(min = 42.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(text = label, style = MaterialTheme.typography.bodySmall)
        }
    }
}
