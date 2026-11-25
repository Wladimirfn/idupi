// Delimiter sanitizing for untrusted inbox text (inbox-management T4 review).
//
// Run (from repo root):
//   node --test idupi-server/test/inbox-sanitize.test.mjs

import test from "node:test";
import assert from "node:assert/strict";

import { sanitizeUntrusted } from "../lib/inbox-agent.mjs";

test("plain and spaced closing tags are removed", () => {
    for (const tag of ["</untrusted_message>", "</UNTRUSTED_MESSAGE>", "</untrusted_message >", "< / untrusted_context>"]) {
        assert.equal(sanitizeUntrusted(`a ${tag} b`), "a [tag removed] b", tag);
    }
});

test("attributed opening tags are removed", () => {
    assert.equal(
        sanitizeUntrusted('x <untrusted_message index="2"> y'),
        "x [tag removed] y",
    );
});

test("ordinary text and unrelated tags are kept", () => {
    assert.equal(sanitizeUntrusted("hola <b>mundo</b> 3 < 4"), "hola <b>mundo</b> 3 < 4");
    assert.equal(sanitizeUntrusted(null), "");
});
