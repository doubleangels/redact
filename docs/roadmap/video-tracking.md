# Video Tracking roadmap

Lets a moving area in Video Censor follow what it covers on its own, so people draw a box once instead of placing keyframes by hand, and later lets Redact suggest the faces it finds. Everything runs on the phone with models shipped inside the app.

| | |
|---|---|
| **Status** | Planned |
| **Branch** | `dev` |
| **Size** | Large. 6 phases |
| **Depends on** | [Video Censor](video-censor.md) Phase 6 (moving areas and keyframes), already built |

## Contents

1. [Summary](#summary)
2. [Goals and non-goals](#goals-and-non-goals)
3. [Privacy and safety design](#privacy-and-safety-design)
4. [Phase 1. Runtime and model spike](#phase-1-runtime-and-model-spike)
5. [Phase 2. Frames in, boxes out](#phase-2-frames-in-boxes-out)
6. [Phase 3. Boxes to keyframes](#phase-3-boxes-to-keyframes)
7. [Phase 4. Track in the editor](#phase-4-track-in-the-editor)
8. [Phase 5. Face suggestions](#phase-5-face-suggestions)
9. [Phase 6. Reporting, docs and store](#phase-6-reporting-docs-and-store)
10. [Test plan](#test-plan)
11. [Risks](#risks)
12. [Open decisions](#open-decisions)

## Summary

Video Censor's moving areas slide in a straight line between keyframes the user sets by hand (`VideoCensorRegion.withKeyframes`, `CensorViewModel.setKeyframe`). For a face that walks across the frame that can take dozens of keyframes. This roadmap adds **Track**. The user draws a rectangle on one frame, taps Track, and a small on-device tracker follows it forwards and backwards through the area's time range. Its boxes are smoothed, padded and thinned into ordinary keyframes, so the renderer, the shader, `VideoCensorLayout` and `CensorVerifier` don't change, and the user can still fix any keyframe by hand.

A later phase adds **Find Faces**, which runs a face detector over the video and offers each face it finds as a tracked area the user accepts or deletes.

ML here only saves effort. It never decides on its own what is safe to share. The user reviews every tracked area before saving, and every save still goes through the existing coverage and clean checks.

## Goals and non-goals

**Goals**

- Track any rectangle the user draws, whatever it covers (face, plate, screen, tattoo, document).
- Turn tracking into normal keyframes that the existing editor shows, steps through, edits and undoes.
- Err towards covering too much. A lost object grows its box instead of dropping it.
- Suggest faces as tracked areas, with nothing added until the user accepts it.
- Run fully offline, with models inside the APK, no new permission and no network use.
- Keep the app's size growth under about 5 MB per ABI for Phases 1 to 5.

**Non-goals**

- Saving without review. Tracking and suggestions always end in the editor.
- Shaped masks (segmentation). Video areas stay rectangles, as in [Video Censor](video-censor.md).
- Plate or text detection in the first release. Tracking covers both once the user draws the box. See [open decisions](#open-decisions).
- Recognising who a face belongs to, or matching faces across videos.
- Tracking on photos. A photo has no motion.
- Downloading models, or any cloud or Play services ML.

## Privacy and safety design

| Rule | How |
|---|---|
| Nothing leaves the phone | Models are app assets, run by the standalone LiteRT runtime from Maven (`com.google.ai.edge.litert`), not the Play services one. No ML Kit, which logs usage to Google through Firebase's transport library and downloads unbundled models |
| No new permission or component | `check_release_manifest.py` must pass with no change to `release-manifest.txt`. It doesn't compare `uses-native-library`, so the merged manifest is checked by hand in Phase 1 for anything a GPU delegate adds |
| A missed frame can't slip through silently | Low confidence thresholds, boxes padded outward, a lost object holds its last box and grows each frame until found again or the range ends, and frames where tracking was weak are marked on the timeline for review |
| Smoothing never shrinks coverage | Each keyframe's rectangle contains every raw box it stands for plus a margin, so thinning keyframes can only make an area bigger |
| The covered detail can't be recovered | Unchanged. Tracked areas are moving areas, with the moving block grid and noise from Video Censor Phase 6 |
| The saved file is checked as before | `requireVideoAreasCovered`, `requireConvertedVideoClean`, `requireVideoMetadataClean`, `VideoStreamMetadata.scan` and the container scans run exactly as now. No check is skipped because an area was tracked |
| No frame is kept | Decoded frames live only in memory or GPU buffers for one step, and any temp file goes through `SecureDelete` |
| Sentry learns nothing about the video | Only technical facts (runtime, delegate, speed, failures). Never how many faces were found, where, or for how long, since that describes what is in the file |

## Phase 1. Runtime and model spike

Measure before committing. Redact is GPLv3, so every model and library must be GPL-compatible, and Apache 2.0 or MIT is preferred.

**Candidates**

| Job | Option | Size | License | Notes |
|---|---|---|---|---|
| Runtime | **LiteRT** (`com.google.ai.edge.litert`) | about 1 to 2 MB per ABI | Apache 2.0 | Recommended. Optional GPU delegate |
| Runtime | ONNX Runtime Android | about 5 to 8 MB per ABI | MIT | Runs the ONNX models without converting them, but larger |
| Runtime | OpenCV Android | 10 MB or more per ABI | Apache 2.0 | Has NanoTrack, VitTrack and YuNet built in. Too large for what's used |
| Tracker | **NanoTrack v3** | about 2 MB, two parts | Apache 2.0 | Recommended. Published as ONNX, converted to `.tflite` with `onnx2tf` |
| Tracker | VitTrack (OpenCV Zoo) | under 1 MB | Apache 2.0 | Smaller, worth comparing on accuracy |
| Face detector | **YuNet** (OpenCV Zoo) | about 230 KB | MIT | Recommended. Better on small and side-on faces |
| Face detector | BlazeFace full range (MediaPipe) | about 1 MB | Apache 2.0 | Ships as `.tflite` already, weaker in crowds |
| Avoid | Ultralytics YOLO | | AGPL 3.0 | Complicates a GPLv3 app |
| Avoid | SCRFD and InsightFace weights | | Non-commercial | Not allowed in a paid app |

**Deliverables**

- A throwaway branch that runs each tracker on the `detail.mp4` fixture and on two real clips (a walking person and a passing car) on `Pixel_10_Pro`, logging speed per frame and how often the box loses the object.
- The chosen runtime, tracker and detector, with the APK size growth measured by `bundleRelease` per ABI, written into this roadmap.
- Each model's source URL, license file and SHA-256 recorded in `app/src/main/assets/models/README.md`, with the license texts added to `LicensesActivity`.
- `.tflite` added to `androidResources.noCompress` so models load by memory mapping.

**Done when**

- [ ] The tracker runs at 15 frames per second or faster at its input size on `Pixel_10_Pro`, CPU only.
- [ ] `bundleRelease` and `check_release_manifest.py` pass with the runtime added, and the merged manifest gains no permission, component or native library entry that isn't understood.
- [ ] A run with the emulator's network turned off behaves the same as one with it on.

## Phase 2. Frames in, boxes out

**Deliverables**

- `VideoFrameSource`, which decodes frames in order from a time range at a small size (about 320 px on the short side), upright after `rotationDegrees`. It uses `MediaCodec` to an `ImageReader`, or Media3's frame extractor if Phase 1 shows it's fast enough. `MediaMetadataRetriever` seeks per frame and is far too slow for this.
- `ObjectTracker`, a thin wrapper around the tracker model. `start(frame, box)`, then `next(frame)` returns a box and a confidence score.
- `FaceDetector`, a thin wrapper around the face model, used from Phase 5.
- `TrackRun`, which runs the tracker forwards from the playhead to the area's end and backwards to its start, with progress and cancellation in the style of `BatchRun`, on a background executor.
- Every frame is tracked. Speed comes from the small input size, not from skipping frames, since a skipped frame is one the tracker never saw.
- Model loading fails closed. A missing, truncated or mismatched model (checked against the recorded SHA-256) disables Track with a message instead of crashing or guessing.

**Done when**

- [ ] A box drawn on a synthetic moving square in a generated test clip follows it to within 2 px at 320 px input.
- [ ] Cancelling stops decoding within one frame and frees the codec and model.
- [ ] GPU and model code sits in thin classes, so the logic around it stays under the 90% line gate.

## Phase 3. Boxes to keyframes

The tracker gives one jittery box per frame. `VideoCensorLayout.movingTrack` pads a whole area by its fastest segment (`maxSpeed()`) and sizes its block grid from its largest keyframe, so raw boxes would make every tracked area huge for its whole range. This phase keeps the keyframes few and smooth without ever losing coverage.

**Deliverables**

- `TrackSmoother`, a pure class with no Android code.
  1. **Smooth** with a One Euro filter on each edge, so slow drift is steady and fast moves still follow.
  2. **Hold and grow** across frames where confidence fell below the threshold. The box keeps its last place and grows by the area's recent speed per frame until confidence returns.
  3. **Pad** every box outward by a margin (a share of its size plus a few pixels) for tracker error.
  4. **Thin** to keyframes with a time-aware Ramer-Douglas-Peucker pass. A frame becomes a keyframe whenever the straight slide between its neighbours would leave any edge of its padded box by more than the tolerance.
  5. **Contain**. After thinning, each segment's interpolated rectangle is grown until it holds every padded box in that segment, so coverage is never lost.
- The output is a `List<VideoCensorRegion.Keyframe>` plus the time spans where tracking was weak.

**Done when**

- [ ] For random, jittery and lost-object box sequences, every raw box is inside the interpolated keyframe rectangle at its time (a property test).
- [ ] A steady pan gives 2 keyframes, and a figure-eight gives far fewer keyframes than frames.
- [ ] `maxSpeed()` of a smoothed track is within 20% of the true object's speed on the synthetic clip, so padding stays tight.

## Phase 4. Track in the editor

**Layout**

```
┌────────────────────────────────┐
│  ← Censor video        Save    │
│ ┌────────────────────────────┐ │
│ │         ┌──────┐           │ │  the selected area, at the playhead
│ │         │ ▓▓▓▓ │           │ │
│ │         └──────┘           │ │
│ └────────────────────────────┘ │
│  0:04 ─────●──────────── 0:31  │
│  ░░░▲░░░░▲▲░░░!!!░░░▲░░░░░░░░  │  keyframes (▲) and weak spots (!)
│                                │
│ [Moves ●] [Track]  ◀ ▶  🗑      │  Track sits with the keyframe tools
│  Tracking… 42%        [Cancel] │  progress while it runs
└────────────────────────────────┘
```

**Deliverables**

- A **Track** button beside the **Moves** switch for the selected area. It turns Moves on, runs `TrackRun` from the area's place at the playhead across its time range, and replaces its keyframes with the smoothed ones in one undoable step.
- Weak spots are drawn on the timeline. The arrows step to them as well as to keyframes, and a notice after tracking says how many need a look.
- Any keyframe can still be moved, added or deleted by hand. **Track From Here** re-tracks from the playhead to the end of the range, keeping keyframes before it, for fixing a drift.
- Save is never blocked by weak spots, but the first save after tracking with any unreviewed ones asks once to confirm.
- Long ranges warn first through `ProcessingResourceWarnings`, since tracking decodes every frame.
- The Track button, progress and weak-spot markers have content descriptions and work with the keyboard and TalkBack. All new text goes in `values/` and the 12 locales.

**Done when**

- [ ] On `Pixel_10_Pro`, drawing a box on a walking person and tapping Track covers them for the whole clip, and the saved video passes `requireVideoAreasCovered`.
- [ ] Undo after Track restores the area's keyframes exactly as before.
- [ ] Rotating the phone or leaving the app during tracking cancels or resumes cleanly, with no leaked codec.
- [ ] The editor works in light, dark, Arabic, at 200% font size, on a foldable and with a keyboard.

## Phase 5. Face suggestions

**Deliverables**

- **Find Faces** in the video editor's tool row. It runs `FaceDetector` over the video (every frame at the detector's input size), links detections into tracks by overlap and position, and fills small gaps with the tracker.
- Each face track becomes a suggested area, drawn dashed with an **Add** and **Dismiss** control. Nothing is added to the edits, saved or undone until the user adds it. **Add All** adds them all.
- Accepted suggestions go through `TrackSmoother` like Phase 3 and get the effect selected in the toolbar.
- The detector's threshold is set low, favoring false alarms, which cost a tap, over misses, which cost a face.
- The notice after a run says Redact can miss faces (small, turned away, covered, in poor light) and that the video should be checked before sharing.

**Done when**

- [ ] On a set of test clips with faces marked by hand, at least 95% of face frames are covered by an accepted suggestion, measured in a local script, not in CI.
- [ ] Dismissing every suggestion leaves the edits unchanged.
- [ ] The run can be cancelled and leaves no suggestions behind.

## Phase 6. Reporting, docs and store

**Deliverables**

- Sentry, through `SentryManager` and `AppMetrics`, following the naming scheme above `SentryManager`'s key lists. Proposed names, to be checked against `SentryNamingTest`:
  - `censor.track_run` count with `outcome` (success, cancelled, failed) and `reason` for failures (model_load, decoder, out_of_memory).
  - `censor.track_run.frames_per_second` distribution, for device speed.
  - `censor.face_suggest` count with `outcome`.
  - A `track.delegate` tag (cpu, gpu).
  - Nothing that counts faces, suggestions, weak spots or keyframes made by tracking.
- Every new name goes into the Names in Sentry tables (id `sentry-names`) in `README.md` and `site/index.html`. Update the What Send Crash Reports captures tables and Section 6 of `legal/redact.html` in the same commit. The consent strings don't change unless what is sent changes in kind.
- The README and site Censor sections, the Play listing text in `fastlane/metadata/android/*/full_description.txt` in all locales, and the Video Censor roadmap's Phase 6 checkbox for tracking.
- `legal/redact.html` and the site say plainly that face finding runs on the phone and that no image or result is sent anywhere.
- `proguard-rules.pro` keeps whatever LiteRT needs, and nothing more.

**Done when**

- [ ] `SentryNamingTest`, `StringTranslationsTest`, `lintRelease` and `bundleRelease` pass.
- [ ] `/test` covers Track and Find Faces on `Pixel_10_Pro`.

## Test plan

| Test | Covers |
|---|---|
| `TrackSmootherTest` (new) | Smoothing, hold and grow, padding, thinning, and the containment property on random sequences |
| `TrackRunTest` (new) | Forwards and backwards runs, progress, cancellation, and failing closed on a bad model, with a fake `ObjectTracker` |
| `VideoFrameSourceTest` (new, Robolectric where possible) | Frame order, upright rotation, time range bounds |
| `ModelAssetsTest` (new) | Every model in `assets/models` matches its recorded SHA-256 and has a license entry, so a model swap is always deliberate |
| `CensorViewModelTest` (extended) | Track replaces keyframes in one undo step, Track From Here keeps earlier keyframes, suggestions don't touch the edits until added |
| `CensorFragmentTest` (extended) | Track button, progress, weak-spot markers, and the confirm on save |
| `TrackOnDeviceTest` (new, `Pixel_10_Pro` test emulator) | Tracks a moving square in a generated clip with the real model and codecs, saves it, and checks the frames independently of `CensorVerifier`. Run with `connectedDebugAndroidTest`, like `CensorOnDeviceTest` |
| `make_instrumented_media.py` (extended) | `moving_square.mp4`, a textured square on a path with a pause, a fast turn and a moment hidden behind a bar |
| `SentryNamingTest` | Every new name follows the scheme and is in both tables |

The 90% line gate still applies.

## Risks

| Risk | Mitigation |
|---|---|
| The tracker drifts onto the background and uncovers the object | Weak spots on the timeline, review before save, Track From Here to fix, and `requireVideoAreasCovered` still checks that what the area covers is censored (though not that it covers the right thing) |
| Users trust Find Faces and stop checking | The notice after every run, suggestions shown as suggestions, and the README and listing never calling it automatic or complete |
| Jitter makes tracked areas huge | Phase 3 smoothing and thinning, with the `maxSpeed()` test |
| The app grows too much | Phase 1 measures it, with a 5 MB per ABI budget, and the larger runtimes are ruled out |
| A library pulls in network code or manifest entries | Phase 1 checks, `check_release_manifest.py`, a manual look at the merged manifest, and the network-off run |
| Slow on older phones | Small input size, the GPU delegate where it works, progress and cancellation, and the resource warning |
| A converted model gives different results from the original | Phase 1 compares the ONNX and `.tflite` outputs on the same frames before adopting it |
| Model files are a parsing attack surface | They come only from the APK, are hash-checked at load, and are never read from user files |

## Open decisions

| Decision | Default |
|---|---|
| Runtime | LiteRT, if Phase 1's size and speed pass |
| Tracker | NanoTrack v3, unless VitTrack is as accurate and smaller |
| Face detector | YuNet |
| GPU delegate | Off at first, turned on per device only if Phase 1 shows a clear gain and it adds no manifest entries |
| Ship Phase 5 in the same release as Track | No. Track first, faces once Track has been in use for a release |
| Plate and text detection | A later roadmap. A plate detector would need training on an open dataset with an Apache-licensed model like YOLOX-nano, and text would use PaddleOCR's detection model |
| Track the whole video when the area covers the whole video | Yes, with the resource warning |
