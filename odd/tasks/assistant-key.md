# IDUPI as the phone's digital assistant (side key long press)

Locator: `odd/tasks/assistant-key.md` · Engram mirror: `odd/assistant-key/tasks`
Branch: `feat/assistant-key` (stacked on `feat/settings-redesign`, PR #30)

## Objective

The owner can pick IDUPI under Ajustes del teléfono > Aplicaciones
predeterminadas > Aplicación de asistente digital (where Google, ChatGPT,
Claude and Meta AI appear). Then a long press of the side key (Samsung,
Motorola or any Android) opens the IDUPI floating chat already dictating.

## Evidence (owner's phone, SM-S928B, API 36, 2026-11-26, adb)

- Current holder of `android.app.role.ASSISTANT`:
  `com.google.android.googlequicksearchbox`.
- ChatGPT declares an `android.intent.action.ASSIST` activity
  (`AssistantProxyActivity`, category DEFAULT) and a `VoiceInteractionService`.
- Claude declares `AssistantOverlayActivity` for `ASSIST` and `VOICE_ASSIST`
  (category DEFAULT) and `ClaudeVoiceInteractionService`.
- Unknown until tested: whether an `ASSIST` activity alone makes an app
  qualify for the assistant role on this phone (otherwise a
  `VoiceInteractionService` with session + recognition services is needed).

## Design

- `AssistEntryActivity`: exported, `ASSIST` + `VOICE_ASSIST` (DEFAULT),
  transparent, own empty task affinity, excluded from recents, no history. It
  only signals the bubble and finishes; it holds no data and decides nothing.
- Pure launch rule: accessibility service running -> open the bubble panel in
  dictation (even when the bubble icon setting is off: a temporary panel that
  goes away when collapsed); service not running -> open the app on the
  Asistente del teléfono screen with a short hint.
- Ajustes > Asistente del teléfono: row "Asistente digital" with the role
  status (IDUPI is / is not the assistant) that opens the system default-apps
  screen.
- `VoiceInteractionService` only if the role does not accept the ASSIST
  activity alone (T3).

## Tasks

- [ ] T1 App: `AssistEntryActivity` + manifest filters + pure launch rule +
      bubble "open in dictation" signal (temporary panel when the bubble icon
      is off). Tests on the rule and the signal.
- [ ] T2 App: Ajustes row "Asistente digital" with role status
      (`RoleManager.isRoleHeld(ROLE_ASSISTANT)`) opening the system screen.
      Pure summary tests.
- [ ] T3 App (only if needed): `VoiceInteractionService` +
      `VoiceInteractionSessionService` + metadata so the role qualifies.
- [ ] T4 Release: version bump, assembleRelease, install over adb, check
      `cmd role add-role-holder android.app.role.ASSISTANT com.idupi.app`,
      owner side-key check, PR.

## Acceptance criteria

- IDUPI can be chosen as the digital assistant app on the owner's phone.
- Long-pressing the side key opens the bubble panel listening, without the
  IDUPI app coming up behind it.
- With accessibility off, the long press opens IDUPI with a hint instead of
  failing silently.

## Checks

- App: `./gradlew :app:testDebugUnitTest`, `:app:assembleDebug`
  (JAVA_HOME = Android Studio1 jbr); on-device role and side-key check.

TDD: on (source: project practice; runner: JUnit via Gradle).

Delivery: forecast ~400-600 authored lines; strategy `exception-ok` (project
precedent), one PR stacked on #30.

## Progress

- 2026-11-26: device evidence gathered; branch created on
  `feat/settings-redesign`.

## Next step

T1 + T2.
