# V1 implementation plan and working record

Started 2026-09-16. User explicitly authorized implementing and testing v1, including a separate backend and Android installation. Work in the current checkout/branch. Preserve existing uncommitted changes. This document carries the later conversation decisions that supersede older design notes.

## Intended result

An installable Friction v1 Dev app with real blocking and rewards, configurable video/GPS tasks, manual settings, and a conversational interface powered by GPT-Live-1 Ripple over direct WebRTC plus GPT-6 Astra Low Fast through Responses function calls. The existing installed app and production exercise-verifier remain operational and unchanged by deployment work.

## Accepted product boundaries

- Deliberate friction is the purpose. Analytics are supplementary. Trust users to tune friction.
- Keep the two existing protected-app mechanisms: browsers/X spend minutes; YouTube requires credits. Preserve earned rewards when settings change.
- Starter tasks: push-ups, plank, sprint, walk (1 km/credit). Tasks have stable IDs, names, approved judging instructions, video/GPS verifier, repetitions/seconds/meters, units per credit and correction minimum. Add/edit/pause/remove tasks; agent cannot invent verifiers or arbitrary app UI/code.
- Settings include wait (including zero), minutes per credit, session presets, daily/budget reset times, YouTube credits, emergency duration, credit and unlock button labels. Emergency earning stays one unlock after the first credit of the day. Existing defaults are retained.
- Shared partial credit progress and earned time expire at their reset boundaries. No yesterday carryover, duplicate rewards or retrospective repricing. Correction eligibility requires observable minimum evidence. Preserve existing correction work and history.
- Video recording remains capped at 30 seconds. GPS continues with screen off, walking uses drift/accuracy/gap/vehicle checks, manual stop and four-hour cap. Sprint retains appropriate speed checks. GPS recordings survive lost connectivity for validation; no reward before validation. Process death ends an attempt, preserving captured evidence for submission, not resuming that recording.
- Yes/no tasks, persistent conversational memory, arbitrary protected-app groups, direct task-specific unlock rules, account restore/export and editing windows remain deferred.

## Conversation and visual UX

- Preload current setup, selected/visible card, pending proposal, application status and concise real usage summary. Retrieve detailed history/docs only when needed; refresh after edits and swipes. Read fresh state before writes.
- Give Live and Astra separate prompts: fast natural acknowledgement and listening for Live; precise supported actions/clarification for Astra. No fabricated progress or premature success. Backend work does not stop voice. Interruptions, corrections, cancellation and button approval continue during a call.
- Multiple requested changes form one proposal containing horizontally swipeable cards. One card per task/setting, related task edits grouped; show position and next-card affordance. Adjust/remove individual cards. One Apply all button plus voice approval. Visible card resolves "this".
- Approval binds to the exact displayed proposal version; voice and button approvals are idempotent. Apply the group atomically or return an actionable error. Show review/applying/applied/problem status, subtle success feedback and Undo. Android applied acknowledgement is distinct from server saved/read-back.
- Cancellation discards pending work or restores committed values; late model results cannot apply. Undo groups or a selected change, preserve unrelated newer edits, reject conflicts. No second approval needed for explicit cancel/undo.

## Isolation and implementation choices

- New backend folder/deployment: `backend-v1/`; separate Railway project/service/storage/environment. Never deploy `server/` or mutate the old Railway service. Reuse existing verifier source only by copying/adapting into the new service.
- Dev app ID: `com.michalkrsik.browserrestrictor.v1dev`; label Friction v1 Dev. No upgrade/uninstall/clear of the original package. Keep its signing and endpoint configuration intact. Only one blocker enabled during physical gate testing; restore previous permission state afterward.
- Server owns editable config and revisions; Android persists applied config and owns runtime balances/timers/history. Recent usage is synchronized for truthful answers. Automatic per-install credentials, no signup/recovery in v1. Provider keys stay server-side.
- Android voice UI may use a dedicated trusted-origin WebView for the tested browser WebRTC path, with a narrow native bridge. The rest of the app remains Android UI and real restriction logic. No API keys in WebView or APK. Do not expose the bridge to arbitrary navigation.
- Network failure preserves last applied enforcement, local reset/timer behavior and eligible GPS evidence. It never automatically unlocks or silently confirms unsaved edits. Unique operation/attempt IDs make retries safe.
- Per-task rules pin to recording start; attempts belong to the period in which recording ends. Delayed validation after the browsing reset may still count toward current daily credits and partial progress, with no expired minutes restored. Once the daily period expires, it adds history only. Reset changes/undo cannot resurrect expired balances.
- Include configurable owner API budget controls before public paid access; preserve existing experiment spend limits during development and record actual test cost. Do not consume unbounded calls or silently change the selected models.

## Parallel work boundaries (after this plan is saved)

1. Backend: `backend-v1/` and API contract, persistence/identity, prompts/tools/group proposals/voice, evidence verification and backend tests. No cloud mutations.
2. Android domain/data: `domain/`, `data/` and their tests; configurable rules, task receipts, shared progress, periods, serialization and migration. Coordinate public methods/models with UI worker.
3. Android UI/integration: `ui/`, new v1 network/voice code, MainActivity/AppContainer integration, task capture/GPS, manual controls and UI tests. Coordinate contract with other workers. Do not edit build.gradle/manifest (root owns these).
4. Root: deployment isolation/credentials, build identity/manifest/dependencies, cross-component review, Android builds/device tests, integration fixes, final documentation and handoff.

## Build and verification gates

- [x] Record initial installed-package state and old backend health; validate available credentials.
- [x] Define shared configuration/API contracts and parallel file ownership.
- [x] Domain migration and configurable restriction behavior pass regression tests.
- [x] New backend passes identity isolation, atomic proposal, stale approval, undo/cancellation, reconnect and verifier tests.
- [x] Android compiles, unit tests/lint/instrumentation compile pass; dev APK identity verified before install.
- [x] Separate hosted deployment health, persistence across restart and authenticated access verified; old service health unchanged.
- [x] Physical flow: ask wait change → swipe/review → button confirmation during voice → Android acknowledgement → real gate timing → undo and gate check.
- [x] Grouped task/settings change, ambiguous reference, clarification and cancellation while work runs exercised.
- [x] Video/GPS recording, measurable receipt, correction and reward path exercised with practical evidence; simulated tests labeled separately.
- [x] Connection loss/retry, repeated submit, process death, daily boundaries and reward preservation tested at relevant layers.
- [x] Final package, endpoints, known limitations and repeatable tests documented; original app remains installed and its backend unchanged.

The first successful setting flow is a milestone, not completion of the full task. Mark items complete only with evidence. Keep updating the working record and persist until the requested v1 is usable or a concrete external blocker prevents the remaining step.

## Working record

- Baseline: 183 existing Android unit tests, zero failures (existing outputs up to date); JDK is `/Applications/Android Studio.app/Contents/jbr/Contents/Home`.
- Original installed package is `com.michalkrsik.browserrestrictor`, last updated 2026-09-14. Package and accessibility state recorded under ignored `work/v1-build/`. Original verifier health recorded and healthy.
- New Railway project `friction-v1-dev` (`b806bbbd-f21d-4546-a59c-6ebd81802ac9`), service `friction-v1-api` (`4e3282d5-f2af-4b8a-9005-4732b1814757`), environment `a1944e01-beba-4051-a84a-516a23c86d81`, persistent volume mounted at `/data`. Dedicated URL `https://friction-v1-api-production.up.railway.app`. Provider credentials configured without displaying values; old service only read.
- Dev variant is `v1Dev`: assemble with `assembleV1Dev`, run units with `testV1DevUnitTest`; select device-test variant with `-PandroidTestBuildType=v1Dev`. Packaging rejects a missing/non-HTTPS/original verifier URL. `IS_V1` gates integration; original variant retains old package identity.
- Shared API contract: [V1_API_CONTRACT.md](V1_API_CONTRACT.md). Root API smoke harness under ignored `work/v1-build/hosted-smoke.mjs`; no paid calls in that harness.
- Early review caught and assigned first-sync revision-zero seed mismatch, conflicting configuration bounds, and voice reconnect/permission lifecycle behavior before device testing.

- Final implementation verification: 231 unit tests passed in each Android variant, lint zero errors, 54 backend tests, nine hosted API checks and three hosted reset-boundary checks. The separate APK is installed. Actual phone voice/apply/undo, spoken cancellation during Astra work, measured Chrome gate timing, real camera/GPS lifecycle and draft preview flows have evidence in [V1_TEST_REPORT.md](V1_TEST_REPORT.md).
- Mixed short-setting and tall-task reviews exposed a layout issue during physical testing. The final UI adjustment makes the carousel follow the visible card height and prevents transparent footer padding from intercepting taps. Final phone visual inspection and cleanup follow that deployment.

- Handoff verification complete: final backend deployment `439f9c32-c24e-47a7-a2e8-2bd33b130538`; physical short/tall cards, Apply/Undo voice regression and ordinary hardware microphone permission/connect/end flow passed. Discarded test proposals, removed fixture credit, closed calls, restored original protection services and original screen timeout. The dev app is ready to use alongside the original daily installation. Outdoor route accuracy and varied real user conversations remain field-testing limits, detailed in the report.
