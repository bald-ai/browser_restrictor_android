# V1 settings agent speed experiment — 2026-09-17

Local lab: `prototypes/settings-speed-lab/`. Open http://localhost:4330 while its server is running. Instructions: [lab README](../prototypes/settings-speed-lab/README.md). Full evidence: [report](../prototypes/settings-speed-lab/results/REPORT.md), [text trials](../prototypes/settings-speed-lab/results/benchmark.json), [voice trials](../prototypes/settings-speed-lab/results/voice-benchmark.json).

Compared a hash-frozen copy of the current v1 agent, prompts, store and browser UI against a variant using typed edit operations instead of regenerating the full configuration. Both used real GPT-6 Astra Low Fast; same model settings, starting configuration, frontend polling and approval flow. Returned service tier was Fast in all 44 calls in the completed text cohort (42 measured turns and two warm-ups).

## Results

- Text request to review, median across successful edit scenarios: **4.81 s → 2.14 s**, **55% less waiting**.
- Four fresh-chat flips: **4.72 s → 2.01 s** median.
- Four same-chat flips: **4.91 s → 1.92 s** median.
- Three grouped requests: **4.89 s → 2.40 s** median.
- All eleven settings, one sample: **5.09 s → 3.38 s**.
- **21/21 expected outcomes passed per version.** Full-config assertions detect unrelated changes. Tested review before save, real Apply/Undo/Cancel buttons, durable read-back/reload, all settings, selected-card amendment preserving other pending edits, task clarification/creation, rename/pause/removal, invalid values, ambiguous references, and unsupported balance edits.
- **15/15 deterministic patch tests; 58/58 frozen backend regressions passed.** Patch tests include atomic rejection, stale pending reviews and cancellation preventing late proposals.
- Recorded-speech WebRTC checks: **4/4 passed**, Apply and Undo during a live call. End of speech to review: baseline **9.93 / 6.62 s**; focused edits **4.14 / 4.18 s**. Only two samples per variant; no general voice latency guarantee.
- Test Chrome audio was muted without changing system volume. Muted media reported zero audio energy, so **first audible response remains unmeasured**. Initial energy-telemetry timeouts are retained separately in the lab evidence.
- Estimated completed text cohort cost **$1.57**; whole experiment including interrupted harness run, voice checks and instrumentation retries **$2.77**. Provider accounting estimates, not invoices.

## Original experiment interpretation

Adopt edit-only proposals in the actual v1 backend, retaining server validation, visual review, explicit approval, pending-card preservation, persisted verification and undo. Carry over the atomic revision/pending-review guard. Production integration and phone checks are recorded below.

The original experiment changed only the new local lab. The baseline uses current project source; manifest hashes pin it. Local browser and hosting timings do not include Android acknowledgement, production network overhead or physical headset playback. The finite suite supports the design but does not prove a universal reliability percentage.

The first text run stopped when automation clicked carousel navigation before scrolling had settled. Successful calls from that interrupted cohort were retained and excluded from the final comparison. The fixed harness waits for confirmed selection. No failed model outcome was discarded to improve the reported result.


## Billing audit — 2026-09-17

The $2.77 figure above is a calculation from published rates and returned usage, **not a verified charge**. After the experiment, the user's OpenAI dashboard in Helium showed **$0.68 total over the last seven days**, with All projects and All API keys selected. The user reported $0.54 yesterday and less than $0.20 today; the chart was consistent with this. Refresh did not change the total. Astra usage was present in the model breakdown. The experiment and v1 credentials use the same API key (compared privately).

Reviewed Astra rates and Fast multiplier against [official model documentation](https://developers.openai.com/api/docs/models/gpt-6-astra). No obvious arithmetic error explains the discrepancy. Exact reconciliation is unresolved; discounts, credits and delayed billing are **not established explanations**. Preserve raw usage and estimates rather than substituting the account total for individual request costs. Future Astra/Live ledger entries explicitly identify published-rate estimates.

## Production integration — 2026-09-17

Implemented typed edit-only proposals, atomic stale-review guards, authenticated streamed UI updates with reconnection and polling fallback, and a concise voice prompt covering acknowledgement, interruption and prompt delegation. Transcript waits remain **350 ms for delegation and 900 ms for cancellation**. Server validation, grouped review, explicit approval, persisted readback and undo remain enforced.

Validation artifacts: `work/v1-build/optimisation/`. Backend suite: **87/87 passed**. Actual local browser checks passed grouped Astra edits, selected-card amendment preserving other pending edits, Apply/Undo, and full configuration comparisons. Three background updates appeared in **27–28 ms** locally; reconnect check **38 ms**. Blocking event streaming exercised the **5-second fallback**. These are update delivery measurements, not total model latency.

Two muted real WebRTC smoke checks passed: spoken setting request, visual review, button Apply and Undo while the call stayed open. First assistant transcript arrived **1.14 / 1.28 s after speech**, before review; reviews took **11.30 / 3.85 s**. This demonstrates early acknowledgement, not guaranteed response speed or measured physical playback. Local grouped text request took 6.28 s and a subsequent selected-card amendment 2.63 s. Retain this variability alongside the earlier controlled benchmark.

Hosted browser checks passed the same group/amend/Apply/Undo scenarios and streamed updates, with **219–439 ms** from external mutation request to visible UI, including network travel and saving. Poll fallback took **4.81 s**. Hosted grouped edit took 4.86 s; selected-card amendment 2.87 s. These are smoke measurements, not a new controlled comparison.

Phone checks uncovered an installed-binary mismatch with the current daily-reset configuration format. Built and installed current v1 sources without clearing app data. Also corrected an obsolete product-prompt sentence and a stale regression assertion referring to the removed separate browsing reset. The final backend suite passes **92/92**, including the additional daily-budget tests.

First full phone run passed the actual Astra clarification, grouped four-card proposal, button approval, durable Android application, and selected-card amendment. It then stopped at the camera permission dialog before recorder assertions; the failure screenshot confirms the correct task and 10-second rate behind that dialog. Original configuration was restored. Granted the test permissions and reran the complete acceptance flow: **passed in 67.6 seconds**, including native camera and GPS task routing, pause/unpause/removal, configuration readback and Android acknowledgement. The test restored the original configuration. Final result: `work/v1-build/optimisation/phone-result.json`.


Final deployment: separate Friction v1 Railway service, deployment `02ffb55d-9ade-4266-9595-6f26a090e52b`, status SUCCESS. Hosted assets match local hashes. Daily app backend was not deployed. Current v1 Android build is installed. Transcript timing defaults verified unchanged. Test browser audio stayed muted; system/headset volume was not altered. Human listening quality and interruption comfort remain subjective checks for the user.
