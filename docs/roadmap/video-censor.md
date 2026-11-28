# Video Censor roadmap

Extends Censor from photos to videos, so people can pixelate, blur or black out a face, number plate or screen in a video before sharing it, all on the phone.

| | |
|---|---|
| **Status** | Built (Phases 1 to 6, keyframes without automatic tracking). Still to try on a device: 4K, and Phase 4's accessibility and form-factor checks |
| **Branch** | `dev` |
| **Size** | Large. 6 phases, the biggest of the four media roadmaps |
| **Depends on** | Nothing. Uses Media3 Transformer and effect (`1.11.1`), already in the app |

## Contents

1. [Summary](#summary)
2. [Goals and non-goals](#goals-and-non-goals)
3. [Privacy and safety design](#privacy-and-safety-design)
4. [Phase 1. Shared effect math](#phase-1-shared-effect-math)
5. [Phase 2. GPU effect for video](#phase-2-gpu-effect-for-video)
6. [Phase 3. Export and verification](#phase-3-export-and-verification)
7. [Phase 4. Editor UI](#phase-4-editor-ui)
8. [Phase 5. Entry points, reporting and docs](#phase-5-entry-points-reporting-and-docs)
9. [Phase 6. Moving areas (later)](#phase-6-moving-areas-later)
10. [Test plan](#test-plan)
11. [Risks](#risks)
12. [Open decisions](#open-decisions)

## Summary

Censor today covers photos with a rectangle or brush, using `CensorRegion` (coordinates as fractions of the image) and `CensorEffects` (Black, Pixelate, Blur, built so the covered detail can't be recovered). This roadmap adds videos. Each area covers a fixed place on screen for a time range the user picks on a timeline. The video is always fully re-encoded through Media3 Transformer, the covered pixels get the same irreversible effect as photos, and the result goes through the existing video clean checks plus a new check that the areas really are covered.

Following a moving face is deliberately left for a later phase. Fixed areas cover the most common cases, like a dashcam plate, a screen recording, or a person who stays in one spot.

## Goals and non-goals

**Goals**

- Black, Pixelate and Blur for videos, looking the same as on photos and just as impossible to undo.
- Each area covers a fixed rectangle for a start and end time, or for the whole video.
- Several areas per video, each with its own effect and time range.
- Crop the video, like photo Censor.
- The saved copy is clean like a Clean tab result, and the original stays untouched.

**Non-goals for the first release**

- Areas that follow a moving object. See [Phase 6](#phase-6-moving-areas-later).
- The brush tool on video. Rectangles only at first.
- Muting or bleeping part of the audio. Remove Audio still applies to the whole video.
- Live playback with effects. The editor shows still frames from a timeline instead.
- Automatic face or plate detection.

## Privacy and safety design

| Rule | How |
|---|---|
| The covered detail can't be recovered | The GPU effect keeps one color per block plus random noise, exactly like `CensorEffects`. No ordinary Gaussian blur, which can be partly sharpened back |
| No uncovered frame slips through | Areas are applied to every frame from start to end time inclusive. Times are rounded outward to whole frames, never inward |
| Never a quick copy | Censoring always re-encodes. Faster Video Cleaning never applies |
| The saved file is clean | The output goes through `requireConvertedVideoClean`, `requireVideoMetadataClean`, `VideoStreamMetadata.scan` and the container scans, the same as Convert |
| The areas really are covered | A new check decodes sampled frames from each area's range and fails the save if the area isn't uniform enough. It fails closed and deletes the output |
| Nothing kept that shows the original | No thumbnail, poster frame or cover art is copied from the source. Temporary frames are deleted with `SecureDelete` |

## Phase 1. Shared effect math

Pull the block size and noise rules out of `CensorEffects` so photos and videos use one definition.

**Deliverables**

- `CensorBlocks`, a pure class that holds block size for a given image and area size, and the noise range. `CensorEffects` calls it with no change to photo output.
- A test that runs the existing photo censor fixtures and confirms the output is byte-identical before and after.

**Done when**

- [x] Photo Censor output is unchanged.
- [x] Both block size and noise come from `CensorBlocks` only.

## Phase 2. GPU effect for video

**Deliverables**

- `VideoCensorEffect`, a Media3 `GlEffect` with two passes per covered area.
  1. Render the area into a small texture with one pixel per block (the block average), and add per-block noise from a seeded random source that changes per area, not per frame, so blocks don't flicker.
  2. Draw that texture back over the area, nearest-neighbor for Pixelate, bilinear for Blur, and a solid fill for Black.
- An area is only drawn when the frame's presentation time falls in its range.
- `VideoCensorRegion` adds `startUs` and `endUs` to a `CensorRegion` rectangle.

**Done when**

- [x] On a decoded test frame, every block inside an area is one color within the noise range.
- [x] Frames outside an area's range are unchanged apart from encoding.
- [ ] The effect works at 4K, in portrait and landscape, and on rotated sources (`rotationDegrees` applied first, like photo Censor's upright decode). Portrait, landscape and a 90 degree rotated source pass in `CensorOnDeviceTest`; Media3 hands effects the upright frame. 4K is still to try.

## Phase 3. Export and verification

**Deliverables**

- `VideoCensorExporter`, next to `CensorExporter`. It builds an `EditedMediaItem` with crop (`Crop`), the censor effect, and Remove Audio when it's on, then runs Transformer to H.264 or H.265 MP4, matching the source when possible.
- A new `MetadataStripper.saveCensoredVideo(...)`, so the result is saved through the same path, checks and naming as a cleaned video (`MediaFileNames`, `OutputDestination`, `MediaStoreWrites`).
- `CensorVerifier.requireVideoAreasCovered(...)` decodes 3 frames per area (start, middle, end of its range) with `MediaMetadataRetriever.getFrameAtTime(..., OPTION_CLOSEST)`, measures how uniform each block is, and throws if any block has more detail than encoding noise allows. The output is then deleted and the user sees a clear error.
- Progress and cancellation through `MetadataStripper`'s shared progress callback and `BatchRun` handling, like Convert.

**Done when**

- [x] A censored video passes every clean check that a Convert result passes.
- [x] A deliberately broken effect (areas not drawn) makes the save fail, not succeed.
- [x] Cancelling mid-export leaves no partial file in MediaStore and removes temp files.

## Phase 4. Editor UI

**Layout**

```
┌────────────────────────────────┐
│  ← Censor video        Save    │
│ ┌────────────────────────────┐ │
│ │                            │ │  still frame at the playhead
│ │     ┌──────┐               │ │  areas drawn with their effect
│ │     │ ▓▓▓▓ │               │ │  drag to move, handles to resize
│ │     └──────┘               │ │
│ └────────────────────────────┘ │
│  0:04 ─────●──────────── 0:31  │  scrubber with frame thumbnails
│  Area 1  ███████░░░░░░░░░░░░░  │  each area's time range,
│  Area 2  ░░░░░░████████████░░  │  drag the ends to change it
│                                │
│ [Rectangle] [Crop]   Pixelate ▾│  same tool row and effect picker
│ [Undo] [Redo]   [Whole video]  │  as photo Censor
└────────────────────────────────┘
```

**Deliverables**

- `CensorFragment` decides photo or video from the picked file's type and shows the video editor for videos.
- The frame preview uses `MediaMetadataRetriever` thumbnails, so no player library is added.
- Each area row has start and end handles that snap to frames, plus a **Whole Video** action.
- The preview applies `CensorEffects` to the still frame, so what you see is what gets saved.
- Undo and redo cover area moves, resizes, time changes and deletes, reusing `CensorViewModel`'s history.
- Pinch-zoom on the frame, like photos.
- Long videos warn first through `ProcessingResourceWarnings`, since a full re-encode takes time and battery.

**Accessibility**

- The timeline handles are focusable with content descriptions like "Area 1 starts at 0:04", and adjustable with volume or arrow keys.
- All new text is in `values/` and the 12 locales.

**Done when**

- [x] A user can add, move, resize and time several areas, preview them, and save. Tried on `Pixel_10_Pro`: an area timed from 0:00 to 0:03 of a 6 second video saved black for those seconds and untouched after.
- [ ] The editor works in light, dark, Arabic, at 200% font size, on a foldable and with a keyboard.

**As built**

- The timeline is the scrubber plus, while a rectangle is selected, that one area's range (a range slider, with **Whole Video**), not a row for every area. Dragging either end of the range shows the frame at that end.
- A new area covers the whole video until its range is narrowed, since covering too much is the safe mistake.
- The picker in Censor and the Censor share route in `MainActivity` take videos already, so the editor can be reached; the manifest filter, the Censor tile and Scan's action are still Phase 5.
- In landscape the timeline sits in the right-hand column, which full screen hides, so full screen in landscape has no timeline yet.

## Phase 5. Entry points, reporting and docs

**Deliverables**

- The Censor picker in `CensorFragment` accepts `video/*`. `MediaPickerContracts.IMAGE_AND_VIDEO_MIME_TYPES` already covers it.
- The `CensorShareTarget` alias and the Censor tile accept videos. In `AndroidManifest.xml`, add `video/*` to its intent filter. This changes a filter, not an exported component, so `release-manifest.txt` stays as it is. Confirm with `exportReleaseManifest`.
- Scan's **Censor** action is offered for videos too.
- Sentry reporting uses existing names, `job.type=censor` and `file.media_type=video`. The export runs inside the existing job transaction with a new span operation `video_censor_export`, which goes into the README and site Names in Sentry tables in the same commit.
- `README.md` and `site/index.html` Features, the Censor section of both, the Play listing text in `fastlane/metadata/android/*/full_description.txt` in all locales, and Section 6 of `legal/redact.html` only if a new name is added.

**Done when**

- [x] Sharing a video to **Censor with Redact** opens the video editor.
- [x] `SentryNamingTest` passes and the tables list every new name.

**As built**

- The new span is `verify.censor_coverage`, around the coverage check, rather than `video_censor_export`: it follows the `area.event` scheme, and the export itself is already timed by `video.transcode`. No new tag or metric: Censor's counters now pass `file.media_type=video` for a video.
- Scan offers **Censor This Video**, the empty state and Select button say photo or video, and **Delete Originals After Cleaning** moves a censored video's original to the trash too.
- The Censor tile only opens the tab, so it needed no change.

## Phase 6. Moving areas

- [x] Keyframes, so the user places an area at a few moments and it moves smoothly between them, interpolated per frame. In the editor: a **Moves** switch for the selected area, then moving it at another time sets a keyframe; arrows step between keyframes and a bin deletes one, all part of undo.
- [x] The coverage check samples each keyframe and halfway between each pair, on frames picked by their exact times (read from the saved video), so a moving area's grid can be placed exactly.
- [ ] Optionally on-device tracking with a bundled model. Not built: it needs a model and library in the APK, and could never use network-backed ML. Keyframes cover most cases first.

**As built**

- A moving area's block grid moves with it. A grid fixed to the frame would sample what the area covers at a slightly different offset in every frame, and enough frames together could be combined to recover detail. Areas that stay put keep the frame-wide grid, unchanged.
- It is padded by the furthest any edge travels in one frame margin, its block size is set once from its largest padded rectangle, and its noise is drawn once for its largest grid.
- On the emulator, a heavily compressed source (about 170 kbps) left specks of the old picture inside a moving Black area, and the coverage check refused the save. Censored exports now get a bitrate floor, 921.6 kbps at 320 x 240 x 30 fps scaled with pixel rate to the power 0.75 (about 11 Mbps at 1080p30), the lowest that came out clean for a fine checkerboard on the software encoder.

## Test plan

| Test | Covers |
|---|---|
| `CensorBlocksTest` (new) | Block size and noise rules, and photo output unchanged |
| `VideoCensorRegionTest` (new) | Times round outward to whole frames, and fractions map to the right pixels after rotation and crop |
| `VideoCensorEffectTest` (new, Robolectric where possible) | Shader inputs per area and time, and areas off outside their range |
| `VideoCensorExporterTest` (new) | The edited item has crop, effect and Remove Audio set, and the clean checks run |
| `CensorVerifierTest` (new) | Uniform blocks pass, a detailed block fails, and failure deletes the output |
| `CensorFragmentTest` (extended) | Photo or video editor by type, undo and redo, the Whole Video action |
| `CensorOnDeviceTest` (new, `Pixel_10_Pro` test emulator) | Censors the checkerboard fixtures with real codecs and the GPU effect, and checks the frames independently of `CensorVerifier`: each effect, a time range, a rotated source, a crop, an undrawn area failing the check, and cancelling. Run with `connectedDebugAndroidTest` on `Pixel_10_Pro`; on the managed `Pixel_10_Pro_Clean` the goldfish H.264 decoder fails to configure, locally too |
| `VideoCensorMotionTest` (new) | Keyframes, interpolation, padding, the moving grid and its stable noise, and the coverage check on moving frames |
| `make_instrumented_media.py` (extended) | `detail.mp4` and `detail_rotated.mp4`: a 3 px checkerboard over the whole frame, plain and with a 90 degree rotation flag |
| `/test` on `Pixel_10_Pro` | Censor a phone video and a screen recording, play the result and confirm the areas stay covered for their full range |

The 90% line gate still applies. GPU code that only runs on a device is kept in thin classes, so the logic around it stays unit-tested.

## Risks

| Risk | Mitigation |
|---|---|
| A frame at the edge of a range is left uncovered | Times round outward, and the coverage check samples start and end frames |
| Encoding brings back detail inside blocks | It can't add real detail, only noise. The coverage check allows for encoding noise and nothing more |
| Some devices fail to encode 4K or HDR with effects | Fall back to 1080p SDR with a notice, the same way Convert handles unsupported targets |
| Long videos take too long or drain the battery | Resource warning first, a foreground service with progress, and cancellation |
| HDR sources come out washed out | Tone-map to SDR the way `VideoMedia3Converter` already does, and say so in the notice |
| The managed emulator can't run video tests in CI | Unit-test the logic, and run `CleanOnDeviceTest` locally before release, as CLAUDE.md already requires |

## Open decisions

| Decision | Default |
|---|---|
| Output codec | Match the source when it's H.264 or H.265, otherwise H.264 |
| Maximum video length | No hard cap, with the resource warning |
| Add the brush tool for video in the first release | No, rectangles only |
| Add ExoPlayer for live preview | No, still frames keep the app smaller |
