# Session Listing Robustness — Specification

## Requirements

### Requirement: Correct Session-to-Project Attribution

The system MUST attribute a session to a project using exact path matching only and
MUST exclude sibling directories that merely share a name prefix.

#### Scenario: Sibling worktree excluded

- GIVEN project directory `Sistema_de_mantencion` and sibling
  `Sistema_de_mantencion-worktrees/*`
- WHEN sessions are listed for `Sistema_de_mantencion`
- THEN sessions belonging only to the sibling worktree directory MUST NOT be included

#### Scenario: Session without a usable `cwd`

- GIVEN a session record has no `cwd` usable for exact-path comparison
- WHEN matching is attempted
- THEN the system MUST NOT include the session via a fallback comparison that can never
  produce a match; unmatched sessions MUST be excluded, not silently miscounted

### Requirement: Observable Engine Failure

A failure while scanning one engine's sessions MUST be observable and MUST NOT be
indistinguishable from that engine legitimately having zero sessions.

#### Scenario: Failing engine reports failure, not emptiness

- GIVEN the Claude session scan encounters an error for a project
- WHEN the sessions listing request completes
- THEN the failure MUST be recorded/logged such that it is distinguishable from "0
  Claude sessions"
