---
description: Regenerate the store screenshots, their captioned store versions and the feature graphic on the Pixel 10 Pro emulator, and check the images
allowed-tools: Bash, PowerShell, Read, Grep, Glob
argument-hint: "[ifchanged] [all | <store locales>]"
---

> These commands drive the Pixel_10_Pro emulator and Gradle with Windows paths, so they only work on my own machine. If you are running in a cloud session without the Android SDK and the emulator, say so and stop instead of trying.

Regenerate the store screenshots on the **Pixel_10_Pro emulator and never on a physical device**, then check the images. This does only the screenshots; use `/generateprofiles` for the profiles or `/generateall` for both. The screenshots land in `build/`, which git ignores, so there is nothing to commit unless I ask you to copy them into `fastlane/`; then commit the copied images to the current branch automatically. Do not push. Do not copy screenshots into `fastlane/` unless I ask.

## 0. Only if the first three tabs look different (`ifchanged`)

Skip this step unless I passed `ifchanged`. The store screenshots show the Clean, Scan and Convert tabs (light and dark). With `ifchanged`, regenerate them only when something that changes how those tabs look has changed since the screenshots were last updated. Settings does not appear in the screenshots, so changes that only affect it never count.

1. Find the last screenshot update, ignoring image-optimiser commits:
   `git log -1 --format=%H --perl-regexp --author='^(?!ImgBot)' -- fastlane/metadata/android/en-US/images/phoneScreenshots`
2. List what changed since then, including uncommitted changes (a translation run may have edited strings): `git diff --stat <that commit> -- app/src/main/res app/src/main/java scripts/screenshots.sh scripts/store_graphics.py scripts/tag_samples.py icons/sample`.
3. It **counts as a visual change** when any of these changed:
   - layouts for those tabs and the parts they share: `activity_main.xml`, `menu/bottom_navigation_menu.xml` (the tab bar is in every shot), `fragment_clean.xml`, `fragment_scan.xml`, `fragment_convert.xml`, every `item_*.xml`, `dialog_progress.xml`;
   - anything under `res/drawable*` or `res/mipmap*`, except a drawable used only by `fragment_settings.xml` or the Censor tab's layouts (check with grep); `ic_censor` counts, since the tab bar shows it;
   - styling resources in any `values*` folder: `colors.xml`, `themes.xml`, `styles.xml`, `dimens.xml`, `shapes.xml`, or a font;
   - English text shown on those tabs in the screenshot state (titles, button labels, hints, badges, format chips): check whether each changed `values/strings.xml` name is used in those layouts or in the classes below;
   - code that sets how those tabs look (visibility, colours, sizes, icons, text, which views appear for a selection): `MainActivity`, `CleanFragment`, `ScanFragment`, `ConvertFragment`, `MediaAdapter`, `ConvertFileAdapter`, `ScanMetadataAdapter`, `UIStateManager`. Read the diff and judge: a change only to background work, logging, or dialogs and states the screenshots never show does not count;
   - the screenshot scripts `scripts/screenshots.sh`, `scripts/store_graphics.py` or `scripts/tag_samples.py`, or the sample media in `icons/sample`.
4. It does **not** count when the only changes are to `fragment_settings.xml`, `SettingsFragment`, Settings-only strings, the Censor tab (`fragment_censor.xml`, `layout-land/fragment_censor.xml`, `censor_*.xml`, `CensorFragment`, `CensorCanvasView`, `CensorViewModel`, `censor_*` strings other than `navigation_censor`), translations into other locales, or non-UI code. The store screenshots never open Censor.
5. If nothing counts, stop here: say which commit you compared against and that nothing on those tabs changed. Otherwise list the files that count and carry on. In step 3, also compare each locale's new store images with its published ones in `fastlane/metadata/android/<locale>/images/phoneScreenshots/` (read both), describe what looks different, and say which locales had no published images yet.

## 1. Find or start the emulator

1. Run `adb devices -l` (adb is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` if it is not on PATH).
2. Pick the serial of the running emulator named `Pixel_10_Pro` (check with `adb -s <serial> emu avd name` or `getprop ro.boot.qemu.avd_name`). Ignore every physical device, even if it is the only other one connected.
3. If no `Pixel_10_Pro` emulator is running, start it: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd Pixel_10_Pro -no-snapshot-save` (in the background), then wait until `sys.boot_completed` is `1`. If the AVD does not exist, stop and tell me.

## 2. Generate the screenshots

1. Pick the locales: what I passed (store locales such as `de-DE fr-FR`), otherwise `all`. Every store locale has its own screenshots and feature graphic in its own language, so a full run covers all 13; it takes about a minute and a half per locale.
2. Run `LOCALES="<locales>" bash scripts/screenshots.sh` from Git Bash. The script only ever uses an API 37 emulator, so a connected physical device is never touched. Do **not** set `SKIP_INSTALL`: the script must install the current debug app itself. It clears Redact's app data on the emulator (default settings, permissions granted, the rating prompt marked answered), switches the app to each locale, and writes:
   - raw captures to `build/screenshots/<locale>/`: `clean`, `clean-done`, `scan`, `convert` (light) and `clean-dark`, `scan-dark`, `convert-dark`;
   - store-ready images to `build/store/<locale>/`: `phone/1.png`…`6.png` (1440×2560, captioned, alternating light and dark: Scan, Clean result dark, Clean selection, Convert dark, Clean result, Scan dark) and `featureGraphic.png` (1024×500), drawn by `scripts/store_graphics.py` with headless Chrome or Edge.
3. If it fails, report the error and stop. Do not retry in a loop. If only the framing failed, `python scripts/store_graphics.py --locales "<locales>"` redoes just that step from the raw captures. If a single locale's images come out wrong (a capture glitch, not an app bug), rerun just that locale once with `LOCALES="<locale>"`; if the emulator stops responding (`adb devices` lists nothing while it runs), close it, start it again and rerun the locale.

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

Finish with a short report: emulator used, locales captured, the verdict per image, and the location of the images. Mention any uncommitted source changes that were part of the build. Offer to copy the store images into fastlane, but do not copy without being asked; once copied, commit them to the current branch automatically with a message like `Update the store screenshots and feature graphics`. Copying means, for **every** locale captured: `build/store/<locale>/phone/*.png` into `fastlane/metadata/android/<locale>/images/phoneScreenshots/`, `sevenInchScreenshots/` and `tenInchScreenshots/` (creating the folders when a locale has none yet, and replacing what is there), and `featureGraphic.png` into `fastlane/metadata/android/<locale>/images/`. Each locale publishes its own images; Play shows the en-US ones only for a locale that has none. Copy only complete, reviewed sets: a locale whose capture failed keeps its old images. Also delete `fastlane/metadata/android/en-US/images/promoGraphic.png` if it is still there: it is a blank legacy image that Play no longer uses.
