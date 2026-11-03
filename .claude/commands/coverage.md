---
description: Measure unit test coverage against the 90% gate, find the least-covered code, write tests for it, and show what improved
allowed-tools: Bash, PowerShell, Read, Edit, Write, Grep, Glob
argument-hint: "[<class or package>]"
---

Raise unit test coverage where it matters most, without changing app behavior. If I passed a class or package name, work only on that. When the task is done, commit its changes to the current branch automatically, as the last step describes. Do not push.

## 1. Measure

1. Run `./gradlew :app:jacocoTestReport -q` (use `gradlew.bat` from PowerShell). It runs `testDebugUnitTest` first and writes `app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`, which leaves out generated code (view binding, Glide's generated API, R, BuildConfig, Sentry).
2. If any unit test failed (`failures="[1-9]"` or `errors="[1-9]"` in `app/build/test-results/testDebugUnitTest/*.xml`), list the failures and stop: coverage from a red suite is not trustworthy, and fixing tests is a separate job.
3. `python .claude/scripts/coverage_summary.py app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml --top 30`. Note line, branch and instruction coverage, and the gap to the 90% line gate that `jacocoTestCoverageVerification` (and CI) enforces.

## 2. Pick the targets

From the top classes by missed lines, choose what to test, in this order:

1. Code that reads, writes or cleans files (`metadata/`, `media/`, `MetadataStripper`, `MediaProcessor`, `FormatConverter`, the HEIF, JPEG and MP4 container code), and anything that decides what is sent to Sentry (`sentry/`, especially `SentryPrivacyScrubber`). A bug there leaks private data.
2. Branches nobody covers in otherwise well-covered classes: error paths, `catch` blocks, SDK-version branches, empty and oversized inputs.
3. UI classes (fragments, adapters, dialogs) last, through Robolectric like the existing `*FragmentTest`s.

Open the HTML report (`app/build/reports/jacoco/jacocoTestReport/html/`) or the XML `<line mi=… ci=…>` entries for each chosen class to see exactly which lines and branches are missed. Skip code that cannot run on the JVM even under Robolectric (real codec work, hardware encoders) and say so.

Show me the target list, with the missed lines per class and what the new tests will exercise, before writing them.

## 3. Write the tests

- Put each test next to the existing ones for that class (`app/src/test/java/<same package>/<Class>Test.java`), and match their style: JUnit 4, Robolectric where Android types are involved, the same helpers (look for existing fixture builders before writing new ones), and descriptive `subject_condition_expectedResult` names.
- Assert behavior, not just that code ran: the bytes or metadata that come out, the value returned, the exception reported to `SentryManager`. A test that only calls a method to touch lines is not worth adding.
- Never change production code to make it easier to test unless the change is trivial and behavior-preserving (for example, widening a private helper to package-private with `@VisibleForTesting`). Point out anything bigger instead.
- If a test reveals a real bug, do not fix it here: keep the test, mark it `@Ignore("bug: <one line>")`, and list it in the report.

## 4. Verify

1. Re-run `./gradlew :app:jacocoTestReport -q` and the summary script. Every test must pass.
2. Run `./gradlew :app:jacocoTestCoverageVerification -q` and confirm the gate passes.
3. Run each new test class a few times (`--tests <class> --rerun`) to make sure none is flaky.

## 5. Report

- Line, branch and instruction coverage before and after, and the margin over the 90% gate.
- Per class: missed lines before and after, and the tests added (one line each, saying what each proves).
- Anything left uncovered on purpose and why, and any bug a test found.

Then commit to the current branch without asking (skip it if nothing changed or a check is still failing, and say so): stage only the test files (and any `@VisibleForTesting` change), and commit with a message like "Cover <what> with unit tests". Never push.
