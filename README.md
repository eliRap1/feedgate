# FeedGate

A personal Android accessibility-service app that keeps Instagram and TikTok usable for messaging while killing the addictive surfaces.

What it does:

- **Instagram Reels** → blocked (overlay + back press)
- **Instagram Explore** → blocked
- **Instagram home feed** → you can *see* it (so the story tray and DM icon are reachable), but the moment you scroll the feed it blocks. Stories viewer and DMs are always allowed. This works because Instagram internally names stories `reel_viewer` and Reels `clips_viewer`.
- **TikTok For You / Friends feed** → blocked; by default it auto-clicks the Inbox tab, so opening TikTok lands you on messages. Streaks, DMs, and things your girlfriend sends all live there and stay fully usable.
- The FeedGate home screen also has an **"Open Instagram DMs"** button that deep-links straight into the inbox (`instagram://direct-inbox`), skipping the feed entirely. Long-press it → add to home screen via a launcher shortcut if you want one-tap access.

## Intentional watching (v1.1)

Blocking everything forever ignores that some short-form content is actually worth watching. Two escape hatches, both deliberate rather than reflexive:

- **DM grace**: a reel opened from a DM thread plays normally (so you can watch what your girlfriend sends), but the first swipe to the *next* reel re-blocks. One reel in, zero rabbit holes.
- **Timed passes**: the FeedGate home screen has 10-min and 30-min pass buttons. Tapping one starts a visible 10-second countdown before feeds unlock — enough time to ask "do I actually want this?". Tap again to cancel; tap the active-pass status line to end a pass early. While a pass is active, everything (Reels, Explore, feeds, TikTok) behaves normally.

## Build

Open the folder in Android Studio (it will generate the Gradle wrapper), let it sync, then Run on your device. Or from CLI with Gradle installed:

```
gradle :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Setup on the phone

1. Open FeedGate → "Enable accessibility service" → turn on **FeedGate blocker**.
2. Android will warn about full screen-content access — that's inherent to how accessibility services work. The service is restricted (in `accessibility_service_config.xml`) to only receive events from Instagram and TikTok, and nothing leaves the device. Read the code, you're a security researcher :)
3. Open Instagram, try to open Reels. It should bounce you out with a "Not today" flash.

## When Instagram/TikTok updates break detection

The detectors match view IDs and content-descriptions, which drift across app versions. To fix:

1. In FeedGate, enable **Inspector mode**.
2. Open the screen that's no longer detected.
3. `adb logcat -s FeedGate` — you'll get a full dump of the view tree.
4. Update the ID suffixes / descriptions in `Detectors.kt`.

## Known limits

- Content-descriptions are locale-dependent. English AND Hebrew descriptions ship built-in (v1.2); for any other app language, add the localized strings to the sets in `Detectors.kt` via Inspector mode.
- TikTok's DM-only web version is too limited to be an alternative; this in-app approach is the reliable way to keep streaks.
- Battery/perf impact is negligible: the service only receives events from the two packages listed in the config.
