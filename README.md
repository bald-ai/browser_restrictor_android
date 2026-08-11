# Internet Restrictor for Android

A native Android version of Internet Restrictor with an earned-budget push-up flow.

Internet Restrictor adds deliberate friction before Chrome, Firefox, Edge, or Opera opens. The user chooses a 10, 30, or 60 minute session, completes a fixed 12-second focused wait, and then receives a wall-clock unlock. Active, focused use across all supported browsers is charged to one shared daily budget: 30 minutes at local-day start plus 30 minutes for every verified five-push-up round completed that day.

This is a personal, user-controlled tool—not parental control, device management, or a tamper-proof blocker.

## What is implemented

- A five-step native onboarding flow with clear privacy and accessibility disclosure.
- A touch-blocking accessibility overlay over supported browsers.
- A fixed 12-second focused wait before every normal browser session.
- Normal 10, 30, and 60 minute wall-clock sessions.
- A rotatable front/back-camera five-push-up challenge on every gate that adds 30 minutes to today's budget after forgiving AI verification. It returns to the originating supported browser after success, without bypassing the normal wait, and its use is charged normally.
- Three emergency 30-minute access skips per local day, including when the daily budget is exhausted.
- A 30-minute base daily budget with unlimited 30-minute earned increments and no weekly or monthly pool.
- Exact local-midnight usage splitting and daily usage accounting.
- Out-of-time latching, expiry handling, stale-command protection, and reset semantics.
- A non-interactive browser status badge during an active unlock.
- Dashboard, health status, settings, reset confirmation, theme selection, and local diagnostic export.
- A developer control that cancels only the current unlock without resetting usage or settings.
- Transactional typed persistence with corruption detection and fail-open startup behavior.
- Boot, user-unlock, date, time, timezone, screen, keyguard, and service-restart handling.
- Android 16 Advanced Protection status reporting.
- No account, backend, telemetry, or advertising ID. Network access is used only to submit a user-recorded challenge video to the configured OpenRouter model for verification.
- One, two, and four hour protection pauses with automatic re-enabling at local noon.
- Active supported-browser reporting while protection is off, kept separate from restriction budgets.
- Informational notifications after 30 minutes of off-mode browser use, with bounded hourly follow-ups.
- Today plus 30 days of local report history split into protection-on and protection-off usage.

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

The unit suite covers local-day boundaries, fixed waits, gate priority, unlocks, earned budget, active-use accounting, skips, budget exhaustion, reset behavior, stale commands, latches, corruption detection, and persistence round trips. Compose gate tests are also compiled as part of the command.

To run the Compose instrumentation tests on a connected emulator or device:

```bash
./gradlew connectedDebugAndroidTest
```

The captures in `validation_artifacts/` predate this redesign and are retained only as a baseline. They are not current visual proof of the daily-only earned-budget flow.

## Architecture

- `domain/`: pure restriction engine, local-day math, fixed wait rules, and immutable state models.
- `data/`: serialized repository mutations and a typed binary DataStore serializer.
- `monitoring/`: supported-browser foreground classification, Accessibility service, permissions, and device health.
- `overlay/`: full-screen gate and compact session badge window controllers.
- `ui/`: onboarding, dashboard, settings, gate, badge, reusable components, and theme.
- `diagnostics/`: bounded, local-only operational events that can be copied from the app.
- `receivers/`: boot and wall-clock boundary recovery signals.

The Accessibility service reacts only to window-level changes. It uses the active window package name to distinguish supported browsers, Internet Restrictor, and other apps. It does not traverse the accessibility tree or read URLs, webpage text, form fields, typing, or screenshots.

Protection pauses leave the Accessibility service connected. This lets the app continue measuring active, focused supported-browser use without blocking it. Off-mode use is report-only and never consumes the normal budget. Protection returns after the chosen 1, 2, or 4 hour pause, or at 12:00 noon in the device's current timezone, whichever comes first. If a supported browser is already active at that moment, the current visit may continue and the next browser visit is protected.

The first informational reminder is sent after 30 minutes of active supported-browser use while protection is off. Further reminders are limited to once per hour and require at least another 15 minutes of use. Android 13 and newer requires notification permission; denying it does not affect tracking or enforcement.

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
- Exact alarms, to restore protection at the selected pause boundary or local noon.
- Notifications, to deliver informational off-mode usage reminders when permitted.
- Android 16 Advanced Protection status query, to report device compatibility honestly.
- Package visibility for stable Chrome, Firefox, Edge, and Opera.
- Camera and internet access for the explicit push-up recording and OpenRouter verification flow.

Backups are disabled so private usage state does not leave the device through Android backup. Bundled typefaces and their license are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
