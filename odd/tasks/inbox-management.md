# Inbox management (neutral Spanish, cleanup, any app, unseen digest)

Locator: `odd/tasks/inbox-management.md` · Engram mirror: `odd/inbox-management/tasks`
Branch: `feat/inbox-management` (stacked on `fix/inbox-capture` → `feat/phone-agent`)

## Objective

Turn Alertas into the owner's phone management center: everything in neutral
Spanish, closed items leave the list, the owner chooses which installed apps
IDUPI follows (Messenger, Telegram, WhatsApp groups...), and IDUPI summarizes
what the owner has not seen yet. Calendar integration is phase 2 (own doc).

## Problem / why (owner feedback 2026-11-24)

- The owner is Chilean. The agent prompt only says "Spanish" and the app copy
  uses Rioplatense voseo ("tocá", "Respondé", 18+ strings). A reply sent to a
  contact in the wrong variant causes real problems.
- Replied, handled and dismissed items stay in Alertas.
- The local "Alerta de prueba" is no longer needed.
- Only WhatsApp/Business, Gmail and Outlook are captured; Messenger, Telegram
  and other apps are ignored. The owner wants to see the installed apps and
  pick which ones to follow.
- Groups go unread. Note: a chat muted inside WhatsApp/Telegram/Messenger posts
  no notification (except mentions), so a notification listener cannot see it;
  the fix is an IDUPI-side "silence but read" option (capture, then cancel the
  notification) for chats the owner un-mutes in the source app.
- The owner wants a summary of unseen content per conversation.

## Scope

In: server agent prompt + inbox pruning + digest endpoint; app copy, Alertas
lifecycle, installed-app inventory and follow list, generic capture, IDUPI-side
silencing, unseen digest UI.
Out (phase 2): calendar read/write (Google/Outlook through the device calendar
provider), mail API search.

## Constraints

- All user-facing Spanish (UI, agent summaries, suggested replies, templates)
  is neutral Latin American Spanish: tú, never voseo, no regionalisms.
- Code/comments/commits in English. Zero-dependency Node server.
- Auto-reply still sends only the owner's template.
- APK versionCode above 43, delivered to the phone.

## Tasks

- [x] T1 Neutral Spanish: agent prompt rule (neutral Latin American Spanish,
  tú, no voseo/regionalisms, match the sender's formality) + server test; every
  voseo string in `app/` rewritten neutral; a unit test that fails on voseo
  forms in app Kotlin string literals.
- [x] T2 Alertas cleanup: closed items (replied, auto_replied, handled,
  dismissed) leave the Alertas list; server prunes closed items after 24 h;
  remove the "Alerta de prueba" action and its code.
- [x] T3 Installed apps + follow list: phone lists launchable apps; Ajustes
  shows them with a follow switch (presets: WhatsApp, Business, Gmail, Outlook,
  Messenger, Telegram); generic capture for any followed app; server accepts any
  package with a display label.
- [x] T4 Groups + unseen digest: IDUPI-side "silence but read" per
  conversation; server digest of a conversation's open items through the
  configured agent; Alertas groups unseen items by conversation with the digest.
- [ ] T5 Deliver: version bump, APK to the phone, server restart (check PTYs).

## Acceptance

- Summaries, suggested replies and app copy contain no voseo.
- A replied/handled/dismissed alert disappears from Alertas.
- A followed Telegram/Messenger chat appears in Alertas.
- Unseen messages of a group show as one card with a summary.

## Checks

- Server: `node --test idupi-server/test/<file>.test.mjs` per file.
- App: `./gradlew.bat :app:testDebugUnitTest :app:assembleRelease -q`.
- TDD mode: off (no project/session TDD config).

## Delivery

Forecast ~1.5k authored lines; stacked PR after #21 / fix/inbox-capture,
commits by task.

## Progress

- Branch created from fix/inbox-capture eec0cc4.
- T1 done in cb143f9: `buildInboxPrompt` now requires neutral Latin American
  Spanish, tú/usted by the sender's formality, never voseo or regional slang,
  with the JSON contract unchanged; 18 voseo strings rewritten across 17 Kotlin
  files; `NeutralSpanishCopyTest` walks `src/main/java` and `src/main/res` and
  fails on the explicit voseo word list. Checks: inbox-agent 18/18; app
  595/595 tests and assembleRelease.
- T2 done in 01019ef: closed states never enter the Alertas list, Atendida,
  Descartar and replies remove the card optimistically and restore it with an
  inline error when the live send or state update fails; "Todas", the closed
  state labels and the "Alerta de prueba" action are gone; `inbox-store` prunes
  closed items older than 24 h on load, write and list with an injectable clock.
  Checks: inbox suites 18 + 11 + 15 pass; app 596/596 tests and assembleRelease.
- T3 done in 9defd78 (server) + 1ad81aa (app): `InboxApp` keeps the four
  original ids and adds Messenger, Telegram, Instagram and Signal (id, package,
  label); `InboxSettings.enabledApps` became `followedApps` (preset ids or
  package names) with the four originals plus Messenger and Telegram as the
  default, persisted in the same DataStore key (`inbox_enabled_apps`), so a set
  saved by an older build stays valid. Ajustes > ALERTAS replaces "Apps
  monitoreadas" with "Apps a seguir": installed presets on top, "Otras apps"
  with a search field, both through `InstalledApps` (pure dedupe, own-package
  exclusion, preset-first label sort; unit-tested) and `AndroidInstalledApps`
  (MAIN/LAUNCHER query through the existing `<queries>` block, no
  QUERY_ALL_PACKAGES). `NotificationParser` captures any followed package: the
  preset mail apps keep `mail()`, everything else uses `messaging()`
  (MessagingStyle first, then title/bigText/text); it skips call, progress,
  transport, service and system categories, generalizes the aggregation-row
  filter to every messaging app and the capture carries the PackageManager
  `appLabel`. The listener observes the persisted follow list, caches labels and
  still re-checks the follow set before upload; the reply registry already
  serves any app, so "Enviar sugerencia" works for non-WhatsApp items while
  auto-reply stays WhatsApp/Business-only. Wire: `InboxMessageRequest.appLabel`
  is optional (omitted when empty); the server accepts `app` matching
  `^[a-z0-9_.]{1,120}$`, trims and clips `appLabel` to 60, stores and returns
  it, and the agent prompt shows the label with the app id as fallback inside
  the untrusted block. Alertas cards and reminders show label > preset label >
  id/package. Checks: inbox suites 19 + 13 + 18 pass; app 616/616 tests and
  assembleRelease. Unverified on device: real Telegram/Messenger/category
  notification shapes, PackageManager label and visibility behavior on the
  owner's Android build, and the DataStore upgrade over a set saved by an older
  build.
- Parent review: fix(server) accept uppercase letters in package names (Android
  allows them; a 400 would make the retry queue drop the message silently).
- Interim delivery (T1-T3): version 44 / 1.43-inbox-apps, app 616 tests /
  0 failures, cert d8ba0fc9, delivered dlv-f4f867dfd334; server restarted on
  feat/inbox-management (no live PTYs). Next: T4 needs the owner decision on
  muted groups; then phase 2 calendar.
- T4 done in e11f794 (server) + d5e29a8 (app capture) + 54a3491 (app UI):
  silence-but-read persists `app|conversation` keys in the existing DataStore
  (`inbox_silenced_conversations`, in-memory twin included); the listener
  registers the reply action and session, starts the upload, then cancels the
  notification (guarded) and the removal reason is explicitly not a dismissal.
  Silenced uploads carry `digestOnly`; the reminder policy ignores silenced
  chats below high importance, and the important-ready ping was already
  high-only. The silence menu is on single and grouped cards, and Ajustes >
  ALERTAS lists silenced chats with a remove action plus the source-app hint.
  The server stores digest-only items as ready/low/"Silenciado" without an
  agent run; `POST /inbox/digest` summarizes the newest 60 open items (12k text
  cap, oldest first) with one delimited untrusted block per message through the
  same serialized no-tools runner, caches by a sha256 of item ids + texts,
  persists `inbox-digests.json` and prunes chats with no open items; `GET`
  returns the current state and `POST /inbox/conversation/state` closes every
  open item of a chat as handled|dismissed in one write, dropping its digest.
  Alertas groups open items per conversation: one item keeps the old card, two
  or more render one card with the digest (auto-requested from 3 messages,
  "Resumir"/"Reintentar"), "Pendiente para ti" bullets, the suggested reply
  with send/edit/copy, "Ver mensajes" and "Marcar todo atendido" (optimistic
  bulk close). Pure grouping/order is unit-tested. Checks: inbox suites 19 + 9
  + 12 + 13 + 22 pass, `node --check idupi-server/index.mjs` OK; app 643/643
  tests and assembleRelease. Unverified on device: cancelNotification actually
  clearing the shade on the owner's Android build, the captured reply
  PendingIntent still firing after that cancel, real group notification shapes,
  and digest summary quality with the live agent (tests use the injected
  runner). Next: T5 delivery (version bump, APK, server restart).
- Parent review: fix(server) sanitizer also strips spaced/attributed
  delimiter tags (digest uses `<untrusted_message index=...>`);
  inbox-sanitize 3/3, inbox suite 78/78.
- T5 (partial): version 45 / 1.44-inbox-digest, app 643 tests / 0 failures,
  cert d8ba0fc9, delivered dlv-57401be7d3a7; server restarted (no PTYs).
  Live evidence: the server inbox holds 15 real items from Gmail, Outlook,
  WhatsApp and Instagram, some already replied/handled/dismissed by the owner
  (capture fix confirmed on the device). Pending: owner tries silence-but-read
  and the group digest on the phone. Next: phase 2 calendar.
