package com.idupi.app.data.phone.control

import com.idupi.app.data.phone.shell.summary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId

/**
 * One executed command, as Ajustes shows it (phone-agent T3).
 *
 * Privacy rule: [target] is a short description, never payload. A `type`
 * command records only its length, and a `screen` command records only
 * "pantalla" — the dump and the typed text never reach memory or the UI.
 */
data class PhoneCommandLogEntry(
    val atMillis: Long,
    val type: String,
    val target: String,
    val ok: Boolean,
    val error: String? = null,
)

/**
 * In-memory ring buffer of the last [MAX_ENTRIES] commands, newest first.
 *
 * It lives for the app process only on purpose: the command queue itself is
 * server-side and memory-only, and a recent-activity list is a debugging aid,
 * not an audit trail.
 */
object PhoneCommandLog {

    const val MAX_ENTRIES = 30

    private val lock = Any()
    private val _entries = MutableStateFlow<List<PhoneCommandLogEntry>>(emptyList())
    val entries: StateFlow<List<PhoneCommandLogEntry>> = _entries.asStateFlow()

    fun record(entry: PhoneCommandLogEntry) {
        synchronized(lock) {
            _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        }
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
        }
    }
}

/** Short, payload-free descriptions for the log and the recent-activity UI. */
object PhoneCommandSummaries {

    private const val MAX_TEXT_CHARS = 40

    fun typeLabel(command: PhoneCommand): String = when (command) {
        is PhoneCommand.Screen -> "screen"
        is PhoneCommand.Tap -> "tap"
        is PhoneCommand.LongPress -> "long_press"
        is PhoneCommand.TypeText -> "type"
        is PhoneCommand.Scroll -> "scroll"
        is PhoneCommand.Global -> "global"
        is PhoneCommand.OpenApp -> "open"
        is PhoneCommand.Settings -> "settings"
        is PhoneCommand.Shell -> "shell"
        is PhoneCommand.Wait -> "wait"
        is PhoneCommand.ReportStatus -> "status"
    }

    fun target(command: PhoneCommand): String = when (command) {
        is PhoneCommand.Screen -> "pantalla"
        is PhoneCommand.Tap -> targetDescription(command.target)
        is PhoneCommand.LongPress -> targetDescription(command.target)
        // Length only: the typed text is the owner's content.
        is PhoneCommand.TypeText -> "${command.text.length} caracteres"
        is PhoneCommand.Scroll -> command.direction.name.lowercase()
        is PhoneCommand.Global -> command.action.name.lowercase()
        is PhoneCommand.OpenApp -> command.app.take(MAX_TEXT_CHARS)
        is PhoneCommand.Settings -> command.packageName?.take(MAX_TEXT_CHARS)
            ?: command.screen.wire
        is PhoneCommand.Shell -> command.operation.summary().take(MAX_TEXT_CHARS)
        is PhoneCommand.Wait -> "${command.millis} ms"
        is PhoneCommand.ReportStatus -> "estado"
    }

    fun entry(
        command: PhoneCommand,
        ok: Boolean,
        error: String?,
        atMillis: Long = System.currentTimeMillis(),
    ): PhoneCommandLogEntry = PhoneCommandLogEntry(
        atMillis = atMillis,
        type = typeLabel(command),
        target = target(command),
        ok = ok,
        error = error,
    )

    private fun targetDescription(target: NodeTarget): String = when (target) {
        is NodeTarget.Text -> target.text.take(MAX_TEXT_CHARS)
        is NodeTarget.Id -> target.id.take(MAX_TEXT_CHARS)
        is NodeTarget.Point -> "${target.x},${target.y}"
    }
}

/** Clock label for the recent-activity rows (`12:34:56`). */
object PhoneCommandLogFormat {
    fun clock(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(atMillis).atZone(zone).toLocalTime().withNano(0).toString()
}
