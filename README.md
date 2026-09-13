# AutoSwipe

An Android app that automatically swipes to the next video when a YouTube Short or Instagram Reel loops back to the start, and skips ads that have no progress indicator (product carousels, sponsored posts, etc.).

It works by running an [AccessibilityService](https://developer.android.com/guide/topics/ui/accessibility/service) that reads the on-screen video progress bar and detects the sudden jump from "near the end" back to "the start" that happens when a short-form video loops. When it can't find a progress bar at all but sees an explicit "Ad" label instead, it swipes past the ad after a short delay.

## Requirements

- [Android Studio](https://developer.android.com/studio) (Narwhal or newer recommended)
- JDK 11+ (Android Studio bundles a compatible JDK — no separate install needed)
- An Android device or emulator running **Android 8.0 (API 26) or higher**

## Setup

1. Clone the repo:
   ```bash
   git clone https://github.com/Hieejerry/Auto-swipe.git
   ```
2. Open the project folder in Android Studio and let it sync Gradle.
3. Connect a physical device (USB debugging enabled) or start an emulator.
4. Run the app (`Shift+F10` / the green ▶ button, or `./gradlew installDebug` from a terminal).

## Enabling the app

The app can't do anything until its Accessibility Service is turned on:

1. Open **AutoSwipe** on your device.
2. If the status card shows "Accessibility Service: OFF", tap **Enable in Settings** — this jumps straight to the system Accessibility page.
3. Find **AutoSwipe** under "Downloaded apps" and turn it on. Android will show a permission dialog explaining what the service can see/do — allow it.
4. Back in the app, the status should now read "Accessibility Service: ON", and the **Auto-Swipe** switch becomes usable.

Auto-Swipe can be toggled off at any time from the main screen without revoking the accessibility permission.

## Settings

Open **Settings** from the main screen to tune behavior:

| Setting | Description |
|---|---|
| **Target app package(s)** | Comma-separated list of app packages to watch. Defaults to `com.google.android.youtube,com.instagram.android` (YouTube Shorts + Instagram Reels) so both are handled at once. Add or remove packages here. |
| **Near-end ratio** | How far into a video (0–1) counts as "about to end." Default `0.92`. |
| **Reset ratio** | How low progress must drop back to before a loop is confirmed. Default `0.15`. |
| **Swipe cooldown (ms)** | Minimum time between auto-swipes, so one loop can't trigger multiple swipes. Default `1200`. |

**Reset to defaults** restores all of the above.

## How detection works (short version)

- **YouTube Shorts**: the progress bar doesn't expose a numeric range, only a text description like "0 minutes 51 seconds of 1 minute 6 seconds" — the app parses that.
- **Instagram Reels**: the progress bar (`SeekBar` with id `scrubber`) exposes real progress values directly.
- **Ads without a progress bar** (e.g. Instagram product-carousel ads): the app looks for an explicit "Ad" label and swipes past it after ~1.5s, since these never "finish" on their own.
- The service polls the screen instead of relying purely on accessibility events, because some apps (YouTube Shorts, Instagram Reels) don't reliably fire events while a video is simply playing. Polling is adaptive — slow most of the time, fast only near the end of a video — and only runs while a watched app is in the foreground, to save battery.

## Project structure

```
app/src/main/java/com/autoswipe/app/
├── MainActivity.kt                     # UI: status card, Auto-Swipe toggle, Settings screen
└── AutoSwipeAccessibilityService.kt    # Core detection + swipe logic
```

## Disclaimer

This app automates gestures on your own device using Android's standard Accessibility API. Using it may be against the terms of service of the apps it's used with — that's your call to make.
