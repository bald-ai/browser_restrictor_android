# Project documentation

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
| `plank` | continuous hold seconds | 30 seconds | 18 seconds | enabled |
| `outdoor_lat_pulldown` | repetitions | 30 seconds | 10 repetitions | enabled |
| `unsupported` | none | 30 seconds | none | fail-closed classification result |
| `sit_up` | repetitions | 30 seconds | TBD | waiting; classified as unsupported |

Credit is computed only on the client. 5 verified push-ups, 18 valid plank seconds, or
10 valid machine repetitions complete one set. Each set adds 3 spendable browser
minutes. Any combination of 4 sets unlocks YouTube until 04:00. Surplus is stored
independently per exercise and survives the 20:00 spendable-budget reset. The first
completed set of the logical day enables the emergency unlock. Monthly 9th-to-9th
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
completed sets and remainder. `POST /v1/pushup-verify` and its existing
`{is_success, confidence, reason, repetition_count}` response remain supported for the
older push-up clients.

The generic contract deliberately has no confidence field. The classifier must either
return one supported exercise with its raw measurement or return `unsupported`; the
client does not carry an unused confidence signal.

The preprocessor still samples at `MODEL_SAMPLE_FPS=4` using `fps=4,setpts=N/1/TB`.
For planks, the model reports the visually measured densified playback seconds and the
server divides by four and rounds to the nearest original second. Keeping this simple
arithmetic out of the model avoids inconsistent duration conversion. Repetition counts
are not scaled.

### Android boundary

`ExerciseChallengeCatalog` remains the client-facing list of stable IDs, metrics,
recording limits, and set sizes. The camera Activity has no picker: its primary CTA is
Start exercise, and every upload goes to `/v1/exercise-verify`. Sit-ups and other
unrecognized clips fail closed without writing credit.

Schema v10 stores typed per-exercise remainder plus a dedicated verified-push-up
counter so plank and machine sets cannot inflate push-up history. Every recording gets
a client-generated verification ID. Crediting the measurement and storing that ID are
one atomic state change, so replaying the same result cannot award credit twice. The
IDs reset with the rest of the logical-day state at 04:00.

## Future proposal: teach by demonstration

Status: unimplemented design proposal. The following ideas and API sketches are
not part of the current app or deployed verifier.

### Idea

Allow the owner to add a new physical task by recording a video of themselves doing
it correctly. The app sends that demonstration to the model, which creates a reusable,
structured definition of the task. Future challenge recordings are checked against
that definition without the owner having to write a task-specific model prompt.

The experience should feel like:

1. Tap **Teach a new task**.
2. Record one clear demonstration.
3. Review what the model understood.
4. Choose how much work completes one set.
5. Save the task.
6. The task becomes available to the generic exercise verifier in future recordings.

This is a natural extension of the current picker-less exercise challenge. Built-in
exercises and owner-taught tasks can ultimately be recognized through the same camera
flow and can award the same shared-pool minutes.

### What “learn” means

The first version should not claim to retrain or permanently fine-tune the foundation
model from one video. Instead, the model observes the demonstration once and produces
a durable, machine-readable `TaskDefinition`. That definition is stored by the app and
included in later verification requests.

The model therefore learns the task at the product level:

- the owner teaches by video rather than writing a prompt;
- the app remembers the generated definition;
- the generic verifier uses that definition for future recordings;
- the base model itself is not modified.

Keeping this distinction explicit gives us a practical first implementation and leaves
fine-tuning or an on-device motion model as possible later improvements.

### Proposed task definition

The teaching endpoint should return structured data rather than free-form prompt text.
A possible contract is:

```json
{
  "schema_version": 1,
  "suggested_name": "Chair squats",
  "metric": "repetitions",
  "observed_units": 6,
  "start_state": "Standing upright in front of a chair",
  "completion_state": "Hips touch the chair, then the person returns to standing",
  "required_motion": [
    "hips move down toward the chair",
    "hips visibly contact or closely approach the seat",
    "knees and hips extend back to the standing position"
  ],
  "do_not_count": [
    "partial descents",
    "a descent without returning to standing",
    "repetitions where the relevant body and chair are not visible"
  ],
  "visibility_requirements": [
    "full torso, hips, knees, and chair remain visible"
  ],
  "ambiguities": [],
  "model_summary": "Count one repetition for each complete stand-to-chair-to-stand cycle."
}
```

Supported metrics for the first iteration should match concepts the existing app can
credit reliably:

| Metric | Meaning | Example |
| --- | --- | --- |
| `repetitions` | Count complete motion cycles | squats, curls, step-ups |
| `hold_seconds` | Measure a continuously maintained state | wall sit, balance pose |
| `completions` | Recognize a single completed sequence | short mobility routine |

The owner chooses `units_per_set` after the draft is generated. Business rules such as
minutes awarded per set remain client-side, just as they do for built-in exercises.
The teaching model describes and measures the movement; it does not decide the reward.

### End-to-end flow

```text
Teach mode
  -> record demonstration
  -> preprocess video using the existing frame pipeline
  -> model generates a structured TaskDefinition
  -> owner reviews name, metric, and units per set
  -> app saves the accepted definition

Future challenge
  -> record an attempt
  -> send built-in and custom definitions with sampled video
  -> model classifies the visible task
  -> model returns task ID, metric, value, and reason
  -> existing domain logic converts value into sets and shared-pool minutes
```

The review step is important even though the owner does not write a prompt. A single
video can be ambiguous, and the model may infer the wrong boundary between one
repetition and the next. Review lets the owner correct the name, metric, or set size
without exposing prompt engineering.

### API sketch

#### Create a draft from a demonstration

`POST /v1/task-teach`

Multipart fields:

- `video`: the demonstration MP4;
- `task_id`: a client-generated stable ID used for idempotency.

Response:

```json
{
  "task_id": "custom_01J...",
  "definition": {
    "schema_version": 1,
    "suggested_name": "Chair squats",
    "metric": "repetitions",
    "observed_units": 6,
    "start_state": "...",
    "completion_state": "...",
    "required_motion": ["..."],
    "do_not_count": ["..."],
    "visibility_requirements": ["..."],
    "ambiguities": [],
    "model_summary": "..."
  }
}
```

#### Verify an attempt

The existing generic endpoint could be extended, or a new
`POST /v1/task-verify` endpoint could accept:

- the attempt video;
- the IDs and definitions of enabled custom tasks;
- the built-in task catalog version.

It should return the same raw-measurement shape used today:

```json
{
  "task_id": "custom_01J...",
  "metric": "repetitions",
  "value": 5,
  "reason": "Five complete chair-touch squat cycles were visible."
}
```

The server must validate the definition schema and bound all string and list sizes
before placing custom content in a model request.

### Storage approach

For the first version, store the accepted structured definition on the Android device
and continue deleting uploaded videos after processing. This preserves the current
temporary-video model and avoids creating a permanent server-side video library.

Suggested local state:

```text
CustomTask
  id
  name
  metric
  unitsPerSet
  definition
  createdAtWallMs
  enabled
  definitionVersion
```

Per-task remainder should be stored in a map keyed by the stable custom task ID. The
current fixed remainder fields for push-ups, plank, and the machine exercise will need
a schema migration before custom tasks can earn partial progress safely.

If text definitions alone prove insufficient, a later version can optionally retain an
owner-approved reference clip or derived visual representation. That should be a
separate product choice rather than an invisible change to the current deletion rule.

### Recognition strategy

#### MVP: generated specification

Generate the structured definition from one demonstration and use it in subsequent
multimodal verification prompts. This is the fastest route and requires no model
training infrastructure.

#### Stronger version: positive and negative examples

Let the owner record:

- two correct demonstrations from slightly different angles;
- optionally, one example that should not count.

The model can use these to refine the definition and identify ambiguity. This will be
more reliable for movements that resemble one another.

#### Later: motion embeddings or fine-tuning

If usage grows enough to justify it, extract pose or video embeddings and train a small
task-specific classifier. This could reduce repeated prompt size and make recognition
more deterministic, but it is not required to validate the product idea.

### Product boundaries for the first experiment

Start with short, visually measurable physical actions that fit within the existing
30-second recording flow. Good candidates have a visible start state, completion state,
and count or hold duration.

Defer tasks that require:

- reading text or inspecting a phone/computer screen;
- proving an outcome that is not visible in the clip;
- identifying exact weight, distance, resistance, or force;
- long routines with many ordered stages;
- safety-critical form assessment;
- recognizing other people without their participation.

The broader “record anything and teach it” vision can remain the direction, but a
physical-action MVP fits the app’s current reward model and makes success measurable.

### Failure behavior

Custom verification should fail closed, like unsupported built-in exercises. Award no
credit when:

- the custom definition is missing or malformed;
- the relevant body parts or equipment are not visible;
- the candidate matches multiple enabled tasks equally well;
- the model cannot identify complete units;
- the returned metric differs from the saved definition;
- a verification receipt has already been credited.

The response should explain what was missing so the owner can re-record from a better
angle. A failed match must not silently fall back to the closest-looking custom task.

### Implementation impact

#### Android

- Add a **Teach a new task** flow in Settings.
- Reuse the existing camera and upload pipeline in a distinct teaching mode.
- Add draft review, naming, metric confirmation, and units-per-set controls.
- Persist versioned custom definitions.
- Generalize exercise remainder storage from fixed fields to task-ID keyed entries.
- Include enabled custom definitions in generic verification requests.
- Show custom task progress in the challenge result and activity history.

#### Server

- Add a schema-constrained teaching endpoint.
- Use a fixed system prompt that turns a demonstration into `TaskDefinition` JSON.
- Validate and normalize all generated fields.
- Extend verification to compare attempts against enabled custom definitions.
- Preserve one-use verification receipts and temporary-file deletion.
- Add request-size limits for the number and total size of custom definitions.

#### Model evaluation

Build a small private evaluation set for each taught task:

- clear positive attempts;
- incomplete attempts;
- visually similar but different movements;
- bad camera angles and partial visibility;
- zero-repetition clips;
- transitions between two protected tasks.

Track classification accuracy, counting error, false-credit rate, and unsupported rate.
False credit is the most important failure because it directly creates spendable time.

### MVP acceptance criteria

The first experiment is successful when:

1. The owner can create a custom repetitive task without writing model instructions.
2. The generated definition is shown and can be accepted or discarded.
3. The task survives app restart and can be disabled or deleted.
4. A future recording can be classified against that definition and return a raw count.
5. The client awards sets using the configured units-per-set value.
6. Custom sets add to the same browser-and-X pool and YouTube set threshold.
7. Unsupported, ambiguous, and duplicate recordings award no credit.
8. Demonstration and attempt videos are deleted after processing.

### Open questions

- Is one positive demonstration sufficient, or should two be required before saving?
- Should custom tasks join the picker-less classifier immediately, or should the owner
  select a custom task during the initial experiment?
- May the owner edit model-generated recognition criteria, or only name and set size?
- How many enabled definitions can be sent before classification quality degrades?
- Should custom tasks count toward the monthly history, or should that remain strictly
  verified push-ups?
- Should a task definition be exportable so it can be backed up or shared later?
- If a saved definition is refined, how should old remainder and report history be
  associated with the new version?

### Recommended first prototype

Implement a narrow vertical slice with one owner-taught repetitive exercise:

1. Record one demonstration.
2. Generate and display the structured definition.
3. Let the owner confirm the name and repetitions per set.
4. Save it locally.
5. Require explicit selection of that custom task for the first verification prototype.
6. Compare several new attempts with the saved definition.
7. Only after evaluation, add custom tasks to the picker-less automatic classifier.

Explicit selection in the prototype isolates whether demonstration-derived definitions
work. Automatic classification among all built-in and custom tasks can then be tested
as a separate problem rather than mixing both uncertainties at once.
