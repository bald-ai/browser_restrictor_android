# Jev settings experiment

This is a text-only comparison in the existing local web prototype. It reuses the
frozen focused-edit agent, PatchStore, frontend, review approval and undo. It does
not modify or connect to Android. The snapshot has historical settings; it is not
a benchmark of the current deployed backend.

## Run

Requires Node and `npm install` in `prototypes/settings-speed-lab`. Supply
`AI_GATEWAY_API_KEY` to the server (for example with Node `--env-file`). The general
agent uses `OPENAI_API_KEY` or the existing `../agent-ux-lab/.env` experiment key.

```
JEV_RUN_ID=my-run node --env-file=/path/to/gateway.env jev/serve.mjs
# Separate terminal, same JEV_RUN_ID:
JEV_RUN_ID=my-run npm run jev:benchmark
npm run jev:test
JEV_RUN_ID=my-run JEV_INTERVAL_MS=0 JEV_HOLDOUT=1 npm run jev:benchmark # optional new phrasings
JEV_RUN_ID=my-run npm run jev:report
```

Choose a NEW run ID to preserve evidence. Dashboard: http://localhost:4340;
general model: port 4341; Jev hybrid: port 4342. These servers bind to loopback.
The server must be restarted with the same run ID before a benchmark. Results,
raw provider requests/responses (without credentials), metrics and local data go
to `results/jev/<run-id>/`. Prior settings-lab results are untouched.

## Jev scope

One parallel Choice request evaluates whether the entire user request fits the
fast path, four numeric fields (wait, reward minutes, YouTube threshold, emergency
minutes), and pause/resume for each actual configured task. Numeric choices cover
0–120 within each field's valid range; waiting time also includes larger literal
integers from the request up to its 600-second limit. A separate boolean asks whether each numeric setting is explicitly changed. A
probability <= 0.1 leaves it alone; >= 0.9 requires a value choice with probability
>= 0.9; intermediate values delegate. Scope and task choices also require >= 0.9.
Value uncertainty for a clearly unchanged setting is ignored. This is an experimental threshold, not a
validated correctness guarantee. Full-context examples and expected test answers
are NOT inserted into inference requests. Ambiguous/unsupported/low-probability
requests go to the same general agent; provider failures are surfaced as errors.
Existing direct approval and cancellation paths remain in use. The general model
is GPT-6 Astra low reasoning, fast tier. Jev uses `typesafe-ai/jev` via AI SDK 7.

## Protocol

Separate first-call warmups; three repetitions of five bounded edit scenarios,
plus pending-review correction, resume, invalid/ambiguous/question cases and a
rename that must delegate. AB/BA order alternates; requests are sequential and
browser installations independent. Tests check the entire proposed config,
unchanged saved state before Apply, button approval, durable reload and Undo.
Latency includes existing UI polling. Provider, total agent, visible review and
save-button timings are recorded separately. Failed trials stay in the report;
latency summaries are for passing edits only. Set `JEV_INTERVAL_MS=0` for paid-tier runs; the default 15-second minimum interval is outside measurement. A provider 403/429 stops the suite after preserving the failed trial. This does not measure voice,
Android acknowledgement, physical enforcement, or broad language coverage.

A per-run $5 estimated model-spending guard is checked before calls. Estimates
are not invoices; the guard is not an account spending limit. Jev input is
estimated at the displayed Gateway rate of $0.04/million tokens. General-model
estimates retain the older lab's rate formula. Restarting reloads the saved usage estimates for that run. Voice endpoints are disabled in this text experiment.

API access requires a Vercel account enabled for Gateway requests. A card-required
403 was observed during initial setup; the first successful call followed account
activation. No paid-call result should be inferred from the local unit tests.
