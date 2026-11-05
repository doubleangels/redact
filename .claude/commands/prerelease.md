---
description: Before a release, run /translate (strings and store listing), upgrade every dependency, fix open Sentry issues (/sentry), check that new code reports to Sentry (/sentrycoverage), run /cleanup and /lint, make sure the version is bumped (/bump), preview the release notes CI will write (/releasenotes), run the unit tests (with /coverage if it is under the gate), run /test on the Pixel 10 Pro test emulator while the profiles generate on their own emulator, regenerate the screenshots if Clean, Scan or Convert look different (/generatescreenshots ifchanged), and show one summary
allowed-tools: Bash, PowerShell, Read, Edit, Write, Grep, Glob, WebFetch
---

> These commands drive the Pixel_10_Pro emulator and Gradle with Windows paths, so they only work on my own machine. If you are running in a cloud session without the Android SDK and the emulator, say so and stop instead of trying.

Get the app ready for a release on two emulators, and never on a physical device, then give one combined summary:

- **Test emulator:** the `Pixel_10_Pro` AVD (Play Store image, API 37.2). `/test` and the store screenshots run on it.
- **Profile emulator:** `profileGeneratorApi37`, the Gradle-managed emulator in `baselineprofile/build.gradle` (Google APIs API 37, no Play Store, headless). Only the profile generator uses it; Gradle starts and stops it.

Because they are separate, the profiles are generated while the feature tests run. Every step that changes the code (translations, dependency upgrades, Sentry fixes, cleanup, lint fixes, the version bump) runs first, then the tests, profiles and screenshots run on that code, so their results cover exactly what will be released. The profiles only change the release build's profile files, which the debug app under test never uses, so generating them alongside the tests does not change what the tests cover. Each step follows another command's file: read that file and do what it says, with the changes listed here. Each step commits its own changes to the current branch automatically when it finishes (translations, dependency upgrades, Sentry fixes, cleanup, lint fixes, the version bump, profiles, coverage tests), as the command it follows describes, so the final test run covers committed code. Never push, and do not copy screenshots into `fastlane/` unless I ask.

If a step fails, record why and carry on with the next one, except that a missing `Pixel_10_Pro` AVD stops everything.

**Test once, at the right point.** Builds, unit tests and emulator runs are the slow part of this command, so each kind of check runs once, where it is needed, and only when something it covers changed:

- The full unit test suite runs once, in step 4. Earlier steps compile, or run only the test classes for what they changed, and only when they need a result before step 4.
- `lintRelease` runs in step 2d only, and the release bundle in step 2f only, covering every code change before them.
- A step that changed nothing skips its checks and its commit.
- Boot the test emulator once in step 0 and keep it running through steps 5 and 6; never restart or cold-boot it between steps, and leave it running at the end. Steps before 5 do not use it: anything they would check on the emulator is covered by step 5's feature run.
- Never run two Gradle builds of this project at once: they fight over the same build locks. Start the profile run only once the feature tests have finished their Gradle install, and let the screenshots skip their install.
- Gradle reuses up-to-date work, so do not run `clean` or pass `--rerun-tasks`.

## 0. Test emulator

Before changing anything, check that the `Pixel_10_Pro` AVD exists (`emulator -list-avds`), so a missing one stops the run early. Then decide how to boot it: run step 0 of `.claude/commands/generatescreenshots.md` (the `ifchanged` check, which only reads git) once now. If the screenshots will need regenerating, start the test emulator with its window, as `generatescreenshots.md` step 1 describes; otherwise start it headless, as `test.md` step 2 describes. Start it in the background and go on with steps 1 to 2f while it boots, since nothing needs it before step 5. If a later step changes something that counts as a visual change, the screenshots still run in step 6 on the headless emulator; say so, and say they are worth a look. Use its serial (`ANDROID_SERIAL`) for every step that uses the test emulator.

## 1. Translations

Follow `.claude/commands/translate.md`: check every string and the Play store listing text (`fastlane/metadata/android/<locale>/` title and descriptions) in all 12 locales, and translate anything missing, out of date or left in English. Do not run the unit tests here; step 4 runs the full suite, including `StringTranslationsTest`, on the final code.

## 2. Dependencies

Follow `.claude/commands/dependencies.md` with these changes:

- Apply **every update**: upgrade everything outdated to its latest stable version, ignoring step 3's groups. That includes the "Needs care" items (Media3, Glide, AGP, the Gradle wrapper, the Java toolchain, `compileSdk` and `targetSdk`, moving together where they must), versions pinned on purpose with a comment, and SHA-pinned actions (keep the SHA style). Read the release notes of each major upgrade and make the smallest code or workflow change it needs. The only exceptions are pre-releases (alpha, beta, rc), and a version that needs a higher `minSdk` than the app has, since raising `minSdk` drops devices; list each exception for me instead of applying it.
- Skip its step 5 checks except the workflow YAML parse. Step 2f builds the release bundle once for all the code changes, and steps 4 and 5 test everything else.
- Commit the upgrades as `dependencies.md` describes.

## 2a. Sentry issues

Follow `.claude/commands/sentry.md` to fix every unresolved issue in Sentry, with these changes:

- If there are no unresolved issues, there is nothing to test or commit; go on.
- In place of its full unit test run (its step 4.1), run only the test classes for the code each fix touched, including the new regression tests (`./gradlew :app:testDebugUnitTest --tests '<class>' -q`). That is enough to mark an issue resolved now; step 4 runs the full suite, and if it then fails because of a Sentry fix, set that issue back to unresolved and say so.
- Skip its lint run (step 2d runs `/lint` on everything) and its `/verifyclean` (step 5 cleans every kind of file on the emulator).
- If the Sentry MCP tools need a sign-in, give me the link and carry on with the next step instead of waiting; come back to this one before step 2f if I have signed in by then, and otherwise list it as not done.
- Commit as `sentry.md` describes, and keep its report for the summary.

## 2b. Sentry coverage

Follow `.claude/commands/sentrycoverage.md` against the previous release, with one change: instead of its step 4, run `./gradlew :app:compileDebugUnitTestJavaWithJavac -q` if anything changed, since step 4 runs the full suite on the final code. If that compile fails because of a Sentry fix, roll that fix back and list it as a gap. Commit as `sentrycoverage.md` describes, and keep its report for the summary.

A gap that could not be fixed does not on its own make the release not ready, but anything that still sends private data to Sentry does.

## 2c. Cleanup

Follow `.claude/commands/cleanup.md` on the whole app, with these changes:

- Do not stop to wait after its step 2: keep its numbered findings for the summary and apply them.
- Run it after the upgrades and both Sentry steps, so it judges the code that will ship. A dependency step 2 just upgraded is still checked for whether anything uses it.
- Skip its step 4 checks except the two `.claude/scripts` runs (`unused_imports.py` and `unused_dependencies.py`), which take seconds. Step 2d runs lint, step 2f builds the release bundle, step 4 runs the unit tests and measures coverage, and step 5 cleans every kind of file on the emulator with `check_media.py`, which covers `/verifyclean`. If a dependency was removed, still compare the `releaseRuntimeClasspath` before and after, as `cleanup.md` says.
- Skip its baseline profile edits: step 5 regenerates the profiles from the cleaned code.
- If `./gradlew :app:compileDebugUnitTestJavaWithJavac -q` fails after a change, revert just that change. Then commit as `cleanup.md` describes.

## 2d. Lint

Follow `.claude/commands/lint.md` to fix every issue lint reports, not only new ones, so CI's `lintRelease` passes and nothing is left for later. Skip its unit test run (step 4 runs the full suite) and its emulator check of changed layouts; instead, name each screen whose layout it changed so step 5 looks at it closely. Its lint re-run is needed only if it fixed something. A lint finding that would need a behaviour change is listed for me, not applied. Commit as `lint.md` describes.

## 2e. Version

Make sure this release has its own version, so CI's `check-version` job and `/release` do not stop it later.

1. Read `versionCode` and `versionName` from `app/build.gradle`. Find the last published release the way CI does: `last-published-version` if `git rev-parse --verify --quiet refs/tags/last-published-version` succeeds, otherwise the newest `v*` tag (run `git fetch --tags --force origin` first).
2. If `versionCode` is higher than that release's (`git show <ref>:app/build.gradle`) and the tag `v<versionName>` does not exist yet, the version is already bumped: say so and go on.
3. Otherwise follow `.claude/commands/bump.md` with no level, so it picks one from the commits. It never picks major on its own; if it proposes major, ask me and carry on with the other steps while waiting. Skip its step 4 checks: step 1 already checked the translations and step 4 runs the unit tests. Commit as `bump.md` describes.

## 2f. Release build

Run `./gradlew :app:bundleRelease -q` once, if any of steps 1 to 2e changed code, resources, Gradle files or dependencies. R8 can fail, or strip something reached by reflection, in a way the debug unit tests never see. If it fails, find the step that broke it from that step's commit (each step committed its own changes, so build the commits in between as needed), then fix or revert just that change and build again.

## 3. Release notes, preview only

Follow `.claude/commands/releasenotes.md` and keep its report for the summary. It only previews what CI will write; nothing is saved.

## 4. Unit tests and coverage

Run `./gradlew :app:jacocoTestReport` alone, as `test.md` step 1 describes: it runs `testDebugUnitTest` first, so the suite runs once and gives both the results and the line coverage. If every unit test passed but line coverage is under the 90% gate CI enforces, follow `.claude/commands/coverage.md` to add tests until it is back over 90% (tests only: it never changes app code), starting from the report this step already made instead of measuring again, commit as it describes, and note the coverage before and after. Do not fix test failures here; if a failure looks tied to one of this run's upgrades or fixes, say which.

## 5. Feature tests and profiles, side by side

1. Follow `.claude/commands/test.md` from its step 2 on, on the test emulator from step 0: its unit tests already ran in step 4. Its install (`./gradlew :app:installDebug`) is the last Gradle build it runs.
2. As soon as that install has finished, start `./gradlew :app:generateBaselineProfile` in the background, as `.claude/commands/generateprofiles.md` step 2 describes. It runs on the profile emulator, so the feature tests carry on undisturbed. Note the profile rule counts before starting it.
3. Do not run any other Gradle build of this project until the profile run has finished. If `/test` would need one later (for example to reinstall), wait for the profile run first.
4. When the profile run finishes, summarize and commit it as `generateprofiles.md` steps 3 and 4 describe. This runs after every code change (translations, upgrades, Sentry fixes, cleanup, lint fixes), so the profile is generated from the final code.
5. Keep `/test`'s summary for the end rather than printing it now. Do not fix feature failures here; if a failure looks tied to one of this run's upgrades or fixes, say which. Do not repeat the emulator run after adding coverage tests in step 4; they change no app code.

## 6. Screenshots, only if the first three tabs look different

Follow `.claude/commands/generatescreenshots.md` with `ifchanged all`, on the test emulator, once `/test` has finished with it. Its step 0 decides whether anything on the Clean, Scan or Convert tabs changed since the screenshots were last updated; when it did, it regenerates every locale and compares the new store images with the published ones. Run it with `SKIP_INSTALL=1`, since `/test` just installed the current debug build there; that keeps it from starting a Gradle build while the profile run may still be going. Keep its report for the summary.

## 7. Summary

Finish with one report:

1. **Verdict:** ready to release, or not, and why, in one or two sentences. Any unit test failure, line coverage still under the 90% gate CI enforces, failed feature, crash, ANR, translation or store listing error left unfixed, lint issue left unfixed, a version that was not bumped, a release bundle that does not build, failed profile run, or private data sent to Sentry means not ready.
2. **Tests:** the unit test totals and line coverage from step 4 (and, if `/coverage` ran, before and after with the tests it added), and the feature table from step 5.
3. **Translations:** what was missing or left in English, what was translated (per locale), the store listing files that were missing, too long or out of date and what changed, and whether `StringTranslationsTest` passed in step 4.
4. **Dependencies:** how many were outdated, each upgrade applied (`name old -> new`), anything skipped or rolled back and why, and any step 4 or 5 failure that looks tied to an upgrade.
5. **Sentry issues:** each issue with its users and events, its category, the fix and its test, and its new status in Sentry; anything left unresolved and what it is waiting for.
6. **Sentry coverage:** the release range checked, each gap with its `file:line` and how it was fixed, and anything left unfixed and why.
7. **Cleanup:** the findings by group with what was applied, `git diff --stat` for its commit, how many imports and dependencies were removed (and any library whose version changed), anything skipped or reverted and why, and its questions for me.
8. **Lint:** how many issues there were and that none remain, the fixes by issue id, and any suppression with its reason.
9. **Version:** the old and new `versionName`/`versionCode` and the bump level with its reason, or that it was already bumped.
10. **Profiles:** rule counts before and after, and whether the change is meaningful or noise.
11. **Screenshots:** regenerated or skipped, the reason (the files that count as a visual change, or the baseline commit), and the review of the new images.
12. **Release notes:** the previewed English text with its character count, and whether CI will generate it or keep a hand-written one.
13. **Commits:** each commit this run made (short sha and subject), anything left uncommitted and why, and where the screenshots are (`build/screenshots/` and `build/store/`).

Offer to copy every locale's store images into fastlane (as step 4 of `generatescreenshots.md` describes), and to fix any failures, but do none of these without being asked.
