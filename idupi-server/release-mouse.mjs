// Emergency release: speaks the framed protocol straight to the helper and
// sends the coordless LEFT UP whose loss wedged the physical mouse.
import { spawn } from "node:child_process";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = join(dirname(fileURLToPath(import.meta.url)), "screen-helper");
const child = spawn(join(here, "idupi-screen.exe"), [], {
  stdio: ["pipe", "pipe", "pipe"],
  windowsHide: true,
});

let buf = Buffer.alloc(0);
child.stdout.on("data", (chunk) => {
  buf = Buffer.concat([buf, chunk]);
  while (buf.length >= 5) {
    const total = buf.readUInt32BE(0);
    if (buf.length < 4 + total) break;
    if (String.fromCharCode(buf[4]) === "J") {
      console.log("CONTROL:", buf.subarray(5, 4 + total).toString("utf8"));
    }
    buf = buf.subarray(4 + total);
  }
});

child.stdin.write(
  JSON.stringify({ id: 1, cmd: "input", action: "up", button: "left" }) + "\n",
);
child.stdin.write(
  JSON.stringify({ id: 2, cmd: "input", action: "up", button: "right" }) + "\n",
);

setTimeout(() => {
  child.kill();
  process.exit(0);
}, 3000);
