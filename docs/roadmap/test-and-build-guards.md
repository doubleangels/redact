# Test and Build Guards roadmap

Ten additions to the tests and the build that catch a privacy regression before it ships. None of them changes the APK, adds a permission or sends anything. They make the existing promises (a cleaned file is clean, nothing leaves the phone, Sentry gets only what the tables list) checked by machines instead of by memory.

| | |
|---|---|
| **Status** | Planned |
| **Branch** | `dev` |
| **Size** | Six small, three medium, one large. About three weeks in all |
| **Depends on** | Nothing outside this roadmap. The order below lists what each item needs from the others |

## Contents

1. [Overview](#overview)
2. [1. Lock the dependency supply chain](#1-lock-the-dependency-supply-chain)
3. [2. dependency-guard](#2-dependency-guard)
4. [3. An independent metadata reader in the unit tests](#3-an-independent-metadata-reader-in-the-unit-tests)
5. [4. The independent reader on the device](#4-the-independent-reader-on-the-device)
6. [5. Run the real Sentry SDK in a test](#5-run-the-real-sentry-sdk-in-a-test)
7. [6. ArchUnit rules](#6-archunit-rules)
8. [7. Error Prone and NullAway](#7-error-prone-and-nullaway)
9. [8. Dependency Analysis plugin](#8-dependency-analysis-plugin)
10. [9. Mutation testing with PIT](#9-mutation-testing-with-pit)
11. [10. UiAutomator journeys](#10-uiautomator-journeys)
12. [Not included](#not-included)

## Overview

Built in this order. Items 1 and 2 go first so that every library the later items add is already pinned and listed. Item 4 reuses item 3's helper. Item 9 goes after 3, 5 and 6, because those add tests that kill mutants PIT would otherwise report.

| # | Item | Size | Ships in | Depends on |
|---|---|---|---|---|
| 1 | Dependency verification and dependency submission | Small | Build, CI | Nothing |
| 2 | dependency-guard | Small | Build, CI | Item 1 |
| 3 | metadata-extractor and exiftool as a second opinion | Medium | Unit tests, `check_media.py` | Item 1 |
| 4 | metadata-extractor in the on-device tests | Small | `androidTest` | Item 3 |
| 5 | Real Sentry SDK test | Medium | Unit tests | Nothing |
| 6 | ArchUnit rules | Small | Unit tests | Item 1 |
| 7 | Error Prone and NullAway, retiring `unused_imports.py` | Medium | Compile | Item 1 |
| 8 | Dependency Analysis plugin, retiring `unused_dependencies.py` | Small | Build | Item 1 |
| 9 | PIT mutation testing on the cleaning code | Medium | On demand | Items 3, 5, 6 |
| 10 | UiAutomator journeys for `/test` | Large | `androidTest` | Nothing |

Rules every item follows, from `CLAUDE.md`.

- Test and build only. Nothing goes into `implementation`, so `check_release_manifest.py` and the release classpath stay as they are. Item 2 proves that.
- No check is removed or loosened to make a new tool pass. Where a new tool finds a real gap, the gap is fixed, and where it finds a false positive, it is suppressed in place with a comment saying why.
- Nothing here changes what Sentry receives, so the `crash-reports` and `sentry-names` tables, Section 6 of `legal/redact.html` and the consent strings stay as they are.
- Every library added lands in `gradle/libs.versions.toml` and in the verification metadata from item 1, and `/dependencies` learns to judge it.

## 1. Lock the dependency supply chain

A library upgrade that quietly starts reading files or sending data is a bigger realistic risk than Redact's own code. Two gaps today.

- Gradle trusts whatever jar the repository serves for a version. A re-published or tampered artifact would go into the next release unnoticed.
- `.github/dependabot.yml` covers GitHub Actions only, so a published vulnerability in Glide, Sentry, Media3 or Material raises no alert. `/dependencies` finds upgrades, but only when someone runs it.

### Phase 1. Dependency verification

- Run `./gradlew --write-verification-metadata sha256 help :app:bundleRelease :app:testDebugUnitTest :app:lintRelease` so the file covers every configuration CI resolves, including lint and the Robolectric runtime. Commit `gradle/verification-metadata.xml`.
- Check that Robolectric's `android-all` jars, which it downloads at test time outside Gradle, are not expected in the file. They are fetched by Robolectric itself, so they stay as they are.
- Add a paragraph to `.claude/commands/dependencies.md`: after an upgrade, regenerate the file with the same command and review the diff. Only the upgraded artifacts and their new transitives should change.

### Phase 2. Dependency submission

- Add a `dependency-submission` job to `test-dev.yml` using `gradle/actions/dependency-submission`, SHA-pinned like the other actions, with `contents: write` for that job only. It submits the resolved `releaseRuntimeClasspath` to GitHub's dependency graph, which turns on Dependabot alerts for Gradle libraries without opening version PRs.
- Leave `dependabot.yml` as it is. Version PRs for Gradle stay with `/dependencies`.

### Done when

- A changed checksum for any artifact fails `./gradlew` locally and in CI.
- The repository's Security tab lists Gradle dependencies under the dependency graph.

### Risks

| Risk | Mitigation |
|---|---|
| Android Studio sync or a new Gradle task resolves a configuration the file doesn't cover and fails | Regenerate with that task added. The failure message names the missing artifact |
| The Gradle cache restored in CI holds an artifact that predates the file | Verification checks the cached file too, so a bad cache fails instead of passing |
| `contents: write` on a job that runs for pull requests from forks | Run the job only on `push` to `dev`, not on `pull_request` |

### Open decisions

- PGP signatures as well as checksums? Proposed: checksums only. Many AndroidX artifacts are signed, many others aren't, and a partial trust list adds upkeep without closing the gap.

## 2. dependency-guard

`check_release_manifest.py` catches a library that adds a permission or exported component. Nothing catches a library that adds code. `/dependencies` asks for a manual `grep io.sentry` on `releaseRuntimeClasspath` after a Sentry upgrade, and only for Sentry.

### Phase 1. Baseline

- Apply `com.dropbox.dependency-guard` to `:app` and guard `releaseRuntimeClasspath`.
- Run `./gradlew :app:dependencyGuardBaseline` and commit `app/dependencies/releaseRuntimeClasspath.txt`.
- Check the baseline has no `io.sentry:sentry-android-replay`. Add a `baselineMap` or allow rule that fails on any artifact matching `sentry-android-replay`, so the `exclude` in `app/build.gradle` is enforced, not just written.

### Phase 2. CI and commands

- Add `./gradlew :app:dependencyGuard` to the `build` job in `test-dev.yml`, beside `check_release_manifest.py`.
- In `dependencies.md`, replace the manual `grep io.sentry` step with: run `dependencyGuard`, read the diff, and for every new artifact say what it is and why it came in. Rebaseline only after that.
- In `cleanup.md` and `prerelease.md`, replace "compare the `releaseRuntimeClasspath` before and after" with the same check.

### Done when

- Adding any library to `implementation`, or upgrading one that brings a new transitive, fails CI until the baseline is updated in the same commit.

### Risks

| Risk | Mitigation |
|---|---|
| Version-only bumps of transitives make every upgrade touch the baseline | That is the point. The diff is small and is read once per upgrade |
| The plugin lags AGP 9.5 or the configuration cache | Check in Phase 1. If it fails, a 30-line Gradle task that writes the sorted resolved coordinates does the same job |

## 3. An independent metadata reader in the unit tests

`CleanVerifier` reuses the parsers the cleaners use (`ImageContainerMetadata`, `VideoContainerMetadata`, `VideoStreamMetadata`, `ExifBlock`). A box or segment the parser doesn't recognise is skipped by the cleaner and passed by the verifier alike. `ParserFuzzTest`'s "never comes out dirty" check uses the same scanners. Outside the app, `check_media.py` has only Pillow and ffprobe, which don't read maker notes, unknown EXIF tags, XMP in `uuid` boxes or vendor trailers.

### Phase 1. A second-opinion helper

- Add `com.drewnoakes:metadata-extractor` as `testImplementation`. Pure Java, so it runs in the JVM unit tests without Robolectric.
- Write `IndependentScan` in `src/test/.../metadata/`. Given a file, it returns every directory and tag metadata-extractor finds, grouped into "identifying" (GPS, dates and times, make and model, serials, owner, software, maker notes, XMP, IPTC, ICC description and copyright, QuickTime user data and location keys) and "structural" (dimensions, orientation, colour space, codec).
- Keep the identifying list in one place, next to the helper, with a comment pointing at `CleanVerifier` so the two lists are reviewed together.

### Phase 2. Tests over every fixture

- `IndependentCleanCheckTest`: for every file in `src/test/resources/image` and `video`, and every seed `ParserFuzzTest` builds, clean it with each Clean preset, then fail if `IndependentScan` finds any identifying tag the preset doesn't keep.
- Run the same check on the first 20 mutated inputs per seed in `ParserFuzzTest` that come out as a successful clean.
- Where metadata-extractor finds something `CleanVerifier` missed, fix the cleaner and verifier first, with a regression test. Do not add the tag to an ignore list unless metadata-extractor is provably wrong.

### Phase 3. exiftool in `check_media.py`

- When `exiftool` is on `PATH`, run `exiftool -a -u -G1 -j` on each file and fail on any tag in the same identifying groups. When it isn't, print "exiftool not found, second opinion skipped" and exit 0 for that check, as the script does for a missing `pillow-heif`.
- Note the install (`winget install exiftool` on Windows, `apt install libimage-exiftool-perl` on Linux) in the script's docstring and in `verifyclean.md`.

### Done when

- Every unit-test fixture passes both `CleanVerifier` and `IndependentScan` after every preset.
- `/verifyclean` reports exiftool's verdict for every file.

### Risks

| Risk | Mitigation |
|---|---|
| metadata-extractor reports tags Redact keeps on purpose (orientation, colour profile, the kept-precision date) | Each preset has a keep list. The check reads it from `CleanPresets` instead of copying it |
| It misreads a malformed file the cleaner rewrote correctly | Compare with exiftool on that file before suppressing, and record the file in the suppression comment |
| A real gap appears late in a release cycle | Land Phase 2 right after a release, not during `/prerelease` |

## 4. The independent reader on the device

Only `CleanOnDeviceTest` and `CensorOnDeviceTest` clean with real codecs. They judge the output with `ExifInterface`, the same library `ImageCleaner` uses to write, so a tag it can't see is invisible to both.

### Phase 1

- Add metadata-extractor as `androidTestImplementation`. It runs on Android as is.
- Move `IndependentScan` from item 3 to a small shared source set (`src/sharedTest/java`, added to both `test` and `androidTest` in `app/build.gradle`), so both use the same identifying list.
- In `assertCleanImage` and `assertCleanVideo`, call it after the existing checks. Keep every existing `ExifInterface` assertion.

### Phase 2

- Add the real phone photo case from `/test` (makernotes, Extended XMP, Ultra HDR gain map, EXIF thumbnail) as a fixture made by `make_instrumented_media.py`, since none of the seven current assets carries all four.

### Done when

- `ANDROID_SERIAL=<test emulator> ./gradlew :app:connectedDebugAndroidTest` passes with the second opinion on every output, video included.

### Risks

| Risk | Mitigation |
|---|---|
| The goldfish video decoders on the managed emulator, already a known failure | No change. The video tests keep running on the `Pixel_10_Pro` AVD before a release, as `CLAUDE.md` says |

## 5. Run the real Sentry SDK in a test

`SentryPrivacyScrubberTest` and `SentryPrivacyScrubberPayloadsTest` call the scrubber directly with built events. Nothing runs the configured SDK and reads what it would put on the wire. A Sentry upgrade that adds a field in `DefaultAndroidEventProcessor` or a new context passes every test, which is exactly the risk `dependencies.md` asks a person to catch by reading the changelog.

### Phase 1. Make the configuration testable

- Move the body of the `SentryAndroid.init` lambda in `SentryInitializer.runSentryInit` into `@VisibleForTesting static void configure(SentryAndroidOptions options, String dsn)`. `runSentryInit` calls it, unchanged in behaviour. Every `before*` callback and event processor stays where it is.

### Phase 2. A capturing transport

- `SentryWireTest` (Robolectric, SDK 34): start `SentryAndroid.init` with `configure(...)`, a dummy DSN, `SentryManager` enabled, and `options.setTransportFactory(...)` returning a transport that keeps each envelope in memory. No network is opened, and the test asserts that too by checking no other transport ran.
- Plant `content://media/external/images/media/42`, `/storage/emulated/0/DCIM/IMG_2024.jpg`, `40.7128,-74.0060` and a typed sentence into a crash message, a breadcrumb, a tag value, a metric attribute, a log, a transaction name and a user feedback message.
- Serialize each captured envelope with `options.getSerializer()` and assert the JSON contains none of the planted values, and none of `boot_time`, `timezone`, `kernel_version`, `installation`, `"user"`, `ip_address`, `"id"` under `device`, or a `screenshot` or `replay` item.
- Assert the envelope does contain what Section 6 says is sent (release, environment, the `outcome` tag, a breadcrumb category), so the test can't pass by sending nothing.

### Phase 3. Upgrade guard

- Add to `dependencies.md`: after a Sentry upgrade, `SentryWireTest` must pass before the changelog is read, and every new top-level key it prints in its failure message is judged against `CLAUDE.md`'s limits.

### Done when

- Removing any single scrub call from `SentryInitializer` makes `SentryWireTest` fail.

### Risks

| Risk | Mitigation |
|---|---|
| `SentryAndroid.init` touches APIs Robolectric doesn't shadow | Spike Phase 2 first. Fall back to `Sentry.init` with `SentryAndroidOptions` and the Android event processors added by hand, and say in the test which integrations that skips |
| Envelopes are sent on a background thread | Call `Sentry.flush(timeout)` before reading, and fail on timeout |
| The test leaves the global hub started for later tests | `Sentry.close()` in `@After`, as `SentryInitializerTest` does |

## 6. ArchUnit rules

Several privacy rules in `CLAUDE.md` live only in prose and in reviews. ArchUnit (`com.tngtech.archunit:archunit-junit4`, `testImplementation`) checks them against the compiled classes.

### Phase 1. Rules for what is true today

`ArchitectureRulesTest`, one rule per test so a failure names the rule.

- Only classes in `sentry` call static methods on `io.sentry.Sentry`. Today that is `SentryManager` and `SentryInitializer`.
- Only `UpdateChecker` and `SentryManager` depend on `java.net`, and `SentryManager` only on exception types (`SocketException`, `SocketTimeoutException`, `UnknownHostException`). A new network path fails the test.
- Nothing outside `sentry` depends on `SentryPrivacyScrubber` or on `io.sentry.Sentry`, so everything reports through `SentryManager`.
- `metadata` doesn't depend on `android.view`, `android.widget`, `Fragment`, `Activity` or AppCompat classes. It passes today. `media` is left out for now: seven of its classes import from those packages (the two adapters, `MediaSelector`, `OutputDestination`, `FormatConverter`, `ProcessingResourceWarnings`, `VideoMedia3Converter`), some for things like `Surface` that aren't UI. Sort those first, then extend the rule.

### Phase 2. The `File.delete()` audit

Seven direct `File.delete()` calls in `metadata` bypass `SecureDelete`. `UltraHdr` (lines 364, 384, 501), `ImageContainerMetadata` (1291) and `HeifContainerMetadata` (377, 569). Some of these may hold bytes from the original file.

- For each one, decide whether the file can hold original media. If it can, switch it to `SecureDelete`. If it can't (it only ever holds cleaned output), leave it and add it by method name to the rule's allowlist with the reason.
- Then add the rule that `media` and `metadata` call `java.io.File.delete()` only from `SecureDelete` and that allowlist.

### Done when

- Each rule fails on a deliberate violation in a scratch commit and passes on `dev`.
- The audit result for all seven calls is in the commit message.

### Open decisions

- Should the source-text tests (`MetricsGoThroughAppMetricsTest`, `AllowedTagKeysAreUsedTest`, parts of `SentryNamingTest`) move to ArchUnit? Bytecode can't be fooled by a static import or a fully qualified name. Proposed: later, one at a time, keeping the old test until the new one has caught the same deliberate violation.

## 7. Error Prone and NullAway

The main code has about 2,100 `@NonNull` and 600 `@Nullable` annotations that nothing enforces. Error Prone also catches ignored return values, which matters around `File.delete()` and `SecureDelete`, and unused imports, which `unused_imports.py` finds with a regex that counts a mention in a comment as a use.

### Phase 1. Error Prone, warnings only

- Apply `net.ltgt.errorprone` to `:app` and add `com.google.errorprone:error_prone_core` to the `errorprone` configuration. Check it supports JDK 27, which CI uses (`setup-android-build`), and add the `--add-exports` flags it documents.
- Exclude generated code (`build/generated`, view binding, Glide's generated API) with `disableWarningsInGeneratedCode` and `excludedPaths`.
- Run once, triage, and fix every real finding. Turn on as errors the checks that guard privacy: `ReturnValueIgnored`, `CheckReturnValue`, `MustBeClosedChecker`, `StreamResourceLeak`, `UnusedVariable`.

### Phase 2. NullAway

- Add `com.uber.nullaway:nullaway`, scoped to `com.doubleangels.redact`, reading the AndroidX annotations Redact already uses.
- Start with `metadata`, then `media`, then the rest, one package per commit. Fix real null paths. A path that would NPE mid-clean must end in the existing fail-closed result, never in a skipped check.
- Then make NullAway an error for all of `com.doubleangels.redact`.

### Phase 3. Retire `unused_imports.py`

- Turn on `RemoveUnusedImports` as an error.
- Remove `unused_imports.py`, and the steps that run it in `cleanup.md` (lines 32, 88 and 103) and `prerelease.md` (line 64), in the same commit.

### Done when

- `compileDebugJavaWithJavac` and `compileReleaseJavaWithJavac` pass with NullAway and the privacy checks as errors.
- No build time regression over 20 percent on a clean build. Measure before and after.

### Risks

| Risk | Mitigation |
|---|---|
| Hundreds of findings at once | One package per commit, warnings first, and suppressions only with a reason |
| JDK 27 is newer than Error Prone supports | Check in Phase 1. If unsupported, set the compile toolchain to 21 for the Error Prone run only, or wait for a release |
| Annotation processing for Glide conflicts | Glide's processor runs in the same javac. Test `bundleRelease` after Phase 1 |

## 8. Dependency Analysis plugin

`unused_dependencies.py` counts source files that name a library's packages. Its own docstring says it can't see use through themes, the manifest, annotation processors or reflection. The Dependency Analysis Gradle Plugin (`com.autonomousapps.dependency-analysis`) reads bytecode, resources and manifests, and also reports libraries the code uses but only gets transitively.

### Phase 1

- Apply it at the root. Check AGP 9.5 and configuration cache support first.
- Run `./gradlew buildHealth` and compare with `unused_dependencies.py`. For each disagreement, find out which one is right.
- Fix real findings. Declare transitive libraries the code uses directly (for example, `androidx.core` if it is only there through AppCompat), since an upgrade elsewhere could drop them.

### Phase 2

- Replace the `unused_dependencies.py` steps in `cleanup.md` (lines 36 and 103) and `prerelease.md` (line 64) with `buildHealth`, and remove the script.
- Keep the version catalog check (entries no build file uses), which the plugin doesn't do. Move that part to a five-line Gradle task or keep it as a smaller script.

### Done when

- `buildHealth` reports nothing on `dev`, and `cleanup.md` runs it.

### Risks

| Risk | Mitigation |
|---|---|
| It suggests removing a library used only by the release `exclude` or the baseline profile | Every removal still goes through dependency-guard (item 2) and `bundleRelease` |

## 9. Mutation testing with PIT

The 90% line gate proves a line ran, not that a test fails when a check is weakened. PIT changes the compiled code (negates conditions, removes calls, returns null or false) and lists every change no test caught. That is a direct test of `CLAUDE.md`'s first privacy rule.

### Phase 1. Spike

- Try `pl.droidsonroids.pitest` with AGP 9.5 on `CleanVerifier` alone. If it doesn't support AGP 9, run PIT through its command-line runner against the `testDebugUnitTest` classpath, written as a Gradle task.
- Measure the run time. Robolectric start-up per mutant is the cost.

### Phase 2. Scope and command

- Target `CleanVerifier`, `VideoStreamMetadata`, `VideoContainerMetadata`, `ImageContainerMetadata`, `HeifContainerMetadata`, `GifContainerMetadata`, `ExifBlock`, `SecureDelete` and `SentryPrivacyScrubber`. Point PIT at the tests for those classes only, with `targetTests`.
- Add `/mutation` in `.claude/commands/mutation.md`. It runs PIT on the classes changed since the last release, or on a class I name, lists surviving mutants in the cleaning code, and writes tests that kill them. It never runs in CI.

### Phase 3. First pass

- Run it on all the target classes once. Every surviving mutant in a check either gets a killing test or a written reason it is equivalent (the change doesn't alter behaviour).

### Done when

- No surviving mutant in `CleanVerifier` or `SentryPrivacyScrubber` without a test or a written reason.

### Risks

| Risk | Mitigation |
|---|---|
| A full run takes hours | Incremental analysis (`withHistory`), the narrow scope and on-demand only |
| Equivalent mutants waste time | Record each one in a comment beside the line, so the next run's reader skips it |

## 10. UiAutomator journeys

`/test` is driven by hand through `ui.sh` taps, so each run differs and CI can't run any of it. Some checks can't be done at all. `test.md` marks the Censor pinch-zoom check as manual because adb can't send two-finger gestures. UiAutomator is already in `baselineprofile`, and `Profiles` already writes journeys with it.

### Phase 1. Harness

- Add `androidx.test.uiautomator:uiautomator` to `app`'s `androidTestImplementation`, sharing the catalog entry with `baselineprofile`.
- Write `Journeys` helpers: launch on a clean install, push fixtures into `DCIM/Camera` through MediaStore, grant or revoke permissions with `UiAutomation`, and wait for a result with `Until`.
- Use the Photo Picker through UiAutomator, since that is how a user picks files.

### Phase 2. One journey per section of `test.md`

In this order, by how often each breaks.

1. Clean one photo, keep nothing, and run `IndependentScan` (item 4) on the output.
2. Clean a batch with the overlap videos, and cancel half way.
3. Share into Redact from another app with `ACTION_SEND` and `ACTION_SEND_MULTIPLE`.
4. Scan with and without the location permission.
5. Convert each target format.
6. Censor with Black, Pixelate and Blur, including the pinch-zoom check, using `UiObject2.pinchOpen`.
7. Settings, the Quick Settings tiles and the What's New screen.

### Phase 3. Fold into `/test`

- `/test` runs the journeys first and drives by hand only what they don't cover yet. `test.md` lists which sections each journey replaces.
- CI still doesn't run them, for the same goldfish video decoder reason as `CleanOnDeviceTest`. The photo-only journeys can run on the managed `Pixel_10_Pro_Clean` device locally.

### Done when

- Every section of `test.md` either has a journey or a written reason it stays manual.

### Risks

| Risk | Mitigation |
|---|---|
| Flaky waits on a slow emulator | `Until` with generous timeouts and no fixed sleeps. A journey that fails once and passes on retry is a bug to fix, not a retry to add |
| Photo Picker UI changes between Android builds | Select by resource ID through one helper, so a change is fixed in one place |
| Large effort for a solo project | Phase 2 is ordered so the first three journeys cover most past regressions. Stop there if the rest isn't worth it |

## Not included

From the same review, judged not worth adding.

- **Mockito.** The tests use fakes (`FakeDocumentsProvider`, `FakeMediaStoreProvider`) and check real bytes. Mocks would check calls, which is weaker for this app.
- **androidx.security-crypto or Tink.** security-crypto is deprecated and Redact stores no secrets.
- **Certificate pinning for the update check.** GitHub rotates certificates, and the request carries nothing private.
- **Timber.** R8 strips `Log` calls in release. Through Timber, the arguments would still be built at each call site.
- **Jazzer or jqwik.** Both need JUnit 5, and Robolectric runs on JUnit 4. Revisit if the byte walkers stop importing `android.*`.
- **Espresso under Robolectric.** Its idling doesn't cover Redact's own executors, so `TestWaits` would stay.
