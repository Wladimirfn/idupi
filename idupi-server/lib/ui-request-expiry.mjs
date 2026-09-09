// ============================================================================
// idupi-server/lib/ui-request-expiry.mjs
//
// Per-engine expire-routing helper extracted from index.mjs so it can be
// exercised by the opencode-sidecar test suite without importing the
// 5000-line server monolith. The shared module replaces a test-local mirror
// that was drifting from production: a single source of truth means a
// future refactor of the routing rule fails the test on the FIRST run, not
// after a silent behaviour change ships.
//
// Contract (per the ui-request-selection spec's Terminality/Deadline/Fallback
// requirement and the opencode-permission-sidecar spec's "OpenCode expiry
// cancels" scenario):
//
//   engine === "opencode"
//     → sidecar writer receives `false` (reject the pending permission
//       through sidecar.reply(false) → 204 / 404 idempotent). The engine's
//       permission is rejected before the 300s AGENT_CLI_TIMEOUT_MS taskkill
//       rides in.
//
//   engine !== "opencode" (pi, claude, stdin-based engines)
//     → stdin writer receives `decision.value` (the registry's blanket
//       auto-approve for `select` or `cancelled:true` for confirm/input,
//       per buildAutoApproveDecision).
//
// Both branches clear the per-requestId writer after a successful dispatch
// so a late POST on the same requestId short-circuits to 404.
//
// Threading model: synchronous. Returns `{ ok, kind }` where `ok` is the
// underlying writer's synchronous return (true = delivery accepted) and
// `kind` is `"sidecar"` or `"stdin"` for the audit log.
// ============================================================================

/**
 * Route the registry's terminal decision to the right writer. Pure
 * function: no I/O, no side effects beyond the supplied writers.
 *
 * @param {object} args
 * @param {{ engine: string, requestId: string, method: string }} args.entry
 *   Registry entry whose token is being resolved. `entry.engine` is the
 *   authoritative selector.
 * @param {{ value: unknown, source: string }} args.decision
 *   Terminal decision from buildAutoApproveDecision. For opencode, the
 *   value is unused (the sidecar always sees `false`); for stdin engines,
 *   the value is what reaches the engine stdin as a JSON envelope.
 * @param {(value: unknown) => boolean} args.sidecarWriter
 *   sync writer that wraps sidecar.reply — typically the seam function
 *   returned by setUiRequestSidecarWriter.
 * @param {(value: unknown) => boolean} args.stdinWriter
 *   sync writer that wraps the engine stdin pipe.
 * @param {(requestId: string) => void} [args.clearSidecarWriter]
 *   Optional cleanup of the sidecar writer entry; called AFTER a successful
 *   sidecar dispatch so a late POST on the same requestId short-circuits.
 * @param {(requestId: string) => void} [args.clearStdinWriter]
 *   Optional cleanup of the stdin writer entry; called AFTER a successful
 *   stdin dispatch.
 * @returns {{ ok: boolean, kind: "sidecar" | "stdin" }}
 */
export function applyExpireRouting({
    entry,
    decision,
    sidecarWriter,
    stdinWriter,
    clearSidecarWriter,
    clearStdinWriter,
}) {
    if (!entry || typeof entry !== "object") {
        return { ok: false, kind: "stdin" };
    }
    if (entry.engine === "opencode") {
        // Per spec: expiry on OpenCode is ALWAYS a rejection, regardless of
        // the method or the registry's blanket-auto-approve decision. The
        // sidecar.reply(false) serializes to { reply: "reject" }, which the
        // engine maps to the canonical "deny" wire enum.
        const ok = typeof sidecarWriter === "function" ? sidecarWriter(false) === true : false;
        if (ok && typeof clearSidecarWriter === "function") {
            try { clearSidecarWriter(entry.requestId); } catch {}
        }
        return { ok, kind: "sidecar" };
    }
    // Stdin-based engines: pass the registry's decision value verbatim.
    const value = decision && Object.prototype.hasOwnProperty.call(decision, "value")
        ? decision.value
        : undefined;
    const ok = typeof stdinWriter === "function" ? stdinWriter(value) === true : false;
    if (ok && typeof clearStdinWriter === "function") {
        try { clearStdinWriter(entry.requestId); } catch {}
    }
    return { ok, kind: "stdin" };
}