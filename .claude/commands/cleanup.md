---
description: Find dead code, unused imports and dependencies, duplicated logic and code that can be simpler or faster, apply the safe changes, and verify with the tests, the coverage gate and lint
allowed-tools: Bash, PowerShell, Read, Edit, Write, Grep, Glob
argument-hint: "[report] [<file, class or package>]"
---

Make the app's code smaller, simpler and cheaper to run without changing what it does. If I passed a file, class or package, work only on that. If I passed `report`, stop after step 2 and change nothing. When the task is done, commit its changes to the current branch automatically, as the last step describes. Do not push.

Redact's job is to make sure private data never leaves a file or the phone, so a "simpler" version that drops a check is a regression, not a cleanup. Behavior, privacy guarantees and user-visible text stay exactly as they are.

## 1. Look for findings

Read the code, do not guess from names. Run `git status` first and leave alone any file I have uncommitted changes in, unless I named it; say which ones you skipped.

**Dead code**

- Private methods, fields, constants, parameters and local variables nothing uses. For a parameter, check every caller, and that it is not there to match an interface, a callback or an `@Override`.
- Package-private or public members with no caller in `app/src/main`. Grep for the name across `app/src/main` and `app/src/test`. One used only by tests is a finding only if it is not marked `@VisibleForTesting` and the test exists only to cover it.
- Unused resources (strings, drawables, layouts, dimens, colors, ids), and string keys present in `values-*` but not in `values/`. Run `./gradlew :app:lintRelease -q` and read `UnusedResources` and `ExtraTranslation` from `app/build/reports/lint-results-release.xml`. Lint does not report unused view ids by default, so grep for those.
- `SDK_INT` checks below `minSdk` (see `app/build.gradle`), branches that can never run, and settings, keys or migrations for things the app no longer has.
- Commented-out code.

Before calling anything dead, rule out these ways the app reaches code that grep alone does not show:

- **Reflection.** `MetadataStripper`, `MetadataDisplayer`, `CleanStats` and `AlreadyCleanCheck` walk `ExifInterface.TAG_*` fields, and `MetadataDisplayer` walks `MediaMetadataRetriever`'s fields. `proguard-rules.pro` keeps the EXIF names for that.
- **The manifest and XML.** Activities, services, tile services, the `FileProvider`, activity aliases, `@xml/shortcuts`, `@xml/file_paths` and anything named in a layout.
- **Annotations.** Glide's `@GlideModule` (`RedactGlideModule`), Sentry integrations, lifecycle callbacks and view binding (generated from layout ids).
- **`app/lint.xml`, the baseline profile generator** (`baselineprofile/`) and the `.claude/scripts` that drive the emulator by view id.

**Unused imports**

Android Lint does not report unused imports in Java, so run `python .claude/scripts/unused_imports.py` (pass a path to limit it to my scope). It lists every unused or duplicate import in `app/src` and `baselineprofile/src`, tests included, as `file:line`. An import counts as used if its name appears anywhere else in the file, comments included, so one kept for a Javadoc `{@link}` is never reported, and a reported one cannot be needed. Wildcard imports are left alone. These are always **Safe**, so list them as one finding per file with the count, not one per line.

**Unused dependencies**

1. Build once if the Gradle cache is empty (`./gradlew :app:assembleDebug -q`), then run `python .claude/scripts/unused_dependencies.py`. For each dependency in `app/build.gradle` and `baselineprofile/build.gradle`, it reads the library's own packages from its jar or aar and counts the source files that name them, in code or XML. It also lists `gradle/libs.versions.toml` entries (libraries, plugins, versions) no build file uses.
2. A count of 0 is a lead, not a verdict. Check each one by hand against the ways a library is used without being named in source:
   - **Annotation processors**, such as `glide-compiler`, which `RedactGlideModule`'s `@GlideModule` needs.
   - **Runtime only**, such as `leakcanary-android` (debug builds) and `profileinstaller`, which install themselves through the manifest.
   - **Resources only**, such as themes, styles and attributes from `material`, `appcompat` or `core-splashscreen` used in `res/` by name rather than by package. Grep `res/` for their style and attribute names.
   - **Umbrella libraries** with no classes of their own, which only pull in others (the script says so). One is unused when nothing needs what it pulls in, or when another declared library already brings the same thing. For example, `lifecycle-viewmodel-ktx` only adds Kotlin extensions, but removing it here drops every Lifecycle library from 2.11.0 to 2.10.0, because it is what pins the newer version, and it still ships through another library. Run `./gradlew :app:dependencies --configuration releaseRuntimeClasspath -q` to see what else brings a library in, and at what version, since removing one can quietly change the version of something it pinned.
   - **The Gradle plugins** in `build.gradle`'s `plugins` block, and `project(':baselineprofile')`, which the script does not judge.
3. A test-only library (`testImplementation`) is unused when no test names it. Check `app/src/test` for the `androidx.*.testing` and rule classes it provides.

Removing a dependency is **Needs care**. List each with the evidence that nothing needs it, including what `:app:dependencies` showed.

**Duplication and reuse**

- The same logic written two or more times: stream copying, SHA-256 hashing, `MediaExtractor` and `MediaMetadataRetriever` set-up and release, temp file creation and secure deletion, `ContentResolver.getType` with its `try`/`catch`, MIME and extension checks, progress and Sentry bookkeeping.
- A hand-written version of something an existing helper already does (`MediaSizeLimits.copyStream`, `SecureDelete`, `MediaFileNames`, `MediaUriResolver`, `MediaStoreWrites`, `OutputDestination`, `SentryManager`). Prefer calling the existing helper over adding a new one.
- Only extract a shared helper when the copies really mean the same thing and there are at least two of them. Two blocks that look alike but guard different cases stay separate.

**Simpler code**

- Long methods with one clear piece that can be named, deep nesting that an early return removes, flags that can be a local, `switch` chains that can be a table, and fully qualified names (`com.doubleangels.redact.metadata.CleanStats`, `android.net.Uri`) written inline where an import would do.
- Comments that no longer match the code next to them, including ones about settings or behavior that has changed.

**Efficiency**

- Disk, network or content-provider work on the main thread (`StrictMode` logs these in debug builds).
- The same file, stream or `ContentResolver` query read more than once when once would do, repeated `ExifInterface` or `MediaExtractor` opens of the same file, and work repeated in a loop that could be done once before it.
- Large buffers or `Bitmap`s allocated per item or per frame where one could be reused, a `Bitmap` not recycled on every path, and a `Cursor`, stream, `MediaExtractor`, `MediaMetadataRetriever` or `ParcelFileDescriptor` not closed on every path.

**Never change these, whatever looks redundant:**

- Any check that a cleaned file is clean (`verifyMetadataRemoval`, `requireSameContent`, `requireVideoMetadataClean`, `VideoStreamMetadata.scan`, the container scans), even where it repeats work an earlier step did. Checking the output separately from the code that wrote it is the point.
- Code that fails closed (returns null, throws or re-encodes when it cannot vouch for a file) must not be turned into code that fails open.
- `SecureDelete` calls on anything that may hold original media, the inbound URI checks in `MainActivity` and `ShareHandlerActivity`, `SentryPrivacyScrubber` and the `before*` callbacks in `SentryInitializer`.
- Public behavior: file names, saved formats, what a setting does, notification and dialog text.

If one of these looks wrong, list it as a question for me instead of changing it.

## 2. Report the findings

Show me a numbered list before changing anything, grouped as **Dead code**, **Unused imports**, **Unused dependencies**, **Duplication**, **Simpler code** and **Efficiency**. For each finding give:

- `file:line` and what it is,
- the change you propose, in one line,
- why it is safe (for dead code: how you know nothing reaches it),
- **Safe** (behavior cannot change, such as an unused private method or an import) or **Needs care** (shared helpers, anything in `metadata/`, `media/`, `sentry/` or the share and intent handling).

Leave out anything that is only a matter of taste. Say how many findings there are in each group. If I passed `report`, stop here.

## 3. Apply the changes

Apply the findings in the list, Safe ones first.

- Remove unused imports with `python .claude/scripts/unused_imports.py --fix` (with my scope's path if I gave one), then run it again without `--fix` to confirm it reports none.
- Remove an unused dependency from the module's `build.gradle`, then remove its entry from `gradle/libs.versions.toml` if nothing else uses it, and its `[versions]` key if that is now unused too. If it had its own rule in `app/proguard-rules.pro`, remove that as well. Remove its credit from `ui/OpenSourceLicenses.java` (`OpenSourceLicensesTest` checks that list against the build), and its `res/raw/license_*.txt` file only if no other library still uses that license. Remove one dependency at a time and run the tests in between, so a failure points at the right one.
- Keep each change small and behavior-preserving. Do not reformat code you are not otherwise changing, and do not rename public things just for style.
- Removing a string or other resource means removing it from `values/` **and all 12 locales** (`ar de es fr hi it ja ko pt ru zh-rCN zh-rTW`), and checking `src/test` (for example `StringTranslationsTest`) for references.
- Removing a setting leaves the value older installs saved for it where it is. Nothing reads it, so it is harmless; do not add startup code to clear it.
- When a method is removed or its signature changes, also remove or update its line in `app/src/release/generated/baselineProfiles/baseline-prof.txt` and `startup-prof.txt`, and tell me to run `/generateprofiles` before the next release.
- Update or delete tests that only exercised removed code. Never delete a test that checks behavior that still exists; change it to reach that behavior another way.
- Update `README.md`, `CONTRIBUTING.md` or the other commands in `.claude/commands` if they describe what you removed.
- If a finding turns out not to be safe once you are in the code, skip it and say why.

## 4. Verify

1. Run `./gradlew :app:jacocoTestCoverageVerification -q` (use `gradlew.bat` from PowerShell). It runs `testDebugUnitTest` first. Confirm no test failed (check `app/build/test-results/testDebugUnitTest/*.xml` for `failures="[1-9]"` or `errors="[1-9]"`) and that the 90% line gate still passes. Deleted code can lower the percentage when it was well covered, so add tests if the margin is gone.
2. Run `./gradlew :app:lintRelease -q` and confirm it reports no new issues.
3. Run `./gradlew :app:bundleRelease -q` once. R8 can fail, or strip something reached by reflection, in a way the debug tests never see. If a dependency was removed, also compare `./gradlew :app:dependencies --configuration releaseRuntimeClasspath -q` before and after, and list any library whose version changed.
4. Run `python .claude/scripts/unused_imports.py` and `python .claude/scripts/unused_dependencies.py` again and confirm they report nothing new.
5. If anything in `metadata/`, `media/`, `MetadataStripper`, `MediaProcessor` or `FormatConverter` changed, run `/verifyclean` (`.claude/commands/verifyclean.md`), or say it still needs to be run if the emulator is not available here.

If a check fails, fix it or revert that one change, and say which.

## 5. Report

Finish with a short report:

- lines removed and added (`git diff --stat`), and how many imports and dependencies were removed,
- the changes made, grouped as in step 2, one line each,
- findings skipped and why, and questions for me (including anything on the never-change list that looked wrong),
- test, coverage, lint, bundle and `/verifyclean` results,
- anything I need to do, such as `/generateprofiles`.

Then commit to the current branch without asking (skip it if nothing changed or a check is still failing, and say so): stage just the files this task changed (leave my other uncommitted files out), and commit with a message that lists the changes by group. Never push.
