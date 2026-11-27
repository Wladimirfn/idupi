package com.idupi.app.data.phone.shell

/**
 * Fixed command lines the in-app adb session sends while it bootstraps the
 * shell helper (phone-shell T2/T3b).
 *
 * These are the only commands the client builds itself. Anything richer goes
 * through the helper's typed allowlist, never through a free-form shell from
 * the server.
 */
object ShellCommands {

    /** Starts the helper's instrumentation bridge as uid 2000. */
    fun instrumentBridge(): String =
        "am instrument -w -r com.idupi.shell/.ShellBridgeInstrumentation"

    /**
     * Installs or updates the embedded helper APK. `-r` replaces an existing
     * install, `-t` allows the test-only flag the helper is signed with.
     */
    fun installHelper(apkPath: String): String {
        require(apkPath.isNotBlank()) { "apk path must not be blank" }
        return "pm install -r -t ${quote(apkPath)}"
    }

    /** Persists shell's WRITE_SECURE_SETTINGS grant for the app across reboots. */
    fun grantWriteSecureSettings(): String =
        "pm grant com.idupi.app android.permission.WRITE_SECURE_SETTINGS"

    /**
     * Starts the helper bridge detached (phone-shell T3b).
     *
     * `am instrument -w` never returns by design: the instrumentation keeps
     * running in the helper process after `am` detaches. Running it through a
     * blocking `shell:` exec would hit the exec timeout every time, so the
     * command backgrounds itself with `setsid`, and the outer redirects keep
     * the adb stream from waiting on the still-open child stdout.
     */
    fun startBridgeDetached(): String =
        "setsid sh -c 'am instrument -w -r com.idupi.shell/.ShellBridgeInstrumentation > /dev/null 2>&1' < /dev/null > /dev/null 2>&1 &"

    /**
     * Streams an APK over stdin into `cmd package install`, the same service
     * `adb install` uses. `-S` tells the package manager exactly how many bytes
     * to read from the stream before it commits.
     */
    fun installStreamed(sizeBytes: Long): String {
        require(sizeBytes > 0) { "apk size must be positive" }
        return "cmd package install -r -t -S $sizeBytes"
    }

    /**
     * Appends the exit-status probe used by [AdbShellClient.exec]. The marker
     * is parsed back by [ShellOutputParser].
     *
     * A command ending in `&` cannot be followed by `;` (that is a shell
     * syntax error), so the probe is separated by a space there: the marker
     * still runs after the background job is launched.
     */
    fun withExitStatus(command: String): String {
        require(command.isNotBlank()) { "command must not be blank" }
        val separator = if (command.trimEnd().endsWith("&")) " " else "; "
        return "$command$separator" + "echo ${ShellOutputParser.EXIT_MARKER}\$?"
    }

    /**
     * Quotes one argument for the device shell.
     *
     * POSIX single quotes make everything literal; an embedded single quote is
     * closed, escaped and reopened (`'\''`), so a path can never break out of
     * the argument. Newlines, carriage returns and NUL are rejected because
     * they would split the adb service request itself.
     */
    fun quote(value: String): String {
        require(value.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "value must not contain line breaks or NUL"
        }
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
