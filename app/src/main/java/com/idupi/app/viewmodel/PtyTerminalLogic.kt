package com.idupi.app.viewmodel

import com.idupi.app.domain.model.PtyInfo

/**
 * Connection lifecycle shown by the terminal screen's state chip.
 *
 * EXITED is a clean end (the shell on the PC finished, [PtyTerminalUiState.exitCode]
 * carries its code); ERROR is a failure the user has to act on (bad token,
 * unreachable server, PTY refused).
 */
enum class PtyConnectionState {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    EXITED,
    ERROR
}

/** Everything the interactive terminal screen renders, in one snapshot. */
data class PtyTerminalUiState(
    val state: PtyConnectionState = PtyConnectionState.CONNECTING,
    val terminalId: String? = null,
    val pty: PtyInfo? = null,
    val exitCode: Int? = null,
    val error: String? = null
)

/** Events the screen reacts to once (navigation), as opposed to state. */
sealed interface PtyTerminalEvent {
    /** The user killed the terminal (or asked to close a dead one): leave. */
    data object Closed : PtyTerminalEvent
}

/** Reconnect delays: 500 ms doubling to a 4 s ceiling. Never unbounded growth. */
val PTY_RECONNECT_BACKOFF_MS: List<Long> = listOf(500L, 1_000L, 2_000L, 4_000L)

fun ptyReconnectDelay(attempt: Int): Long =
    PTY_RECONNECT_BACKOFF_MS[attempt.coerceIn(0, PTY_RECONNECT_BACKOFF_MS.lastIndex)]

/** What to do after one stream attempt ends (server end, socket drop, HTTP). */
sealed interface PtyStreamOutcome {
    data class Reconnect(val delayMs: Long) : PtyStreamOutcome
    data class Exited(val exitCode: Int?) : PtyStreamOutcome
    data class Failed(val message: String) : PtyStreamOutcome
}

/**
 * Pure reconnect decision shared by the ViewModel and its unit tests.
 *
 * Rules:
 *  - 401/403: the token is wrong, retrying cannot fix it -> Failed.
 *  - 404 on the stream: the PTY is gone -> Exited with no code.
 *  - The liveness check itself failed ([ptys] null): we cannot prove the
 *    terminal died, so reconnect with backoff instead of inventing an exit.
 *  - Terminal absent from the server's list: it was killed or the server
 *    restarted -> Exited.
 *  - Still alive: reconnect (the response ended because of backpressure or a
 *    transient drop; the server replays its ring buffer on reconnect).
 *  - Not alive: Exited with the reported exit code.
 */
fun decideAfterStreamEnd(
    id: String,
    attempt: Int,
    httpStatus: Int? = null,
    ptys: List<PtyInfo>? = null,
    failure: String? = null
): PtyStreamOutcome {
    if (httpStatus == 401 || httpStatus == 403) {
        return PtyStreamOutcome.Failed(failure ?: "Token inválido o ausente.")
    }
    if (httpStatus == 404) return PtyStreamOutcome.Exited(null)
    if (ptys == null) return PtyStreamOutcome.Reconnect(ptyReconnectDelay(attempt))
    val info = ptys.firstOrNull { it.id == id } ?: return PtyStreamOutcome.Exited(null)
    if (info.alive) return PtyStreamOutcome.Reconnect(ptyReconnectDelay(attempt))
    return PtyStreamOutcome.Exited(info.exitCode)
}

/**
 * Order-preserving coalescer for keystrokes. Everything offered before a
 * [drain] leaves as ONE `POST /pty/{id}/input`, in arrival order -- the wire
 * calls stay few without ever reordering characters (a terminal that receives
 * "ls" as "sl" is worse than a few more requests).
 */
class PtyInputQueue {
    private val pending = StringBuilder()

    fun offer(chunk: String) {
        if (chunk.isNotEmpty()) pending.append(chunk)
    }

    fun isEmpty(): Boolean = pending.isEmpty()

    fun drain(): String {
        if (pending.isEmpty()) return ""
        val out = pending.toString()
        pending.setLength(0)
        return out
    }
}

/**
 * Maps a character typed with Ctrl armed to its C0 control code.
 *
 * Mirrors `controlCode()` in `assets/terminal/terminal.js` so the browser
 * transform is pinned by JVM unit tests (JS itself is not unit-testable here):
 * a-z (case-insensitive) and @ [ \ ] ^ _ map to `code & 0x1f`; space maps to
 * NUL; anything else returns null (no conversion).
 */
fun ptyControlCode(char: Char): Char? {
    val lower = char.lowercaseChar()
    return when {
        lower in 'a'..'z' -> (lower.code and 0x1f).toChar()
        lower == '@' || lower == '[' || lower == '\\' ||
            lower == ']' || lower == '^' || lower == '_' -> (lower.code and 0x1f).toChar()
        char == ' ' -> '\u0000'
        else -> null
    }
}

/**
 * Applies the one-shot modifier transform to one input string, mirroring the
 * JS `applyModifiers()`: Ctrl only converts a single character (a paste is
 * never rewritten), Alt always prefixes ESC.
 */
fun applyPtyModifiers(data: String, ctrl: Boolean, alt: Boolean): String {
    if (data.isEmpty()) return data
    var out = if (ctrl && data.length == 1) {
        ptyControlCode(data[0])?.toString() ?: data
    } else data
    if (alt) out = "\u001b$out"
    return out
}

/** Hard cap for one `/pty/{id}/input` body on the server, with a margin. */
const val PTY_MAX_INPUT_BYTES: Int = 200 * 1024

/**
 * Splits one coalesced batch so no part exceeds [maxBytes] of UTF-8 -- the
 * server rejects anything over 256 KiB with 413. Splits only at code-point
 * boundaries, so a large paste arrives intact instead of being dropped whole.
 */
fun splitPtyInput(text: String, maxBytes: Int = PTY_MAX_INPUT_BYTES): List<String> {
    if (text.isEmpty()) return emptyList()
    if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(text)
    val parts = ArrayList<String>()
    var start = 0
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val codePointBytes = String(Character.toChars(codePoint))
            .toByteArray(Charsets.UTF_8).size
        if (bytes + codePointBytes > maxBytes && index > start) {
            parts += text.substring(start, index)
            start = index
            bytes = 0
        }
        bytes += codePointBytes
        index += Character.charCount(codePoint)
    }
    if (start < text.length) parts += text.substring(start)
    return parts
}
