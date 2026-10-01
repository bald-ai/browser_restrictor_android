# Friction v1 — implementation and test handoff

Date: 2026-09-16. Implemented, deployed and installed on the test phone.

## Installed system

| Part | V1 development installation |
| --- | --- |
| Phone | Nothing Phone A001 / Galaga, tested through wireless ADB |
| Launcher name | Friction v1 Dev |
| Application ID | `com.michalkrsik.browserrestrictor.v1dev` |
| Backend | `https://friction-v1-api-production.up.railway.app` |
| Railway project | `friction-v1-dev`, `b806bbbd-f21d-4546-a59c-6ebd81802ac9` |
| Service | `friction-v1-api`, `4e3282d5-f2af-4b8a-9005-4732b1814757` |
| Environment | `a1944e01-beba-4051-a84a-516a23c86d81` |
| Storage | Separate persistent volume mounted at `/data`, one replica |
| Voice | GPT-Live-1, Ripple, direct WebRTC audio; backend sideband |
| App actions | GPT-6 Astra, Low reasoning, Fast service tier; Responses functions |
| Video | Google Gemini 3.8 Flash through OpenRouter and ffmpeg preprocessing |

The original application ID and original exercise-verifier deployment are separate.
The original accessibility service, including Wispr, was restored after physical gate
testing. Voice/setup in the dev app can be used with the daily app still protecting
Chrome. Switch the active protection service to Friction v1 Dev only when testing
v1 blocking; use one blocker at a time.

## Implemented product

- Dynamic video repetition/seconds and GPS walking/sprint tasks; add/edit/pause/remove.
- Manual setup and voice/text changes use the same grouped review, horizontally
  scrollable cards, individual adjustment/removal, Apply button, and undo.
- Changes save atomically against the exact reviewed version. UI distinguishes
  server saved state from Android's persisted-and-applied acknowledgement.
- Existing blocking, browsing minutes, session presets, YouTube daily credits,
  emergency unlock, configurable reset clocks and action labels.
- Exact shared partial credit progress, decimal corrections and immutable receipts.
  Existing earnings retain their value after rate changes. No prior-day carryover.
- Camera capture/verification, GPS screen-off capture, local evidence retention,
  automatic retry and no duplicate rewards. Process death ends GPS recording at
  the last saved point; it does not resume the recording or count downtime.
- Fast voice acknowledgement while Astra works, current setup/card context and
  compact measured usage at conversation start; detailed browsing history on demand.
- Try it simulations and optional draft task test recordings without activation
  or changes to real rewards/progress.

## Practical evidence

| Check | Result / scope |
| --- | --- |
| Android domain/unit suite | 231 passing in each of v1Dev and the original debug variant (462 executions) |
| Android lint | Passed; zero errors, 31 warnings |
| Backend suite | 54 passing, including replay of the failed phone cancellation and negative/duplicate cases |
| Hosted reset boundaries | Three checks passed on the final deployment: expired minutes with current daily progress, expired day, and both periods current |
| Hosted API | Nine checks rerun successfully on the final deployment: authentication, grouped atomic save, retry idempotency, stale review rejection, acknowledgement, undo and version invalidation |
| Real Astra grouped change | Wait + label produced two review cards in 6.38s; selected-card amendment in 4.30s; neither saved before approval |
| Real Astra task creation | Asked for missing task details in 3.112s, built a complete Brush teeth task review in 6.283s; button saved in 182ms; targeted undo in 3.518s |
| Phone voice → card | Actual WAV speech sent as WebRTC microphone audio: final-deployment request-to-review 9.612s (earlier run 8.606s); received non-silent remote voice audio; no text-injection shortcut |
| Phone dashboard UI | Five tests passed: configured task launch, paused-task visibility, manual customization entry, decimal correction and invalid correction controls |
| Native GPS draft preview | 34.3 synthetic meters verified through native coordinator and hosted backend; both reward flags false, no native receipt/reward/config changes |
| Actual draft video preview | Review card opened CameraX; short real capture verified as zero; returned to the same pending card with the result. Backend and native rewards/configuration stayed unchanged |
| Try it on phone | Proposed eight-second wait counted down to Ready; stop returned to the same pending review without saving or opening an app |
| Real microphone and permissions | Normal phone UI requested Android audio permission, connected Live using the real unsilenced microphone, and released the microphone and backend call when End conversation was tapped |
| Real spoken cancellation | Passed on final deployment: actual audio during busy Astra reached the backend, invalidated the request, left no pending review or config change, and kept the voice call open; 4.644s from cancel-audio start to settled state |
| Phone button during voice | Apply → native persisted state and backend acknowledgement in 1.219s on final deployment (earlier run 910ms); button undo restored the original value while the call stayed open |
| Real Chrome blocking | Configured five-second wait measured 5.062s; restored twelve-second wait measured 12.074s, followed by actual Chrome access |
| Real phone camera | CameraX reached its 30-second recording cap, uploaded and verified a no-activity recording as zero; pending file cleaned after persistence |
| Real positive video | Existing 22.777s plank recording returned 18.0s, consistent with inspection/prior analysis; 59.67s including original 49.2MB upload; one paid call, $0.004958415 |
| Video negative control | Synthetic blue MP4 returned zero; correction rejected; repeated upload reused the same receipt |
| Hosted GPS | 12 scenarios passed using explicitly synthetic routes: qualifying walk/sprint, vehicle/drift/gap/poor/mock rejection, safe fallback, pinned rules, expiry and duplicate/concurrent submission |
| Real GPS lifecycle | Stationary phone: points grew 62→73 with screen off for 12s. Forced process stop retained evidence. Reopening verified zero meters, using the last saved point as the end time, and cleared the pending file |

The gate test used one clearly labelled instrumentation credit. It did not claim
the user performed an exercise. The fixture was removed successfully with the cleanup device test (one test passed).
The GPS route scenarios are simulated validation inputs; the stationary lifecycle
test is real phone GPS and does not establish outdoor distance accuracy.

## Bugs found and fixed through verification

- Runtime Live context messages exceeded the provider's 500-token limit, causing
  API errors during review. Runtime updates now use small bounded messages, with
  full setup only at session start. Actual phone voice was rerun successfully.
- Backend reads could expose changes before durable storage completed. Transactions
  now publish state only after the atomic disk write; failure/race tests cover it.
- Approval needed an immutable proposal version captured before model work, rather
  than approving whatever review happened to be latest afterward.
- Initial server revision zero needed to seed the device even when its defaults
  already carried a revision-zero placeholder.
- A delayed recording after the 20:00 browsing reset incorrectly lost same-day
  partial progress. Daily credits/progress remain eligible until 04:00; expired
  browsing minutes do not return. Arrival of late evidence preserves already
  earned current minutes. Tests cover retry, persistence, reset edits and undo.
- Video correction ceilings now use actual source duration, excluding the camera's
  preparation countdown. Decimal correction input no longer truncates values.
- Expired and manually completed recordings no longer offer invalid correction UI.

## Final deployment and cleanup

The final mixed-card layout was checked on the physical phone. A short setting
card now keeps its natural height, the task card remains fully scrollable, and
transparent footer padding does not intercept buttons. The phone Cancel request
button discarded the two-card test proposal; configuration and rewards stayed
unchanged. The final voice Apply/Undo regression passed, including audible return
audio and native acknowledgement. The real microphone permission/connection/end
flow also passed. All calls are closed, microphone capture has stopped, and the
original screen timeout is restored.

The final backend (`439f9c32-c24e-47a7-a2e8-2bd33b130538`) is deployed and both
APKs are installed. The real spoken cancellation rerun passed. Draft attempt replay
guards passed five unit tests and independent review. Hosted API and eligibility
checks passed. Five dashboard UI tests, native GPS draft preview and the actual
draft-camera flow passed. The UI tests require an awake, unlocked phone: an initial
locked-screen run could not see Compose views; the unlocked rerun passed all five.
Gate test credit is removed; original package/backend/protection state has been verified.

## Repeatable commands

Use the bundled Android Studio JDK when running Gradle:

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew :app:testV1DevUnitTest :app:testDebugUnitTest :app:lintV1Dev
./gradlew :app:assembleV1Dev :app:assembleV1DevAndroidTest -PandroidTestBuildType=v1Dev
```

Backend: run `npm ci` followed by `npm test` from `backend-v1/`.

APK: `app/build/outputs/apk/v1Dev/app-v1Dev.apk`.
Device test APK: `app/build/outputs/apk/androidTest/v1Dev/app-v1Dev-androidTest.apk`.
Device runner: `com.michalkrsik.browserrestrictor.v1dev.test/androidx.test.runner.AndroidJUnitRunner`.

`V1DashboardUiTest` is an ordinary device UI test. Paid voice tests explicitly
require `-e v1VoiceSmoke true`; the input WAV files must first be placed in the
dev application's private files directory. `V1PreviewDeviceTest` uses
`-e v1PreviewSmoke true`. Gate credit preparation/cleanup explicitly require
`-e v1GateFixture true`. None of these may target the original package.

Detailed, private test artifacts and HTTP harnesses are in ignored `work/v1-build/`:
`phone-voice-passed.json`, `phone-voice-cancellation-passed.json`,
`phone-gate-measured.json`, `phone-gps-lifecycle.json`,
`hosted-api-evidence.json`, `hosted-model-evidence.json`,
`hosted-task-creation-evidence.json`, `hosted-positive-video-evidence.json`, and
`hosted-gps-REPORT.md`, `phone-preview-evidence.json`, and
`phone-preview-gps-evidence.json`, `phone-voice-final.json`, and
`phone-hardware-mic-evidence.json`. Installation credentials are not included in this document.

## Practical limits

This is a usable experimental v1, with one backend replica and per-install identity.
There is no account recovery or persistent conversational memory. Outdoor walking
and sprint distance accuracy, varied lighting/exercise video, and ordinary human
microphone conversations still benefit from user testing. The voice transport was
tested with recorded speech, actual returned audio, and the real phone microphone
permission/connection flow; that does not substitute for evaluating every accent,
noisy room or Bluetooth headset.

The owner API budget is currently $3 across all v1 installations, with a five-minute
call limit. The ledger uses provider usage where available and conservative estimates
otherwise. It is a development spending guard, not an invoice. Manual configuration
and cancellation/undo remain available after the model budget is exhausted.


## Additional YouTube activity checks — 2026-09-16

Real hosted tests passed for push-ups (2 reps), plank (16.3 active seconds with rest excluded), and an Astra-created Brush teeth task (10 seconds). Three negative cases returned zero: talking about brushing, lying flat before a plank, and push-ups submitted as brushing teeth. All six duplicate uploads returned identical receipts without another provider call. Minimum-evidence and maximum-duration correction protections passed; a valid 9.5-second decimal correction passed. Two real Astra turns created a review; confirmation saved the new task without application changes or touching phone configuration. Total test ledger cost was $0.097326 (about 9.7 US cents).

Full evidence, source URLs, preparation, timings and limits: `work/v1-build/youtube-verification/REPORT.md`. This extends functional video coverage but does not establish accuracy across normal phone environments, liveness, or outdoor GPS performance.


## Full setup acceptance completed — 2026-09-17

All 11 setup settings now have actual phone UI → review → Apply → server → native disk/acknowledgement evidence. Grouped reviews, preview/edit/remove/cancel/undo, task lifecycle and native recorder routing passed. Real video rewards verified rate preservation; actual Chrome and YouTube exercised configured gates/buttons; actual timed browsing and daily resets passed. Agent clarification, a four-change request, selected-card references, and process-death persistence passed.

This pass found and fixed blocker-overlay retention over Home/Friction, stale idle reset state, review scrolling and editor error placement. Final installed APK SHA-256: `8e1cf32bb2b0ce7f9b7cf047275252ca959256f857dc324da2a76437e23ec0ba`. Current separate v1 backend deployment: `cb502bd3-6384-4969-a12d-6e12b3694481`. Server/native configuration restored and aligned at revision 68.

See `Dev/V1_SETUP_ACCEPTANCE.md` and `work/v1-build/setup-e2e/REPORT.md` for the requirement matrix, retained failure evidence, final pass artifacts and remaining field-testing limits. Earlier blanket claims that every setup option had been tested are superseded by this explicit acceptance record.


## 2026-09-17 dashboard clarity and walking reward

- Walking starter threshold now 250 metres per credit in native and backend defaults. Existing test phone installation updated through proposal/confirmation API: revision 71, 250 metres, 3 minutes per credit; awaiting device acknowledgement.
- Dashboard assistant entry is a contrasting elevated button with explicit “Open Friction assistant”, tap instruction and chevron. Recent result is separated beneath a divider under Recent activity, without an exercise-style outlined card. Correction/fallback remain available.
- Verification: 54 backend tests and 231 v1 Android unit tests pass; v1Dev APK assembled. Updated fractional-distance regression to 249.9 + 0.1 metres.
- ADB and mDNS reported no devices. Installation and visual device verification remain pending.
- Backend default deployment: 56ffe95f-b06d-409b-ae07-b554a1aeaf99.
- Architecture review: per-installation tokens/config/history/runtime support concurrent installations on one server. File-backed serialized writes; no multi-replica coordination or load-tested capacity. Existing global reserved-spend budget, registration throttle, one agent turn/voice session per installation. Per-installation daily quotas, global concurrency limits and invite gating are recommendations, not implemented in this pass.


## 2026-09-17 remove launch spending and registration throttles

Per user annotated screenshot: removed backend spending-cap enforcement and 20/hour/IP registration throttle. Retained usage accounting, 300-second voice sessions and per-installation agent/voice concurrency. API_BUDGET_USD removed from Railway variables; snapshot budget is null. No provider billing settings changed. All 55 backend tests pass, including above-cap accounting and 25 sequential same-address registrations. Deployment: 39cbba5d-baf0-42ae-9421-379e16acbe55.


## 2026-09-17 voice duration explanation

Server records the session ID and duration_limit reason before closing a five-minute call. The voice client reads that reason after remote closure and displays an accessible status message with a Start another conversation button. Manual hangups and other sessions do not show this message; stale callbacks cannot override a newly started call. All 58 backend/client tests pass, including mocked WebRTC closure and manual-hangup cases. No five-minute physical phone call performed in this pass.
