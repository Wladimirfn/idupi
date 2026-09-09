// ============================================================================
// idupi-server/lib/opencode-auto-approve-header.mjs
//
// Pure header parser for the X-OpenCode-Auto-Approve toggle (R5 / opencode-
// serve-sidecar remediation). Lives in `lib/` so the chat route in
// index.mjs and the opencode-sidecar test suite can share one definition;
// the helper is the single source of truth for the wire-shape contract that
// RealIduPiClient.AutoApproveHeaderTest already pins on the Android side
// (false → "0", true → "1"; constant header name).
//
// Fail-closed default: anything other than the literal string "1" yields
// `false`. The toggle ON path is the explicit opt-in — missing header,
// "0", "true", "yes", whitespace-padded variants, or any unknown value
// all keep the sidecar-first path active. This is the secure default for
// a flag that controls whether permission prompts bypass user mediation.
// ============================================================================

/**
 * Wire name of the toggle header. The Android client
 * (app/src/main/java/.../RealIduPiClient.kt) emits this same constant;
 * Node's http.IncomingMessage.headers lowercases all keys, so the lookup
 * key MUST be lowercase to match.
 */
export const OPENCODE_AUTO_APPROVE_HEADER = "x-opencode-auto-approve";

/**
 * Parse the X-OpenCode-Auto-Approve request header into a strict boolean.
 *
 * @param {object|undefined|null} headers - Node's request headers object
 *   (http.IncomingMessage.headers). May be undefined on synthetic calls.
 * @returns {boolean} `true` only when the header is present AND its trimmed
 *   value is the exact ASCII digit `"1"`. Anything else — missing header,
 *   `"0"`, `"true"`, `"yes"`, empty string, whitespace, non-string, malformed
 *   headers object — yields `false` (sidecar-first path; the spec's fail-
 *   closed default).
 */
export function parseOpenCodeAutoApproveHeader(headers) {
    if (!headers || typeof headers !== "object") return false;
    const raw = headers[OPENCODE_AUTO_APPROVE_HEADER];
    if (typeof raw !== "string") return false;
    // The Android emitter is documented to send exactly "0" or "1"; we
    // still tolerate surrounding whitespace as a courtesy, but anything
    // other than the literal digit "1" is OFF.
    return raw.trim() === "1";
}