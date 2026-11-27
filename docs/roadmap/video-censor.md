# Video Censor roadmap

Extends Censor from photos to videos, so people can pixelate, blur or black out a face, number plate or screen in a video before sharing it, all on the phone.

| | |
|---|---|
| **Status** | In progress (Phase 1 done) |
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

- [ ] On a decoded test frame, every block inside an area is one color within the noise range.
- [ ] Frames outside an area's range are unchanged apart from encoding.
- [ ] The effect works at 4K, in portrait and landscape, and on rotated sources (`rotationDegrees` applied first, like photo Censor's upright decode).

## Phase 3. Export and verification

**Deliverables**

- `VideoCensorExporter`, next to `CensorExporter`. It builds an `EditedMediaItem` with crop (`Crop`), the censor effect, and Remove Audio when it's on, then runs Transformer to H.264 or H.265 MP4, matching the source when possible.
- A new `MetadataStripper.saveCensoredVideo(...)`, so the result is saved through the same path, checks and naming as a cleaned video (`MediaFileNames`, `OutputDestination`, `MediaStoreWrites`).
- `CensorVerifier.requireVideoAreasCovered(...)` decodes 3 frames per area (start, middle, end of its range) with `MediaMetadataRetriever.getFrameAtTime(..., OPTION_CLOSEST)`, measures how uniform each block is, and throws if any block has more detail than encoding noise allows. The output is then deleted and the user sees a clear error.
- Progress and cancellation through `MetadataStripper`'s shared progress callback and `BatchRun` handling, like Convert.

**Done when**

- [ ] A censored video passes every clean check that a Convert result passes.
- [ ] A deliberately broken effect (areas not drawn) makes the save fail, not succeed.
- [ ] Cancelling mid-export leaves no partial file in MediaStore and removes temp files.

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

- [ ] A user can add, move, resize and time several areas, preview them, and save.
- [ ] The editor works in light, dark, Arabic, at 200% font size, on a foldable and with a keyboard.

## Phase 5. Entry points, reporting and docs

**Deliverables**

- The Censor picker in `CensorFragment` accepts `video/*`. `MediaPickerContracts.IMAGE_AND_VIDEO_MIME_TYPES` already covers it.
- The `CensorShareTarget` alias and the Censor tile accept videos. In `AndroidManifest.xml`, add `video/*` to its intent filter. This changes a filter, not an exported component, so `release-manifest.txt` stays as it is. Confirm with `exportReleaseManifest`.
- Scan's **Censor** action is offered for videos too.
- Sentry reporting uses existing names, `job.type=censor` and `file.media_type=video`. The export runs inside the existing job transaction with a new span operation `video_censor_export`, which goes into the README and site Names in Sentry tables in the same commit.
- `README.md` and `site/index.html` Features, the Censor section of both, the Play listing text in `fastlane/metadata/android/*/full_description.txt` in all locales, and Section 6 of `legal/redact.html` only if a new name is added.

**Done when**

- [ ] Sharing a video to **Censor with Redact** opens the video editor.
- [ ] `SentryNamingTest` passes and the tables list every new name.

## Phase 6. Moving areas (later)

Planned separately once fixed areas ship.

- Keyframes, so the user places an area at a few moments and it moves smoothly between them, interpolated per frame.
- Optionally on-device tracking with a bundled model, under the same no-network rule as Censor detection.
- The coverage check then samples frames between keyframes too.

## Test plan

| Test | Covers |
|---|---|
| `CensorBlocksTest` (new) | Block size and noise rules, and photo output unchanged |
| `VideoCensorRegionTest` (new) | Times round outward to whole frames, and fractions map to the right pixels after rotation and crop |
| `VideoCensorEffectTest` (new, Robolectric where possible) | Shader inputs per area and time, and areas off outside their range |
| `VideoCensorExporterTest` (new) | The edited item has crop, effect and Remove Audio set, and the clean checks run |
| `CensorVerifierTest` (new) | Uniform blocks pass, a detailed block fails, and failure deletes the output |
| `CensorFragmentTest` (extended) | Photo or video editor by type, undo and redo, the Whole Video action |
| `CleanOnDeviceTest` (extended, managed emulator) | Censors the fixture videos with real codecs and runs the coverage and clean checks. Run locally, since the goldfish decoders fail there in CI |
| `make_instrumented_media.py` (extended) | A test video with a high-detail pattern (text, a checkerboard) in a known area |
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
