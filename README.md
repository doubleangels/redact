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

Protect your privacy with Redact - the powerful yet simple app that removes all EXIF metadata from photos and videos before sharing online. Prevent location tracking, hide device information, and secure your personal data when posting on social media platforms.

**Take control of your digital footprint and share content on your terms!**

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [How It Works](#how-it-works)

- [FAQ](#faq)

  - [What exactly is EXIF data?](#what-is-exif-data)
  - [Does Redact alter the quality of my photos or videos?](#quality-preservation)
  - [Does Redact need internet access?](#internet-access)
  - [Where are processed files stored?](#file-storage)
  - [Are there any analytics or trackers?](#analytics-tracking)

- [Reporting Issues & Feedback](#reporting-issues--feedback)
- [Privacy & Security](#privacy--security)
- [License](#license)

---

## Key Features:

- **Complete Privacy Protection & EXIF Cleaner:**  
  Remove all metadata including GPS location data, device information, timestamps, and personal details hidden in your media files. Uses advanced remuxing for videos to ensure complete metadata removal.

- **Metadata Viewer & Inspector:**  
  Easily scan and view all hidden EXIF data in your photos and videos before completely removing it. View metadata in an organized, alphabetically sorted format.

- **User-Friendly Privacy Interface:**  
  One-tap complete metadata removal makes protecting your digital privacy simple, quick, and accessible for everyone.

- **Bulk Photo & Video Processing:**  
  Clean multiple media files simultaneously, saving you time while enhancing your online privacy protection.

- **Original Quality Preservation:**  
  Remove all metadata without compressing or degrading your photos and videos - maintain full image quality. Only essential codec parameters are preserved for proper playback.

- **100% Local & Secure Processing:**  
  All data cleaning happens directly on your device - your personal information never leaves your phone or connects to external servers. Secure file deletion ensures no metadata traces remain.

- **Multi-Language Support:**  
  Available in 13 languages: English, Spanish, French, German, Italian, Portuguese, Russian, Japanese, Korean, Chinese (Simplified & Traditional), Hindi, and Arabic. Supports Android's native per-app language settings.

- **Ad-Free & Open-Source Privacy Tool:**  
  Committed to transparency with no advertising, tracking, or data collection. Our code is fully open-source for community verification.

- **Android Share Sheet Integration:**  
  Seamlessly clean files from any app via Android's share menu and get back completely privacy-protected files ready to share.

Take control of your digital footprint! EXIF metadata can expose your precise GPS coordinates, device details, camera settings, timestamps, and unique identifiers. Redact eliminates all these privacy risks while preserving the quality of your media files.

---

## Installation

### Google Play Store

Install Redact through the [Google Play Store](https://play.google.com/store/apps/details?id=com.doubleangels.redact).

### Requirements

- **Minimum Android Version:** Android 12 (API 31)
- **Target Android Version:** Android 15 (API 35)
- **Permissions Required:**
  - **Android 13+:** `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` (required for selecting media files)
  - **Android 12 and below:** `READ_EXTERNAL_STORAGE` (required for selecting media files)
  - **Optional:** `ACCESS_MEDIA_LOCATION` (only needed to view GPS location metadata in media files)

---

## How It Works

Redact helps you protect your privacy in two simple ways:

1. **Gallery Selection:**

   - Open Redact
   - Select multiple photos and videos
   - Tap once to remove EXIF data from all selected files
   - Share your cleaned files without privacy concerns

2. **Share Integration:**
   - When viewing a photo or video in any app
   - Use the share function
   - Select Redact from the share menu
   - Get back a clean file ready to share further

All processing happens locally on your device, with no internet connection required for core functionality. None of your files are ever sent to external servers.

---

## FAQ

### <a id="what-is-exif-data"></a>What exactly is EXIF data?

EXIF (Exchangeable Image File Format) data is hidden metadata embedded in photos and videos that can include:

- GPS location coordinates
- Date, time, and timezone information
- Device manufacturer and model
- Camera settings (aperture, shutter speed, etc.)
- Sometimes even unique identifiers

### <a id="quality-preservation"></a>Does Redact alter the quality of my photos or videos?

No. Redact is designed to preserve the original quality of your media files while removing only the metadata.

### <a id="internet-access"></a>Does Redact need internet access?

No. The core functionality works completely offline. Your files never leave your device.

### <a id="file-storage"></a>Where are processed files stored?

Cleaned files are saved to your device's storage in a designated folder for easy access. When sharing files via the share menu, temporary cleaned files are automatically deleted after sharing to protect your privacy.

### <a id="analytics-tracking"></a>Are there any analytics or trackers?

This app uses [Google Firebase](https://firebase.google.com) and only collects anonymized data to help diagnose issues. The information I receive includes:

- **Device model and type**
- **Operating system version**
- **App version**
- **Battery life, memory usage, and storage status when an error occurs**
- **Connection type (Wi-Fi/cellular) and VPN status at the time of an error** (No IP addresses are collected or logged)
- **Detailed crash reports and error logs**
- **Performance metrics for specific code sections**

---

## Reporting Issues & Feedback

If you encounter any issues or have suggestions to improve Redact, please:

1. Check for existing issues in the [GitHub Issues](https://github.com/doubleangels/redact/issues) section
2. Open a new issue with a detailed description if your problem hasn't been reported

Your feedback helps make Redact better for everyone!

---

## Privacy & Security

Redact is built with privacy as its core principle:

- Your files remain yours, they never leave your device
- No network requests needed for core functionality
- Open-source code for transparency
- Secure file deletion with data overwriting
- Comprehensive metadata removal using reflection to catch all possible EXIF tags
- Video metadata removal via remuxing (only essential codec parameters preserved)
- Temporary files are automatically cleaned up after sharing

Your privacy is not just a feature - it's the entire point of this app.

**[Read my full Privacy Policy](https://doubleangels.github.io/privacypolicy/redact.html)**

---

## License

Redact is released under the [GNU General Public License v3.0](LICENSE).

---

I hope you enjoy using Redact to protect your privacy online!
