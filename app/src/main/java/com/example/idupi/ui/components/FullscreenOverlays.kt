package com.example.idupi.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
 * control row, 13sp labels -- the density of the HTML reference
 * (flex 1, 3px gaps, no vertical padding). ALL FOUR ROWS (Q-W-E-R-T,
 * A-S-D-F-G, Z-X-C-V, and the Espacio/⌫/⏎ control) are now uniformly
 * 27dp tall so the Z row is no longer visually "the fat one" (owner
 * feedback, Aug 27: "agrandar la 3 [fila] osea las 3 filas deben ser
 * del mismo tamaño, si considero donde esta el espacio deben caber en
 * 40%"). Gaps between letter rows are 2dp. Total keyboard height = 26
 * (header, includes 22dp tab bar) + 24 (preview bar with 1dp vertical
 * padding) + 6 (three 2dp letter gaps) + 27+27+27 (three letter rows) +
 * 27 (control) + 2 (top pad) = 166dp, just over the original 165dp
 * ceiling -- still inside the 40% strip at 24dp system bar / 100dp nav
 * bar / 56dp status bar of a 2400px-tall S24 Ultra.
 *
 * Hito 11: 3-mode switcher. The header now hosts an Abc | 123 | Fn
 * segmented control INSIDE its 26dp budget (title on the left, tabs in
 * the middle, close on the right). Each mode renders its own 3 letter
 * rows + 1 control row, all at the same 27dp height so the bottom row
 * of every mode is visually equal to its top row. The preview bar
 * persists across mode changes (it is owner echo, not mode-local). */
private val KeyboardBg = Color(0xFF2B2B2B)
private val KeyboardKeyBg = Color(0xFF3C3C3C)
private val KeyboardKeyText = Color(0xFFECECEC)
private val KeyboardKeyHighlight = Color(0xFF6366F1)
private val KeyboardPreviewBg = Color(0xFF1E1E1E)
private val KeyboardPreviewHint = Color(0xFF8A8A8A)

/** Three layouts the 40% strip keyboard can switch between (hito 11). */
internal enum class KeyboardMode { Abc, Num, Fn }

/**
 * One button on a row. A button either emits a printable char, a single
 * virtual-key (SpecialKey), or a custom VK code (for F-keys / arrows
 * / Home / End that the soft keyboard needs but [SpecialKey] does not
 * enumerate). [comboVk] is reserved for shortcuts like Ctrl+C: the
 * keycap fires the control VK down, then the char up -- a sequential
 * pair of [KeyPress] events the ViewModel streams to the wire
 * independently.
 */
internal data class KeyDef(
    val label: String,
    val char: Char? = null,
    val special: SpecialKey? = null,
    /** Raw VK code used when neither [char] nor [special] apply (F1-F12,
     *  ARROW_*, HOME, END, DELETE-supplementary, etc). */
    val vk: Int? = null,
    /** Modifier VK (e.g. VK_CONTROL=17) sent BEFORE [comboChar] to form a
     *  shortcut like Ctrl+C / Ctrl+V. null on a non-shortcut key. */
    val comboModifier: Int? = null,
    /** Printable char paired with [comboModifier]. null on a non-shortcut. */
    val comboChar: Char? = null,
    /** Weight inside its row (sum of weights across one row is the row's
     *  total). Most keys weigh 1; the spacebar on the Abc control row
     *  weighs 2. */
    val weight: Float = 1f,
)

/** Convenience for the Abc mode's letter rows: maps each char in [s] to a
 *  KeyDef whose label is the uppercase form (so the cap always reads
 *  "Q" not "q") and whose [KeyDef.char] is the lowercase form (so the
 *  shift toggle can re-uppercase on click). Non-letter chars (comma,
 *  period, ñ) keep their own char -- the cap will show them verbatim. */
private fun letters(s: String): List<KeyDef> =
    s.map { c -> KeyDef(label = c.uppercaseChar().toString(), char = c) }

/** VK codes referenced by Fn/Num modes but NOT enumerated in [SpecialKey]
 *  (the enum is intentionally tight; the soft keyboard only needs F-keys,
 *  Home/End/PageUp/PageDown and a couple of media controls to be useful
 *  in the 40% strip, so we inline the codes here instead of bloating the
 *  domain enum). */
private const val VK_F1 = 0x70
private const val VK_F2 = 0x71
private const val VK_F3 = 0x72
private const val VK_F4 = 0x73
private const val VK_F5 = 0x74
private const val VK_F6 = 0x75
private const val VK_F7 = 0x76
private const val VK_F8 = 0x77
private const val VK_F9 = 0x78
private const val VK_F10 = 0x79
private const val VK_F11 = 0x7A
private const val VK_F12 = 0x7B
private const val VK_HOME = 0x24
private const val VK_END = 0x23
private const val VK_PAGE_UP = 0x21
private const val VK_PAGE_DOWN = 0x22
private const val VK_INSERT = 0x2D
private const val VK_PRINTSCREEN = 0x2C
private const val VK_CONTROL = 0x11

/**
 * Row layout for one mode. Three left-half rows + three right-half rows
 * (mirrored split-thumb) + one bottom control row. Every key is a
 * [KeyDef]; the keyboard composable turns those into caps at 27dp each,
 * uniform across modes so the Z row in Abc is the same height as the
 * last numeric row in 123 and the last command row in Fn.
 */
internal data class ModeLayout(
    val leftRow1: List<KeyDef>,
    val rightRow1: List<KeyDef>,
    val leftRow2: List<KeyDef>,
    val rightRow2: List<KeyDef>,
    val leftRow3: List<KeyDef>,
    val rightRow3: List<KeyDef>,
    val controlRow: List<KeyDef>,
) {
    companion object {
        /** Default Abc mode (unchanged from the previous build, modulo the
         *  the new keycap system). Shift is applied at click time, not here,
         *  so [leftRow1] etc hold the lowercase letters. */
        val Abc = ModeLayout(
            leftRow1 = letters("qwert"),
            rightRow1 = letters("yuiop"),
            leftRow2 = letters("asdfg"),
            rightRow2 = letters("hjkñl"),
            leftRow3 = letters("zxcv"),
            rightRow3 = letters("bnm,."),
            controlRow = listOf(
                KeyDef("⇧", weight = 1f),
                KeyDef("Espacio", char = ' ', weight = 2f),
                KeyDef("⌫", special = SpecialKey.BACKSPACE, weight = 1f),
                KeyDef("⏎", special = SpecialKey.ENTER, weight = 1f),
            ),
        )

        /**
         * Numeric 123 mode (hito 11): numbers 1-0 across row 1, common
         * math/symbol operators + the 4 arrow keys on row 2, and a
         * punctuation row 3. Each half has exactly 5 keys so the row
         * width matches Abc (no per-mode width re-tuning -- the rows
         * stay uniform across modes).
         */
        val Num = ModeLayout(
            leftRow1 = listOf(
                KeyDef("1", char = '1'), KeyDef("2", char = '2'),
                KeyDef("3", char = '3'), KeyDef("4", char = '4'),
                KeyDef("5", char = '5'),
            ),
            rightRow1 = listOf(
                KeyDef("6", char = '6'), KeyDef("7", char = '7'),
                KeyDef("8", char = '8'), KeyDef("9", char = '9'),
                KeyDef("0", char = '0'),
            ),
            leftRow2 = listOf(
                KeyDef("-", char = '-'), KeyDef("/", char = '/'),
                KeyDef(":", char = ':'), KeyDef(";", char = ';'),
                KeyDef("(", char = '('),
            ),
            rightRow2 = listOf(
                KeyDef(")", char = ')'), KeyDef("←", special = SpecialKey.ARROW_LEFT),
                KeyDef("↓", special = SpecialKey.ARROW_DOWN),
                KeyDef("↑", special = SpecialKey.ARROW_UP),
                KeyDef("→", special = SpecialKey.ARROW_RIGHT),
            ),
            leftRow3 = listOf(
                KeyDef(",", char = ','), KeyDef(".", char = '.'),
                KeyDef("?", char = '?'), KeyDef("!", char = '!'),
                KeyDef("'", char = '\''),
            ),
            rightRow3 = listOf(
                KeyDef("\"", char = '"'), KeyDef("@", char = '@'),
                KeyDef("#", char = '#'), KeyDef("$", char = '$'),
                KeyDef("%", char = '%'),
            ),
            controlRow = listOf(
                KeyDef("⇧", weight = 1f),
                KeyDef("Espacio", char = ' ', weight = 2f),
                KeyDef("⌫", special = SpecialKey.BACKSPACE, weight = 1f),
                KeyDef("⏎", special = SpecialKey.ENTER, weight = 1f),
            ),
        )

        /**
         * Function / shortcuts mode (hito 11): F1-F6 left + F7-F12 right
         * across row 1, copy/paste/cut/print/delete/escape + tab on row 2,
         * and the navigation keys (Home/End/PgUp/PgDn/Insert) on row 3.
         * The two-character "Ctrl+C" / "Ctrl+V" / "Ctrl+X" labels are
         * rendered as plain text on the cap; the keycap emits the modifier
         * VK + the char as two sequential presses.
         */
        val Fn = ModeLayout(
            leftRow1 = listOf(
                KeyDef("F1", vk = VK_F1), KeyDef("F2", vk = VK_F2),
                KeyDef("F3", vk = VK_F3), KeyDef("F4", vk = VK_F4),
                KeyDef("F5", vk = VK_F5),
            ),
            rightRow1 = listOf(
                KeyDef("F6", vk = VK_F6), KeyDef("F7", vk = VK_F7),
                KeyDef("F8", vk = VK_F8), KeyDef("F9", vk = VK_F9),
                KeyDef("F10", vk = VK_F10),
            ),
            leftRow2 = listOf(
                KeyDef("F11", vk = VK_F11), KeyDef("F12", vk = VK_F12),
                KeyDef("Esc", special = SpecialKey.ESCAPE),
                KeyDef("Tab", special = SpecialKey.TAB),
                KeyDef("Supr", special = SpecialKey.DELETE),
            ),
            rightRow2 = listOf(
                KeyDef("Ctrl+C", comboModifier = VK_CONTROL, comboChar = 'c'),
                KeyDef("Ctrl+V", comboModifier = VK_CONTROL, comboChar = 'v'),
                KeyDef("Ctrl+X", comboModifier = VK_CONTROL, comboChar = 'x'),
                KeyDef("Impr", vk = VK_PRINTSCREEN),
                KeyDef("Ins", vk = VK_INSERT),
            ),
            leftRow3 = listOf(
                KeyDef("Home", vk = VK_HOME), KeyDef("End", vk = VK_END),
                KeyDef("PgUp", vk = VK_PAGE_UP), KeyDef("PgDn", vk = VK_PAGE_DOWN),
                KeyDef("⏎", special = SpecialKey.ENTER),
            ),
            rightRow3 = listOf(
                KeyDef("⌫", special = SpecialKey.BACKSPACE),
                KeyDef("←", special = SpecialKey.ARROW_LEFT),
                KeyDef("↓", special = SpecialKey.ARROW_DOWN),
                KeyDef("↑", special = SpecialKey.ARROW_UP),
                KeyDef("→", special = SpecialKey.ARROW_RIGHT),
            ),
            controlRow = listOf(
                KeyDef("Espacio", char = ' ', weight = 3f),
                KeyDef("⌫", special = SpecialKey.BACKSPACE, weight = 1f),
                KeyDef("⏎", special = SpecialKey.ENTER, weight = 1f),
            ),
        )
    }
}

@Composable
fun SplitKeyboard(
    onKey: (KeyPress) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    previewText: String = "",
) {
    var shift by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(KeyboardMode.Abc) }
    val haptics = LocalHapticFeedback.current
    fun emit(def: KeyDef) {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        when {
            // Ctrl+C / Ctrl+V / Ctrl+X style shortcut: stream the modifier
            // first, then the char. Each one is its own KeyPress so the
            // existing realtime path (sendKey) handles it the same as any
            // other keystroke. Two events, no special wire shape needed.
            def.comboModifier != null && def.comboChar != null -> {
                onKey(KeyPress(KeyPress.Kind.SPECIAL, def.comboModifier))
                onKey(KeyPress.char(def.comboChar))
            }
            def.char != null -> {
                val raw = if (mode == KeyboardMode.Abc) {
                    if (def.char.isLetter() && shift) def.char.uppercaseChar() else def.char
                } else def.char
                onKey(KeyPress.char(raw))
                if (mode == KeyboardMode.Abc) shift = false
            }
            def.special != null -> onKey(KeyPress.special(def.special))
            def.vk != null -> onKey(KeyPress(KeyPress.Kind.SPECIAL, def.vk))
        }
    }
    // The full mode->rows mapping. Computed once per recomposition; pure
    // data, so it does not need to be in a `remember` block (no allocation
    // hot path -- just a `when` on a 3-value enum).
    val layout = when (mode) {
        KeyboardMode.Abc -> ModeLayout.Abc
        KeyboardMode.Num -> ModeLayout.Num
        KeyboardMode.Fn -> ModeLayout.Fn
    }
    Surface(
        color = KeyboardBg,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: title (left) + 3-mode segmented control (centre) + close
            // (right). Total 26dp. The segmented control is 22dp tall so the
            // title and close -- both 16dp icons / 12sp text -- sit vertically
            // centred. A mode tap is a soft LongPress-style haptic so the user
            // feels the layout switch.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Teclado",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = KeyboardKeyText.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 4.dp, end = 6.dp),
                )
                ModeTabs(
                    mode = mode,
                    onMode = { newMode ->
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                        )
                        mode = newMode
                    },
                    modifier = Modifier.weight(1f),
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
            // too short. Budget per owner spec is 26 (header) + 24 (preview
            // bar with 1dp vertical padding) + 6 (three 2dp letter gaps) +
            // 27 + 27 + 27 (three uniform letter rows) + 27 (control) + 2
            // (top pad) = 166dp, at the owner ceiling. A longer echo
            // truncates with ellipsis so a runaway auto-repeat cannot blow
            // the bar up. Preview is mode-independent: switching from Abc
            // to Fn does NOT wipe it -- the user just pressed letters and
            // should still see them.
            Surface(
                color = KeyboardPreviewBg,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 1.dp),
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
            // Two halves with a 12dp thumb gap; rows inside are 2dp apart.
            // All three letter rows are uniformly 27dp. The previously
            // squished Z-row came from a parent Row(weight(1f)) shrinking
            // the columns when the host's weight(0.4f) returned less than
            // 166dp; the last row of each Column took the visual hit. We
            // now let the inner Row size to its content (sum of explicit
            // row heights) so no parent can ever clip the bottom row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    KeyboardRow(layout.leftRow1) { emit(it) }
                    KeyboardRow(layout.leftRow2) { emit(it) }
                    KeyboardRow(layout.leftRow3) { emit(it) }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    KeyboardRow(layout.rightRow1) { emit(it) }
                    KeyboardRow(layout.rightRow2) { emit(it) }
                    KeyboardRow(layout.rightRow3) { emit(it) }
                }
            }
            // Control row: width-spanning, same 27dp height as the letter
            // rows so all four rows line up. Shift / Espacio / ⌫ / ⏎ for
            // Abc and Num, Espacio / ⌫ / ⏎ for Fn (where arrows and Ctrl
            // combos already live on the main grid).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                layout.controlRow.forEach { def ->
                    KeyCap(
                        label = def.label,
                        modifier = Modifier.weight(def.weight),
                        highlighted = (def.label == "⇧" && shift) || (def.label == "⏎"),
                        onClick = { emit(def) },
                    )
                }
            }
        }
    }
}

/** Three-pill segmented control: Abc | 123 | Fn. 22dp tall, 11sp label,
 *  selected pill = [KeyboardKeyHighlight] (#6366F1), unselected =
 *  [KeyboardKeyBg] (#3C3C3C). Fits inside the 26dp header without
 *  raising it. */
@Composable
private fun ModeTabs(
    mode: KeyboardMode,
    onMode: (KeyboardMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = KeyboardKeyBg,
        shape = RoundedCornerShape(11.dp),
        modifier = modifier.height(22.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeTab(label = "Abc", selected = mode == KeyboardMode.Abc) { onMode(KeyboardMode.Abc) }
            ModeTab(label = "123", selected = mode == KeyboardMode.Num) { onMode(KeyboardMode.Num) }
            ModeTab(label = "Fn", selected = mode == KeyboardMode.Fn) { onMode(KeyboardMode.Fn) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) KeyboardKeyHighlight else Color.Transparent,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = KeyboardKeyText,
            )
        }
    }
}

@Composable
private fun KeyboardRow(
    keys: List<KeyDef>,
    onKey: (KeyDef) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(27.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        keys.forEach { def ->
            KeyCap(
                label = def.label,
                modifier = Modifier.weight(def.weight),
                onClick = { onKey(def) },
            )
        }
    }
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
