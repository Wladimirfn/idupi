import test from "node:test";
import assert from "node:assert/strict";

import {
    claudeArgs,
    openCodeArgs,
    normalizeOpenCodeModel,
} from "../lib/agent-cmdline.mjs";

// The CLIs spawn WITHOUT a shell: arguments are an argv array, so a hostile
// payload is inert data. These tests pin that no quoting layer exists to
// break -- the evil message must arrive as ONE element, byte-for-byte.

const EVIL = 'x" & calc.exe & echo "pwned';

test("claude args keep the hostile message as one inert element", () => {
    const args = claudeArgs({ message: EVIL });
    assert.deepEqual(args.slice(-2), ["-p", JSON.stringify(EVIL)]);
    // The static flags stay exactly where the CLI expects them.
    assert.equal(args.indexOf("--output-format"), 0);
    assert.ok(args.includes("bypassPermissions"));
});

test("openCode args keep the hostile message as one inert element", () => {
    const args = openCodeArgs({ message: EVIL });
    assert.deepEqual(args, ["run", "--format", "json", "--auto", EVIL]);
});

test("model and session ids ride as their own array elements", () => {
    const evilSession = 'abc" & whoami & "';
    const args = claudeArgs({ modelId: "m1", sessionId: evilSession, isNewSession: false, message: "hi" });
    assert.deepEqual(args.slice(0, 5), ["--model", "m1", "-r", evilSession, "--output-format"]);
});

test("happy path matches the documented CLI shape", () => {
    const args = claudeArgs({ modelId: "claude-x", sessionId: "s-123", isNewSession: true, message: "hola" });
    assert.deepEqual(args, [
        "--model", "claude-x",
        "--session-id", "s-123",
        "--output-format", "stream-json",
        "--verbose",
        "--permission-mode", "bypassPermissions",
        "-p", '"hola"',
    ]);
    assert.deepEqual(openCodeArgs({ sessionId: "s-9", message: "hola" }),
        ["run", "--format", "json", "--auto", "-s", "s-9", "hola"]);
});

test("openCode args pass the selected model as -m in provider/model form", () => {
    const args = openCodeArgs({ model: "gpt-5.6-luna", provider: "opencode-go", sessionId: "ses_x", message: "hola" });
    assert.deepEqual(args, ["run", "--format", "json", "--auto", "-m", "opencode-go/gpt-5.6-luna", "-s", "ses_x", "hola"]);
});

test("openCode args keep a catalog id that already carries the provider", () => {
    const args = openCodeArgs({ model: "opencode-go/gpt-5.6-luna", sessionId: "ses_x", message: "hola" });
    assert.deepEqual(args, ["run", "--format", "json", "--auto", "-m", "opencode-go/gpt-5.6-luna", "-s", "ses_x", "hola"]);
});

test("openCode args without a model keep the historical shape", () => {
    assert.deepEqual(openCodeArgs({ message: "hola" }), ["run", "--format", "json", "--auto", "hola"]);
});

// --- normalizeOpenCodeModel: the doubled-provider hang guard -----------------
//
// The app sends the catalog id (`opencode/muse-...`) as model AND the provider
// (`opencode`) as provider. The old log line "provider/model" then showed a
// doubled prefix, and prefixing the provider onto an id that already carries
// it built `opencode/opencode/...` -- an unresolvable model that left the CLI
// stuck. Rule: an id with '/' is complete; the provider is never re-prefixed.

test("normalizeOpenCodeModel keeps a catalog id that already carries the provider", () => {
    assert.equal(
        normalizeOpenCodeModel("opencode/muse-spark-1.3-contributor-free", "opencode"),
        "opencode/muse-spark-1.3-contributor-free"
    );
});

test("normalizeOpenCodeModel prefixes a bare model with the provider", () => {
    assert.equal(normalizeOpenCodeModel("muse-spark-1.3-contributor-free", "opencode"), "opencode/muse-spark-1.3-contributor-free");
});

test("normalizeOpenCodeModel returns the bare model when no provider is known", () => {
    assert.equal(normalizeOpenCodeModel("muse-spark-1.3-contributor-free"), "muse-spark-1.3-contributor-free");
});

test("normalizeOpenCodeModel trims whitespace and handles empty input", () => {
    assert.equal(normalizeOpenCodeModel("  opencode/muse-spark-1.3-contributor-free  "), "opencode/muse-spark-1.3-contributor-free");
    assert.equal(normalizeOpenCodeModel("", "opencode"), "");
    assert.equal(normalizeOpenCodeModel(null, "opencode"), "");
});

test("openCode args never double the provider prefix on a catalog id", () => {
    // provider + model-that-already-contains-provider must stay single-prefixed
    const args = openCodeArgs({
        model: "opencode/muse-spark-1.3-contributor-free",
        provider: "opencode",
        sessionId: "ses_x",
        message: "hola"
    });
    assert.deepEqual(args, ["run", "--format", "json", "--auto", "-m", "opencode/muse-spark-1.3-contributor-free", "-s", "ses_x", "hola"]);
});

// --- openCodeArgs autoApprove: PR 2 sidecar toggle seam --------------------
//
// The toggle gates only the spawn path (D8 in design.md). ON keeps the legacy
// `--auto` self-approve behaviour; OFF drops `--auto` so the CLI waits on the
// sidecar's HTTP answer route. The hostile-message invariant MUST be
// preserved in both modes (one JSON-string element, no shell escape path).

test("openCode args drop --auto when autoApprove is false (sidecar mode)", () => {
    const args = openCodeArgs({
        autoApprove: false,
        model: "opencode-go/gpt-5.6-luna",
        provider: "opencode-go",
        sessionId: "ses_x",
        message: "hola",
    });
    // The hostile-message invariant: message is still ONE array element.
    assert.deepEqual(args, [
        "run", "--format", "json",
        "-m", "opencode-go/gpt-5.6-luna",
        "-s", "ses_x",
        "hola",
    ]);
    assert.equal(args.includes("--auto"), false, "--auto MUST be absent when autoApprove is false");
    // Hostile message stays one element.
    const evilArgs = openCodeArgs({ autoApprove: false, message: EVIL });
    assert.equal(evilArgs[evilArgs.length - 1], EVIL, "hostile message MUST stay one inert element when autoApprove is false");
    assert.equal(evilArgs.includes("--auto"), false);
});

test("openCode args keep --auto when autoApprove is true (legacy mode)", () => {
    const args = openCodeArgs({
        autoApprove: true,
        model: "opencode-go/gpt-5.6-luna",
        sessionId: "ses_x",
        message: "hola",
    });
    assert.deepEqual(args, [
        "run", "--format", "json", "--auto",
        "-m", "opencode-go/gpt-5.6-luna",
        "-s", "ses_x",
        "hola",
    ]);
});

test("openCode args default to autoApprove=true (legacy --auto) when unspecified", () => {
    // Backwards-compat: existing call sites that do not pass autoApprove
    // MUST keep producing --auto so no regression slips into the legacy path.
    const args = openCodeArgs({ message: "hola" });
    assert.equal(args.includes("--auto"), true, "default MUST remain --auto (legacy self-approve)");
});
