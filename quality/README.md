# Verification

Before handing off code changes, run the ordinary test suites listed in `AGENTS.md`, fix failures caused by the changes, and report unrelated failures or unavailable checks. Add tests for new behavior using existing project patterns. Intermediate focused tests are optional.

Coverage, complexity/CRAP metrics, and mutations are explicit-request-only. They are never automatic handoff checks. Existing CI uses ordinary tests/build checks; projects without CI do not acquire new scheduled jobs.

Manual commands run through `scripts/verification-session.py`. It gives the existing tools disposable intermediate directories, prints results, and removes the directories after success, failure, or handled interruption. Do not run competing builds, mutations, or edits during a manual check. Existing output at those paths makes the runner fail rather than overwrite another run. No generated report archive is retained.

```sh
python3 scripts/verification-session.py coverage
python3 scripts/verification-session.py metrics
python3 scripts/verification-session.py mutations
```

Ordinary suites: `npm test`, `npm run benchmark:test` (local tooling tests), and `npm run test:android`. The existing APK CI also builds/lints the app and uploads its APK; the APK is a deliverable, not a verification report. `coverage` measures backend/browser JavaScript; `metrics` runs the full Android and JavaScript measurement pipeline with the existing 90% line, 85% branch and CRAP <=8 gates. `mutations` accepts JavaScript mutation names. For Android use `python3 scripts/verification-session.py mutations:android GROUP` with a group defined in `quality/mutate-android.py`. Production model benchmark history is separate and preserved.

Mutation outcomes distinguish surviving changes from assertion detections and infrastructure errors. Low-level measurement scripts are implementation helpers; use the manual runner so fresh inputs and temporary cleanup are managed together.

## Measurement basis

The Android API 36 JVM fixtures require JDK 21. Set `QUALITY_JAVA_HOME` to its
home directory when Gradle's configured JDK is older. This does not change the
project's production bytecode target.

Android line coverage uses JaCoCo across all handwritten production files. The
branch gate uses exact Kotlin compiler probes for both outcomes of each
handwritten condition, before JVM and Compose lowering. JaCoCo's broader bytecode
branch percentage is retained and reported separately. Neither percentage is
line coverage presented as branch coverage. Literal conditions and generated
conditions without a source expression do not invent alternative source outcomes.
Every observed handwritten condition must belong to a production function; the
gate fails when ownership is unresolved.

Per-function CRAP prefers those exact source branch counters. Branchless bodies
use attributable JaCoCo lines/methods; otherwise-counterless bodies can use exact
invocation probes. Bodyless abstract/interface method declarations remain visible
in a separate non-executable inventory, with coverage, complexity and CRAP marked
not applicable (null). Their concrete implementations are measured independently.
The compiler PSI determines this classification; missing counters never imply a
non-executable declaration. Default interface bodies, empty bodies, constructors,
accessors and external/native functions remain subject to the executable-function
gate. Unmapped executable functions still fail; no scores are assumed and no
per-function exceptions are allowed. Regression fixtures cover
inline and shared-line lambdas, short-circuit/null decisions, loops, suspension,
constant loops, and preservation of results and exceptions.

Ordinary Android tests use `npm run test:android`, which explicitly sets Gradle’s JDK to `ANDROID_TEST_JAVA_HOME`, Homebrew JDK 21, or finally `JAVA_HOME`. This avoids a user-level Gradle setting silently selecting Java 17. Android Studio’s bundled Java 25 is too new for the pinned Gradle 8.14.5. Test binary output is cleaned after success or failure; HTML/XML reports remain disabled outside manual sessions.
