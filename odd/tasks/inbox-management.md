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

- [ ] T1 Neutral Spanish: agent prompt rule (neutral Latin American Spanish,
  tú, no voseo/regionalisms, match the sender's formality) + server test; every
  voseo string in `app/` rewritten neutral; a unit test that fails on voseo
  forms in app Kotlin string literals.
- [ ] T2 Alertas cleanup: closed items (replied, auto_replied, handled,
  dismissed) leave the Alertas list; server prunes closed items after 24 h;
  remove the "Alerta de prueba" action and its code.
- [ ] T3 Installed apps + follow list: phone lists launchable apps; Ajustes
  shows them with a follow switch (presets: WhatsApp, Business, Gmail, Outlook,
  Messenger, Telegram); generic capture for any followed app; server accepts any
  package with a display label.
- [ ] T4 Groups + unseen digest: IDUPI-side "silence but read" per
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
