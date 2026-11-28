# Private Notifications roadmap

Keeps what Redact's notifications say about a clean off the lock screen and off paired devices. A locked phone shows only "Redact finished", and nothing is bridged to a watch.

| | |
|---|---|
| **Status** | Built. Still to do: the Phase 1 lock-screen check on the test emulator, with "hide sensitive content" off and on |
| **Branch** | `dev` |
| **Size** | Small. 3 phases, about one day |
| **Depends on** | Nothing |

## Contents

1. [Summary](#summary)
2. [Goals and non-goals](#goals-and-non-goals)
3. [What leaks today](#what-leaks-today)
4. [Design](#design)
5. [Phase 1. Lock-screen versions](#phase-1-lock-screen-versions)
6. [Phase 2. Keep notifications on the phone](#phase-2-keep-notifications-on-the-phone)
7. [Phase 3. Docs](#phase-3-docs)
8. [Test plan](#test-plan)
9. [Risks](#risks)
10. [Open decisions](#open-decisions)

## Summary

The clean-complete notification says how many files were cleaned and how many had a location removed. None of Redact's notifications sets a lock-screen version, so on Android's default setting ("show all notification content") anyone who picks up the locked phone can read them. The same text is also forwarded to a paired Wear OS watch and to any app with notification access. This roadmap gives every notification a generic lock-screen version and keeps them on the phone.

## Goals and non-goals

**Goals**

- A locked phone shows that Redact finished or is working, never counts, file numbers or locations removed.
- Redact's notifications are not bridged to watches or other devices.
- No new setting and no change when the phone is unlocked.

**Non-goals**

- Hiding the notifications from the user's own unlocked phone. They asked for them.
- Changing the Live Update chip's look when unlocked.
- Changing the channel's lock-screen setting. Android lets an app set it only when the channel is created, and recreating `tasks` under a new ID would reset the user's own choices for it.

## What leaks today

| Notification | Where | What it shows |
|---|---|---|
| Clean finished | `LocalNotifications.showCleanComplete` | "N files cleaned", failures, "N locations removed" |
| Convert finished | `LocalNotifications.showConversionComplete` | Files converted and failures |
| Clean, Convert and Share progress | `ProcessingForegroundService` | Title, percent, and with progress updates on, a Live Update with "3 of 10" in the status bar chip and progress segments |

The progress notification passes no file names (`message` is `null` in `updateCleanProgress` and `updateConversionProgress`), so names are already safe. The counts are the leak. "4 locations removed" tells someone at the lock screen what the owner was doing.

## Design

- Each builder calls `setVisibility(NotificationCompat.VISIBILITY_PRIVATE)` explicitly and `setPublicVersion(...)` with a minimal notification: app icon, title "Redact", text "Finished" or "Working", no progress numbers and no style.
- Each builder calls `setLocalOnly(true)`, which keeps it from being bridged to Wear OS and other devices.
- One helper, `LocalNotifications.publicVersion(Context, boolean ongoing)`, builds the public version, so the service and both completion notifications stay the same.
- Two new strings, `notification_public_working` and `notification_public_done`, in `values/` and all 12 locales.

What still shows on the lock screen: Redact's icon and name, and while working, an ongoing notification. That reveals Redact is installed and running, which the icon in the status bar already shows.

## Phase 1. Lock-screen versions

- Add `publicVersion` and the two strings.
- Apply it in `showCleanComplete`, `showConversionComplete` and `ProcessingForegroundService`'s builder, for both the start and update paths.
- Promoted (Live Update) notifications keep `setShortCriticalText` on the private version only. Check on the test emulator that the chip on the lock screen falls back to the public version.

## Phase 2. Keep notifications on the phone

- `setLocalOnly(true)` on the same three builders.

## Phase 3. Docs

- README Settings table, Notifications row: one sentence that locked phones and watches see only "Redact finished".
- `site/index.html`, same sentence.
- No Sentry change: nothing new is reported. No change to Section 6 of `legal/redact.html` or the consent strings.

## Test plan

- `LocalNotificationsTest`: for clean and convert completions, the posted notification has `visibility == VISIBILITY_PRIVATE`, a non-null `publicVersion` whose title and text contain no digits, and `FLAG_LOCAL_ONLY` set.
- `ProcessingForegroundServiceTest`: the same three checks on the start and update notifications, with progress updates on and off.
- `StringTranslationsTest` covers the two new strings.
- Test emulator: lock the screen with "hide sensitive content" off and on, run a clean with notifications and progress updates on, and check the lock screen shows only the public text.

## Risks

| Risk | Mitigation |
|---|---|
| Users who liked seeing results on the lock screen lose them | They see them as soon as they unlock. The counts were never useful enough to justify the leak |
| A Live Update ignores the public version on some Android 16 builds | Phase 1's emulator check. If the chip still shows "3 of 10" when locked, remove the file count from the chip and keep only the percent, since the app can't tell when the phone is locked |
| A later notification forgets the helper | The tests above cover every builder. A new notification needs the same assertions in its test |

## Open decisions

- Should the public text name the task ("Clean finished") or stay generic ("Finished")? Decided: generic ("Redact", "Working" or "Finished"), since "Clean" hints at what was done.
