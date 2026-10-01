# Voice and function-calling architecture — v2

Updated 2026-09-16 against the working `prototypes/agent-ux-lab` implementation.

## What the diagram means

The working prototype runs in a browser with a local backend. Its model calls and saved configuration are real; Android blocking, activity verification, balances and statistics are simulated or absent from this prototype.

- Browser audio connects directly to GPT-Live-1 through WebRTC. Ripple is the configured voice.
- Our backend creates the Live session and attaches a sideband WebSocket for conversation events, delegation and results.
- The conversation controller calls GPT-6 Astra using the Responses API, low reasoning and fast service tier. Astra requests supported functions; our backend executes them.
- Functions read product documentation and current setup, propose settings/tasks, confirm approved proposals, cancel/undo and verify saved changes.
- Changes require a review card and subsequent approval. Verification reads persisted configuration back; it does not prove Android applied the change or that an activity verifier works.
- Explicit cancellation discards pending proposals, prevents pending writes or restores values after an in-progress write. Undo preserves unrelated newer edits and rejects conflicting edits. A cancelled model request can still finish and incur token cost, but cannot apply its late result.

## Proposed Android integration

Android will need a conversation UI, local configuration, restriction/runtime state and camera/GPS recording connected to backend configuration and activity-verification services. Device acknowledgement, synchronization and final balance ownership remain technical design decisions. Video judging is a separate service boundary from voice and configuration function calling; its provider is not selected by this diagram.

## Verification sources

- `prototypes/agent-ux-lab/server.mjs`: session creation, Ripple, WebRTC sideband, Responses requests and cancellation routing.
- `prototypes/agent-ux-lab/functions.mjs`: eight supported functions, review/approval, serialized mutations, receipts and read-back.
- `prototypes/agent-ux-lab/results/cancellation/REPORT.md`: 27 automated tests and paid text/voice checks.
- `Dev/REBUILD_PLAN_V1.md`: intended product scope and agent boundaries.

## Corrections to the earlier image

1. Add the direct client-to-Live audio connection.
2. Identify the selected models and transport.
3. Replace generic update functions with the proposal, approval and verified-save flow.
4. Include cancellation and undo.
5. Show product-document retrieval.
6. Distinguish the working prototype from proposed Android integration and avoid presenting unsettled balance ownership as a decision.
