# Internet Restrictor for Android

A native Android version of Internet Restrictor, built from the sibling [Android Chrome project specification](../internet_restrictor_desktop/Dev/ANDROID_CHROME_PROJECT_SPEC.md).

Internet Restrictor adds deliberate friction before stable Google Chrome opens. The user chooses a 10, 30, or 60 minute session, completes a focused wait, and then receives a wall-clock unlock. Only active, focused Chrome use is charged to the daily, weekly, and monthly budgets.

This is a personal, user-controlled tool—not parental control, device management, or a tamper-proof blocker.

## What is implemented

- A five-step native onboarding flow with clear privacy and accessibility disclosure.
- A touch-blocking accessibility overlay over stable Chrome (`com.android.chrome`).
- Focused waits with the extension's escalating wait-cost rules.
- Normal 10, 30, and 60 minute wall-clock sessions.
- A rotatable front/back-camera five-push-up challenge on every gate that grants 30 budget-free minutes after forgiving AI verification.
- Three emergency wait skips per local day, without budget bypass.
- Daily, Monday-based weekly, and calendar-month budgets.
- Exact local-midnight usage splitting and all-period usage accounting.
- Out-of-time latching, expiry handling, stale-command protection, and reset semantics.
- A non-interactive Chrome status badge during an active unlock.
- Dashboard, health status, settings, reset confirmation, theme selection, and local diagnostic export.
- A developer control that cancels only the current unlock without resetting usage or settings.
- Transactional typed persistence with corruption detection and fail-open startup behavior.
- Boot, user-unlock, date, time, timezone, screen, keyguard, and service-restart handling.
- Android 16 Advanced Protection status reporting.
- No account, backend, telemetry, advertising ID, or network permission.
- One, two, and four hour protection pauses with automatic re-enabling at local noon.
- Active Chrome reporting while protection is off, kept separate from restriction budgets.
- Informational notifications after 30 minutes of off-mode Chrome use, with bounded hourly follow-ups.
- Today plus 30 days of local report history split into protection-on and protection-off usage.

## Requirements

- Android Studio or another Android toolchain with JDK 17.
- Android SDK Platform 36 and Build Tools installed.
- An Android 10 (API 29) or newer device.
- Stable Google Chrome installed.

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

This green-icon Chrome gate is installed as
`com.michalkrsik.browserrestrictor`. That ID is intentionally different from
the Calculator/GPS Internet Restrictor prototype, so both can be installed on
the same phone. Before installing any APK, follow the shared
[Android application ID registry](../ANDROID_APP_IDS.md) and verify the
package embedded in the APK.

## Device setup

1. Open **Internet Restrictor** and continue through onboarding.
2. On the Accessibility step, open Android's Accessibility settings and enable **Internet Restrictor — Chrome gate**.
3. On Android 13 or newer, a sideloaded APK may initially block that switch. Open the app's **App info**, use the three-dot menu, choose **Allow restricted settings**, and then return to Accessibility.
4. Usage Access is optional for the current personal MVP. Granting it prepares the permission path, but the app still truthfully reports **Protected with limited recovery** until history reconciliation is validated.
5. Use **Test Chrome gate**. Chrome may draw one brief frame before Android reports the foreground change; the gate should then cover Chrome and block its touches.

After setup, changing the Accessibility or Usage Access switch in Android settings is reflected in the dashboard health card.

## Verification

Run the complete local verification suite with:

```bash
./gradlew clean testDebugUnitTest :app:compileDebugAndroidTestKotlin lintDebug assembleDebug
```

The unit suite covers period boundaries, wait formulas, gate priority, unlocks, active-use accounting, skips, budget exhaustion, reset behavior, stale commands, latches, corruption detection, and persistence round trips. Compose gate tests are also compiled as part of the command.

To run the Compose instrumentation tests on a connected emulator or device:

```bash
./gradlew connectedDebugAndroidTest
```

The checked emulator captures are in `validation_artifacts/`: [onboarding](validation_artifacts/final_onboarding.png), [dashboard](validation_artifacts/dashboard.png), [live Chrome gate](validation_artifacts/chrome_gate_final.png), and [active-session badge](validation_artifacts/chrome_badge_home_02.png).

## Architecture

- `domain/`: pure restriction engine, period math, wait pricing, and immutable state models.
- `data/`: serialized repository mutations and a typed binary DataStore serializer.
- `monitoring/`: Chrome foreground classification, Accessibility service, permissions, and device health.
- `overlay/`: full-screen gate and compact session badge window controllers.
- `ui/`: onboarding, dashboard, settings, gate, badge, reusable components, and theme.
- `diagnostics/`: bounded, local-only operational events that can be copied from the app.
- `receivers/`: boot and wall-clock boundary recovery signals.

The Accessibility service reacts only to window-level changes. It uses the active window package name to distinguish Chrome, Internet Restrictor, and other apps. It does not traverse the accessibility tree or read URLs, webpage text, form fields, typing, or screenshots.

Protection pauses leave the Accessibility service connected. This lets the app continue measuring active, focused Chrome use without blocking it. Off-mode use is report-only and never consumes the normal budgets. Protection returns after the chosen 1, 2, or 4 hour pause, or at 12:00 noon in the device's current timezone, whichever comes first. If Chrome is already active at that moment, the current visit may continue and the next Chrome visit is protected.

The first informational reminder is sent after 30 minutes of active Chrome use while protection is off. Further reminders are limited to once per hour and require at least another 15 minutes of use. Android 13 and newer requires notification permission; denying it does not affect tracking or enforcement.

## Important boundaries

- Version 1 protects only stable Google Chrome. Other browsers and Chrome variants are intentionally outside scope.
- The real-time Accessibility signal is authoritative. Ambiguous gaps are left uncounted instead of being guessed.
- Usage Access history reconciliation is not yet implemented; the current build explicitly labels recovery as limited.
- Accessibility overlays cannot stop a user from disabling the service, force-stopping the app, uninstalling it, or using another browser.
- Physical-device checks are still required across target OEMs for foreground-event latency, touch blocking, TalkBack behavior, Incognito, Custom Tabs, picture-in-picture, multi-window, lock/unlock, reboot, and aggressive process killing.
- A release build still needs the owner's production signing configuration before distribution.

## Privacy and permissions

The manifest intentionally contains no `INTERNET`, VPN, or general system-overlay permission. It requests only what the product needs:

- Accessibility service binding, granted manually by the user, for foreground detection and the accessibility overlay.
- Usage Access, granted manually and optional in this MVP, for the future bounded recovery path.
- Boot completion, to re-establish local period and time-boundary state.
- Exact alarms, to restore protection at the selected pause boundary or local noon.
- Notifications, to deliver informational off-mode usage reminders when permitted.
- Android 16 Advanced Protection status query, to report device compatibility honestly.
- Package visibility for stable Chrome only.

Backups are disabled so private usage state does not leave the device through Android backup. Bundled typefaces and their license are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
