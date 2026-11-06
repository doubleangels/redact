---
description: Clean every kind of test photo and video on the Pixel 10 Pro emulator and prove no identifying metadata is left, in minutes instead of a full /test
allowed-tools: Bash, PowerShell, Read, Grep, Glob
argument-hint: "[fast]"
---

> These commands drive the Pixel_10_Pro emulator and Gradle with Windows paths, so they only work on my own machine. If you are running in a cloud session without the Android SDK and the emulator, say so and stop instead of trying.

Prove that the current code still removes identifying metadata from every format it handles, on the **Pixel_10_Pro emulator and never on a physical device**. This is the quick check to run after touching `MetadataStripper`, `MediaProcessor`, `FormatConverter`, the HEIF/JPEG/MP4 container code or a media library; `/test` does the full app. Do not change source code, commit or push. If a file fails, record the evidence and keep going.

If I passed `fast`, also run the video files a second time with Strict Clean off and Faster Video Cleaning on (the quick transmux path).

Work from Git Bash. Use the scratchpad directory for everything you create; call it `$S` below. Use `.claude/scripts/ui.sh adb …` (or `MSYS_NO_PATHCONV=1`) for adb commands with device paths. Run the Python scripts with `python3` (plain `python` has no Pillow), without `-I`.

## 1. Emulator and app

1. Run `adb devices -l` and pick the running emulator whose `adb -s <serial> emu avd name` is `Pixel_10_Pro`; ignore physical devices. If none is running, start the **test emulator** headless, since these checks drive it through adb and never need its window: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd Pixel_10_Pro -no-window -no-audio -no-boot-anim -no-snapshot-save` in the background, and wait until `getprop sys.boot_completed` is `1`. Reuse it as it is if it is already running, with or without a window. Ignore any other emulator: the profile generator's Gradle-managed one (`Pixel_10_Pro_Profiles`) may be running alongside. If the AVD does not exist, stop and tell me.
2. Set `ANDROID_SERIAL=<serial>` in every command from here on, including Gradle.
3. `adb uninstall com.doubleangels.redact`, so no setting from an earlier run carries over, then `./gradlew :app:installDebug -q`.
4. Skip the introduction and prompts the way `fresh_app` in `scripts/screenshots.sh` does: `pm grant` POST_NOTIFICATIONS, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED and ACCESS_MEDIA_LOCATION, and write `shared_prefs/redact_prefs.xml` through `run-as` with `initial_permissions_prompted` and `onboarding_seen` true, `review_state` 2 and `crash_reporting_prompted` true. Every other setting stays at its default (Strict Clean on).
5. `adb logcat -c`.

## 2. Media

1. `python3 .claude/scripts/make_test_media.py "$S/media"`. Its docstring lists the files: JPEGs with EXIF GPS and XMP, a 16000×8000 JPEG, a PNG with an EXIF chunk, an H.264 MP4 with a location atom, and a VP9 WebM.
2. Add a HEIC: if `/sdcard/Download` or `/sdcard/DCIM` on the emulator holds a real `.heic` (or a `PXL_*.jpg`, which carries makernotes and Extended XMP), include it. Otherwise make one with `ffmpeg -i "$S/media/IMG_beach.jpg" -c:v libx265 -tag:v hvc1 "$S/media/IMG_heic.heic"` only if your ffmpeg can write HEIF, and say if no HEIC could be tested.
3. Push everything to `/sdcard/DCIM/Camera/` and broadcast `android.intent.action.MEDIA_SCANNER_SCAN_FILE` for each file. Before cleaning, run `check_media.py` on the originals: each must FAIL, or the fixture proves nothing.

## 3. Clean each file

Use the share path, which runs the same clean as the Clean tab and leaves the result where it can be pulled:

1. Find the file's MediaStore id: `ui.sh adb shell "content query --uri content://media/external/file --projection _id:_display_name --where \"_display_name='NAME'\""`.
2. `ui.sh adb shell am start -a android.intent.action.SEND -t <mime> --eu android.intent.extra.STREAM content://media/external/<images|video>/media/<id> --grant-read-uri-permission -n com.doubleangels.redact/.ShareHandlerActivity`.
3. Wait (poll every second, up to 2 minutes for the long video) until the system share sheet is showing and `run-as com.doubleangels.redact ls cache/processed` lists one file. Pull it with `adb exec-out run-as com.doubleangels.redact cat cache/processed/<name> > "$S/out/<original name>.<ext>"`, keeping the extension the app gave it.
4. Press Back to close the share sheet, and confirm `cache/processed` empties.
5. After each file, check `adb logcat -d -b crash` and `logcat -d | grep -E "FATAL EXCEPTION|ANR in com.doubleangels"`. A crash or ANR fails that file.

For `fast`, set `strict_clean` false and the faster video option on in `redact_prefs.xml` (read `AppPreferences` for its key), force-stop the app, and repeat step 3 for the videos only.

## 4. Check the outputs

1. `python3 .claude/scripts/check_media.py "$S/out"`: every file must be `ok`. Orientation, ImageWidth, ImageLength and LightSource left as `0`, and picture-describing XMP (an Ultra HDR gain map, Motion Photo container directory), are allowed.
2. Each output still opens and looks like its original: `Image.open()` loads every image at the original size; `ffprobe` gives each video its original duration (±0.5 s), a video stream, and the audio stream if the original had one.
3. Each output's extension matches its real container (`Image.open().format`, `ffprobe` format name).

## 5. Report

A table with one row per file: format, path used (strict or fast), PASS or FAIL, and for a FAIL the `check_media.py` line or the error. Then any crash or ANR with its first app frame, which formats could not be tested (HEIC, an encoder the emulator lacks) and why, and a one-line verdict. Leave the emulator running.
