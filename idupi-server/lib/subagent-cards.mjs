/**
 * Tracks the delegation cards open during one CLI run.
 *
 * The Claude path used to hold this in two scalars, `activeSubagentId` and
 * `activeSubagentName`. That is a single slot, and a parallel delegation opens
 * several cards at once: the second `tool_use` overwrote the first, so when the
 * first subagent's `tool_result` came back its id no longer matched, its close
 * was routed to the plain-tool branch, and its card span forever on work that
 * had already finished. Always the FIRST one, because that is the one the
 * second launch evicted.
 *
 * There is nothing Claude-specific about the mistake -- Pi hit the same class
 * through a different door -- so what is modelled here is the fact the scalars
 * denied: N cards can be open at once, each closing on its own id.
 */
export class SubagentCards {
    constructor() {
        /** @type {Map<string, string>} insertion-ordered card id -> agent name */
        this._open = new Map();
    }

    get size() {
        return this._open.size;
    }

    /** @param {string} id @param {string} name */
    open(id, name) {
        this._open.set(id, name);
    }

    /** @param {string} id */
    has(id) {
        return this._open.has(id);
    }

    /**
     * Closes one card by its own id.
     * @returns {{id: string, name: string}|null} null when the id is not a
     *   delegation card, which is how a caller tells a subagent's result apart
     *   from an ordinary tool's.
     */
    close(id) {
        if (!this._open.has(id)) return null;
        const name = this._open.get(id);
        this._open.delete(id);
        return { id, name };
    }

    /**
     * Every card still open, removed. A run that ends with cards left is a run
     * whose results never arrived; closing one of them and leaving the rest is
     * the same single-slot mistake at the end of the stream.
     * @returns {Array<{id: string, name: string}>}
     */
    drain() {
        const left = [...this._open].map(([id, name]) => ({ id, name }));
        this._open.clear();
        return left;
    }
}
