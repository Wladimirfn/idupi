/**
 * Keeps each chat task addressable by the key its own client chose.
 *
 * The server used one global `activeTask`, replaced on every POST. The app
 * abandons that POST after 20 seconds -- a CLI answer with subagent delegation
 * routinely outlives a mobile NAT's idle window -- and falls back to polling
 * /chat/active-task. Since the poll had nothing to match on, a second message
 * overwrote the first one's result and the first poll either returned the wrong
 * answer or never saw its own.
 *
 * The correlation key belongs to the client: it gave up on the POST before the
 * response carrying a server-side id ever came back, so a server-generated id
 * is unusable for this. The client sends `clientTaskId` with the message and
 * polls with the same value.
 */

const DEFAULT_CAP = 20;

/** Reported for an id this registry has no record of. Never another task's state. */
const UNKNOWN = Object.freeze({ status: "unknown", output: null, error: null });

export class TaskRegistry {
    constructor({ cap = DEFAULT_CAP } = {}) {
        this.cap = cap;
        /** @type {Map<string, {id: string, message: string, status: string, output: string|null, error: string|null, startTime: number}>} */
        this.tasks = new Map(); // insertion-ordered: the oldest key is the first
        this.currentId = null;
    }

    get size() {
        return this.tasks.size;
    }

    start(clientTaskId, message) {
        if (!clientTaskId) return null;
        const task = {
            id: clientTaskId,
            message,
            status: "running",
            output: null,
            error: null,
            startTime: Date.now(),
        };
        // Re-inserting moves the key to the end, so eviction stays newest-wins.
        this.tasks.delete(clientTaskId);
        this.tasks.set(clientTaskId, task);
        this.currentId = clientTaskId;
        while (this.tasks.size > this.cap) {
            this.tasks.delete(this.tasks.keys().next().value);
        }
        return task;
    }

    /**
     * Records a terminal outcome. A finish for an id that never started is
     * ignored: materialising a task from it would let a stray call fabricate a
     * result the client could then read as its own.
     */
    finish(clientTaskId, { output = null, error = null } = {}) {
        const task = this.tasks.get(clientTaskId);
        if (!task) return null;
        task.status = error ? "error" : "completed";
        task.output = output;
        task.error = error;
        return task;
    }

    /** State for one client's own task, or UNKNOWN. Never a different task. */
    get(clientTaskId) {
        return this.tasks.get(clientTaskId) || UNKNOWN;
    }

    /**
     * The most recently started task, for clients that send no id. Preserves
     * the pre-existing single-task behavior rather than breaking an older APK.
     */
    current() {
        return (this.currentId && this.tasks.get(this.currentId)) || UNKNOWN;
    }
}
