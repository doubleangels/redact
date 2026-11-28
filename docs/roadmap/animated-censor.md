# Animated Censor roadmap

Lets Censor cover areas in every frame of an animated GIF, WebP or PNG and save it still animated, instead of keeping only the first frame.

| | |
|---|---|
| **Status** | Planned |
| **Branch** | `dev` |
| **Size** | Medium. 6 phases |
| **Depends on** | Nothing new in the APK. Reuses `CensorEffects`, the video timeline from [Video Censor](video-censor.md), and the container walkers Clean uses |

## Contents

1. [Summary](#summary)
2. [Goals and non-goals](#goals-and-non-goals)
3. [Privacy and safety design](#privacy-and-safety-design)
4. [Phase 1. Reading frames](#phase-1-reading-frames)
5. [Phase 2. Writing frames](#phase-2-writing-frames)
6. [Phase 3. Export and verification](#phase-3-export-and-verification)
7. [Phase 4. Editor](#phase-4-editor)
8. [Phase 5. Time ranges](#phase-5-time-ranges)
9. [Phase 6. Reporting, docs and store](#phase-6-reporting-docs-and-store)
10. [Test plan](#test-plan)
11. [Risks](#risks)
12. [Open decisions](#open-decisions)

## Summary

Censor saves a GIF as a PNG of its first frame, and an animated WebP or PNG keeps only its first frame. Reaction GIFs, short screen recordings and stickers lose their animation, so people either send the original or don't use Redact.

Android can decode these animations only as a drawable it plays, not frame by frame, and can't write an animated file at all. So this roadmap:

- reads frames with Redact's own code, built on the walkers Clean already uses to rebuild these formats (`GifContainerMetadata`, `ImageContainerMetadata`);
- covers each full frame with `CensorEffects`, as on a photo;
- writes each frame whole, never as a change from the frame before, using Android's still encoders for WebP and PNG and a small GIF writer;
- checks every frame of the saved file before it reaches the gallery.

## Goals and non-goals

**Goals**

- Animated GIF, WebP and PNG come out in the same format, animated, with the same frame timings and loop count.
- Every area covers every frame, with the same effects and the same safety as on a photo.
- Uncovered pixels stay as they were wherever the format allows: always for lossless WebP and PNG, and for GIF whenever a frame fits in 256 colours (see [Phase 2](#phase-2-writing-frames)).
- Later, areas with time ranges, using the video timeline.

**Non-goals**

- Animated HEIC and AVIF. Android can't write them, and they're rare. They keep today's behaviour.
- Moving areas in animations. Time ranges come in Phase 5. Keyframes can follow if people ask.
- Smaller files. Writing whole frames makes the file bigger than the source. Correctness comes first.
- Turning a GIF into a video. Convert does that.

## Privacy and safety design

| Rule | How |
|---|---|
| No frame is missed | Every frame is composited onto the full canvas by the format's own blend and disposal rules before it's covered, so a frame that only changes a few pixels is covered as it's seen |
| Nothing shows through from the original | Output frames are whole and opaque where the canvas is, with "replace" blending and no disposal tricks, so no pixel from an uncovered earlier frame can show |
| The covered detail can't be recovered | `CensorEffects` per frame, with fresh noise per frame, as on photos and video |
| Independent check | The verifier decodes the saved frames with code that doesn't share the compositing path. For GIF it uses Glide's `StandardGifDecoder`, already in the app. For WebP and PNG it decodes each saved frame on its own with `BitmapFactory`, which works because saved frames are whole |
| The saved file is clean | The output is rebuilt by the same container code as a Clean (`GifContainerMetadata` and `ImageContainerMetadata`) and checked by `CleanVerifier.verifyMetadataRemoval` |
| Fail closed | A frame that can't be decoded, an animation over the size limit, or a failed check saves nothing |
| Working copies | Decoded frames live in memory one or two at a time. Any spill to disk goes through `SecureDelete` |

## Phase 1. Reading frames

**Deliverables**

- `AnimatedFrames` in `media/`, an iterator of full-canvas frames (`Bitmap`, duration in milliseconds) plus the loop count, for:
  - **GIF:** LZW decoding of each image block, local and global colour tables, transparency, and disposal methods 1 to 3. Block positions come from `GifContainerMetadata`'s walk.
  - **WebP:** each `ANMF` frame's `VP8 `, `VP8L` or `ALPH` + `VP8 ` payload wrapped as a still WebP and decoded with `BitmapFactory`, then placed by its offset with its blend and dispose flags.
  - **APNG:** each frame's `fdAT` data turned back into a still PNG (its `IHDR` with the frame's size and `IDAT` chunks) and decoded with `BitmapFactory`, then placed by `fcTL`'s offset, blend and dispose ops.
- A size limit: canvas width x height x frames at most 400 megapixels, and at most 1000 frames. Over it, Censor says the animation is too large and offers the first frame only, as today.
- 16-bit APNG is decoded at 8 bits and noted in the result, since the animation path writes 8-bit PNG frames.

**Done when**

- [ ] Each fixture's frames match a reference decode (from Pillow, in `make_test_media.py`) pixel for pixel.
- [ ] Damaged frames, wrong offsets and loops stop with an error, never an exception or a hang.

## Phase 2. Writing frames

**Deliverables**

- `AnimatedWriter` in `media/`:
  - **WebP:** each covered frame through `Bitmap.compress` (`WEBP_LOSSLESS` when the source is lossless, by `FormatConverter.isLosslessImage`, otherwise `WEBP_LOSSY` at the source's quality class), its `VP8L` or `VP8 ` and `ALPH` chunks moved into an `ANMF` at offset 0 with full canvas size, no blending and no disposal. Then `VP8X` with the animation flag and `ANIM` with the loop count and background.
  - **APNG:** each covered frame through `Bitmap.compress(PNG)`, its `IDAT` data written as `IDAT` for the first frame and `fdAT` with sequence numbers after it, each with an `fcTL` covering the whole canvas with `APNG_DISPOSE_OP_NONE` and `APNG_BLEND_OP_SOURCE`. Then `acTL` with the frame count and loop count.
  - **GIF:** a writer for full-canvas frames with a local colour table per frame and LZW compression. When the composited frame has at most 256 colours, which is usual, the table is exact and no uncovered pixel changes. When it has more, the frame is reduced to 256 colours with median cut, without dithering inside covered blocks, and the export notes that colours changed.
- Frame delays are kept to the millisecond for WebP and APNG, and to the 10 ms GIF unit for GIF.

**Done when**

- [ ] A round trip with no areas gives the same pixels in every frame for lossless WebP, APNG and a GIF within 256 colours per frame.
- [ ] The saved files play in Chrome, Firefox, Google Photos and the Files app.

## Phase 3. Export and verification

**Deliverables**

- `CensorExporter` routes an animated source to `AnimatedFrames`, covers each frame with `CensorEffects.apply` (the same regions, a new `SecureRandom` draw per frame), applies the crop to every frame, and writes it with `AnimatedWriter`.
- The output is rebuilt by `GifContainerMetadata` or `ImageContainerMetadata`, as a Clean is, so only the blocks and chunks a player needs remain.
- `CensorVerifier.requireAnimationCovered(...)` checks that:
  - the frame count and total duration match the source;
  - each saved frame, decoded on its own (see the [safety design](#privacy-and-safety-design)), has every area uniform per block, as `requireAreasCovered` checks a photo.
- `CleanVerifier.verifyMetadataRemoval` runs on the output, as for every Censor save.

**Done when**

- [ ] A writer with a deliberate bug (one frame left uncovered, a delta frame that lets the original show) makes the save fail.
- [ ] An animated file with location in its XMP comes out with none.

## Phase 4. Editor

**Deliverables**

- An animated source opens in Censor with the video timeline above it: frames as stills, with the playhead stepping frame by frame. The video timeline code is reused, fed by `AnimatedFrames` instead of `MediaMetadataRetriever`.
- In this phase every area covers every frame. A line under the timeline says so.
- The brush works, since frames are bitmaps covered like a photo.
- A frame count and "Saved as an animated GIF" (or WebP or PNG) in the save summary.
- New strings go into `values/` and the 12 locales.

**Done when**

- [ ] A 200-frame GIF opens, scrubs and saves on the test emulator without running out of memory.

## Phase 5. Time ranges

**Deliverables**

- Areas get start and end times, like video areas, using `VideoCensorRegion`'s ranges and the same timeline sliders. Times are rounded outward to whole frames.
- `requireAnimationCovered` checks each area only in its frames, and checks the frames just outside a range aren't affected only where another area doesn't overlap.

**Done when**

- [ ] An area covering frames 10 to 20 covers exactly those frames, and the check fails if frame 20 is left out.

## Phase 6. Reporting, docs and store

- A `censor.animation_save` transaction and a `censor.animation_save.frame_count` distribution through `SentryManager` and `AppMetrics`, a `verify.censor_animation` span, and `file.mime_type` values that already exist (`image/gif`, `image/webp`, `image/png`). Add the new names to both Names in Sentry tables, the "What Send Crash Reports captures" tables and Section 6 of `legal/redact.html` in the same commit.
- `README.md` and `site/index.html`, the "Censor a photo" rows of "What happens to your files" (GIF stays GIF and animated, animated WebP and PNG stay animated) and the Censor section.
- `CLAUDE.md`, add `requireAnimationCovered` to the checks that must never be weakened.

## Test plan

| Test | Covers |
|---|---|
| `AnimatedFramesTest` (new) | GIF, WebP and APNG decoding against Pillow references, every disposal and blend mode, size limits, damaged files |
| `AnimatedWriterTest` (new) | Round trips, chunk layout, loop counts, delays, the 256-colour path and the reduced path |
| `CensorVerifierTest` (extended) | `requireAnimationCovered` passes on good output and fails on each deliberate bug |
| `CensorExporterTest` (extended) | Routing, crop on every frame, metadata removed |
| `CensorViewModelTest` (extended) | The timeline for animations, Phase 5 ranges |
| `make_test_media.py` and `check_media.py` (extended) | Animated GIF (delta frames, transparency, local tables), lossy and lossless animated WebP, APNG with each dispose op, each with XMP location |
| `/test` on `Pixel_10_Pro` | Censor and save each fixture, then play it in Files and Google Photos |

## Risks

| Risk | Mitigation |
|---|---|
| A compositing bug shows an original pixel in a frame | Whole output frames, and a verifier that decodes saved frames without the compositing code |
| Files grow a lot | Expected. Shown in the save summary as the new size. Delta frames for uncovered parts can come later, verified the same way |
| GIF frames with more than 256 colours change slightly | Only after compositing mixed palettes. Noted in the summary. No dithering inside covered blocks |
| Large animations run out of memory | The size limit, and one or two frames in memory at a time |
| An odd GIF or WebP crashes the decoder | Limits on every length and offset, fuzz tests, and today's first-frame fallback when decoding fails |

## Open decisions

| Decision | Default |
|---|---|
| Which formats first | GIF, then WebP, then APNG, as GIF is the most common |
| Size limit | 400 megapixels in total and 1000 frames |
| Animated HEIC and AVIF | Keep the first frame, as today |
| Delta frames to keep files small | Not at first |
