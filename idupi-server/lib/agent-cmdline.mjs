// Argument builders for the agent CLIs (security hardening).
//
// These CLIs are spawned WITHOUT a shell, so arguments travel as an argv
// ARRAY: no quoting layer exists to break out of, and metacharacters in a
// chat message are inert data. Resolving the real executable (npm .cmd shims
// cannot be spawned without a shell since Node's CVE hardening) happens in
// index.mjs next to its sibling OpenCode resolver.

/**
 * Claude CLI argv. The message rides as ONE JSON-string element -- newlines
 * and quotes are just characters to the array-based spawn.
 */
export function claudeArgs({ modelId = "", sessionId = "", isNewSession = false, message = "" }) {
    const args = [];
    if (modelId) args.push("--model", modelId);
    if (sessionId) args.push(...(isNewSession ? ["--session-id", sessionId] : ["-r", sessionId]));
    args.push(
        "--output-format", "stream-json",
        "--verbose",
        "--permission-mode", "bypassPermissions",
        "-p", JSON.stringify(message),
    );
    return args;
}

/**
 * Normalize an OpenCode model id into its final `provider/model` form.
 *
 * `opencode run -m` expects "provider/model" (`opencode run --help`), and the
 * catalog ids returned by `opencode models` ALREADY carry the provider (e.g.
 * `opencode/muse-spark-1.3-contributor-free`). Prefixing the provider again
 * onto such an id builds `opencode/opencode/...`, which OpenCode cannot
 * resolve — and spawning into that invalid state was what left the CLI stuck
 * waiting. Rule: when the model already contains '/', it is the complete id
 * and the provider is NEVER re-prefixed; a bare model gets the provider
 * prefix only when one is known.
 *
 * Pure function: no I/O, no logging.
 *
 * @param {string} model - raw model id from the UI/catalog
 * @param {string} [provider] - provider to prefix for a bare model
 * @returns {string} final provider/model id ("" when no model)
 */
export function normalizeOpenCodeModel(model, provider = "") {
    if (!model) return "";
    const trimmed = String(model).trim();
    if (!trimmed) return "";
    if (trimmed.includes("/")) return trimmed;
    return provider ? `${provider}/${trimmed}` : trimmed;
}

/** OpenCode CLI argv. Same array principle: data never becomes syntax. */
export function openCodeArgs({ model = "", provider = "", sessionId = "", message = "" }) {
    const args = ["run", "--format", "json", "--auto"];
    if (model) {
        args.push("-m", normalizeOpenCodeModel(model, provider));
    }
    if (sessionId) args.push("-s", sessionId);
    args.push(message);
    return args;
}
