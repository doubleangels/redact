# Roadmaps

Plans for features that aren't built yet. Each one lists its phases, what's done when, tests, risks and open decisions.

| Roadmap | What it adds | Size | Status |
|---|---|---|---|
| [What's New](whats-new.md) | A plain-English screen after each update, written before release | Medium | Done |
| [Tell a Friend](tell-a-friend.md) | A Settings row that shares a short message and the Play link | Small | Done |
| [Clean Presets](clean-presets.md) | One choice that sets Clean's keep options for a purpose | Small to medium | Done |
| [Video Censor](video-censor.md) | Black, Pixelate and Blur for fixed areas of a video | Large | Planned |
| [Audio Cleaning](audio-cleaning.md) | Clean and Scan voice memos and music files | Medium | Planned |
| [PDF Cleaning](pdf-cleaning.md) | Clean or Flatten PDFs, removing names, revisions and image data | Large | Planned |

Audio Cleaning and PDF Cleaning share the `MediaKind` refactor. Whichever ships first does it.

Every roadmap follows the privacy rules in `CLAUDE.md`. None of them weakens a clean check, adds a permission, or sends anything about a file.
