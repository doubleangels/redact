---
description: Before a release, run /test and /translate, regenerate the profiles, regenerate the screenshots if Clean, Scan or Convert look different, write the changelog and Play Store release notes, and show one summary
allowed-tools: Bash, PowerShell, Read, Edit, Write, Grep, Glob
---

> These commands drive the Pixel_10_Pro emulator and Gradle with Windows paths, so they only work on my own machine. If you are running in a cloud session without the Android SDK and the emulator, say so and stop instead of trying.

Get the app ready for a release by running four checks in order, then writing the release notes, on the **Pixel_10_Pro emulator and never on a physical device**, then give one combined summary. Each step follows another command's file: read that file and do what it says, with the changes listed here. Do not commit or push anything, and do not copy screenshots into `fastlane/`, unless I ask (or I invoked this with the word "commit", which allows commits but never a push).

If a step fails, record why and carry on with the next one, except that a missing `Pixel_10_Pro` AVD stops everything.

## 1. Tests

Follow `.claude/commands/test.md` in full: the unit tests, then every feature on the emulator. Keep its summary for the end rather than printing it now. Do not fix failures in this step.

## 2. Translations

Follow `.claude/commands/translate.md`: check every string in all 12 locales and translate anything missing or left in English. Translation fixes are the one kind of change this command makes to the source; leave them uncommitted unless commits are allowed. If strings changed, run only `./gradlew :app:testDebugUnitTest --tests com.doubleangels.redact.StringTranslationsTest -q` afterwards (the full suite already ran in step 1) and note the result.

## 2b. Dependencies, report only

Follow `.claude/commands/dependencies.md` through step 3 (collect, find the latest versions, decide) and **stop there: do not apply any upgrade** in a release run, since new versions would invalidate the test results above. Keep the table for the summary so I can decide what goes in the next release.

## 3. Profiles

Follow `.claude/commands/generateprofile.md`, using the emulator from step 1. This runs after the translation step so the profile is generated from the final code.

## 4. Screenshots, only if the first three tabs look different

The store screenshots show the Clean, Scan and Convert tabs (light and dark). Regenerate them only when something that changes how those tabs look has changed since the screenshots were last updated. Settings does not appear in the screenshots, so changes that only affect it never count.

1. Find the last screenshot update, ignoring image-optimiser commits:
   `git log -1 --format=%H --perl-regexp --author='^(?!ImgBot)' -- fastlane/metadata/android/en-US/images/phoneScreenshots`
2. List what changed since then, including uncommitted changes (the translation step may have edited strings): `git diff --stat <that commit> -- app/src/main/res app/src/main/java scripts/screenshots.sh scripts/store_graphics.py scripts/tag_samples.py icons/sample`.
3. It **counts as a visual change** when any of these changed:
   - layouts for those tabs and the parts they share: `activity_main.xml`, `fragment_clean.xml`, `fragment_scan.xml`, `fragment_convert.xml`, every `item_*.xml`, `dialog_progress.xml`;
   - anything under `res/drawable*` or `res/mipmap*`, except a drawable used only by `fragment_settings.xml` (check with grep);
   - styling resources in any `values*` folder: `colors.xml`, `themes.xml`, `styles.xml`, `dimens.xml`, `shapes.xml`, or a font;
   - English text shown on those tabs in the screenshot state (titles, button labels, hints, badges, format chips): check whether each changed `values/strings.xml` name is used in those layouts or in the classes below;
   - code that sets how those tabs look (visibility, colours, sizes, icons, text, which views appear for a selection): `MainActivity`, `CleanFragment`, `ScanFragment`, `ConvertFragment`, `MediaAdapter`, `ConvertFileAdapter`, `ScanMetadataAdapter`, `UIStateManager`. Read the diff and judge: a change only to background work, logging, or dialogs and states the screenshots never show does not count;
   - the screenshot scripts `scripts/screenshots.sh`, `scripts/store_graphics.py` or `scripts/tag_samples.py`, or the sample media in `icons/sample`.
4. It does **not** count when the only changes are to `fragment_settings.xml`, `SettingsFragment`, Settings-only strings, translations into other locales, or non-UI code.
5. If there is a visual change, follow `.claude/commands/generatescreenshots.md` for `all` locales (store screenshots and feature graphics are per language). Also compare each new en-US store image with the matching one in `fastlane/metadata/android/en-US/images/phoneScreenshots/` (read both) and describe what looks different. If there is no visual change, skip the screenshots and say which commit you compared against and that nothing on those tabs changed.

## 5. Changelog and release notes

Write what this release changed, for people deciding whether to update (and for anyone reading the listing before buying). Play shows the `whatsnew/whatsnew-<locale>` files; the deploy uploads them with `whatsNewDirectory: whatsnew`. The `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` file is the permanent record (the deploy skips uploading changelogs).

1. Read `versionCode` and `versionName` from `app/build.gradle`, and find the last release tag: `git tag -l 'v*' --sort=-v:refname | head -1`. If `versionName` still equals that tag's version, the release has not been bumped: say so in the summary, tell me to run `/bump`, and skip the rest of this step.
2. List the changes: `git log <tag>..HEAD --no-merges --format='%h %s'`, plus uncommitted changes from `git status`. Read the diff of anything whose subject is unclear. Leave out dependency bumps, CI, tests, docs, profiles, screenshots, refactors and anything else a user would never notice.
3. Write the English text:
   - One short line per user-facing change, in plain words with no commit hashes, jargon or class names, most important first: new features, then changed behaviour, then fixes.
   - Name features exactly as the app labels them in `values/strings.xml` (for example Scan with Redact, Keep Original File Names, Strict Clean).
   - Describe fixes by what the user saw ("Fixed a crash when cancelling a video"), and group many small ones into one line such as `Many reliability fixes.` Never write only `Bug fixes and performance improvements.`; if the release truly has nothing visible, say what got better, for example `Faster startup.` or `Updated libraries for stability and security.`
   - At most 500 characters (the Play limit); aim for 6 lines or fewer. Use `changelogs/27.txt` as the model.
4. Save it as `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and `whatsnew/whatsnew-en-US`, each ending with one newline. If the file already exists from `/bump`, rewrite it with these rules when it is generic or out of date.
5. Translate it into `whatsnew/whatsnew-<locale>` for every other store locale: `ar de-DE es-ES fr-FR hi-IN it-IT ja-JP ko-KR pt-BR ru-RU zh-CN zh-TW` (the folder names in `fastlane/metadata/android/`). Use each locale's own app labels from `values-<lang>/strings.xml` (`values-zh-rCN` for zh-CN, `values-pt` for pt-BR, and so on) so the notes match what users see in the app. Check every file is at most 500 characters.
6. Do not touch older changelogs.

## 6. Summary

Finish with one report:

1. **Verdict:** ready to release, or not, and why, in one or two sentences. Any unit test failure, failed feature, crash, ANR, translation error left unfixed, or failed profile run means not ready.
2. **Tests:** the unit test totals and the feature table from step 1.
3. **Translations:** what was missing or left in English, what was translated (per locale), and whether the check now passes.
4. **Profiles:** rule counts before and after, and whether the change is meaningful or noise.
5. **Screenshots:** regenerated or skipped, the reason (the files that count as a visual change, or the baseline commit), and the review of the new images.
6. **Release notes:** the English text with its character count, and whether all 13 `whatsnew` files were written and fit the limit.
7. **Dependencies:** how many are outdated, and the ones in the "needs care" group.
8. **Left uncommitted:** every changed file (translations, profiles, changelog, whatsnew), plus the screenshots in `build/screenshots/` and `build/store/`.

Offer to commit the translations, profiles and release notes, to copy the store images into fastlane (as step 4 of `generatescreenshots.md` describes), and to fix any failures, but do none of these without being asked.
