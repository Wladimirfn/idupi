// Owner bubble turn grants (phone-bubble, T13).
//
// A bubble turn is the owner's own request, so the server treats its gated
// messaging commands as already approved. That decision needs proof that a
// command really came from a running turn: the bearer token alone is shared by
// every PC process, screen text is untrusted, and `requestedBy` is just a
// label. This registry is that proof.
//
// Rules that are load-bearing:
//
//   - The secret is 32 random bytes from the crypto module, kept in memory and
//     bound to one turn id. It is never persisted, logged, or written into a
//     step, an answer or a turn record.
//   - The runner passes it to the CLI child as an environment variable; the CLI
//     forwards it in one header on command creation. The agent never sees it.
//   - The lifecycle owner (phone-agent-routes) revokes it as soon as the turn
//     stops being queued/running: done, failed, cancelled or superseded.
//   - Lookups compare with timingSafeEqual, so a caller cannot learn the secret
//     byte by byte from response timing.

import { randomBytes, timingSafeEqual } from "node:crypto";

/** Env var the turn runner sets for the CLI child. */
export const TURN_GRANT_ENV = "IDUPI_PHONE_TURN_GRANT";

/** Header the CLI sends on POST /api/v1/phone/commands. Lowercase: node. */
export const TURN_GRANT_HEADER = "x-idupi-turn-grant";

/** Entropy per grant; 32 bytes -> 64 hex chars. */
export const TURN_GRANT_BYTES = 32;

/**
 * @param {{ random?: (size: number) => Buffer | string }} [options]
 */
export function createPhoneTurnGrants({ random = (size) => randomBytes(size) } = {}) {
    /** @type {{ turnId: string, secret: string }[]} */
    const entries = [];

    function revoke(turnId) {
        const id = String(turnId ?? "").trim();
        if (id.length === 0) return false;
        let removed = false;
        for (let index = entries.length - 1; index >= 0; index -= 1) {
            if (entries[index].turnId !== id) continue;
            entries.splice(index, 1);
            removed = true;
        }
        return removed;
    }

    /**
     * Mints a fresh secret for one turn and replaces any previous one. The
     * secret only ever leaves through this return value and secretFor(); callers
     * must not log or persist it.
     */
    function issue(turnId) {
        const id = typeof turnId === "string" ? turnId.trim() : "";
        if (id.length === 0) {
            throw new Error("turnId is required");
        }
        revoke(id);
        const raw = random(TURN_GRANT_BYTES);
        const secret = Buffer.isBuffer(raw) ? raw.toString("hex") : String(raw);
        entries.push({ turnId: id, secret });
        return secret;
    }

    /** Turn id owning `candidate`, or null. Constant-time for equal lengths. */
    function turnFor(candidate) {
        if (typeof candidate !== "string" || candidate.length === 0) return null;
        const given = Buffer.from(candidate, "utf8");
        let found = null;
        for (const entry of entries) {
            const expected = Buffer.from(entry.secret, "utf8");
            if (expected.length !== given.length) continue;
            if (timingSafeEqual(expected, given)) found = entry.turnId;
        }
        return found;
    }

    /** Current secret for a turn, or null after its revocation. */
    function secretFor(turnId) {
        const id = String(turnId ?? "").trim();
        if (id.length === 0) return null;
        const entry = entries.find((candidate) => candidate.turnId === id);
        return entry ? entry.secret : null;
    }

    function has(turnId) {
        return secretFor(turnId) !== null;
    }

    return {
        issue,
        revoke,
        turnFor,
        secretFor,
        has,
    };
}
