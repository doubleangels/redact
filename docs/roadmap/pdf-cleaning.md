# PDF Cleaning roadmap

Lets Clean, Scan and Share into Redact handle PDFs, removing author names, editing software, dates, hidden revisions and the photo data inside embedded images. A **Flatten** option makes a guaranteed-clean copy by turning each page into an image.

| | |
|---|---|
| **Status** | Planned |
| **Branch** | `dev` |
| **Size** | Large. 7 phases |
| **Depends on** | The `MediaKind` refactor from the [Audio Cleaning roadmap](audio-cleaning.md#phase-1-media-kinds). Whichever ships first does it |

## Contents

1. [Summary](#summary)
2. [Goals and non-goals](#goals-and-non-goals)
3. [What PDFs carry](#what-pdfs-carry)
4. [Privacy and safety design](#privacy-and-safety-design)
5. [Phase 1. Library choice](#phase-1-library-choice)
6. [Phase 2. Reading PDF metadata](#phase-2-reading-pdf-metadata)
7. [Phase 3. Flatten](#phase-3-flatten)
8. [Phase 4. Clean, keeping text](#phase-4-clean-keeping-text)
9. [Phase 5. Verification](#phase-5-verification)
10. [Phase 6. App integration](#phase-6-app-integration)
11. [Phase 7. Reporting, docs and store](#phase-7-reporting-docs-and-store)
12. [Test plan](#test-plan)
13. [Risks](#risks)
14. [Open decisions](#open-decisions)

## Summary

PDFs often leak more than photos. The author field holds a real name, the producer names the software and its version, hidden earlier revisions keep deleted text, comments carry their author's name, and photos inside the document still have their GPS data. This roadmap adds PDFs in two ways.

- **Clean** rewrites the PDF without its metadata and keeps text selectable. It needs a PDF library and handles most everyday documents.
- **Flatten** draws each page as an image and writes a brand-new PDF with nothing else in it. Text is no longer selectable, but nothing hidden can survive. It's also the fallback whenever Clean can't vouch for a document.

Flatten ships first, because it's simple, needs no new library and fails closed by design.

## Goals and non-goals

**Goals**

- Remove everything in [the table below](#what-pdfs-carry) from everyday PDFs (forms, scans, exports from Word or Google Docs).
- Offer Flatten for anything Clean can't handle safely, and for people who want certainty.
- Show a PDF's metadata in Scan in plain words.
- No new permission. Picking uses the system document picker, and output goes to `Documents/Redact`.

**Non-goals**

- Office files (DOCX, XLSX, PPTX). They're a separate roadmap.
- Censoring parts of a page.
- Opening password-protected PDFs. Redact asks the user to remove the password first.
- Keeping digital signatures valid. Any change breaks a signature, and Redact says so before cleaning.
- OCR to make flattened pages searchable again.

## What PDFs carry

| Where | What it can reveal | Clean | Flatten |
|---|---|---|---|
| Document info (`/Info`: Author, Creator, Producer, Title, Subject, Keywords, CreationDate, ModDate, custom keys) | Names, software and version, dates | Removed | Not written |
| XMP metadata streams (catalog and any object's `/Metadata`) | The same, plus edit history and document IDs | Removed | Not written |
| Trailer `/ID` | A permanent fingerprint that links copies of a document | Replaced with random bytes | Random |
| Incremental updates | Earlier versions, including deleted text | Dropped by a full rewrite | Not written |
| Annotations (`/T` author, `/M` date, popups, comments) | Reviewers' names and dates | Author and date removed. Comments kept unless the user removes them | Drawn into the page, then gone |
| Embedded files and attachments | Anything | Removed | Not written |
| JavaScript and actions (`/JS`, `/OpenAction`, `/AA`, `/Launch`, `/URI` with tracking) | Scripts, tracking links | Removed | Not written |
| Page thumbnails (`/Thumb`) | Old versions of pages | Removed | Not written |
| Private app data (`/PieceInfo`, Illustrator and InDesign data) | Full original editing files | Removed | Not written |
| JPEG images (`/DCTDecode`) | EXIF with GPS, camera, dates | APP segments stripped without re-encoding, with the same logic as `ImageCleaner` | Redrawn |
| Form field values | Whatever was typed | Kept, with a notice | Drawn into the page |
| Fonts, page content | Nothing personal | Kept | Drawn |

## Privacy and safety design

| Rule | How |
|---|---|
| Fail closed | Encrypted, damaged or unusual PDFs (XFA forms, unknown structures) aren't cleaned. Redact offers Flatten instead, or refuses if the page can't be drawn |
| No earlier revisions survive | Clean always writes a full new file. It never appends an incremental update |
| Independent check | Every output is re-read by a second, simpler parser that doesn't share code with the writer (Phase 5) |
| Flatten writes only what Redact controls | A minimal PDF writer in Redact produces page images and the structure needed to show them. No `/Info`, no XMP, random `/ID`. Android's own `PdfDocument` isn't used, because it writes its own producer and creation date |
| Embedded images are clean | Every JPEG stream is stripped and then checked like a cleaned photo |
| Signed PDFs | Redact warns that cleaning will break the signature, and the user decides |
| Originals handled safely | `InboundSnapshots`, `SecureDelete` for temp files and page bitmaps, and **Delete Originals After Cleaning** as for photos |
| No new permission or network | `release-manifest.txt` stays the same, and the library must make no network calls (checked in Phase 1) |

## Phase 1. Library choice

Clean needs a PDF parser and writer. Redact is GPLv3, so the library must be GPL-compatible.

| Option | License | Notes |
|---|---|---|
| **PDFBox-Android** (`com.tom-roush:pdfbox-android`) | Apache 2.0 | A port of Apache PDFBox, full read and write. Recommended if it's still maintained and passes the checks below |
| iText 7 / 8 | AGPL 3.0 or commercial | Strong, but AGPL terms complicate a GPLv3 app. Not recommended |
| Writing a parser in Redact | GPLv3 | Full control, but PDF parsing is notoriously hard and a bug there is a leak. Only for the verifier's narrow checks |

**Checks before adopting a library**

- Last release and open security issues. PDF parsers are a common attack surface.
- APK size after R8, measured with `bundleRelease`.
- No `INTERNET` use or new manifest entries. `check_release_manifest.py` catches manifest changes, and a code search plus a run with the network blocked confirms the rest.
- R8 rules it needs, added to `proguard-rules.pro` without keeping more than necessary.

**Done when**

- [ ] The choice and its measured size are written into this roadmap.
- [ ] `bundleRelease` and `check_release_manifest.py` pass with the library added.

## Phase 2. Reading PDF metadata

**Deliverables**

- `PdfMetadata` in `metadata/`, built on the chosen library, that lists everything from [the table](#what-pdfs-carry) found in a file.
- Scan shows it in plain words ("Author", "Made with", "Created on", "Earlier versions inside: 3", "Comments by: 2 people", "Photos with location: 1"), with the Location badge when an embedded image has GPS.
- `CleanStats` maps PDF findings to its categories.

**Done when**

- [ ] Scan shows every item for the fixture PDFs, including hidden revisions and image GPS.

## Phase 3. Flatten

**Deliverables**

- `PdfFlattener` renders each page with `android.graphics.pdf.PdfRenderer` at 200 dpi (as a setting, see the open decisions) into a bitmap, encodes it as JPEG at high quality, and writes it with `MinimalPdfWriter`.
- `MinimalPdfWriter` writes only a catalog, page tree, pages, image XObjects and content streams that draw them, a cross-reference table and a trailer with a random `/ID`. It's about 200 lines and fully unit-tested.
- Page size and orientation match the source.
- Bitmaps are recycled page by page, and temp files go through `SecureDelete`.

**Done when**

- [ ] A flattened PDF opens in common viewers and contains nothing but what `MinimalPdfWriter` writes.
- [ ] A 100-page document flattens without running out of memory on a low-RAM device.

## Phase 4. Clean, keeping text

**Deliverables**

- `PdfCleaner` in `metadata/`, created by `MetadataStripper`, with `cleanPdf(...)` and `cleanPdfForSharing(...)`.
- It removes everything marked "Removed" in [the table](#what-pdfs-carry), strips EXIF and other APP segments from every JPEG stream, replaces `/ID`, and saves a full rewrite.
- Refuses and offers Flatten for encrypted files, XFA forms, files the library can't fully parse, and any object type it doesn't recognize where metadata could hide.
- **Strict Clean** always flattens, for people who want certainty.
- **Keep Date Taken** and the other keep options don't apply to PDFs.

**Done when**

- [ ] Each fixture comes out with nothing from the table and text still selectable.
- [ ] Unsupported files offer Flatten and never save a partial clean.

## Phase 5. Verification

**Deliverables**

- `PdfVerifier`, a small separate checker that doesn't use the writing library. It decompresses every stream and object stream and fails if it finds `/Info` with any key, `/Metadata`, `<x:xmpmeta`, `/EmbeddedFiles`, `/JavaScript`, `/JS`, `/OpenAction`, `/Thumb`, `/PieceInfo`, an annotation `/T`, more than one `%%EOF` (a sign of incremental updates), or the original's `/ID`.
- Every JPEG stream is checked with the same rules as `CleanVerifier.verifyMetadataRemoval` for photos.
- `CleanVerifier.requirePdfClean(...)` runs it for both Clean and Flatten. It fails closed and deletes the output.
- `AlreadyCleanCheck` learns PDFs.

**Done when**

- [ ] A cleaner with a deliberate bug (leaving `/Author`, keeping a revision) makes the save fail.
- [ ] The verifier has its own fixtures for every rule.

## Phase 6. App integration

**Deliverables**

- **Clean** and **Scan** pickers accept `application/pdf`.
- Share into Redact and **Scan with Redact** accept `application/pdf`. In `AndroidManifest.xml`, add it to their intent filters. That adds no exported component, so `release-manifest.txt` doesn't change. Confirm with `exportReleaseManifest`.
- Inbound URI checks in `ShareHandlerActivity` and `MainActivity` cover PDFs the same way.
- Before cleaning a PDF, a short sheet offers **Clean** (keeps text) or **Flatten** (most private, text no longer selectable), and warns if the PDF is signed or has filled-in forms.
- Output to `Documents/Redact` through `MediaStoreWrites` (Android 11 and later let apps write any file type to `Documents/`), with a new **Documents** folder in Settings › Output Folders.
- File rows show the first page as a thumbnail (rendered with `PdfRenderer`, never cached to disk) and the page count.
- All new strings in `values/` and the 12 locales.

**Done when**

- [ ] A PDF shared from Files or Gmail comes back clean through the share sheet.
- [ ] Mixed batches with photos, videos and PDFs work.

## Phase 7. Reporting, docs and store

**Sentry**

- `file.media_type` gets the value `document`, and new span operations `pdf_clean` and `pdf_flatten`. Add them to both Names in Sentry tables in the same commit.
- Library errors are reported through `SentryManager` with `error_type` only. Messages from the library can quote document content, so they go through `SentryPrivacyScrubber`, and a test confirms a PDF title in an exception message doesn't reach Sentry.

**Docs and store**

- `README.md` and `site/index.html`, Features, "What happens to your files", and a PDF section of the table of what Clean removes.
- The Open-Source Licenses screen lists the new library automatically through `OpenSourceLicenses`. Confirm it appears.
- Section 6 of `legal/redact.html` for the new Sentry names.
- Play listing `full_description.txt` and `short_description.txt` in every locale.
- `CLAUDE.md`, add `PdfCleaner`, `PdfFlattener` and `PdfVerifier`, and add `PdfVerifier` to the checks that must never be weakened.

## Test plan

| Test | Covers |
|---|---|
| `MinimalPdfWriterTest` (new) | The exact structure written, random `/ID`, nothing else |
| `PdfFlattenerTest` (new, Robolectric where `PdfRenderer` allows, otherwise instrumented) | Page sizes, rotation, memory per page, temp files removed |
| `PdfMetadataTest` (new) | Every row of the table found in fixtures |
| `PdfCleanerTest` (new) | Every row removed, JPEG EXIF stripped, refusals for encrypted, XFA and damaged files |
| `PdfVerifierTest` (new) | One fixture per rule that must fail, and clean fixtures that must pass |
| `SentryPrivacyScrubberTest` (extended) | Document text in library exceptions is scrubbed |
| `ShareHandlerActivityFlowsTest`, `CleanConvertFragmentsTest`, `ScanFragmentTest` (extended) | Picker types, the Clean or Flatten sheet, rows and summary |
| `make_test_media.py` and `check_media.py` (extended) | Fixture PDFs with author, XMP, revisions, comments, attachments, JavaScript and a GPS-tagged photo, made with `pypdf` |
| `/verifyclean` on `Pixel_10_Pro` | Clean and flatten every fixture and prove nothing identifying is left |

## Risks

| Risk | Mitigation |
|---|---|
| A PDF structure hides data the cleaner misses | Independent verifier, fail closed, and Flatten as the fallback |
| A malicious PDF exploits the parser | Choose a maintained library, keep it updated through `/dependencies`, parse only in the app's sandbox, and never on an exported component without the inbound checks |
| The library makes the app much larger | Measure in Phase 1, and keep Flatten-only as a fallback plan if the size isn't acceptable |
| People expect text to stay selectable after Flatten | The choice sheet says so in plain words, and Clean is the default when it's safe |
| Large documents use too much memory when flattened | One page at a time, with bitmaps recycled, and the existing resource warnings |
| Signed documents break | Warning before cleaning, and the user decides |

## Open decisions

| Decision | Default |
|---|---|
| Library | PDFBox-Android, if the Phase 1 checks pass |
| Ship Flatten alone first | Yes. It needs no library and is safe by design |
| Flatten resolution | 200 dpi, with a choice of 150 or 300 later if people ask |
| Remove comments in Clean by default | No, keep comments but remove their authors and dates, and offer a switch |
| Office files | A separate roadmap |
