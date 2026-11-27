// ============================================================================
// idupi-server/lib/inherited-session-env.mjs
//
// Drops per-session Claude Code variables the server may inherit when it is
// launched from inside a Claude Code session (e.g. an agent restarting it).
// Left in place, every Claude CLI the server spawns treats itself as a child
// session and disables transcript saving, so the owner's conversation is
// lost. Only session-scoped markers are removed; configuration such as
// CLAUDE_CODE_USE_BEDROCK, CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_* stays.
// ============================================================================

const SESSION_SCOPED_KEYS = new Set([
    "CLAUDECODE",
    "CLAUDE_PID",
    "CLAUDE_EFFORT",
    "CLAUDE_CODE_ENTRYPOINT",
    "CLAUDE_CODE_EXECPATH",
    "CLAUDE_CODE_SESSION_ID",
    "CLAUDE_CODE_SESSION_ATTENDED",
    "CLAUDE_CODE_DISABLE_BG_SHELL_PRESSURE_REAP",
]);

const SESSION_SCOPED_PREFIXES = ["CLAUDE_CODE_CHILD_", "CLAUDE_CODE_MESSAGING_"];

function isSessionScoped(key) {
    return SESSION_SCOPED_KEYS.has(key) || SESSION_SCOPED_PREFIXES.some((p) => key.startsWith(p));
}

/**
 * Removes inherited session-scoped Claude Code variables from `env` in place.
 * @param {Record<string, string | undefined>} env
 * @returns {string[]} the removed keys
 */
export function scrubInheritedSessionEnv(env) {
    const removed = Object.keys(env).filter(isSessionScoped);
    for (const key of removed) delete env[key];
    return removed;
}
