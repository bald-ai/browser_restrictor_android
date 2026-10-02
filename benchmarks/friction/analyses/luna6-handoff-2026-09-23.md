# Luna 6 handoff prompt comparison

Baseline: `luna6-sol6-jev-fast-2026-09-23-v1`. Updated: `luna6-handoff-jev-fast-2026-09-23-v1`. Both complete for Luna: 37 identical scenarios per effort, one pass, Jev first, Fast tier, GPT-6 Luna at none and low. Suite hash unchanged.

## Change

The general assistant now receives a definition of Jev as a specialized non-generative model selecting predefined options with confidence scores. The system instructions identify a rejected/uncertain evaluation, infrastructure failure, or no Jev evaluation. They explicitly require checking the entire request, asking about missing choices, avoiding guessed or substituted values, and respecting explanation-before-proposal conditions. No rejected candidate answers or raw provider error messages are forwarded. The benchmark and production Agent use the same prompt helper. Jev questions, threshold and decoding were unchanged. This is a combined prompting intervention; results do not isolate the effect of explaining Jev from the added behavioral guidance.

## Old versus new

| Luna effort | Wrong proposals old → new | Median old → new | p95 old → new | Scored cost old → new |
| --- | --- | --- | --- | --- |
| Non-reasoning | 5 → 3 | 1.76 → 2.00 s | 6.58 → 4.66 s | $0.0284 → $0.0285 |
| Low | 4 → 3 | 1.87 → 2.35 s | 8.18 → 4.15 s | $0.0279 → $0.0299 |

Both efforts retained correct decisions on 26/26 action scenarios. The shared task-removal undo-order bug remained, giving 25/26 end-to-end action passes. Wrong proposals in the table concern guard cases and were all selected by Luna, not Jev.

## Observed case changes

- **jev-luna6-none-fast:** now handles conflicting, mixed-unavailable without a wrong proposal; remaining failures: ambiguous, excess-allowance, fractional-reward.
- **jev-luna6-low-fast:** now handles mixed-unavailable without a wrong proposal; remaining failures: excess-allowance, fractional-reward, incomplete-task.

Both efforts now explain the unavailable free-credit operation before proposing anything and ask whether to proceed with the supported wait change. Non-reasoning also asks the user to choose between the two undecided waiting times. Remaining failures involve fraction/out-of-range substitution, ambiguity (none), and premature task creation (low).

The 22 guard responses were inspected informally. A further semantic weakness remains: non-reasoning asked whether squats should use video or GPS distance. This produced no proposal and therefore passes the automatic no-change check, but GPS distance is not a sensible squat measurement. Counts above are wrong state/proposal decisions, not a complete semantic error rate.

## Limits and verification

One pass each over known regression cases is preliminary evidence, not a statistical or held-out evaluation. The new prompt was designed after seeing the old failures; improved results need confirmation on fresh examples. Median times rose, while p95 fell, but provider variation and different Jev fallback outcomes prevent attributing these changes to the prompt alone.
- jev-luna6-none-fast Jev provider errors, old → new: 18/37 → 16/37.
- jev-luna6-low-fast Jev provider errors, old → new: 17/37 → 22/37.

New run total estimated cost including warmups: $0.060299. Costs are estimates, not invoices. Backend tests: 137 passed. Harness tests: 15 passed, including production/benchmark handoff-reason checks and excluding provider text from instructions. Implementation is local; this experiment did not deploy the hosted backend.

## Evidence

- [New report](../runs/luna6-handoff-jev-fast-2026-09-23-v1/REPORT.md)
- [New raw results](../runs/luna6-handoff-jev-fast-2026-09-23-v1/results.json)
- [Persistent record](../history/luna6-handoff-jev-fast-2026-09-23-v1.json)
- [Old report](../runs/luna6-sol6-jev-fast-2026-09-23-v1/REPORT.md)
- [Rerun configuration](../configs/luna6-handoff-jev-fast.json)
