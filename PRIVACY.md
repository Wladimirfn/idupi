# Privacy Policy — IDUPI

**Last updated:** 25 November 2026  
**App:** IDUPI — `com.idupi.app`  
**Contact:** idubox.chile@gmail.com — https://github.com/Wladimirfn/idupi

## Summary
IDUPI runs entirely on **your own hardware**. The Android app (`com.idupi.app`) connects directly to the IDUPI server you run on your PC. We do not operate cloud servers, we do not collect your personal data, and we do not sell data to third parties.

## Data Collection
- **IDUPI itself does not collect personal data.** No analytics SDK, no ads SDK, no tracking.
- The server generates a local **access token** (64 hex characters) stored as `.idupi-token` on your PC. This token is used only to authenticate the connection between your phone and your PC. It never leaves your devices.
- Screen capture, **terminal input and output**, the **process list** and **system metrics** (CPU, RAM, GPU, temperatures, power, storage) are read and processed **in memory** on your PC and streamed to your phone over your network (LAN or Tailscale). They are **not sent to us or persisted by us**.
- **Phone management features (all optional, each needs your explicit grant):**
  - **Alertas** reads the notifications of the apps you choose to follow (sender, conversation and text) through Android's notification access and sends them to your PC.
  - **Phone assistant** sends battery, signal, network, memory, storage and per-app usage to your PC; with Accessibility enabled and the in-app "Control desde la PC" switch on, your PC can read the screen and tap, type, scroll or open apps. Password fields are masked and never typed into.
  - **Calendar** sends the next 14 days of the calendars on your phone (title, time, location, calendar name) to your PC. Events are written only after you tap Guardar on the phone.
  - **Salud** reads health data you allow in Health Connect (steps, distance, calories, heart rate, resting heart rate, sleep, workouts, weight, SpO₂) and digital-wellbeing usage (screen time, unlocks, night use, top apps), and sends daily summaries to your PC. IDUPI never writes health data.
  - **Huawei / Honor Health** (optional): with wireless debugging paired by you, the PC opens the app on a hidden virtual display, reads its "Today" screen and closes it; temporary files are deleted after each read.
- **APK deliveries:** when you run `idupi-push` on the PC, the server stores a local copy of that APK in `idupi-server/deliveries/` so the phone downloads stable bytes. The newest 10 deliveries are kept (up to 300 MB each). You can delete a delivery from the app or by removing that folder on your PC.

## Data Usage & Sharing
- Data is used solely to display and control **your** PC from **your** phone.
- We do **not** share data with third parties.
- **AI analysis you configure:** Alertas, the unseen-messages digest and the Salud daily digest send the relevant message text, health and wellbeing numbers and agenda titles to the AI agent you choose on your PC (OpenCode, Claude Code, Codex or Pi), which may contact its model provider under **its** own privacy policy. The agent runs without tools, and message text is treated as untrusted data. Automatic replies send only your fixed template, never AI-generated text.
- If you run AI agents (Claude Code, OpenCode, Codex, Pi, etc.) inside an IDUPI terminal, those agents may contact their respective model providers according to **their** own configuration and privacy policies. IDUPI does not proxy, log or store those conversations.
- The optional browser terminal page at `http://127.0.0.1:8788/terminal/` is served only to the PC itself (loopback). The access token travels in the URL fragment, which is not sent to the server.

## Network & Permissions
- **Permissions requested by the Android app:**
  - `INTERNET` / `ACCESS_NETWORK_STATE` — to connect to your PC.
  - `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` — to keep the connection alive in background and announce deliveries.
  - `WAKE_LOCK` / `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — optional, to prevent the system from killing the session.
  - `REQUEST_INSTALL_PACKAGES` — to install APKs that you pushed from your own PC. Android always asks you to confirm each sideload install.
  - Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`) — Alertas: read and, when you ask, reply to notifications of the apps you follow.
  - Accessibility service (`BIND_ACCESSIBILITY_SERVICE`) — phone assistant control from your PC (off by default).
  - Usage access (`PACKAGE_USAGE_STATS`) — per-app usage and digital wellbeing.
  - `READ_PHONE_STATE` (optional) — report whether you are on 4G or 5G.
  - `READ_CALENDAR` / `WRITE_CALENDAR` — share your agenda with your PC and save events you confirm.
  - Health Connect read permissions (including background read) — Salud.
- By default the server is intended to be exposed **only via Tailscale** (or your local Wi-Fi). Do not expose it directly to the Internet. Tailscale’s own privacy policy applies when you use it.

## Data Retention & Deletion
- No data is retained on our side.
- The server stores only local state on your PC: `.idupi-token`, registered projects in `idupi-server/projects.json`, APK snapshots in `idupi-server/deliveries/`, server logs in `idupi-server/logs/`, sensor readings in `%ProgramData%\IDUPI\`, and the phone-management data in `idupi-server/data/`:
  - `inbox.json` and `inbox-digests.json`: closed alerts are pruned after 24 h.
  - `phone-status.json`.
  - `phone-calendar.json` and `phone-calendar-proposals.json`: proposals expire after 7 days.
  - `health.json`: kept for 120 days.
  - `health-digests.json`: kept for 30 days.
  - `adb-targets.json`: saved wireless-debugging addresses.

  Server logs never contain message text, event details or health values. Delete those files or run the uninstall options of the server scripts to remove them.
- Uninstalling the Android app removes all local app data, including connection profiles and appearance settings.

## Children’s Privacy
IDUPI is not directed to children under 13 and does not knowingly collect data from them.

## Changes
If this policy changes, we will update this file in the repository and bump the date above.

## Contact
For privacy questions, contact idubox.chile@gmail.com or open an issue at https://github.com/Wladimirfn/idupi/issues.

---
*This policy is provided to satisfy store requirements (including Aptoide). IDUPI is open-source under the MIT License.*
