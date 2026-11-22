// Every spawned child needs listeners on its stdio streams: Node throws an
// 'error' event that nobody listens to, so a write into a pipe whose reader
// just died (EPIPE) or a stream reset would take the whole server down.
// Spawn-level errors (ENOENT, EACCES) are emitted on the child itself and are
// handled at each call site, because each one settles different state.

/**
 * Attach logging 'error' listeners to child.stdin/stdout/stderr.
 * @template T
 * @param {T} child
 * @param {string} label
 * @param {{ warn: (msg: string) => void }} [log]
 * @returns {T}
 */
export function guardChildStreams(child, label, log = console) {
    for (const name of ["stdin", "stdout", "stderr"]) {
        const stream = child && child[name];
        if (stream && typeof stream.on === "function") {
            stream.on("error", (err) => {
                log.warn(`[${label}] ${name} error: ${err && (err.code || err.message)}`);
            });
        }
    }
    return child;
}
