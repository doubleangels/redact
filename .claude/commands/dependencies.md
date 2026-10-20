---
description: Find outdated Gradle libraries and plugins, Bundler gems and GitHub Actions in every workflow, judge which upgrades are safe, apply them, and verify with lint and the tests
allowed-tools: Bash, PowerShell, Read, Edit, Write, Grep, Glob, WebFetch
---

Check every dependency the project uses, list what is outdated, upgrade what is safe, then prove nothing broke. Do not push. Only commit if I ask (or if I invoked this with the word "commit"). Dependabot already opens PRs for these, so this is the one place where they are all judged together, and where the ones that touch metadata handling get extra care.

## 1. Collect what the project uses

Read the files; do not guess versions.

- **Gradle libraries and plugins**: `gradle/libs.versions.toml` (the `[versions]`, `[libraries]` and `[plugins]` tables), plus any version written directly in `build.gradle`, `app/build.gradle` or `baselineprofile/build.gradle`. Include the Android Gradle Plugin, Kotlin or Java toolchain settings if present, and the Gradle wrapper in `gradle/wrapper/gradle-wrapper.properties`.
- **Bundler gems**: `Gemfile` and `Gemfile.lock` (fastlane and its plugins).
- **GitHub Actions**: every `uses:` line in `.github/workflows/*.yml` **and** in local composite actions under `.github/actions/*/action.yml`. Run `grep -rn "uses:" .github` so none are missed. Skip `uses: ./...` (local), but do read the composite action files, because they contain `uses:` lines of their own. Note which style each one is pinned in: a version tag (`actions/checkout@v7.0.1`), or a full commit SHA followed by a `# vX.Y.Z` comment.
- Also note the Java version set in the workflows (`actions/setup-java` inputs) and the Ruby version (`ruby/setup-ruby`), since they must stay compatible with the AGP, Gradle and fastlane versions.

## 2. Find the latest versions

Use real sources, not memory.

- **Gradle libraries**: read `maven-metadata.xml` for each group and artifact from Google Maven (`https://dl.google.com/dl/android/maven2/<group path>/<artifact>/maven-metadata.xml`) or Maven Central (`https://repo1.maven.org/maven2/<group path>/<artifact>/maven-metadata.xml`). Gradle plugins resolve from the plugin portal (`https://plugins.gradle.org/m2/`) or Google Maven. Gradle itself: `https://services.gradle.org/versions/current`. If a library has no network route, say so rather than guessing.
- **Gems**: `https://rubygems.org/api/v1/versions/<gem>/latest.json`.
- **Actions**: list tags with `git ls-remote --tags --refs https://github.com/<owner>/<repo>` and take the highest stable semantic version. Ignore pre-releases (`-rc`, `-beta`). For a SHA-pinned action, resolve the new tag to its commit SHA (`git ls-remote https://github.com/<owner>/<repo> refs/tags/<tag>^{}`, falling back to `refs/tags/<tag>` for lightweight tags), and make sure the SHA belongs to that tag and not to a branch.
- For every action that moved a **major** version, open its release notes (`https://github.com/<owner>/<repo>/releases`) and look for changed inputs, removed inputs, a new minimum runner or Node version, or changed defaults. Compare them with how our workflows call it.
- If the network is blocked for a source, say which one, and carry on with the rest.

Build one table, sorted by risk: **name, where it is used, current, latest, jump (patch, minor, major), and a one-line risk note**. Show it to me before changing anything, and say how many are outdated. If nothing is outdated, say so and stop.

## 3. Decide what is safe

Sort every outdated item into one of three groups and say why.

- **Safe to apply now**: patch and minor bumps of libraries with no behavior change in the notes, GitHub Actions minor and patch bumps, and major bumps of actions whose notes show no change that affects our inputs.
- **Needs care**: anything that touches how files are read, written or cleaned, which is the heart of the app. That means Media3 (Transformer, ExoPlayer, container and muxer code), Glide, any EXIF, XMP or image-metadata library, and AndroidX libraries used by `MetadataStripper`, `MediaProcessor` or `FormatConverter`. Also AGP, the Gradle wrapper, the Java toolchain, `compileSdk` and `targetSdk` (these have to move together and may need code changes). Read the release notes for each, upgrade them one at a time, and run the tests after each.
- **Leave alone**: pre-releases and alphas, anything pinned on purpose (look for a comment next to the version), and anything whose latest version needs a higher `minSdk`, Java version or AGP than we have. Say what each is waiting for.

Never loosen a pin into a range, never add `+` or `latest.release`, and never change a SHA-pinned action to a plain tag.

## 4. Apply the upgrades

1. Check `git status` first and tell me about uncommitted changes in files you are about to edit.
2. Edit versions in place: the `[versions]` table in `libs.versions.toml` when a version is shared, the single `uses:` line for an action, `Gemfile` plus `bundle lock` for gems. Keep the existing pinning style. For a SHA-pinned action, replace both the SHA and the `# vX.Y.Z` comment, and do not touch the other `uses:` lines of the same action until you have checked each one (the same action is often used in several workflows, so update **every** occurrence, and keep them on one version).
3. For a major upgrade that needs a code or workflow change, make the smallest change that keeps behavior the same.
4. Do not hand-edit lock files; regenerate them with the tool (`bundle lock`, or `./gradlew --write-verification-metadata sha256 help` if the repo has `gradle/verification-metadata.xml`).

## 5. Verify

1. `./gradlew :app:lintRelease -q` and `./gradlew :app:testDebugUnitTest -q` (use `gradlew.bat` from PowerShell). Check `app/build/test-results/testDebugUnitTest/*.xml` for `failures="[1-9]"` or `errors="[1-9]"`.
2. `./gradlew :app:bundleRelease -q` if AGP, Gradle, R8 or any library changed, since CI builds the release bundle and R8 can fail on a new library when the unit tests pass.
3. For workflow files, check they still parse (`python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in sys.argv[1:]]" .github/workflows/*.yml .github/actions/*/action.yml`), and re-read every changed `uses:` line to confirm the owner, repo and ref are what you meant. A workflow cannot be run from here, so say which workflows are untested.
4. If a media library changed, run `/verify-clean` if it exists. If not, run `python3 .claude/scripts/check_media.py` and clean at least one test JPEG and one test MP4 on the Pixel_10_Pro emulator (set `ANDROID_SERIAL` to its serial so a physical device is never used), then confirm no metadata remains.
5. If a check fails, find which upgrade caused it, roll that one back, and put it in the **Needs a human** list. Never leave the tree red.

## 6. Report

Finish with a short report:

- how many were outdated and how many were upgraded,
- the upgrades grouped by Gradle, Bundler and Actions, one line each (`name old -> new`, and for a SHA-pinned action the new tag),
- what was left alone and what each is waiting for,
- anything rolled back and why, and anything that deserves a human decision or a real CI run,
- test, lint and bundle results.

Only if I asked to commit: stage just the files this task changed (leave my other uncommitted files out), and commit with a message that lists the upgrades, using the prefixes Dependabot uses (`[Gradle]`, `[Github Actions]`, `[Bundler]`) when a commit holds one kind only. Never push.
