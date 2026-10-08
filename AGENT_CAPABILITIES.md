# In-app agent capabilities

Maintained product reference for the Friction assistant. Read this first when
answering what the in-app agent can do. This describes the current source; a
phone uses these capabilities after the corresponding backend and app updates
are deployed. This is not a list of Codex coding tools.

## Quick answers

- The user and agent can configure a **daily allowance of 0–30 whole minutes**.
  Default: **0**. It is the starting browsing balance at the next daily reset.
  Saving, changing or undoing this setting does **not** add or remove time now.
- The user and agent can change the **daily reset time**, default **04:00** in
  the phone's local time zone. This is the only reset schedule.
- The separate browsing/evening reset has been removed. `budgetReset` is not an
  editable setting or agent operation. Old stored data may mention it only for
  migration or historical compatibility.
- At daily reset, unused browsing time is replaced by the allowance. Daily
  credits, partial progress and daily unlock eligibility reset. Missed days do
  not accumulate allowances. Tasks earn extra time; total time may exceed 30.
- Changing reset time preserves the current balance and progress until the next
  actual boundary under the new schedule. It does not restore expired rewards.
- The agent can prepare settings and task changes, show them for review, apply
  an approved review, and cancel or undo. The same capabilities are available
  through text and the voice interface.
- Saving on the backend and applying on Android are separate steps. The agent
  must report “waiting for your phone” until Android acknowledges the revision.

## Editable settings

All numeric values below are whole numbers. The manual setup editor and agent
share backend validation; Android validates the applied configuration as well.
A change request creates a review. The user approves it before it is saved.

| Field | User control | Default | Allowed values | Effect |
| --- | --- | --- | --- | --- |
| `dailyBudgetMinutes` | Daily allowance | 0 | 0–30 minutes | Replaces the browsing balance at the next daily reset. No immediate balance change. |
| `dayReset` | Daily reset | 04:00 | HH:mm, 00:00–23:59 | One daily boundary in the device time zone. Schedule edits preserve current balances. |
| `waitSeconds` | Waiting time | 12 | 0–600 seconds | Pause before a normal unlock. Zero skips the wait. A changed wait restarts an unfinished wait on the next selection. |
| `minutesPerCredit` | Minutes per credit | 3 | 1–120 minutes | Reward rate for new recordings. Existing rewards and in-flight recordings keep their pinned rate. |
| `sessionMinutes` | Session lengths | 3, 5, 10 | Exactly three values, each 1–120 minutes | Offered normal session lengths; existing active sessions keep their agreed duration. Lengths above the whole minutes left today are greyed out; when even the shortest does not fit, it is offered as the remaining minutes instead. |
| `youtubeCredits` | YouTube credits | 4 | 1–100 credits | Daily credit threshold for YouTube access. |
| `emergencyMinutes` | Emergency minutes | 15 | 1–120 minutes | Duration of a new emergency unlock, earned after the first daily credit. One per day; free of browsing-budget charges. |
| `creditName` | Credit name | credits | 1–60 characters, nonblank | Display label. |
| `unlockLabel` | Unlock button | Unlock | 1–60 characters, nonblank | Display label. |
| `youtubeUnlockLabel` | YouTube button | Unlock YouTube | 1–60 characters, nonblank | Display label. |
| `emergencyUnlockLabel` | Emergency button | Emergency unlock | 1–60 characters, nonblank | Display label. |

## Task controls

The agent can add a task, edit an existing task, pause/resume it with `paused`,
or remove it. Up to 50 tasks are supported. Removal preserves historical
receipts. New tasks require a complete definition; the agent asks for missing
activity, verification, measurement and reward preferences. It may draft judging
instructions from the user's description for review.

| Field | Meaning and constraints |
| --- | --- |
| `id` | New task ID: 1–64 lowercase letters, digits, `_` or `-`, beginning with a letter; unique. Cannot be edited after creation. |
| `name` | Nonblank display name, at most 80 characters. |
| `verifier` | `video` or `gps`. No arbitrary verifier or code execution. |
| `metric` | Video: `repetitions` or `seconds`. GPS: `meters`. |
| `unitsPerCredit` | 1–100,000 whole units required per credit. Partial progress combines across tasks. |
| `correctionMinimum` | Minimum original verified evidence to permit correction: 1–100,000; video seconds at most 30, video repetitions at most 150. |
| `instructions` | Nonblank judging instructions, at most 2,000 characters. |
| `paused` | Boolean task availability; true pauses, false resumes. |
| `gpsMode` | GPS: `walk` or `sprint`. Video: null. GPS thresholds are fixed runtime rules. |

Video recordings are capped at 30 seconds. GPS supports walking and sprinting,
with a four-hour cap and manual stop. Live distance uses the verification movement
filters. Mock locations or repeated/backwards GPS timestamps invalidate the whole
recording: live distance clears to zero with an explanation, and the retained
evidence is still rejected during verification.
The agent cannot introduce new recording
methods, change GPS speed/accuracy rules or replace verification with its own
claim. Task and reward rules are pinned when recording begins. Retries do not
award the same recording twice. Uploads after the pinned daily period expires
are history only. Corrections and eligible manual GPS completion also expire
with the recording's reward period.

## Request routing

Every text and delegated voice request goes to the selected assistant model
(Sol 6.1 Low Fast by default). Exact approval and cancel/undo commands are
handled directly by the app without a model call. There is no classifier or
decision-model fast path. The model prepares reviews; it cannot save changes.
All requests share one active turn, current/pending configuration, validation,
stale-review checks, review/approval, undo and Android acknowledgement.

## Assistant model preferences

In Your setup → Assistant, users can select Sol 6 (`gpt-6-sol`), Sol 6.1 (`gpt-6.1-sol`),
Astra (`gpt-6-astra`), Luna (`gpt-5.6-luna`) or Terra (`gpt-5.6-terra`). Sol 6, Luna and
Terra offer Non-reasoning (`none`), Low, Medium, High, Extra high (`xhigh`) and Max.
Sol 6.1 and Astra offer Low through Max; they have no Non-reasoning option. All five
support Standard (`default`) and Fast (`fast`) processing, verified against official
pricing and live API calls. The default is Sol 6.1 / Low / Fast, chosen from the
2026-10-08 benchmark (equal accuracy to Sol 6, lower cost than Sol 6 and Astra). Higher effort can take longer; Fast has
higher token prices. Usage estimates use the actual model and returned tier.

Save assistant settings persists `{model, reasoning, serviceTier}` per
installation via authenticated `POST /api/v1/assistant-settings`. It takes effect
on the next text or delegated voice request. Each active request keeps its
starting selection. These preferences do not change app rules, reset time,
balances, pending reviews or native configuration revisions, and do not need a
phone acknowledgement. The selection is included in current agent context.
This is a manual settings control, not a new callable agent function: the agent
cannot change its model through `set_setting` or conversation. GPT-Live/Ripple voice audio and video judging
keep their separate models. None/Low requests time out after 60 seconds; higher
efforts after 180 seconds.

## Callable agent functions

These five functions are exposed to the configuration agent. Voice delegates
requests to that same backend agent; it does not have separate settings powers.

| Function | What it does |
| --- | --- |
| `read_setup` | Refreshes setup, review/selected card, recent changes, device application status and available statistics when context is stale. Fresh context is already supplied on each turn. |
| `read_details` | Retrieves the shipped app guide by topic; `index` lists topics. `history` returns actual device records. See the runtime documentation section below. |
| `propose_changes` | Creates/amends one grouped review with explicit operations. Keeps unrelated pending edits. Does not activate settings. Requires the expected configuration revision. |
| `confirm_changes` | Saves the displayed proposal/version only after explicit user approval. Binds approval to the current review; duplicate confirmations do not reapply it. |
| `cancel_changes` | Cancels pending work/review or restores the whole latest saved action following an explicit cancel/undo request. Older actions, individual saved cards, and undo itself cannot be undone. Cancel pending reviews first. Restores exact task order. |

Operations accepted by `propose_changes`:

| Operation | Parameters |
| --- | --- |
| `set_setting` | `field`, `value`; one of the settings above. |
| `set_task_field` | `taskId`, `field`, `value`; editable task field above except `id`. |
| `add_task` | `task`; complete task definition. |
| `remove_task` | `taskId`. |

Example: configure 20 minutes at reset and move the daily reset to 06:30:

```json
{
  "changes": [
    {"action": "set_setting", "field": "dailyBudgetMinutes", "value": 20},
    {"action": "set_setting", "field": "dayReset", "value": "06:30"}
  ],
  "expectedRevision": 7,
  "title": "Daily allowance and reset"
}
```

The revision must come from current app context. The resulting review still
needs the user's Apply action or explicit text/voice approval. Reviews stay in conversation order, with later replies beneath them. Completed
reviews collapse to reopenable summaries with details and an undo control only for the latest saved action;
pending reviews stay expanded. Recent earlier reviews remain accessible. The
labelled microphone button starts voice chat. A short sound marks a ready
connection; a compact microphone beside Send starts a call and becomes a stop icon to end
it. The toggle remains available near the latest message after a call ends;
the large voice introduction is reserved for the initial conversation. Review cards can
be adjusted or removed; changed versions invalidate earlier approvals. Previews
and optional task test recordings are user interface actions and grant no real
rewards. The agent has no standalone function to start a recording or preview.

## Runtime app documentation

The in-app agent can call `read_details` with `topic: "index"` to discover the
maintained [app guide](backend-v1/docs/app-guide.md), then retrieve a relevant
topic. Topics: `purpose`, `settings`, `daily_budget`, `sessions`, `rewards`,
`tasks`, `video`, `gps`, `corrections`, `youtube`, `emergency`, `assistant_settings`, `assistant_routing`, `review`, `offline`,
`troubleshooting`, and `privacy`. `history` remains the separate live-data lookup.

The backend packages and loads this guide through `backend-v1/app-docs.mjs`.
Both voice and configuration prompts preload the compact
[app overview](backend-v1/docs/app-overview.md), so common questions can be
answered immediately. The configuration prompt also carries the topic index;
detailed sections are retrieved only when needed. For app-behavior questions the agent is instructed to
answer directly when the overview or current context is sufficient, otherwise
read the relevant topic, then explain it in plain language using the user's current
settings. Documentation defaults are not evidence of the user's active values.
An explanation request alone must not create or apply settings changes. Voice
routes questions requiring documentation to the same backend agent.

Coding agents must update both the compact overview and the shipped guide
alongside capability and behavior changes. Topic/schema coverage, settings defaults, and the read-and-answer flow
are tested in `backend-v1/test/app-docs.test.mjs`.

## What the agent can read

- Current configuration, selected review card, pending proposal, recent saved
  changes and whether the phone has applied the saved revision.
- Device-reported remaining/used/granted browsing time, today's credits and
  partial progress, recorded usage, emergency usage, gate/protection status,
  daily allowance, reset schedule, time zone and observation timestamps.
- Through `read_details` with `history`: up to 14 days of daily usage and bounded
  lists of browser sessions (80), task receipts (60), and corrections (30), with
  truncation indicators. Context preloads a smaller set of recent task events.

Missing or stale statistics are not live observations. Records do not identify
websites or individual apps, or label each session as emergency/charged use.
Exact past balances removed at resets are not recorded. The agent must not
invent those details.

## Conversation context limit

There is one conversation per installation, retaining at most 100 recent messages
(the UI receives the latest 30 and 12 reviews). Every configuration-agent request,
including voice delegations and follow-up tool calls, has a conservative
100,000-token context ceiling. It includes the full request, instructions,
settings/reviews, tool definitions/results, framing and the output
allowance (2,400 for None/Low, 8,192 for Medium, 16,384 for High and 32,768
for Extra high/Max). UTF-8 byte accounting deliberately overestimates text token usage.
Oldest complete exchanges are removed first, preserving the current request and
fresh app state. Oversized required context is rejected before a provider call;
manual controls remain usable. No additional threads or persistent model-side
conversation are created. Saved app state is independent of trimmed messages.
Live audio retains its separate temporary session and five-minute default limit.

## Controls the in-app agent does not have

No immediate top-up, direct balance edit, manual grant of credits, emergency
use-count edit, forced unlock, clock change, arbitrary app blocklist, enforcement
disable switch, camera/GPS start, correction submission, deletion of history,
shell/file access, or new verification method. Some actions such as qualifying
receipt corrections are available to the user through dedicated app UI/API;
they are not callable configuration-agent functions. Theme and diagnostic
controls are also outside the agent settings schema.

## Maintenance contract and source map

Coding agents must update this reference **in the same change** as any added,
removed or altered agent function, editable field, bound/default, approval flow,
readable data, or timing/reward behavior. Update examples and unsupported controls
too. This is required by [AGENTS.md](AGENTS.md), not a scheduled external job.
Do not leave “update later” notes when the implementation is already changed.

`npm test` includes `backend-v1/test/capabilities-doc.test.mjs`, which compares the
settings, task fields, agent functions and edit operations documented here with
the exported schemas. It catches inventory drift; coding agents still need to
keep descriptions, limits and timing accurate in both this reference and the
runtime guide. Start with this document for
capability questions; inspect the following sources only if an answer is missing,
ambiguous, or there is evidence of a code/document mismatch.

- `backend-v1/domain.mjs`: settings/task validation, defaults and configuration schema.
- `backend-v1/edit-schema.mjs`, `edits.mjs`: supported edit operations.
- `backend-v1/prompts.mjs`, `agent.mjs`, `store.mjs`: functions, review, approval, undo and persistence.
- `backend-v1/public/app.js`: manual setup, review, preview and voice UI.
- `app/src/main/java/com/michalkrsik/internetrestrictor/domain/AppConfiguration.kt`: Android contract.
- `app/src/main/java/com/michalkrsik/internetrestrictor/domain/TaskRewardPolicy.kt`: configuration and receipt application.
- `app/src/main/java/com/michalkrsik/internetrestrictor/domain/RestrictionEngine.kt`, `PeriodCalculator.kt`: reset/budget enforcement.
- `app/src/main/java/com/michalkrsik/internetrestrictor/data/ConfigurationJson.kt`, `AppStateSerializer.kt`: migration and saved state.
- `app/src/main/java/com/michalkrsik/internetrestrictor/data/AgentStatistics.kt`: actual readable statistics and limitations.

Undo is one level only: a saved request is one action even with several cards.
The backend exposes `undoableChangeId` only for the latest saved action when no
review is pending. Undo restores the previous saved configuration exactly,
including task order. Afterwards older actions remain viewable but cannot be
restored; a new save enables undo for that new action. Repeated requests are
idempotent. Sol 6 supports Standard/Fast and none, low, medium, high, xhigh, max;
the factory assistant default is Sol 6.1 Low Fast.
