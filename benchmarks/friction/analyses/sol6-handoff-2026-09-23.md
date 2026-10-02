# Sol 6 handoff prompt comparison

Baseline: `luna6-sol6-jev-fast-2026-09-23-v1`. Updated: `sol6-handoff-jev-fast-2026-09-23-v1`. 37 identical scenarios per effort, one pass each; Jev first, GPT-6 Sol none/low, Fast tier. The suite hash is unchanged. Uses exactly the handoff implementation tested in the Luna rerun.

| Effort | Wrong proposals old → new | Median old → new | p95 old → new | Scored cost old → new |
| --- | --- | --- | --- | --- |
| Non-reasoning | 1 → 0 | 2.13 → 1.90 s | 4.46 → 5.17 s | $0.1657 → $0.1787 |
| Low | 1 → 0 | 1.89 → 1.97 s | 3.94 → 4.37 s | $0.1737 → $0.1718 |

Both efforts now explain the unsupported free-credit request and ask before proposing the supported wait edit, fixing the only wrong proposal observed for Sol in the baseline. Both retained 26/26 correct action decisions and 11/11 guard cases without unwanted changes. The existing undo-order issue remains once per setup, so full action lifecycle passes remain 25/26. Zero wrong proposals does not mean the entire app or every explanation is error-free.

The 22 guard responses were informally reviewed. Low unnecessarily mentioned Jev to the user in its incomplete-task clarification; its pause-all explanation was less precise about already-running recordings than the non-reasoning response. These do not alter state and are outside the automatic wrong-proposal score.

This is one repeat over previously examined regression cases. Prompt changes were informed by those cases, so fresh held-out examples and repeated trials are needed to establish general improvement. The combined change explains Jev, identifies routing outcomes and adds explicit behavioral rules; this experiment does not isolate those components. Speed changes cannot be attributed solely to the prompt. Jev provider errors remained frequent: 22/37 calls in each new setup (baseline 20/37 none, 22/37 low), recovered through the LLM.

Non-reasoning and Low have identical observed decision accuracy here. Non-reasoning has a slightly lower median, Low a lower p95; this small sample does not establish a stable speed winner.

Total estimated new spend including warmups: $0.381900. New run: 74/74 scored trials completed plus two warmups. The shared app implementation remains local; this rerun did not deploy it.

- [Full report](../runs/sol6-handoff-jev-fast-2026-09-23-v1/REPORT.md)
- [Raw evidence](../runs/sol6-handoff-jev-fast-2026-09-23-v1/results.json)
- [Persistent run record](../history/sol6-handoff-jev-fast-2026-09-23-v1.json)
- [Configuration](../configs/sol6-handoff-jev-fast.json)
- [Luna prompt comparison](luna6-handoff-2026-09-23.md)
