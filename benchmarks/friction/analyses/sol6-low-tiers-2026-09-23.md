# Sol 6 Low: Fast versus Standard

Completed run `sol6-low-tiers-2026-09-23-v1`: six frozen scenarios × three repetitions × two tiers = 36 scored trials plus two warmups. Cases: rename, complete task creation, mixed unavailable request, ambiguity, middle-task removal and session presets. Both use the same new handoff prompt and Jev first. Every scored request reached Sol; actual API tiers confirmed Fast or Standard as requested. Sequential execution, seeded case order and rotating tier order.

| Tier | Median | p95 | Wrong proposals | Action lifecycle passes | Guard cases without changes | Estimated scored cost |
| --- | --- | --- | --- | --- | --- | --- |
| Fast | 1.80 s | 2.93 s | 0 | 12/12 | 6/6 | $0.0960 |
| Standard | 2.34 s | 3.81 s | 0 | 12/12 | 6/6 | $0.0527 |

Fast reduced median latency by 0.54 seconds (23%) and p95 by 0.87 seconds (23%) in this sample; measured cost was 1.82×. This small sample suggests a speed benefit, not a guarantee for every request. Cost includes Jev and varies with token usage and caching. No semantic judge is used for guard prose. Timing excludes phone rendering and acknowledgement. Task-removal undo now passes all six trials, including exact restored order.

Fast won 13/18 matched case/repetition pairs. Total estimated cost including warmups: $0.173128.

[Full report](../runs/sol6-low-tiers-2026-09-23-v1/REPORT.md) · [Persistent record](../history/sol6-low-tiers-2026-09-23-v1.json) · [Config](../configs/sol6-low-tier-comparison.json)
