# Cleanup pass

Instructions for a manual cleanup pass: tests and coverage first, then CRAP refactoring. Only run this when the user asks for it.

## This project

- Ordinary tests: `npm test`, `npm run benchmark:test`, and `npm run test:android`
- Metrics (coverage and CRAP gates): `python3 scripts/verification-session.py metrics` (Android, server and browser JavaScript)
- Coverage only: `python3 scripts/verification-session.py coverage` (server and browser JavaScript)
- Mutation tests: `python3 scripts/verification-session.py mutations <name>` for JavaScript, and `python3 scripts/verification-session.py mutations:android <GROUP>` for Android. Android groups are defined in `quality/mutate-android.py`.
- Critical rules, which should get close to 100% coverage and mutation tests: browsing budgets, blocking and enforcement, waits, unlock, relock and expiry, saved state recovery, GPS and video rewards, and consistency between the Android app and the backend.

## When this applies

Run a cleanup pass only when the user explicitly asks for one, for example "clean up the unlock flow" or "run a coverage and CRAP pass on X". Ordinary feature work skips everything in this file and follows the normal verification preferences in `AGENTS.md`.

Features are built quickly first. A cleanup pass hardens the parts the user has decided to keep.

## Scope

- Work on the scope the user names: a feature, a folder, or a list of files. If no scope is named, ask for one.
- "The whole project" means feature by feature: finish one feature completely before starting the next.
- Leave code outside the scope alone, even when it scores badly. Mention serious problems you noticed in the report.
- Automated tests only. Do not launch the app, drive devices or emulators, or run UI journeys. List what still needs manual checking.

## Targets

- Line coverage of at least 90% and branch coverage of at least 85% for the code in scope. Aim close to 100% for the critical rules listed under "This project".
- CRAP of 10 or less per function. CRAP = complexity² × (1 − coverage)³ + complexity. With full coverage it equals complexity, so a fully tested function passes at complexity 10 or less. At 80% coverage the most it can have is 9, and at 50% about 5.
- The metrics command measures whole components. Use the per-function results for the files in scope; if the printed summary shows only totals, read the per-function data the metrics script builds.

## Order of work

1. **Baseline.** Run the ordinary tests. If they fail before you change anything, report the failures and ask before continuing. Then run the metrics and note coverage and every function over 10 in scope.
2. **Tests first.** Write tests that pin down how the code in scope behaves now, before refactoring anything. Then rerun the metrics: missing coverage inflates CRAP, so tests alone often fix part of the list.
3. **Refactor what is still over 10**, using the techniques below in order of preference.
4. **Verify.** Run the ordinary tests. Run mutation tests on the critical rules you changed or newly tested, and strengthen tests where a surviving mutant shows a real missed bug. Rerun the metrics. Finish with one more ordinary test run after the mutation runs.
5. **Report**, as described at the end.

## Test rules

- Test what a user or caller can observe: return values, saved state, messages, decisions.
- Work out expected values from the requirement or by hand. Never copy them from the code's current output without checking them.
- Mocks and fakes must not supply the value being asserted.
- Do not write tests that only raise coverage, such as calling a function without asserting anything meaningful.
- Do not add test-only paths to production code, weaken or delete existing assertions, or disable tests.
- If a test shows behavior that looks wrong, keep the test describing current behavior, mark it clearly, and report it. A cleanup pass does not change behavior unless the user agrees.

## How to lower complexity

Prefer these, in this order. Each one removes decisions instead of moving them somewhere else.

1. **Delete conditions that cannot change the result**, such as a check already implied by an earlier check or by the current state. Prove it by tracing the code or with a test, and list each deletion in the report.
2. **Replace branch chains with data:** a lookup table, a map of handlers, a configuration object, or a validation schema.
3. **Check input once at the edge.** Parse and normalize data where it enters, so inner code does not repeat null and range checks.
4. **Use types to rule out impossible states:** sealed classes, enums with associated values, or tagged unions.
5. **Merge duplicated logic** into one shared function with several callers.
6. **Extract a function** only when it passes the extraction test below.

## Extraction test

A new function must pass all three:

- You can name it with words from the product or the domain, and the name tells the reader something the body does not make obvious.
- It can be tested on its own.
- It owns its inputs: it takes the data it works on and returns a result the caller uses directly.

Do not:

- Pass a boolean or mode flag the caller already knew. Keep the branch in the caller or write two clear call sites.
- Return a bundle that the caller immediately unpacks into the next helper, or pass a closure down a chain of helpers.
- Build a chain where each helper has one caller and only hands work to the next one.
- Split a flat `switch`, `when`, `case`, or `cond` that answers one question, such as mapping a key to a command or an error code to a message. It may stay above 10; list it as an exception.
- Split a file that has one job just to lower a number.
- Lose comments that explain order or intent. Move them with the code.

After refactoring, look again at every new function with a single caller. If inlining it makes the parent easier to read, inline it.

## Exceptions

If a function cannot reach 10 without breaking the rules above, leave it as it is and list it in the report with its score and a one-sentence reason. The metrics gate will still show it as failing; the user decides what to do with it. Never change gates, thresholds, exclusions, or measurement scripts to make a result pass.

## Behavior

A cleanup pass keeps behavior the same and adds no features. If you find a bug, report it with a test that shows it, and fix it only if the user agrees.

## Report

Give the report in your final answer. Do not save report files.

- Scope covered.
- Before and after for the scope: line and branch coverage, number of functions over 10, highest CRAP.
- Exceptions left above 10, each with its score and reason.
- Conditions deleted as unreachable or redundant, and how you proved it.
- Suspected bugs found, with the tests that show them.
- Test results: pass, fail and skip counts. Mutation results: detected, survived, errors.
- What remains unverified and needs manual checking.
