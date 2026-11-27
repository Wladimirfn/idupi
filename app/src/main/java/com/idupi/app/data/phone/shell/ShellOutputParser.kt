package com.idupi.app.data.phone.shell

/**
 * Result of one command run over the adb `shell:` service.
 *
 * The adb `shell:` stream carries stdout and stderr but no exit status, so the
 * client appends an echo marker to the command and reads the code back from
 * the stream. [exitCode] is -1 when the marker never arrived (the process was
 * killed, the stream died, or output was truncated).
 */
data class ShellResult(
    val exitCode: Int,
    val output: String,
)

/**
 * Splits the marker out of a raw `shell:` stream read.
 *
 * The legacy `shell:` service runs the command on a pseudo-terminal, so lines
 * usually end in CRLF, while a raw stream ends in LF. The parser therefore
 * accepts either, removes the one line break that separates the command
 * output from the marker, and keeps every other byte as [ShellResult.output].
 */
object ShellOutputParser {

    /** Echoed after the command as `echo __IDUPI_EXIT__$?`. */
    const val EXIT_MARKER = "__IDUPI_EXIT__"

    private const val UNKNOWN_EXIT_CODE = -1
    private const val MAX_EXIT_DIGITS = 3

    fun parse(raw: String): ShellResult {
        val markerIndex = raw.lastIndexOf(EXIT_MARKER)
        if (markerIndex < 0) return ShellResult(UNKNOWN_EXIT_CODE, raw)

        val codeText = raw.substring(markerIndex + EXIT_MARKER.length).trimEnd('\r', '\n')
        if (codeText.isEmpty() || codeText.length > MAX_EXIT_DIGITS || codeText.any { it !in '0'..'9' }) {
            return ShellResult(UNKNOWN_EXIT_CODE, raw)
        }
        val exitCode = codeText.toIntOrNull() ?: return ShellResult(UNKNOWN_EXIT_CODE, raw)
        return ShellResult(exitCode, stripOneLineBreak(raw.substring(0, markerIndex)))
    }

    /**
     * Drops exactly one trailing line break, the one the shell wrote after the
     * last output line. Additional blank lines are part of the output and stay.
     */
    private fun stripOneLineBreak(text: String): String = when {
        text.endsWith("\r\n") -> text.dropLast(2)
        text.endsWith("\n") || text.endsWith("\r") -> text.dropLast(1)
        else -> text
    }
}
