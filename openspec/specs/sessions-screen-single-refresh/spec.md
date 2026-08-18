# Sessions Screen Single Refresh — Specification

## Requirements

### Requirement: Single Initial Load Per Screen Entry

Opening the Sessions screen MUST trigger exactly one sessions-listing request, not
duplicate concurrent requests, while the manual refresh action MUST remain available.

#### Scenario: Single request on screen open

- GIVEN the user navigates to the Sessions screen
- WHEN the screen finishes its initial load
- THEN exactly one sessions request MUST fire (no duplicate `[Sessions DB] Cargadas`
  log lines)

#### Scenario: Manual refresh still works

- GIVEN the Sessions screen is already loaded
- WHEN the user taps manual refresh
- THEN exactly one additional sessions request MUST fire
