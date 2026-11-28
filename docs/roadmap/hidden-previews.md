# Hidden Previews roadmap

Lets Scan show the small preview pictures hidden inside a photo, not just their size, and warns when a preview shows more than the photo itself.

| | |
|---|---|
| **Status** | Planned |
| **Branch** | `dev` |
| **Size** | Small to medium. 4 phases |
| **Depends on** | Nothing |

## Contents

1. [Summary](#summary)
2. [Goals and non-goals](#goals-and-non-goals)
3. [Where previews hide](#where-previews-hide)
4. [Privacy and safety design](#privacy-and-safety-design)
5. [Phase 1. Extracting previews](#phase-1-extracting-previews)
6. [Phase 2. Showing them in Scan](#phase-2-showing-them-in-scan)
7. [Phase 3. Mismatch warning](#phase-3-mismatch-warning)
8. [Phase 4. Reporting, docs and store](#phase-4-reporting-docs-and-store)
9. [Test plan](#test-plan)
10. [Risks](#risks)
11. [Open decisions](#open-decisions)

## Summary

Many photos carry smaller copies of themselves: an EXIF thumbnail, a JFIF thumbnail, a Photoshop thumbnail, extra pictures in an MPF block, and in portrait-mode photos the whole original picture in XMP (`GImage:Data`). Some editors crop or blur the main picture and leave these copies as they were. Then the photo you share is cropped, and its preview still shows the whole scene.

Scan already lists these items, for example "EXIF thumbnail, 12 KB" (`MetadataDisplayer.java:619`), and Clean already removes them. But a size doesn't make anyone worry. Showing the preview itself does, and so does a plain warning when it shows something the photo doesn't.

## Goals and non-goals

**Goals**

- Every preview Scan finds can be opened and seen, at its own size.
- A warning when a preview doesn't match the photo: a different shape, or a different picture.
- The Clean result for the same file says the previews are gone.

**Non-goals**

- Previews in videos (cover art, poster frames). Video Clean re-encodes and drops them already, and Scan lists them. A later step if people ask.
- Showing depth maps and portrait mattes as pictures. They're grey maps, not previews, and Scan already explains them.
- Reading previews that need a decoder Android doesn't have.

## Where previews hide

| Where | Formats | Found today as | Shown after this roadmap |
|---|---|---|---|
| EXIF IFD1 thumbnail | JPEG, HEIC, AVIF, PNG and WebP with EXIF | `EXIF_THUMBNAIL` | Yes |
| JFIF APP0 thumbnail (RGB) and JFXX extension (JPEG, palette, RGB) | JPEG | `JFIF_THUMBNAIL` | Yes |
| Photoshop APP13 thumbnail resource (1036 or 1033) | JPEG | `PHOTOSHOP_THUMBNAIL` | Yes |
| MPF extra pictures, except an Ultra HDR gain map | JPEG | `EMBEDDED_IMAGES` | Yes. The gain map is shown as "HDR layer", not as a preview |
| XMP `GImage:Data` (the original picture before portrait blur) | JPEG | Summarized by `EmbeddedData` | Yes, marked "Original picture before editing" |
| HEIF thumbnail items (`thmb` references) | HEIC, AVIF | Listed with the other items | Yes, where `ImageDecoder` can decode the item on its own |

## Privacy and safety design

| Rule | How |
|---|---|
| Nothing written to disk | Previews are decoded from bytes in memory and shown from a `Bitmap`. No file, no Glide cache. Bitmaps are recycled when Scan moves on |
| A broken preview can't harm the app | Each preview is decoded with a size limit (its declared size and at most 4096 pixels on the longer side), and a failure shows "Can't be shown" rather than an error |
| Screenshots blocked like the rest of Scan | The preview sheet uses the same window flag as **Hide Content in Screenshots and Recents** |
| Nothing about previews reaches Sentry | Only counts through `AppMetrics`: how many previews were shown and how many mismatches were found. Never sizes, pictures or which file |
| Clean unchanged | Previews are already removed, and `CleanVerifier` already checks it. This roadmap only reads |

## Phase 1. Extracting previews

**Deliverables**

- `HiddenPreviews` in `metadata/`, which returns each preview as a record: where it was found, its byte range or decoded bytes, its declared size, and its kind (`THUMBNAIL`, `EXTRA_PICTURE`, `ORIGINAL_BEFORE_EDIT`, `HDR_LAYER`).
- It reuses the walkers that already find them: `ExifBlock` (IFD1 offset and length), `ImageContainerMetadata` (JFIF, Photoshop resources, MPF), `EmbeddedData` (XMP base64) and `HeifContainerMetadata` (thumbnail item references). It adds no second parser.
- JFIF RGB thumbnails, which are raw pixels and not a JPEG, are turned into a `Bitmap` directly.

**Done when**

- [ ] Every row of [the table](#where-previews-hide) is found in a fixture and decodes to the expected size.
- [ ] Damaged lengths and offsets give "Can't be shown", never an exception.

## Phase 2. Showing them in Scan

**Deliverables**

- In Scan's **Hidden & embedded data** section, each preview row gets a small picture beside it and opens a sheet with the preview at full size, its kind, its size in pixels and where it was found.
- With several files scanned, the summary counts "Photos with hidden previews".
- New strings go into `values/` and the 12 locales.

**Done when**

- [ ] The sheet works with TalkBack (the picture has a description of its kind and size), in light and dark, at 200% font size and in Arabic.

## Phase 3. Mismatch warning

**Deliverables**

- For each preview of kind `THUMBNAIL` or `EXTRA_PICTURE`, compare it with the main picture decoded at the same small size:
  - **Shape:** the aspect ratios differ by more than 3%, after allowing for orientation. This is the common case of a crop.
  - **Picture:** the mean difference of 16 x 16 greyscale versions is above a threshold tuned on the fixtures. This catches a blur or censor that left the preview alone.
- A preview of kind `ORIGINAL_BEFORE_EDIT` always gets the warning, since it is by definition the picture before editing.
- A mismatch shows an orange badge on the row and one line at the top of Scan: "This photo has a hidden preview that shows more than the photo itself. Clean removes it."
- The main picture is decoded once at small size for the comparison and never kept.

**Done when**

- [ ] A JPEG cropped in an editor that keeps the old thumbnail is flagged. The camera's original JPEG isn't.
- [ ] A photo blurred in one area with an untouched thumbnail is flagged.
- [ ] Fewer than 1 in 50 untouched camera photos in the fixture set are flagged.

## Phase 4. Reporting, docs and store

- `scan.hidden_preview` distribution (previews per file) and `scan.preview_mismatch` counter, through `AppMetrics`, with names that pass `SentryNamingTest`. Add them to both Names in Sentry tables, the "What Send Crash Reports captures" tables and Section 6 of `legal/redact.html` in the same commit.
- `README.md` and `site/index.html`, the Scan section's **Hidden & embedded data** bullet.
- A store screenshot of the warning, if `/generatescreenshots` shows Scan with it.

## Test plan

| Test | Covers |
|---|---|
| `HiddenPreviewsTest` (new) | Each source in the table, damaged lengths, size limits |
| `PreviewMismatchTest` (new) | Shape and picture comparison, orientation, the threshold on fixtures |
| `ScanFragmentTest` (extended) | Preview rows, the sheet, the warning line, the multi-file count |
| `make_test_media.py` (extended) | A cropped JPEG with its old EXIF thumbnail, a blurred one, a JFIF RGB thumbnail, a Photoshop thumbnail, a portrait photo with `GImage:Data`, a HEIC with a thumbnail item |
| `/test` on `Pixel_10_Pro` | Scan the fixtures and see each preview and warning |

## Risks

| Risk | Mitigation |
|---|---|
| False warnings on untouched photos | Tune on real camera photos, and require both a clear shape or picture difference |
| A huge or malicious preview uses too much memory | Declared size checked before decoding, and a hard pixel cap |
| Showing a preview surprises someone with what it shows | The sheet opens only on a tap, and the row says what it is first |

## Open decisions

| Decision | Default |
|---|---|
| Show the Ultra HDR gain map | No, it's a brightness map. Listed as "HDR layer" |
| Picture difference threshold | Tuned on the fixtures in Phase 3 |
| Warning in the Clean summary | No. Clean removes previews anyway. Scan is where people look |
