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
     * Appends the exit-status probe used by [AdbShellClient.exec]. The marker
     * is parsed back by [ShellOutputParser].
     */
    fun withExitStatus(command: String): String {
        require(command.isNotBlank()) { "command must not be blank" }
        return "$command; echo ${ShellOutputParser.EXIT_MARKER}\$?"
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
