---
description: Regenerate only the store screenshots on the Pixel 10 Pro emulator and check the images
allowed-tools: Bash, PowerShell, Read, Grep, Glob
---

Regenerate the store screenshots on the **Pixel_10_Pro emulator and never on a physical device**, then check the images. This does only the screenshots; use `/generateprofile` for the profiles or `/generateall` for both. Do not commit or push anything unless I ask. Do not copy screenshots into `fastlane/` unless I ask.

## 1. Find or start the emulator

1. Run `adb devices -l` (adb is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` if it is not on PATH).
2. Pick the serial of the running emulator named `Pixel_10_Pro` (check with `adb -s <serial> emu avd name` or `getprop ro.boot.qemu.avd_name`). Ignore every physical device, even if it is the only other one connected.
3. If no `Pixel_10_Pro` emulator is running, start it: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd Pixel_10_Pro -no-snapshot-save` (in the background), then wait until `sys.boot_completed` is `1`. If the AVD does not exist, stop and tell me.

## 2. Generate the screenshots

1. Run `bash scripts/screenshots.sh` from Git Bash. The script itself only ever uses an API 37 emulator, so a connected physical device is never touched. Do **not** set `SKIP_INSTALL`: the script must install the current debug app itself. It applies the black-and-white theme and writes six images to `build/screenshots/` (1 and 2 Clean, 3 and 4 Scan, 5 and 6 Convert, each light then dark). It takes about a minute and a half.
2. If it fails, report the error and stop. Do not retry in a loop.

## 3. Check the images

Read all six images in `build/screenshots/` and check each for:

- the right tab and the expected state (Clean and Convert show the 8 selected samples; Scan shows one file's metadata),
- the black-and-white system theme applied (no blue or purple tint),
- clean status bar (12:00, full battery),
- light versions are light and dark versions are dark,
- nothing cut off, blank, overlapping, showing an error or a permission dialog, or showing a notification.

List anything wrong per image. Say plainly if everything looks good.

## 4. Wrap up

Finish with a short report: emulator used, the verdict per image, and the location of the images. Mention any uncommitted source changes that were part of the build. Offer to copy the screenshots into `fastlane/metadata/android/en-US/images/` (phone, sevenInch and tenInch) and to commit them, but do not do either without being asked.
