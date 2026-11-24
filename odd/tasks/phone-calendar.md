# Phone calendar (read, add from alerts, PC proposals)

Locator: `odd/tasks/phone-calendar.md` · Engram mirror: `odd/phone-calendar/tasks`
Branch: `feat/phone-calendar` (stacked on `feat/inbox-management`)

## Objective

IDUPI knows the owner's agenda and helps manage it: the phone reports the
upcoming events of its calendars (Google, and Outlook when the Outlook app
syncs its calendar to the device) to the PC; alerts that mention a date offer
"Agregar al calendario" with the event prefilled and a conflict warning; PC
agents can read the agenda and propose events, conflicts and free slots; no
event is written without the owner's approval on the phone.

## Problem / why

Owner request 2026-11-24: events with dates in mail/WhatsApp should go to the
calendar; IDUPI should check the Google (phone) and Outlook calendars to
schedule things and point out conflicts and recommendations.

## Design decisions

- Calendar access goes through Android `CalendarContract` (READ_CALENDAR /
  WRITE_CALENDAR runtime permissions): one source for Google, Samsung and
  Outlook-synced calendars, no cloud API or OAuth. Outlook events only appear
  when the Outlook app has "sync calendars" enabled; the app says so.
- Writes happen only on the phone after an explicit owner tap: from an alert
  ("Agregar al calendario" → confirm sheet) or by approving a PC proposal.
  The PC never writes directly. Event text from messages is untrusted: the
  agent only extracts fields; the owner sees and can edit them before saving.
- Conflicts are computed deterministically (time overlap) on the server; the
  agent adds the human wording/recommendations.

## Scope

In: server calendar snapshot store + routes, event extraction in the inbox
analysis, proposals queue, conflict check, `idupi-phone calendar` CLI + docs;
app calendar reader/reporter, permissions UI, add-event confirm sheet, alert
event chip, proposals approval, Agenda section.
Out: editing/deleting existing events, recurring-event authoring, invites to
attendees, Outlook/Google cloud APIs.

## Constraints

- Calendar data leaves the phone only to the owner's PC and configured agent.
- Neutral Latin American Spanish in all UI and agent text.
- Code/comments/commits in English; zero-dependency server.
- APK versionCode above 45, delivered to the phone.

## Tasks

- [x] T1 Server: `POST /api/v1/phone/calendar` snapshot (events next 14 days:
  id, calendarName, accountType, title, start, end, allDay, location), `GET`
  agenda + deterministic conflicts; inbox analysis JSON gains optional
  `event {title,start,end,allDay,location}` (agent extracts, server validates
  ISO times) plus `conflicts` against the snapshot; proposals queue
  (`POST /api/v1/phone/calendar/proposals` from PC, phone lists/approves/
  rejects); `idupi-phone calendar [agenda|free|propose]` CLI; docs; tests.
- [ ] T2 App read + report: READ_CALENDAR permission flow in Ajustes,
  calendar reader (instances next 14 days, visible calendars), periodic report
  with the phone status loop and on change; Agenda section; tests.
- [ ] T3 App write + approve: alert card event chip + "Agregar al calendario"
  confirm sheet (editable fields, calendar picker, conflict warning) writing
  via CalendarContract with WRITE_CALENDAR; PC proposals approval list; tests.
- [ ] T4 Deliver: version bump, APK to the phone, server restart (check PTYs),
  live check.

## Acceptance

- `idupi-phone calendar agenda` on the PC lists the phone's upcoming events.
- A message with a date shows an event chip; saving it creates the event in
  the chosen calendar after confirmation, with a conflict warning when it
  overlaps.
- A PC proposal appears on the phone and is written only after approval.

## Checks

- Server: `node --test idupi-server/test/<file>.test.mjs` per file.
- App: `./gradlew.bat :app:testDebugUnitTest :app:assembleRelease -q`.
- TDD mode: off (no project/session TDD config).

## Delivery

Forecast ~1.5-2k authored lines; stacked PR after feat/inbox-management,
commits by task.

## Progress

- Branch created from feat/inbox-management aedb3b8.
- T1 done (2026-11-24): calendar snapshot store + routes with strict
  validation, deterministic conflicts and free slots, inbox event extraction
  with snapshot conflict warnings, proposals queue with 7-day expiry,
  `idupi-phone calendar` CLI, docs and tests.
  Commits: `5f9eb28` (store+routes, 1852 insertions incl. tests and index
  wiring), `a478946` (inbox extraction, +361/-12), `e0a37d0` (CLI+docs,
  +788/-138). Verification: all 67 `idupi-server/test/*.test.mjs` files pass
  (0 FAIL) and `node --check idupi-server/index.mjs` passes.
  Deviation: `idupi-server/docs/README.md` had a committed duplicated,
  truncated copy of the whole document (the inbox line was cut mid-row); the
  copy was removed while adding the calendar rows. No `app/` files touched, no
  push, no server restart.
