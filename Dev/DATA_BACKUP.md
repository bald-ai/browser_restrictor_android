# Data backup with Convex

Status: future idea. Saved for later; no implementation requested now.

## Why this is needed

Exercise history and settings were lost after the app was uninstalled. Normal
updates had preserved the data, but this app currently disables Android backup
and has no data backup or restore feature. Export diagnostics only saves logs.

## Idea

Use Convex to store recoverable backups of the app's data so it can be restored
after reinstalling the app, replacing a phone, or losing local storage.

Convex CLI was confirmed authenticated on this Mac on 2026-09-06 for
`baldai's team` (`baldai`). No Convex backup project or integration was created
as part of this note.

## Data to preserve

- Verified pushup ledger and monthly totals.
- Exercise-set history and per-exercise progress.
- Browser usage and report history.
- User settings and session presets.
- Verification receipts needed to prevent duplicate exercise credit.

Define how daily budget, time boundaries, and expired unlocks are reconciled
when restoring an older backup. Restoring history should not revive an expired
unlock or award the same exercise credit twice.

## Desired behavior

- Back up automatically after meaningful changes, with retries when offline.
- Show the last successful backup time and whether changes are still pending.
- Provide a clear restore flow after reinstalling or changing phones.
- Keep dated backup versions so one bad write does not replace every good copy.
- Never let an empty fresh installation overwrite an existing populated backup
  before the user has a chance to restore it.
- Validate backup format and version before replacing local data, and preserve
  the current local state until restoration succeeds.

## Decide when we return to this

- How the owner signs in and finds the same backup after reinstalling.
- Backup format, frequency, version retention, and conflict handling.
- Whether a downloadable local backup should also be available.
- How to migrate backups when the app's data schema changes.

This is a future protection against data loss. Implementing it will not recover
past data unless an existing copy is found elsewhere.
