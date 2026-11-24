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
- [x] T2 App read + report: READ_CALENDAR permission flow in Ajustes,
  calendar reader (instances next 14 days, visible calendars), periodic report
  with the phone status loop and on change; Agenda section; tests.
- [x] T3 App write + approve: alert card event chip + "Agregar al calendario"
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
- T2 done (2026-11-24): READ/WRITE_CALENDAR declared; Ajustes > CALENDARIO
  re-checks permissions on resume, requests both at runtime, shows the Outlook
  sync hint, the share switch (default on), the last report and "Enviar ahora".
  Reader reads visible `CalendarContract.Calendars` and `Instances` for now+14
  days, skips cancelled/deleted rows, keeps all-day UTC boundaries and maps
  everything through pure, capped/clipped functions. Process-wide reporter runs
  on the 60 s tick (15 min floor), a 30 s debounced ContentObserver on
  `Events.CONTENT_URI`, and the manual send; the client trio gained the
  snapshot/proposals/decision calls. Drawer Agenda lists the next 7 local days
  with conflicts flagged from the shared overlap logic.
  Commit: `9c6790b` (27 files, +2,955: reader, pure mapping, reporter,
  notifier, settings, Ajustes section component, client calls, tests).
- T3 done (2026-11-24): Alertas single and grouped cards show the extracted
  event chip, the "Choca con: ..." warning and "Agregar al calendario"; the
  shared confirm sheet edits title/date/start/end/all-day/location, picks a
  writable calendar (last used, else the first Google one), warns about local
  conflicts and inserts through `CalendarContract.Events` only on the explicit
  Guardar tap, then shows "Evento guardado en ...", remembers the calendar and
  triggers a report. Pending PC proposals appear at the top of Agenda, poll
  every 30 s while visible, open the same prefilled sheet, post the approved
  decision with the new event id (or reject), treat 409/404 as already decided,
  and a fresh proposal raises a "Calendario IDUPI" local notification from the
  reporter loop.
  Commit: `bdaf0c6` (12 files, +1,934/-3: Agenda, sheet, alert chips,
  CalendarViewModel, navigation, tests).
  Verification: `./gradlew.bat :app:testDebugUnitTest :app:assembleRelease -q`
  passes (exit 0); 725 tests in 80 files, 0 failures, release APK built.
  Deviation: the Agenda screen and the Ajustes wiring landed in the T3 commit
  because they share `CalendarViewModel` and the confirm sheet with proposals;
  the reporter's proposal poll/notification infrastructure is in the T2 commit.
  Not verified on a device: real provider data (Google, Samsung, Outlook-synced
  calendars), the Outlook "Sincronizar calendarios" hint, all-day/time-zone
  rendering on the owner's phone, runtime permission dialogs and the insert
  dialog, ContentObserver delivery on the real provider, and the notification
  channel. No `idupi-server/` changes, no push, no version bump, no install.
- T4 (partial): version 46 / 1.45-calendar, app 725 tests / 0 failures,
  cert d8ba0fc9, delivered dlv-365d557daa7e; server restarted (no PTYs);
  `idupi-phone calendar` answers "Sin datos del calendario todavía" until the
  owner installs and grants the calendar permission. Pending: live check.
