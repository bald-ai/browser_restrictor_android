# Daily settings lock

Status: high-level idea. Saved for later; no implementation requested now.

## Why

The app should provide deliberate friction against changing the rules in a moment
of temptation. The proposed teach-by-demonstration feature is especially easy to
abuse: the owner could teach an effortless activity such as "sit and do nothing"
and use it to earn browsing time.

Allowing rule changes only at certain times could make that impulse harder to act
on. This would add friction; it would not establish whether a taught activity is
meaningful exercise.

## Idea

Lock editing settings during a configurable daily time range. Outside that range,
the owner can review and change the rules. Exact hours and which settings are
covered are undecided.

During the locked range:

- Protected settings remain visible but cannot be changed.
- The app explains when editing becomes available again.
- The lock's own schedule cannot be shortened, disabled, or changed to unlock
  settings immediately.
- If custom activities are added later, creating, editing, deleting, or replacing
  their definitions should be considered part of the protected settings, along
  with any controls that change how much activity earns browsing time.

The restriction should apply when a change is saved, including if the settings
screen was opened before the lock began.

## Decisions for later

- What daily hours should be locked? Should the range support crossing midnight?
- Should all settings be locked, or only activities, rewards, and restriction
  rules? Should appearance and diagnostics remain available?
- Should schedule changes outside the locked range take effect immediately or
  only the next day?
- How should travel, timezone changes, and manual clock changes affect the lock?
- Is a recurring daily range sufficient, or are different weekday schedules useful?

## Relationship to teach by demonstration

Consider this friction before making owner-taught activities available. Teaching
and changing their reward criteria should respect the same editing window, so
creating an easy new activity cannot bypass a locked existing definition.

This note records the product direction only. Timing, scope, UI, and implementation
details remain open.
