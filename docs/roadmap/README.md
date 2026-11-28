# Roadmaps

Plans for features that aren't built yet. Each one lists its phases, what's done when, tests, risks and open decisions.

| Roadmap | What it adds | Size | Status |
|---|---|---|---|
| [What's New](whats-new.md) | A plain-English screen after each update, written before release | Medium | Done |
| [Tell a Friend](tell-a-friend.md) | A Settings row that shares a short message and the Play link | Small | Done |
| [Clean Presets](clean-presets.md) | One choice that sets Clean's keep options for a purpose | Small to medium | Done |
| [Video Censor](video-censor.md) | Black, Pixelate and Blur for fixed and moving areas of a video | Large | Built (device checks left) |
| [Video Tracking](video-tracking.md) | Track a drawn area through a video and suggest faces, on the phone | Large | Planned |
| [More Formats](more-formats.md) | Keep DNG raw photos as DNG, open TIFF, and test more video containers | Medium to large | Planned |
| [Privacy Additions](privacy-additions.md) | Warnings about faces and text in a photo, suggested censor areas, screenshot cleanup, permanent delete and a camera fingerprint study | Large | Planned |
| [Private Notifications](private-notifications.md) | Keep counts and locations removed off the lock screen and off paired watches | Small | Planned |
| [Video Censor Additions](video-censor-additions.md) | Mute or bleep part of a video's sound, trim or cut sections, and the brush on video | Large | Planned |
| [Hidden Previews](hidden-previews.md) | Show the preview pictures hidden inside a photo in Scan, and warn when one shows more than the photo | Small to medium | Planned |
| [Animated Censor](animated-censor.md) | Censor every frame of an animated GIF, WebP or PNG and keep it animated | Medium | Planned |
| [Replace Originals](replace-originals.md) | Write the clean version over the original instead of saving a duplicate | Medium | Planned |
| [Test and Build Guards](test-and-build-guards.md) | Pinned dependencies, a second metadata reader, a real Sentry SDK test, ArchUnit, NullAway, mutation testing and UI journeys | Large | Planned |

Privacy Additions and Video Tracking share the LiteRT runtime and YuNet face detector spike. Whichever starts first does it. Replace Originals and Privacy Additions' permanent delete share the choice of what happens to originals after a clean in the same way.

Every roadmap follows the privacy rules in `CLAUDE.md`. None of them weakens a clean check, adds a permission, or sends anything about a file.
