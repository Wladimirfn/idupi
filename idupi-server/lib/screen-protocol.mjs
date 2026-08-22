// Pure framing helpers mirroring the Go helper's wire format byte-for-byte:
// every stdout message is u32be totalLen || kind(1) || body, where a control
// body is raw JSON and a frame body is u32be metaLen || metaJSON || jpegBytes.
// Keeping this pure lets tests feed Buffers without spawning the helper.

import { Buffer } from "node:buffer";

export const KIND_CONTROL = 0x4a; // 'J'
export const KIND_FRAME = 0x46; // 'F'

/** Serialise one request as a single stdin line for the Go helper. */
export function encodeRequest(obj) {
  return JSON.stringify(obj) + "\n";
}

/** Encode a control message as full wire bytes. */
export function encodeControl(obj) {
  const body = Buffer.from(JSON.stringify(obj));
  const wire = Buffer.alloc(5 + body.length);
  wire.writeUInt32BE(body.length + 1, 0);
  wire[4] = KIND_CONTROL;
  body.copy(wire, 5);
  return wire;
}

/** Encode a frame message (meta JSON + JPEG bytes) as full wire bytes. */
export function encodeFrame(metaObj, jpeg) {
  const meta = Buffer.from(JSON.stringify(metaObj));
  const body = Buffer.alloc(4 + meta.length + jpeg.length);
  body.writeUInt32BE(meta.length, 0);
  meta.copy(body, 4);
  jpeg.copy(body, 4 + meta.length);
  const wire = Buffer.alloc(5 + body.length);
  wire.writeUInt32BE(body.length + 1, 0);
  wire[4] = KIND_FRAME;
  body.copy(wire, 5);
  return wire;
}

/**
 * Incremental decoder for the helper's stdout stream. Feed it every chunk;
 * it returns zero or more complete { kind, body } messages per call.
 */
export function createFrameDecoder() {
  let pending = null; // Buffer being reassembled
  return function decode(chunk) {
    const messages = [];
    const buf = pending ? Buffer.concat([pending, chunk]) : chunk;
    let offset = 0;
    while (buf.length - offset >= 4) {
      const total = buf.readUInt32BE(offset);
      if (total < 1 || total > 64 * 1024 * 1024) {
        throw new Error(`helper frame length out of range: ${total}`);
      }
      if (buf.length - offset - 4 < total) break;
      messages.push({
        kind: String.fromCharCode(buf[offset + 4]),
        body: buf.subarray(offset + 5, offset + 4 + total),
      });
      offset += 4 + total;
    }
    pending = buf.length - offset > 0 ? buf.subarray(offset) : null;
    return messages;
  };
}

/** Split a KindFrame body into its parsed meta object and JPEG bytes. */
export function splitFrameBody(body) {
  if (body.length < 5) {
    throw new Error("frame body shorter than meta length prefix");
  }
  const metaLen = body.readUInt32BE(0);
  if (metaLen + 4 > body.length) {
    throw new Error("frame meta length exceeds frame body");
  }
  let meta;
  try {
    meta = JSON.parse(body.subarray(4, 4 + metaLen).toString());
  } catch (err) {
    throw new Error(`frame meta is not valid JSON: ${err.message}`);
  }
  return {
    meta,
    jpeg: body.subarray(4 + metaLen),
  };
}
