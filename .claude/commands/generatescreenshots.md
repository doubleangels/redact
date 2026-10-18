---
description: Regenerate the store screenshots, their captioned store versions and the feature graphic on the Pixel 10 Pro emulator, and check the images
allowed-tools: Bash, PowerShell, Read, Grep, Glob
argument-hint: "[all | <store locales>]"
---

Regenerate the store screenshots on the **Pixel_10_Pro emulator and never on a physical device**, then check the images. This does only the screenshots; use `/generateprofile` for the profiles or `/generateall` for both. Do not commit or push anything unless I ask. Do not copy screenshots into `fastlane/` unless I ask.

## 1. Find or start the emulator

1. Run `adb devices -l` (adb is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` if it is not on PATH).
2. Pick the serial of the running emulator named `Pixel_10_Pro` (check with `adb -s <serial> emu avd name` or `getprop ro.boot.qemu.avd_name`). Ignore every physical device, even if it is the only other one connected.
3. If no `Pixel_10_Pro` emulator is running, start it: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd Pixel_10_Pro -no-snapshot-save` (in the background), then wait until `sys.boot_completed` is `1`. If the AVD does not exist, stop and tell me.

## 2. Generate the screenshots

1. Pick the locales: what I passed (`all`, or store locales such as `de-DE fr-FR`), otherwise `en-US` only. `all` takes about a minute and a half per locale for the 13 store locales.
2. Run `LOCALES="<locales>" bash scripts/screenshots.sh` from Git Bash. The script only ever uses an API 37 emulator, so a connected physical device is never touched. Do **not** set `SKIP_INSTALL`: the script must install the current debug app itself. It clears Redact's app data on the emulator (default settings, permissions granted, the rating prompt marked answered), switches the app to each locale, and writes:
   - raw captures to `build/screenshots/<locale>/`: `clean`, `clean-done`, `scan`, `convert` (light) and `clean-dark`, `scan-dark`, `convert-dark`;
   - store-ready images to `build/store/<locale>/`: `phone/1.png`…`6.png` (1440×2560, captioned: Scan, Clean result, Clean selection, Convert, Scan dark, Clean result dark) and `featureGraphic.png` (1024×500), drawn by `scripts/store_graphics.py` with headless Chrome or Edge.
3. If it fails, report the error and stop. Do not retry in a loop. If only the framing failed, `python scripts/store_graphics.py --locales "<locales>"` redoes just that step from the raw captures.

If the sample media changed, or a new sample was added, run `python scripts/tag_samples.py` first so every sample carries GPS, camera and time metadata.

## 3. Check the images

Read every raw capture and every store image, and check:

- **Scan** (`scan`, `scan-dark`): one sample's metadata with the **Location** badge and GPS rows visible, plus the phone make and model. If the GPS rows are below the fold, say so: the first store image must show them.
- **Clean** (`clean`): the 8 selected samples. **Clean result** (`clean-done`, `clean-dark`): the result line with the number of fields removed and how many files had a location, and the Share button.
- **Convert**: the 8 selected samples and the format chips.
- All: the black-and-white system theme (no blue or purple tint), a clean status bar (12:00, full battery), light shots light and dark shots dark, the app in the locale's language, and nothing cut off, blank, overlapping, or showing an error, a permission dialog, the rating prompt or a notification.
- Store images: the caption is in the locale's language, fully visible, not overlapping the phone, and right-to-left for `ar`; the phone frame is whole. The feature graphic's text is readable and not clipped.

List anything wrong per image. Say plainly if everything looks good.

## 4. Wrap up

Finish with a short report: emulator used, locales captured, the verdict per image, and the location of the images. Mention any uncommitted source changes that were part of the build. Offer to copy the store images into fastlane and to commit them, but do not do either without being asked. Copying means, for each locale: `build/store/<locale>/phone/*.png` into `fastlane/metadata/android/<locale>/images/phoneScreenshots/`, `sevenInchScreenshots/` and `tenInchScreenshots/` (replacing what is there), and `featureGraphic.png` into `fastlane/metadata/android/<locale>/images/`. Also delete `fastlane/metadata/android/en-US/images/promoGraphic.png` if it is still there: it is a blank legacy image that Play no longer uses.
