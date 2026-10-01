# Friction v1 API contract

Backend prefix `/api/v1`; all protected requests use `Authorization: Bearer <token>`. JSON UTF-8; errors `{error, code?}` with 400 invalid, 401 identity, 409 conflict, 429 budget, 503 provider unavailable. Millisecond Unix timestamps. This new service has no dependency on production.

## Installation and synchronization
- `POST /api/v1/installations` `{installationId?: UUID}` → `{installationId, token, config, revision:0}`. Persist both returned identity values privately. Repeating registration with an existing ID never returns its secret. Browser prototype can register its own independent installation.
- `GET /api/v1/state` → `{installationId, config, revision, appliedRevision, applicationStatus, proposals, changes, messages, busy, selectedCardId, statistics, model, voice, usage}`. `applicationStatus` is `applied` only after native acknowledgement; browser-only saves remain `awaiting_device`.
- `GET /api/v1/config` → `{config, revision, appliedRevision}`.
- `POST /api/v1/ack` `{revision, configHash?: string}` after Android durably stores and activates exact returned config → same config envelope. Optional hash SHA256 canonical JSON; revision is required.
- `POST /api/v1/statistics` `{summary: object, events?: array}` → `{ok:true}`. Android owns runtime balances/timers/history. Include truthfully measured current balances, period/reset information, counts and recent events; server never invents these.

## Canonical configuration
`{settings:{waitSeconds:12, minutesPerCredit:3, sessionMinutes:[3,5,10], budgetReset:"20:00", dayReset:"04:00", youtubeCredits:4, emergencyMinutes:15, creditName:"credits", unlockLabel:"Unlock", youtubeUnlockLabel:"Unlock YouTube", emergencyUnlockLabel:"Emergency unlock"}, tasks:[{id:"pushups",name:"Push-ups",verifier:"video",metric:"repetitions",unitsPerCredit:5,correctionMinimum:1,instructions:"…",paused:false,gpsMode:null}]}`.

Task metric `repetitions|seconds|meters`; video supports repetitions/seconds, GPS meters only. `gpsMode` null for video, `walk|sprint` for GPS. Task IDs stable; removal excludes from future attempts but history/attempt snapshots survive. Starter IDs pushups, plank, sprint, walk (5 reps / 15 seconds / 120 meters / 1000 meters per credit). Full configuration is validated by backend before a review is accepted.

## Proposals, manual changes, confirmation and undo
- `POST /api/v1/proposals` `{config: fullDesiredConfig, expectedRevision, title?: string}` → proposal. Used by manual settings/task editor; contains only changed cards. Backend computes trusted before/after difference.
- Proposal `{id, version, baseRevision, title, status:"pending|cancelled|saved|undone", cards:[{id, kind:"setting|task", key, title, before, after}], changeId?, createdAt}`.
- `POST /api/v1/proposals/:id/remove` `{version,cardId}` → revised proposal; remove one card. Re-review required after every change.
- `POST /api/v1/proposals/:id/confirm` `{version, operationId: UUID}` → `{change, config, revision, appliedRevision, applicationStatus}`. Native and voice approvals share this exact operation. Duplicate approval returns same receipt. Whole group is atomic and stale revision/version fails before any change.
- `POST /api/v1/cancel` `{proposalId?: string, changeId?: string, cardId?: string, operationId?: UUID}` → `{cancelled, restored?, ...state envelope}`. Priority cancellation invalidates running model turn immediately, then discards matching pending proposal or undoes last group; cardId targets only selected change. Undo only restores touched values and rejects overlapping newer changes.
- `POST /api/v1/context` `{selectedCardId?: string|null}` updates current visible card for “this”.
- `POST /api/v1/chat` `{message: string}` → `{text, ...state envelope}`. Proposals never save without approval. Button endpoints remain available while voice/backend work is active.

## Voice and embedded conversation UI
- Trusted origin page `/` renders chat, horizontal cards, button confirmation and voice. Native injects **only** narrow `FrictionNative` bridge, never provider credentials.
- `FrictionNative.bootstrap()` returns JSON `{installationId,token}` (optional baseUrl ignored; page is same-origin).
- `FrictionNative.configChanged()` notifies native to fetch `/config`, persist/apply, then `/ack`. Web UI also polls application status. Call for every newly saved/undone revision; idempotent.
- `FrictionNative.close()` optional close conversation.
- Android grants microphone capture only to its configured trusted HTTPS origin; external navigation leaves or is blocked and bridge unavailable. Native keeps voice WebView alive while conversation visible.
- `POST /api/v1/voice/session` `{sdp:string}` → `{sessionId,sdp}` (WebRTC SDP answer). GPT-Live-1 Ripple direct media. Backend sideband executes asynchronous Astra Low Fast functions; returns useful progress and outcomes without blocking listening. The authenticated server sideband recognizes whole generic cancel/undo commands after 900 ms of stable accumulated input text and uses the same cancellation operation even when Live emits no second delegation. This is an application stability policy, not an authoritative audio turn boundary; negative/quoted/continued fragments disqualify the command. Cancellation clears queued work and suppresses late results/progress.
- `POST /api/v1/voice/close` `{sessionId}` closes session. Backend cost/timeout limit independent of client.
- `GET /api/v1/events` uses authenticated fetch streaming NDJSON/SSE (UI may poll state instead); current initial UI uses polling.

## Evidence (per-recording pinned task definition)
- `POST /api/v1/attempts` `{attemptId:UUID,taskId,startedAt,configRevision}` → `{attemptId,task,configRevision,settings,startedAt}`. Create before capture when connected. Offline GPS may submit same fields with full points later; backend uses stored config revision, never current task repricing. Historical revisions retained.
- `POST /api/v1/attempts/:id/gps` JSON `{taskId?,startedAt?,configRevision?,endedAt,points:[{latitude,longitude,time,accuracy,speed?:number}],timeZone:string}`. `speed` meters/sec if provided; server calculates distance and checks speed itself. Optional bootstrap fields required only if offline attempt was not registered. Maximum 4h; no recovery/resume. Return receipt below.
- `POST /api/v1/attempts/:id/video` multipart fields `video` (MP4), `endedAt`, `timeZone`. Attempt must already be registered; max 30.5s and 75MB. Real video preprocessing + OpenRouter model. Return receipt below.
- Receipt `{id,attemptId,taskId,task: pinnedTask,configRevision,startedAt,endedAt,verifiedAt,verifier,metric,measuredUnits,correctionEligible,correctionMinimum,unitsPerCredit,minutesPerCredit,dayReset,budgetReset,timeZone,rewardEligible,minutesRewardEligible,reason,correctedUnits?:number}`. `rewardEligible` means daily progress/credit eligibility at verification; `minutesRewardEligible` additionally requires the original browsing-budget period to remain current. After the budget reset but before day reset, daily progress may still count while spendable minutes do not. After day reset, both are false and only history is stored. Android consumes receipt ID once, calculates shared partial credit using pinned rates, owns awarding/balances. Backend never calls GPT to calculate rewards.
- `POST /api/v1/attempts/:id/correction` `{value:number}` requires original verified measuredUnits ≥ pinned correctionMinimum, stores correctedUnits without rewriting measuredUnits. Same value retry idempotent. Android owns linked correction/reward adjustment, pinned ratios and exactly-once bookkeeping.
- `GET /api/v1/attempts/:id` → pinned attempt plus receipt if completed. Duplicate submit returns existing receipt and cannot double-charge model/reward.

## Health/deployment
`GET /health` no auth → `{ok:true,service:"friction-v1",...}`. Environment `PORT`, `DATA_DIR=/data`, `OPENAI_API_KEY`, `OPENROUTER_API_KEY`, `API_BUDGET_USD` (owner-configured global cap, default 3 for development), `VOICE_MAX_SECONDS` (default 300), `PUBLIC_BASE_URL` optional. No credentials returned in health. Docker has ffmpeg and a persistent `/data` volume is required.

### GPS coverage fallback and validation refinements

Receipt additionally includes `fallbackEligible:boolean`. Only genuinely insufficient GPS coverage (no reliable points/mostly rejected accuracy or gaps) can set it. Vehicle-speed or mock-position detection never enables it. `POST /api/v1/attempts/:id/fallback` `{completed:true}` is an explicit user assertion of completion and returns the same receipt with `manualCompletion:true`, `correctedUnits:unitsPerCredit` (exactly one credit). It does not change original measuredUnits. Android applies the linked receipt adjustment once. Normal correction remains separately gated by observed minimum. Both correction and fallback reject expired reward periods.

Validation: waitSeconds 0–600; minutesPerCredit/emergencyMinutes 1–120; exactly three sessionMinutes each 1–120; YouTube credits 1–100; labels 1–60 chars; maximum 50 tasks, names 1–80 chars, task instructions 1–2000 chars; positive integer unitsPerCredit/correctionMinimum ≤100000. Video correction minimum caps at 30 seconds or 150 repetitions so it is achievable within the recording cap. Task reward threshold may exceed one recording's capacity because partial progress is shared.

Daily resets choose the first occurrence of an ambiguous repeated local time; a reset inside a skipped daylight-saving interval uses the first valid minute afterward. This prevents a period from going backward during clock changes.

### Preview and optional task test recording

Review cards offer **Try it**: an interactive local preview of proposed wait/session/reward/reset/label behavior. Preview timers and example balances are explicitly marked and never change saved config or actual Android balances. Stop returns to the same pending review.

A proposed task additionally offers **Test recording**, calling `FrictionNative.testTask(proposalId, proposalVersion, taskId)`. Native first calls the normal `POST /api/v1/attempts` with additional `proposalId` and `proposalVersion` alongside `attemptId`, `taskId`, `startedAt`, `configRevision`. Server requires the exact current pending review version/base revision, resolves its staged config and returns `{...normalAttempt,preview:true,proposalId,proposalVersion}`. No arbitrary JavaScript task definition is trusted. Use normal GPS/video submission endpoints; receipt returns `preview:true`, original proposal identifiers, `rewardEligible:false`, `minutesRewardEligible:false`, `correctionEligible:false`, `fallbackEligible:false`. Native must **never** pass preview receipts to the reward ledger; show the measurement and return to the same review. Corrections and fallback cannot grant anything for previews.

`GET /state` includes `previewResults:[receipt]` (latest 10) for the review UI. Results only attach visually to a card whose proposal ID, version and task ID still match. An amended review does not silently reuse an old test result. Existing pinned preview attempts may finish after amendment; their receipts remain linked to the older version and still earn nothing.

### Actual usage detail and trustworthy correction bounds

`POST /statistics` may include `history` containing bounded actual device daily usage, browser sessions, task receipts, corrections, time zone and explicit data limitations. Backend retains it for `read_details(history)` while startup context includes only the concise summary and last 15 recent events. It never fabricates per-site or charged/emergency distinctions missing from native records.

Video seconds receipts include `maximumCorrectableUnits` calculated from the smaller of trusted ffprobe clip duration and attempt elapsed time, with 0.1-second rounding tolerance, capped at 30.5 seconds and rounded down to tenths. Other receipt metrics use null. Numeric correction must not exceed this field; countdown/setup time before the camera started cannot be counted as recorded activity.

### Separate daily progress and browsing-minute eligibility

The final receipt has two immutable eligibility flags, computed when verification finishes:

- `rewardEligible`: the recording ended in the still-current pinned **daily** period. It may contribute shared partial credit, daily whole credits, YouTube eligibility and fixed emergency earning.
- `minutesRewardEligible`: daily eligibility **and** the recording ended in the still-current pinned **browsing-budget** period. Only this can contribute spendable browsing minutes.

Example: an activity ending at 19:59, verified at 20:01 with a 20:00 budget reset and 04:00 day reset, has `rewardEligible:true` and `minutesRewardEligible:false`. It does not lose valid daily progress and does not restore expired minutes. An activity ending at 03:59 and verified at 04:01 has both flags false, even if its budget period has not ended. Preview receipts always have both false.

Retries return the same original receipt and eligibility-at-verification flags. Android additionally checks current periods on receipt consumption and retains partial-progress provenance so two pre-reset activities arriving late cannot turn expired work into new browsing minutes. Existing earned rewards are not repriced. Older receipts lacking `minutesRewardEligible` may default it to their legacy `rewardEligible` value while still applying native current-period checks.

Corrections and explicit GPS fallback remain allowed only while **both** original periods are current; historical reward reallocation is outside this change. They do not alter the receipt's immutable eligibility flags.
