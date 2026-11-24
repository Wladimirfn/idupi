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
 *
 * [context] is the live context the server attaches to each LISTED terminal:
 * where it currently is and which agent CLI runs inside it. Older servers do
 * not send it at all, so it is nullable and every nested field has a default.
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
    val error: String? = null,
    val context: PtyContext? = null
)

/**
 * Live context of one terminal: where it is ([cwd], git [project] and
 * [branch]) and which agent CLI runs in it ([agent]).
 *
 * The server answers `null` for any piece it cannot resolve (helper could not
 * read the process, cwd outside a repository, no agent detected), so every
 * field is nullable with a default and the app renders fallbacks instead of
 * trusting the object to be complete.
 */
@Serializable
data class PtyContext(
    val cwd: String? = null,
    val project: String? = null,
    val branch: String? = null,
    val detached: Boolean = false,
    val agent: PtyAgent? = null
)

/**
 * The agent CLI detected inside a terminal. [id] is the wire key the app maps
 * to an accent colour (`claude`, `opencode`, `codex`, `cmdc`, `mcode`, `kimi`,
 * `qwen`, `gemini`, `pi`); [name] is the display label the server already
 * localised ("Claude Code", "OpenCode", ...) and [pid] its OS process.
 */
@Serializable
data class PtyAgent(
    val id: String = "",
    val name: String = "",
    val pid: Int = 0
)
