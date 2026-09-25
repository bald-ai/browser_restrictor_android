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

## Verification preferences

- For new features and behavior changes, add or update meaningful tests using the project's existing test framework, fixtures, helpers, and conventions. Inspect nearby tests first. Bug fixes should include a regression test where practical. Do not weaken assertions or disable tests merely to make a run pass.
- After the final code change, run the full ordinary automated test suite before handoff. Project commands: `npm test`, `npm run benchmark:test` (local benchmark-tooling unit tests, not model benchmark runs), and `npm run test:android` for the Android app. Fix failures caused by the change and rerun after fixes. Clearly report unrelated failures, unavailable toolchains, blockers, and skipped checks; do not claim the suite passed when it did not. Existing lint, typecheck, and build requirements still apply.
- Intermediate focused test runs are optional when useful for implementation or debugging. There is no requirement to run tests after every edit.
- Coverage, code metrics (including complexity and CRAP), and mutation testing are manual-only. Run them only when the user explicitly requests the corresponding check. Never include them in routine coding checks, pre-handoff checks, or automatic CI, including for critical behavior changes. A combined quality command that collects metrics or runs mutations is also manual-only; use the ordinary test commands instead.
- Do not generate or retain verification report files, evidence bundles, saved test logs, or source snapshots. Print verification results to the terminal. Disable optional file reporters; if tooling requires temporary internal data, use temporary storage and clean up data created by that run after success or failure. This is not permission to delete unrelated existing files or user-requested deliverables.
- Documentation-only changes do not require app tests unless they affect build/test instructions or an existing project rule explicitly requires documentation consistency tests.
- Ordinary local tests do not authorize device interaction, deployment, paid service calls, or live model benchmarks. Keep those within the user's explicit scope and report excluded checks.

- Android JVM tests require Java 21 or newer for Robolectric SDK 36. The Android test command selects Homebrew’s JDK 21 unless `ANDROID_TEST_JAVA_HOME` is set and explicitly overrides Gradle’s global JDK setting. Set `ANDROID_TEST_JAVA_HOME` for another compatible JDK (`JAVA_HOME` is the fallback when Homebrew JDK 21 is unavailable); do not downgrade test SDKs to bypass a toolchain mismatch.

- When changing verification tooling, also run its ordinary regression tests: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_verification_session.py`. These tests simulate subprocess outcomes and do not run coverage, code metrics, or mutations.
