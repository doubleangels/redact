# Replace Originals roadmap

Adds a choice to write the clean version over the original, so the gallery keeps one copy in its place instead of the original plus a clean duplicate in another album.

| | |
|---|---|
| **Status** | Planned |
| **Branch** | `dev` |
| **Size** | Medium. 5 phases |
| **Depends on** | Nothing. Shares the "what happens to originals" choice with item 4, permanent delete, of the [Privacy Additions roadmap](privacy-additions.md#4-permanent-delete-option). Whichever ships first adds the choice |

## Contents

1. [Summary](#summary)
2. [Goals and non-goals](#goals-and-non-goals)
3. [What replacing can and can't do](#what-replacing-can-and-cant-do)
4. [Privacy and safety design](#privacy-and-safety-design)
5. [Phase 1. Spike](#phase-1-spike)
6. [Phase 2. Safe write](#phase-2-safe-write)
7. [Phase 3. Setting and flow](#phase-3-setting-and-flow)
8. [Phase 4. Gallery order](#phase-4-gallery-order)
9. [Phase 5. Reporting, docs and store](#phase-5-reporting-docs-and-store)
10. [Test plan](#test-plan)
11. [Risks](#risks)
12. [Open decisions](#open-decisions)

## Summary

Today a clean saves a new copy in `Pictures/Redact` or `Movies/Redact`. With **Delete Originals After Cleaning** on, Android moves the originals to the trash, where they stay for 30 days. Either way the gallery now holds the clean copy in a different album, often at a different place in the timeline. For people who clean their whole camera roll this is the main annoyance.

This roadmap adds a third choice. After a clean passes every check, Redact asks Android for permission to change the originals, writes the clean bytes into each one, reads them back to confirm, and keeps a private backup until it's sure. The photo stays in its album and, where Android allows it, in its place in the timeline.

## Goals and non-goals

**Goals**

- One choice for originals after a clean: **Keep**, **Move to Trash** (today's Delete Originals) or **Replace with Clean Version**.
- Replace never leaves a file half written. If anything fails, the original is put back.
- Replace happens only for a file whose clean version has the same format. Others are saved as new copies, and the summary says why.

**Non-goals**

- Replacing on Share, Share Without Saving, Convert or Censor. Shares never touch originals. Censor may get it later.
- Files outside MediaStore, such as a document from a cloud provider in the Files app. They are saved as new copies.
- Removing copies elsewhere: cloud backups, messaging apps, or the bytes still on the flash chip. [What replacing can and can't do](#what-replacing-can-and-cant-do) explains, and the app says so.

## What replacing can and can't do

This goes into the setting's description and the README, in plain words.

| Can | Can't |
|---|---|
| Leave one file in the gallery, in its album, without its metadata | Remove a copy Google Photos or another backup already uploaded. A backup app may upload the clean version as a new item, or replace its copy, depending on the app |
| Keep its place in the timeline, where Android lets Redact keep the date column (Phase 4) | Remove copies sent through messaging apps, or kept in their folders |
| Avoid 30 days of originals in the trash | Wipe the old bytes from the flash chip. Like Move to Trash, Android frees the space without overwriting it, and Redact can't reach the chip. Phones encrypt their storage, which protects those bytes from anyone without the phone unlocked |

## Privacy and safety design

| Rule | How |
|---|---|
| Only verified bytes are written | Replace runs after every `CleanVerifier` check has passed on the clean copy in private storage. Nothing that fails a check comes near the original |
| The user agrees | Android's own `MediaStore.createWriteRequest` dialog, once per batch. Declining saves new copies, as **Keep** does |
| No half-written file | Before writing, the original is copied to private storage. After writing, the original's bytes are read back and their SHA-256 compared with the clean copy's. On any failure the backup is written back, and that is checked the same way |
| A failed restore still loses nothing | If writing the backup back also fails, the backup is saved as a new file in the output folder with its original name, and the summary says so. It's the user's own original, so this exposes nothing new |
| Backups don't linger | Each backup is removed with `SecureDelete` as soon as its file is confirmed, and on the next start by `CacheCleanup` if the app was killed in between |
| Same format only | Replace only when the clean copy's MIME type matches the original's in MediaStore. A MOV cleaned to MP4, a DNG saved as JPEG or a GIF made into PNG is saved as a new copy |
| Nothing about files reaches Sentry | Counts and outcomes only, through `AppMetrics` |
| No new permission | `createWriteRequest` needs none, so `release-manifest.txt` doesn't change. Confirm with `exportReleaseManifest` |

## Phase 1. Spike

Android's behaviour decides how much of this works, so this phase measures it on the test emulator before any UI work.

**Questions**

- Does `createWriteRequest` accept the URIs Clean gets: photo picker URIs mapped with `MediaStore.getMediaUri`, and Files app URIs mapped with `trashableMediaUri` (`CleanFragment.java:395`)?
- After writing with `openOutputStream(uri, "wt")`, does MediaStore rescan the file? Does `DATE_TAKEN` change to the time of the write when the EXIF date is gone?
- Can Redact then set `DATE_TAKEN` back with `ContentResolver.update` while it holds the write grant? Do Google Photos and the Files app keep the place in the timeline?
- What does Google Photos backup do with a replaced file: a new item, an update, or nothing?

**Done when**

- [ ] The answers are written into this roadmap, with the API levels tested (37 on the test emulator, and 31 on an emulator if a managed one is added).

## Phase 2. Safe write

**Deliverables**

- `OriginalReplacer` in `media/`, which for each file:
  1. checks the MIME match;
  2. copies the original to private storage and records its SHA-256;
  3. writes the clean bytes into the original with `MediaSizeLimits.copyStream`;
  4. reads them back and compares hashes;
  5. on success, removes the backup with `SecureDelete` and the clean copy from private storage;
  6. on failure, writes the backup back, checks it, and only then reports the failure. If that fails too, saves the backup as a new file through `OutputDestination`.
- Free space is checked before a batch: each file needs room for its backup. Too little space saves new copies instead, with the same message as the large-job warning.
- `CacheCleanup` treats leftover backups as original media and removes them with `SecureDelete` on start.

**Done when**

- [ ] Tests that fail the write at each step, including halfway through, always end with the original or the clean version, never anything in between.
- [ ] A killed process between steps 3 and 5 leaves a backup that the next start removes only after confirming the file holds the clean version or putting the original back.

## Phase 3. Setting and flow

**Deliverables**

- Settings: **Delete Originals After Cleaning** becomes **After Cleaning**, with **Keep Originals**, **Move Originals to Trash** and **Replace with Clean Version**. A saved Delete Originals value of on reads as Move to Trash. The old key stays in place, as `CLAUDE.md` requires for removed settings.
- Choosing Replace shows a one-time explanation with [what it can and can't do](#what-replacing-can-and-cant-do).
- After a clean, one `createWriteRequest` for every file that matches, then `OriginalReplacer` in `BatchRun`, with progress.
- The summary says how many were replaced, how many were saved as new copies and why ("format changed", "not in your gallery", "you declined"), and how many were restored after a failure.
- **Open** and **Scan Result** point at the replaced file.
- Strict Clean, the Keep options and presets work as now. Replace only changes where the result goes.
- New strings go into `values/` and the 12 locales.

**Done when**

- [ ] A batch of 50 photos and videos with mixed formats replaces the matching ones, saves the rest as copies, and the summary adds up.
- [ ] Declining Android's dialog saves new copies and loses nothing.

## Phase 4. Gallery order

Depends on the Phase 1 answers.

**Deliverables**

- If Android allows it, after a replace Redact sets `DATE_TAKEN` back to the original's value in MediaStore. This keeps the place in the timeline. The date lives only in the phone's own media index, not in the file, so a shared file still has no date.
- If **Keep Date Taken** is on, the file keeps its date anyway, and this step isn't needed.
- If Android doesn't allow it, the setting's description says replaced photos may move to the date they were cleaned.

**Done when**

- [ ] A replaced photo stays at its place in Google Photos and the Files app on the test emulator, or the description says it won't.

## Phase 5. Reporting, docs and store

- A `clean.replace_original` counter with `outcome` (`replaced`, `kept_new_copy`, `restored`, `backup_saved`) and `reason` (`format_changed`, `not_media_store`, `declined`, `low_space`, `write_failed`), and `setting.after_cleaning` replacing `setting.delete_originals` as a tag. All through `AppMetrics` and `SentryManager`, with names that pass `SentryNamingTest`. Update both Names in Sentry tables, the "What Send Crash Reports captures" tables and Section 6 of `legal/redact.html` in the same commit.
- `README.md` and `site/index.html`, the Features row for Delete originals, the Settings table, and an FAQ answer about backups.
- Play listing text in every locale, where it mentions Delete Originals.
- `CLAUDE.md`, add `OriginalReplacer` next to `SecureDelete` as code that must keep backups safe.

## Test plan

| Test | Covers |
|---|---|
| `OriginalReplacerTest` (new) | Every step, failures injected at each, hash checks, restore, backup saved as a new file, space checks |
| `CacheCleanupTest` (extended) | Leftover backups after a killed process |
| `CleanFragmentTest`, `CleanConvertFragmentsTest` (extended) | The write request, declined request, summary counts, Open and Scan Result |
| `SettingsFragmentTest` (extended) | The three-way choice, migration from Delete Originals, the explanation |
| `/test` on `Pixel_10_Pro` | Replace a batch from Google Photos and the Files app, then check albums, timeline order and that each file is clean |

## Risks

| Risk | Mitigation |
|---|---|
| A write fails halfway and the original is lost | Backup, read-back check, restore, and the backup saved as a new file as the last resort |
| People think Replace removes their cloud backups | The one-time explanation, the setting's description and the FAQ say it doesn't |
| Photos jump to today in the timeline | Phase 4 restores the date column where Android allows, and says so where it doesn't |
| Backups of originals stay in private storage | `SecureDelete` on success, and `CacheCleanup` on start |
| A future Android changes what `createWriteRequest` allows | Declined or failed requests fall back to new copies, and nothing is lost |

## Open decisions

| Decision | Default |
|---|---|
| Default choice | Keep Originals, as today |
| Videos | Included when the format matches. Most MOV files become MP4 and are saved as new copies |
| Replace in Censor | Later, with the same `OriginalReplacer` |
| Ask again each batch | No. Android's dialog already asks each time |
