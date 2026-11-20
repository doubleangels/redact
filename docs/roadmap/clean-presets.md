# Clean Presets roadmap

One choice that sets all of Clean's keep options for a purpose, like **Posting Online** or **Keeping for Myself**, instead of setting six or more switches by hand.

| | |
|---|---|
| **Status** | Planned |
| **Branch** | `dev` |
| **Size** | Small to medium. 4 phases, about one week |
| **Depends on** | Nothing |

## Contents

1. [Summary](#summary)
2. [Goals and non-goals](#goals-and-non-goals)
3. [The presets](#the-presets)
4. [Privacy and safety design](#privacy-and-safety-design)
5. [Phase 1. Preset logic](#phase-1-preset-logic)
6. [Phase 2. Settings UI](#phase-2-settings-ui)
7. [Phase 3. Clean tab chip](#phase-3-clean-tab-chip)
8. [Phase 4. Reporting and docs](#phase-4-reporting-and-docs)
9. [Test plan](#test-plan)
10. [Risks](#risks)
11. [Open decisions](#open-decisions)

## Summary

Clean has powerful options (**Strict Clean**, **Keep Location** with **Approximate Location**, **Keep Date Taken** with **Date Only**, **Keep Camera Settings**, **Keep Motion Photos**, **Remove Audio**), and the chips in `CleanQuickToggles` let people change them per batch. The trouble is that the right combination depends on what you're about to do with the files, and it takes knowing what each option means.

A preset sets them all at once for a purpose. It isn't a separate mode. It writes the same preferences the switches use, and the screen shows **Custom** as soon as someone changes a switch by hand.

## Goals and non-goals

**Goals**

- Pick a purpose, get the right options, in one tap from Settings or the Clean tab.
- Every option stays visible and changeable after picking a preset.
- Picking a preset that keeps location still asks first, exactly like the Keep Location switch does.
- Existing users see their current options unchanged, shown as a preset if they match one, otherwise **Custom**.

**Non-goals**

- User-made presets with names. Four fixed presets plus **Custom** cover it.
- Different presets per tab or per file type.
- Changing how shared-in files are cleaned. They always lose location, whatever the preset.

## The presets

| Option | Standard (default) | Posting Online | Sending to People I Know | Keeping for Myself |
|---|---|---|---|---|
| Strict Clean | Off | **On** | Off | Off |
| Keep Location | Off | Off | Off | **On** |
| Approximate Location | (off) | (off) | (off) | **On** |
| Keep Date Taken | Off | Off | **On** | **On** |
| Date Only | (off) | (off) | **On** | Off |
| Keep Camera Settings | Off | Off | Off | **On** |
| Keep Motion Photos | Off | Off | Off | **On** |
| Remove Audio | unchanged | unchanged | unchanged | unchanged |
| Keep Original File Names | unchanged | unchanged | unchanged | unchanged |

Plain-English descriptions shown under each choice:

| Preset | Description |
|---|---|
| **Standard** | Removes location, dates, device details and names, and keeps what photos need to look right. The default. |
| **Posting Online** | Removes everything that isn't needed to show the photo. Best for social media, marketplaces and anywhere strangers can see it. |
| **Sending to People I Know** | Removes location and device details but keeps the day each photo was taken, so it lands in the right place in their gallery. |
| **Keeping for Myself** | Keeps the date, camera details and Motion Photos, and rounds the location to the town. Good for backups and cloud storage you don't fully trust. |
| **Custom** | Shown when your options don't match a preset. |

Remove Audio and Keep Original File Names are left as they are, since they depend on the content more than the audience.

**Standard** is exactly today's defaults, so new installs and anyone who never changed an option see **Standard**, not Custom.

## Privacy and safety design

| Rule | How |
|---|---|
| No hidden state | The current preset is worked out from the options each time. No preset is saved, so it can never disagree with the switches |
| Keeping location still needs a yes | **Keeping for Myself** shows the same confirmation as the Keep Location switch. Declining leaves everything unchanged |
| A preset never weakens a running job | A job reads the options when it starts, as today. Changing a preset mid-job only affects the next one |
| Strict Clean rules still hold | **Posting Online** turns Strict Clean on, which turns the keep options off and greys them out, through the same code path as the switch |
| Clean checks unchanged | Presets only set preferences. `CleanVerifier` and every other check run exactly as before |

## Phase 1. Preset logic

**Deliverables**

- `CleanPreset`, an enum with `STANDARD`, `POSTING_ONLINE`, `SENDING_TO_PEOPLE`, `KEEPING_FOR_MYSELF` and `CUSTOM`, with each preset's values in one table.
- `CleanPresets.current(Context)` returns the preset whose values all match, or `CUSTOM`.
- `CleanPresets.apply(Context, CleanPreset)` writes the values through the existing `AppPreferences` setters in one `SharedPreferences` edit, so listeners see one change. It refuses to apply a location-keeping preset unless the caller says the user confirmed.
- No new preference key. The preset is derived, which also means Reset All Settings and a removed setting need no special handling.

**Done when**

- [ ] Applying each preset and then calling `current` returns that preset.
- [ ] Changing any single option afterwards returns `CUSTOM`.
- [ ] The location-keeping preset can't be applied without confirmation.

## Phase 2. Settings UI

**Layout, top of Metadata Cleaning in Settings**

```
Metadata Cleaning
┌────────────────────────────────┐
│ Preset                         │
│ ◉ Standard                     │
│   Removes location, dates...   │
│ ○ Posting Online               │  radio list, one line of
│   Removes everything that...   │  description under each
│ ○ Sending to People I Know     │
│   Removes location and...      │
│ ○ Keeping for Myself           │
│   Keeps the date, camera...    │
│ ○ Custom                       │  disabled, selected only
│                                │  when nothing matches
└────────────────────────────────┘
  Strict Clean               [●]
  Keep Location              [ ]
  ...                              existing switches, unchanged
```

**Deliverables**

- A preset block in `fragment_settings.xml` above the existing switches, using `MaterialRadioButton` rows with a title and a description, at least 48dp tall.
- `SettingsFragment` updates the selection when any switch changes, and the switches when a preset is picked.
- Strings for the section title, five names and five descriptions, in `values/` and all 12 locales.

**Done when**

- [ ] Picking a preset flips the switches below it, and flipping a switch moves the selection to **Custom** or to a matching preset.
- [ ] Declining the location confirmation leaves the previous selection.
- [ ] The block reads well in light, dark, Arabic and at 200% font size.

## Phase 3. Clean tab chip

**Deliverables**

- A new first chip in `CleanQuickToggles`, labeled with the current preset name ("Posting Online ▾"). Tapping it opens a small menu with the presets and their descriptions.
- The other chips stay as they are and stay in sync both ways.
- The chip is disabled while a job runs, like the other chips (`locked`).

**Done when**

- [ ] The chip and Settings always show the same preset.
- [ ] Picking from the chip affects the next batch only.

## Phase 4. Reporting and docs

**Sentry**

- Add `setting.clean_preset` to `AppStateTags` next to `setting.strict_clean`, with values `standard`, `posting_online`, `sending_to_people`, `keeping_for_myself` and `custom`. It helps tell whether a bug report happened under Strict Clean or a keep option, and says nothing about a person or file.
- In the same commit, add the tag to the Names in Sentry and What Send Crash Reports captures tables in `README.md` and `site/index.html`, and mention it in Section 6 of `legal/redact.html` where the setting tags are described. `SentryNamingTest` checks the tables.
- Consent strings don't change. Setting tags are already part of what's described.

**Docs**

- `README.md` and `site/index.html`, Settings › Metadata Cleaning, with the preset table above.
- `fastlane/metadata/android/*/full_description.txt`, one line about presets in every locale.
- `.claude/commands/test.md`, a step that picks each preset and checks the switches and a cleaned file.

**Done when**

- [ ] `SentryNamingTest` passes and both tables list `setting.clean_preset`.

## Test plan

| Test | Covers |
|---|---|
| `CleanPresetsTest` (new) | Each preset's values, `current` for every preset and for one-off changes, refusal without location confirmation, one edit per apply |
| `AppPreferencesTest` (extended) | Reset All Settings lands on **Standard** |
| `SettingsFragmentTest` (extended) | Preset and switches stay in sync both ways, confirmation accept and decline |
| `CleanConvertFragmentsTest` (extended) | The preset chip, its menu, sync with the other chips, and locking during a job |
| `ImageCleaner` and `CleanVerifier` tests | Run unchanged against each preset's options to show each one cleans as described |
| `AppStateTagsTest` / `SentryNamingTest` | The new tag and its values |
| `StringTranslationsTest`, `HardCodedTextTest`, `LayoutAccessibilityTest` | New strings and rows |
| `/verifyclean` on `Pixel_10_Pro` | Clean the test media under each preset and confirm what's left matches the table |

## Risks

| Risk | Mitigation |
|---|---|
| People think a preset is a mode that overrides the switches | The switches stay visible right under it and update live. **Custom** appears as soon as anything differs |
| **Keeping for Myself** sounds safe but keeps location | Its description says the location is rounded to the town, and it asks first like Keep Location |
| Preset names don't translate well | Translators get context comments in `values/strings.xml`, and `/translate` reviews them |
| A future option is added and presets forget it | `CleanPresetsTest` lists every Clean option and fails if one has no value in each preset or isn't marked "unchanged" |

## Open decisions

| Decision | Default |
|---|---|
| Change the default from **Standard** to another preset | No. Nothing changes for anyone who doesn't pick a preset |
| Offer the preset choice in the introduction | No, keep the introduction short |
| Add `setting.clean_preset` to Sentry | Yes |
