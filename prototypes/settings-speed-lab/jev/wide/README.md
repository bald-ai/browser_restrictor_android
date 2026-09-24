# Broad Jev interface experiment

Uses the **current** `backend-v1` schema, validation, review Store, and general
agent. This is separate from the earlier frozen-snapshot experiment one directory
up. All installations are synthetic and local; Android and production are untouched.

## Run

From `prototypes/settings-speed-lab`, with existing npm dependencies installed:

```sh
npm run jev:wide:test
RUN_ID=my-fresh-run MODE=sweep SPLIT=fresh VERSION=v4 THRESHOLD=.75 REPEATS=2 \
  node --env-file=/path/to/gateway.env jev/wide/run.mjs
RUN_ID=my-comparison MODE=compare VERSION=v4 THRESHOLD=.75 REPEATS=2 \
  CASES=wait,daily,minutes,reset-pm,presets-and,pause,rename,group,mixed-creative,ambiguous,camera,mixed-unavailable,selected,pending,h-distance,h-hypothetical \
  node --env-file=/path/to/gateway.env jev/wide/run.mjs
OUTPUT_DIR=/absolute/output/directory npm run jev:wide:report
node jev/wide/serve-report.mjs /absolute/output/directory
```

`AI_GATEWAY_API_KEY` is read by the AI SDK. General-model credentials use the
existing `prototypes/agent-ux-lab/.env` OPENAI_API_KEY. New run IDs are required;
the runner will not overwrite evidence. Results live in `results/jev-wide`.
The report currently aggregates the named research runs listed in `report.mjs`;
add new run names there when comparing further experiments.

`SPLIT` supports development (default), `holdout`, and `fresh`. `CASES` explicitly
selects IDs. Do not call reruns of exposed cases fresh validation.

## Design

One Jev call asks routing plus target/value questions against the same state.
Supported fast-path outputs are existing setting/task edits. Exact text choices
come from quoted user text. Code supplies bounded numeric candidates, unit and
relative-value candidates, times, and session triples. These are candidate
values, not a semantic parser; Jev must select the right ones. Missing values,
uncertain outputs, invalid configurations, and unsupported scope fall back.
No test answers or case IDs are passed to either model.

V1: inclusion booleans and value choices, threshold .90. V2: no inclusion
booleans, conversion candidates. V3: inclusion booleans restored, threshold .75.
V4: nonexclusive inclusion wording and canonical AM/PM candidates. The threshold
is empirically tuned and not a correctness guarantee. Development sweeps,
original holdout, post-fix regressions, and fresh validation remain distinguishable.

Comparison arrangements:

- General alone: the maintained configuration agent.
- Jev-first: accepted Jev proposal, otherwise start the same general agent.
- Parallel: both begin on isolated stores; first usable result wins. A rejected
  Jev result cannot beat the general answer. Losers finish for trace collection.

Only selected results count toward comparison outcomes. Stores are isolated,
so speculative work never mutates a winning installation. There is no production
request-cancellation optimization. Exact approvals/cancels are tested through
the existing deterministic agent path, without inference.

## Scoring and measurement

- Fixed edits: full proposed configuration must equal the authored expectation.
- No-edit requests: no configuration change or added proposal; text retained for
  inspection. This does not grade explanatory prose automatically.
- Generated edits: required fields change, other fields remain intact; quality
  of generated prose is not independently scored.
- Mixed unavailable requests: whole request must be addressed before proposing
  partial action. Current general-agent partial proposals fail this expectation.
- Reviews: saved state remains unchanged before approval; Apply, persisted reload,
  and Undo are audited outside response timing. An existing removal-undo task
  ordering defect is separately identified.
- Latency: invocation to backend-ready proposal/answer, measured with a monotonic
  clock. No UI paint, device acknowledgement, voice, or human completion time.
- Comparisons alternate order across two repetitions. Small samples and network
  variability limit conclusions; the workload is not usage-frequency weighted.

`run.mjs` records provider answers, raw general outputs, durations and usage.
Later runs archive their exact harness source. Provider errors remain outcomes;
the harness does not silently retry them. Runtime imports refer to the current
backend, so also preserve the repository revision when reproducing later.

The report is a standalone HTML explorer, Markdown report, CSV and JSON evidence.
It is not a deployed replacement for the live app interface.
The optional report server binds to localhost:4343 and serves only report files.
