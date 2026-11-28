# Video Censor Additions roadmap

Three additions to video Censor: muting or bleeping part of the sound, trimming the start, end or a middle section away, and the brush tool for video. All three run through the export and checks video Censor already has.

| | |
|---|---|
| **Status** | Planned |
| **Branch** | `dev` |
| **Size** | Large. 3 items in 8 phases. Trim is medium, Sound is medium to large, Brush is medium to large |
| **Depends on** | [Video Censor](video-censor.md), built. Nothing new in the APK: Media3 Transformer and effect (`1.11.1`) already do clipping, audio processing and GPU effects |

## Contents

1. [Summary](#summary)
2. [Goals and non-goals](#goals-and-non-goals)
3. [Privacy and safety design](#privacy-and-safety-design)
4. [Order](#order)
5. [Trim. Phase 1. Export](#trim-phase-1-export)
6. [Trim. Phase 2. Editor](#trim-phase-2-editor)
7. [Sound. Phase 3. Audio effect](#sound-phase-3-audio-effect)
8. [Sound. Phase 4. Verification](#sound-phase-4-verification)
9. [Sound. Phase 5. Editor and listening](#sound-phase-5-editor-and-listening)
10. [Brush. Phase 6. Strokes on video](#brush-phase-6-strokes-on-video)
11. [Brush. Phase 7. Editor](#brush-phase-7-editor)
12. [Phase 8. Reporting, docs and store](#phase-8-reporting-docs-and-store)
13. [Test plan](#test-plan)
14. [Risks](#risks)
15. [Open decisions](#open-decisions)

## Summary

Video Censor covers the picture with rectangles, fixed or moving, for time ranges the user picks. Three gaps are left.

- **Sound.** A spoken name, address or phone number stays in the audio. The only choice today is **Remove Audio**, which drops all of it. This adds ranges on the timeline whose sound is muted or replaced with a tone.
- **Trim.** Censor can crop a video's frame but not its length. The first and last seconds are often where a house number, a reflection or a voice slips in, and a middle section can show a screen or document full frame. Cutting it away is safer than covering it, because nothing is left to look past.
- **Brush.** Rectangles are the only shape on video. A rectangle over a tilted number plate or a curved sign covers far more than it needs to. Photo Censor already has a brush.

Every saved copy is still fully encoded again, checked like a Convert result, and checked once more that what the user asked to hide really is hidden, before it reaches the gallery.

## Goals and non-goals

**Goals**

- Mute or bleep any number of sound ranges, each set on the timeline like an area's range.
- Trim the start and end, and cut out one or more middle sections, with picture and sound cut together.
- Paint brush strokes on a video, each with its own effect and time range, like a rectangle.
- A check for each that fails closed: sound that isn't silenced, a frame that should have been cut, or a stroke that isn't covered stops the save.

**Non-goals**

- Disguising a voice by changing its pitch. A simple pitch shift can be undone, so it would promise more than it gives. Mute or bleep removes the words.
- Automatic speech detection or transcription to find words. That needs a speech model, and is a separate idea.
- Moving brush strokes. A stroke stays where it's painted for its time range. A moving shape is still a moving rectangle.
- Trim on Clean or Convert. Trimming is an edit, so it lives in Censor.
- Live playback of the effects while editing. The editor still shows still frames for the picture.

## Privacy and safety design

| Rule | How |
|---|---|
| No sound slips through at a range's edges | Sound ranges are widened by two AAC frames (about 43 ms at 48 kHz) on each side, so the encoder's overlapping frames can't carry a syllable over the edge |
| A muted range is really silent | The saved audio is decoded and every muted range must stay below -60 dBFS (Phase 4). A bleeped range must be the tone and almost nothing else |
| No frame or sound from a cut part survives | Clipping runs before encoding, the saved length must match the kept length to within one frame, and frames just inside each cut are compared with the source (Phase 1) |
| Brush strokes are as safe as rectangles | Strokes use the same block colours and noise as rectangles, clipped to the stroke, as photo Censor does. The coverage check samples inside each stroke (Phase 6) |
| Never a quick copy | Every save is fully encoded again, as now. Faster Video Cleaning never applies |
| The saved file is clean | `requireConvertedVideoClean`, `requireVideoMetadataClean`, `VideoStreamMetadata.scan` and the container scans, unchanged |
| Remove Audio still wins | With Remove Audio on, the copy has no sound at all, and sound ranges are hidden in the editor |
| Nothing about the edits reaches Sentry | Only counts and outcomes, through `AppMetrics`. No times, lengths or positions tied to a file |

## Order

Trim ships first. It's the smallest, it needs the timeline changes the other two build on, and it moves area times, which Sound must handle too. Sound comes next, as the biggest privacy gain. Brush is last.

## Trim. Phase 1. Export

**Deliverables**

- `VideoCensorEdit`, a small value class holding the kept parts of the source as a sorted list of `[startUs, endUs)` ranges, plus the areas and sound ranges in source time. Trimming only the ends is one range. Each cut adds one.
- `VideoCensorExporter` builds one `EditedMediaItem` per kept range from the same source, each with a `MediaItem.ClippingConfiguration`, in a single `EditedMediaItemSequence`, so the parts play back to back. Area and sound ranges are mapped from source time to output time per part. An area that spans a cut is split into the parts on each side.
- Kept ranges are rounded **inward** to whole frames, the opposite of areas, so a partly kept frame at a cut is dropped, never shown.
- `CensorVerifier.requireTrimmed(...)` checks that:
  - The saved length equals the kept length to within one source frame, and is never longer.
  - The first and last frame of each kept part match the source frames at those times, by downscaled comparison. This catches a part that starts early.
  - The audio length matches the video length to within one AAC frame.
- `requireVideoAreasCovered` takes the output-time ranges.

**Done when**

- [ ] A trim of only the ends, and a trim with two cuts, both save with the right length and the right frames at every edge.
- [ ] A deliberate off-by-one-frame bug in the export makes the save fail.
- [ ] Areas still cover the right frames after a cut, including an area that spans one.

## Trim. Phase 2. Editor

**Deliverables**

- Start and end handles on the timeline. What's outside them is dimmed, like the crop.
- **Cut Section** adds a cut at the playhead, with two handles. The bin deletes it. Cut parts are hatched on the timeline.
- Areas keep their source times, so moving a trim handle back never loses an area. An area wholly inside a cut part is greyed out and left out of the save.
- Undo and Redo cover trims and cuts.
- The saved name and the "what you get" summary say the copy is trimmed.

**Done when**

- [ ] Trims and cuts work with the keyboard and TalkBack, at 200% font size, in Arabic and on an unfolded foldable.

## Sound. Phase 3. Audio effect

**Deliverables**

- `VideoSoundRange`, holding a start, an end and a mode (`MUTE` or `BEEP`), in source time.
- `VideoSoundCensor`, a Media3 `BaseAudioProcessor` passed as the audio processor in `Effects` (today `ImmutableList.of()` in `VideoMedia3Converter`). It counts samples from the start of each part, after clipping, and:
  - writes zeros through every widened `MUTE` range;
  - writes a 1 kHz sine at -12 dBFS through every widened `BEEP` range, replacing the speech rather than mixing over it;
  - fades the first and last 5 ms of each range to and from silence to avoid clicks. The fades sit inside the widened margin, never over the user's range.
- It handles every channel count and sample rate the source has, and 16-bit and float PCM, which the decoder may output.
- The output-time mapping from Phase 1 places ranges after trims and cuts.

**Done when**

- [ ] Unit tests on generated PCM: every sample inside each widened range is zero or the tone, and every sample outside is untouched.
- [ ] Ranges that touch or overlap merge, and a range wider than the video is clamped.

## Sound. Phase 4. Verification

**Deliverables**

- `CensorVerifier.requireSoundCensored(...)` decodes the saved audio with `MediaExtractor` and `MediaCodec` and, for each range shrunk by 10 ms at each end:
  - `MUTE`: the level of every 10 ms window is below -60 dBFS;
  - `BEEP`: Goertzel at 1 kHz holds almost all the window's energy, and what's left is below -50 dBFS.
- It fails if the audio can't be decoded, if a range can't be found in time, or if the decoded audio is shorter than the video. Failing deletes the output, like every other check.
- It runs in the `verify.censor_sound` span, next to `verify.censor_coverage`.

**Done when**

- [ ] A processor with a deliberate bug (a range 100 ms short, the wrong channel left alone) makes the save fail.
- [ ] A video whose own sound is silent where a range sits still passes.

## Sound. Phase 5. Editor and listening

People can't find a spoken name on a still frame, so this phase adds listening.

**Deliverables**

- **Play** on the timeline plays the source's sound from the playhead, with the picture following on the still-frame preview at a few frames a second. It uses `MediaCodec` and `AudioTrack`, already on every phone, so no player library is added. If the app adds Media3 ExoPlayer later for something else, this can move to it.
- A waveform strip under the timeline, built in memory from a quick decode at low resolution and never saved to disk.
- **Mute Sound** and **Bleep Sound** add a range at the playhead, with handles like an area. Ranges show on the waveform. Selecting one lets you switch between mute and bleep, or delete it.
- Play skips muted parts and plays the tone in bleeped ones, so people hear what they'll get.
- With **Remove Audio** on, the sound controls are hidden and a line says the copy will have no sound.
- New strings go into `values/` and the 12 locales.

**Done when**

- [ ] A sound range can be placed on a spoken word within a tenth of a second on the test emulator.
- [ ] Playback stops when the editor is left, and nothing about the sound is written to disk.

## Brush. Phase 6. Strokes on video

**Deliverables**

- `VideoCensorRegion` accepts a `CensorRegion` of shape `STROKE` with one keyframe. **Moves** is off for strokes.
- `VideoCensorLayout` gives a stroke a track over its bounding box, with the same block size and noise as a rectangle there.
- `VideoCensorShaderProgram` gets a mask texture per stroke, drawn once from `CensorRegion.toPath` at the frame's size (as `CensorExporter` does for photos) and sampled in the shader, so the block colours show only inside the stroke. Mask edges are drawn with no anti-aliasing, so no pixel is half covered.
- A cap on strokes per video, for GPU memory. Strokes are drawn into one shared mask per effect when there are many.
- `CensorVerifier.requireVideoAreasCovered` samples the pixels inside each stroke, eroded by two pixels for the encoder's edge spread, and requires the same block uniformity it requires inside rectangles.

**Done when**

- [ ] A diagonal stroke over a plate is covered in every sampled frame, and nothing outside its bounding box changes.
- [ ] A shader with a deliberate bug (the mask offset by a few pixels) makes the save fail.
- [ ] 20 strokes at 4K stay within the GPU budget on the test emulator.

## Brush. Phase 7. Editor

**Deliverables**

- The **Brush** tool is enabled for video, with the brush size it has on photos. A stroke covers the whole video until its range is set, like a new rectangle.
- Selecting a stroke shows its range slider, effect and delete, like a rectangle. Its handles move the whole stroke.
- The "Rectangles only (no brush)" line in `README.md` and `site/index.html` goes.

**Done when**

- [ ] Painting, selecting and deleting strokes on video works in light, dark, Arabic and at 200% font size.

## Phase 8. Reporting, docs and store

**Sentry**, all through `SentryManager` and `AppMetrics`, with names that pass `SentryNamingTest`:

- `verify.censor_sound` and `verify.censor_trim` spans.
- `censor.copy_save.sound_range_count` and `censor.copy_save.cut_count` distributions, beside `censor.copy_save.region_count`. Counts only, never times or lengths.
- A `censor.video_trimmed` tag (`true` or `false`) on the `censor.copy_save` transaction.
- Add them all to both Names in Sentry tables (`README.md` and `site/index.html`, id `sentry-names`), and the counts to the "What Send Crash Reports captures" tables and Section 6 of `legal/redact.html`, in the same commit. The consent strings don't change: these are more of the counts they already describe.

**Docs and store**

- `README.md` and `site/index.html`, the Censor section and the "Censor a video" row of "What happens to your files".
- Play listing `full_description.txt` in every locale, where it lists what Censor does.
- `.claude/commands/test.md` and `/verifyclean` steps for a trimmed, bleeped and brushed video.
- `CLAUDE.md`, add `requireSoundCensored` and `requireTrimmed` to the checks that must never be weakened.

## Test plan

| Test | Covers |
|---|---|
| `VideoCensorEditTest` (new) | Kept ranges, inward rounding, source to output time mapping, areas split at cuts |
| `VideoSoundCensorTest` (new) | Mute and beep on generated PCM, widening, fades, merging, channel counts, 16-bit and float |
| `CensorVerifierTest` (extended) | `requireTrimmed` and `requireSoundCensored` pass on good output and fail on each deliberate bug. Stroke coverage |
| `VideoCensorLayoutTest`, `VideoCensorShaderProgramTest` (extended) | Stroke tracks and masks |
| `VideoCensorExporterTest` (extended) | Sequences of clipped items, audio processors passed through, Remove Audio winning |
| `CensorViewModelTest`, `CensorCanvasViewTest` (extended) | Trim handles, cuts, sound ranges, brush on video, undo and redo |
| `make_instrumented_media.py` (extended) | A video with a spoken countdown and a tone marker, so a test can tell which second it hears |
| `CensorOnDeviceTest` on `Pixel_10_Pro` (extended) | A trimmed, cut, bleeped and brushed export on real codecs. The managed emulator's video decoders fail, so this runs on the test emulator, as now |

## Risks

| Risk | Mitigation |
|---|---|
| Clipping keeps a frame before the requested start | Inward rounding, and `requireTrimmed` compares the edge frames with the source |
| The AAC encoder spreads speech past a muted edge | Two-frame widening, and the check measures the level inside every range |
| Sample counting drifts from the video's time when the source's audio starts late or has gaps | Count from each part's first audio timestamp, not from zero, and let the check find the range by time in the saved file |
| People can't place a sound range without hearing it | Phase 5 adds Play and a waveform before sound ranges ship |
| Brush masks use too much GPU memory at 4K | A cap on strokes, shared masks per effect, and the 20 strokes at 4K test |
| Splitting areas at cuts adds bugs to moving areas | Tests for a moving area across a cut, and the coverage check on every part |

## Open decisions

| Decision | Default |
|---|---|
| Bleep tone | 1 kHz sine at -12 dBFS, the broadcast convention. No choice of tone at first |
| Default sound mode | Bleep, so people hear that something was removed. Mute is one tap away |
| Cuts | Any number. Start with one if the timeline gets crowded on a phone |
| Moving brush strokes | Not planned. Revisit if people ask |
| Trim in Clean or Convert | No. Revisit if people ask |
