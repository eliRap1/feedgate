# FeedGate — personal Android feed blocker

Accessibility-service app that keeps Instagram + TikTok usable for
messaging while blocking the addictive surfaces (Reels, Explore, feed
scroll, For You). Personal APK — no Play Store, no backend, nothing
leaves the device.

## Commands

```bash
# Build (no wrapper committed — Android Studio, or local Gradle ≥9 + JDK 17+)
gradle :app:assembleDebug
# Install
adb install app/build/outputs/apk/debug/app-debug.apk
# Debug detector drift
adb logcat -s FeedGate
```

Known-good local toolchain (2026-07): Gradle 9.3.1 (`~/.gradle/wrapper/dists`),
Zulu JDK 21, AGP 8.13.1, SDK platform 36 at `%LOCALAPPDATA%\Android\Sdk`.

## Architecture

- `FeedGateService.kt` — the accessibility service: event routing, DM-grace,
  timed passes, block overlay.
- `Detectors.kt` — ALL screen detection (view-id suffixes + localized
  content-descriptions, EN + HE). Selectors drift with app updates; fix via
  Inspector mode + logcat dump.
- `Prefs.kt` — SharedPreferences toggles; service reads them live.
- `MainActivity.kt` — settings screen; pass buttons run a 10s countdown
  (deliberate friction) before unlocking.

## Sandbox testing (fakegram/)

`fakegram/` = Instagram test double (same package, same view IDs, same
visibility semantics from real dumps). Full blocking matrix on emulator:

```bash
# build both, boot AVD pixel_7_-_api_36_0, install, enable service:
adb shell settings put secure enabled_accessibility_services dev.eli.feedgate/dev.eli.feedgate.FeedGateService
adb shell settings put secure accessibility_enabled 1
adb shell am start -n com.instagram.android/.Main
adb logcat -s FeedGate   # BLOCK / grace lines
```

Test-double gotchas learned the hard way: mark id-bearing containers
importantForAccessibility=YES (Android prunes passive containers from the
a11y tree) and keep a ticker view updating (static screens emit no events,
detectors never re-run).

## Gotchas

- **Service overlays MUST inflate via ContextThemeWrapper(Theme.FeedGate)**
  — the raw service context has no Material theme; MaterialButton inflation
  throws and runCatching eats it silently (this killed the blackout panel
  for five releases).
- **Cover window needs FLAG_LAYOUT_IN_SCREEN** — node bounds are screen
  coordinates; without the flag the panel sits one status-bar too low.

- **Instagram naming trap:** internally stories = `reel_*` (ALLOWED),
  Reels = `clips_*` (BLOCKED). Do not "fix" this.
- **Design system:** `res/values/colors.xml` — "quiet warden" palette.
  Warm ink ground, bone text, ONE ember accent reserved for: enable CTA,
  active pass, block overlay. Don't put ember on routine controls.
- **targetSdk stays 34** on purpose (35+ forces edge-to-edge; personal app
  has no Play target-API requirement). compileSdk tracks installed platform.
- Bilingual: every user-facing string in `values/` AND `values-iw/`.
- Accessibility service is restricted to IG/TikTok packages in
  `accessibility_service_config.xml` — keep it that way (privacy stance).
- Network access exists ONLY in `Updater.kt` (github.com releases, throttled)
  and `BriefRepo.kt` (curated RSS list, once per topic per day). The blocker
  service itself never touches the network — keep it so.
- Daybrief is deliberately FINITE: ≤12 items/day, per-topic fetched once per
  day, hard end card. Never add infinite scroll, pull-to-refresh, or ranking.
