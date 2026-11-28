# Audio Cleaning roadmap

Lets Clean, Scan and Share into Redact handle audio files, so voice memos and music lose names, dates, locations and recording-app details before they're shared.

| | |
|---|---|
| **Status** | Planned |
| **Branch** | `dev` |
| **Size** | Medium. 6 phases |
| **Depends on** | The `MediaKind` refactor in Phase 1, shared with the [PDF Cleaning roadmap](pdf-cleaning.md). Whichever ships first does it |

## Contents

1. [Summary](#summary)
2. [Goals and non-goals](#goals-and-non-goals)
3. [What audio files carry](#what-audio-files-carry)
4. [Privacy and safety design](#privacy-and-safety-design)
5. [Phase 1. Media kinds](#phase-1-media-kinds)
6. [Phase 2. Reading audio metadata](#phase-2-reading-audio-metadata)
7. [Phase 3. Cleaning without re-encoding](#phase-3-cleaning-without-re-encoding)
8. [Phase 4. Verification](#phase-4-verification)
9. [Phase 5. App integration](#phase-5-app-integration)
10. [Phase 6. Reporting, docs and store](#phase-6-reporting-docs-and-store)
11. [Test plan](#test-plan)
12. [Risks](#risks)
13. [Open decisions](#open-decisions)

## Summary

Voice recorders on Samsung, Xiaomi and others save the recording date and sometimes the location, and music and podcast tools write names, emails, software and cover art. Today Redact refuses audio files. This roadmap adds them to Clean, Scan and Share into Redact.

Audio is cleaned by rewriting the file's structure and copying the sound frames byte for byte, so quality never drops. **Strict Clean** re-encodes to AAC in M4A instead, which also removes anything hidden inside the frames. Every result is checked the same way photos and videos are, and anything Redact can't vouch for is refused.

## Goals and non-goals

**Goals**

- Clean M4A (AAC, ALAC), 3GP and AMR voice notes, MP3, FLAC, Ogg (Vorbis, Opus) and WAV.
- Keep the sound bit-identical in a normal clean.
- Show audio metadata in Scan in plain words, with the Location badge for recorded locations.
- Use the system document picker, so no new permission is needed (no `READ_MEDIA_AUDIO`).
- Count audio in Statistics like photos and videos.

**Non-goals**

- Converting between audio formats in Convert.
- Censoring or muting parts of a recording.
- Voice changing or removing background sound.
- Keeping song info (title, artist, album, cover art) in the first release. See the [open decisions](#open-decisions).

## What audio files carry

| Format | Where metadata lives | Removed | Kept |
|---|---|---|---|
| M4A, 3GP, AMR-in-3GP | `udta`, `meta`/`ilst` (`©nam`, `©ART`, `©day`, `©too`, `covr`), `loci`, `©xyz`, XMP `uuid`, `mvhd`/`tkhd`/`mdhd` dates | All of them. Header dates zeroed | `ftyp`, `moov` structure, `mdat` frames |
| MP3 | ID3v2 at the start, ID3v1 (`TAG`) in the last 128 bytes, APEv2 tags, Lyrics3 | All tags | MPEG frames. The Xing/LAME header is kept in a normal clean (it holds seek info) and dropped by Strict Clean |
| FLAC | `VORBIS_COMMENT`, `PICTURE`, `APPLICATION`, `CUESHEET` blocks | Those blocks | `STREAMINFO`, `SEEKTABLE`, `PADDING` (zeroed), audio frames |
| Ogg Vorbis, Opus | The comment header packet (`vendor` string and comments) | Comments, and the vendor string set to empty | Identification and setup headers, audio pages. Page CRCs recomputed |
| WAV | `LIST`/`INFO`, `bext` (Broadcast Wave originator and date), `iXML`, `id3 `, `cart`, `axml`, `_PMX` (XMP) chunks | Those chunks | `fmt `, `data`, `fact` |

Anything else found in a file is handled under the fail-closed rule below.

## Privacy and safety design

| Rule | How |
|---|---|
| Fail closed | A chunk, box, block or tag the cleaner doesn't recognize is dropped if playback doesn't need it. If it can't tell, the clean fails with "Redact can't clean this audio file safely" and nothing is saved |
| Sound unchanged in a normal clean | The cleaner copies frames and records a SHA-256 of the audio payload in and out. They must match, the audio counterpart of `requireSameContent` |
| Hidden data inside frames | Normal clean keeps frames as they are, which can carry MP3 ancillary data or AAC fill elements, as the comment on `VideoStreamMetadata` already notes. **Strict Clean** re-encodes through Media3 Transformer to AAC in M4A, which drops them |
| Shared-in files always lose location | As with photos and videos, whatever the settings |
| Originals handled safely | Working copies through `InboundSnapshots`, temp files removed with `SecureDelete`, **Delete Originals After Cleaning** works the same way |
| No new permission | Picking uses `OpenMultipleDocuments`, already used by Clean and Scan. Output is written through `MediaStoreWrites` to the app's own entries. `release-manifest.txt` stays the same |

## Phase 1. Media kinds

Today many helpers take `boolean video`. Audio (and PDFs) need a third and fourth kind.

**Deliverables**

- `MediaKind`, an enum with `IMAGE`, `VIDEO`, `AUDIO` and `DOCUMENT`, from a MIME type.
- `OutputDestination`, `MediaStoreWrites`, `MediaFileNames`, `MediaUriResolver`, `MediaSizeLimits` and `BatchRun` take a `MediaKind` instead of `boolean video`. Images and videos behave exactly as before.
- `OutputDestination.defaultPath(AUDIO)` returns `Recordings/Redact` (`Environment.DIRECTORY_RECORDINGS`, available from API 31, the app's `minSdk`).
- `MediaPickerContracts` gets `CLEAN_MIME_TYPES` (`image/*`, `video/*`, `audio/*`).

**Done when**

- [ ] The full unit test suite passes with no behavior change for images and videos.
- [ ] No `boolean video` parameter is left in those helpers.

## Phase 2. Reading audio metadata

**Deliverables**

- `AudioContainerMetadata` in `metadata/`, with one reader per format. M4A and 3GP reuse the box walker in `VideoContainerMetadata`.
- `AudioContainerMetadata.scan(...)` returns every tag found with its name and a readable value, for Scan.
- `MetadataDisplayer` and `MetadataLabels` show audio tags in plain words ("Recorded on", "Recorded at", "Made with", "Artist").
- `CleanStats` maps audio tags to its categories (location, dates, names, device).

**Done when**

- [ ] Scan shows every tag from the fixture files for each format, with the Location badge where a location is present.

## Phase 3. Cleaning without re-encoding

**Deliverables**

- `AudioCleaner` in `metadata/`, created by `MetadataStripper` like `ImageCleaner` and `VideoCleaner`, with a new `MetadataStripper.cleanAudio(...)` and `cleanAudioForSharing(...)`.
- One rewriter per format, following [the table above](#what-audio-files-carry). M4A uses the same in-place `free`-box approach as `VideoContainerMetadata.scrub`, so offsets stay valid.
- **Keep Date Taken** keeps the recording date (`©day`, ID3 `TDRC`, `bext` date), with **Date Only** applied. **Keep Location** keeps `©xyz` or `loci`, rounded with **Approximate Location**. Other keep options don't apply to audio.
- **Strict Clean** transcodes through Transformer with audio only, to AAC in M4A at the source's bitrate or 192 kbps, whichever is lower.
- **Remove Audio** doesn't apply to audio files and is ignored for them.

**Done when**

- [ ] Every fixture comes out with no metadata from the table and the same audio payload hash.
- [ ] Strict Clean output plays and passes the video-style audio checks.

## Phase 4. Verification

**Deliverables**

- `CleanVerifier.requireAudioMetadataClean(...)` re-reads the output with `AudioContainerMetadata.scan`, a second parser path from the one that wrote it, and fails on any tag except those the keep options allow.
- `CleanVerifier.requireSameAudioContent(...)` compares the payload hashes in a normal clean.
- A raw byte search of the output for `ID3`, `TAG` at the end, `APETAGEX`, `LIST`, `bext`, `<x:xmpmeta`, and the original's location and date strings when they were removed. It fails closed if any appear.
- `AlreadyCleanCheck` learns audio, so **Warn About Already-Clean Files** works for it.

**Done when**

- [ ] A cleaner with a deliberate bug (leaving ID3v1, say) makes the clean fail, not succeed.
- [ ] Already-clean audio files are detected.

## Phase 5. App integration

**Deliverables**

- The **Clean** and **Scan** pickers accept `audio/*`.
- Share into Redact (`ShareHandlerActivity`) and **Scan with Redact** (`ScanShareTarget`) accept `audio/*`. In `AndroidManifest.xml`, add `audio/*` to their intent filters. That adds no exported component, so `release-manifest.txt` doesn't change. Confirm with `exportReleaseManifest`.
- Inbound URI checks in `ShareHandlerActivity` and `MainActivity` cover audio the same way. They stay at least as strict.
- File rows show a generic audio icon and the duration. Embedded cover art isn't shown, since the clean removes it.
- A new **Audio** output folder in Settings › Output Folders, with **Open Audio Folder**, like Images and Videos.
- Clean's summary card counts audio files with the right plural strings.
- All new strings in `values/` and the 12 locales.

**Done when**

- [ ] A voice memo shared from a recorder app comes back clean through the share sheet.
- [ ] Mixed batches of photos, videos and audio clean in one go.

## Phase 6. Reporting, docs and store

**Sentry**

- `file.media_type` gets the value `audio`. No new name. If the README lists its values, update the list there and in `site/index.html`.
- The clean runs inside the existing job transaction with a new span operation `audio_clean`, added to both Names in Sentry tables in the same commit. `SentryNamingTest` checks them.
- Nothing about a file's tags or contents is sent, as today. `SentryPrivacyScrubber` already scrubs file names and paths.

**Docs and store**

- `README.md` and `site/index.html`, Features, "What happens to your files", and the table of what Clean removes, with an audio row per format.
- Section 6 of `legal/redact.html` only if a new Sentry name is added.
- Play listing `full_description.txt` and `short_description.txt` in every locale, since "photos and videos" becomes "photos, videos and audio".
- `CLAUDE.md`, add `AudioCleaner` next to `ImageCleaner` and `VideoCleaner`, and the new verifier checks to the list of checks that must never be weakened.

## Test plan

| Test | Covers |
|---|---|
| `MediaKindTest` (new) | MIME to kind, including `audio/mp4` versus `video/mp4` by track type |
| `AudioContainerMetadataTest` (new) | Every format in the table, scan and display, including malformed and truncated files |
| `AudioCleanerTest` (new) | Each rewriter, the keep options, Strict Clean, unknown chunks dropped or refused |
| `CleanVerifierAudioTest` (new) | Clean output passes, a leftover tag fails, a changed payload fails |
| `AlreadyCleanCheckTest` (extended) | Audio already clean and not clean |
| `ShareHandlerActivityFlowsTest` (extended) | Shared audio, and audio refused when unsafe |
| `CleanConvertFragmentsTest`, `ScanFragmentTest` (extended) | Picker types, rows and summary for audio |
| `make_test_media.py` and `check_media.py` (extended) | Generate tagged audio for each format and check outputs, using the `mutagen` Python library for tags |
| `CleanOnDeviceTest` (extended, managed emulator) | Real files through real codecs, including Strict Clean |
| `/verifyclean` on `Pixel_10_Pro` | Clean every audio fixture and prove nothing identifying is left |

## Risks

| Risk | Mitigation |
|---|---|
| A rare chunk carries personal data | Unknown chunks are dropped or the clean is refused. Never kept |
| A rewrite makes a file unplayable | Payload hash check, plus `MediaExtractor` must open the output and report the same duration |
| Ogg page CRCs are wrong after the rewrite | Recompute every changed page, and verify by re-reading all pages |
| Large lossless files (WAV, FLAC) are slow | Streamed rewrite with `MediaSizeLimits.copyStream`, plus the existing low-storage warnings |
| People expect audio in Convert too | Out of scope, said plainly in the README |

## Open decisions

| Decision | Default |
|---|---|
| Add a **Keep Song Info** setting (title, artist, album, track, cover art) for music | Not in the first release |
| Default audio folder, `Recordings/Redact` or `Music/Redact` | `Recordings/Redact` |
| Strict Clean bitrate | Source bitrate or 192 kbps, whichever is lower |
