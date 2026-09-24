# AGENTS.md

## Test phone unlock

The Nothing Phone A001 (Galaga) has lock-screen PIN/password **`123456`**,
confirmed by the user. Use it when authorized phone testing or control requires
unlocking the device. Confirm the PIN-entry screen before entering it.

## In-app agent capability reference

Read [AGENT_CAPABILITIES.md](AGENT_CAPABILITIES.md) first for questions about what
the Friction in-app agent can read or control, settings, limits, reset behavior,
review/approval, and task customization. Use it to answer known capability
questions directly; search implementation only when the document does not cover
the question, is ambiguous, or appears inconsistent with current code.

When changing any of those capabilities, update `AGENT_CAPABILITIES.md` in the
same change, including defaults, bounds, timing, callable functions, examples
and unsupported controls. Do this automatically as part of the coding work,
without waiting for a separate documentation request. Run the capability
reference test (`npm --prefix backend-v1 test`) with the relevant implementation
checks. A change to agent capabilities is not complete while this reference is
out of date.


## Runtime help for the in-app agent

[backend-v1/docs/app-guide.md](backend-v1/docs/app-guide.md) is the product guide
shipped to the Friction assistant through `read_details`. Maintain it in the same
change whenever app behavior, settings, limits, troubleshooting, verification,
or available data changes. Update `AGENT_CAPABILITIES.md` too when capabilities
change. The topic index and function enum are derived from the guide, so use its
`## topic_id: Title` section format and preserve existing IDs when practical.
Use actual app context for user values; guide defaults describe factory settings.
Run `npm --prefix backend-v1 test`; the app-guide tests check topic availability,
settings/default coverage, and the documentation lookup-to-answer flow.


## Fast app overview for voice and text

[backend-v1/docs/app-overview.md](backend-v1/docs/app-overview.md) is preloaded in
both voice and configuration-agent prompts. It lets the assistant answer common
app questions immediately without fetching documentation. Keep it concise (under
400 words) and update it in the same change as any core app behavior change,
alongside the detailed app guide and capability reference. Avoid duplicating
user-specific values: prompts receive current app context. Preserve the routing
rule: answer covered questions directly; retrieve details only when needed;
always delegate changes, approvals and history requests to the backend.
The app-docs tests verify that both prompts contain this maintained overview.


## Benchmark history

For questions about previous model benchmarks or comparisons, read
[benchmarks/friction/RESULTS.md](benchmarks/friction/RESULTS.md) first, then the
linked history record and full evidence as needed. Refer to runs by their saved
run ID. The runner automatically retains immutable summaries in
`benchmarks/friction/history/` and updates the index. Preserve existing results;
never treat smoke tests or partial runs as a reliable model ranking.

For model benchmarks, only non-reasoning (`none`) and `low` reasoning are relevant
to the user. Do not run higher efforts unless the user explicitly changes this
preference. The Jev-first Fast comparison for these efforts is saved in
`benchmarks/friction/configs/jev-up-to-low-fast.json`.
