// Session-scoped screen route contracts: the ACK telemetry handoff and the
// live quality-change route. These run against the real route dispatcher with
// a FAKE stream injected through the test seam -- no Go helper, no HTTP
// server, no ports. The auth layer above these routes is covered by
// screen-routes-auth.test.mjs.
//
//   node --test idupi-server/test/screen-routes-session.test.mjs

import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";

import { handleScreenRoute, _sessionsForTest } from "../lib/screen-routes.mjs";

function fakeStream({ failSetQuality = false } = {}) {
  const acks = [];
  const qualities = [];
  return {
    acks,
    qualities,
    async onAck(args) {
      acks.push(args);
    },
    async setQuality(q) {
      if (failSetQuality) throw new Error(`unknown quality: ${q}`);
      qualities.push(q);
    },
  };
}

function postJson(pathname, payload) {
  const req = new EventEmitter();
  req.method = "POST";
  const res = {
    statusCode: null,
    body: null,
    ended: false,
    writeHead(code) {
      this.statusCode = code;
    },
    end(body) {
      this.ended = true;
      this.body = body ?? null;
    },
  };
  const routed = handleScreenRoute(req, res, pathname);
  req.emit("data", JSON.stringify(payload));
  req.emit("end");
  return { routed, res };
}

async function settledPost(pathname, payload) {
  const { routed, res } = postJson(pathname, payload);
  assert.equal(await routed, true, "route should claim this path");
  // The body handlers answer asynchronously; give them their turn.
  for (let i = 0; i < 50 && !res.ended; i++) {
    await new Promise((r) => setTimeout(r, 10));
  }
  return res;
}

test("the ack route forwards the receiver's renderMs to the stream", async () => {
  // Regression: the route dropped renderMs, so the auto ladder saw every
  // frame as fast, climbed to ultra and could NEVER step down again.
  const stream = fakeStream();
  _sessionsForTest().set("t-ack", { stream });
  try {
    const res = await settledPost("/api/v1/screen/ack", {
      sid: "t-ack",
      frameId: 7,
      bytes: 12_000,
      renderMs: 234,
    });
    assert.equal(res.statusCode, 204);
    assert.deepEqual(stream.acks, [{ frameId: 7, renderMs: 234 }]);
  } finally {
    _sessionsForTest().delete("t-ack");
  }
});

test("the quality route applies a change to a live session", async () => {
  const stream = fakeStream();
  _sessionsForTest().set("t-q", { stream });
  try {
    const res = await settledPost("/api/v1/screen/quality", {
      sid: "t-q",
      quality: "ultra",
    });
    assert.equal(res.statusCode, 204);
    assert.deepEqual(stream.qualities, ["ultra"]);
  } finally {
    _sessionsForTest().delete("t-q");
  }
});

test("the quality route rejects unknown sessions and invalid values", async () => {
  const missing = await settledPost("/api/v1/screen/quality", {
    sid: "ghost",
    quality: "ultra",
  });
  assert.equal(missing.statusCode, 404);

  const stream = fakeStream({ failSetQuality: true });
  _sessionsForTest().set("t-bad", { stream });
  try {
    const bad = await settledPost("/api/v1/screen/quality", {
      sid: "t-bad",
      quality: "nope",
    });
    assert.equal(bad.statusCode, 400);
  } finally {
    _sessionsForTest().delete("t-bad");
  }
});
