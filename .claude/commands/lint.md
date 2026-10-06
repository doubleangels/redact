---
description: Run Android Lint, list every issue from the report, fix each one, and verify lint and the tests are clean
allowed-tools: Bash, PowerShell, Read, Edit, Write, Grep, Glob
---

Run lint on the app, list every issue it reports, fix each one, then prove lint is clean. Do not push. Only commit if I ask (or if I invoked this with the word "commit").

## 1. Run lint and list every issue

1. Run `./gradlew :app:lintRelease -q` (use `gradlew.bat` from PowerShell). This is the same task CI runs. It writes `app/build/reports/lint-results-release.html`, `.xml`, `.txt` and `.sarif`. A failing exit code means lint found errors; warnings alone still exit 0.
2. Build the list from the XML report, which holds the same issues as the HTML file in a form that is easy to parse. For example:

   ```python
   import xml.etree.ElementTree as ET
   for i in ET.parse("app/build/reports/lint-results-release.xml").getroot().iter("issue"):
       loc = i.find("location")
       print(i.get("severity"), i.get("id"), loc.get("file"), loc.get("line"), i.get("message"))
   ```

   Sort by severity (errors first), then by issue id, then by file. Show me the full numbered list, grouped by issue id, before changing anything. Say how many there are.
3. If there are no issues, say so and stop.

## 2. Fix each issue

Fix every issue. Prefer a real fix over a suppression. Read the surrounding code first; never fix from the message alone.

- **ObsoleteSdkInt**: delete the unneeded `SDK_INT` check (`minSdk` is in `app/build.gradle`) and any import that becomes unused.
- **InlinedApi**: add a correct SDK guard if one is missing; if the guard is correct but lint cannot follow it (for example it goes through a variable), add `@SuppressLint("InlinedApi")` on the smallest method with a comment saying why.
- **UseCompoundDrawables / UseCompatTextViewDrawableXml**: replace an icon plus text pair with one `TextView` using `app:drawableStartCompat` and `app:drawableTint`.
- **NotifyDataSetChanged**: use a specific change event or `DiffUtil` when that is correct; otherwise `@SuppressLint("NotifyDataSetChanged")` on the method that calls it, with a reason.
- **UnusedResources**: remove the resource from the default file **and every locale** (`values-*`), and check `src/test` (for example `StringTranslationsTest`) for references. Confirm nothing in `src/` still uses it.
- **Strings**: any string edit goes into all 12 locales (`ar de es fr hi it ja ko pt ru zh-rCN zh-rTW`); escape `&` as `&amp;` and `'` as `\'`.
- **PluralsCandidate / Typos** on text that is intentionally as written (for example the codec name AV1): add `tools:ignore` on that element, and `xmlns:tools` on the file's root if it is missing.
- **Overdraw**: root backgrounds on the stacked, hide/show tab fragments are deliberate (each needs to be opaque), and ripple backgrounds on list rows are needed. Use `tools:ignore="Overdraw"` for those, and remove a truly redundant background only if the layout does not rely on it.
- **VisibleForTests, EmptySuperCall, SmallSp, DisableBaselineAlignment, NewerVersionAvailable**: fix directly (suppress `VisibleForTests` only when the code is correct and the member is legitimately shared).
- **UnsafeIntentLaunch** on `ShareHandlerActivity`: `@SuppressLint` and `tools:ignore` are not honored for this check. It is already ignored for that one file in `app/lint.xml`; do not widen that ignore, and fix any new occurrence elsewhere properly.
- If lint still reports an issue after a source-level suppression, do not keep stacking annotations; scope an `<ignore path="...">` for that one issue and file in `app/lint.xml` with a comment explaining why.
- A suppression must always carry a short comment explaining why the code is safe. Never turn a check off globally or lower its severity to get a clean report.

Do not touch files I have uncommitted changes in unless an issue is in that file; check `git status` first and tell me if one is. Keep every change behavior-preserving, and call out anything where lint points at a possible real bug instead of hiding it (for example, a foreground service type or API guard that may be wrong at runtime).

## 3. Verify

1. Re-run lint and re-list the issues from the new XML report. The goal is **0 issues**. If any remain, fix them and repeat; if one cannot be fixed without a behavior change, stop and ask me.
2. Run `./gradlew :app:testDebugUnitTest -q` and confirm no test failed (check `app/build/test-results/testDebugUnitTest/*.xml` for `failures="[1-9]"` or `errors="[1-9]"`). Fix anything the lint changes broke, including tests that reference removed strings or changed code.
3. If any layout changed, install the debug build on the Pixel_10_Pro emulator (set `ANDROID_SERIAL` to its serial so a physical device is never used), open the affected screen, take a screenshot and look at it. Skip this step if no layout or visual resource changed.

## 4. Report

Finish with a short report:

- how many issues there were and how many remain (should be 0),
- the fixes grouped by issue id, one line each, separating real fixes from intentional suppressions (with the reason),
- anything that deserves a human decision or device testing,
- test results, and any uncommitted changes that were not mine.

Only if I asked to commit: stage just the files this task changed (leave my other uncommitted files out), and commit with a message that lists the fixes. Never push.
