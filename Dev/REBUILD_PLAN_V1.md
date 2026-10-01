# Rebuild plan v1 — product scope

Date: 2026-09-16

This records the product decisions from the design interview, updated with later prototype decisions on 2026-09-16. Later explicit user decisions supersede earlier interview answers. The voice/function prototype has been implemented and tested; integrating the rebuild into Android still requires a build brief and implementation request.

## Purpose and audience

Build a friction app for an experimental public launch for an OpenAI competition and Product Hunt. It is for people who want to do things, struggle with self-control because of ADHD or other reasons, and are willing to tinker with the friction that works for them.

The central purpose is deliberate friction before app access. Analytics are supplementary. Trust users to tune their own setup; avoid making friction so inflexible that they delete the app.

## Agent as the interface

- The agent is a conversational interface to capabilities we provide, not a coding agent that changes the app or builds new verification methods.
- Technical direction updated on 2026-09-16: use model function calling with app functions executed by our backend. Drop the managed Agents API/coding-agent runtime. "Agent" elsewhere in this product plan describes the conversational UX, not a requirement for an agent framework.
- The current architecture overview is [ARCHITECTURE_V2.md](ARCHITECTURE_V2.md), with [its diagram](ARCHITECTURE_V2.png). It distinguishes the working browser prototype from proposed Android integration. Despite its higher version number, REBUILD_ARCHITECTURE_V3.md is an older proposal; earlier coding-agent diagrams are also superseded.
- Voice and text provide the same capabilities.
- Start with a working default setup. The agent explains it and offers customization. Its introduction can be skipped after required permission setup.
- Agent access is from home/settings, not the blocking screen.
- Show task/settings cards alongside conversation so users can see the actual values and changes.
- Resolve references such as "this task" from the visible card and current conversation. Ask when intent is ambiguous.
- Agent-proposed configuration changes produce a visible review card before saving, including simple settings edits. The user confirms with the card button or explicit voice/text approval in a later turn. Revised proposals need fresh approval. Task judging descriptions are included in the review. After approval, apply changes without an editing-window delay and verify the saved result before claiming success. This supersedes the earlier immediate-edit/no-confirmation decision; ordinary manual controls retain their normal save interaction.
- Provide a "Try it" preview for changed behavior without spending rewards or changing progress. Technical validation of actual behavior must also be designed later.
- Explain questions such as "why am I blocked?", "where did my minutes go?", and "what would changing this reset time do?" using actual rules and records, with relevant cards.
- Preload current setup and a concise summary of recent app usage, task completions, and earned/spent time; retrieve details when needed.
- Offer advice when asked, not proactively outside user requests. Initial setup explanation remains part of onboarding.
- Persistent conversational memory is deferred. Loading current setup and statistics does not require it.
- Support explicit cancellation and undo for agent changes. Before saving, discard the proposal; during saving, prevent the save when possible or restore the previous values afterward; after saving, restore the changed values and verify them. Preserve unrelated newer edits, reject conflicting newer edits, and prevent repeated cancellation from undoing older changes. This supersedes the earlier no-undo decision. Full setup version history and recovery remain outside launch scope.
- No setup export/import, account restoration, or cross-device setup recovery is required for launch. Authentication architecture remains a later technical decision.

## Editable product surfaces

Users can change these through the agent within capabilities we provide:

- Task names, descriptions, judging instructions, measurement requirements, rewards, and correction minimums.
- Add, edit, rename, pause, and remove tasks, including starter tasks.
- Waiting duration, including zero to disable the wait.
- Session lengths, reward amounts, reset times, and unlock thresholds within the existing mechanisms.
- Emergency unlock duration only; its earning rule remains fixed.
- Task and unlock button names.

Common manual controls cover waiting time, session lengths, reward amounts, reset times, emergency duration, and pausing tasks. Task creation and detailed verification instructions use the agent.

We control layouts, recording controls, verification-method capabilities and safeguards, permission explanations, and error/recovery screens. New tasks appear through layouts we design. The agent does not freely redesign screens or create new sensors or verifier capabilities.

## Tasks and creation

Tasks are defined by verification methods rather than an exercise-only category. Brushing teeth can be a video task just as push-ups can.

Starter tasks:

- Push-ups.
- Plank.
- Sprint.
- Walking, initially **1 km per credit**.

During task creation, the user specifies each of:

1. Task name.
2. Verification method: video or GPS.
3. Measurement/completion requirements.
4. Reward contribution.
5. Minimum evidence required before correcting the result.

The agent asks for these details instead of silently filling in a complete proposed task. The user describes the activity in words and reviews and approves the resulting judging instructions. Example videos are deferred; verification uses the approved description.

Offer a test recording before activation, but allow activation without testing.

## Verification and corrections

### Video

- Keep the existing fixed **30-second recording limit**.
- Support measured repetitions and active duration. Duration means time actually performing the activity, not simply elapsed clip length.
- Users select the task before recording, so the verifier knows which instructions to apply.
- The product emphasizes activation friction: demonstrating the activity can earn measured progress without proving an entire longer routine was completed.
- Yes/no outcomes, such as "the bed is made," are deferred.

### Corrections

- Preserve existing corrections and extend numeric measurements to supported tasks.
- The user defines task-specific minimum observable evidence before a result becomes correctable, such as at least one detected push-up or some actual brushing.
- Use observable evidence rather than an untested model confidence score as the acceptance requirement.
- Retain the existing GPS fallback: unreliable tracking can offer manual completion. Current behavior grants one credit/set for that confirmation.
- Suspected vehicle travel excluded by safeguards does not become eligible for the unreliable-GPS manual-completion fallback merely because it was rejected.
- Retain existing correction behavior unless explicitly changed here; detailed implementation and consistency checks belong to technical planning.

## Walking

Borrow the recording experience and GPS safeguards from the user's walk-to-unlock feature in `mac_lock_prototype`, while using this app's credit and daily-reset rules.

- Start a walk and wait for GPS.
- Continue recording with the screen off.
- Show live distance and elapsed time; live distance is provisional until validation.
- Stop manually. Reaching a reward distance does not automatically end the recording.
- End automatically at four hours. This is a recording cap, not a minimum walking duration.
- Exclude sections above 18 km/h, stationary drift, poor GPS readings, and gaps. Later valid walking can still count.
- Use GPS speed and recording-quality checks for launch. Additional step/activity recognition is deferred and may never be wanted. GPS-only checks do not promise perfect detection of slow vehicle travel.
- Partial distances from multiple recordings combine toward credits within the day. Do not require 2 km in a single recording.
- Do not import the other product's Mac unlock, 05:00 Prague schedule, special point rewards, two-walk limit, two-hour spacing, or capped free-unlock balance.
- If the app is closed by Android or the phone restarts, end the recording and preserve valid recorded distance for submission. A later walk is a new recording.
- If internet drops, save the recording locally and validate on reconnect. No reward is granted before validation.
- Prevent duplicate rewards when submission is retried.

## Credits, access, and resets

- Preserve the existing shared-progress reward mechanism. Use a neutral default name such as **credit**, which the agent can rename, instead of exercise-specific "set" wording.
- Keep the current two protected-app groups: browsers/X share earned browsing time; YouTube opens after a credit threshold. Do not add arbitrary app grouping for launch.
- Keep the current mechanisms. Direct rules such as "brush your teeth to open YouTube" are deferred.
- Measured partial progress carries forward toward a complete credit within the daily period.
- Every verified attempt can contribute rewards; no per-task daily earning limits at launch.
- Reward-rate changes apply to future completions, preserving rewards already earned.
- Earned browsing time and unfinished credit progress expire at their daily resets. Users can edit reset times but cannot disable resets or carry yesterday's balances forward.
- Late submission must not bypass expiry and create spendable rewards from a previous daily window. Exact boundary handling should be made explicit in technical design.
- Keep the existing defaults for existing rules, including the 12-second wait, three browsing minutes per credit, current session presets, current reset times, and current YouTube threshold.
- Preserve the emergency earning rule: the first completed credit of the day earns one emergency unlock. Only its duration is configurable; retain the existing 15-minute default.
- Waiting time is one shared configurable value. Different waits by app or time of day are outside the agreed launch scope.

## Home screen and connectivity

- Focus the home screen on credits, available time, and unlock progress.
- Remove the dedicated monthly push-up section from the home experience; do not replace it with a general task analytics section for launch. This does not authorize deleting historical records.
- Internet is required for the product. The explicit exception is preserving GPS recordings through temporary connection loss for later validation.
- Technical planning must define connection failure behavior without silently introducing a fully offline product.

## Deferred work and next step

See [deffered_ideas.md](deffered_ideas.md) for explicitly deferred ideas, including editing windows, direct task unlock requirements, API spending controls, reference videos during verification, yes/no outcomes, additional walking detection, and persistent memory.

The voice/function prototype now demonstrates review cards, explicit approval, saved-configuration verification and cancellation/undo using GPT-Live-1 with Ripple over WebRTC and GPT-6 Astra Low Fast through the Responses API. Android integration and real activity verification are not implemented by that prototype. The next phase is a build brief covering data ownership, synchronization, verifier integration, connectivity and acceptance checks. Proposed answers are recorded in [V1_BUILD_PROPOSALS.md](V1_BUILD_PROPOSALS.md); they are recommendations, not additional accepted product decisions. This plan alone does not authorize starting the Android rebuild.
