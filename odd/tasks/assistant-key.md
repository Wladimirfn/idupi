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

- [x] T1 App: `AssistEntryActivity` + manifest filters + pure launch rule +
      bubble "open in dictation" signal (temporary panel when the bubble icon
      is off). Tests on the rule and the signal.
- [x] T2 App: Ajustes row "Asistente digital" with role status
      (`RoleManager.isRoleHeld(ROLE_ASSISTANT)`) opening the system screen.
      Pure summary tests.
- [x] T3 App (not needed): `VoiceInteractionService` +
      `VoiceInteractionSessionService` + metadata so the role qualifies.
- [x] T5 Security (found on device 2026-11-26): the ASSIST entry opened the
      listening panel over the LOCK SCREEN, and bubble turns auto-approve
      messaging (phone-bubble T13), so anyone holding the locked phone could
      dictate "envía un mensaje a ...". Fix: (a) while the keyguard is locked
      the entry calls `KeyguardManager.requestDismissKeyguard` and opens the
      dictation only on `onDismissSucceeded` (cancel -> nothing opens); (b) the
      bubble overlay is hidden while the keyguard is locked and comes back on
      unlock (ACTION_USER_PRESENT / screen events), and an open panel collapses
      and stops listening when the screen locks. Pure gate tests.
- [x] T4 Release: version bump, assembleRelease, install over adb, check
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

- 2026-11-26: T1+T2 done (writer idu-pi work-1): `AssistEntryActivity`
  (ASSIST/VOICE_ASSIST), `AssistLaunchRule`, `AssistDictationHost`, session-only
  panel via `BubbleGate.temporarySessionActive`, Ajustes "Asistente digital"
  card with RoleManager status. Commit `cac72d2`; 1.60-asistente (versionCode
  61, `4ed0bfb`) installed over adb. Device: ASSIST intent opened the panel
  listening without the app behind it.
- 2026-11-26: T5 found and fixed on device. The panel opened over the lock
  screen. Writer work-1 added the keyguard gate (`BubbleGate.keyguardLocked`,
  SCREEN_OFF/ON/USER_PRESENT receiver) and `requestDismissKeyguard`.
  Orchestrator device iterations: request from onCreate failed at once
  (moved to onResume); a translucent activity cannot occlude the keyguard
  (`setTranslucent(false)` when locked); Samsung SystemUI still cancels the
  dismiss right after showing the bouncer (`notifyDismissCancelled`) for an
  adb-launched ASSIST, so a cancelled/failed unlock now defers the dictation to
  the next unlock within 30 s (`AssistDictationHost.deferUntilUnlock`). Suite
  137 suites, 1281 tests, 0 failures. Pending: owner check with a real side-key
  press (locked and unlocked) once IDUPI is the assistant; T3 only if the role
  does not list IDUPI.

- 2026-11-26: T3 not needed: the owner picked IDUPI in Aplicación de asistente
  digital with only the ASSIST activity (role holder `com.idupi.app`). T4: real
  side-key long press logged `START act=ASSIST ... from com.android.systemui`,
  the bubble dictated and sent turn pat-edbb51fdaf30 3 s later (done); the
  follow-up listen ended in no-speech (error 7). PR opened stacked on #30.

## Next step

None; pending PR review. The locked side-key path (unlock then dictate) is
unit-tested and adb-checked; a real locked press is the remaining owner check.
