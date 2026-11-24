# PC -> phone APK delivery (one-tap install)

Locator: `odd/tasks/apk-delivery.md` · Engram mirror: `odd/apk-delivery/tasks`
Branch: `feat/apk-delivery` (stacked on `feat/live-shared-terminals`)
Roadmap: step 1 of the "phone as command center" vision (Engram `vision/phone-command-center`).

## Objective

Anything built on the PC (this app's APK or any project's APK) reaches the phone and
installs with one tap: an agent or the owner runs `idupi-push <file.apk>` on the PC, the
phone shows a notification "APK lista", and tapping it installs the APK.

## Problem

- No server route transfers files to the phone; the app has no install permission,
  no installer, no FileProvider (explore-1 map, 2026-11-23).
- Today APKs travel by hand (Desktop copy, GitHub release, Drive).

## Design

- **Server:** `lib/delivery-registry.mjs` + `lib/delivery-routes.mjs` behind the auth choke
  point. `POST /api/v1/deliveries {path}` registers an existing `.apk` on the PC (absolute
  path, regular file, size cap, extension check), snapshotting it into
  `idupi-server/deliveries/` so later rebuilds do not change bytes mid-download; returns
  `{id,name,size,sha256,createdAt}`. `GET /api/v1/deliveries` lists; `GET
  /api/v1/deliveries/{id}/download` streams `application/vnd.android.package-archive` with
  Content-Length; `DELETE` removes. Keep the newest N (10).
- **CLI:** `idupi-push <file.apk>` (bin/, on PATH already) registers the file and prints
  the delivery id; agents in shared terminals can call it.
- **App:** `REQUEST_INSTALL_PACKAGES`; PackageInstaller Session API (stream the download
  straight into the session, commit with a PendingIntent to a receiver that launches the
  system confirmation on `STATUS_PENDING_USER_ACTION`); `canRequestPackageInstalls()` gate
  that opens `ACTION_MANAGE_UNKNOWN_APP_SOURCES`. Discovery: poll the list from the app's
  existing background/connection path and post a notification per new delivery; a
  "Entregas" section lists deliveries with Instalar + progress.
- Android always asks the user to confirm a sideload install; silent install (ADB/device
  owner) is out of scope.

## Tasks

- [x] T1 Server: registry + routes + `idupi-push` CLI + tests.
- [x] T2 App: installer, unknown-sources gate, deliveries UI, new-delivery notification + tests.
- [x] T3 Live test with the owner: push the next IDUPI APK through this path.

## Acceptance criteria

- `idupi-push app-release.apk` on the PC -> notification on the phone within ~10 s.
- Tap -> system install dialog -> app updated, data kept (same signature).
- Non-.apk, missing, directory, or oversize paths are rejected; every route needs the token.

## Checks

- Server TDD off (`openspec/config.yaml:24`); runner `node --test idupi-server/test/<file>`.
- App: `gradlew :app:testDebugUnitTest :app:assembleDebug`.

## Delivery

- Stacked PRs: PR-F = T1, PR-G = T2+T3.

## Progress / evidence

| Task | Commit | Checks |
|------|--------|--------|
| T1 | 5cd5e35 | Writer idu-pi work-1. 34/34 (10 new) worker; parent rerun delivery tests 10/10; routes wired after requireAuth (reviewed). Live server not restarted yet (owner had a live PTY). |
| T2 | 1277fa9, 14bebc7 | Writer idu-pi work-1. :app:testDebugUnitTest 378/0 fail (25 new) + assembleDebug; parent: assembleRelease, APK 1.32 versionCode 33 same cert; receiver exported=false, explicit-component mutable PendingIntent. Watcher polls only while the app process lives (background push = roadmap step 3). |
| T3 | 139e103 | Server restarted (PID 32120 on :8788). `idupi-push idupi-1.33-drawer-swipe.apk` -> dlv-27ac19c5e788 (48.1 MB); owner confirmed notification arrived and tapping it installed 1.33 (versionCode 34, same cert) over 1.32. |

## Next step

Done. Next roadmap step: app redesign around terminals + deliveries (owner request 2026-11-23).
