<p align="center">
  <img src="https://img.shields.io/github/actions/workflow/status/doubleangels/redact/.github/workflows/deploy.yml?label=Deployment%20Pipeline&style=for-the-badge" alt="Main Deployment">
  <img src="https://img.shields.io/github/actions/workflow/status/doubleangels/redact/.github/workflows/test-dev.yml?label=Development%20Testing&style=for-the-badge" alt="Development Testing">
  <img src="https://img.shields.io/librariesio/github/doubleangels/redact?label=Dependencies&style=for-the-badge" alt="Dependencies">
  <img src="https://img.shields.io/github/issues/doubleangels/redact?label=GitHub%20Issues&style=for-the-badge" alt="GitHub Issues">
  <img src="https://img.shields.io/github/issues-pr/doubleangels/redact?label=GitHub%20Pull%20Requests&style=for-the-badge" alt="GitHub Pull Requests">
</p>

<p align="center">
  <img src="icons/web/icon.png" alt="Redact Icon" width="96">
  <br>
  <a href="https://play.google.com/store/apps/details?id=com.doubleangels.redact">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="48">
  </a>
</p>

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Screenshot of Redact" width="250">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="Screenshot of Redact" width="250">
</p>

# Redact: Privacy & Metadata Remover

Redact helps you protect your privacy by removing the hidden information that cameras and phones quietly attach to your photos and videos. This hidden information is called metadata, and it can include things like your exact GPS location, the date and time a photo was taken, and even the model of camera or phone that captured it. Redact strips all of that out, lets you look at exactly what is embedded in a file before you decide to share it, and can also convert files between formats, all directly on your device.

**Take control of your digital footprint and share your photos and videos on your own terms.**

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [How It Works](#how-it-works)
- [FAQ](#faq)
- [Reporting Issues & Feedback](#reporting-issues--feedback)
- [Privacy & Security](#privacy--security)
- [License](#license)

---

## Features

- **Thorough metadata cleaning.** Redact removes GPS coordinates, device information, timestamps, and other hidden EXIF and container metadata from your images and videos. A mode called Strict Clean re-encodes your videos for the most thorough removal possible, and a faster alternative is available on compatible files when you would rather save time.

- **A metadata scanner you can actually read.** Redact lets you view a file's metadata, laid out in an organized, easy-to-follow list, before you decide what to do with it. You can copy camera details or the full metadata list to your clipboard, send the file straight to Clean or Convert, or, if the file has a location attached, open that location in your phone's default maps app. Opening a location in your maps app shares those coordinates with that separate app, so the first time you use this feature, Redact explains exactly what is happening and asks for your permission.

- **Local format conversion.** Redact converts images between JPEG, PNG, WebP, and HEIC (on supported devices), and transcodes video between H.264, H.265, VP9, and AV1, all without uploading anything to the cloud. Convert also strips metadata from what it produces and verifies the result, just as Clean does, so a converted file does not carry the original's location or device details.

- **Optional trash for originals.** Turn on **Delete Originals After Cleaning** in Settings and Redact asks Android to move the original files to trash after a successful clean. Android shows its own confirmation each time, so nothing is removed without your say-so.

- **Batch processing.** Clean or convert as many as twenty files in a single batch, and Redact will tell you if some files succeeded while others failed, rather than giving up on the whole batch.

- **Support for large files.** Redact does not enforce a hard size limit when cleaning, converting, or sharing files in. If your device is running low on storage or memory, Redact warns you before it starts, so you have a chance to free up space first.

- **Share sheet integration.** You can share a photo or video into Redact from any other app on your phone. An optional confirmation dialog, a progress indicator you can cancel at any time, reporting on partial batch success, and automatic cleanup of temporary files are all built in.

- **Permissions that respect your privacy.** Redact uses Android's built-in document picker on Android 13 and newer, so it can reach your Downloads, Files app, and gallery folders without needing broad access to all your media. Granting the optional location permission lets Scan reveal GPS fields when they exist. Notifications stay off until you turn them on yourself.

- **Processing that stays on your device.** Your photos and videos are never uploaded anywhere for Redact's core features. The one exception is opening a scanned location in your maps app, since that necessarily shares the coordinates with that other app, and it only ever happens after you agree to it. Optional crash reporting, also off by default, sends anonymized diagnostic information only, which is covered in more detail below.

- **Thirteen languages.** Redact is available in English, Spanish, French, German, Italian, Portuguese, Russian, Japanese, Korean, Chinese (both Simplified and Traditional), Hindi, and Arabic.

- **Open source and free of ads.** Redact has no ads and no behavioral tracking, and its source code is on GitHub for anyone to review.

---

## Installation

### Google Play Store

Download Redact from the [Google Play Store](https://play.google.com/store/apps/details?id=com.doubleangels.redact).

### Requirements

- **Minimum Android version:** Android 12 (API 31).
- **Target Android version:** API 37.
- **Permissions Redact uses:**
  - Starting with Android 13, file selection relies on the system document picker, so you can browse Downloads, Files, or any folder without granting broad `READ_MEDIA_*` access. The optional `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` permissions, or the partial-access option on Android 14 and newer, can still help Redact in certain situations.
  - The optional `FOREGROUND_SERVICE` permission shows a progress notification during long clean or convert jobs, as long as notifications are turned on.
  - Android 12 and earlier relies on `READ_EXTERNAL_STORAGE` to reach your gallery instead.
  - The optional `ACCESS_MEDIA_LOCATION` permission lets Redact read the GPS coordinates embedded in your media while using Scan.
  - The optional `POST_NOTIFICATIONS` permission lets Redact alert you when a task finishes or show progress along the way. This stays off until you switch it on in Settings.

---

## How It Works

### Using the in-app file picker

1. Open the **Clean**, **Scan**, or **Convert** tab.
2. Tap **Select Files** and browse Downloads, Documents, your gallery folders, or any other storage provider on your device.
3. Choose up to twenty images or videos for Clean or Convert, or a single file for Scan.
4. **Clean** strips the metadata and saves the result to `Pictures/Redact` or `Movies/Redact`.
5. **Scan** shows you the file's metadata and gives you action buttons to copy fields, open the file in Clean, send it to Convert, or open an attached location in your maps app. Rows that reveal where a file was taken, such as GPS coordinates, carry a **Location** badge. Grant the photo-location permission if you want Scan to show GPS coordinates at all.
6. **Convert** changes the file's format or codec and strips and verifies the metadata of the result, the same way Clean does.

### Using share integration

1. Share a photo or video into Redact from any other app.
2. Confirm that you want Redact to strip the file's metadata, if you have that confirmation step turned on in Settings.
3. Redact removes the metadata locally and then reopens the share sheet with the cleaned file or files ready to send.
4. Cancel a long-running job at any point from the progress dialog, if you change your mind partway through.

Every core processing step happens locally on your device. Optional crash reporting, which stays off unless you turn it on, may use the network to send anonymized diagnostic information, and opening a scanned location in your maps app, which only happens after you agree to it, hands that location to whichever maps app you have set as your default.

---

## FAQ

### What exactly is EXIF data?

EXIF, short for Exchangeable Image File Format, along with the related metadata that video containers carry, can describe quite a lot about how and where a photo or video was made. Common examples include:

- Your GPS location at the time the photo or video was captured
- The date, time, and timezone it was recorded in
- The manufacturer and model of the device that created it
- Camera settings such as aperture, shutter speed, and ISO
- XMP and IPTC tags, along with other video container tags

### Does Redact change the quality of my photos or videos?

Cleaning is designed to keep your photo or video looking the way it did before, while still removing its hidden metadata. Strict Clean and a full video transcode do re-encode your video, which can change some of its encoding details, though image cleaning preserves orientation whenever that is possible.

### Does Convert remove metadata too?

Yes. Images are re-encoded without their EXIF data, and videos have their metadata removed before they are transcoded and are checked afterward, so the converted copy is clean. The original file is never modified.

### Does Redact need an internet connection?

No, you do not need an internet connection to clean, scan, or convert your files. Two optional features do use the network: turning on **Send Crash Reports** in Settings sends anonymized crash diagnostics to [Sentry](https://sentry.io) over a secure connection, and opening a scanned location in your maps app hands those coordinates to that separate app, which may itself use the network to display the map. Both of these stay off until you explicitly choose to use them, and Redact asks for your permission the very first time you open a location in maps.

### Where does Redact store my processed files?

Cleaned and converted files land in `Pictures/Redact` or `Movies/Redact`, managed through Android's MediaStore. Files you share in are kept in the app's temporary cache and deleted once sharing is done, with a short delay built in so the app you shared to has time to finish reading them.

### How does secure delete and cache cleanup work?

Settings let you ask Redact to overwrite temporary files with random data before deleting them, though this offers limited benefit on modern flash storage. Redact automatically clears out processing cache files older than twenty-four hours every time the app starts, whether you opened it directly or arrived through the share sheet, and you can also clear temporary files manually from Settings whenever you like.

### Are there any file size limits?

No, Redact does not enforce a hard size cap. Very large files can still run into trouble on a device that is low on free storage or memory, so Redact shows a warning beforehand whenever that looks likely. Lowering the **Max Processing Resolution** setting can reduce how much memory Redact needs while decoding large images.

### Does Redact use analytics or trackers?

Optional crash reporting through [Sentry](https://sentry.io) is the only thing of this kind in Redact, and it stays disabled until you turn it on yourself in Settings.

Once enabled, the anonymized data it sends may include your device model, Android version, the app's version, stack traces, and a handful of scrubbed diagnostic tags. File paths, filenames, and GPS coordinates are all removed before anything is sent. Your actual photos and videos are never uploaded, under any circumstance.

---

## Reporting Issues & Feedback

1. Take a look through the existing [GitHub Issues](https://github.com/doubleangels/redact/issues) first, in case someone has already reported what you are seeing.
2. Open a new issue with the steps to reproduce the problem, if you cannot find one that already matches.

---

## Privacy & Security

- Your files stay on your device for every core feature.
- Cleaning, scanning, and converting never require a network connection.
- Opening a scanned location in your maps app is the one feature that shares data with another app, and Redact only does this after you give explicit, one-time consent.
- The source code is public, so anyone can review exactly what Redact does.
- Temporary files can be securely deleted, within the limits of what modern flash storage allows.
- Share-in always strips location data. Clean can preserve it only if you explicitly turn on **Keep Location** (with a confirmation prompt) and Strict Clean is switched off.
- Settings include options for clearing temporary and stale cache files whenever you want.

**[Privacy Policy](https://doubleangels.github.io/privacypolicy/redact.html)**

---

## License

Redact is released under the [GNU General Public License v3.0](LICENSE).

---

Thank you for trusting Redact to help protect your privacy. I hope it serves you well!
