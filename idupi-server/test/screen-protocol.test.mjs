import test from "node:test";
import assert from "node:assert/strict";

import {
  createFrameDecoder,
  encodeControl,
  encodeFrame,
  encodeRequest,
  splitFrameBody,
} from "../lib/screen-protocol.mjs";

test("encodeRequest emits one JSON line", () => {
  assert.equal(
    encodeRequest({ id: 1, cmd: "list" }),
    '{"id":1,"cmd":"list"}\n',
  );
});

test("decoder yields a complete control message fed in one chunk", () => {
  // Hand-built wire bytes: len=15 || 'J' || {"ok":true,"id":1} (14 chars)
  const body = Buffer.from('{"id":1,"ok":true}');
  const wire = Buffer.concat([u32be(body.length + 1), Buffer.from("J"), body]);
  const decoder = createFrameDecoder();
  const messages = [...decoder(wire)];
  assert.equal(messages.length, 1);
  assert.equal(messages[0].kind, "J");
  assert.equal(messages[0].body.toString(), body.toString());
});

test("decoder reassembles frames split across chunks", () => {
  const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]);
  const meta = Buffer.from('{"id":2,"w":800}');
  const frameBody = Buffer.concat([u32be(meta.length), meta, jpeg]);
  const wire = Buffer.concat([
    u32be(frameBody.length + 1),
    Buffer.from("F"),
    frameBody,
  ]);

  const decoder = createFrameDecoder();
  assert.deepEqual([...decoder(wire.subarray(0, 3))], []);
  assert.deepEqual([...decoder(wire.subarray(3, 9))], []);
  const messages = [...decoder(wire.subarray(9))];
  assert.equal(messages.length, 1);
  assert.equal(messages[0].kind, "F");
});

function u32be(n) {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(n);
  return b;
}

test("splitFrameBody separates meta json from jpeg bytes", () => {
  const jpeg = Buffer.from([0xff, 0xd8, 1]);
  const meta = Buffer.from('{"id":7}');
  const body = Buffer.concat([u32be(meta.length), meta, jpeg]);
  const got = splitFrameBody(body);
  assert.deepEqual(got.meta, JSON.parse(meta.toString()));
  assert.ok(got.jpeg.equals(jpeg));
});

test("splitFrameBody rejects truncated or lying meta length", () => {
  assert.throws(() => splitFrameBody(Buffer.from([0, 0])), /meta/);
  const liar = Buffer.concat([u32be(999), Buffer.from([1])]);
  assert.throws(() => splitFrameBody(liar), /meta/);
});

test("encodeControl produces bytes the Go-format decoder accepts", () => {
  const wire = encodeControl({ id: 3, ok: true });
  const [msg] = createFrameDecoder()(wire);
  assert.equal(msg.kind, "J");
  assert.deepEqual(JSON.parse(msg.body.toString()), { id: 3, ok: true });
});

test("encodeFrame produces a frame the decoder and splitter accept", () => {
  const jpeg = Buffer.from([0xff, 0xd8, 9, 9]);
  const wire = encodeFrame({ id: 4, w: 800 }, jpeg);
  const [msg] = createFrameDecoder()(wire);
  assert.equal(msg.kind, "F");
  const { meta, jpeg: gotJpeg } = splitFrameBody(msg.body);
  assert.deepEqual(meta, { id: 4, w: 800 });
  assert.ok(gotJpeg.equals(jpeg));
});
