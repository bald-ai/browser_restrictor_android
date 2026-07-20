# AGENTS.md

## Android application identity

This project is the green-icon Chrome gate. Its installed Android identity is
`com.michalkrsik.browserrestrictor`. It must remain distinct from the
Calculator/GPS prototype at `com.michalkrsik.internetrestrictor`.

Before building for the phone or running `adb install`, read
[`../ANDROID_APP_IDS.md`](../ANDROID_APP_IDS.md) and perform its APK package-ID
check. Do not use `adb uninstall` to work around an install failure without
explicit user approval.

## Extended phone-control workloads

Before starting an extended phone-control workload, read
[`ADB_PHONE_CONTROL_PLAYBOOK.md`](ADB_PHONE_CONTROL_PLAYBOOK.md). This applies
to multi-step ADB UI automation, long-running on-device processing, cross-app
comparisons, repeated evidence capture, or another sustained phone
investigation.

Do not load the playbook for a simple one-off phone action such as installing
or launching an app, checking ADB connectivity, or performing a basic smoke
check.

## Real-phone Android validation

Use the Android SDK copy of ADB directly because `adb` may not be on `PATH`:

```bash
ADB="/Users/michalkrsik/Library/Android/sdk/platform-tools/adb"
```

Check the connected devices before starting real-phone validation:

```bash
"$ADB" devices -l
```

### Pairing the Nothing Phone over Wi-Fi

1. On the Nothing Phone, open **Settings -> System -> Developer options -> Wireless debugging** and enable it.
2. Tap **Pair device with pairing code**. Keep that screen open; it shows a pairing IP/port and a temporary pairing code.
3. On the Mac, run `"$ADB" pair <pairing-ip>:<pairing-port>` and enter the temporary pairing code shown on the phone.
4. Back on the main **Wireless debugging** screen, note the separate device IP/port and run `"$ADB" connect <device-ip>:<device-port>` if the phone does not appear automatically through mDNS.
5. Confirm the phone is listed as `device` with `"$ADB" devices -l`. Pairing and connection ports can differ and can change when Wireless debugging restarts.

The development phone is the Nothing A001/Galaga device. Depending on how wireless ADB resolves it, the serial may be the mDNS service name or an IP address and port. Valid examples include:

```text
adb-001923589001020-7nqWvz._adb-tls-connect._tcp device product:GalagaEEA model:A001 device:Galaga
192.168.91.52:<port> device product:GalagaEEA model:A001 device:Galaga
```

Always target that device explicitly with `-s <serial>` when an emulator is also connected. Never rely on ADB's default device selection.

If the A001/Galaga phone is not listed with status `device`, stop and report that real-phone validation is blocked. Do not substitute emulator results for requested real-phone validation.

## Development phone unlock

The phone PIN has temporarily been changed to `123456` for development. Agents are authorized to use this PIN to unlock the phone when installation, home-screen placement, or real-phone UI validation requires it.

After waking the phone, swipe up to show the PIN entry screen, enter `123456`, and submit it. Confirm the phone is unlocked before continuing with UI automation or screenshots.

## Screenshots taken on the Nothing Phone

When the user says they **took a screenshot on the phone**, interpret that as an existing screenshot in the Nothing Phone's Essential Space folder. Look for the newest relevant image in:

```text
/storage/emulated/0/Pictures/EssentialSpace/
```

Do not assume they mean a new ADB `screencap`. Use `"$ADB" shell ls -lt /storage/emulated/0/Pictures/EssentialSpace/` to find the newest file and `"$ADB" pull <remote-path> <local-path>` to retrieve it.
