# Tell a Friend roadmap

A row in Settings › About that opens the Android share sheet with a short message about Redact and its Google Play link. No file is shared and nothing is sent by Redact itself.

| | |
|---|---|
| **Status** | Planned |
| **Branch** | `dev` |
| **Size** | Small. One commit, about 1 class, 1 layout row, 3 strings in 13 files, tests and docs |
| **Target** | The next release after it lands |

## Contents

1. [Summary](#summary)
2. [Goals and non-goals](#goals-and-non-goals)
3. [Privacy design](#privacy-design)
4. [Phase 1. Strings](#phase-1-strings)
5. [Phase 2. Code and layout](#phase-2-code-and-layout)
6. [Phase 3. Tests](#phase-3-tests)
7. [Phase 4. Documentation](#phase-4-documentation)
8. [Phase 5. Verify and ship](#phase-5-verify-and-ship)
9. [Files touched](#files-touched)
10. [Risks](#risks)

## Summary

Redact has no ads and no marketing budget, and people find it mostly by word of mouth. Today the only way to help from inside the app is **Rate Redact on Google Play**. This adds **Tell a Friend About Redact** right below it. Tapping it opens the standard share sheet with a ready-made message the user can edit before sending, in their app of choice.

The row lives only in Settings. It never appears on its own, after a clean, or in any prompt.

## Goals and non-goals

**Goals**

- One tap from Settings › About to a share sheet with a friendly, plain message and the Play link.
- A message in the user's language, in all 12 locales plus English.
- No new data sent anywhere, no new Sentry names, no change to the consent strings.

**Non-goals**

- Promoting Redact inside files, file names or shared results. Cleaned files stay free of anything Redact adds.
- Prompting people to share. No popup, no counter, no reminder.
- Referral tracking, install attribution or rewards.
- Knowing which app the user shared to.

## Privacy design

| Decision | Why |
|---|---|
| Plain text only (`text/plain`), no `EXTRA_STREAM`, no URI grants | No file or file reference can ever leave through this path |
| Play link with no `referrer` or `utm_*` parameters | A tracking parameter would let installs be traced back to shares |
| No `EXTRA_CHOSEN_COMPONENT` callback on the chooser | Redact never learns which app or contact the message went to |
| Nothing new reported to Sentry | The SDK's existing `ui.click` breadcrumb already records the tap by view ID. A missing share target reuses `ui.external_app_missing` with `reason=share_sheet`, which already exists |
| The message is a format string with the link passed in | Translators can't break or alter the link |

Because no new tag, metric or breadcrumb category is added, the Sentry tables in `README.md` and `site/index.html`, `SentryNamingTest` and the consent strings stay as they are.

## Phase 1. Strings

Add to `values/strings.xml` and all 12 locales (`ar de es fr hi it ja ko pt ru zh-rCN zh-rTW`), following the title case of the other About rows.

| Name | English |
|---|---|
| `settings_about_tell_friend` | Tell a Friend About Redact |
| `tell_friend_chooser_title` | Share Redact |
| `tell_friend_message` | I use Redact to remove hidden details like location and camera model from my photos and videos before I share them. It\'s free, has no ads and works right on your phone. %1$s |

Writing rules for the message and its translations:

- First person, because the user is the one sending it.
- No jargon. No "metadata" or "EXIF".
- At most 250 characters before the link, so it fits in one text message in most languages.
- Exactly one `%1$s`, at the end, which becomes the link.

**Done when**

- [ ] All three strings exist in all 13 files, and `StringTranslationsTest` passes.
- [ ] Every locale's message has exactly one `%1$s` and no hard-coded URL.

## Phase 2. Code and layout

**Shared store link**

- Pull the Play URL out of `ReviewPrompt.openStoreListing` into a small helper, `StoreLinks.playListingUrl(Context)`, that returns `https://play.google.com/store/apps/details?id=<package>`. Both Rate and Tell a Friend use it, so the link is written once.

**New class `com.doubleangels.redact.ui.TellAFriend`**

```java
/** Opens the share sheet with a short message about Redact and its Play link. No file is shared. */
public final class TellAFriend {

    private TellAFriend() {}

    public static void share(@NonNull Context context) {
        String message = context.getString(
                R.string.tell_friend_message, StoreLinks.playListingUrl(context));
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, message)
                .putExtra(Intent.EXTRA_TITLE, context.getString(R.string.app_name));
        try {
            context.startActivity(Intent.createChooser(
                    send, context.getString(R.string.tell_friend_chooser_title)));
        } catch (ActivityNotFoundException none) {
            AppMetrics.externalAppMissing(AppMetrics.EXTERNAL_SHARE_SHEET);
            Notice.show(context, R.string.settings_link_unavailable, false);
        }
    }

    /** The message, for tests. */
    @VisibleForTesting
    static Intent buildIntent(@NonNull Context context) { ... }
}
```

`share` builds the intent through `buildIntent` so tests can check it without starting anything.

**Layout, `fragment_settings.xml`**

- A new row `rowAboutTellFriend` directly below `rowAboutRate`, copied from that row so the padding, touch target (`@dimen/touch_target_min`), ripple, text style (`textAppearanceBodyLarge`, `colorPrimary`) and trailing chevron all match.
- A 1dp `colorOutlineVariant` divider between the two rows, like the others.

```
About
  Version 1.14.0
  Check for Updates
  View Source on GitHub          ›
  Privacy Policy                 ›
  Terms of Use                   ›
  Open-Source Licenses           ›
  Show Introduction              ›
  Report an Issue                ›
  Rate Redact on Google Play     ›
  Tell a Friend About Redact     ›   ← new
  Donate                         ›
```

**`SettingsFragment`**

```java
view.findViewById(R.id.rowAboutTellFriend).setOnClickListener(v ->
        TellAFriend.share(requireContext()));
```

**Done when**

- [ ] Tapping the row opens the system share sheet with the message in the app's language, ready to edit.
- [ ] The row looks identical to Rate in light, dark, Arabic and at 200% font size.
- [ ] `exportReleaseManifest` shows no change to `app/release-manifest.txt`. No permission or component is added.

## Phase 3. Tests

| Test | What it checks |
|---|---|
| `TellAFriendTest` (new, Robolectric) | The intent is `ACTION_SEND` with `text/plain`. `EXTRA_TEXT` ends with the Play link for the app's package. There is no `EXTRA_STREAM`, no clip data and no `FLAG_GRANT_*_URI_PERMISSION`. The link has no query parameters besides `id` |
| `TellAFriendTest`, missing share target | With a context that throws `ActivityNotFoundException`, `ui.external_app_missing` is counted with `reason=share_sheet` and a notice shows instead of a crash |
| `TellAFriendTest`, locales | For every `values-*` folder, `tell_friend_message` has exactly one `%1$s`, no `http`, and is at most 250 characters without it |
| `StoreLinksTest` (new) | The URL is built from the package name, and `ReviewPrompt` still opens the same listing |
| `SettingsFragmentTest` (extended) | `R.id.rowAboutTellFriend` is in the list of About rows at line 489. Clicking it starts a chooser whose inner intent is the share above |
| `StringTranslationsTest`, `HardCodedTextTest`, `LayoutAccessibilityTest` | Pass unchanged. They pick up the new strings and row on their own |

**Done when**

- [ ] `./gradlew :app:testDebugUnitTest` passes.
- [ ] `./gradlew :app:jacocoTestCoverageVerification` stays above the 90% line gate. The new code is fully covered by the tests above.

## Phase 4. Documentation

| File | Change |
|---|---|
| `README.md`, Settings › About line | Add **Tell a Friend About Redact** after **Rate Redact on Google Play** |
| `README.md`, network table | No change. Redact connects to nothing. The app the user picks may load a link preview under its own policy |
| `site/index.html` | Same About change as the README |
| `legal/redact.html`, Section 6 | Add **Tell a Friend About Redact** to the paragraph about buttons that hand off to another app. It "hands a short message and the Google Play link to the app you choose, and Redact sends nothing itself" |
| Consent strings | No change. Nothing new is collected |
| `fastlane/metadata/android/*` | No change |
| Commit message | `Add Tell a Friend About Redact to Settings › About`, so CI's Play notes and `/whatsnew` both pick it up in plain words |

## Phase 5. Verify and ship

Run these one at a time, never two Gradle builds at once.

1. `./gradlew :app:testDebugUnitTest`
2. `./gradlew :app:jacocoTestCoverageVerification`
3. `./gradlew :app:lintRelease`
4. `./gradlew :app:bundleRelease`, to catch R8 problems
5. On the `Pixel_10_Pro` emulator, open Settings › About, tap **Tell a Friend About Redact**, and check that:
   - the share sheet opens with the message and link
   - Messages and Gmail receive the text unchanged
   - switching the app language to German and Arabic shows the translated message
   - nothing about a file appears anywhere in the share
6. Add a line for the new row to the Settings section of `.claude/commands/test.md`, so `/test` checks it on every release.

**Done when**

- [ ] All four Gradle tasks pass.
- [ ] The emulator check passes in English, German and Arabic.
- [ ] The commit is on `dev` and the next `/prerelease` reports it ready.

## Files touched

| File | Change |
|---|---|
| `app/src/main/java/com/doubleangels/redact/ui/TellAFriend.java` | New |
| `app/src/main/java/com/doubleangels/redact/ui/StoreLinks.java` | New, shared Play URL |
| `app/src/main/java/com/doubleangels/redact/ui/ReviewPrompt.java` | Use `StoreLinks` |
| `app/src/main/java/com/doubleangels/redact/SettingsFragment.java` | Click listener |
| `app/src/main/res/layout/fragment_settings.xml` | New row and divider |
| `app/src/main/res/values*/strings.xml` (13 files) | 3 strings |
| `app/src/test/java/com/doubleangels/redact/ui/TellAFriendTest.java` | New |
| `app/src/test/java/com/doubleangels/redact/ui/StoreLinksTest.java` | New |
| `app/src/test/java/com/doubleangels/redact/SettingsFragmentTest.java` | New row |
| `README.md`, `site/index.html`, `legal/redact.html` | About list and Section 6 |
| `.claude/commands/test.md` | Emulator check |

## Risks

| Risk | Mitigation |
|---|---|
| A translation breaks or drops the link | The link is a format argument, and a test checks every locale for exactly one `%1$s` |
| Someone later adds a tracking parameter or a file to the share | `TellAFriendTest` fails on any query parameter besides `id`, and on any stream, clip data or URI grant |
| The row feels pushy | It stays a quiet row in About, with no prompt, badge or counter anywhere |
| No share target on the device | Caught, counted under the existing `ui.external_app_missing` metric, and shown as a notice |
| The message sounds like an ad | First person, plain, and editable in the share sheet before sending |
