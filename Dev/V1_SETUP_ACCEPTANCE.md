# Full setup acceptance testing

User request: test the new setup options end to end, following the identified gap in complete phone acceptance testing.

## Required evidence

- [x] Every configuration setting: edit through the actual Android-hosted setup UI, review before saving, Apply button, server readback, native durable configuration, native acknowledgement.
- [x] Grouped changes: swipe/navigation, preview, adjust one card, remove one card, cancel, whole-group Apply, per-card undo and whole-group undo.
- [x] Task lifecycle: add video repetitions and seconds; add GPS walk/sprint; edit name, definition, units/credit, correction minimum, verifier/metric and availability; pause/unpause; remove; verify native task list and recorder routing.
- [x] Runtime effects: wait duration, three session options, unlock labels, credit label, minutes per credit and preserved earned values, YouTube threshold, emergency duration/label.
- [x] Reset effects: UI-configured browsing and daily reset actually expire the right balances/progress at the real boundary.
- [x] Agent setup: real chat uses current settings, creates grouped review, clarification/reference handling, confirmation and native application. Reuse existing actual-phone voice/interrupt evidence only where unchanged current code and matching scope make it applicable.
- [x] Persistence/reopen and cleanup: changes persist across process restart, baseline configuration restored, test rewards removed, original blocker restored and healthy.

Evidence must distinguish actual UI/network/native execution from test fixtures. Injected activity receipts may establish runtime setup behavior but never count as new video/GPS-provider evidence. Existing six real hosted video-verification results remain supplemental evidence.

## Failures found and addressed during the run

1. New setup reviews could appear below old messages while the screen stayed at the top. The hosted review now scrolls into view when a new pending proposal/version appears. Deployed as `7822887c-c22d-4288-b40c-4f9f6426158e`; final phone visual check pending.
2. The actual blocker could remain above Home or Friction. Reproduced outside instrumentation. Its own focusable overlay can leave every application window inactive; the monitor previously required an active application window. The monitor now falls back to the top application window and recognizes its own real application window as an exit (its overlay events are still excluded). Direct ADB Home and Friction-return checks passed after installing the fix.
3. Harness corrections: reacquire the new WebView after activity recreation; remove literal shell quotes from UiAutomation service commands; preserve/restore the existing accessibility list. These were test-code failures, not product bugs.

Android regression results after the foreground-monitor fix: 231 v1Dev and 231 original-debug unit tests passed, with no failures/errors/skips. Only the v1Dev APK was installed.

## Real boundary result after the scheduling fix

The first runtime pass exposed stale visible balances at 00:06: the foreground dashboard had no scheduled tick, and YouTube’s dedicated watcher used a default clock. Added a process-level v1 watcher for the next configured browsing/daily boundary and passed configured settings to the YouTube watcher.

The repeated physical-phone runtime test passed in 179.073 seconds. At 00:10, browsing minutes became zero while 2 daily credits and 0.5 partial progress remained. At 00:11, credits and partial progress also became zero. No device clock changes, manual refresh, fake time source, or injected reward receipts were used: three real hosted video verifications supplied the rewards. Original configuration and accessibility services were restored.

Full runtime evidence: `work/v1-build/setup-e2e/runtime-final.json`, with the passed instrumentation log and before/after screenshots in the same directory.

## Final acceptance

All requirements above passed. Complete evidence, fixes, final build/deployment identity and limits are recorded in `work/v1-build/setup-e2e/REPORT.md`. The full final UI pass has 26 checkpoints, runtime pass 21, agent/task routing pass 19, plus a two-process persistence check. Original configuration and daily blocker restored.
