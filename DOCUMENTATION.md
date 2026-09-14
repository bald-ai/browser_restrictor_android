# Project documentation

## Source map

- `app/src/main/`: maintained Android app and product behavior.
- `app/src/test/` and `app/src/androidTest/`: Android regression and device tests.
- `server/src/` and `server/test/`: maintained video verifier and its tests.
- `app/src/main/java/com/michalkrsik/internetrestrictor/domain/Exercise.kt`: authoritative exercise weights; display set sizes derive from them.
- `app/src/main/java/com/michalkrsik/internetrestrictor/domain/RestrictionEngine.kt`: reward application and restriction rules.
- `design-preview/`: historical React design mockup; not the shipped Android UI.
- `prototypes/`: historical experiments; not maintained product code.
- `validation_artifacts/` and `review-reports/`: historical captures and review evidence.

## Challenge reliability and limits

Verified camera measurements and sprint results retain their receipt until saving succeeds;
"Retry saving" reuses that receipt without another recording or model call. The reward
transaction returns its awarded sets, remainder, and day, so crossing 04:00 does not
require the UI to infer the award from changing daily totals. Sprint session state lives
in the foreground service; camera verification and pending credit live in a retained
ViewModel. These preserve progress across Activity recreation within the running app.

Sprints stop automatically after 15 minutes and retain their evaluated result. Leaving
or starting over with unsaved sprint progress requires a discard confirmation. Missing
GPS coverage at the beginning of a recording counts toward the reliability fallback.
Permanent location denial routes the user to the app's system settings.

Normal session presets remain maximum wall-clock windows subject to the available
budget. An exhausted normal session can be replaced by an earned emergency skip.
Monthly push-up cycles follow the device timezone, like the daily accounting periods.

The verifier uses one playback densification factor for preprocessing and plank timing.
Plank output is converted to original seconds on the server. Results exceeding the clip's
length (holds) or three repetitions per original second are rejected as implausible.
The client permits 240 seconds for a response, retries explicit busy responses according
to Retry-After, and disconnects cancelled requests; the server cancels its active work
when that client connection closes.

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

The Android app sends exercise recordings to the dedicated Railway verifier,
which processes the video with FFmpeg and calls OpenRouter. Railway is the only
production hosting dependency for this app; there is no separate server to maintain.

| Setting | Current value |
| --- | --- |
| Railway project | [browser-restrictor](https://railway.com/project/da2c70e8-6f57-47a5-bab2-d697888abbae) |
| Service / environment | `exercise-verifier` / `production` |
| Service ID | `999755bc-8c57-4fd1-8130-90f0a8106b89` |
| Environment ID | `558bca8f-abd7-4e06-bd56-9a0acddf275e` |
| Region | `europe-west4-drams3a`, one replica |
| Verification endpoint | `https://exercise-verifier-production.up.railway.app/v1/exercise-verify` |
| Health endpoint | `https://exercise-verifier-production.up.railway.app/health` |
| Model | `google/gemini-3.8-flash` |
| Infrastructure definition | `.railway/railway.ts` |

The public verifier address is tracked in `gradle.properties`. The matching
`VERIFY_SHARED_SECRET` is configured in Railway and GitHub repository secrets;
local builds use `verifySharedSecret` in the ignored `local.properties` or the
environment. `OPENROUTER_API_KEY` is configured in Railway.

### Why the build now requires verifier settings

A previous APK built on a new computer had empty verifier settings and could not
start exercise recording. Packaging now fails when the URL or shared secret is
missing. GitHub's build supplies the saved settings and signing key, so a new
computer does not need to reproduce the local setup to obtain an installable APK.

### Verified baseline — 2026-09-06

- 112 Android unit tests, Android test compilation, lint, and APK assembly passed.
- All 14 server tests passed locally and during the container build.
- Live health and unauthenticated-upload rejection checks passed.
- A synthetic MP4 passed through HTTPS upload, FFmpeg, and OpenRouter and returned
  `unsupported` with zero credit.
- The [cloud build](https://github.com/bald-ai/browser_restrictor_android/actions/runs/34048306333)
  passed; its downloaded APK matched the local signing certificate and verifier settings.
- Physical camera recording and actual pushup counting still need a phone check.

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

The verifier address is checked into `gradle.properties`, so every checkout uses
the Railway service. For a local build, the ignored `local.properties` supplies
the shared verifier secret:

```properties
verifySharedSecret=your-shared-secret
```

Alternatively, supply `verifyUrl` and `verifySharedSecret` as Gradle properties,
or use the `VERIFY_URL` and `VERIFY_SHARED_SECRET` environment variables.
Gradle properties take precedence over `local.properties`, then environment variables.
APK and bundle packaging fails if either setting is missing, because that build
would disable exercise recording. Compilation and unit tests still run without
verifier settings.

The **Build Android APK** GitHub Actions workflow builds an installable APK
using the repository secrets `VERIFY_SHARED_SECRET` and
`ANDROID_DEBUG_KEYSTORE_BASE64`. The latter preserves the signing key between
builds, allowing updates without uninstalling the app. The workflow explicitly
selects the restored key through `ANDROID_DEBUG_KEYSTORE_PATH` and checks the
packaged APK certificate before publishing it. Local builds use the Android
toolchain's default debug key unless that environment variable is supplied. Download the
`internet-restrictor-android` artifact from the successful workflow run.

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
./gradlew clean testDebugUnitTest :app:compileDebugAndroidTestKotlin lintDebug assembleDebug
```

The unit suite covers local-day and Monday-to-Sunday week boundaries, fixed waits, gate priority, unlocks, earned budget, active-use accounting, persisted exercise sets, budget exhaustion, stale commands, latches, corruption detection, and persistence round trips. Compose gate tests are also compiled as part of the command.

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
- `server/`: the ffmpeg-backed exercise verifier. It accepts one authenticated MP4 upload up to 75 MB and 30 seconds, stretches four source frames per second from the complete clip into Gemini's one-frame-per-playback-second sampling path, and classifies the clip with a typed result. The legacy push-up endpoint remains supported. See [the exercise-verification contract](#exercise-verification-contract).

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

### Railway deployment

The `server/` directory is a self-contained Railway service. Its Dockerfile
installs Node.js 22 and FFmpeg, runs the server tests, and starts the verifier.
`.railway/railway.ts` configures a `/health` deployment check and one European replica.
No database or persistent disk is required; uploaded clips are temporary.

Set these service variables in Railway:

- `OPENROUTER_API_KEY`: the owner's OpenRouter API key.
- `VERIFY_SHARED_SECRET`: the same verifier secret supplied to the Android build.

The container listens on `0.0.0.0` and Railway's assigned `PORT`.
The production endpoint is
`https://exercise-verifier-production.up.railway.app/v1/exercise-verify`.
The [Railway project](https://railway.com/project/da2c70e8-6f57-47a5-bab2-d697888abbae)
contains the service `exercise-verifier` in the `production` environment.

Deploy with the Railway CLI from this repository:

```bash
npm ci
npx railway login
npx railway link --project da2c70e8-6f57-47a5-bab2-d697888abbae --environment production --service exercise-verifier
npm run hosting:plan
npm run hosting:apply
npm run hosting:deploy
```

The infrastructure file preserves the credentials already stored in Railway.
The Docker build uses `server/` as its root, while the project-level hosting
settings are applied by `hosting:apply` from the repository root.

### Local server development

The server requires Node.js 22 or newer, `ffmpeg`, and `ffprobe`. Run its tests and start it with:

```bash
cd server
npm test
OPENROUTER_API_KEY=... VERIFY_SHARED_SECRET=... npm start
```

Local development defaults to `127.0.0.1:8787`. `VERIFY_HOST` and `VERIFY_PORT`
can override the listening address for development and tests.


### Live verification check

With Node.js 22, FFmpeg, and the matching shared secret in the environment:

```bash
export VERIFY_URL=https://exercise-verifier-production.up.railway.app/v1/exercise-verify
node server/scripts/smoke.mjs
```

The script requires `VERIFY_SHARED_SECRET`; `FFMPEG_PATH` optionally selects the
FFmpeg executable. It checks health and authentication, then makes one paid model
request with a generated test pattern. The expected result is `unsupported` with
zero credit. Its temporary video is deleted afterward.

## Exercise verification contract

The launchable challenge is a picker-less generic recording. The verifier classifies
the clip, and the Android client converts the raw measurement into shared exercise
sets.

### Exercise readiness

| Exercise ID | Measurement | Recording limit | Units per set | Current state |
| --- | --- | ---: | ---: | --- |
| `push_up` | repetitions | 30 seconds | 5 repetitions | enabled |
| `plank` | continuous hold seconds | 30 seconds | 15 seconds | enabled |
| `outdoor_lat_pulldown` | repetitions | 30 seconds | 10 repetitions | enabled |
| `unsupported` | none | 30 seconds | none | fail-closed classification result |
| `sit_up` | repetitions | 30 seconds | TBD | waiting; classified as unsupported |

Credit is computed only on the client. 1 push-up = 2 machine reps = 3 plank seconds =
1 point. Every 5 combined points earns a 3-minute boost (one set). Fractional points
are stored exactly in sixths and mix across exercises. Any combination of 4 boosts
unlocks YouTube until 04:00. Shared progress survives the 20:00 spendable-budget reset.
Schema 11 preserves and combines legacy surplus once during normalization.
Tapping Start begins a 3-second preparation countdown before the 30-second recording;
leaving the activity cancels that preparation countdown. The first
completed set of the logical day enables the emergency unlock (granting 15 elapsed
minutes of free access without consuming earned budget). Monthly 9th-to-9th
history remains verified push-ups only.

### Verification contract

New clients use `POST /v1/exercise-verify` with one multipart field:

- `video`: one `video/mp4` file up to 75 MB and 30 seconds.

The endpoint does not take an exercise picker. It classifies among `push_up`,
`plank`, `outdoor_lat_pulldown`, and `unsupported`. The successful response is a raw
measurement:

```json
{
  "exercise_id": "plank",
  "metric": "hold_seconds",
  "value": 20,
  "reason": "..."
}
```

Business thresholds stay outside the model prompt. Domain code converts `value` into
earned boosts and combined point progress. `POST /v1/pushup-verify` and its existing
`{is_success, confidence, reason, repetition_count}` response remain supported for the
older push-up clients.

The generic contract deliberately has no confidence field. The classifier must either
return one supported exercise with its raw measurement or return `unsupported`; the
client does not carry an unused confidence signal.

The preprocessor still samples at `MODEL_SAMPLE_FPS=4` using `fps=4,setpts=N/1/TB`.
Video is resized proportionally to a 720-pixel short edge (1280×720 for 16:9
landscape, 720×1280 for portrait), then encoded as H.264 at CRF 23 without audio.
Gemini 3.8 Flash uses medium reasoning with a 16,384-token output allowance,
including reasoning tokens. `/health` exposes these active verification settings.
For planks, the model reports the visually measured densified playback seconds and the
server divides by four and rounds to the nearest original second. Keeping this simple
arithmetic out of the model avoids inconsistent duration conversion. Repetition counts
are not scaled.

### Android boundary

`ExerciseChallengeCatalog` remains the client-facing list of stable IDs, metrics,
recording limits, and set sizes. The camera Activity has no picker: its primary CTA is
Start exercise, and every upload goes to `/v1/exercise-verify`. Sit-ups and other
unrecognized clips fail closed without writing credit.

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
  - Remainder resets to 0 at the logical day boundary (04:00) alongside unfinished exercise progress; persists through the 20:00 evening budget reset.
- **Reward semantics:**
  - Every 120m qualifying distance earns 1 set (+3 browser minutes, counts towards 4 sets for YouTube unlock until 04:00, unlocks emergency skip on set 1).
  - Deduplication: `recordingId` is tracked in `creditedExerciseVerificationIds` to prevent duplicate awards.
  - Unreliable GPS fallback: When GPS was unreliable, user can confirm manual completion to receive exactly 1 set (deduplicated, no double crediting).
  - Destination unlock protection: Reopening YouTube from sprint results is blocked unless $\ge 4$ sets have been unlocked today.
  - Asynchronous save safety: `SprintChallengeActivity` captures immutable `SprintSaveState`, disables navigation and back presses during persistence, and only reflects awards upon confirmed storage commit.
