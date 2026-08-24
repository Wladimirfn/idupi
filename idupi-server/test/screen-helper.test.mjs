import test from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import { ScreenHelper } from "../lib/screen-helper.mjs";

const fakeHelper = fileURLToPath(
  new URL("./fixtures/fake-helper.mjs", import.meta.url),
);
// Generous wall-clock budget for spawning node on CI-slow machines; the
// per-request timeout itself is injected and kept tiny in tests.
const SPAWN_WAIT_MS = 10_000;

function makeHelper(overrides = {}) {
  return new ScreenHelper({
    command: process.execPath,
    commandArgs: [fakeHelper],
    requestTimeoutMs: 1_500,
    ...overrides,
  });
}

test("list() spawns the helper and resolves parsed monitors", async () => {
  const helper = makeHelper();
  try {
    const monitors = await Promise.race([
      helper.list(),
      failAfter(SPAWN_WAIT_MS, "list spawn"),
    ]);
    assert.deepEqual(monitors, [{ id: 0, name: "FAKE", primary: true }]);
  } finally {
    await helper.stop();
  }
});

test("capture() resolves meta plus jpeg bytes", async () => {
  const helper = makeHelper();
  try {
    const frame = await Promise.race([
      helper.capture({ monitor: 0, width: 800, height: 450 }),
      failAfter(SPAWN_WAIT_MS, "capture"),
    ]);
    assert.equal(frame.meta.w, 8);
    assert.ok(frame.jpeg.equals(Buffer.from([0xff, 0xd8, 1])));
  } finally {
    await helper.stop();
  }
});

test(
  "a crashing helper rejects pending requests and respawns on next use",
  async () => {
    const helper = makeHelper();
    try {
      await Promise.race([helper.list(), failAfter(SPAWN_WAIT_MS, "warmup")]);

      // One request in flight when the child dies must reject, not hang.
      const doomed = helper.request({ cmd: "crash" });
      await assert.rejects(doomed, /crashed|exited/);

      // Next use respawns a fresh child.
      const monitors = await Promise.race([
        helper.list(),
        failAfter(SPAWN_WAIT_MS, "respawn"),
      ]);
      assert.equal(monitors[0].name, "FAKE");
    } finally {
      await helper.stop();
    }
  },
  SPAWN_WAIT_MS * 3,
);

test("requests time out against an unresponsive helper", async () => {
  const helper = makeHelper({ requestTimeoutMs: 150 });
  try {
    await assert.rejects(
      Promise.race([
        helper.request({ cmd: "hang" }),
        failAfter(SPAWN_WAIT_MS, "timeout test"),
      ]),
      /timeout/i,
    );
  } finally {
    await helper.stop();
  }
});

test("stop() refuses further work while shutting down", async () => {
  const helper = makeHelper();
  await Promise.race([helper.list(), failAfter(SPAWN_WAIT_MS, "warmup")]);
  const stopping = helper.stop();
  await assert.rejects(helper.list(), /shut ?down|stopped/i);
  await stopping;
});

test(
  "a request racing a killed child settles instead of crashing the server",
  async () => {
    const helper = makeHelper();
    try {
      await Promise.race([helper.list(), failAfter(SPAWN_WAIT_MS, "warmup")]);

      // The production crash: a write into a pipe whose reader had already
      // died surfaced as an UNHANDLED 'error' event (EPIPE) that tore down
      // the whole Node server mid-session. The request must merely reject.
      helper.child.kill();
      const outcome = await Promise.race([
        helper.request({ cmd: "list" }).then(
          () => null,
          (err) => err,
        ),
        failAfter(SPAWN_WAIT_MS, "racing request"),
      ]);
      assert.ok(
        outcome === null || outcome instanceof Error,
        "racing request must settle, never throw unhandled",
      );

      // And the supervisor keeps serving afterwards.
      const monitors = await Promise.race([
        helper.list(),
        failAfter(SPAWN_WAIT_MS, "post-crash respawn"),
      ]);
      assert.equal(monitors[0].name, "FAKE");
    } finally {
      await helper.stop();
    }
  },
  SPAWN_WAIT_MS * 3,
);

test(
  "a respawned helper starts with a clean frame decoder",
  async () => {
    const helper = makeHelper({ requestTimeoutMs: 400 });
    try {
      await Promise.race([helper.list(), failAfter(SPAWN_WAIT_MS, "warmup")]);

      // Half a frame poisons any DECODER THAT SURVIVES THE CHILD: the next
      // spawn's bytes would be parsed as a continuation of a dead stream.
      await assert.rejects(
        Promise.race([
          helper.request({ cmd: "halfframe" }),
          failAfter(SPAWN_WAIT_MS, "halfframe"),
        ]),
        /timeout/i,
      );
      const doomed = helper.child;
      doomed.kill();
      await new Promise((resolve) => doomed.once("close", resolve));

      const frame = await Promise.race([
        helper.capture({ monitor: 0, width: 800, height: 450 }),
        failAfter(SPAWN_WAIT_MS, "capture after respawn"),
      ]);
      assert.equal(frame.meta.w, 8);
    } finally {
      await helper.stop();
    }
  },
  SPAWN_WAIT_MS * 3,
);

function failAfter(ms, label) {
  return new Promise((_, reject) =>
    setTimeout(
      () => reject(new Error(`test budget exceeded waiting for ${label}`)),
      ms,
    ),
  );
}
