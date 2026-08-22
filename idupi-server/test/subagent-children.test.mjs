// ============================================================================
// idupi-server/test/subagent-children.test.mjs
//
// The delegation card kept spinning on work that had finished, and the log
// finally said why:
//
//   [IDUPI Subagente] Aviso de fin sin respuesta legible: Background task
//   completed: **workflow**
//
// `Return:` carries whatever the workflowScript returned, and the MODEL writes
// that script. Two runs of the same prompt produced different shapes:
//
//   {"scout": {"key":"scout","agent":"...","runId":"...","output":"..."}}
//   {"scout": "Total .kt files under app/src/main/: **60** ..."}
//
// A reader keyed to `output` found nothing in the second, so nothing closed
// the card. Both shapes are captured here from real sessions.
//
// Run (from repo root):
//   node --test idupi-server/test/subagent-children.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { extractChildren } from "../lib/subagent-children.mjs";
import { parseSubagentNotify, describeNoticeResult } from "../lib/subagent-notify.mjs";

const REAL = JSON.parse(readFileSync(new URL("./fixtures/subagent-notify.json", import.meta.url), "utf8"));
const noticeWith = (needle) => REAL.find((e) => e.content.includes(needle));

test("both shapes the model produced are read the same way", () => {
    const asObjects = extractChildren('{"scout": {"agent":"a","output":"uno"}, "researcher": {"agent":"b","output":"dos"}}');
    const asStrings = extractChildren('{"scout": "uno", "researcher": "dos"}');
    assert.deepEqual(asObjects.map((c) => c.output), ["uno", "dos"]);
    assert.deepEqual(asStrings.map((c) => c.output), ["uno", "dos"]);
    assert.deepEqual(asStrings.map((c) => c.agent), ["scout", "researcher"]);
});

test("the string-shaped fan-out from the live run yields both answers", () => {
    // The exact notice that logged "sin respuesta legible".
    const notice = parseSubagentNotify(noticeWith('"scout": "Total').content);
    assert.equal(notice.children.length, 2);
    const [scout, researcher] = notice.children;
    assert.equal(scout.agent, "scout");
    assert.match(scout.output, /Total .?\.kt.? files/);
    assert.equal(researcher.agent, "researcher");
    assert.match(researcher.output, /terminalize/);
});

test("a child truncated mid-answer still yields the text that arrived", () => {
    const children = extractChildren('{"scout": "completo", "researcher": "cortado a la mi');
    assert.equal(children.length, 2);
    assert.equal(children[1].output, "cortado a la mi");
});

test("a single child describing itself is one child, not one per field", () => {
    // {"key":"main","ok":true,"agent":"scout","runId":"r1","output":"..."} --
    // scanning naively turned each field into its own child and labelled the
    // card "key".
    const children = extractChildren('{"key":"main","ok":true,"agent":"scout","runId":"r1","output":"la respuesta"');
    assert.equal(children.length, 1);
    assert.equal(children[0].agent, "scout");
    assert.equal(children[0].output, "la respuesta");
    assert.equal(children[0].runId, "r1");
});

test("a notice always parses, so a card can close on it even unreadable", () => {
    // Finishing and being readable are different facts. Requiring the second
    // to act on the first is what left the card spinning.
    const bare = parseSubagentNotify("Background task completed: **workflow**\n\nsin bloque Return");
    assert.notEqual(bare, null);
    assert.equal(bare.output, null);
    assert.match(describeNoticeResult(bare), /no incluyó su respuesta/);
});

test("every child that could be read is shown, labelled by role", () => {
    const notice = parseSubagentNotify(noticeWith('"scout": "Total').content);
    const shown = describeNoticeResult(notice);
    assert.match(shown, /\*\*scout\*\*/);
    assert.match(shown, /\*\*researcher\*\*/);
});

test("children Pi cut from the notice are reported, not silently dropped", () => {
    const notice = parseSubagentNotify(
        'Background task completed: **workflow**\n\nWorkflow completed with 3 child run(s). Return: {"scout": "uno"}',
    );
    assert.equal(notice.childCount, 3);
    assert.match(describeNoticeResult(notice), /2 más terminaron/);
});

test("the declared count wins over what the truncated text happened to show", () => {
    const notice = parseSubagentNotify(
        'Background task completed: **workflow**\n\nWorkflow completed with 2 child run(s). Return: {"scout": "uno"}',
    );
    assert.equal(notice.childCount, 2);
    assert.equal(notice.children.length, 1);
});
