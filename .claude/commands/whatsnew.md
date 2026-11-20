---
description: Write the in-app What's New notes for the next release from the commits since the last one, get my approval, translate them into all 12 locales, and commit
allowed-tools: Bash, PowerShell, Read, Edit, Write, Grep, Glob
---

Write the notes the What's New screen shows after an update. They ship inside the app in `app/src/main/res/values/whats_new.xml` and `values-<locale>/whats_new.xml`, are for exactly one `versionCode`, and are separate from the Play "What's new" text, which CI keeps writing from the commits when the release is created (see `releasenotes.md`). Run this after `/bump`, so the version is final. When the task is done, commit its changes to the current branch automatically, as the last step describes. Do not push.

## 1. Read the current state

1. Read `versionCode` and `versionName` from `app/build.gradle`.
2. Find the last published release the way CI does: `last-published-version` if `git rev-parse --verify --quiet refs/tags/last-published-version` succeeds, otherwise the newest `v*` tag (run `git fetch --tags --force origin` first). If `versionCode` is not higher than that release's, tell me to run `/bump` first and stop.
3. Read `git log <that ref>..HEAD --no-merges`, and the diff for any commit whose subject does not make its user-visible effect clear.
4. Read the current `values/whats_new.xml`. If `whats_new_version_code` already equals `versionCode`, the notes may have been written already: show them and ask whether to rewrite them.

## 2. Draft the English

Keep only changes a user would notice: new features and settings, visible improvements, and fixes to something people saw. Leave out dependency updates, refactors, tests, CI, profiles, screenshots, translations of existing text and Sentry changes.

Follow the writing guide in `docs/roadmap/whats-new.md`:

- 1 to 8 items. Each one is a `kind` (`new`, `improved` or `fixed`), an `icon` (`clean`, `scan`, `convert`, `censor`, `settings`, `stats` or `fixes`), a title of at most 50 characters and a body of at most 300.
- Titles say what the person can now do ("Keep the date a photo was taken"), never the feature's internal name.
- Bodies are 1 to 3 short sentences in second person. They answer what changed, why it matters and where to find it, with the app's exact labels from `values/strings.xml` and the path when it helps ("Settings › Metadata Cleaning › Keep Date Taken").
- No jargon: no EXIF, XMP, codec, container or MIME unless an app label uses the word. No version numbers, commit hashes, class names or internal names.
- Fixes describe what the person saw. Group many small fixes into one "Smaller fixes" item naming the two or three most noticeable.
- Order: New, then Improved, then Fixed, the most useful item first in each group.
- `whats_new_announce` is `true` for a minor or major bump (the screen opens by itself once) and `false` for a patch (it waits in Settings › About). Take the level from the bump: compare `versionName` with the last release's.

Show me the draft as a table (kind, icon, title, body, with each length) and the announce value, and wait for my approval or changes before going on.

## 3. Write and translate

1. Write `values/whats_new.xml`: set `whats_new_version_code` to `versionCode`, `whats_new_announce`, and the four arrays (`whats_new_kinds` and `whats_new_icons` keep `translatable="false"`). Escape apostrophes as `\'`.
2. Write `values-<locale>/whats_new.xml` for `ar de es fr hi it ja ko pt ru zh-rCN zh-rTW` with only `whats_new_titles` and `whats_new_bodies`, in the same order. Use each locale's own labels from its `strings.xml` for every app label and path (so "Settings › Metadata Cleaning" becomes that locale's names for both), match the tone of that locale's `strings.xml`, avoid gendered first-person forms, and stay within the same length limits, shortening the wording rather than dropping a point.

## 4. Verify

Run `./gradlew :app:testDebugUnitTest --tests "com.doubleangels.redact.whatsnew.WhatsNewContentTest" --tests "com.doubleangels.redact.StringTranslationsTest" -q` (use `gradlew.bat` from PowerShell). Fix anything it reports and run it again.

## 5. Report and commit

Show the final English table, the announce value, the version it is for, and per locale any item that had to be shortened.

Then commit to the current branch without asking (skip it if nothing changed or the tests still fail, and say so): stage the 13 `whats_new.xml` files only, and commit with the message `Write the What's New notes for <versionName>`. Never push.
