---
description: Preview the Play "What's new" text CI will write for the next release from the commit history, and flag anything that should not be public or is missing
allowed-tools: Bash, PowerShell, Read, Grep, Glob
---

CI (the `release-notes` job in `.github/workflows/deploy.yml`) writes the Play "What's new" text, its 12 translations and `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` when the release is created, from the commits since the last published release. Nothing is written by hand and nothing here is saved. Preview what CI will produce so I can catch a bad line before releasing. Do not edit, commit or push anything.

## 1. Check for hand-written notes

Read `versionCode` and `versionName` from `app/build.gradle`. If `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` exists, CI will keep that hand-written text instead: show it with its character count and stop.

## 2. Draft the preview

1. Find the previous release the way CI does: `last-published-version` if `git rev-parse --verify --quiet refs/tags/last-published-version` succeeds, otherwise the newest `v*` tag. Run `git fetch --tags --force origin` first, since a local copy of that tag can be stale.
2. Read `git log <that ref>..HEAD --no-merges`, and the diff for any commit whose subject does not make its user-visible effect clear.
3. Draft the English text yourself, following the rules in the `release-notes` job's prompt in `deploy.yml` (one short plain line per user-facing change, features first, no jargon, labels as the app names them, at most 500 characters). CI will word it independently with `anthropics/claude-code-action`, so treat this as a check on the commit history, not as the final text.

## 3. Report

1. The previewed English text and its character count, the range it covers, and the version it is for.
2. Anything in the commit history that should not be public (internal names, private data, unreleased plans) and would reach the notes.
3. Any user-visible change the preview misses because its commit message does not say what changed.

For either problem, the fix is a clearer commit message or a hand-written `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, never an edit to `whatsnew/`. Offer to write the changelog file, but do not write it without being asked.
