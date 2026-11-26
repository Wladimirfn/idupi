# Settings redesign: hub with sub-screens (phase 2, design)

Locator: `odd/tasks/settings-redesign.md` · Engram mirror: `odd/settings-redesign/tasks`
Branch: `feat/settings-redesign` (stacked on `feat/phone-bubble`, PR #29)

## Objective

Ajustes stops being one long scroll. It becomes a short hub of categories, like
Android's own Settings; each category opens its own screen with a back arrow,
and the longest blocks inside a category open their own screen too.

## Problem (exploration 2026-11-26, explore-1)

- `ui/screens/SettingsScreen.kt:58` renders 9 sections in one `Column` +
  `verticalScroll`. ALERTAS is ~35-40 rows (more with the app list), ASISTENTE
  DEL TELÉFONO ~25-30, SALUD ~20-25, APARIENCIA in Personalizado ~20.
- APARIENCIA (owner): in Personalizado the color editor cannot be hidden. It
  renders whenever `settings.mode == CUSTOM` (`SettingsScreen.kt:219-229`);
  there is no expansion state, so hiding it means changing the theme.
- "Apps a seguir" (owner: "la más extensa") is a card inside ALERTAS
  (`InboxAlertsSection.kt:449-598`) listing every launchable app with a switch;
  it should be a row that opens its own screen.
- Navigation is a custom `Screen` enum + `AnimatedContent` in
  `ui/navigation/AppNavigation.kt` with one `BackHandler`; `CommandsScreen` is
  the existing pushed sub-screen of Ajustes (`AppNavigation.kt:115-117`).
- No Compose UI tests exist (no `androidTest/`); state/logic is JVM-tested.

## Owner decision (2026-11-26)

"Menú con subpantallas": hub list with summaries; categories open sub-screens;
inside Alertas, "Apps a seguir" (and the other long blocks) open their own
screen; Personalizado colors can be opened and closed.

## Design

- Settings navigation: a small pure route model (`SettingsRoute`: Hub,
  Appearance, Alerts, FollowedApps, SilencedChats, AlertAnalyst,
  CaptureStatus, PhoneAssistant, Calendar, Health, Actions) with a parent map
  for back; hosted inside the SETTINGS screen so the drawer and the global
  `BackHandler` stay as they are (back from a sub-route goes to its parent,
  back from Hub keeps today's behaviour). Route survives rotation.
- Shared pieces extracted once: `SettingsCard`, `SettingsCategoryRow` (icon
  badge, title, summary, chevron), `SettingsSubScreen` (TopAppBar with back
  arrow + scroll container).
- Hub rows with live summaries (pure functions): Conexión (profile · host),
  Apariencia (mode · accent), Alertas (N apps seguidas), Asistente del
  teléfono (control on/off), Calendario, Salud, Ayuda y comandos (existing
  CommandsScreen), Acerca de, Acciones y seguridad.
- Existing section composables move as-is into their sub-screens first; only
  the owner's complaints change behaviour (collapsible colors, Apps a seguir
  screen).

## Tasks

- [ ] T1 Navigation + shared pieces: `SettingsRoute` model with parent/back
      table and saveable state, `SettingsSubScreen`, `SettingsCategoryRow`,
      `SettingsCard`; hub renders category rows and each route renders the
      existing section unchanged. Pure route/back tests.
- [ ] T2 Hub summaries: pure summary functions per category from the existing
      view-model state; tests.
- [ ] T3 Apariencia: Personalizado color editor behind "Editar colores"
      (expand/collapse, collapsed on entry, opened right after choosing
      Personalizado); pure state tests.
- [ ] T4 Alertas: inline access, reminders and auto-reply; rows that open
      "Apps a seguir" (full searchable list, followed first, no 420dp cap),
      "Chats silenciados", "Analista en la PC" and "Estado de captura". Tests
      on any new state.
- [ ] T5 Asistente del teléfono, Calendario, Salud, Acciones y seguridad as
      sub-screens (content moved as-is; "Actividad reciente" and "Aprobaciones
      pendientes" stay inside Asistente). Tests where state changes.
- [ ] T6 Release: version bump, assembleRelease (same cert), install, owner
      live check, PR.

## Acceptance criteria

- Ajustes opens on a short list of categories that fits about one screen.
- Every category opens its own screen; back returns to the parent; the drawer
  still works.
- Personalizado colors can be collapsed without changing the theme.
- "Apps a seguir" is its own screen with search over all apps.
- No setting is lost or changes behaviour beyond the above.

## Checks

- App: `./gradlew :app:testDebugUnitTest` and `:app:assembleDebug`
  (JAVA_HOME = Android Studio1 jbr); `NeutralSpanishCopyTest` covers new copy.
- Manual on the phone (no Compose UI tests in the project).

TDD: on (source: project practice in phases 1-2; runner: JUnit via Gradle).

Delivery: forecast ~1500 authored lines (mostly moved code); strategy
`exception-ok` (project precedent for #27-#29), one PR stacked on #29 with
`size:exception`.

## Progress

- 2026-11-26: exploration done (explore-1); owner chose the hub with
  sub-screens; branch created on `feat/phone-bubble`.

## Next step

T1 (navigation and shared pieces).
