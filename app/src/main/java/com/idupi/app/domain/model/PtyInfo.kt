package com.idupi.app.domain.model

import kotlinx.serialization.Serializable

/**
 * One interactive PTY terminal hosted on the PC (`/api/v1/pty` routes).
 *
 * Every field except [id] defaults so a wire-shape addition on the server can
 * never break deserialisation: the app's Json instance uses
 * `ignoreUnknownKeys`, and defaults cover missing keys.
 *
 * [alive] flips to `false` when the shell exits; [exitCode] carries the real
 * child exit code once the host process reports it. [error] is the host's
 * startup failure message (e.g. the helper could not spawn a shell).
 */
@Serializable
data class PtyInfo(
    val id: String = "",
    val pid: Int = 0,
    val cols: Int = 80,
    val rows: Int = 24,
    val cwd: String? = null,
    val shell: String? = null,
    val createdAt: String? = null,
    val alive: Boolean = true,
    val exitCode: Int? = null,
    val error: String? = null
)
