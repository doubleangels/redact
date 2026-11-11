# Redact

Android app (Java, minSdk 31, targetSdk 37) that removes metadata from photos and videos, converts formats and censors parts of photos, all on the phone. Its job is that private data never leaves a file or the phone, so a change that drops a check is a regression, never a simplification.

## Privacy rules

- Never weaken a check that a cleaned file is clean (`verifyMetadataRemoval`, `requireSameContent`, `requireVideoMetadataClean`, `VideoStreamMetadata.scan`, the container scans), even where it repeats work. Code that fails closed (returns null, throws or re-encodes when it cannot vouch for a file) stays that way.
- Keep `SecureDelete` on anything that may hold original media, the inbound URI checks in `MainActivity` and `ShareHandlerActivity`, `SentryPrivacyScrubber` and the `before*` callbacks in `SentryInitializer`.
- Everything reported to Sentry goes through `SentryManager`, and nothing sent to it may hold a URI, path, file name, coordinate, metadata value or typed text.

## Code

- Reuse the helpers before writing new ones: `MediaSizeLimits.copyStream`, `SecureDelete`, `MediaFileNames`, `MediaUriResolver`, `MediaStoreWrites`, `OutputDestination`, `SentryManager`.
- `MetadataStripper`, `MetadataDisplayer`, `CleanStats` and `AlreadyCleanCheck` find EXIF tags by reflection over `ExifInterface.TAG_*`, so grep alone does not show every use; `proguard-rules.pro` keeps those names.
- Every user-visible string goes into `values/` and all 12 locales (`ar de es fr hi it ja ko pt ru zh-rCN zh-rTW`); `StringTranslationsTest` checks it. The Play listing text in `fastlane/metadata/android/<locale>/` follows the English too.
- A setting that is removed leaves its saved value in place; do not add code to clear it.

## Build and test

- `./gradlew :app:testDebugUnitTest` (Robolectric), `:app:jacocoTestCoverageVerification` for the 90% line gate CI enforces, `:app:lintRelease`, and `:app:bundleRelease` to catch R8 problems the debug tests miss. From PowerShell use `gradlew.bat`.
- Never run two Gradle builds of this project at once: they fight over the same build locks. The daemon is on locally; CI turns it off.
- Python scripts that need Pillow (`.claude/scripts/make_test_media.py`, `check_media.py`) run with `python3`; plain `python` has no Pillow.

## Emulators

- **Test emulator:** the `Pixel_10_Pro` AVD (Play Store image, API 37.2). `/test` and `/verifyclean` boot it headless; `/generatescreenshots` uses its window. Never use a physical device.
- **Profile emulator:** `Pixel_10_Pro_Profiles`, Gradle-managed (Google APIs API 37.0, defined in `baselineprofile/build.gradle`). Gradle starts and stops it for `:app:generateBaselineProfile`. Its journeys are in `Profiles` (`startup` feeds the startup profile, `generate` the baseline profile).
- **Clean test emulator:** `Pixel_10_Pro_Clean`, Gradle-managed (Google APIs API 37.0, defined in `app/build.gradle`). `./gradlew :app:Pixel_10_Pro_CleanDebugAndroidTest` runs `CleanOnDeviceTest`, which cleans the fixtures in `app/src/androidTest/assets/media` (made by `.claude/scripts/make_instrumented_media.py`) with real codecs and checks them like `check_media.py`. The `device-test` job in `deploy.yml` runs it before every release.
- **Benchmark emulator:** `Pixel_10_Pro_Benchmark`, Gradle-managed (Automated Test Device image, API 37.0, stripped of most system apps), used only by `StartupBenchmark` through `/benchmark`.
- The managed emulators stay on 37.0: AGP 9.5's managed devices take only whole API levels, and no stripped-down image exists for 37.2. While running, `adb` shows them under a generated AVD name such as `dev37_google_apis_x86_64_Pixel_10_Pro`, so pick the test emulator by its exact AVD name, `Pixel_10_Pro`.
- Keep the profile generator class name (`Profiles`) and the managed device names short: they go into the paths of the profile files copied back from the emulator, and `adb` on Windows fails on paths over 260 characters. When every copy fails, the run finds no rules and deletes the committed profiles; restore them with `git checkout`.

## Commands

`/prerelease` runs the whole release check and calls the others: `/translate`, `/dependencies`, `/sentry`, `/sentrycoverage`, `/cleanup`, `/lint`, `/bump`, `/releasenotes`, `/test`, `/coverage`, `/generateprofiles`, `/generatescreenshots`. Also `/verifyclean` (quick emulator check after touching cleaning code), `/benchmark` (startup with and without the profile), `/generateall` and `/release`. Each one's file in `.claude/commands/` is the source of truth for how it works.

## Git

Work on `dev`; `main` is what ships, through `/release`. Commit to the current branch when a command says to, and never push unless asked. Leave `.idea/` files alone.
