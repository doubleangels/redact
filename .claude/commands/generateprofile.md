---
description: Regenerate only the baseline and startup profiles on the Pixel 10 Pro emulator and summarize what changed
allowed-tools: Bash, PowerShell, Read, Grep, Glob
---

Regenerate the baseline and startup profiles on the **Pixel_10_Pro emulator and never on a physical device**, then summarize what changed. This does only the profiles; use `/generatescreenshots` for screenshots or `/generateall` for both. Do not commit or push anything unless I ask.

## 1. Find or start the emulator

1. Run `adb devices -l` (adb is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` if it is not on PATH).
2. Pick the serial of the running emulator named `Pixel_10_Pro` (check with `adb -s <serial> emu avd name` or `getprop ro.boot.qemu.avd_name`). Ignore every physical device, even if it is the only other one connected.
3. If no `Pixel_10_Pro` emulator is running, start it: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd Pixel_10_Pro -no-snapshot-save` (in the background), then wait until `sys.boot_completed` is `1`. If the AVD does not exist, stop and tell me.
4. Use that serial for the Gradle call by setting `ANDROID_SERIAL=<serial>` in the same command. Never run Gradle without it while a physical device is connected.

## 2. Generate the profiles

1. Note the current rule counts first, from `app/src/release/generated/baselineProfiles/baseline-prof.txt` and `startup-prof.txt` (line counts).
2. Run `./gradlew :app:generateBaselineProfile` with `ANDROID_SERIAL` set (use `gradlew.bat` from PowerShell). It takes a few minutes. Do not change the build to work around problems; just run it.
3. If it fails, show the relevant error and stop. Do not retry in a loop.

## 3. Summarize the profile changes

Using `git diff` on the two profile files (compare to HEAD), report:

- Rule counts before and after for `baseline-prof.txt` and `startup-prof.txt`, and how many rules were added and removed. Say whether the two files are identical.
- The notable changes, grouped by package, with a few example classes or methods for each. Call out anything tied to this app (`com.doubleangels.redact`) separately from framework and library rules (androidx, Media3, Glide, Sentry, kotlin).
- Whether the startup profile looks sensible (it should be dominated by app startup classes such as the application class, `MainActivity` and the first tab).
- Whether the change looks meaningful or is just run-to-run noise (for example, renumbered synthetic lambdas, the same rules in a different order, or a handful of library methods).

## 4. Wrap up

Finish with a short report: emulator used, profile counts and verdict, and what is left uncommitted. Mention any uncommitted source changes that were part of the build. Offer to commit the profiles, but do not do it without being asked.
