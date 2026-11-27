// ============================================================================
// idupi-server/test/inherited-session-env.test.mjs
//
// When the server is launched from inside a Claude Code session (for example
// a restart run from an agent's shell), it inherits that session's
// per-session variables. Every Claude CLI the server spawns then believes it
// is a child session and prints "Transcript saving is off - inherited
// CLAUDE_CODE_CHILD_SESSION", losing the owner's conversation. The server
// must drop those session-scoped variables at startup while keeping real
// configuration (auth, provider, ANTHROPIC_*).
//
// Run (from repo root):
//   node --test idupi-server/test/inherited-session-env.test.mjs
// ============================================================================

import test from "node:test";
import assert from "node:assert/strict";
import { scrubInheritedSessionEnv } from "../lib/inherited-session-env.mjs";

test("drops the variables that mark a spawned CLI as a child session", () => {
    const env = {
        CLAUDECODE: "1",
        CLAUDE_CODE_CHILD_SESSION: "1",
        CLAUDE_CODE_CHILD_NOTES_DIR: "C:\\notes",
        CLAUDE_CODE_SESSION_ID: "abc",
        CLAUDE_CODE_SESSION_ATTENDED: "1",
        CLAUDE_CODE_ENTRYPOINT: "cli",
        CLAUDE_CODE_EXECPATH: "C:\\claude.exe",
        CLAUDE_CODE_MESSAGING_SOCKET: "\\\\.\\pipe\\x",
        CLAUDE_CODE_MESSAGING_TOKEN: "secret",
        CLAUDE_CODE_DISABLE_BG_SHELL_PRESSURE_REAP: "1",
        CLAUDE_PID: "123",
        CLAUDE_EFFORT: "low",
    };

    const removed = scrubInheritedSessionEnv(env);

    assert.deepEqual(env, {});
    assert.equal(removed.length, 12);
});

test("keeps real configuration and unrelated variables", () => {
    const env = {
        CLAUDE_CODE_USE_BEDROCK: "1",
        CLAUDE_CODE_OAUTH_TOKEN: "tok",
        CLAUDE_CONFIG_DIR: "C:\\cfg",
        ANTHROPIC_API_KEY: "sk",
        PATH: "C:\\bin",
        CLAUDECODE: "1",
    };

    const removed = scrubInheritedSessionEnv(env);

    assert.deepEqual(removed, ["CLAUDECODE"]);
    assert.deepEqual(env, {
        CLAUDE_CODE_USE_BEDROCK: "1",
        CLAUDE_CODE_OAUTH_TOKEN: "tok",
        CLAUDE_CONFIG_DIR: "C:\\cfg",
        ANTHROPIC_API_KEY: "sk",
        PATH: "C:\\bin",
    });
});

test("is a no-op on a clean environment", () => {
    const env = { PATH: "C:\\bin" };
    assert.deepEqual(scrubInheritedSessionEnv(env), []);
    assert.deepEqual(env, { PATH: "C:\\bin" });
});
