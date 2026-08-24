// Fake idupi-screen used only by screen-helper.test.mjs: speaks the same
// framed protocol over stdin/stdout so supervisor behaviour can be tested
// without a real display or the Go toolchain in the loop.
import { encodeControl, encodeFrame } from "../../lib/screen-protocol.mjs";

let buffer = "";

process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buffer += chunk;
  let nl;
  while ((nl = buffer.indexOf("\n")) !== -1) {
    const line = buffer.slice(0, nl);
    buffer = buffer.slice(nl + 1);
    if (line.trim()) handle(JSON.parse(line));
  }
});

function handle(req) {
  switch (req.cmd) {
    case "list":
      process.stdout.write(
        encodeControl({
          id: req.id,
          ok: true,
          monitors: [{ id: 0, name: "FAKE", primary: true }],
        }),
      );
      break;
    case "capture":
      process.stdout.write(
        encodeFrame({ id: req.id, w: 8, h: 8 }, Buffer.from([0xff, 0xd8, 1])),
      );
      break;
    case "crash":
      // Die with a request still pending: exercises rejection + respawn.
      process.exit(9);
      break;
    case "hang":
      // Never respond: exercises the timeout path.
      return;
    case "halfframe":
      // Write HALF a framed message and go silent: whatever bytes a frame
      // decoder carries across a respawn are poisoning, not state.
      {
        const wire = encodeFrame({ id: req.id, w: 8, h: 8 }, Buffer.from([0xff, 0xd8, 1]));
        process.stdout.write(wire.subarray(0, Math.ceil(wire.length / 2)));
      }
      return;
  }
}
