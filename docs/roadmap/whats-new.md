# What's New screen roadmap

An in-app screen that explains each release in plain English, written and translated before release and shipped inside the app.

| | |
|---|---|
| **Status** | Done |
| **Branch** | `dev` |
| **Target** | The first minor release after the work lands |
| **Phases** | 5, each one a commit that passes the tests on its own |

## Contents

1. [Summary](#summary)
2. [Goals and non-goals](#goals-and-non-goals)
3. [How it fits with the Play notes](#how-it-fits-with-the-play-notes)
4. [Phase 1. Notes file and content checks](#phase-1-notes-file-and-content-checks)
5. [Phase 2. The screen](#phase-2-the-screen)
6. [Phase 3. When it opens](#phase-3-when-it-opens)
7. [Phase 4. Release workflow](#phase-4-release-workflow)
8. [Phase 5. Documentation and first release](#phase-5-documentation-and-first-release)
9. [Writing guide](#writing-guide)
10. [Test plan](#test-plan)
11. [Risks](#risks)
12. [Open decisions](#open-decisions)

## Summary

After an update, Redact opens a short, clean screen once that explains what changed and why it matters, grouped under **New**, **Improved** and **Fixed**. People can open it again any time from Settings. The text is written on `dev` with a new `/whatsnew` command during `/prerelease`, translated into all 12 locales, and checked by unit tests so a release can't ship without it.

The Google Play "What's new" text doesn't change. CI keeps writing it from the commits when the release is created.

## Goals and non-goals

**Goals**

- Explain each release in words someone who has never heard of EXIF understands.
- Go into more detail than Play allows. Each item says what changed, why it matters and where to find it.
- Work entirely offline, with no request on launch and nothing sent anywhere.
- Never interrupt a task. The screen opens only on a plain launch, never from a share, a tile or a shortcut.
- Fail closed. Notes written for another version are never shown.

**Non-goals**

- Fetching notes from GitHub or any server. A request on every update would reveal the user's IP address and that they use Redact.
- Replacing or changing the Play notes CI writes.
- Showing a history of past releases. Only the installed version's notes ship.
- Asking for a review. `ReviewPrompt` already does that, and the Play text carries the review line.

## How it fits with the Play notes

| | Play "What's new" | In-app What's New |
|---|---|---|
| Written by | CI (`release-notes` job in `deploy.yml`) | `/whatsnew`, approved by hand |
| Written when | When the release is created | On `dev`, before the release build |
| Length | Up to 5 lines, 500 characters | Up to 8 items, 1 to 3 sentences each |
| Lives in | `whatsnew/`, the release artifact | `app/src/main/res/values*/whats_new.xml` |
| Shipped in the app | No | Yes |

Here is the same change in both:

> **Play:** Scan shows Hidden & embedded data.
>
> **In app:** **See what's hidden inside a photo.** Some photos carry extra pictures or data you can't see, like a small preview of the original or notes from an editing app. Scan now lists them in plain words under Hidden & embedded data, and Clean removes them.

## Phase 1. Notes file and content checks

The notes format, with tests that hold every release to it.

**Deliverables**

- `app/src/main/res/values/whats_new.xml`, with the version code, the announce flag, the item kinds and icons (not translated), and the titles and bodies.
- `values-<locale>/whats_new.xml` for `ar de es fr hi it ja ko pt ru zh-rCN zh-rTW`, with only the translated titles and bodies.
- `WhatsNewContentTest`.
- `StringTranslationsTest` extended to cover `<string-array>`.

```xml
<resources>
    <!-- The versionCode these notes are for. The screen never shows notes for another version. -->
    <integer name="whats_new_version_code">45</integer>
    <!-- true for a minor or major release (opens by itself once), false for fixes only. -->
    <bool name="whats_new_announce">true</bool>
    <string-array name="whats_new_kinds" translatable="false">
        <item>new</item>
        <item>improved</item>
        <item>fixed</item>
    </string-array>
    <string-array name="whats_new_icons" translatable="false">
        <item>scan</item>
        <item>clean</item>
        <item>fixes</item>
    </string-array>
    <string-array name="whats_new_titles">
        <item>See what\'s hidden inside a photo</item>
        <item>…</item>
        <item>…</item>
    </string-array>
    <string-array name="whats_new_bodies">
        <item>Some photos carry extra pictures or data you can\'t see…</item>
        <item>…</item>
        <item>…</item>
    </string-array>
</resources>
```

**Done when**

- [ ] `whats_new_version_code` equals `BuildConfig.VERSION_CODE`, or the build fails.
- [ ] There are 1 to 8 items, titles of at most 50 characters and bodies of at most 300.
- [ ] Every locale has both arrays, each the same length as the English.
- [ ] Every kind is `new`, `improved` or `fixed`, and every icon key is known.

## Phase 2. The screen

**Deliverables**

- `com.doubleangels.redact.whatsnew.WhatsNew` loads the items and says whether they're for this version.
- `WhatsNewIcons` maps icon keys (`clean`, `scan`, `convert`, `censor`, `settings`, `stats`, `fixes`) to existing drawables, with a generic icon for anything unknown.
- `WhatsNewActivity` with layout `activity_whats_new.xml`, declared `exported="false"`.
- Strings for the version label, the screen title, the three section headers and "Got it", in all 13 `strings.xml` files.
- A "What's new" row in Settings › About, next to Open source licenses.

**Layout**

```
┌────────────────────────────────┐
│ VERSION 1.14.0                 │  labelLarge, colorPrimary
│ What's new                     │  headlineMedium, accessibility heading
│                                │
│ NEW                            │  section header, hidden when empty
│ (◉)  See what's hidden inside  │  40dp tonal circle, primaryContainer
│      a photo                   │  titleMedium
│      Some photos carry extra   │  bodyMedium, onSurface
│      pictures or data…         │
│                                │
│ IMPROVED                       │
│ (◉)  …                         │  24dp between items
│                                │
│ FIXED                          │
│ (◉)  …                         │
│                                │
│ ┌────────────────────────────┐ │
│ │          Got it            │ │  filled button, pinned above the nav bar
│ └────────────────────────────┘ │
└────────────────────────────────┘
```

**Design rules**

- Full screen rather than a dialog, so eight items at 200% font size still fit and scroll.
- Edge to edge with insets. Content at most 600dp wide, centered on tablets and unfolded foldables.
- Material 3 theme tokens only, so dark mode and dynamic color work without extra code.
- Start and end, never left and right, so Arabic lays out correctly.
- Icons are decorative (`contentDescription="@null"`), and the button is at least 48dp tall.
- `SecureWindow` applies like on the other screens.
- "Got it", back and predictive back all close the screen.
- No carousel, animation beyond a short fade, or prompts.

**Done when**

- [ ] The screen renders the items under the right headers and hides empty sections.
- [ ] It reads well in light and dark, at 200% font size, in Arabic, and on an unfolded foldable.
- [ ] `HardCodedTextTest` and `LayoutAccessibilityTest` pass with no exceptions added.
- [ ] `exportReleaseManifest` shows no change to `app/release-manifest.txt`.

## Phase 3. When it opens

**Deliverables**

- `AppPreferences` gets `KEY_WHATS_NEW_SEEN_VERSION` with a getter and setter.
- `WhatsNewGate.shouldShow(...)`, a pure function that makes the decision.
- A third branch in `MainActivity.onCreate`, after the onboarding branch.
- Finishing `OnboardingActivity` stores the current version as seen.
- `EXTRA_SKIP_INTRO` also stores the current version as seen.
- A debug-only `EXTRA_SHOW_WHATS_NEW` extra that opens the screen for testing.
- One Sentry breadcrumb through `SentryManager`, category `whatsnew` and message `shown` or `opened_from_settings`.

**The rule**

The screen opens by itself only when every one of these is true:

| Condition | Why |
|---|---|
| Onboarding is done | New installs see the introduction, not release notes |
| The seen version is lower than the installed one | Once per update |
| `whats_new_announce` is true | Fix-only releases don't interrupt anyone |
| The notes are for this version | Stale notes are never shown |
| It's a plain launcher start | A share, view intent, tile or shortcut means a task is underway |
| `savedInstanceState` is null | Rotation doesn't open it again |
| No permission flow is running | One thing at a time |

The seen version is stored **when the screen opens**, not when it closes, so a crash on the screen can't make it reappear on every launch. People updating from a version that predates this feature have no stored value, so they see it once.

**Done when**

- [ ] An updated install sees the screen once, and a relaunch doesn't show it again.
- [ ] A fresh install never sees it.
- [ ] Shares, tiles, shortcuts and rotation never open it.
- [ ] `skip_intro` launches used by `/test`, `/generatescreenshots`, `Profiles` and `StartupBenchmark` go straight to the tabs.
- [ ] `whatsnew` is listed in the Names in Sentry tables in `README.md` and `site/index.html`, and `SentryNamingTest` passes.

## Phase 4. Release workflow

**Deliverables**

- New `.claude/commands/whatsnew.md`.
- `prerelease.md` gets a What's New step right after the version bump (2e) and before the release build (2f) and the tests, so they cover the real notes. The step commits its changes.
- `bump.md` ends with "run `/whatsnew` next".
- `releasenotes.md` adds a report item that flags a change mentioned in the Play preview but missing from the in-app notes, or the reverse.
- `translate.md` includes `whats_new.xml`.
- `test.md` adds a step that checks the screen with `--ez show_whats_new true` and from Settings.

**What `/whatsnew` does**

1. Reads the commits since `last-published-version`, plus the diff for anything unclear.
2. Keeps only changes a user would notice.
3. Drafts the English following the [writing guide](#writing-guide) and sets `announce` from the bump level.
4. Shows the draft and waits for approval.
5. Translates into the 12 locales, using each locale's own feature labels from its `strings.xml`.
6. Runs `WhatsNewContentTest` and `StringTranslationsTest`, then commits.

**Release order**

```
/prerelease
  1   translate
  2   dependencies, sentry, cleanup, lint
  2e  bump            → versionCode 45
  2e' whatsnew        → whats_new.xml for 45, approved and translated   (new)
  2f  release build
  3   releasenotes    → Play preview, now also compared with whats_new.xml
  4   unit tests
  5   /test and profiles
/release
  CI writes the Play notes from the commits                              (unchanged)
```

**Done when**

- [ ] A dry run of `/prerelease` writes, translates and commits the notes before the release build.
- [ ] `/prerelease` reports "not ready" when the notes are missing or for another version.

## Phase 5. Documentation and first release

**Deliverables**

- A What's New section in `CLAUDE.md`. It says the in-app notes are written by `/whatsnew` before release, are separate from the Play notes CI writes, and must match the `versionCode`.
- A short note in `CONTRIBUTING.md` under Workflow.
- Optionally, one line in the feature lists in `README.md`, `site/index.html` and the Play listing.
- The first real notes, written with `/whatsnew` for the release that ships the screen.

**Done when**

- [ ] `/test` passes on the `Pixel_10_Pro` emulator, including the new What's New step.
- [ ] `:app:testDebugUnitTest`, `:app:jacocoTestCoverageVerification`, `:app:lintRelease` and `:app:bundleRelease` pass, run one at a time.
- [ ] The release ships, and updating from the previous version shows the screen once.

## Writing guide

The in-app notes are for people who don't know what metadata is. Every item answers three questions: what changed, why it matters to me, and where to find it.

**Titles** say what the person can now do.

| Avoid | Write |
|---|---|
| Date preservation option | Keep the date a photo was taken |
| XMP parsing improvements | See what's hidden inside a photo |
| HEIC EXIF fixes | Cleaning iPhone photos is more reliable |

**Bodies**

- 1 to 3 short sentences, in second person ("you").
- Use the app's exact labels from `strings.xml`, with the path when it helps ("Settings › Keep Date Taken").
- No jargon. No EXIF, XMP, codec, container or MIME unless an app label uses the word. Say "hidden details like location and camera model".
- Fixes describe what the person saw ("Cleaning a long video no longer stops halfway on some phones").
- Group many small fixes into one "Smaller fixes" item that names the two or three most noticeable.
- No version numbers, commit hashes, class names or internal names.

**Order** is New, then Improved, then Fixed, with the most useful item first in each group.

## Test plan

| Test | Covers |
|---|---|
| `WhatsNewContentTest` (new) | Version code match, item count, length limits, equal array lengths per locale, known kinds and icons |
| `StringTranslationsTest` (extended) | Every locale has every `<string-array>` |
| `WhatsNewGateTest` (new) | Every row of [the rule](#phase-3-when-it-opens), as a table of cases |
| `WhatsNewActivityTest` (new, Robolectric) | Rows and headers render, empty sections hide, version label, Got it and back close it, breadcrumb sent |
| `MainActivityTest` (extended) | Shown once after an update, never on fresh install, share, tile, shortcut or rotation, `skip_intro` suppresses it |
| `OnboardingActivityTest` (extended) | Finishing onboarding stores the current version as seen |
| `SettingsFragmentTest` (extended) | The About row opens the screen |
| `AppPreferencesTest` (extended) | The new key round-trips |
| `/test` on the emulator | The screen in light, dark, 200% font size and Arabic, and from Settings |

The new code is small and fully reachable, so the 90% coverage gate isn't at risk.

## Risks

| Risk | Mitigation |
|---|---|
| The Play notes and the in-app notes highlight different things | `/releasenotes` compares them during `/prerelease` |
| A release ships without notes | `WhatsNewContentTest` fails the build when the version code doesn't match |
| Notes appear during a task | Only plain launcher starts can open the screen |
| Screenshots, profiles or benchmarks stop at the screen | `skip_intro` and onboarding both store the version as seen |
| A translation runs long or breaks the layout | Length limits in the test, plus the 200% font and Arabic checks in `/test` |
| Notes from an old version show after a missed update | The screen only shows notes whose version code equals the installed one |

## Open decisions

| Decision | Default |
|---|---|
| Keep the `whatsnew` Sentry breadcrumb, which needs doc updates, or leave it out | Keep it |
| Add one line to the CI Play notes prompt telling it to use `whats_new.xml` as a guide to what matters | Leave CI untouched |
| Mention What's New in the README, site and Play listing feature lists | No, it's not a feature people choose an app for |
