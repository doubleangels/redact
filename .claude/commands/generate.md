---
description: Regenerate the baseline and startup profiles, then the store screenshots, on the Pixel 10 Pro emulator, and summarize the results
allowed-tools: Bash, PowerShell, Read, Grep, Glob
---

Regenerate the baseline profile and the store screenshots, both on the **Pixel_10_Pro emulator and never on a physical device**, then review the results. Do not commit or push anything unless I ask. Do not copy screenshots into `fastlane/` unless I ask.

## 1. Find or start the emulator

1. Run `adb devices -l` (adb is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` if it is not on PATH).
2. Pick the serial of the running emulator named `Pixel_10_Pro` (check with `adb -s <serial> emu avd name` or `getprop ro.boot.qemu.avd_name`). Ignore every physical device, even if it is the only other one connected.
3. If no `Pixel_10_Pro` emulator is running, start it: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd Pixel_10_Pro -no-snapshot-save` (in the background), then wait until `sys.boot_completed` is `1`. If the AVD does not exist, stop and tell me.
4. Use that serial for everything below by setting `ANDROID_SERIAL=<serial>` in the same command as each Gradle or script call. Never run Gradle without it while a physical device is connected.

## 2. Baseline and startup profiles

1. Note the current rule counts first, from `app/src/release/generated/baselineProfiles/baseline-prof.txt` and `startup-prof.txt` (line counts).
2. Run `./gradlew :app:generateBaselineProfile` with `ANDROID_SERIAL` set (use `gradlew.bat` from PowerShell). It takes a few minutes. Do not use `--no-daemon`-style workarounds that change the build; just run it.
3. If it fails, show the relevant error and stop. Do not retry in a loop.

## 3. Screenshots

1. Run `bash scripts/screenshots.sh` from Git Bash with `ANDROID_SERIAL` unset or set to the emulator (the script only ever uses an API 37 emulator). Do **not** set `SKIP_INSTALL`: the profile step installs a different build variant, so the script must install the current debug app itself. It applies the black-and-white theme and writes six images to `build/screenshots/` (1 and 2 Clean, 3 and 4 Scan, 5 and 6 Convert, each light then dark). It takes about a minute and a half.
2. If it fails, report the error and stop.

## 4. Check the images

Read all six images in `build/screenshots/` and check each for:

- the right tab and the expected state (Clean and Convert show the 8 selected samples; Scan shows one file's metadata),
- the black-and-white system theme applied (no blue or purple tint),
- clean status bar (12:00, full battery),
- light versions are light and dark versions are dark,
- nothing cut off, blank, overlapping, showing an error or a permission dialog, or showing a notification.

List anything wrong per image. Say plainly if everything looks good.

## 5. Summarize the profile changes

Using `git diff` on the two profile files (compare to HEAD), report:

- Rule counts before and after for `baseline-prof.txt` and `startup-prof.txt`, and how many rules were added and removed.
- The notable changes, grouped by package, with a few example classes or methods for each. Call out anything tied to this app (`com.doubleangels.redact`) separately from framework and library rules (androidx, Media3, Glide, Sentry, kotlin).
- Whether the startup profile looks sensible (it should be dominated by app startup classes such as the application class, `MainActivity` and the first tab).
- Whether the change looks meaningful or is just run-to-run noise (for example, mostly the same rules in a different order or a handful of library methods).

## 6. Wrap up

Finish with a short report: emulator used, profile counts and verdict, screenshot verdict, and what is left uncommitted. Offer to commit the profiles and to copy the screenshots into `fastlane/metadata/android/en-US/images/` (phone, sevenInch and tenInch), but do not do either without being asked.
