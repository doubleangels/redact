---
description: Read the current version, choose a major, minor or patch bump from the changes since the last release, and update versionCode, versionName and the changelog
allowed-tools: Bash, PowerShell, Read, Edit, Write, Grep, Glob
argument-hint: "[major|minor|patch] [commit]"
---

Bump the app version for the next release. If I passed `major`, `minor` or `patch`, use that level; otherwise decide from the changes. Do not push. Only commit if I ask (or if I invoked this with the word "commit").

## 1. Read the current state

1. Read `versionCode` and `versionName` from `app/build.gradle` (the `defaultConfig` block).
2. Find the last release tag: `git tag -l 'v*' --sort=-v:refname | head -1`. CI creates `v<versionName>` when a release reaches `main`.
3. If `versionName` already differs from that tag's version (`git show <tag>:app/build.gradle`), this release has already been bumped. Say so, show both versions, and stop unless I asked for a different level; in that case re-bump from the tag's version and keep the existing new `versionCode`.

## 2. Decide the bump

List the commits since the tag with `git log <tag>..HEAD --no-merges --format='%h %s'`, and read the diff (`git diff --stat <tag>..HEAD`, plus the code for anything unclear) instead of trusting the subjects alone. Ignore merges, dependabot bumps, profile and screenshot regeneration, docs-only and CI-only changes when classifying.

- **major**: a change that removes or reworks something users rely on, such as removing a feature, changing what Clean strips by default, raising `minSdk`, or a redesign of the main flow. Never pick major on your own: propose it, and ask me.
- **minor**: at least one new user-facing feature or setting, such as a new screen, tile, button, option or file format.
- **patch**: only fixes, performance work, wording, translations or dependency updates.

New version: increment the chosen part of `versionName` and reset the parts after it (`1.5.3` → minor → `1.6.0`). Always increment `versionCode` by exactly 1; CI's `check-version` job fails a release whose `versionCode` is not higher than the last published one.

Show me the classified commit list (features / fixes / ignored), the level you chose and why, and the old and new versions before editing.

## 3. Apply

1. Update `versionCode` and `versionName` in `app/build.gradle`. Nothing else stores the version; `grep -rn "<old versionName>"` outside `build/` to make sure.
2. Write `fastlane/metadata/android/en-US/changelogs/<new versionCode>.txt`:
   - **patch**: `Bug fixes and performance improvements.`, the same as earlier patch releases.
   - **minor / major**: one short line per user-facing change, in plain words with no commit hashes, like `changelogs/19.txt`, ending with `Bug fixes and performance improvements.` if the release also has fixes. Stay under 500 characters (the store limit).
3. Leave `whatsnew/whatsnew-en-US` alone; it is the generic Play Store note and does not change per release.

## 4. Verify

1. Run `./gradlew :app:testDebugUnitTest -q` (use `gradlew.bat` from PowerShell) and confirm no test failed. Check `app/build/test-results/testDebugUnitTest/*.xml` for `failures="[1-9]"` or `errors="[1-9]"`.
2. Run `python .claude/scripts/check_translations.py --since <tag>` and report its total. If it finds problems, tell me to run `/translate` before releasing; do not fix them here.

## 5. Report

End with: the old and new `versionName`/`versionCode`, the level and the reason, the changelog text, test and translation results, and the reminder that the `v<new versionName>` tag and GitHub release are created by CI when this reaches `main`.

Only if I asked to commit: stage `app/build.gradle` and the new changelog file only, and commit with the message `Bump version to <new versionName>`. Never push, and never create tags.
