# Session Listing Nonblocking Scan — Specification

## Requirements

### Requirement: Non-Blocking OpenCode Query

Querying OpenCode sessions MUST NOT block concurrent request handling, including an
in-progress chat SSE stream, for the duration of the query.

#### Scenario: Concurrent SSE stream unaffected

- GIVEN an active chat SSE stream is open
- WHEN a sessions listing request queries OpenCode
- THEN the SSE stream MUST keep delivering events without a multi-second stall
  attributable to the sessions query
