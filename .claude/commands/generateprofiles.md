---
description: Regenerate only the baseline and startup profiles on the profile generator's own Gradle-managed emulator and summarize what changed
allowed-tools: Bash, PowerShell, Read, Grep, Glob
---

> These commands use the Android SDK's emulator and Gradle with Windows paths, so they only work on my own machine. If you are running in a cloud session without the Android SDK and the emulator, say so and stop instead of trying.

Regenerate the baseline and startup profiles on the **profile emulator**, then summarize what changed. This does only the profiles; use `/generatescreenshots` for screenshots or `/generateall` for both. When it is done, commit the two profile files to the current branch automatically. Do not push.

**Which emulator.** The profiles are generated on `profileGeneratorApi37`, a Gradle-managed emulator defined in `baselineprofile/build.gradle` (Google APIs API 37, no Play Store, on a Pixel 10 Pro screen). Gradle creates it, boots it headless with a fresh install, runs the generator and shuts it down. It is not the `Pixel_10_Pro` AVD, which is the **test emulator** that `/test`, `/verifyclean` and `/generatescreenshots` use. The profile run never touches the test emulator or a connected device, so it can run while they are in use, as long as no other Gradle build of this project runs at the same time (two builds fight over the same build locks).

## 1. Before the run

Nothing to start: there is no emulator to find, and `ANDROID_SERIAL` is not needed. The first run on a machine downloads the API 37 Google APIs system image (`system-images;android-37.0;google_apis;x86_64`) and creates the device, which takes a few extra minutes; later runs reuse both.

## 2. Generate the profiles

1. Note the current rule counts first, from `app/src/release/generated/baselineProfiles/baseline-prof.txt` and `startup-prof.txt` (line counts).
2. Run `./gradlew :app:generateBaselineProfile` (use `gradlew.bat` from PowerShell). It runs on `profileGeneratorApi37` and takes a few minutes. Do not change the build to work around problems; just run it.
3. If it fails, show the relevant error and stop. Do not retry in a loop. If the managed emulator itself failed to boot, `./gradlew cleanManagedDevices` removes its saved state; say so rather than running it yourself.

## 3. Summarize the profile changes

Using `git diff` on the two profile files (compare to HEAD), report:

- Rule counts before and after for `baseline-prof.txt` and `startup-prof.txt`, and how many rules were added and removed. The two files come from different journeys in `BaselineProfileGenerator` (`startup` for the startup profile, `generate` for the rest of the baseline profile), so the startup profile should be much smaller. If they are identical, say so: the generator's journeys are broken.
- The notable changes, grouped by package, with a few example classes or methods for each. Call out anything tied to this app (`com.doubleangels.redact`) separately from framework and library rules (androidx, Media3, Glide, Sentry, kotlin).
- Whether the startup profile looks sensible: a cold start to the Clean tab, so the application class, `MainActivity`, `CleanFragment` and what they load, and no `OnboardingActivity`, Settings, Statistics or share-handling code.
- Whether the baseline profile covers every journey `generate` runs: each tab, `StatisticsActivity`, `LicensesActivity`, the tile services, `ShareHandlerActivity` and the clean it runs (`MetadataStripper`), and the Scan, Convert and Censor share targets. Name any that are missing.
- Whether the change looks meaningful or is just run-to-run noise (for example, renumbered synthetic lambdas, the same rules in a different order, or a handful of library methods).

## 4. Wrap up

Unless the profile run failed, commit `baseline-prof.txt` and `startup-prof.txt` (only those two files) to the current branch without asking, with a message like `Refresh the baseline and startup profiles`, **but only when the change is meaningful**:

- **Meaningful:** any `com.doubleangels.redact` rule added or removed, or library rules added or removed in more than a handful of classes (roughly 1% of the rules or more), as happens after a dependency upgrade or new code on a startup path.
- **Noise:** everything else. That means only flag changes on existing rules (`HSPL` ↔ `SPL` ↔ `PL`), the same rules in a different order, renumbered synthetic lambdas, or a few library methods coming and going.

For noise, skip the commit and restore the two files (`git checkout -- app/src/release/generated/baselineProfiles/baseline-prof.txt app/src/release/generated/baselineProfiles/startup-prof.txt`) so they do not linger in the working tree. Skip the commit too if neither file changed.

Finish with a short report: that it ran on `profileGeneratorApi37`, how long the run took, profile counts and verdict, the commit (or that it was skipped as noise and the files restored), and any uncommitted source changes that were part of the build.
