# Privacy Policy — IDUPI

**Last updated:** 28 August 2026  
**App:** IDUPI — `com.idupi.app`  
**Contact:** wladimirfn (GitHub: https://github.com/Wladimirfn/idupi)

## Summary
IDUPI runs entirely on **your own hardware**. The Android app (`com.idupi.app`) connects directly to the IDUPI server you run on your PC. We do not operate cloud servers, we do not collect your personal data, and we do not sell data to third parties.

## Data Collection
- **IDUPI itself does not collect personal data.** No analytics SDK, no ads SDK, no tracking.
- The server generates a local **access token** (64 hex characters) stored as `.idupi-token` on your PC. This token is used only to authenticate the connection between your phone and your PC. It never leaves your devices.
- Screen capture, terminal output, and chat content stay **in memory** on your PC and are streamed to your phone over your network (LAN or Tailscale). They are **not persisted** to our servers.

## Data Usage & Sharing
- Data is used solely to display and control **your** PC from **your** phone.
- We do **not** share data with third parties.
- If you use AI agents (Pi, Claude, OpenCode) through IDUPI, those agents may contact their respective model providers (e.g., OpenAI, Anthropic) according to **their** own configuration and privacy policies. IDUPI does not proxy or store those conversations beyond the session history kept locally on your PC.

## Network & Permissions
- **Permissions requested by the Android app:**
  - `INTERNET` / `ACCESS_NETWORK_STATE` — to connect to your PC.
  - `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE` — to keep the connection alive in background.
  - `WAKE_LOCK` / `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — optional, to prevent the system from killing the session.
- By default the server is intended to be exposed **only via Tailscale** (or your local Wi-Fi). Do not expose it directly to the Internet. Tailscale’s own privacy policy applies when you use it.

## Data Retention & Deletion
- No data is retained on our side. Session history is stored locally as `idupi-server/projects.json` on your PC and can be deleted by removing that file or uninstalling the server.
- Uninstalling the Android app removes all local app data.

## Children’s Privacy
IDUPI is not directed to children under 13 and does not knowingly collect data from them.

## Changes
If this policy changes, we will update this file in the repository and bump the date above.

## Contact
For privacy questions, open an issue at https://github.com/Wladimirfn/idupi/issues or contact the maintainer via GitHub.

---
*This policy is provided to satisfy store requirements (including Aptoide). IDUPI is open-source under the MIT License.*
