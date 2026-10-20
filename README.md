# Friction: Internet Restrictor for Android

> **Status: working prototype**, used daily on my own phone.

**In short**

- **The app:** a native Android app (Kotlin) that puts a short pause in front of browsers, X and YouTube. Browsing time comes from a small daily allowance plus time earned by exercise, which a vision model verifies from a short video.
- **The in-app AI agent:** tell it what to change, by text or voice ("set my daily allowance to 10 minutes", "move the reset to 5 am"). It prepares the change, shows it for review, and applies it only after approval. Every applied change can be undone. A Node.js backend validates each proposal against the same rules the app uses. What the agent can and cannot do: [AGENT_CAPABILITIES.md](AGENT_CAPABILITIES.md).
- **The model benchmark:** a repeatable harness with 37 frozen scenarios (26 actions, 11 guard cases where the right answer is to change nothing). It runs the real backend validation and undo code against synthetic installs and records correctness, coverage, latency, estimated cost and failures. Every run is kept as a history record. Results and analyses: [benchmarks/friction/RESULTS.md](benchmarks/friction/RESULTS.md).

Latest comparison (8 October 2026, 222 scored trials): Sol 6, Sol 6.1 Low and Sol 6.1 Medium all passed 52/52 action runs and 22/22 guard runs with no wrong proposals. Median response was 1.5–1.7 s, and Sol 6.1 cost about 17% less. Earlier runs are where cheaper models failed, mostly by making changes in guard cases where they should have asked first.

## Details

A native Android version of Internet Restrictor with an earned-budget exercise flow.

Internet Restrictor adds deliberate friction before Chrome, Firefox, Edge, Opera, or X opens. The user chooses one of three customizable session presets (3, 5, or 10 minutes by default), completes a fixed 12-second focused wait, and then receives a wall-clock unlock. Active, focused use across all supported browsers and X is charged to one shared budget: a configurable daily allowance of 0–30 minutes (default 0), plus time earned from tasks (3 minutes per credit by default). The single configurable daily reset defaults to 04:00 local time. Allowance changes apply at the next reset and never add minutes immediately.

The official YouTube app is a separate protected target. It stays locked until 4 exercise sets accumulate in the logical day, then remains open without consuming browser budget until the 04:00 daily reset.

This is a personal, user-controlled tool—not parental control, device management, or a tamper-proof blocker.

## What is implemented

- A five-step native onboarding flow with clear privacy and accessibility disclosure.
- A touch-blocking accessibility overlay over supported browsers and X.
- A separate morning gate over the official YouTube app (`com.google.android.youtube`) until 4 exercise sets have accumulated in the logical day.
- A fixed 12-second focused wait before every normal browser session.
- Three customizable 1-to-120-minute wall-clock session presets, defaulting to 3, 5, and 10 minutes. Presets longer than the remaining budget are greyed out; when even the shortest does not fit, it offers the remaining minutes instead.
- Whole-minute charging: each normal session is charged only for completed minutes of use, and its unfinished minute is dropped when it ends.
- Configurable video and GPS tasks in every build. Select a task before recording; the single V1 evaluator measures repetitions or seconds using its judging instructions. A 3-second countdown precedes each video, with a 30-second limit.
- A one-use verification receipt for each recording, persisted until 04:00, prevents one model result from being credited twice.
- One earned emergency 15-minute access unlock per local day. Each day begins with none; the first completed exercise set earns it, granting 15 elapsed minutes of free access without consuming earned budget, even when the daily budget is exhausted.
- One daily reset replaces unused browsing time with the configured allowance and clears daily credit progress and unlock eligibility. There is no separate evening browsing reset.
- An append-only push-up ledger counts every verified push-up repetition in device-local time cycles running from the 9th at 00:00 to the next month's 9th. Plank and machine work never write fake push-ups into that ledger. The dashboard shows the current total and all completed cycle totals; completed cycles are never pruned by report cleanup.
- Emergency unlocks are always enabled; there is no product switch that can disable them.
- A 0–30-minute daily allowance editable through manual settings or the in-app agent. Earned time can exceed 30 minutes. There is no weekly or monthly pool.
- Exact configurable daily-boundary usage splitting (04:00 by default), daily usage accounting, and persisted exercise-set history grouped into Monday-to-Sunday weeks.
- Out-of-time latching, expiry handling, and stale-command protection.
- A non-interactive browser status badge during an active unlock, counting down whichever ends first: the session window or the remaining budget.
- Dashboard, health status, settings, theme selection, and local diagnostic export.
- A developer control that cancels only the current unlock without resetting usage or settings.
- Transactional typed persistence with corruption detection and fail-open startup behavior.
- Boot, user-unlock, date, time, timezone, screen, keyguard, and service-restart handling.
- Android 16 Advanced Protection status reporting.
- No account, telemetry, or advertising ID. Network access supports setup synchronization, conversation and task verification. The backend preprocesses recorded video, asks the configured OpenRouter model to measure the selected task, and deletes temporary video files.
- Today plus 30 days of browser-time and exercise-set report history.

## Documentation

See [DOCUMENTATION.md](DOCUMENTATION.md) for build and installation instructions,
Railway hosting, verification checks, and architecture.

Personal notes and future ideas are kept in `Dev/`.

Third-party licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
