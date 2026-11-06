---
description: Check that the code added or changed since the last release reports to Sentry like the rest of the app without sending anything private, fix every gap, and show what was checked
allowed-tools: Bash, PowerShell, Read, Edit, Write, Grep, Glob
argument-hint: "[<git ref to compare against>]"
---

Check that the code added or changed for this release reports to Sentry like the rest of the app, without sending anything private, and fix every gap. If I passed a git ref (a tag, branch or commit), compare against it instead of the previous release. When the task is done, commit its changes to the current branch automatically, as the last step describes. Do not push.

This command checks the code. `/sentry` fixes issues already reported to Sentry.

## 1. Find the scope

1. Run `git fetch --tags --force origin` first, since a local copy of a release tag can be stale.
2. Find the previous release the way CI does: `last-published-version` if `git rev-parse --verify --quiet refs/tags/last-published-version` succeeds, otherwise the newest `v*` tag (`git tag -l 'v*' --sort=-v:refname | head -1`). Use the ref I passed instead, if any.
3. The scope is `git diff <ref> -- app/src/main/java` (committed and uncommitted changes), read hunk by hunk, plus every file added since `<ref>` (`git diff --name-status <ref> -- app/src/main/java`, status `A`).

## 2. Check each new or changed piece of code

- **Failures are reported.** Every `catch` either calls `SentryManager.recordException(e)`, or has a clear reason not to (`SentryManager.isUserCancellation(e)` or `isIgnored(e)`, an expected case such as an unsupported format or a missing file that is logged with `SentryManager.log`, or a comment explaining why it is ignored). An empty or comment-free `catch (Exception ignored)` on a processing, saving or sharing path is a gap.
- **Background work is covered.** Code run on a `new Thread`, an executor, `worker.execute`, a `Handler.post` or a callback catches and records its exceptions instead of losing them or crashing the process.
- **New flows leave a trail.** A new screen, tab, button, dialog choice, share target or job logs a breadcrumb (`SentryManager.logEvent` or `SentryManager.log`) when it starts and for its outcome. A long job uses `SentryManager.beginOperation` or `startTransaction` like Clean and Convert do, and sets its failure tags (`setCustomKey`) before `recordException`. New fragments and activities are picked up by the lifecycle integrations in `SentryInitializer`, so they need no extra code.
- **Everything goes through `SentryManager`.** No direct `Sentry.*`, `io.sentry` capture or breadcrumb calls outside `app/src/main/java/com/doubleangels/redact/sentry/`, because those calls would skip the consent check (`SentryManager.isEnabled()`) and the scrubbing.
- **Nothing private is sent.** No string passed to Sentry (log messages, breadcrumbs, tags, metric attributes, exception messages from our own `throw`s) contains a URI, path, file name, coordinate, metadata value or text the user typed. Do not rely on `SentryPrivacyScrubber` alone: check each value against its patterns, and keep in mind that it currently misses `.heic` names, names with spaces or parentheses (such as `IMG_1 (1).jpg`), and `/sdcard/` and `/mnt/` paths.

## 3. Fix the gaps

Fix each gap with the smallest change, written like the surrounding code: add the missing `recordException`, breadcrumb, operation or failure tag; route a direct Sentry call through `SentryManager`; drop or reword a private value so it never reaches Sentry. When the scrubber misses something, fix the pattern in `SentryPrivacyScrubber` and add a case to its test (`SentryPrivacyScrubberTest`) for the value it missed. Do not change behaviour beyond reporting, and do not report user cancellations or expected cases such as an unsupported format, which would only add noise.

## 4. Verify

If anything changed, run `./gradlew :app:testDebugUnitTest -q` (use `gradlew.bat` from PowerShell) and check `app/build/test-results/testDebugUnitTest/*.xml` for `failures="[1-9]"` or `errors="[1-9]"`. If a test fails because of a Sentry fix, roll that fix back and list it as a gap.

## 5. Report

- The range checked (`<ref>..HEAD` plus uncommitted changes).
- Each gap with its `file:line`, what was missing and how it was fixed, or why it was left.
- One line per file of new or changed code that was already covered, so I can see the scope was complete.
- The test result.

A gap that could not be fixed is worth knowing about, but anything that still sends private data to Sentry means the release is not ready; say so plainly.

Then commit to the current branch without asking (skip it if nothing changed or a test is still failing, and say so): stage just the files this task changed, and commit with a message that lists the fixes. Never push.
