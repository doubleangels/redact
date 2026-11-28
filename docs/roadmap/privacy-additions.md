# Privacy Additions roadmap

Five additions that go past metadata: warnings about what a photo shows, suggested censor areas, screenshot cleanup, a permanent delete option and a study of camera fingerprints. Everything runs on the phone.

| | |
|---|---|
| **Status** | Planned |
| **Branch** | `dev` |
| **Size** | Two large, two small, one research study |
| **Depends on** | Items 1 and 2 share the runtime and face detector spike with [Video Tracking](video-tracking.md#phase-1-runtime-and-model-spike) Phase 1. Whichever starts first does it |

## Contents

1. [Overview](#overview)
2. [1. Visual leak warnings in Scan](#1-visual-leak-warnings-in-scan)
3. [2. Suggested censor areas](#2-suggested-censor-areas)
4. [3. Screenshot cleanup](#3-screenshot-cleanup)
5. [4. Permanent delete option](#4-permanent-delete-option)
6. [5. Camera fingerprint reduction (research)](#5-camera-fingerprint-reduction-research)
7. [Local model selection](#local-model-selection)

## Overview

Five items, built in this order. Items 1 and 2 share one on-device detector, so they ship as a pair. Item 4 is small and can go out in any release. Item 5 is research and ships only if the tests justify the claim.

| # | Item | Size | Depends on |
|---|---|---|---|
| 1 | Visual leak warnings in Scan | Large | New detector module |
| 2 | Suggested censor areas | Medium | Item 1's detector |
| 3 | Screenshot cleanup | Small | Censor crop |
| 4 | Permanent delete option | Small | Nothing |
| 5 | Camera fingerprint reduction | Research | Test corpus |

Rules every item follows, from `CLAUDE.md`.

- Detection results, coordinates and counts tied to a file never reach Sentry. Only aggregate outcomes go through `SentryManager` and `AppMetrics`, with names that pass `SentryNamingTest` and appear in the `sentry-names` tables.
- Every new string goes into `values/` and all 12 locales, checked by `StringTranslationsTest`.
- No network. Models ship inside the APK, and the release manifest must not change except where an item says so.
- Nothing weakens `CleanVerifier` or `CensorVerifier`. New output paths fail closed.

## 1. Visual leak warnings in Scan

Scan gains a "What the picture shows" card that flags faces, readable text and QR codes or barcodes, found on the phone by models bundled in the APK. Today Scan reads only metadata (`ScanFragment`, `ScanViewModel`), yet a street sign or a boarding pass in the frame leaks more than any EXIF tag.

ML Kit and MediaPipe Tasks both send usage logs to Google with no way to turn them off, so neither can ship. The chosen stack runs plain model files on LiteRT 1.x. See [Local model selection](#local-model-selection) for the evidence.

### Detection stack

| Finding | Chosen | Licence | Size |
|---|---|---|---|
| Faces | YuNet 2023mar, int8 | MIT | ~0.1 MB |
| Text regions | PP-OCRv5 mobile detector, converted to LiteRT | Apache 2.0 | ~4.7 MB before quantization |
| QR and barcodes | zxing-cpp Android | Apache 2.0 | Native library, measure in Phase 0 |
| Number plates | Covered by the text detector in v1 | n/a | 0 |
| Runtime | LiteRT 1.x (`com.google.ai.edge.litert:litert`) | Apache 2.0 | Measure in Phase 0 |

The added APK size budget is 5 MB per ABI. Phase 0 measures it with `bundleRelease`.

### UX

- A card under the metadata lists each kind once, for example "2 faces", "Readable text", "QR code (website link)".
- Tapping it shows the photo with the boxes outlined and a **Censor These** button that opens Censor with them as suggestions (item 2).
- When nothing is found the card says "No faces or text found. Look over the photo yourself before sharing." It never says the photo is safe.
- Batch Scan (`BatchScanSummary`) adds "with faces" and "with readable text" counts beside location and date.
- New setting **Check Photo Content**, on by default, under Privacy. Off skips the models entirely.

### Phases

1. **Phase 0, spike (1 week).** Run each candidate on the test emulator with traffic captured. Any request outside the app's known Sentry and update hosts disqualifies the library. Measure APK growth with `bundleRelease`, run time on a 12 MP photo and recall on a labelled set. Shared with Video Tracking Phase 1.
2. **Phase 1, photos.** New `metadata/VisualLeakDetector` returns `VisualFinding(kind, box in fractions, confidence)`. It runs on a copy decoded at 1024 px on the long edge, on `AppProcessingScope`, and never writes to disk. Wire the Scan card and the setting.
3. **Phase 2, batch and video.** Batch counts. Videos sample one frame per second, capped at 30 frames, and report the time of each finding.
4. **Phase 3, docs.** README, `site/index.html` and the Play listing describe the feature and its limits.

### Tests

- Robolectric tests for box merging, the empty-result wording and the setting.
- Instrumented test with fixtures from `.claude/scripts/make_instrumented_media.py`. Text and QR images are drawn with Pillow and `qrcode`. Faces come from a CC0 set kept in the repo.
- Acceptance bar on the labelled set: face recall of 90% or more for faces wider than 5% of the frame, text recall of 85% or more, under 1.5 s per photo on the test emulator.

### Sentry

Only `scan.visual.duration` and an `outcome` of ok, failed or disabled go out. No counts, kinds or boxes, even aggregated, because they describe what is in a photo. Add the metric to both `sentry-names` tables and to Section 6 of `legal/redact.html`.

### Risks

- False confidence from a missed face. Mitigated by the wording above and by never auto-censoring.
- Model files are a new supply chain input. Pin them by SHA-256 in the build and record their licences in `OpenSourceLicenses`.
- Open question: should the QR warning show the decoded content? It helps the user judge, but puts a Wi-Fi password on screen. Proposed answer is kind only, with content behind a tap.

## 2. Suggested censor areas

Censor shows the detector's findings as dashed outlines the user accepts or rejects one by one. An accepted outline becomes a normal rectangle region, so saving, undo and `CensorVerifier` work exactly as today. Nothing is covered until the user taps it.

### UX

- A **Suggest** button in the floating toolbar runs the detector on the open photo. Opening Censor from Scan's **Censor These** shows the suggestions straight away.
- Each suggestion is a dashed outline with a label (Face, Text, QR code). Tap to accept with the selected effect, long-press to dismiss. **Accept All** sits beside the count.
- A banner stays while suggestions are on screen: "Suggestions can miss things. Check the whole photo."
- Accepting one or all is a single undo step.
- Faces are padded by 20% on each side so hair, ears and glasses are covered. Text and QR boxes by 8%.

### Phases

1. **Photos.** Add a `suggestions` list to `CensorViewModel`, kept apart from `regions` and the `Edit` history so a dismissed suggestion never reaches the exporter. `CensorCanvasView` draws them. Accepting calls the existing `addRegion` with `CensorRegion.rect(...)`. `ScanFragment.openInCensorTab` passes Scan's findings through `requestOpen` so the photo is not run twice.
2. **Videos.** Covered by [Video Tracking](video-tracking.md#phase-5-face-suggestions) Phase 5, which suggests faces as tracked moving areas. This roadmap adds only the Text and QR kinds to that flow once both exist. Sampling can miss a face that appears for under a second, so the banner says so for videos.
3. **Crop suggestions.** Out of scope. Crop stays manual, except for screenshots (item 3).

### Tests

- Robolectric: accept, dismiss, Accept All, undo and redo leave `regions` and history in the expected state. A dismissed suggestion never appears in what `CensorExporter` receives.
- Instrumented: accept all suggestions on the face and text fixtures, save, then confirm `CensorVerifier` passes and the covered area no longer detects a face.

### Sentry

`censor.suggest.duration` and `outcome` only. Whether a user accepted suggestions is not sent, since that reveals what the photo held.

### Risks

- A user may treat Accept All as complete. The banner is the guard. Do not add a "done" checkmark that implies coverage.

## 3. Screenshot cleanup

When Scan or Censor opens a screenshot, Redact offers to crop off the status bar and navigation bar, which show the time, carrier, battery and the icons of apps that just sent notifications. It reuses Censor's crop (`CensorViewModel.setCrop`), so no new export path is needed.

### Detection

A file counts as a screenshot when any of these hold, checked in this order.

1. Its `RELATIVE_PATH` from `MediaUriResolver` ends in `Screenshots/` (Pixel, Samsung and most others use `Pictures/Screenshots` or `DCIM/Screenshots`).
2. Its pixel size equals this phone's display size from `WindowMetrics`, in either orientation.
3. Its pixel size has a common phone aspect ratio (19.5:9 to 21:9), it is a PNG and it has no camera EXIF.

Rule 3 alone only shows a softer hint, since it also matches some edited images.

### Where the bars are

- Rule 2 match: use this phone's `statusBars()`, `navigationBars()` and `displayCutout()` insets as fractions of the height. Exact.
- Otherwise: propose the top 4% and bottom 3% (a typical gesture bar), and let the user adjust. The crop handles already exist.

### UX

- Scan shows a finding "This looks like a screenshot. The bars at the top and bottom can show the time, battery, carrier and notification icons" with **Crop Bars**.
- **Crop Bars** opens Censor with the crop already set. The user sees it, can adjust it and saves as usual.
- Censor shows the same one-line offer when a screenshot is opened directly.
- No new setting. The offer is dismissible per file.

### Phases

1. `ScreenshotCheck` helper (pure function of path, size, MIME and display metrics) plus the Scan finding and the Censor prefill.
2. With item 1's text detector in place, also suggest covering text in the visible notification area of a screenshot taken with the shade pulled down.

### Tests

- Robolectric tests for `ScreenshotCheck` with each rule, both orientations, a foldable's two display sizes and a non-matching camera photo.
- A test that **Crop Bars** sets the crop from the insets and that the saved copy's height equals the original minus both bars.
- Add a 1080 x 2400 PNG screenshot fixture to `.claude/scripts/make_test_media.py`.

### Sentry

`censor.screenshot_crop` counted with `outcome` accepted or dismissed. This says nothing about the file, only that a screenshot was cropped. Add it to both `sentry-names` tables.

## 4. Permanent delete option

Delete Originals After Cleaning gets a choice between **Move to Trash** (today's behavior, still the default) and **Delete Permanently**. Trashed originals sit in the gallery's trash for about 30 days with their location intact, and anyone with the unlocked phone can restore them.

### How

- Today `CleanFragment` and `CensorFragment` call `MediaStore.createTrashRequest`. Move both into one helper, `OriginalsRemoval`, that picks `createTrashRequest` or `createDeleteRequest` from the setting. Both show Android's own confirmation, so nothing new is deleted without consent.
- Keep `trashableMediaUri` for the `.../file/<id>` case. `createDeleteRequest` rejects the same URIs.
- Keep the boolean `delete_originals_after_clean` key as is. Add a separate `delete_originals_mode` key (`trash` or `delete`) shown only while the switch is on.

### What it does not do, said in the docs

- It does not overwrite the bytes. On flash storage with wear levelling, overwriting from an app does not reach every copy, so a "secure erase" claim would be false. File-based encryption already makes deleted blocks hard to read, and the README can say that much.
- It does not touch cloud backups. A copy already uploaded to Google Photos or OneDrive keeps its metadata. The setting's summary says so in one line.

### UX

- Under **Before & After Cleaning**, a two-option row under the switch. Choosing **Delete Permanently** asks once: "Originals will skip the trash and can't be restored from Redact or your gallery."
- After a permanent delete, the summary card says "Originals deleted" instead of "Originals moved to trash".

### Phases

1. `OriginalsRemoval` refactor with no behavior change, covered by tests first.
2. The new key, setting row, confirmation and summary wording. Strings in `values/` and all 12 locales.
3. README Settings table, `site/index.html` and the FAQ.

### Tests

- Robolectric: the helper builds a trash request for `trash`, a delete request for `delete`, and nothing when the switch is off, for images, videos and `.../file/<id>` URIs.
- **Reset All Settings** returns the mode to `trash`.
- On the test emulator: clean one photo with each mode, confirm the system dialog, then check `MediaStore` for `IS_TRASHED` or absence.

### Sentry

Add tag `setting.delete_originals_mode` beside the existing `setting.delete_originals` in `AppStateTags`. Update both `sentry-names` tables in the same commit.

## 5. Camera fingerprint reduction (research)

A two-week study decides whether Redact can honestly offer a **Reduce Camera Fingerprint** option for photos. Each sensor leaves a noise pattern (PRNU) that survives metadata removal and plain re-encoding, so it can link an anonymous photo to the same phone's other photos. The study ships nothing unless a transform passes the gate below.

### Threat model

The attacker has 20 or more photos known to come from the victim's phone, builds its fingerprint and tests whether an anonymous photo matches. Linking two copies of the same photo by perceptual hash is a different problem and out of scope.

### Method

1. **Corpus.** The public VISION dataset (35 phones) and the Dresden Image Database. No new photos taken.
2. **Baseline.** Build each phone's fingerprint from 50 flat images and score test photos by PCE (peak-to-correlation energy). PCE above 60 is the usual match threshold. Measure it on originals, on Strict Clean output (pixels unchanged, so it should match) and on Convert output at JPEG quality 85.
3. **Transforms**, each alone and in pairs.
    - Downscale to 75% and 50%.
    - Denoise (wavelet or non-local means), then add fresh synthetic noise.
    - Small geometric change: rotate 0.3 to 1 degree, crop 1 to 3%, slight non-uniform scale. Misalignment breaks PCE without visible loss.
4. **Quality.** Score each output with SSIM and a perceptual metric (LPIPS or Butteraugli) against the original.

### Gate

- **Go** if one transform keeps at least 95% of photos below PCE 60 across both datasets with SSIM of 0.95 or more, and still holds when the attacker re-aligns by search over rotation and scale.
- **No-go** otherwise. Write the findings into the README FAQ ("Can a photo be traced to my camera?") so users know the limit, and stop.

### If it ships

- A separate option in Clean, off by default and ignored by Strict Clean, because it changes pixels while Strict Clean promises they stay as encoded. Also offered in Convert and Censor, which already re-encode.
- Wording says "makes it much harder to match" with the measured rate, never "removes".
- The app cannot verify the result without the attacker's fingerprint. What it can verify is that the transform ran with the expected parameters. If it cannot confirm that, it does not save and does not claim the option applied, in line with fail-closed.

### Open questions

- Does Redact keep a Python research harness in `.claude/scripts/`, or does the study live outside the repo?
- Is a visible quality cost acceptable for the people who need this most (whistleblowers, activists), if no invisible transform passes?

## Local model selection

Use YuNet for faces, the PP-OCRv5 mobile detector for text and zxing-cpp for QR codes, all run on LiteRT 1.x with the model files inside the APK. Every rejected option either sends data to Google, needs Play services, has an unclear weights licence or adds 7 MB or more. This agrees with the runtime and face detector already chosen in [Video Tracking](video-tracking.md#phase-1-runtime-and-model-spike).

### Runtime

| Option | Verdict | Why |
|---|---|---|
| LiteRT 1.x standalone | **Use** | Plain `.tflite` files, no Play services. Nextcloud Talk moved to `com.google.ai.edge.litert:litert:1.4.2` in Nov 2026 to get rid of MediaPipe's telemetry |
| LiteRT 2.x | Reject | Pulls in Play Asset Delivery (`com.google.android.play:ai-delivery`), which F-Droid flags as non-free and which can fetch models over the network. Pin `<2` in Gradle |
| MediaPipe Tasks | Reject | `tasks-core` sends task name, running mode, device info, latency and frame counts to Google through `datatransport`. A maintainer confirmed in Nov 2026 there is no opt-out on Android |
| ML Kit (bundled) | Reject | Google's disclosure says it collects device, app and performance data for analytics, and may contact Google for model updates |
| ONNX Runtime | Reject | Prebuilt AAR is about 24 MB. A custom minimal build is about 7.5 MB and needs a custom build pipeline |
| OpenCV Android SDK | Reject | Runs YuNet natively, but is tens of MB for one function |

`.github/scripts/check_release_manifest.py` already guards against telemetry creeping in. `datatransport` adds services to the merged manifest, so the release build fails if any library pulls it in.

### Faces

| Model | Licence | Size | WIDER FACE hard AP | Verdict |
|---|---|---|---|---|
| YuNet 2023mar | MIT | 233 KB fp32, 100 KB int8 | 81.1% | **Use**. Smallest, takes any input size, so it can run at a higher resolution to catch small faces in crowds |
| BlazeFace full range | Apache 2.0 | 1.1 MB (sparse about 60% smaller) | Not published by Google | Backup. Made for faces within 5 m of a back camera, so weaker on group and street photos |
| RetinaFace MobileNet 0.25 | MIT ports | 1.7 MB | 82.5% (community port) | Reject. Same accuracy as YuNet at 17 times the size |
| SCRFD 0.5 to 2.5 GF | Weights licence unclear (InsightFace) | 2.7 MB and up | 77.8% and up | Reject on licence |

AP figures come from different papers and test setups, so they only rank models roughly. Phase 0 runs YuNet and BlazeFace on the same labelled set.

**YuNet plan.** Convert the official OpenCV Zoo ONNX file to `.tflite` with `onnx2tf`, or start from the community `litert-community/YuNet-Face-LiteRT` port after checking its output matches the ONNX file. Run on the photo tiled at 640 px so faces down to about 10 px are found. Keep the newer `2026may` dynamic-input release as a candidate if it converts cleanly.

### Text regions

| Model | Licence | Size | Verdict |
|---|---|---|---|
| PP-OCRv5 mobile detector | Apache 2.0 | ~4.7 MB, detection Hmean 79.0% | **Use**. Detection only, works for every script the app is translated into, official ONNX export exists |
| EAST | Apache 2.0 | ~95 MB | Reject on size |
| CRAFT | MIT | ~80 MB | Reject on size |

**Plan.** Convert the official ONNX export to `.tflite` with `onnx2tf`, then quantize to fp16 (about half the size) or int8 if recall holds within 2 points. Only the box map is used. Redact never reads the words, so no recognition model ships.

### QR codes and barcodes

| Library | Licence | Verdict |
|---|---|---|
| zxing-cpp Android (`io.github.zxing-cpp:android`, 3.1.0 in Jul 2026) | Apache 2.0 | **Use**. Finds several codes in one photo, better on angled and blurry codes |
| ZXing Java core | Apache 2.0 | Backup if the native library costs too much. Pure Java but in maintenance mode |
| ML Kit barcode | Proprietary terms | Reject, same telemetry as ML Kit above |

### Number plates

No dedicated model in v1. The text detector already boxes most plates. The best open candidate, the YOLOv9 plate models in `open-image-models`, has MIT code but no stated licence for its weights or training data. Revisit only if the labelled set shows the text detector misses more than 20% of plates.

### Phase 0 checks before any of this ships

- [ ] Capture the test emulator's network traffic while each model runs. Any connection fails the library.
- [ ] `bundleRelease` APK size per ABI with all three added, against the 5 MB budget.
- [ ] Recall on the labelled set, and time per 12 MP photo on the test emulator.
- [ ] Store each model's SHA-256 in the build and add its licence to `OpenSourceLicenses`.
- [ ] Confirm the LiteRT 1.x version is current and ships no `datatransport`.

### Sources

Pages read directly: [MediaPipe issue 6397](https://github.com/google-ai-edge/mediapipe/issues/6397), [Nextcloud Talk PR 6984](https://github.com/nextcloud/talk-android/pull/6984), [open-image-models](https://github.com/ankandrew/open-image-models).

From search results only, to verify in Phase 0: [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure), [OpenCV Zoo YuNet](https://huggingface.co/opencv/face_detection_yunet), [BlazeFace full range model card](https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20(Full%20Range).pdf), [PP-OCRv5 mobile det ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx), [PaddleOCR text detection guide](https://paddlepaddle.github.io/PaddleOCR/main/en/version3.x/module_usage/text_detection.html), [zxing-cpp Android on Maven](https://mvnrepository.com/artifact/io.github.zxing-cpp/android), [ONNX Runtime mobile sizes](https://onnxruntime.ai/docs/tutorials/mobile), [SCRFD paper](https://arxiv.org/pdf/2105.04714). EAST and CRAFT sizes are from memory.
