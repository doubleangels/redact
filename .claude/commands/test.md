---
description: Run every unit test, then test every feature of the app on the Pixel 10 Pro emulator, and show a summary
allowed-tools: Bash, PowerShell, Read, Grep, Glob
---

Run the full unit test suite, then drive the app on the **Pixel_10_Pro emulator and never on a physical device** through every feature below, checking real output files, and finish with a summary. Do not change source code, commit or push. If a feature is broken, record it with the evidence and keep going; at the end, offer to fix what failed.

Work from Git Bash. Use the scratchpad directory for everything you create (media, pulled files, screenshots); call it `$S` below.

## Helpers

- `.claude/scripts/ui.sh dump [png]` prints every visible text or clickable node as `[x1,y1][x2,y2] id=… t='…' d='…' chk=…` and saves a screenshot. `ui.sh tap <text|content-desc|id>` taps a node. `ui.sh adb …` runs adb with Git Bash path conversion off; always use it (or `MSYS_NO_PATHCONV=1`) for adb commands with device paths, or `/sdcard/...` becomes a Windows path.
- `.claude/scripts/make_test_media.py <dir>` makes photos and videos full of metadata (GPS, camera, software, XMP, video location and comment, a 16000×8000 JPEG, a 90 s video and a VP9 WebM). Its docstring lists the files.
- `.claude/scripts/check_media.py <file|dir>…` reports identifying metadata left in files and exits 1 if any remains. Run it on every file Redact writes. Orientation, ImageWidth, ImageLength and LightSource left as `0` are a known `ExifInterface` limitation and are not reported. An Ultra HDR gain map's `hdrgm`-only XMP is allowed.
- Read screenshots when the node dump is not enough to judge a screen.
- Find a file's MediaStore id with `ui.sh adb shell "content query --uri content://media/external/file --projection _id:_display_name:relative_path --where \"_display_name='NAME'\""`. Read app files with `ui.sh adb shell run-as com.doubleangels.redact …` (for example `cat shared_prefs/redact_prefs.xml` or `ls cache/processed`).
- Clear logcat at the start (`ui.sh adb logcat -c`), and after every section check `ui.sh adb logcat -d -b crash` and `logcat -d | grep -E "FATAL EXCEPTION|ANR in com.doubleangels"`. Any crash or ANR fails that section.

## 1. Unit tests

1. Run `./gradlew testDebugUnitTest`. Afterwards total the `tests`, `skipped`, `failures` and `errors` attributes of `app/build/test-results/testDebugUnitTest/*.xml`, and list every failing test with its message.
2. If `jacocoTestReport` exists (`./gradlew tasks --all | grep -i jacoco`), run it and note the line coverage from its report.
3. Failing unit tests do not stop the run; carry on to the emulator.

## 2. Emulator and app

1. Run `adb devices -l`. Pick the running emulator whose `adb -s <serial> emu avd name` is `Pixel_10_Pro`; ignore physical devices even if one is the only other device. If none is running, start `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd Pixel_10_Pro -no-snapshot-save` in the background and wait until `getprop sys.boot_completed` is `1`. If the AVD does not exist, stop and tell me.
2. Set `ANDROID_SERIAL=<serial>` in every command from here on, including Gradle.
3. Uninstall any existing build (`adb uninstall com.doubleangels.redact`; the profile generator installs a differently signed variant), then `./gradlew :app:installDebug`.
4. Make the media with `make_test_media.py "$S/media"`, push it to `/sdcard/DCIM/Camera/`, and broadcast `android.intent.action.MEDIA_SCANNER_SCAN_FILE` for each file. Also push four copies of `VID_long.mp4` (`VID_long2.mp4`…`VID_long5.mp4`) for the overlap test.

## 3. Features

Test each item, note PASS or FAIL with a one-line reason, and keep the evidence (node lines, file names, checker output).

**First launch.** Launch `com.doubleangels.redact/.MainActivity`. The media access prompt and then the notification prompt appear; allow both. Then the "Enable Crash Reporting" dialog (the same one the Send Crash Reports switch shows) appears: check it offers only Enable and Not Now and cannot be dismissed by tapping outside it or pressing Back, then tap Not Now. A snackbar says Redact won't ask again and points to Settings › Diagnostics & Feedback, `redact_prefs.xml` has `crash_reporting_prompted` set to `true` and `crash_reporting_enabled` is not `true`, and the dialog must not appear again for the rest of the run, even after Reset All Settings. The Clean tab shows its empty state.

**Settings defaults.** In Settings, Strict Clean, Warn About Already-Clean Files and Clear Cache on Startup are on; everything else is off; output folders show `Pictures/Redact` and `Movies/Redact`. Turn on Allow Notifications with all three sub-switches, and Keep Original File Names.

**Clean, several files.** On Clean, tap Select Files. The Files picker opens; browse to DCIM › Camera (use the list view), long-press the first file and tap the others to select `IMG_20260117_093012.jpg`, `IMG_beach.jpg`, `IMG_xmp.jpg`, `BIG_panorama.jpg` and `VID_20260117_100000.mp4`, then tap Select. Tap Clean Metadata.
- The status text reports the count, fields removed and files with location; a "Cleaning Finished" notification is posted (`dumpsys notification --noredact`).
- The outputs in `Pictures/Redact` and `Movies/Redact` keep the original names; pull them and run `check_media.py`: all `ok`.
- Share appears; Scan Result does not (several files).

**Clean, one file.** Clear, select `IMG_beach.jpg` again and clean. It is saved as `IMG_beach (1).jpg`. Scan Result appears; tapping it opens Scan on that file with no Location badge and no GPS, Make, Model or Software fields.

**Already clean.** Select a file from `Pictures/Redact` and tap Clean Metadata: the "Files Look Clean Already" dialog appears. Cancel.

**Keep Original File Names off.** Turn it off and clean `IMG_20260117_093012.jpg`: the output gets a random 12-character name.

**Video paths.** Clean `VID_vp9.webm` with Strict Clean on (full re-encode), then with Strict Clean off and Faster Video Cleaning on (quick transmux). Both outputs pass `check_media.py`, play to their full length (`ffprobe` duration), and have an extension and MediaStore `mime_type` that match the container.

**Scan.** On Scan, select `IMG_20260117_093012.jpg` from DCIM/Camera: Location badge, GPS, Make, Model and Software fields shown. Search filters the fields. Copy puts a value on the clipboard.

**Scan with Redact.** `adb shell am start -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM content://media/external/images/media/<id> --grant-read-uri-permission -n com.doubleangels.redact/.ScanShareTarget` with an original's id opens it in Scan uncleaned. With the id of a file in `Pictures/Redact`, nothing is opened.

**Share to Redact.** Send an original to `.ShareHandlerActivity` the same way. The system share sheet opens with the cleaned copy, and neither Redact nor Scan with Redact is offered in it. While it is open, `cache/processed` holds one randomly named file; pull it (`adb exec-out run-as com.doubleangels.redact cat cache/processed/<name>`) and check it: `ok`. No other copy of the original is left in `cache/`. Back out. Repeat with `android.intent.action.SEND_MULTIPLE` (`--eua android.intent.extra.STREAM <uri>,<uri>`) for a photo and a video. If `/sdcard/Download` holds a real phone photo (for example a `PXL_*.jpg`), share that too, since real photos carry makernotes and Extended XMP the fixtures do not.

**Foreground service.** Select `VID_long2.mp4`…`VID_long5.mp4` on Clean and start cleaning. Within a few seconds share a photo to Redact, then back out of its share sheet. Throughout, `dumpsys activity services com.doubleangels.redact` shows `isForeground=true` and the progress notification stays, until the clean finishes; afterwards the service is gone.

**Convert.** On Convert, select `convert_me.png`, `BIG_panorama.jpg`, `VID_20260117_100000.mp4` and `VID_vp9.webm`. For each of the four format chips (JPEG · MP4 (H.264), PNG · MP4 (H.265), WebP · MP4 (VP9), HEIC · MP4 (AV1)), convert and check:
- each output's extension, MediaStore `mime_type` and real container (`ffprobe` format name, `Image.open().format`) agree, and the codec is the one the chip names (skip a codec the emulator cannot encode, and say so);
- `check_media.py` passes on every output;
- the 16000×8000 JPEG converts without a crash or ANR.

**Output folders.** In Settings, Open Folder for Images and Videos opens the Files app on the right folder (Pictures or Movies when the Redact folder does not exist yet). Choose Folder for Images, browse to DCIM › Camera, tap Use This Folder and Allow: "Save videos here too?" appears; tap Use for Both, and both tiles show `DCIM/Camera`. Open Folder now opens DCIM › Camera.

**Delete originals.** With the output folder on DCIM/Camera and Keep Original File Names on, turn on Delete Originals After Cleaning and clean `IMG_beach.jpg` from DCIM/Camera (choose Clean Anyway if warned). The system "move this photo to trash?" prompt appears; Allow. The original is gone and the clean copy is renamed from `IMG_beach (1).jpg` to `IMG_beach.jpg` and passes `check_media.py`.

**Privacy settings.** Turn on Hide Content in Screenshots and Recents: `adb exec-out screencap -p` of Redact is now black. Turn it off again.

**Reset All Settings.** In its own card just above About, Reset All Settings, then Reset. `shared_prefs/redact_prefs.xml` holds only `initial_permissions_prompted` and any `review_*` keys (the rating prompt's progress survives a reset), every switch shows its default, and both folders are back to `Pictures/Redact` and `Movies/Redact`.

**Rating prompt.** The "Rate Redact" dialog appears once, on Clean or Convert, when a job that saved every file brings the count of such jobs to three (shares into Redact count but never show it, and Clean skips it while Delete Originals After Cleaning is on). The first time it appears during the sections above, check it offers only Yes and No and cannot be dismissed by tapping outside it or pressing Back, then tap No: a snackbar says Redact won't ask again and points to Settings › About, and `redact_prefs.xml` has `review_state` set to `2`. It must not appear again for the rest of the run. Settings › About › Rate Redact on Google Play opens the Play Store listing (or a browser on an emulator without Play); press Back.

**Quick Settings tile.** Leave the app on another tab and go home. `adb shell cmd statusbar add-tile com.doubleangels.redact/.CleanTileService`, then `cmd statusbar click-tile com.doubleangels.redact/.CleanTileService`: Redact opens on the Clean tab. Collapse the shade with `cmd statusbar collapse`.

**Cache cleanup.** Force-stop and relaunch the app; `cache/` holds no original or processed media older than the current session (Glide thumbnails are fine).

## 4. Summary

Finish with:

1. **Unit tests:** total, passed, skipped, failed (each failure named), and coverage if measured.
2. **Features:** a table with one row per feature above: PASS, FAIL or SKIPPED, and a short note. Every FAIL gets the evidence (node text, checker output, logcat excerpt) and, if you can tell, the likely cause in the code with a `file:line` reference.
3. **Crashes and ANRs:** any seen, with the stack trace's first app frame.
4. **Left behind:** the emulator stays running; test media stays in DCIM/Camera and outputs in Pictures/Redact and Movies/Redact.

Offer to fix the failures, but do not change code without being asked.
