# Inbox capture fix (notifications not reaching Alertas)

Locator: `odd/tasks/inbox-capture-fix.md` · Engram mirror: `odd/inbox-capture-fix/tasks`
Branch: `fix/inbox-capture` (stacked on `feat/phone-agent`, PR #21)

## Objective

Every WhatsApp/Gmail/Outlook notification on the phone reaches the PC inbox:
the ones already in the shade, the ones that arrive while the PC server is
down, and the ones that arrive after an app update unbinds the listener. The
owner can see in Ajustes whether capture really works.

## Problem / why (verified 2026-11-24)

Owner report: notification access shows "activo", the local "Alerta de
prueba" arrives, the shade has WhatsApp and mail notifications, and nothing
reaches Alertas. The server inbox has no item since the 12:12 test and there
is no `phone-status.json`. Code evidence:

1. `InboxNotificationListener` only handles `onNotificationPosted`; it never
   reads `getActiveNotifications()` on connect, so notifications already in the
   shade are never captured.
2. `upload()` logs and drops on any failure (server down during the PC reboot,
   network change): no retry.
3. Ajustes "activo" is `isInboxListenerEnabled` (the permission string in
   secure settings), not whether the service is bound. Nothing calls
   `NotificationListenerService.requestRebind`, so a listener the system
   unbound (common after an APK update or OEM battery kill) stays dead while the
   UI says active.
4. The "Alerta de prueba" only posts IDUPI's own local notification; it does
   not test capture.

The server already dedupes on notificationKey + text, so re-sending is safe.

## Scope

In (app only, `app/`):
- Capture the active notifications on listener connect and on demand.
- Persistent bounded retry queue for failed uploads.
- Rebind request when access is granted but the service is not connected.
- Capture diagnostics in Ajustes > ALERTAS + "Leer notificaciones ahora".

Out: server changes, mail API search.

## Constraints

- Never log or persist message text outside the retry queue (app-private
  storage, bounded, dropped once uploaded or after 24 h).
- Code/comments/commits in English; UI copy in Spanish.
- APK delivered with versionCode above 42.

## Tasks

- [x] T1 App capture reliability: active-notification scan on connect and on
  demand; persistent retry queue (bounded, 24 h TTL) drained on connect, on
  every 60 s tick and after a successful upload; `requestRebind` from the app
  when access is granted but the listener has not connected recently;
  diagnostics (connected, last capture, last upload ok/error class, pending
  count) shown in Ajustes > ALERTAS with a "Leer notificaciones ahora" button.
  Unit tests for the pure parts (queue, scan selection, diagnostics mapping).
- [ ] T2 Deliver: version 43 / 1.42-inbox-capture, release APK to the phone,
  owner confirms items appear.

## Acceptance

- Notifications already in the shade appear in Alertas after the listener
  connects or after "Leer notificaciones ahora".
- Messages received while the server is down appear once it is back.
- Ajustes shows whether the listener is really connected and the last upload
  result.

## Checks

- App: `./gradlew.bat :app:testDebugUnitTest :app:assembleRelease -q`.
- TDD mode: off (no project/session TDD config); ordinary functional checks.

## Delivery

Forecast ~400-600 authored lines; one PR stacked on #21.

## Progress

- Branch created from feat/phone-agent 076136a.
- T1 done in two app work units on `fix/inbox-capture`:
  - `4c884f9` `fix(app): capture active notifications and retry failed inbox
    uploads`: activeNotifications scan on connect and on demand (same parser +
    reply action + InboxSession path as live posts), persistent bounded retry
    queue (200 entries, 24 h TTL, dedupe on notificationKey+text, app-private
    JSON under filesDir), drain on connect/tick/after a successful upload,
    `requestInboxListenerRebind` + MainActivity resume hook, process-wide
    `InboxCaptureDiagnostics`, and pure tests for queue, scan selection and
    diagnostics.
  - `bba54bf` `feat(app): show inbox capture status in Ajustes`: "Estado de
    captura" card (Escuchando, última notificación leída, último envío,
    pendientes), "Reconectar" and "Leer notificaciones ahora" actions,
    ViewModel actions plus fake-gateway tests.
- Checks: `./gradlew.bat :app:testDebugUnitTest :app:assembleRelease -q`
  passed. 594 unit tests in 69 suites, 0 failures / 0 errors / 0 skipped;
  release APK assembled (signed, version 42 unchanged). New tests: 13 queue,
  4 active scan, 6 diagnostics, 7 ViewModel capture actions/states, 1 relative
  time.
- Not verified on a device: real `activeNotifications` content after connect and
  reply-action registration from a scan; the system actually honouring
  `requestRebind` after an APK update; OEM battery-kill recovery while the
  permission still reads granted; queue drain across a real PC outage and a
  process restart. T2's phone install and owner confirmation cover these.
