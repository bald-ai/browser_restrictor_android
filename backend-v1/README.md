# Friction v1 backend

The single backend for all Friction Android builds. Video tasks use one configurable evaluator and one structured model result: `measured_units`, `confidence`, and `reason`.

## Run

Node 22 or newer and ffmpeg/ffprobe are required for video. `npm ci`, then `npm test` and `npm start`. Local default is `http://localhost:4318`. The Dockerfile installs video tooling and listens on `PORT` / `HOST` (default `0.0.0.0`). Deploy this folder as a separate Railway service with a persistent volume mounted at `/data`.

Environment:

| Variable | Purpose |
|---|---|
| `DATA_DIR` | Persistent JSON database directory; `/data` in Docker |
| `PORT` | HTTP port; Railway uses 8080 |
| `PUBLIC_BASE_URL` | Exact public HTTPS origin for same-origin browser checks |
| `AI_GATEWAY_API_KEY` | Optional: enables Jev common settings edits with Astra fallback, 0.9 threshold and 3-second timeout |
| `OPENAI_API_KEY` | GPT-6 Astra Low Fast and GPT-Live-1 Ripple |
| `OPENROUTER_API_KEY` | Video measurement provider |
| `VIDEO_MODEL` | Optional video model, default `google/gemini-3.8-flash` |
| `VOICE_MAX_SECONDS` | Maximum single call duration, default 300 seconds; bounded to 30–900 |

Provider keys stay server-side. Browser-only visits create a separate installation for manual UI testing. Android supplies its own installation identity through the trusted `FrictionNative.bootstrap()` bridge; no provider keys are passed to JavaScript.

Full request/config/receipt shapes are in `Dev/V1_API_CONTRACT.md` in the project root.

## What works

- Persisted per-install identity, settings, task definitions, immutable config revisions, proposals, change receipts, evidence receipts and measured usage summary.
- Atomic grouped proposals; one setting/task per horizontally scrollable card; amendment/removal invalidates older approval versions. Manual settings and complete task add/edit/pause/remove use the same review and confirmation path.
- Try it previews for waits, sessions, reward math, resets and labels; stop returns to the same review without writes. Optional task test recordings pin the exact pending proposal and return preview-only measurement receipts that can never grant rewards.
- Button approval during voice and explicit voice approval. No model round trip required to apply and read back an approved review. Duplicate approvals are idempotent.
- Cancellation invalidates in-flight model work immediately. Pending reviews are discarded; an in-progress save is undone after it commits. Undo restores only changed values and rejects overlap with newer changes. Per-card and whole-request undo, duplicate generic cancellation protection.
- Save/read-back and Android-applied acknowledgement are distinct. Reads expose only durable committed state, so a device cannot apply a revision that later fails its disk write.
- GPT-Live direct WebRTC microphone/speaker media, backend sideband delegation, Ripple voice and asynchronous Astra Low Fast function execution. Voice remains open during work/approval, receives real progress/results and updated context. Clear spoken cancel/undo commands also route directly from the authenticated sideband transcript after 900 ms of stable text, without waiting for another Live delegation. Further fragments reset the candidate; questions, quoted/negative commands and continuations do not trigger this direct path. Late delegations/results and stale progress are suppressed. Leaving the WebView closes microphone tracks immediately.
- Model prompts start with current config, outstanding grouped proposal, selected visible card, application status and measured recent device statistics. Detailed rules/history are read through narrow tools only when needed. Task text/statistics are data, not executable instructions.
- Video preprocessing: MP4 up to 30.5s/75MB, 4x temporal sampling, real OpenRouter measurement, deterministic duration normalization and physical ceiling. Configured task instructions replace fixed exercise labels.
- GPS distance checking: accuracy, timestamps, gaps, drift, walking/sprint speed boundaries, mock flags and vehicle-like jumps. All rejected segments are recorded in diagnostics. Inadequate coverage may allow explicit one-credit manual completion; vehicle/mock evidence never grants that fallback.
- Attempts pin task rules, credit conversion and reset schedule at capture start. Retries return one receipt and concurrent video retries call the provider once. Receipts distinguish daily progress eligibility from browsing-minute eligibility. Uploading after a budget reset can preserve daily credit progress until the day reset, while expired minutes stay expired; uploads after the day reset add history only. Corrections and fallback cannot revive an expired period.

## Tests and evidence

`npm test` currently runs 55 tests with no paid API calls. They cover real HTTP routing/auth, private durable transactions and restart persistence, grouped changes/stale approval, actual disk read-back mismatch, cancellation during a gated write, repeat cancellation, current-user intent authorization, in-flight fake-model cancellation, the failed phone transcript replay without a second delegation, negative/quoted transcript continuations, duplicate/late voice events, immutable approval targets, uncapped cost accounting, duplicate video submission, correction eligibility, GPS exclusion/fallback and DST/reset boundaries.

The UI was manually exercised through the browser: edit waiting time 12→9 and minutes per credit 3→4 in one review, navigate both cards, apply by button, undo the whole request, verify both original values in Your setup; create a seconds-based “Brush teeth” task and inspect its complete review. These are local app tests, not claims of measured real activity or paid model execution. Hosted/phone/paid voice evidence is coordinated by the root implementation task and recorded in the project’s v1 test report.

## Cost controls and practical limits

A shared owner ledger tracks cost before and after provider work, without enforcing a spending cap. Installation registration has no per-IP hourly throttle. Astra reservation uses a conservative UTF-8 input-token upper bound plus maximum configured output and cache-write allowance; settlement uses returned usage and the selected Fast rate. GPT-Live charges tracked seconds. OpenRouter settlement uses reported `usage.cost`; if a paid request's outcome/usage is unknown, its reservation is retained conservatively. The local ledger is informational, not the provider invoice or a spending limit. Five-minute voice calls and one active agent request plus one voice session per installation remain enforced.

This is a single-instance persistent backend. Keep one Railway replica with its volume; multi-instance scale would need a shared transactional database. Native Android remains the authority for actual timers, balances and history, while this backend owns config and measurement receipts. The browser cannot acknowledge a native applied change on its own. GPS can reject obvious vehicle tracks; it cannot prove walking from a vehicle deliberately moving at walking speed. Real outdoor GPS behavior and microphone conversational timing must also be tested on the phone.

Repeated ambiguous references should trigger clarification. Explicit supported requests and button actions are validated by server code. No coding-agent runtime, persistent conversational memory, account recovery or new verifier creation is included.

## Official API reference used

- [Live server-side controls](https://developers.openai.com/api/docs/guides/voice-server-controls?api=live)
- [Live session creation / sideband / commentary events](https://developers.openai.com/api/reference/typescript/resources/live)

The direct WebRTC and sideband payloads were also cross-checked against the working `prototypes/agent-ux-lab` implementation in this repository.
