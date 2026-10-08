# Project documentation

## One V1 implementation

All Android builds use the configurable V1 backend in `backend-v1/` at
`https://friction-v1-api-production.up.railway.app`. The `debug` and `release`
variants retain the original application ID. `v1Dev` retains its separate
`com.michalkrsik.browserrestrictor.v1dev` ID for testing alongside the daily app.

Users select a configured video or GPS task. Android owns blocking, timers,
balances and activity history; the backend owns setup revisions, review/undo and
verification receipts. Existing local history and corrections migrate without
being deleted. The old recording Activity is a redirect to the task dashboard.
The legacy verifier service, its shared-secret configuration and both old
verification endpoints have been removed from this repository.

V1 includes manual setup, grouped change review, and GPT-Live-1 Ripple voice over
WebRTC. GPT-6.1 Sol handles supported app functions by default. Configuration changes are
acknowledged only after Android persists them.

The detailed implementation record, API contract, test evidence and handoff are in
`Dev/V1_IMPLEMENTATION_PLAN.md`, `Dev/V1_API_CONTRACT.md`, and
`Dev/V1_TEST_REPORT.md`. Those historical reports describe their original build;
this document describes the current single-backend layout.

## Daily allowance and reset

`dailyBudgetMinutes` accepts whole minutes from 0 to 30, default 0. Manual setup
and the in-app agent use the same reviewed configuration workflow. Saving a new
allowance preserves the current balance; it applies at the next `dayReset`.
The reset replaces unused time with the allowance and clears daily progress and
unlock eligibility. Tasks may earn additional minutes above the allowance cap.
There is one reset, default 04:00 in the phone's time zone.

The separate browsing reset has been retired from controls, agent schemas,
receipts and runtime scheduling. Backend schema 2 migrates existing settings
and removes retired review cards, issuing a new configuration revision for the
phone. Historical revision records remain available for old attempts. Android
accepts old settings without the new allowance as zero and preserves balances.
The old positional binary reset marker remains only for saved-data compatibility
and legacy correction eligibility; it never schedules a reset.

See [AGENT_CAPABILITIES.md](AGENT_CAPABILITIES.md) for the maintained list of
agent functions, settings, bounds and timing rules.

## Documentation available to the assistant

The backend preloads `backend-v1/docs/app-overview.md` in voice and text prompts
for immediate answers to common questions. It also ships the detailed
`backend-v1/docs/app-guide.md`. The existing `read_details`
function exposes an index and focused topics for settings, daily allowance,
sessions, verification, corrections, offline behavior, troubleshooting and other
product rules. `backend-v1/app-docs.mjs` loads it at startup and derives the topic
catalog. The agent reads details on demand and explains them using current app
context. `history` continues to return actual device statistics. Keep both documents
with the backend when packaging/deploying; the backend-local Git ignore rules
explicitly include them, and the Dockerfile checks their presence at build time; no separate documentation service or
network request is required. Changes to the guide take effect after backend
restart/deployment.

## Source map

- `app/src/main/`: maintained Android app and product behavior.
- `app/src/test/` and `app/src/androidTest/`: Android regression and device tests.
- `backend-v1/`: the only backend, including configurable video verification and its tests.
- `app/src/main/java/com/michalkrsik/internetrestrictor/v1/`: v1 setup sync, trusted conversation WebView, and task capture.
- `app/src/main/java/com/michalkrsik/internetrestrictor/domain/Exercise.kt`: authoritative exercise weights; display set sizes derive from them.
- `app/src/main/java/com/michalkrsik/internetrestrictor/domain/RestrictionEngine.kt`: reward application and restriction rules.
- `design-preview/`: historical React design mockup; not the shipped Android UI.
- `prototypes/`: historical experiments; not maintained product code.

## Challenge reliability and limits

V1 saves submitted video evidence for retry and pins each attempt to its task and
configuration revision. Duplicate uploads reuse the receipt, avoiding duplicate
model usage or rewards. Previews use the same evaluator without awarding credit.

The verifier samples four source frames per second and presents them at one
playback frame per second. Duration measurements are divided by four on the server;
repetition counts are unchanged. Measurements exceeding the clip's duration or
five repetitions per original second are rejected. Temporary server video files
are deleted in a `finally` block.

Saved local history, receipts, corrections, balances and configuration have migration
and serialization coverage in Android unit tests. Changing the evaluation pipeline
does not delete historical exercise IDs or their reward records.

## Personal notes and future ideas

Personal notes and future ideas belong in `Dev/` at the project root, as individual
`.md` files. Preserve that folder and its contents during cleanup. Do not delete,
move, rename, or consolidate those notes into `DOCUMENTATION.md` or other
operational documentation unless the user explicitly asks to change those notes.
An idea saved there is for later work; saving it does not authorize implementation.

This is the central reference for building, operating, and developing Internet
Restrictor. The [README](README.md) describes the current product; license notices
remain in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Production setup

The single backend is the Railway `friction-v1-api` service in project
`friction-v1-dev` (`b806bbbd-f21d-4546-a59c-6ebd81802ac9`). Its public origin is
`https://friction-v1-api-production.up.railway.app`; `/health` reports configuration.
It requires a persistent volume at `/data` for installation identities, task
configuration and receipts. Removing code does not decommission the old remote
service; deployment and remote resource removal are separate operations.

## Requirements

- Android Studio or another Android toolchain with JDK 17.
- Android SDK Platform 36 and Build Tools installed.
- An Android 10 (API 29) or newer device.
- At least one shared-pool app installed: stable Chrome, Firefox, Edge, Opera, or X.

The project uses Gradle 8.14.5, Android Gradle Plugin 8.13.2, Kotlin 2.3.20, Jetpack Compose BOM 2026.06.00, and DataStore 1.2.1.

## Build

From this directory:

```bash
./gradlew assembleDebug
```

The public V1 origin is checked into `gradle.properties` as `v1BackendUrl`.
Use `-Pv1BackendUrl=https://...` to override it. Packaging requires an HTTPS backend
and rejects the retired verifier host. Apps register individual installation tokens;
there is no shared verifier secret in the build.

The **Build Android APK** workflow requires only `ANDROID_DEBUG_KEYSTORE_BASE64`
for consistent signing. It runs unit tests, compiles device tests, runs lint and
assembles the debug APK. Local builds use the default debug key unless
`ANDROID_DEBUG_KEYSTORE_PATH` is supplied.

For the separate test installation, build `./gradlew :app:assembleV1Dev`.
Both variants use the same V1 task UI and evaluator.

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
the same phone. Before installing an APK, use the Android SDK's `aapt dump badging`
to confirm that its embedded package is `com.michalkrsik.browserrestrictor`.
The GitHub build performs this check before publishing its artifact.

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
npm test
npm run benchmark:test
npm run test:android -- :app:compileDebugAndroidTestKotlin lintDebug assembleDebug
```

The Android test command uses JDK 21 or newer and overrides any global Gradle JDK selection. Set `ANDROID_TEST_JAVA_HOME` or `JAVA_HOME` when needed. Ordinary tests print results to the terminal and clean their binary output after success or failure. Coverage, metrics, and mutations are manual-only; see `quality/README.md`.

The unit suite covers local-day and Monday-to-Sunday week boundaries, fixed waits, gate priority, unlocks, earned budget, active-use accounting, persisted exercise sets, budget exhaustion, stale commands, latches, corruption detection, and persistence round trips. Compose gate tests are also compiled as part of the command.

To run the Compose instrumentation tests on a connected emulator or device:

```bash
./gradlew connectedDebugAndroidTest
```
## Architecture

- `domain/`: pure restriction engine, local-day math, fixed wait rules, and immutable state models.
- `data/`: serialized repository mutations and a typed binary DataStore serializer.
- `monitoring/`: protected-target foreground classification, Accessibility service, permissions, and device health.
- `overlay/`: full-screen gate and compact session badge window controllers.
- `ui/`: onboarding, dashboard, settings, gate, badge, reusable components, and theme.
- `diagnostics/`: bounded, local-only operational events that can be copied from the app.
- `receivers/`: boot and wall-clock boundary recovery signals.
- `backend-v1/evidence.mjs`: the only video evaluator. It measures the selected task using strict structured output. See [the exercise-verification contract](#exercise-verification-contract).

The Accessibility service reacts only to window-level changes. It uses the active window package name to distinguish supported browsers, X, YouTube, Internet Restrictor, and other apps. It does not traverse the accessibility tree or read URLs, webpage text, form fields, typing, or screenshots.

## Important boundaries

- The shared browser pool contains stable Chrome (`com.android.chrome`), Firefox (`org.mozilla.firefox`), Edge (`com.microsoft.emmx`), Opera (`com.opera.browser`), and X (`com.twitter.android`). Their use is combined into one budget and one active session.
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
- Package visibility for stable Chrome, Firefox, Edge, Opera, and X.
- Camera and internet access for the explicit exercise recording and owner-operated verification flow.

Challenge videos are temporarily uploaded to the owner's verifier and sent to OpenRouter through a zero-data-retention provider route. The verifier deletes source and processed files after every request and removes stale temporary directories after a crash. Backups are disabled so private usage state does not leave the device through Android backup. Bundled typefaces and their license are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Verification server

Node.js 22 or newer, FFmpeg and ffprobe are required. From the repository root:

```bash
npm --prefix backend-v1 ci
npm test
npm --prefix backend-v1 start
```

Environment variables:

- `OPENROUTER_API_KEY`: video measurement provider credentials.
- `OPENAI_API_KEY`: conversation and voice credentials.
- `VIDEO_MODEL`: optional model override, default `google/gemini-3.8-flash`.
- `DATA_DIR`: persistent state directory, `/data` in Docker.
- `PUBLIC_BASE_URL`: exact public HTTPS origin.
- `HOST` / `PORT`: listening address and port.

The Dockerfile in `backend-v1/` installs FFmpeg. Deploy the existing V1 service with
`npm run hosting:deploy` after authenticating the Railway CLI. This command targets
the V1 project explicitly. No legacy deployment definition remains.

Backend tests exercise actual FFmpeg preprocessing with a synthetic MP4 and a stubbed
provider response, including repetitions, seconds, zero activity, malformed results
and retired JSON formats. They do not incur model charges.

## Exercise verification contract

Create an authenticated attempt at `POST /api/v1/attempts`, selecting a configured
`taskId`. Upload one MP4 (up to 75 MB and 30 seconds) to
`POST /api/v1/attempts/:attemptId/video`, with `endedAt` and `timeZone` fields.
The backend uses the task definition pinned when the attempt was created.

The model returns exactly:

```json
{"measured_units": 8, "confidence": 0.94, "reason": "Eight completed cycles"}
```

For repetitions the value is an integer. For time tasks the model returns playback
seconds; the backend divides by four and rounds to one decimal place. Confidence
must be between zero and one. The backend rejects extra fields, invalid values and
incomplete responses. It returns a receipt containing `measuredUnits`, the task
snapshot and reward eligibility. Rewards are calculated outside the model.

There is no classifier or push-up-specific evaluator, and no legacy shared-secret
endpoint. Historical `repetition_count` and classifier JSON are not accepted.

### Historical Android rewards

Existing exercise IDs, saved receipts and correction history remain readable.

Schema v11 stores shared progress in integer sixth-points, retaining legacy remainder fields
for one-time conversion, plus a dedicated verified-push-up
counter so plank and machine sets cannot inflate push-up history. Every recording gets
a client-generated verification ID. Crediting the measurement and storing that ID are
one atomic state change, so replaying the same result cannot award credit twice. The
IDs reset with the rest of the logical-day state at 04:00.

### GPS sprint activity

- **Entry points:** Dedicated "Start sprint" action buttons on the Dashboard, GateScreen (Paused, Out of Time), and YouTube locked overlays, completely separate from the camera exercise flow.
- **Recording lifecycle:** Managed by `SprintLocationService`, an Android location foreground service (`FOREGROUND_SERVICE_TYPE_LOCATION`) with an ongoing notification and `PARTIAL_WAKE_LOCK`. Designed to continue recording while the device screen is locked; physical device verification remains pending.
- **GPS acquisition:** Entry requires both `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` permissions (requested together per Android 12+ requirements), enabled GPS provider in system settings, and a fresh satellite fix ($\le 5\text{s}$ old) with horizontal accuracy $\le 25\text{m}$.
- **Movement qualification:**
  - Target sprint distance: 150m; passing set threshold: 120m per earned set.
  - Qualifying average speed: $\ge 15.0\text{ km/h}$ ($\ge 4.167\text{ m/s}$, with $14.95\text{ km/h}$ float tolerance).
  - Running entry: $\ge 3.6\text{ m/s}$ ($13\text{ km/h}$) allows accumulating steps into candidate efforts; walking ($\sim 1.4\text{ m/s}$) or stationary jitter is completely rejected.
  - Speed metric & pace evaluation: Qualification uses time-weighted pace per interval, using reported Doppler speed when present and robust coordinate fallback where absent. Modest coordinate distance undercounts do not discard reliable reported running speeds (accommodated by the 120m threshold). Gross discrepancies between speed and displacement flag GPS as unreliable.
  - Stationary oscillation defense: When speed is absent, small coordinate oscillations within the declared accuracy radius or with near-zero directional progress (<35% net displacement / total distance) are rejected, preventing standing still from generating running credit while preserving clean directional sprints.
  - Spike rejection: At least 2 steps (3 consecutive fixes) and $\ge 1.5\text{s}$ duration are required to qualify an effort, preventing isolated single-point coordinate spikes from accumulating credit.
  - Jump filter: Fixes with implied coordinate speed $> 15\text{ m/s}$ ($54\text{ km/h}$) are rejected as GPS multipath artifacts.
  - Accuracy filter & reliability: Fixes with horizontal accuracy $> 30\text{m}$ are discarded during recording. Unreliable GPS is flagged if $> 60\%$ of fixes are discarded for poor accuracy, if a GPS blackout gap $> 8\text{s}$ occurs, or if no usable fixes arrive in runs $\ge 4\text{s}$.
  - Qualification stability: The longest qualifying prefix $\ge 15.0\text{ km/h}$ is committed and preserved; slowing down (e.g. phone retrieval or jogging to a halt) never revokes already earned qualifying metres or set qualification. Live and final evaluation agree.
  - Vibration: Single 500ms haptic pulse fires at each newly reached set boundary (120m, 240m, etc.).
- **Carryover & persistence (Schema 12):**
  - Unearned qualifying metres carry over across recordings via `DailyCounter.sprintMetersRemainder` (bounds: $0 \dots 119\text{m}$).
  - Remainder resets to 0 at the configured daily boundary (04:00 by default) alongside unfinished exercise progress.
- **Reward semantics:**
  - Every 120m qualifying distance earns 1 set (+3 browser minutes, counts towards 4 sets for YouTube unlock until 04:00, unlocks emergency skip on set 1).
  - Deduplication: `recordingId` is tracked in `creditedExerciseVerificationIds` to prevent duplicate awards.
  - Unreliable GPS fallback: When GPS was unreliable, user can confirm manual completion to receive exactly 1 set (deduplicated, no double crediting).
  - Destination unlock protection: Reopening YouTube from sprint results is blocked unless $\ge 4$ sets have been unlocked today.
  - Asynchronous save safety: `SprintChallengeActivity` captures immutable `SprintSaveState`, disables navigation and back presses during persistence, and only reflects awards upon confirmed storage commit.


### Correcting the latest verified exercise

The review screen and dashboard Last set card offer **Wrong result?** only for a
saved supported exercise whose original verifier value was greater than zero.
Zero, unsupported, and failed reviews do not authorize manual entry. The original
positive measurement is retained even if a later correction reduces the count to zero.
Opening the editor cancels the two-second automatic return; after closing it, the
user continues manually. The editor supports ±1/2/3, an exact whole count, changing
the supported exercise, and a local flag without changing credit.

Schema 13 appends the latest exercise receipt to the existing state format. Edits
atomically replace its contribution to shared points, earned/spendable rounds,
YouTube eligibility, daily reports, activity events, and monthly push-up totals.
Repeated saves of the same count do not add credit. Storage failures retain the
previous result for retry. Older state files migrate without creating an editable
receipt. Corrections are limited to the latest saved camera set in the same budget
period: the configured daily reset closes editing, while the
Last set card remains visible. A new positive camera result replaces that card.

Schema 14 appends a permanent local correction history to the same state
transaction. Every saved edit that actually changes the count or the exercise type
appends an immutable entry recording the correction time in epoch milliseconds,
the receipt's verification ID, the immutable reviewer count and type, and the
corrected count and type. Both types are stored as stable wire strings (exercise
IDs today) so other validation kinds can reuse the entry later. Re-saving
identical values and flag-only marks add no entry. The history survives restarts,
newer recordings, both daily resets, and report-history clearing with no age or
count truncation; only deleting the app's data or uninstalling removes it. There
is no online sync, export, or history UI yet.
