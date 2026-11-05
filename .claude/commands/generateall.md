---
description: Regenerate the baseline and startup profiles on the profile emulator and the store screenshots on the Pixel 10 Pro test emulator, side by side, and summarize the results
allowed-tools: Bash, PowerShell, Read, Grep, Glob
argument-hint: "[all | <store locales>]"
---

> These commands drive the Pixel_10_Pro emulator and Gradle with Windows paths, so they only work on my own machine. If you are running in a cloud session without the Android SDK and the emulator, say so and stop instead of trying.

Regenerate the baseline profile and the store screenshots, then review the results. They use two different emulators, so they run at the same time:

- **Profile emulator:** `profileGeneratorApi37`, the Gradle-managed emulator in `baselineprofile/build.gradle` (Google APIs API 37, no Play Store, headless). See `/generateprofiles`.
- **Test emulator:** the `Pixel_10_Pro` AVD (Play Store image, API 37.2), with its window. The screenshots always come from this one, never from the profile emulator or a physical device.

Use `/generateprofiles` or `/generatescreenshots` to do just one. When it is done, commit the profiles to the current branch automatically. The screenshots land in `build/`, which git ignores, so there is nothing to commit unless I ask you to copy them into `fastlane/`; then commit the copied images to the current branch automatically. Do not push. Do not copy screenshots into `fastlane/` unless I ask.

## 1. Test emulator

Find or start the test emulator as step 1 of `.claude/commands/generatescreenshots.md` describes.

## 2. Screenshots, then the profiles alongside

Two Gradle builds of this project must not run at once (they fight over the same build locks), and the screenshot script starts with one (`installDebug`). So:

1. Note the current rule counts from `app/src/release/generated/baselineProfiles/baseline-prof.txt` and `startup-prof.txt` (line counts).
2. Start the screenshots as step 2 of `generatescreenshots.md` describes, with the locales I passed (default `all`), in the background, and watch its output.
3. Once it prints that the debug app is installed and starts capturing (it uses only adb from then on), start `./gradlew :app:generateBaselineProfile` in the background, as step 2 of `generateprofiles.md` describes. If the screenshot script fails before that point, start the profiles anyway.
4. Wait for both. A failure in one does not stop the other; report it and carry on.

## 3. Check the images

Follow step 3 ("Check the images") of `.claude/commands/generatescreenshots.md`.

## 4. Summarize the profile changes

Follow step 3 ("Summarize the profile changes") of `.claude/commands/generateprofiles.md`.

## 5. Wrap up

Finish with a short report: the emulator each part used, how long each took, profile counts and verdict, screenshot verdict, and the profile commit (or that it was skipped as noise). Unless the profile run failed, commit `baseline-prof.txt` and `startup-prof.txt` (only those two files) to the current branch without asking when the change is meaningful, and skip the commit and restore the two files when it is only noise, exactly as step 4 of `generateprofiles.md` describes. Offer to copy the store images into fastlane as step 4 of `generatescreenshots.md` describes, but do not copy without being asked; once copied, commit them automatically too.
