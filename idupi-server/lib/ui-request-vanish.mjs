// ============================================================================
// idupi-server/lib/ui-request-vanish.mjs
//
// R4 vanish-abort handler extracted from index.mjs (opencode-serve-sidecar
// remediation). Mirrors the same pattern as lib/ui-request-expiry.mjs: the
// production wiring in runOpenCodeCli and the opencode-sidecar test suite
// now share ONE implementation. The previous test-local mirror
// (wireVanishAbort in opencode-sidecar.test.mjs:967) re-implemented the
// production handler and was a drift layer — its own comment admitted it
// "Mirrors the runOpenCodeCli.onRemoved wiring in index.mjs". An inline
// copy IS the drift layer; an imported helper is not.
//
// Contract (per the opencode-permission-sidecar spec, "Vanish-Abort for
// Dead Turns" scenario):
//
//   1) Resolve the registry entry from the engine-side requestId via the
//      engineToRegistry map. A `permission.removed` for an id the run
//      never tracked (engine cleanup noise) MUST be ignored.
//
//   2) D7 snapshot fallback: consult listPendingPermissions() on the
//      sidecar. If the engine STILL reports the id, treat the removed
//      frame as a delayed cleanup — the abort is DEFERRED. The engine
//      will resolve the permission on its own and the registry's 120s
//      timer will expire the entry normally.
//
//   3) Truly vanished: force-expire the registry entry, drop the sidecar
//      writer so a late reply cannot resurrect the request (terminality
//      contract — 5.3 threat matrix), kill the run child tree via
//      taskkill so the turn aborts now (NOT at the 300s
//      AGENT_CLI_TIMEOUT_MS backstop), and publish BOTH a
//      UI_REQUEST_RESOLVED (so the chat sees the cancelled card) AND a
//      MESSAGE_END (so the user gets the Spanish abort notice).
//
// Threading model: the function returns synchronously after the early-
// return guards; the abort itself happens in a fire-and-forget async IIFE
// because the snapshot fallback is an HTTP round-trip. Callers MUST NOT
// assume the abort has completed by the time the function returns — the
// production run is async and the child-kill races on purpose.
// ============================================================================

/**
 * Handle a `permission.removed` SSE event from the OpenCode sidecar.
 * All side effects (registry expire, sidecar-writer clear, taskkill,
 * publishChatEvent) are delegated to the supplied dependency bag so the
 * test suite can stub them and assert the exact contract.
 *
 * @param {object} args
 * @param {{ requestId: string }} args.entry - The SSE frame payload the
 *   sidecar's _routeFrame decoded for permission.removed.
 * @param {object} args.sidecar - The OpenCodeSidecar instance whose
 *   `listPendingPermissions()` is consulted as the snapshot fallback.
 *   May be null (no snapshot path available — treated as snapshot-
 *   unavailable and the abort still fires).
 * @param {Map<string,string>} args.engineToRegistry - Engine-side
 *   requestId → registry-side requestId map, owned by the run.
 * @param {{ expire: (requestId: string) => unknown }} args.uiRequestRegistry -
 *   The PendingUiRequestRegistry the sidecar subscription writes to.
 *   Only `.expire()` is required by this helper.
 * @param {{ pid: number|undefined }} args.child - The run child process
 *   the engine-to-registry map was tracking. Used to taskkill the tree.
 * @param {(requestId: string) => void} args.clearUiRequestSidecarWriter -
 *   Writer-cleanup seam (drops the per-requestId writer so a late POST
 *   cannot resurrect the request).
 * @param {(cmd: string, args: string[], cb: (err?: Error|null) => void) => any}
 *   args.execFile - node:child_process.execFile. Injected so the suite
 *   can observe the taskkill argv exactly.
 * @param {(eventName: string, payload: object) => void} args.publishChatEvent -
 *   SSE publisher from chat-events.mjs.
 * @param {(engine: string) => string|undefined} args.currentActivitySession -
 *   Returns the active session id for the given engine.
 * @param {object} args.console - console (or stub) for the warn/log lines.
 * @param {object} args.CHAT_EVENTS - The event-name constants map; only
 *   `UI_REQUEST_RESOLVED` and `MESSAGE_END` are referenced.
 * @returns {{ registryRid: string|undefined, deferred: boolean }}
 *   Synchronous return. `registryRid` is the resolved registry id (or
 *   undefined when the engine id was unknown — early return). `deferred`
 *   is always false from the synchronous return; the abort fires
 *   asynchronously after the snapshot lookup.
 */
export function applyVanishAbort({
    entry,
    sidecar,
    engineToRegistry,
    uiRequestRegistry,
    child,
    clearUiRequestSidecarWriter,
    execFile,
    publishChatEvent,
    currentActivitySession,
    console,
    CHAT_EVENTS,
}) {
    // Guard 1: engine id we never tracked → engine cleanup noise. The
    // spec mandates "MUST NOT abort anything because there is nothing
    // to abort" (REM/R4 third contract branch).
    const registryRid = engineToRegistry && engineToRegistry.get
        ? engineToRegistry.get(entry.requestId)
        : undefined;
    if (!registryRid) {
        console.warn(
            `[opencode-sidecar] permission.removed for unknown engine requestId=${entry.requestId} — ignoring`,
        );
        return { registryRid: undefined, deferred: false, ignored: true };
    }

    // Force-expire + clear-writer + taskkill + publish. Encapsulated so
    // the async IIFE below can call it once the snapshot confirms the
    // permission is truly gone.
    const abortTurn = (reason) => {
        // 1) Force-expire the registry entry so the chat session sees a
        //    terminal resolution (cancelled message + dropped card).
        try {
            uiRequestRegistry.expire(registryRid);
        } catch (err) {
            console.warn(`[opencode-sidecar] expire after vanish failed: ${err?.message || err}`);
        }
        // 2) Drop the sidecar writer so a late reply CANNOT resurrect
        //    the request after the engine has been killed (terminality
        //    contract — 5.3 threat matrix).
        try { clearUiRequestSidecarWriter(registryRid); } catch {}
        // 3) Kill the run child tree so the turn aborts NOW, not at the
        //    300s AGENT_CLI_TIMEOUT_MS backstop.
        try {
            execFile("taskkill", ["/F", "/T", "/PID", String(child.pid)], () => {});
        } catch (err) {
            console.warn(`[opencode-sidecar] taskkill after vanish failed: ${err?.message || err}`);
        }
        // 4) Publish BOTH chat events so the UI:
        //    a) drops the dialog (UI_REQUEST_RESOLVED with cancelled resolution)
        //    b) shows the user-facing Spanish abort notice (MESSAGE_END).
        //    The two-frame emit matches the spec's terminality contract:
        //    the card is dropped AND the user sees WHY.
        publishChatEvent(CHAT_EVENTS.UI_REQUEST_RESOLVED, {
            requestId: registryRid,
            sessionId: currentActivitySession("opencode"),
            engine: "opencode",
            resolution: "cancelled",
            value: { cancelled: true },
        });
        publishChatEvent(CHAT_EVENTS.MESSAGE_END, {
            text: `⚠️ Permiso de OpenCode desapareció (${reason}); turno abortado.`,
        });
        console.warn(
            `[opencode-sidecar] VANISH-ABORT engine.requestId=${entry.requestId} ` +
            `registry.requestId=${registryRid} reason=${reason}`,
        );
    };

    // D7 snapshot fallback: confirm via the engine's pending permission
    // list before we kill anything. If the engine still reports the id,
    // treat the removed frame as a delayed cleanup — the abort is
    // deferred and the registry's 120s timer will expire the entry
    // normally. This is fire-and-forget: the snapshot round-trip is an
    // HTTP GET that may run on a dead sidecar, hence the try/catch.
    (async () => {
        let snapshot = null;
        try {
            if (sidecar && typeof sidecar.listPendingPermissions === "function") {
                snapshot = await sidecar.listPendingPermissions();
            }
        } catch (err) {
            console.warn(
                `[opencode-sidecar] listPendingPermissions failed during vanish check: ${err?.message || err}`,
            );
        }
        const stillPending = Array.isArray(snapshot)
            && snapshot.some((p) => {
                const id = (p && typeof p === "object") ? (p.id || p.requestID) : null;
                return id === entry.requestId;
            });
        if (stillPending) {
            console.log(
                `[opencode-sidecar] permission.removed ${entry.requestId} still in snapshot; deferring abort`,
            );
            return;
        }
        abortTurn(snapshot == null ? "snapshot-unavailable" : "not-in-snapshot");
    })();

    return { registryRid, deferred: false };
}