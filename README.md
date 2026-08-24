# Internet Restrictor for Android

A native Android version of Internet Restrictor with an earned-budget push-up flow.

Internet Restrictor adds deliberate friction before Chrome, Firefox, Edge, or Opera opens. The user chooses one of three customizable session presets (3, 5, or 10 minutes by default), completes a fixed 12-second focused wait, and then receives a wall-clock unlock. Active, focused use across all supported browsers is charged to one shared budget: zero minutes at local-day start plus 3 minutes for every five verified push-ups. Spendable time resets at 20:00 local time and can be earned again afterward.

The official YouTube app is a separate protected target. It stays locked until 20 accumulated verified push-ups in the logical day, then remains open without consuming browser budget until the 04:00 daily reset.

This is a personal, user-controlled tool—not parental control, device management, or a tamper-proof blocker.

## What is implemented

- A five-step native onboarding flow with clear privacy and accessibility disclosure.
- A touch-blocking accessibility overlay over supported browsers.
- A separate morning gate over the official YouTube app (`com.google.android.youtube`) until 20 verified push-ups have accumulated in the logical day.
- A fixed 12-second focused wait before every normal browser session.
- Three customizable 1-to-120-minute wall-clock session presets, defaulting to 3, 5, and 10 minutes.
- A rotatable front/back-camera push-up challenge on every gate. Every five verified repetitions add 3 minutes to today's budget; incomplete groups carry across videos until the 04:00 local daily reset. A 30-second on-screen countdown bounds each recording. The first completed group of each local day also earns one emergency unlock. It returns to the originating supported browser after earning budget, without bypassing the normal wait, and its use is charged normally.
- One earned emergency 15-minute access unlock per local day. Each day begins with none; the first completed five-push-up group earns it, and it can be used even when the daily budget is exhausted.
- At 20:00 local time, accumulated spendable budget and normal browser access reset immediately. Push-up carry-over, the earned emergency unlock, and today's report history survive; new evening push-ups earn fresh spendable time.
- An append-only push-up ledger counts every verified repetition in Europe/Prague cycles running from the 9th at 00:00 to the next month's 9th. The dashboard shows the current total and all completed cycle totals; completed cycles are never pruned by report cleanup.
- Emergency unlocks are always enabled; there is no product switch that can disable them.
- A zero-minute base daily budget with unlimited 3-minute earned increments and no weekly or monthly pool.
- Exact 04:00 local daily-boundary usage splitting, daily usage accounting, and persisted push-up-session history grouped into Monday-to-Sunday weeks.
- Out-of-time latching, expiry handling, and stale-command protection.
- A non-interactive browser status badge during an active unlock.
- Dashboard, health status, settings, theme selection, and local diagnostic export.
- A developer control that cancels only the current unlock without resetting usage or settings.
- Transactional typed persistence with corruption detection and fail-open startup behavior.
- Boot, user-unlock, date, time, timezone, screen, keyguard, and service-restart handling.
- Android 16 Advanced Protection status reporting.
- No account, telemetry, or advertising ID. Network access is used only to submit a user-recorded challenge video to the owner's verifier, which preprocesses it, asks the configured OpenRouter model for a repetition count, and deletes the temporary files.
- Today plus 30 days of browser-time and push-up-session report history.

## Requirements

- Android Studio or another Android toolchain with JDK 17.
- Android SDK Platform 36 and Build Tools installed.
- An Android 10 (API 29) or newer device.
- At least one supported browser installed: stable Chrome, Firefox, Edge, or Opera.

The project uses Gradle 8.14.5, Android Gradle Plugin 8.13.2, Kotlin 2.3.20, Jetpack Compose BOM 2026.06.00, and DataStore 1.2.1.

## Build

From this directory:

```bash
./gradlew assembleDebug
```

The ignored `local.properties` must provide the verifier endpoint and its shared bearer secret:

```properties
verifyUrl=https://your-verifier.example/v1/pushup-verify
verifySharedSecret=your-shared-secret
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected device with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Android app identity

This green-icon browser gate is installed as
`com.michalkrsik.browserrestrictor`. That ID is intentionally different from
the Calculator/GPS Internet Restrictor prototype, so both can be installed on
the same phone. Before installing any APK, follow the shared
[Android application ID registry](../ANDROID_APP_IDS.md) and verify the
package embedded in the APK.

## Device setup

1. Open **Internet Restrictor** and continue through onboarding.
2. On the Accessibility step, open Android's Accessibility settings and enable **Internet Restrictor — Protect browsers**.
3. On Android 13 or newer, a sideloaded APK may initially block that switch. Open the app's **App info**, use the three-dot menu, choose **Allow restricted settings**, and then return to Accessibility.
4. Usage Access is optional for the current personal MVP. Granting it prepares the permission path, but the app still truthfully reports **Protected with limited recovery** until history reconciliation is validated.
5. Use **Test browser gate**. The browser may draw one brief frame before Android reports the foreground change; the gate should then cover it and block its touches.

After setup, changing the Accessibility or Usage Access switch in Android settings is reflected in the dashboard health card.

## Verification

Run the complete local verification suite with:

```bash
./gradlew clean testDebugUnitTest :app:compileDebugAndroidTestKotlin lintDebug assembleDebug
```

The unit suite covers local-day and Monday-to-Sunday week boundaries, fixed waits, gate priority, unlocks, earned budget, active-use accounting, persisted push-up sessions, budget exhaustion, stale commands, latches, corruption detection, and persistence round trips. Compose gate tests are also compiled as part of the command.

To run the Compose instrumentation tests on a connected emulator or device:

```bash
./gradlew connectedDebugAndroidTest
```

The captures in `validation_artifacts/` predate this redesign and are retained only as a baseline. They are not current visual proof of the daily-only earned-budget flow.

## Architecture

- `domain/`: pure restriction engine, local-day math, fixed wait rules, and immutable state models.
- `data/`: serialized repository mutations and a typed binary DataStore serializer.
- `monitoring/`: protected-target foreground classification, Accessibility service, permissions, and device health.
- `overlay/`: full-screen gate and compact session badge window controllers.
- `ui/`: onboarding, dashboard, settings, gate, badge, reusable components, and theme.
- `diagnostics/`: bounded, local-only operational events that can be copied from the app.
- `receivers/`: boot and wall-clock boundary recovery signals.
- `server/`: the ffmpeg-backed push-up verifier. It accepts one authenticated MP4 upload up to 30 seconds, stretches four source frames per second from the complete clip into Gemini's one-frame-per-playback-second sampling path, and computes the single-video pass threshold from the returned repetition count.

The Accessibility service reacts only to window-level changes. It uses the active window package name to distinguish supported browsers, YouTube, Internet Restrictor, and other apps. It does not traverse the accessibility tree or read URLs, webpage text, form fields, typing, or screenshots.

## Important boundaries

- Supported packages are stable Chrome (`com.android.chrome`), Firefox (`org.mozilla.firefox`), Edge (`com.microsoft.emmx`), and Opera (`com.opera.browser`). Their use is combined into one budget and one active session.
- Other browsers and browser variants are outside the current scope.
- The real-time Accessibility signal is authoritative. Ambiguous gaps are left uncounted instead of being guessed.
- Usage Access history reconciliation is not yet implemented; the current build explicitly labels recovery as limited.
- Accessibility overlays cannot stop a user from disabling the service, force-stopping the app, uninstalling it, or using an unsupported browser.
- Physical-device checks are still required across target OEMs for foreground-event latency, touch blocking, TalkBack behavior, Incognito, Custom Tabs, picture-in-picture, multi-window, lock/unlock, reboot, and aggressive process killing.
- A release build still needs the owner's production signing configuration before distribution.

## Privacy and permissions

The manifest intentionally contains no VPN or general system-overlay permission. It requests only what the product needs:

- Accessibility service binding, granted manually by the user, for foreground detection and the accessibility overlay.
- Usage Access, granted manually and optional in this MVP, for the future bounded recovery path.
- Boot completion, to re-establish local period and time-boundary state.
- Android 16 Advanced Protection status query, to report device compatibility honestly.
- Package visibility for stable Chrome, Firefox, Edge, and Opera.
- Camera and internet access for the explicit push-up recording and owner-operated verification flow.

Challenge videos are temporarily uploaded to the owner's verifier and sent to OpenRouter through a zero-data-retention provider route. The verifier deletes source and processed files after every request and removes stale temporary directories after a crash. Backups are disabled so private usage state does not leave the device through Android backup. Bundled typefaces and their license are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Verification server

The server requires Node.js 22 or newer, `ffmpeg`, and `ffprobe`. Run its tests and start it with:

```bash
cd server
npm test
OPENROUTER_API_KEY=... VERIFY_SHARED_SECRET=... npm start
```

It listens on `127.0.0.1:8787` by default and is intended to sit behind HTTPS. The included user-service unit is the deployment template used by the prototype server.
