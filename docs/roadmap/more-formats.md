# More Formats roadmap

Lets Clean keep DNG raw photos as DNG instead of turning them into JPEGs, opens TIFF files Android can't decode, and confirms which video containers beyond MP4, MOV and WebM already work.

| | |
|---|---|
| **Status** | Planned |
| **Branch** | `dev` |
| **Size** | Medium to large. 6 phases. Phase 1 is about an hour, the rest is the TIFF rebuilder |
| **Depends on** | Nothing |

## Contents

1. [Summary](#summary)
2. [Goals and non-goals](#goals-and-non-goals)
3. [Where things stand](#where-things-stand)
4. [What DNG and TIFF carry](#what-dng-and-tiff-carry)
5. [Privacy and safety design](#privacy-and-safety-design)
6. [Phase 1. Video containers](#phase-1-video-containers)
7. [Phase 2. Reading TIFF and DNG](#phase-2-reading-tiff-and-dng)
8. [Phase 3. Rebuilding TIFF and DNG](#phase-3-rebuilding-tiff-and-dng)
9. [Phase 4. Verification](#phase-4-verification)
10. [Phase 5. App integration](#phase-5-app-integration)
11. [Phase 6. Docs and store](#phase-6-docs-and-store)
12. [Test plan](#test-plan)
13. [Risks](#risks)
14. [Open decisions](#open-decisions)

## Summary

Redact already rebuilds JPEG, PNG, WebP, HEIC, AVIF and GIF without touching a pixel, and re-encodes every video into a new file. Two gaps are left that people actually hit.

- **DNG.** Pixel and Samsung Pro modes save DNG raw photos. Today Clean decodes them and saves a JPEG at High quality, so a photographer loses the raw file they shot it for.
- **TIFF.** Android has no TIFF decoder, so Redact refuses it.

DNG is a TIFF underneath. One rebuilder that walks TIFF's IFDs and copies only what a viewer or raw developer needs fixes both. A separate, short check confirms whether MKV, 3GP, TS and AVI videos already clean correctly, since a clean always writes a new MP4 or WebM.

## Goals and non-goals

**Goals**

- Clean a DNG into a DNG that still opens in Lightroom, Snapseed, darktable and RawTherapee, with the raw data byte for byte.
- Clean a TIFF into a TIFF, with the picture byte for byte.
- Show a DNG's and TIFF's metadata in Scan in plain words.
- Know, with a test, which video containers Clean, Scan and Convert handle.

**Non-goals**

- JPEG XL. Android can't decode it, and bundling libjxl adds megabytes of native code and a parser that files from strangers would reach, for a format phones don't make.
- SVG. It's a drawing in XML, not a photo, and its "metadata" can be anything.
- Camera RAW formats other than DNG (CR3, NEF, ARW, RAF and others). Each is proprietary and Android can't decode them. People can convert them to DNG on a computer.
- Removing the sensor's own noise pattern from raw data. Like a JPEG's compression, a forensic examiner may still match a raw photo to a camera from its pixels. Converting to JPEG is the answer for that, as it is today.
- Censoring a DNG as a DNG. Censor keeps falling back to a JPEG for it.

## Where things stand

| Format | Today | After this roadmap |
|---|---|---|
| DNG | Decoded and saved as a JPEG at High quality (`README.md`, "Other images Android can open") | Rebuilt as a DNG, raw data unchanged |
| TIFF | Refused as unsupported | Rebuilt as a TIFF, picture unchanged |
| MKV, 3GP, TS, AVI video | Probably cleaned through the normal re-encode, untested | Tested. Scan reads their container metadata where it's missing |
| JPEG XL, SVG, other RAW | Refused | Refused (non-goals) |

`image/x-adobe-dng` is already in `SentryManager`'s known MIME types, so DNG needs no new Sentry name. `image/tiff` does, if it's added (see [Phase 6](#phase-6-docs-and-store)). The intent filters already accept `image/*` and `video/*`, so the manifest doesn't change.

## What DNG and TIFF carry

A TIFF keeps its picture and its metadata in the same structure: tags in IFDs, which is the same layout EXIF uses inside a JPEG. A DNG adds tags a raw developer needs to turn sensor data into a picture. Some look like metadata but aren't, so the allowlist has to tell them apart.

| Tags | What they reveal | Clean |
|---|---|---|
| `Make`, `Model`, `Software`, `Artist`, `Copyright`, `HostComputer`, `DateTime` | Device, person, software, date | Removed (camera and date tags kept with the matching keep options) |
| Exif IFD, including `BodySerialNumber`, `LensSerialNumber`, `ImageUniqueID`, `MakerNote` | Serial numbers, a unique fingerprint, the maker's own records | Removed, except the camera settings Keep Camera Settings keeps |
| GPS IFD | Location | Removed unless Keep Location is on |
| XMP (700), IPTC (33723), Photoshop (34377) | Captions, names, edit history | Removed |
| `UniqueCameraModel` (50708), `LocalizedCameraModel` (50709) | Camera model | Removed. Some developers use it to pick a camera profile, so this is a deliberate trade (see the open decisions) |
| `CameraSerialNumber` (50735), `RawDataUniqueID` (50781) | Serial number, a fingerprint linking copies | Removed |
| `DNGPrivateData` (50740) | The maker's private data | Removed |
| `OriginalRawFileName` (50827), `OriginalRawFileData` (50828), `OriginalRawFileDigest` | The original file's name, and the whole original raw file with all its metadata | Removed |
| `ProfileCopyright`, `CameraCalibrationSignature`, `ProfileCalibrationSignature` | Can name a person or a calibration lab | Removed |
| Embedded preview JPEGs (in SubIFDs or IFD0) | Their own EXIF and XMP | Rebuilt with `ImageCleaner`'s JPEG rules, or left out (see the open decisions) |
| `ColorMatrix1/2`, `CalibrationIlluminant1/2`, `AsShotNeutral`, `BlackLevel`, `WhiteLevel`, `CFAPattern`, `CFARepeatPatternDim`, `LinearizationTable`, `DefaultCrop*`, `ActiveArea`, `BaselineExposure`, `OpcodeList1/2/3`, `ProfileName`, `ProfileHueSatMap*`, `ProfileToneCurve`, `DNGVersion`, `DNGBackwardVersion` | How to develop the raw data | Kept. Without them the file won't open or its colours are wrong |
| `ImageWidth`, `ImageLength`, `BitsPerSample`, `Compression`, `PhotometricInterpretation`, strip and tile offsets and counts, `Orientation`, `ICCProfile` | How to read and show the picture | Kept. The ICC profile goes through `IccProfiles` like any other |

## Privacy and safety design

| Rule | How |
|---|---|
| Allowlist, not blocklist | Only the tags listed as kept above are copied. Any tag Redact doesn't know, including private tags above 32768, is left out |
| Fail closed | A TIFF or DNG with a layout the rebuilder can't fully walk (offsets pointing outside the file, overlapping strips, more IFDs than a sane limit) isn't saved. A DNG falls back to today's JPEG, which `CleanVerifier` already checks. A TIFF is refused |
| Pixels unchanged | Strip and tile bytes are copied as they are, never decoded and encoded again |
| Independent check | `CleanVerifier` re-reads the output with its own walker (Phase 4) |
| Originals handled safely | `InboundSnapshots`, `SecureDelete` on every working copy, and **Delete Originals After Cleaning** as for any photo |
| No new permission or network | `release-manifest.txt` stays the same |

## Phase 1. Video containers

A clean always writes a new MP4 or WebM and checks the output, so other containers may already work. This phase finds out before any code is written.

**Deliverables**

- MKV (H.264 and AAC), 3GP (H.263 and AMR), MPEG-TS (H.264) and AVI (MPEG-4 Part 2) fixtures in `.claude/scripts/make_instrumented_media.py`, each with a title, date and location tag in its container.
- `CleanOnDeviceTest` cases that clean each one and check the output like `check_media.py`.
- A Scan check of each fixture. `VideoContainerMetadata` walks MP4, MOV and Matroska, so TS and AVI are expected to show less.

**Done when**

- [ ] Each fixture's result (cleaned, refused, or failed) is written into this roadmap.
- [ ] Any container that fails gets its own fix, or is listed in `README.md` as unsupported.
- [ ] If Scan misses container tags in TS or AVI that a clean removes, a small reader for them is added to `VideoContainerMetadata`, or Scan says the container isn't read.

## Phase 2. Reading TIFF and DNG

**Deliverables**

- `TiffContainerMetadata` in `metadata/` that walks a TIFF's IFDs, SubIFDs, Exif IFD and GPS IFD, in both byte orders and in BigTIFF, with limits on IFD count, tag count and offsets.
- TIFF magic bytes (`II*\0`, `MM\0*`, and BigTIFF's `II+\0`, `MM\0+`) in `FormatConverter.sniffImageType` and `ImageContainerMetadata.sniff`. A DNG is told apart by its `DNGVersion` tag, not its name.
- `MetadataDisplayer`, `MetadataSurvey` and `CleanStats` show and count what it finds, with the Location badge for a GPS IFD.

**Done when**

- [ ] Scan lists every row of [the table](#what-dng-and-tiff-carry) found in the fixtures.
- [ ] A damaged or looping IFD chain is read safely and reported as unreadable.

## Phase 3. Rebuilding TIFF and DNG

**Deliverables**

- `TiffContainerMetadata.rebuild(...)` writes a new file with only the kept tags, the strips or tiles copied byte for byte, and fresh offsets.
- `ImageCleaner` routes TIFF and DNG to it through `resolveCleanFormat`, which gains `.dng` (`image/x-adobe-dng`) and `.tif`/`.tiff` (`image/tiff`) specs.
- Keep Camera Settings, Keep Date Taken and Keep Location write their tags back into a new Exif or GPS IFD, reusing `ExifTags`. Strict Clean and a share keep only orientation and colour space.
- `AlreadyCleanCheck` learns both MIME types.
- `isLosslessImage` treats TIFF as lossless, so Convert to WebP stays lossless.

**Done when**

- [ ] Every fixture comes out with nothing from the "Removed" rows and opens in at least two raw developers with the same picture as the original.
- [ ] The raw and picture bytes of output and source match.

## Phase 4. Verification

**Deliverables**

- `CleanVerifier` re-walks every rebuilt TIFF and DNG and fails if any tag outside the allowlist is present, if a GPS or Exif IFD holds a tag the keep options didn't ask for, or if an embedded preview has metadata.
- `requireSameContent` compares the strip and tile bytes with the source.
- Failures delete the output, and a DNG falls back to the JPEG path.

**Done when**

- [ ] A rebuilder with a deliberate bug (keeping `MakerNote`, keeping `OriginalRawFileData`) makes the save fail.

## Phase 5. App integration

**Deliverables**

- Output keeps the source's format, `.dng` in `Pictures/Redact`, through `MediaStoreWrites`.
- Convert accepts TIFF as a source through `TiffContainerMetadata` reading the first picture, since `ImageDecoder` can't. Uncompressed and LZW strips only at first. Anything else is refused with the usual unsupported message.
- Censor keeps saving a JPEG for DNG and TIFF.
- The large-file warning in `ProcessingResourceWarnings` accounts for DNGs, which are often 20 to 80 MB.
- Any new strings go into `values/` and the 12 locales.

**Done when**

- [ ] A DNG shot on the test emulator's camera in Pro mode, shared from Files, comes back as a clean DNG.

## Phase 6. Docs and store

- `README.md` and `site/index.html`, the "What happens to your files" table, with rows for DNG and TIFF replacing "Other images Android can open, such as DNG" and the TIFF part of "Images Android can't open".
- `image/tiff` in `SentryManager`'s known MIME types if TIFF ships, which changes what `file.mime_type` can hold. Update both "What Send Crash Reports captures" tables and Section 6 of `legal/redact.html` in the same commit.
- `TiffContainerMetadata` in the README's code map, and in `CLAUDE.md`'s list of checks never to weaken.
- Play listing text in every locale if the feature list changes.

## Test plan

| Test | Covers |
|---|---|
| `TiffContainerMetadataTest` (new) | Both byte orders, BigTIFF, SubIFDs, every kept and removed tag, damaged and looping IFDs, size limits |
| `ImageCleanerTest` (extended) | DNG and TIFF routing, keep options, the JPEG fallback |
| `CleanVerifierTest` (extended) | A leftover tag of each removed kind fails the save |
| `FormatConverterTest` (extended) | Sniffing, specs, lossless detection |
| `make_test_media.py` and `check_media.py` (extended) | DNG and TIFF fixtures with every removed tag, a GPS IFD, an embedded preview with EXIF and `OriginalRawFileData` |
| `make_instrumented_media.py` and `CleanOnDeviceTest` (extended) | The Phase 1 video containers, and a DNG on real codecs |
| `/verifyclean` on `Pixel_10_Pro` | Every new fixture cleaned and checked |

## Risks

| Risk | Mitigation |
|---|---|
| A cleaned DNG won't open, or its colours shift, because a needed tag was removed | Test in several raw developers. Grow the allowlist only from the DNG specification, never by keeping unknown tags |
| A maker hides data in a tag the allowlist keeps | Kept tags have fixed types and counts in the specification. The verifier checks them |
| A malicious TIFF exploits the walker | Hard limits on IFDs, tags and offsets, no recursion without a depth limit, and fuzz tests on the walker |
| Large DNGs run out of memory | Strips and tiles are streamed with `MediaSizeLimits.copyStream`, never held whole |
| Phase 1 finds a container that fails | Fix it or document it. Either way the answer is written down |

## Open decisions

| Decision | Default |
|---|---|
| Remove `UniqueCameraModel` | Yes. It names the camera, and developers fall back to the colour matrices without it. Revisit if a major developer breaks |
| Embedded DNG previews | Keep the largest, rebuilt like a JPEG. Drop the rest |
| Ship TIFF with DNG | Yes, it's the same code. TIFF in Convert can follow |
| Keep the JPEG fallback for DNGs the rebuilder refuses | Yes, so a DNG is never refused outright |
