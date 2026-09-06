# Internet Restrictor for Android

A native Android version of Internet Restrictor with an earned-budget exercise flow.

Internet Restrictor adds deliberate friction before Chrome, Firefox, Edge, Opera, or X opens. The user chooses one of three customizable session presets (3, 5, or 10 minutes by default), completes a fixed 12-second focused wait, and then receives a wall-clock unlock. Active, focused use across all supported browsers and X is charged to one shared budget: zero minutes at local-day start plus 3 minutes for every completed exercise set. Spendable time resets at 20:00 local time and can be earned again afterward.

The official YouTube app is a separate protected target. It stays locked until 4 exercise sets accumulate in the logical day, then remains open without consuming browser budget until the 04:00 daily reset.

This is a personal, user-controlled tool—not parental control, device management, or a tamper-proof blocker.

## What is implemented

- A five-step native onboarding flow with clear privacy and accessibility disclosure.
- A touch-blocking accessibility overlay over supported browsers and X.
- A separate morning gate over the official YouTube app (`com.google.android.youtube`) until 4 exercise sets have accumulated in the logical day.
- A fixed 12-second focused wait before every normal browser session.
- Three customizable 1-to-120-minute wall-clock session presets, defaulting to 3, 5, and 10 minutes.
- A rotatable front/back-camera exercise challenge on every gate, with no exercise picker. The verifier classifies push-ups, plank, or the outdoor seated lat-pulldown machine. 5 push-ups, 18 plank seconds, or 10 machine reps complete one set; each set adds 3 minutes to today's budget; incomplete surplus carries independently per exercise until the 04:00 local daily reset. A 30-second on-screen countdown bounds each recording. The first completed set of each local day also earns one emergency unlock. It returns to the originating supported browser after earning budget, without bypassing the normal wait, and its use is charged normally.
- A one-use verification receipt for each recording, persisted until 04:00, prevents one model result from being credited twice.
- One earned emergency 15-minute access unlock per local day. Each day begins with none; the first completed exercise set earns it, and it can be used even when the daily budget is exhausted.
- At 20:00 local time, accumulated spendable budget and normal browser access reset immediately. Exercise-set progress, per-exercise surplus, the earned emergency unlock, and today's report history survive; new evening sets earn fresh spendable time.
- An append-only push-up ledger counts every verified push-up repetition in Europe/Prague cycles running from the 9th at 00:00 to the next month's 9th. Plank and machine work never write fake push-ups into that ledger. The dashboard shows the current total and all completed cycle totals; completed cycles are never pruned by report cleanup.
- Emergency unlocks are always enabled; there is no product switch that can disable them.
- A zero-minute base daily budget with unlimited 3-minute earned increments and no weekly or monthly pool.
- Exact 04:00 local daily-boundary usage splitting, daily usage accounting, and persisted exercise-set history grouped into Monday-to-Sunday weeks.
- Out-of-time latching, expiry handling, and stale-command protection.
- A non-interactive browser status badge during an active unlock.
- Dashboard, health status, settings, theme selection, and local diagnostic export.
- A developer control that cancels only the current unlock without resetting usage or settings.
- Transactional typed persistence with corruption detection and fail-open startup behavior.
- Boot, user-unlock, date, time, timezone, screen, keyguard, and service-restart handling.
- Android 16 Advanced Protection status reporting.
- No account, telemetry, or advertising ID. Network access is used only to submit a user-recorded challenge video to the owner's verifier, which preprocesses it, asks the configured OpenRouter model to classify and measure the clip, and deletes the temporary files.
- Today plus 30 days of browser-time and exercise-set report history.

## Documentation

See [DOCUMENTATION.md](DOCUMENTATION.md) for build and installation instructions,
Railway hosting, verification checks, architecture, and future design proposals.

Third-party licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
