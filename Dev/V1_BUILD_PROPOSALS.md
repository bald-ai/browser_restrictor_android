# V1 build proposals

2026-09-16. Recommendations for discussion, not accepted decisions or authorization to implement. Current accepted product scope: REBUILD_PLAN_V1.md. Current prototype architecture: ARCHITECTURE_V2.md.

## 1. Data ownership and synchronization

Use the backend as the authority for task definitions and editable settings. Android keeps a persisted copy and remains responsible for blocking, timers, spending, balances and activity history for this single-device v1. Upload a recent summary and fetchable records so the assistant can answer from real usage. This preserves the existing RestrictorRepository/DataStore foundation and avoids moving every runtime action online. Tradeoff: no cross-device balances or reinstall recovery, consistent with the launch scope.

All configuration edits, including manual settings, use the same validated revision system. Agent edits require a review card and explicit approval. Reject stale revisions; do not silently overwrite newer edits. Report server saving separately from Android applying the revision; claim the setting is active only after Android acknowledges it. Synchronize on connection, app resume and after changes. Do not hold an active restriction session's timing or reward calculation open to retrospective edits.

## 2. Integrate existing verifiers

Keep current recording/preprocessing and restriction/reward behavior where compatible. Introduce a shared task definition with stable ID, name, verifier type, metric, units per credit, minimum correction evidence and approved judging instructions. Migrate starter tasks and existing state without deleting balances or history.

Submit evidence with task ID, definition revision and a unique attempt ID. The server loads the trusted definition and returns measured units, correction eligibility and a receipt. Reuse video plumbing while replacing hardcoded exercise judging with the approved task instructions; GPS rules stay in ordinary code. Android applies each receipt once through the reward engine. Task edits apply to new attempts, not recordings already started. Explicitly test corrections, partial progress and earned-balance preservation.

## 3. Connectivity and identity

Recommend automatic per-install identity with a separate revocable credential, no sign-up screen, one device per installation, and no restoration promise for v1. Separate users' configuration and evidence on the server; replace the existing shared verifier secret for public use. Keep provider API credentials on the backend. Tradeoff: reinstalling starts a new identity and setup.

When disconnected, retain the last applied rules and do not grant access merely because the network failed. Voice, edits and verification show an explicit unavailable/retry state; continue existing local timers and resets. Preserve GPS recordings and upload on reconnect; do not introduce a broader offline product or silently promise video upload recovery. Recording closure after process death follows the accepted product plan.

Give every mutation and evidence submission a unique ID. Retry without duplicate saves or rewards and query status after uncertain responses. Propose assigning an attempt to the daily window in which recording ends; accept within-window partial progress only. Late validation can add history but cannot revive expired spendable rewards/progress. Persist period IDs/deadlines so reset-time edits and undo cannot resurrect an expired balance. Pin task rules at recording start. Clock changes, reset edits and recordings crossing a boundary need explicit tests.

Detailed API spending controls remain deferred. Resolve a minimal owner-controlled cap before opening paid API access to public users; no dollar amount is assumed here.

## 4. Build order and acceptance

1. Real wait-setting flow: voice/text request, review, button or spoken approval, save, Android acknowledgement, actual blocking-screen wait, undo. Confirm button must work during a live voice call; the current prototype disables it then.
2. Data migration and the complete agreed settings/task surfaces: common manual controls, naming, creation, edits, pause/removal and previews. Exercise a changed setting after app restart and reject stale review cards.
3. Real custom video task and GPS activity: record, judge, correct when eligible, earn partial/full credit, spend time, and reset. Repeated submission must grant rewards once; parameter changes must preserve prior earnings.
4. Release checks: identity isolation, connection loss/reconnect, interrupted saves/uploads, Android process death, reset boundaries, physical microphone/speaker interruptions and an end-to-end cost sample.

The first milestone proves integration; it is not completion of the full agreed launch scope. Existing model/transport comparisons do not need repeating before this work begins. Carry the 27 prototype tests forward and add tests for the real integration boundaries.

## Source inspection

- app/src/main/java/com/michalkrsik/internetrestrictor/data/RestrictorRepository.kt: persisted Android state and local runtime engine.
- app/src/main/java/com/michalkrsik/internetrestrictor/ui/challenge/ExerciseVerifierClient.kt: current video upload, shared credential and retries.
- server/src/: existing verification backend to assess for reuse.
- prototypes/agent-ux-lab/: proven conversation/function flow, simulated runtime and verifier integration.
