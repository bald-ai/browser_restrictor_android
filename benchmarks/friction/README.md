# Repeatable Friction assistant benchmark

Version 1 contains **37 frozen scenarios / 40 scripted turns**, using synthetic
installations and the maintained backend's actual validation, review, persistence
and undo code. It never connects to the phone or mutates a production installation.
Paid runs contact the configured model providers only.

## Quick start

From the repository root, with Node 22+ and backend dependencies installed:

```sh
npm --prefix backend-v1 ci
npm run benchmark:test
npm run benchmark                         # print the plan; no provider calls
```

Put `OPENAI_API_KEY` in your environment or an external Node env file. The
harness does not read prototype credentials implicitly. Run a small
infrastructure smoke test first:

```sh
node --env-file=/absolute/path/benchmark.env benchmarks/friction/run.mjs \
  --config benchmarks/friction/configs/smoke.json --run
```

Run the full comparison (3 setups × 37 scenarios × 3 repetitions = 333 scored
scenario trials, plus three warmups):

```sh
node --env-file=/absolute/path/benchmark.env benchmarks/friction/run.mjs \
  --config benchmarks/friction/configs/comparison.json \
  --run --run-id baseline-v1
```

The full configuration has a $30 **estimated** cost ceiling and 1,500-provider-call
ceiling. It may stop before finishing. Every call reserves a conservative cost
before sending; actual reported usage then settles the estimate. Unknown usage
retains that reservation. This is a local guard, not a provider billing guarantee.
A stopped run is explicitly incomplete and must not be ranked as a balanced run.
Increase the limits deliberately in a copied configuration for a larger study.
Running without `--run` only prints the plan and requires no keys.

Useful overrides:

```sh
npm run benchmark -- --cases wait,rename,selected-pending-correction --repeats 5
# Add --run once the displayed plan is the comparison you want.
npm run benchmark -- --config benchmarks/friction/configs/smoke.json \
  --repeats 3 --seed 42 --max-usd 5 --run
```

Every live run creates a new directory under `benchmarks/friction/runs/`. Explicit
run IDs cannot be reused. Ctrl+C requests a stop after the active trial finishes;
completed provider calls are also recorded incrementally in `calls.jsonl`.

## Setups

`models.mjs` registers GPT-6 Luna and GPT-6 Sol candidates only inside the
benchmark process; it does not add them to the app settings UI. Their published
Standard input/cached/output rates are $0.10/$0.01/$0.50 for Luna and
$2/$0.20/$10 for Sol per million tokens; Fast is 2x.

A setup is a named strategy tree in a JSON configuration. Each leaf is a model:

```json
{"kind":"llm","model":"gpt-5.6-luna","reasoning":"none","serviceTier":"default"}
```

This invokes the maintained configuration agent. The current
model registry supports Sol 6, Sol 6.1, Astra, Luna and Terra, their validated reasoning efforts,
and Standard (`default`) or Fast (`fast`) tiers. Add future general models to the
backend's `assistant-settings.mjs` registry with verified support and pricing.
Higher efforts use the backend's larger output allowances. A call is bounded by
both the backend timeout and the benchmark's configured timeout.

Leaves can be combined:

```json
{
  "id":"luna-or-terra",
  "kind":"cascade",
  "steps":[
    {"kind":"llm","model":"gpt-5.6-luna","reasoning":"none","serviceTier":"default"},
    {"kind":"llm","model":"gpt-5.6-terra","reasoning":"low","serviceTier":"default"}
  ]
}
```

- `cascade`: try each step until one resolves the request. Errors advance to the
  next step. A wrong resolved edit does **not** get an oracle retry. A model's
  answer or clarification is a resolved request.
- `race`: start all steps from independent copies of the same installation.
  The first resolved result wins, even if it is wrong. A failed call cannot win.
  Losing calls finish; their costs and errors remain in the evidence. Measured
  ready-time ends when the winner finishes, while wall-time includes losers and the lifecycle audit.
  This measures a speculative strategy, not production cancellation optimization.
- Nested combinations are supported. A model leaf only fails to resolve through
  an error, so a cascade serves as error fallback, not an automatic quality judge
  or planner/executor.

Exact approval/cancel commands use the shared deterministic app path in **every**
setup. They make no provider calls. No route chooses
an answer by comparing it with the expected result.

## Cases and scoring

`actions-v1.json` is a literal snapshot of inputs and expected states. It covers:

- Four common numeric edits, boundary values, unit conversion and relative edits.
- Task pause/resume, negated targets and grouped changes.
- Allowance, reset time, session presets, names and verifier instructions.
- Task distance, complete task creation and middle-task removal.
- Selected-card references, pending corrections, apply, undo and cancellation.
- Ambiguous requests, invalid values, hypothetical/quoted commands, unavailable
  controls, incomplete task creation and already-set values.
- Instructions embedded in task data that must not override the user's request.

Only the input, initial app state and prior conversation reach a model. Expected
states, case IDs and grading instructions stay in the scorer. Correctness compares
**the entire saved and proposed configuration**, including unrelated fields and
task order. Wrong changes cannot disappear inside a partial-field comparison.

For actionable cases, an automatic pass requires all scripted turns to match.
After the final review, an independent audit applies it, reopens the saved store
and undoes it. Decision correctness and lifecycle failures are recorded separately.
For example, a model may correctly remove a task but expose a Store undo-order
bug; that is a lifecycle failure, not a wrong model interpretation. If a turn
fails or abstains, remaining turns are not scored from the divergent state.

For guard/question cases, the harness checks that saved state and pending reviews
remain untouched. **It does not automatically grade whether the explanation or
clarifying question is good.** A safe text reply is `needs-review`; a safe
abstention has zero completion credit. Read the retained response before claiming
semantic accuracy. Version 1 intentionally avoids a subjective LLM-as-judge score.

## Reproducibility and fair comparisons

- Keep the suite version and hash fixed while comparing setups. Change cases by
  introducing a new suite version; never overwrite expected outcomes to improve
  a model's score. These reused regression cases are not an unseen holdout.
- Each trial has fresh local app state. Trials run sequentially; only a race's
  branches overlap. Case order is seeded and shuffled per repetition; strategy
  order rotates to reduce systematic first-run advantage.
- Warmups are charged and retained but excluded from summary scores. Provider-side
  caches are not flushed and may be shared. Actual service tiers and response
  model IDs are retained where the provider supplies them.
- Repeats measure variability, not deterministic reproducibility. Use at least
  5–10 repeats for a serious comparison, more for stable tail estimates. Three
  repeats is a starter configuration; single smoke observations are not rankings.
- Compare action completion and wrong edits first, then latency and cost. Compare
  per-case/category rows when workloads differ. This suite is not weighted by
  your real frequency of use. Identical prompts plus your real workload weights
  are needed before drawing a usage-cost conclusion.
- If a provider times out or returns an error, retain it. There are no hidden
  retries. The general agent's ordinary multi-step tool loop remains
  part of the setup and every call is counted.
- The ready latency includes model work, branch isolation, local persistence and
  orchestration. It excludes post-run audit, UI rendering, voice audio, Android
  acknowledgement and human approval. Provider timing is also saved separately.

Each run stores `suite.json`, `config.json`, hashes, Git revision/dirty status,
Node/platform metadata, and a `source/` snapshot of benchmark and backend source,
including runtime docs and dependency locks. The snapshot preserves relevant
uncommitted files, which a Git revision alone would miss. To rerun archived code,
install dependencies in `source/backend-v1` and invoke the archived runner with
`--config /absolute/run/config.json --suite /absolute/run/suite.json --run`.
Use a new `--run-id` and optionally `--out /absolute/new-results`. API credentials
and node_modules are never archived.

## Results

The completed [Luna 6 / Sol 6 comparison](analyses/luna6-sol6-2026-09-23.md)
includes the four requested setups, failure attribution and comparison limits.
The [Luna 6 handoff prompt experiment](analyses/luna6-handoff-2026-09-23.md)
compares that baseline with a revised handoff prompt (74 trials).
The [Sol 6 handoff prompt experiment](analyses/sol6-handoff-2026-09-23.md)
repeats that 74-trial comparison for Sol 6.
The [Sol 6 Low tier comparison](analyses/sol6-low-tiers-2026-09-23.md)
compares Fast and Standard processing on six scenarios repeated three times
(36 scored trials); `configs/sol6-low-tier-comparison.json` reruns it without Jev.
The [Jev versus Clef comparison](analyses/jev-vs-clef-2026-10-08.md) measured
both decision models before they were removed.

Runs up to 2026-10-08 placed the Jev decision model in front of the assistant
model. Jev and classifier strategies have since been removed from the app and
harness; their configurations remain in each `history/` record and in Git.

- `index.html`: standalone browsable summary and expandable trial details.
- `REPORT.md`, `summary.csv`, `summary.json`: summary for sharing or analysis.
- `results.json`: all scored/warmup trials, expected/actual states, route decisions,
  provider requests/outputs, errors, usage and cost estimates.
- `calls.jsonl`: incremental provider start/finish records, including race losers.
- `stores/`: isolated synthetic state used during execution and audits.

Summary metrics include action passes, completion coverage, abstentions, wrong
results, errors, lifecycle failures, guard safety, median/p95 latency for all
trials and successful actions, and estimated cost. All-trial latency includes
failed/abstained cases; the successful-only number is explicitly labelled.
Cost per passed action uses the setup's full measured workload cost, including
its guard calls and failures; it is not the cost of just successful action calls.
Do not treat a fast abstainer as the fastest useful assistant.

Regenerate reports without model calls:

```sh
npm run benchmark:report -- benchmarks/friction/runs/baseline-v1
```

Harness tests use fake provider results and no paid calls. They exercise seeded
scheduling, budget reservations, scorer isolation, abstention scoring, fallback,
race winner selection/loser accounting, deterministic actions and lifecycle audits.

## Long-term reference

[RESULTS.md](RESULTS.md) indexes completed and stopped runs. The runner automatically
saves an immutable compact record under `history/`, including configuration,
source/suite hashes and all summary metrics. These files are eligible for Git,
while bulky raw runs remain ignored. Reference a run by ID in future tasks.
Persistence across sessions is local; off-device backup requires committing and
pushing these files or another backup. The project instructions point future
benchmark work to this index.
