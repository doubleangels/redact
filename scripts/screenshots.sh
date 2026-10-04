#!/usr/bin/env bash
# Dev tool: capture light and dark screenshots of the Clean, Scan and Convert tabs on a connected
# device, using the media in icons/sample. Output: build/screenshots/1.png .. 6.png, grouped by
# screen, light then dark: 1/2 Clean, 3/4 Scan, 5/6 Convert.
#
# Requires: adb, python3, the debug app installed (./gradlew installDebug), device unlocked.
# Device: ANDROID_SERIAL if set, else a running emulator, else the first USB device.
# Emulator setup: sdkmanager "system-images;android-36-ext19;google_apis;x86_64", then avdmanager create avd
# -n redact_shots -d pixel_7_pro -k <that image>; boot with emulator -avd redact_shots -no-window.
# Changes it makes on the device are undone on exit: system dark mode, demo-mode status
# bar, and the pushed files in /sdcard/Pictures/redact-samples.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.doubleangels.redact
SAMPLE_DIR=/sdcard/Pictures/redact-samples
OUT="$ROOT/build/screenshots"
PICKER_SEARCH="redact-sample"   # every pushed file is prefixed with this; the picker search finds them

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  ANDROID_SERIAL=$(adb devices | awk 'NR>1 && $2=="device" && $1~/^emulator-/ {print $1; exit}')
  [[ -n "$ANDROID_SERIAL" ]] || ANDROID_SERIAL=$(adb devices | awk 'NR>1 && $2=="device" && $1!~/^adb-/ {print $1; exit}')
  [[ -n "$ANDROID_SERIAL" ]] || { echo "No device found" >&2; exit 1; }
  export ANDROID_SERIAL
fi
for _ in {1..60}; do adb shell ls /sdcard/Android >/dev/null 2>&1 && break; sleep 2; done   # storage mounts late after emulator boot
adb shell pm path "$PKG" >/dev/null || { echo "$PKG not installed; run ./gradlew installDebug" >&2; exit 1; }

ORIGINAL_NIGHT=$(adb shell cmd uimode night | awk '{print $NF}')

cleanup() {
  adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null 2>&1 || true
  adb shell cmd uimode night "$ORIGINAL_NIGHT" >/dev/null 2>&1 || true
  adb shell rm -rf "$SAMPLE_DIR" >/dev/null 2>&1 || true
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --- helpers -----------------------------------------------------------------------------

# Prints "cx cy" for the first on-screen node matching an attribute, e.g. find_node resource-id ':id/selectButton'
# (suffix match for resource-id, exact match for text). Exit 1 if absent.
find_node() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml | python3 -c '
import re, sys
attr, want = sys.argv[1], sys.argv[2]
for n in re.findall(r"<node[^>]*>", sys.stdin.read()):
    v = re.search(r" %s=\"([^\"]*)\"" % attr, n)
    if v and (v.group(1).endswith(want) if attr == "resource-id" else v.group(1) == want):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", n).group(1)))
        print((x1 + x2) // 2, (y1 + y2) // 2); sys.exit(0)
sys.exit(1)' "$1" "$2"
}

# Prints "cx cy" for every picker grid item, one per line, in on-screen order.
picker_items() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml | python3 -c '
import re, sys
for n in re.findall(r"<node[^>]*>", sys.stdin.read()):
    if "documentsui:id/item_root" in n:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", n).group(1)))
        if y2 - y1 > 400 and y2 <= 3000: print((x1 + x2) // 2, (y1 + y2) // 2 - 150)'
}

wait_for() {  # wait_for <attr> <value> [tries]
  local i
  for ((i = 0; i < ${3:-20}; i++)); do
    find_node "$1" "$2" >/dev/null && return 0
    sleep 0.5
  done
  echo "Timed out waiting for $1=$2" >&2; return 1
}

tap_node() { read -r x y < <(find_node "$1" "$2") && adb shell input tap "$x" "$y"; }

# Opens the picker from the current tab and selects the first N sample files.
pick_files() {
  local count=$1 items=() x y
  # Empty state shows its own button; the bottom bar one appears once files are loaded.
  wait_for resource-id 'electButton' 40
  tap_node resource-id ':id/selectButton' || tap_node resource-id ':id/emptyStateSelectButton'
  wait_for resource-id 'documentsui:id/toolbar' 90
  # Search is a collapsed icon on some pickers and an open bar on others.
  tap_node resource-id 'documentsui:id/option_menu_search' || tap_node resource-id 'documentsui:id/search_bar'
  wait_for resource-id 'documentsui:id/search_src_text' 10
  sleep 1
  adb shell input text "$PICKER_SEARCH"
  adb shell input keyevent KEYCODE_ENTER
  sleep 2.5
  mapfile -t items < <(picker_items)
  ((${#items[@]} >= count)) || { echo "Picker found only ${#items[@]} sample files" >&2; return 1; }
  read -r x y <<<"${items[0]}"
  adb shell input swipe "$x" "$y" "$x" "$y" 800      # long-press starts multi-select
  sleep 1
  if ((count > 1)); then
    for ((i = 1; i < count; i++)); do read -r x y <<<"${items[i]}"; adb shell input tap "$x" "$y"; done
    tap_node text 'Select'
  elif ! tap_node text 'Select'; then
    adb shell input tap "$x" "$y"
  fi
}

# Waits until the app is back in the foreground and no progress text ("Analyzing…") is on screen.
settle() {
  local i
  wait_for resource-id ':id/bottomNavigation' 40
  for ((i = 0; i < 60; i++)); do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb exec-out cat /sdcard/ui.xml | grep -q 'text="[^"]*…' || break
    sleep 0.5
  done
  sleep 1.5   # let thumbnails and animations finish
}

# shot <clean|scan|convert>: screens are numbered in pairs, light first.
shot() {
  local n
  case $1 in clean) n=1 ;; scan) n=3 ;; convert) n=5 ;; esac
  [[ $MODE == dark ]] && n=$((n + 1))
  adb exec-out screencap -p >"$OUT/$n.png"
  echo "  $n.png ($1, $MODE)"
}

go_tab() { tap_node resource-id ":id/navigation_$1"; sleep 1; }

# --- run ---------------------------------------------------------------------------------

mkdir -p "$OUT"
rm -f "$OUT"/[1-6].png
adb shell mkdir -p "$SAMPLE_DIR"
for f in "$ROOT"/icons/sample/*; do
  n="redact-sample-$(basename "$f")"
  adb push "$f" "$SAMPLE_DIR/$n" >/dev/null
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$SAMPLE_DIR/$n" >/dev/null
done

for p in POST_NOTIFICATIONS READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_VISUAL_USER_SELECTED ACCESS_MEDIA_LOCATION; do
  adb shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true
done

# Clean status bar: 12:00, full battery, no notification icons.
adb shell settings put global sysui_demo_allowed 1
for c in "enter" "clock -e hhmm 1200" "battery -e level 100 -e plugged false" "notifications -e visible false"; do
  adb shell am broadcast -a com.android.systemui.demo -e command $c >/dev/null
done

adb shell input keyevent KEYCODE_WAKEUP
for MODE in light dark; do
  echo "$MODE:"
  adb shell cmd uimode night "$([[ $MODE == dark ]] && echo yes || echo no)" >/dev/null
  adb shell am force-stop com.google.android.documentsui
  adb shell am force-stop "$PKG"
  adb shell input keyevent KEYCODE_HOME   # leave any picker/other task from a previous run
  sleep 1
  adb shell am start -n "$PKG/.MainActivity" >/dev/null
  wait_for resource-id ':id/bottomNavigation'
  sleep 1

  pick_files 4;  settle; shot clean
  go_tab scan;   pick_files 1; settle; shot scan
  go_tab convert; pick_files 3; settle; shot convert
done
echo "Done: $OUT"
