# Jev versus Clef decision models — 2026-10-08

Run: `jev-vs-clef-2026-10-08-v1`. Evaluator-only (no LLM fallback), 37 scenarios, one pass each plus one warmup. Same production question set (`buildQuestions`), same decoder and acceptance rules. Jev via AI Gateway (`typesafe-ai/jev`); Clef via Workers AI (`@cf/cloudflare/clef`) through the benchmark-only adapter `workers-ai.mjs`, which only renames Jev `boolean` questions to Clef `noul`. Both used a 15-second timeout so true latency is visible (production Jev timeout is 3 seconds). Price was out of scope.

## Live result at the production 0.9 threshold

| Model | Actions passed | Wrong | Errors | Safe guards | p50 | p95 |
| --- | --- | --- | --- | --- | --- | --- |
| Jev | 12/26 | 0 | 0 | 11/11 | 0.58 s | 0.93 s |
| Clef | 2/26 | 0 | 2 (timeouts) | 11/11 | 2.19 s | 15.00 s |

The 26 actions include edits outside the fast path (rename, reset time, task creation, etc.). Abstaining on those is correct for an evaluator; the cascade sends them to the LLM.

## Why Clef abstained

Clef selected the correct value in every in-scope numeric case, but spread probability over the 120+ value options: top value 0.63–0.81 versus Jev's 1.00. The 0.9 threshold was tuned for Jev. Clef's yes/no and task choices were confident (0.94–0.99).

## Offline rescoring of the stored answers (single-turn cases, no new calls)

| Model / threshold | Correct | Wrong accepted | Unsafe guards | Errors |
| --- | --- | --- | --- | --- |
| Jev 0.9 (production) | 10 | 0 | 0 | 0 |
| Clef 0.9 | 1 | 0 | 0 | 2 |
| Clef 0.7 | 10 | 0 | 0 | 2 |
| Clef 0.6 | 13 | 0 | 0 | 2 |

At 0.6 Clef additionally solved `unit-conversion` (2 minutes → 120 s), `relative-reward` ("double") and `selected-pending-correction`, which Jev abstained on; it lost `pause` to a timeout. Lowering Jev's threshold adds one correct and one wrong (`grouped`). The 0.6 threshold was chosen after seeing these answers, so the Clef gain is optimistic until confirmed on a fresh run.

## Latency (evaluator calls, warmups excluded)

| Model | Min | p50 | p90 | Max | Over 3 s | Median input tokens |
| --- | --- | --- | --- | --- | --- | --- |
| Jev | 0.33 s | 0.57 s | 0.81 s | 1.01 s | 0/37 | 9,443 |
| Clef | 2.00 s | 2.19 s | 11.8 s | 15.0 s (timeout) | 5/36 | 11,760 |

Clef never answered under 2 seconds, far from Cloudflare's published 209 ms median, which was measured on different, likely much shorter inputs. Our requests carry four 120+-option value lists. With the production 3-second timeout, 5 of 36 Clef calls would fail. Jev had zero provider errors in this run (earlier runs saw 16–22/37).

## Conclusion and limits

Clef understands these requests at least as well as Jev, but is ~4× slower at the median with severe tail latency, and needs a separately calibrated threshold. As a fast path in front of an LLM, Jev is the better choice today. One pass, one day, one Workers AI region; Clef had just launched. The Cloudflare free allowance (~458k input tokens/day) was nearly used by this run, so a confirmation run needs the Workers Paid plan or another day.

- [Report](../runs/jev-vs-clef-2026-10-08-v1/REPORT.md) · [Raw results](../runs/jev-vs-clef-2026-10-08-v1/results.json) · [Persistent record](../history/jev-vs-clef-2026-10-08-v1.json)
