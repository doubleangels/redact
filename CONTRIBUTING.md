# Contributing to Redact

Thank you for helping! Redact is a small, privacy-focused Android app, and contributions of every size are welcome: typo fixes, translations, bug fixes, tests and new features.

By taking part you agree to be respectful and constructive. Redact is released under the [GNU General Public License v3.0](LICENSE), and your contributions will be too.

## Ways to help

- **Report a bug or suggest an idea** in a [GitHub issue](https://github.com/doubleangels/redact/issues). Please do not attach photos or files that contain private information.
- **Improve translations.** Many are machine-written and benefit from native-speaker review.
- **Fix a bug or build a feature.** For anything larger than a small fix, open an issue first so we can agree on the approach before you spend time on it.
- **Report a security problem privately.** See [SECURITY.md](SECURITY.md).

## Privacy comes first

Privacy is the product. Changes that add network access, tracking, analytics, new permissions or new data collection need a clear justification and will get extra scrutiny. Core features (Clean, Scan, Convert) must keep working fully offline, and user media must never leave the device.

## Setting up

Requirements: **JDK 21**, **Android Studio** (recent stable) with **Android SDK 37**, and an Android device or emulator on API 31 or newer.

```bash
git clone https://github.com/<your-fork>/redact.git
cd redact
./gradlew assembleDebug      # build a debug APK
./gradlew installDebug       # build and install on a connected device or emulator
```

On Windows use `gradlew.bat`, or Git Bash. You do not need a Sentry DSN to build, run or test the app; see the [README](README.md#optional-crash-reporting-while-developing) if you want crash reporting in debug builds.

## Workflow

1. **Fork** the repository and create a branch from **`dev`**. Day-to-day work happens on `dev`; `main` is used for releases.
2. **Keep the change focused.** One concern per pull request, matching the style of the surrounding code.
3. **Add or update tests.** Unit tests live in `app/src/test` and run with Robolectric, so no device is needed.
4. **Run the checks** before you push:

   ```bash
   ./gradlew testDebugUnitTest
   ./gradlew lintRelease
   ```

5. **Localize any user-visible text** (see below).
6. **Open a pull request against `dev`.** Describe what changed and why, and include screenshots for UI changes. CI runs the tests and Android Lint on every pull request.

### Commit messages

Write short, descriptive messages in the imperative mood ("Fix the Convert header subtitle"), with a body explaining *why* when it is not obvious.

## Translations

User-facing strings live in `app/src/main/res/values*/strings.xml`. When you add or change a string:

- Add it to **every** locale folder: `ar`, `de`, `es`, `fr`, `hi`, `it`, `ja`, `ko`, `pt`, `ru`, `zh-rCN` and `zh-rTW`.
- Use `<plurals>` for anything that shows a count, since plural rules differ between languages.
- Never hard-code user-visible text in Java; use string resources.
- Keep the casing style of the English text: where an English string is in Title Case (labels, buttons, headings), capitalize the translation the same way in German, Spanish, French, Italian, Portuguese and Russian, leaving small words such as articles, prepositions and conjunctions lowercase. Scripts without letter case need nothing.

If you are not confident in a language, a machine translation is acceptable, as long as you say so in the pull request so a native speaker can review it.

## Store listing and screenshots

The Google Play text, screenshots and icon live in `fastlane/metadata/`. `scripts/screenshots.sh` captures screenshots on an API 37 emulator; see the header of the script for details. The Play icon is rendered from `app/src/main/ic_launcher-playstore.svg`.

## Baseline profile

If you change startup-critical code, regenerate the baseline and startup profiles on a connected device or emulator (set `ANDROID_SERIAL` if more than one is connected):

```bash
./gradlew :app:generateBaselineProfile
```

## Questions

Open an issue and ask. No question is too small.
