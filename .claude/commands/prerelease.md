---
description: Before a release, run /test and /translate, check that new code reports to Sentry, regenerate the profiles, regenerate the screenshots if Clean, Scan or Convert look different, preview the release notes CI will write, and show one summary
allowed-tools: Bash, PowerShell, Read, Edit, Write, Grep, Glob
---

> These commands drive the Pixel_10_Pro emulator and Gradle with Windows paths, so they only work on my own machine. If you are running in a cloud session without the Android SDK and the emulator, say so and stop instead of trying.

Get the app ready for a release by running four checks in order, then previewing the release notes, on the **Pixel_10_Pro emulator and never on a physical device**, then give one combined summary. Each step follows another command's file: read that file and do what it says, with the changes listed here. Do not commit or push anything, and do not copy screenshots into `fastlane/`, unless I ask (or I invoked this with the word "commit", which allows commits but never a push).

If a step fails, record why and carry on with the next one, except that a missing `Pixel_10_Pro` AVD stops everything.

## 1. Tests

Follow `.claude/commands/test.md` in full: the unit tests, then every feature on the emulator. Keep its summary for the end rather than printing it now. Do not fix failures in this step.

## 2. Translations

Follow `.claude/commands/translate.md`: check every string in all 12 locales and translate anything missing or left in English. Translation fixes and the Sentry fixes in step 2c are the only changes this command makes to the source; leave them uncommitted unless commits are allowed. If strings changed, run only `./gradlew :app:testDebugUnitTest --tests com.doubleangels.redact.StringTranslationsTest -q` afterwards (the full suite already ran in step 1) and note the result.

## 2b. Dependencies, report only

Follow `.claude/commands/dependencies.md` through step 3 (collect, find the latest versions, decide) and **stop there: do not apply any upgrade** in a release run, since new versions would invalidate the test results above. Keep the table for the summary so I can decide what goes in the next release.

## 2c. Sentry coverage

Check that the code added or changed for this release reports to Sentry like the rest of the app, without sending anything private, and fix every gap.

1. Find the previous release the way CI does: `last-published-version` if `git rev-parse --verify --quiet refs/tags/last-published-version` succeeds, otherwise the newest `v*` tag. Run `git fetch --tags --force origin` first, since a local copy of that tag can be stale. The scope is `git diff <tag> -- app/src/main/java` (committed and uncommitted changes), read hunk by hunk, plus every file added since the tag.
2. For each new or changed piece of code, check:
   - **Failures are reported.** Every `catch` either calls `SentryManager.recordException(e)`, or has a clear reason not to (`SentryManager.isUserCancellation(e)` or `isIgnored(e)`, an expected case such as an unsupported format or a missing file that is logged with `SentryManager.log`, or a comment explaining why it is ignored). An empty or comment-free `catch (Exception ignored)` on a processing, saving or sharing path is a gap.
   - **Background work is covered.** Code run on a `new Thread`, an executor, `worker.execute`, a `Handler.post` or a callback catches and records its exceptions instead of losing them or crashing the process.
   - **New flows leave a trail.** A new screen, tab, button, dialog choice, share target or job logs a breadcrumb (`SentryManager.logEvent` or `SentryManager.log`) when it starts and for its outcome. A long job uses `SentryManager.beginOperation` or `startTransaction` like Clean and Convert do, and sets its failure tags (`setCustomKey`) before `recordException`. New fragments and activities are picked up by the lifecycle integrations in `SentryInitializer`, so they need no extra code.
   - **Everything goes through `SentryManager`.** No direct `Sentry.*`, `io.sentry` capture or breadcrumb calls outside `app/src/main/java/com/doubleangels/redact/sentry/`, because those calls would skip the consent check (`SentryManager.isEnabled()`) and the scrubbing.
   - **Nothing private is sent.** No string passed to Sentry (log messages, breadcrumbs, tags, metric attributes, exception messages from our own `throw`s) contains a URI, path, file name, coordinate, metadata value or text the user typed. Do not rely on `SentryPrivacyScrubber` alone: check each value against its patterns, and keep in mind that it currently misses `.heic` names, names with spaces or parentheses (such as `IMG_1 (1).jpg`), and `/sdcard/` and `/mnt/` paths.
3. Fix each gap with the smallest change, written like the surrounding code: add the missing `recordException`, breadcrumb, operation or failure tag; route a direct Sentry call through `SentryManager`; drop or reword a private value so it never reaches Sentry. When the scrubber misses something, fix the pattern in `SentryPrivacyScrubber` and add a case to its test (`SentryPrivacyScrubberTest`) for the value it missed. Do not change behaviour beyond reporting, and do not report user cancellations or expected cases such as an unsupported format, which would only add noise.
4. If anything changed, run the full `./gradlew :app:testDebugUnitTest -q` again (step 1's results no longer cover the final code) and check `app/build/test-results/testDebugUnitTest/*.xml` for `failures="[1-9]"` or `errors="[1-9]"`. A fix that breaks a test is rolled back and listed as a gap instead. Leave the fixes uncommitted unless commits are allowed.
5. Keep for the summary: each gap with its `file:line`, what was missing and how it was fixed (or why it was left), and one line per file of new code that was already covered, so I can see the scope was complete.

A gap that could not be fixed does not on its own make the release not ready, but anything that still sends private data to Sentry does.

## 3. Profiles

Follow `.claude/commands/generateprofile.md`, using the emulator from step 1. This runs after the translation step so the profile is generated from the final code.

## 4. Screenshots, only if the first three tabs look different

The store screenshots show the Clean, Scan and Convert tabs (light and dark). Regenerate them only when something that changes how those tabs look has changed since the screenshots were last updated. Settings does not appear in the screenshots, so changes that only affect it never count.

1. Find the last screenshot update, ignoring image-optimiser commits:
   `git log -1 --format=%H --perl-regexp --author='^(?!ImgBot)' -- fastlane/metadata/android/en-US/images/phoneScreenshots`
2. List what changed since then, including uncommitted changes (the translation step may have edited strings): `git diff --stat <that commit> -- app/src/main/res app/src/main/java scripts/screenshots.sh scripts/store_graphics.py scripts/tag_samples.py icons/sample`.
3. It **counts as a visual change** when any of these changed:
   - layouts for those tabs and the parts they share: `activity_main.xml`, `menu/bottom_navigation_menu.xml` (the tab bar is in every shot), `fragment_clean.xml`, `fragment_scan.xml`, `fragment_convert.xml`, every `item_*.xml`, `dialog_progress.xml`;
   - anything under `res/drawable*` or `res/mipmap*`, except a drawable used only by `fragment_settings.xml` or the Censor tab's layouts (check with grep); `ic_censor` counts, since the tab bar shows it;
   - styling resources in any `values*` folder: `colors.xml`, `themes.xml`, `styles.xml`, `dimens.xml`, `shapes.xml`, or a font;
   - English text shown on those tabs in the screenshot state (titles, button labels, hints, badges, format chips): check whether each changed `values/strings.xml` name is used in those layouts or in the classes below;
   - code that sets how those tabs look (visibility, colours, sizes, icons, text, which views appear for a selection): `MainActivity`, `CleanFragment`, `ScanFragment`, `ConvertFragment`, `MediaAdapter`, `ConvertFileAdapter`, `ScanMetadataAdapter`, `UIStateManager`. Read the diff and judge: a change only to background work, logging, or dialogs and states the screenshots never show does not count;
   - the screenshot scripts `scripts/screenshots.sh`, `scripts/store_graphics.py` or `scripts/tag_samples.py`, or the sample media in `icons/sample`.
4. It does **not** count when the only changes are to `fragment_settings.xml`, `SettingsFragment`, Settings-only strings, the Censor tab (`fragment_censor.xml`, `layout-land/fragment_censor.xml`, `censor_*.xml`, `CensorFragment`, `CensorCanvasView`, `CensorViewModel`, `censor_*` strings other than `navigation_censor`), translations into other locales, or non-UI code. The store screenshots never open Censor.
5. If there is a visual change, follow `.claude/commands/generatescreenshots.md` for `all` locales (store screenshots and feature graphics are per language). Also compare each new en-US store image with the matching one in `fastlane/metadata/android/en-US/images/phoneScreenshots/` (read both) and describe what looks different. If there is no visual change, skip the screenshots and say which commit you compared against and that nothing on those tabs changed.

## 5. Release notes, preview only

CI (the `release-notes` job in `deploy.yml`) writes the Play "What's new" text, the 12 translations and `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` when the release is created, from the commits since the last published release. Nothing is written by hand and nothing here is saved. Preview what CI will produce so I can catch a bad line before releasing.

1. Read `versionCode` and `versionName` from `app/build.gradle`. If `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` exists, CI will keep that hand-written text instead; show it and skip the rest of this step.
2. Draft the English text yourself from `git log $(git rev-parse --verify --quiet refs/tags/last-published-version >/dev/null && echo last-published-version || git tag -l 'v*' --sort=-v:refname | head -1)..HEAD --no-merges`, following the rules in the `release-notes` job's prompt in `.github/workflows/deploy.yml` (one short plain line per user-facing change, features first, no jargon, labels as the app names them, at most 500 characters). Show it with its character count. CI will word it independently with `anthropics/claude-code-action`, so treat this as a check on the commit history, not as the final text.
3. Say if the preview mentions something that should not be public, or misses a user-visible change. The fix is a clearer commit message or a hand-written `changelogs/<versionCode>.txt`, never an edit to `whatsnew/`.

## 6. Summary

Finish with one report:

1. **Verdict:** ready to release, or not, and why, in one or two sentences. Any unit test failure, failed feature, crash, ANR, translation error left unfixed, failed profile run, or private data sent to Sentry means not ready.
2. **Tests:** the unit test totals and the feature table from step 1.
3. **Translations:** what was missing or left in English, what was translated (per locale), and whether the check now passes.
4. **Profiles:** rule counts before and after, and whether the change is meaningful or noise.
5. **Screenshots:** regenerated or skipped, the reason (the files that count as a visual change, or the baseline commit), and the review of the new images.
6. **Release notes:** the previewed English text with its character count, and whether CI will generate it or keep a hand-written one.
7. **Dependencies:** how many are outdated, and the ones in the "needs care" group.
8. **Sentry coverage:** the release range checked, each gap with its `file:line` and how it was fixed, anything left unfixed and why, and the unit test result after the fixes.
9. **Left uncommitted:** every changed file (translations, Sentry fixes, profiles), plus the screenshots in `build/screenshots/` and `build/store/`.

Offer to commit the translations, Sentry fixes and profiles, to copy the store images into fastlane (as step 4 of `generatescreenshots.md` describes), and to fix any failures, but do none of these without being asked.
