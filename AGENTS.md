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

## Gotchas

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
- No outbound network calls anywhere. Keep it so.
