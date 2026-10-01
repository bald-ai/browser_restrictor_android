# Deferred ideas

Ideas explicitly deferred during product design. Recording an idea here does not authorize implementation.

## Optional setup editing windows

Deferred on 2026-09-16. Launch with agent changes taking effect after review and approval, without restrictions on which times of day editing is allowed. Later, consider letting users choose times when setup changes are allowed, so they can voluntarily protect their chosen friction settings. Exact behavior and exceptions remain undecided.

## Direct task requirements for unlocking

Deferred on 2026-09-16 (Q12). Later, consider rules such as "brush your teeth to open YouTube" or "complete any two tasks." For launch, retain the existing two mechanisms: spending earned minutes for the browser/X group, and reaching a completion threshold to open YouTube, with editable amounts and requirements.

## API rate limits and spending controls

Deferred for later discussion on 2026-09-16. The owner will initially pay users' API costs and wants protection against excessive bills. Revisit request limits and spending controls; their amounts, behavior, and timing remain undecided. Deferring this discussion does not mean agreeing to unlimited API spending at launch.

## Using example videos during future verification

Deferred on 2026-09-16 (Q32). An optional example recording helps the agent draft a task's judging description, which the user reviews and approves. Future verification uses the approved description only. Revisit supplying the example video to future verification only if experience shows it is needed; the user currently doubts this will be necessary.

## Yes/no task outcomes

Deferred on 2026-09-16 (Q46). Support tasks with discrete completion outcomes, such as "the bed is made," in a later version. Launch uses repetitions, active duration, and GPS distance/speed, with unfinished credit progress carrying forward until the daily reset. Binary outcome verification, rewards, and correction behavior remain to be designed.

## Additional walking and vehicle detection signals

Deferred on 2026-09-16 (Q49). Potentially supplement GPS speed and recording-quality checks with step detection or walking/vehicle activity recognition. The user is not sure they want this feature at all; this is an idea to reconsider, not a planned commitment. For now, use GPS speed and recording-quality checks alone. Extra signals could add permissions and incorrectly reject genuine walking.

## Persistent agent memory

Deferred on 2026-09-16 after Q59. Revisit a basic OpenClaw-inspired memory setup for preferences and useful context across conversations. A separate research task, "Investigate OpenClaw memory for the friction app" (01a0a776-f6a1-75d2-b19f-f572612e1e23), was created; its findings are for later consideration, not implementation. The prototype can still load the current app setup and a concise recent statistics summary, with details fetched when needed; this does not require persistent conversational memory.
