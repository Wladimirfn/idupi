// Tests for the owner turn grants (phone-bubble, T13).
//
// A grant is the only server-side proof that a command was created by a
// queued/running bubble turn. It lives in memory, is bound to one turn id and
// dies with the turn: it is never persisted, never written to a log and never
// derived from request data.
//
// Run (from repo root):
//   node --test idupi-server/test/phone-turn-grants.test.mjs

import test from "node:test";
import assert from "node:assert/strict";

import {
    TURN_GRANT_BYTES,
    TURN_GRANT_ENV,
    TURN_GRANT_HEADER,
    createPhoneTurnGrants,
} from "../lib/phone-turn-grants.mjs";

const TURN = "pat-0123456789ab";
const OTHER_TURN = "pat-ba9876543210";

test("a turn grant is a random secret bound to its turn", () => {
    const grants = createPhoneTurnGrants();
    const secret = grants.issue(TURN);

    assert.match(secret, /^[0-9a-f]{64}$/);
    assert.equal(secret.length, TURN_GRANT_BYTES * 2);
    assert.equal(grants.turnFor(secret), TURN);
    assert.equal(grants.secretFor(TURN), secret);
    assert.equal(grants.has(TURN), true);
});

test("each turn gets its own secret", () => {
    const grants = createPhoneTurnGrants();
    const first = grants.issue(TURN);
    const second = grants.issue(OTHER_TURN);

    assert.notEqual(first, second);
    assert.equal(grants.turnFor(first), TURN);
    assert.equal(grants.turnFor(second), OTHER_TURN);
});

test("a revoked grant stops resolving immediately", () => {
    const grants = createPhoneTurnGrants();
    const secret = grants.issue(TURN);

    grants.revoke(TURN);

    assert.equal(grants.turnFor(secret), null);
    assert.equal(grants.secretFor(TURN), null);
    assert.equal(grants.has(TURN), false);
});

test("re-issuing a turn replaces the previous secret", () => {
    const grants = createPhoneTurnGrants();
    const oldSecret = grants.issue(TURN);
    const newSecret = grants.issue(TURN);

    assert.notEqual(oldSecret, newSecret);
    assert.equal(grants.turnFor(oldSecret), null);
    assert.equal(grants.turnFor(newSecret), TURN);
});

test("wrong, blank and non-string secrets never resolve", () => {
    const grants = createPhoneTurnGrants();
    const secret = grants.issue(TURN);
    const wrongSameLength = secret.replace(/^./, secret[0] === "a" ? "b" : "a");

    assert.equal(grants.turnFor(wrongSameLength), null);
    assert.equal(grants.turnFor(secret.slice(0, -1)), null);
    assert.equal(grants.turnFor(secret + "a"), null);
    assert.equal(grants.turnFor(""), null);
    assert.equal(grants.turnFor("not-a-grant"), null);
    assert.equal(grants.turnFor(null), null);
    assert.equal(grants.turnFor(undefined), null);
    assert.equal(grants.turnFor(42), null);
    assert.equal(grants.turnFor([secret]), null);
});

test("the transport constants are the ones the CLI and the route share", () => {
    assert.equal(TURN_GRANT_ENV, "IDUPI_PHONE_TURN_GRANT");
    assert.equal(TURN_GRANT_HEADER, "x-idupi-turn-grant");
});

test("issue requires a non-empty turn id", () => {
    const grants = createPhoneTurnGrants();
    for (const turnId of ["", "   ", null, undefined, 42]) {
        assert.throws(() => grants.issue(turnId), /turnId is required/);
    }
});
