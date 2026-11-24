package com.idupi.app.domain.model

/**
 * Longest cwd tail a card keeps before the leading ellipsis. The card's inner
 * width on a ~412 dp phone fits roughly 45-50 characters at 10sp mono; 42
 * leaves room for the ellipsis and the padding without clipping the project
 * end of the path.
 */
private const val PATH_TAIL_CHARS = 42

/** Label shown when the server reports no terminal agent (a plain shell). */
const val SHELL_AGENT_LABEL = "Shell"

/** Label shown when an agent object arrived without a usable name or id. */
private const val UNKNOWN_AGENT_LABEL = "Agente"

/** Suffix that marks a detached-HEAD branch chip (the "branch" is the sha). */
const val DETACHED_BRANCH_SUFFIX = "sin rama"

/** Title of the full-screen terminal when no project is known. */
const val TERMINAL_HEADER_FALLBACK_TITLE = "Terminal interactiva"

/**
 * Everything a terminal card shows about one PTY, derived once from the wire
 * model so the fallbacks are unit-testable without Compose:
 *
 *  - [title]: the project name; else the last segment of the live (or launch)
 *    cwd; else the shell executable name.
 *  - [pathLine]: the live cwd, truncated FROM THE START so the project end of
 *    a long path stays visible (`…\\AndroidStudioProjects\\IDUPI`).
 *  - [agentLabel]/[agentId]: the running agent CLI; a plain shell answers
 *    "Shell" with a null id, which the screen renders muted.
 *  - [branchLabel]/[detached]: the branch chip text (null when there is no
 *    git branch); [detached] asks for the distinct detached-HEAD style.
 */
data class PtyDisplay(
    val title: String,
    val pathLine: String?,
    val agentLabel: String,
    val agentId: String?,
    val branchLabel: String?,
    val detached: Boolean
)

/** Title and subtitle of the full-screen terminal's top bar. */
data class PtyHeaderDisplay(val title: String, val subtitle: String)

/** One card's display strings, with every server-optional piece falling back. */
fun ptyDisplay(pty: PtyInfo): PtyDisplay {
    val context = pty.context
    val liveCwd = context?.cwd?.takeIf { it.isNotBlank() }
    val launchCwd = pty.cwd?.takeIf { it.isNotBlank() }
    val agent = context?.agent
    val agentName = agent?.name?.takeIf { it.isNotBlank() }
    val agentId = agent?.id?.takeIf { it.isNotBlank() }
    return PtyDisplay(
        title = context?.project?.takeIf { it.isNotBlank() }
            ?: lastPathSegment(liveCwd)
            ?: lastPathSegment(launchCwd)
            ?: shellNameOf(pty),
        pathLine = (liveCwd ?: launchCwd)?.let { compactPath(it) },
        agentLabel = when {
            agent == null -> SHELL_AGENT_LABEL
            agentName != null -> agentName
            agentId != null -> agentId
            else -> UNKNOWN_AGENT_LABEL
        },
        agentId = if (agent == null) null else agentId ?: agentName,
        branchLabel = branchLabelOf(context),
        detached = context?.detached == true
    )
}

/**
 * Top bar of the full-screen terminal: the project as title and
 * `branch · agent` as subtitle, falling back to the shell name / terminal id
 * when the server has no context for it (older server, not yet sampled).
 */
fun ptyHeaderDisplay(pty: PtyInfo?, fallbackId: String?): PtyHeaderDisplay {
    val context = pty?.context
    val title = context?.project?.takeIf { it.isNotBlank() } ?: TERMINAL_HEADER_FALLBACK_TITLE
    val subtitleParts = listOfNotNull(
        branchLabelOf(context),
        context?.agent?.name?.takeIf { it.isNotBlank() }
    )
    val subtitle = subtitleParts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: pty?.shell?.takeIf { it.isNotBlank() }
        ?: fallbackId?.takeIf { it.isNotBlank() }
        ?: "Nueva terminal"
    return PtyHeaderDisplay(title = title, subtitle = subtitle)
}

/** Branch chip text (`main`, `abc1234 · sin rama`), or null when unknown. */
fun branchLabelOf(context: PtyContext?): String? {
    val branch = context?.branch?.takeIf { it.isNotBlank() } ?: return null
    return if (context.detached) "$branch · $DETACHED_BRANCH_SUFFIX" else branch
}

/**
 * The shell's executable name (the terminal's fallback title): the first token
 * of the command, without its directory. "Terminal" when the server did not
 * report the shell.
 */
fun shellNameOf(pty: PtyInfo): String {
    val executable = pty.shell
        ?.substringBefore(' ')
        ?.substringAfterLast('\\')
        ?.substringAfterLast('/')
        ?.ifBlank { null }
    return executable ?: "Terminal"
}

/**
 * Keeps the tail of a path so the project end stays visible: a long path is
 * cut at a separator boundary and prefixed with "…". Short paths pass through.
 * [maxChars] is the total budget, ellipsis included.
 */
fun compactPath(path: String, maxChars: Int = PATH_TAIL_CHARS): String {
    val text = path.trim()
    if (text.length <= maxChars || maxChars < 2) return text
    val tail = text.takeLast(maxChars - 1)
    // Prefer a separator so no half folder name is shown: if the cut lands
    // inside a segment, drop that partial head segment instead.
    val cut = tail.indexOfFirst { it == '\\' || it == '/' }
    val visible = if (cut in 1 until tail.lastIndex) tail.substring(cut) else tail
    return "…$visible"
}

/** Last folder of a Windows / POSIX path, or null when there is none. */
private fun lastPathSegment(path: String?): String? {
    val text = path?.trim()?.trimEnd('\\', '/') ?: return null
    if (text.isEmpty()) return null
    return text.split('\\', '/').lastOrNull { it.isNotEmpty() }
}
