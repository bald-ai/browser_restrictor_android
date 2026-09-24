# Friction app guide

This guide is retrieved by the in-app assistant through `read_details`. It
explains product behavior, not the current user's configuration or live phone
state. Use fresh app context for their actual values, and distinguish saved
settings from values acknowledged by Android. Examples are illustrative.

## purpose: What Friction does and which apps it covers

Friction helps people discover their own workable relationship with distracting
apps. The central idea is win/win: do something you value and earn browser/app
time, or decide the extra time is not worth doing the activity and spend less
time in the protected apps. A skipped task can still be a useful outcome. There
is no need to frame it as failure or to earn the right to feel good about yourself.
The reward loop can feel playful: "I found a way to get what I wanted" while
still making progress on something you chose. Explain that openly, without
claiming to secretly manipulate the user or guarantee behavior change.

The distinctive feature is the amount of personal control available through a
simple interface. Users can choose supported video or GPS activities, define
what counts, change effort per credit and minutes earned, tune the pause before
unlocking, choose session lengths, and adjust the daily allowance and reset.
Text, voice and manual controls all support this exploration. Review, previews,
task test recordings and undo make it practical to try a rule and refine it.
The purpose is self-exploration through tinkering: learn which activities,
rewards and pauses actually fit your life. There is no universal perfect setup.

When asked what makes the app special, lead with the win/win and this freedom
to experiment, then give one concrete example. For example: "You can make a
short walk earn a little browsing time. If you take the walk, you get the walk
and the time. If you decide scrolling isn't worth the walk, you've given
scrolling less of your day. Then tune the trade until it feels right for you."
Use the user's current task and reward values when quoting numbers. A daily
allowance or previously earned time can still allow access without a new task;
this idea concerns protected apps, not a promise to eliminate all phone use.

Be warm, curious and non-threatening. Ask what the user wants more room for,
what feels annoying, and which small change they want to try. Treat their
observations as useful information, including "too strict" or "too easy".
Offer a manageable experiment when invited, with the normal review before
changes. Avoid moral judgments, shame, threats, discipline lectures, addiction
treatment claims and pressure to maximize exercise or minimize all screen time.

Chrome, Firefox, Edge, Opera and X share one browsing budget. The official
YouTube app has a separate daily credit threshold. There is no editable app
blocklist through the assistant. Android handles blocking, time accounting and
rewards; the backend handles configuration, conversation and verification.

The user can customize through the Friction assistant by text or voice, or use
the manual settings editor. Changes are presented for review before saving.
See `settings`, `sessions`, `youtube`, `emergency`, and `review` for details.

## settings: Editable settings, limits and defaults

Use current context to say what a user's settings ARE. The following are factory
defaults and supported ranges, not evidence that a phone currently uses them.

| Field | Default | Supported values and purpose |
| --- | --- | --- |
| `dailyBudgetMinutes` | 0 | 0–30 whole minutes granted at the next daily reset. No immediate top-up. |
| `dayReset` | 04:00 | HH:mm, 00:00–23:59, in the phone's local time zone. The only reset schedule. |
| `waitSeconds` | 12 | 0–600 whole seconds before a normal unlock. Zero skips the waiting period. |
| `minutesPerCredit` | 3 | 1–120 whole minutes per earned credit for new recordings. |
| `sessionMinutes` | 3, 5, 10 | Exactly three presets, each 1–120 whole minutes. |
| `youtubeCredits` | 4 | 1–100 whole credits required for daily YouTube access. |
| `emergencyMinutes` | 15 | 1–120 whole minutes for a newly started emergency unlock. |
| `creditName` | credits | Nonblank display label, at most 60 characters. |
| `unlockLabel` | Unlock | Nonblank normal-unlock button label, at most 60 characters. |
| `youtubeUnlockLabel` | Unlock YouTube | Nonblank YouTube button label, at most 60 characters. |
| `emergencyUnlockLabel` | Emergency unlock | Nonblank emergency button label, at most 60 characters. |

All numeric settings require integers. Changing reward rate does not reprice
previously earned minutes or recordings already started. Changing an active
session's preset or emergency duration does not rewrite its agreed expiry.
Changing wait rules or session presets cancels an unfinished wait so the next
selection can use the new rules. See `daily_budget` and `review` for timing.

## daily_budget: Daily allowance, reset time and carryover

The daily allowance is the starting browsing balance at the daily reset. It can
be set from 0 to 30 whole minutes, including zero. Changing or undoing it leaves
the current balance untouched. It first takes effect at the next daily reset
that the phone processes after applying the configuration.

There is one configurable reset, `dayReset`, default 04:00 in the phone's time
zone. The former separate evening/browsing reset has been removed. At reset,
unused browsing minutes are replaced with the allowance. Daily credits,
unfinished partial-credit progress and daily unlock eligibility also reset.
Tasks can earn additional minutes; the 30-minute cap applies only to the daily
allowance, not the total balance. An allowance does not count as earned credits
and does not by itself unlock YouTube or earn an emergency unlock.

Example: the allowance is 10 minutes, and 7 minutes remain today. Changing the
allowance to 20 leaves 7 available now. At the next daily reset, the balance
becomes 20, not 27. Earning a credit afterward adds the configured reward.
Missing several days does not accumulate multiple allowances.

Changing reset time preserves current balances and progress until the next
actual boundary under the new schedule. It never restores expired rewards.
Daylight-saving time uses the first occurrence of a repeated reset time; a
missing clock time resets at the first valid instant after the gap. If asked for
an exact upcoming reset, use the phone's reported next reset and time zone.

## sessions: Waiting, browsing sessions and charged time

When a protected browsing app opens, the gate offers the configured session
lengths. Normal access needs available browsing time and completion of the
configured wait. The focused wait advances while the gate is focused, the screen
is on and the phone is unlocked. A zero-second wait grants the session directly.

A session is an elapsed-time access window. Its expiry continues while the user
switches away or locks the phone. Browsing budget is charged for active protected
browsing use, not for every elapsed minute in that window. Chrome, Firefox,
Edge, Opera and X share that budget. Running out of budget can block normal
browsing before the selected session window ends. A new session still needs the
gate's normal unlock flow. The assistant cannot start a session or force access.

Example: a five-minute session does not immediately deduct five minutes. Only
qualifying use is charged, but returning after the window expires needs another
unlock. Emergency access is different; see `emergency`.

## rewards: Credits, shared progress and delayed verification

A verified recording contributes its measured units divided by the task's
units-per-credit requirement. Partial progress combines across tasks. Each
completed whole credit earns browsing minutes at the recording's pinned reward
rate and counts toward daily YouTube and emergency eligibility.

Example: half a credit from one task plus half from another completes one credit.
The completed credit earns the reward rate pinned to the recording that completes
it. Existing credited minutes are not repriced when settings change. Task rules
and reward rate are fixed when recording begins. Repeating an upload or applying
the same receipt again does not earn duplicate rewards.

No real reward is granted for a preview recording. No reward is granted until
verification succeeds and Android saves the result. A recording delivered after
its daily reward period expires adds history only. Old receipts keep their
original eligibility flags; a change of settings cannot revive expired rewards.
See `corrections` for editing verified measurements and `offline` for retries.

## tasks: Creating, changing, pausing and removing activities

The assistant can add, edit, pause, resume and remove tasks, up to 50 tasks total.
A task needs a name, verification method, measurement, units per credit,
correction minimum and judging instructions. Ask about missing activity and
reward preferences; the assistant may draft judging instructions for review.

Supported methods are video with repetitions or seconds, and GPS with meters in
walk or sprint mode. Yes/no verification, example-video verification and arbitrary
new methods are not supported. Names can describe custom activities rather than
only starter exercises, but their measurements must fit an available method.

Task names allow 1–80 characters; judging instructions allow 1–2,000 nonblank
characters. Units per credit and correction minimum are whole numbers from 1 to
100,000. Video correction minimum is additionally capped at 30 seconds or 150
repetitions. IDs are unique and cannot be renamed after creation. Pausing hides a
task from available activity choices. Removing it preserves historical receipts.
Changes apply to future recordings; already started recordings keep pinned rules.
See `video` and `gps` for recording behavior.

## video: Video activity recording and verification

Choose the configured video task and record evidence for it. A preparation
countdown precedes capture; recordings are limited to 30 seconds. The verifier
uses that task's judging instructions to measure repetitions or active seconds.
The recording must visibly show enough evidence for the chosen activity.

The backend preprocesses video and uses the configured verification model. The
measurement is converted into credits by the app; the conversational assistant
does not decide or directly award the result. Repetition counts must be whole;
duration measurements may include fractions of a second. Duration corrections
cannot exceed the recorded clip's allowed duration. Temporary server video files
are deleted after processing. Submitted local evidence can be retained for retry
until its outcome is resolved; do not promise it is never stored or uploaded.

The preview's optional test recording checks a proposed task without earning
real credits or browsing time. If a result is wrong, see `corrections`. If an
upload is interrupted, see `offline`.

## gps: Walking, sprinting and location quality

GPS tasks measure meters, using walk or sprint mode. Walking excludes speeds
above 18 km/h. Sprint qualifying sections are 15–35 km/h. Checks reject mock
locations, inaccurate readings, stationary drift, gaps and implausible jumps.
Task reward distance is configurable, but these validation rules are fixed.

Recording needs location permission, enabled location services and usable GPS.
It can continue with the screen off. Reaching the task target does not stop the
recording automatically: the user stops it. The maximum recording is four hours.
Distance and speed depend on usable readings, so measured qualifying distance may
differ from the route actually traveled.

When verification identifies eligible unreliable GPS coverage, the app may offer
manual completion for exactly one credit. It is not a general way to claim any
number of meters. Preview recordings never qualify for this fallback. The
assistant cannot turn on fallback or submit it for the user. See `offline` and
`corrections` for recording recovery and expiry.

## corrections: Correcting measurements and manual GPS completion

Corrections are available only when the original verified measurement reaches
the task's pinned correction minimum. A zero or insufficient result does not
authorize arbitrary manual entry. Repetitions remain whole; duration and distance
can have decimal precision. Corrections cannot exceed the supported measurement
limits, and video duration is bounded by the recorded clip.

Corrections recalculate the recording's contribution and affected shared progress
and rewards. Increasing or decreasing the measurement can change available
minutes and unlock eligibility; already consumed time is not erased. Re-saving
the same value does not create another reward. The original measurement remains
part of the record. A correction cannot restore rewards from an expired period.

Eligible unreliable-GPS manual completion grants one credit once; it cannot be
corrected again into a larger award. The user performs corrections or manual
completion in the dedicated app flow. The conversational assistant can explain
them but has no tool to submit corrections, fabricate evidence or grant credits.

## youtube: YouTube access

The official YouTube app has a daily earned-credit requirement, configurable from
1 to 100, default 4. Credits from supported tasks count toward it. Once the
threshold is met, YouTube is available until the daily reset and does not consume
the shared browsing budget. Partial credit alone does not meet a whole-credit
threshold. The daily allowance is browsing time, not earned credit.

Changing the threshold can change whether today's earned credits are enough.
Correcting a recording can also change eligibility. The assistant can configure
the threshold through review but cannot directly unlock YouTube or invent earned
credits. Use current phone statistics to answer whether it is unlocked now.

## emergency: Emergency unlock

The first completed daily credit earns access to one emergency unlock that day.
Its configurable duration is 1–120 minutes, default 15. The allowance alone does
not earn it. The user starts it through the gate; the assistant cannot use or
reset its counter for them.

Emergency access is an elapsed-time window and does not consume the browsing
budget. It can therefore be useful when earned browsing time has run out.
Recorded foreground use can still appear in usage reports. Changing the duration
applies to new emergency sessions, not one already running. Daily reset clears
the daily use/earning counters; another credit must be earned for that day's
emergency eligibility. It does not bypass YouTube's credit threshold.

## assistant_settings: Model, reasoning and processing speed

Sol 6 (`gpt-6-sol`) is also available in Assistant settings with non-reasoning,
low, medium, high, xhigh and max efforts and Standard or Fast processing.

Open Your setup → Assistant. Choose Sol 6, Astra, Luna or Terra, a reasoning effort,
and Standard or Fast processing, then tap Save assistant settings. Luna and
Terra support Non-reasoning, Low, Medium, High, Extra high and Max. Astra supports
Low through Max, with no Non-reasoning choice. All three support Fast mode.
The default is Astra / Low / Fast. More reasoning may take longer, while Fast
uses higher pricing. Preferences persist for this installation and apply to the
next request, including backend work delegated from voice. Requests already
running keep their original selection. Jev still handles confident common edits
first; other work uses the selected model. Voice audio remains GPT-Live/Ripple;
video verification uses its separate model.

Saving these preferences does not alter app rules, rewards, pending reviews or
the phone's configuration revision. It needs no review or Android acknowledgement.
The assistant can read the current selection but cannot change it by conversation;
use these controls. None/Low requests have a 60-second provider timeout and a
2,400-token output allowance; Medium/High/Extra high/Max have 180 seconds and
8,192/16,384/32,768/32,768 output tokens respectively. The total context ceiling
remains 100,000 tokens including the allowance. Jev's timeout remains 3 seconds.

## assistant_routing: Jev and selected model

With the server's AI Gateway key configured, Jev first interprets explicit
waiting-time, minutes-per-credit, YouTube-threshold and emergency-duration edits,
plus pause/resume of existing tasks. For example: “Set waiting time to 7 seconds”
or “Pause walking.” The settings limits above still apply. Jev needs confident
scope, target and value selections (threshold 0.9); uncertain results and requests
outside that scope go to the selected assistant model. This includes questions, renaming,
new tasks, allowance/reset edits and mixed requests needing unsupported changes.
The threshold does not guarantee correctness; users still review every change.

The provider timeout is 3 seconds, with no Jev retry. Provider failures or missing
gateway configuration fall back to the selected assistant model. Fallback requests can take longer.
The assistant receives a brief explanation of Jev as a specialized decision model
that selects predefined options, plus the handoff reason: an evaluated request
was not accepted, the provider failed/timed out, or Jev was not run. Provider
failure does not imply complexity. Rejected guesses are not recommendations.
The assistant checks the whole original request, clarifies missing choices,
avoids replacing invalid values, and respects a requirement to explain an
unavailable action before proposing changes.
Text and delegated voice use this same route. Approval and cancellation keep
their direct paths. Pending edits survive follow-ups, newer reviews cannot be
overwritten by late results, and phone acknowledgement is still required after
saving. Routing adds no permissions or new controls.

## review: Review, approval, previews, cancellation and undo

The assistant proposes requested changes together in a review, with a card for
each setting or task. The user can adjust or remove cards before approving.
Previewing a setting or testing a proposed task does not activate it or grant
rewards. Merely asking to change a setting creates a proposal, not approval.

In the conversation, reviews appear where they were created, with subsequent
messages and the live voice transcript underneath. Saved, cancelled and undone reviews collapse to a compact
summary. Tap the summary to reopen the details; only the latest saved action offers whole-request undo. Older actions and
individual saved cards cannot be restored. Cancel any pending review first.
Undo restores the exact previous configuration, including task order. Undo
cannot itself be undone and does not unlock older actions; a new save enables
undo for that new action. Recent earlier reviews remain accessible when a new
request is created. Pending reviews stay expanded for inspection and approval.
The main screen says "Change your app by voice": tell the AI what you want,
then review and approve. The conversation screen explains that speaking can add
activities, adjust rewards and change limits, with "Make the wait shorter" as
an example. This large introduction is shown before the conversation begins. Returning
conversations use the compact microphone beside Send, with the reminder
"Tap the microphone to change your setup."
The microphone button labelled "Start voice chat" starts an out-loud conversation.
A short, soft two-note sound plays when the connection is ready (if device audio
is audible), not while it is still connecting. "End voice chat" ends it. During
a call, the microphone beside Send becomes a contrasting stop icon labelled
"End voice chat" for accessibility, with "Voice chat on · Tap stop to end" below.
Tap it to end the call; it returns to a microphone that starts the next call.
This toggle stays reachable while scrolling and is disabled while connecting. Text entry remains available below the conversation.

The user approves the displayed version with Apply or explicit text/voice
approval. If that review changes, an old approval cannot apply it. Duplicate
confirmations are safe. Saved settings become active only after the phone
receives and persists them. Say "saved; waiting for your phone" until Android
acknowledges them. For allowances, active configuration still waits until reset
to affect the balance.

An explicit cancel discards pending work. Undo restores only the entire latest
saved action, once; older actions and individual saved cards are unavailable.
Undoing configuration
does not reverse browsing already used or recreate expired rewards. Interrupting
voice speech alone does not cancel a pending app change; ask to cancel it.

The app keeps one conversation per installation. It retains at most 100 recent
messages; the UI shows the latest 30 messages and 12 reviews. Model requests use
recent saved messages within a conservative 100,000-token context ceiling,
including instructions, app state, tool definitions, tool results, framing and
room for the selected reasoning effort’s output allowance (2,400–32,768 tokens). The check runs before every configuration-agent
call, including requests delegated from voice. Oldest complete exchanges leave
the model context first. The current request and authoritative app state are
kept intact. If those alone exceed the budget, the request stops before calling
the model and asks the user to shorten the request or simplify long task
instructions; manual controls remain available. The conservative byte-based
calculation can retain less than 100,000 actual tokens. Context trimming does not
undo settings or remove saved activity records. Do not claim to remember older
conversation details that are no longer supplied. Live audio calls remain
separate, temporary sessions with their existing five-minute default limit.

## offline: Offline operation, syncing and retries

The phone enforces its last applied settings when offline. New settings saved on
the backend wait for the phone to reconnect and acknowledge them. A conversation
or new verification request needs the backend. A saved review is not proof that
the phone has already applied it.

Captured video/GPS evidence is saved for supported upload retries. Internet loss
during GPS recording does not itself require discarding the recording; completed
evidence can be verified after reconnecting. App death ends GPS recording; the
interrupted live recording does not resume automatically. Do not promise recovery
of evidence that was never saved. Retry pending recordings through the app.

A retried result is credited at most once. Reconnecting after the recording's
daily reward window has expired can save history without restoring expired time.
The dashboard's retry connection/saved recordings action can help complete sync.
If verification fails, the assistant cannot replace it with a manual award.

## troubleshooting: Explaining blocked access, missing time or unapplied changes

Start with the question, fresh configuration and device statistics. Check the
observation time and whether the phone applied the configuration. Ask for the
visible error only if the available context cannot identify what happened.

For missing allowance minutes: saving the allowance never tops up the balance
now; check the next daily reset and whether the phone applied the new setting.
For normal browsing still blocked: check remaining time, session expiry, gate
wait and reported protection health. For YouTube: check earned credits against
the current threshold. For emergency access: check whether a credit was earned
and whether the day's emergency unlock was already used.

For a missing task reward: check whether the result is verified and saved,
whether it was only a preview, partial rather than a full credit, a duplicate,
or delivered after expiry. For unapplied settings or pending uploads: check
connectivity and try the app's retry action. The assistant may explain required
accessibility or location setup, but cannot grant permissions or inspect a live
screen. Do not claim a specific phone problem from stale or absent statistics.

## privacy: Available statistics and their limits

The assistant can read settings, review state, phone application status and
statistics uploaded by the phone. These include remaining/used/granted browsing
time, daily credits, partial progress, emergency usage, gate/protection status,
reset time, time zone and observation timestamps. This is not live screen access.

Use `read_details` with `history` for actual uploaded history. It includes up to
14 days of daily usage, at most 80 browser sessions, 60 task receipts and 30
corrections, with truncation indicators. Missing days are unknown, not zero.
Browsing records do not identify individual apps or websites, nor classify every
session as emergency or charged usage. Recorded usage may exceed charged budget
use because emergency sessions are free. Exact expired balances are not stored.

Task recordings go to the backend for verification. Conversation and verification
use configured AI providers. Do not promise all data stays on-device, anonymity,
or an unsupported deletion/export feature. The assistant cannot delete history,
change permissions, read arbitrary files or manufacture missing usage records.
