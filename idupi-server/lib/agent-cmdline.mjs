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

/** OpenCode CLI argv. Same array principle: data never becomes syntax. */
export function openCodeArgs({ model = "", provider = "", sessionId = "", message = "" }) {
    const args = ["run", "--format", "json", "--auto"];
    if (model) {
        // `opencode run -m` expects provider/model ("model to use in the format
        // of provider/model" -- `opencode run --help`). Catalog ids already
        // carry the provider; a bare model gets the provider prefix when known.
        args.push("-m", model.includes("/") ? model : (provider ? `${provider}/${model}` : model));
    }
    if (sessionId) args.push("-s", sessionId);
    args.push(message);
    return args;
}
