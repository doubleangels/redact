#!/usr/bin/env bash
# Dev tool: capture light and dark screenshots of the Clean, Scan and Convert tabs on an Android
# emulator, using the media in icons/sample. Output: build/screenshots/1.png .. 6.png, grouped by
# screen, light then dark: 1/2 Clean, 3/4 Scan, 5/6 Convert.
#
# Requires: adb, python (python3, python or py -3), emulator unlocked. It builds and installs the current
# debug app on the emulator first (./gradlew installDebug); set SKIP_INSTALL=1 to reuse what is installed.
# Windows: run it from Git Bash (adb, emulator and python are found via PATH or the SDK in local.properties).
# Android 17 (API 37) emulator only: physical devices and emulators on any other API level are never used
# or touched (an inherited ANDROID_SERIAL is ignored). Uses a running API 37 emulator if there is one (and
# leaves it running); otherwise boots the AVD named by $AVD (default redact_shots, must be API 37) in a
# visible window and shuts it down on exit.
# Emulator setup: sdkmanager "system-images;android-37;google_apis;x86_64", then avdmanager create avd
# -n redact_shots -d pixel_7_pro -k <that image>.
# Changes it makes on the emulator are undone on exit: system dark mode, demo-mode status
# bar, and the pushed files in /sdcard/Pictures/redact-samples.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.doubleangels.redact
SAMPLE_DIR=/sdcard/Pictures/redact-samples
OUT="$ROOT/build/screenshots"
PICKER_SEARCH="redact-sample"   # every pushed file is prefixed with this; the picker search finds them

AVD="${AVD:-redact_shots}"
REQUIRED_API=37
EMU_PORT=5570
EMU_PID=""
unset ANDROID_SERIAL   # never inherit a physical device from the environment

# Windows (Git Bash): stop MSYS rewriting device paths like /sdcard/... into C:/Program Files/Git/sdcard/...
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

# Local paths handed to adb.exe must be Windows-style on Windows; a no-op elsewhere.
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

# Prints the Android SDK dir: ANDROID_SDK_ROOT, ANDROID_HOME, else sdk.dir from local.properties.
sdk_dir() {
  local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -z "$sdk" && -f "$ROOT/local.properties" ]]; then
    # local.properties escapes Windows paths as C\:\\Users\\me\\...
    sdk=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tr -d '\r' | sed 's#\\:#:#g; s#\\\\#/#g')
  fi
  echo "$sdk"
}

# Prints the emulator binary: on PATH, else under the SDK.
find_emulator() {
  command -v emulator && return 0
  local sdk e
  sdk=$(sdk_dir)
  for e in "$sdk/emulator/emulator" "$sdk/emulator/emulator.exe"; do
    [[ -x "$e" ]] && { echo "$e"; return 0; }
  done
  return 1
}

# adb: on PATH, else the SDK's platform-tools (adb is often not on PATH on Windows).
if ! command -v adb >/dev/null 2>&1; then
  tools="$(sdk_dir)/platform-tools"
  if command -v cygpath >/dev/null 2>&1; then tools=$(cygpath -u "$tools"); fi   # "C:/..." would split PATH at the colon
  PATH="$tools:$PATH"
  command -v adb >/dev/null 2>&1 || { echo "adb not found; add platform-tools to PATH or set ANDROID_HOME" >&2; exit 1; }
fi

# Python: python3 may be a Microsoft Store stub on Windows, so use the first one that actually runs.
PY=""
for c in python3 python "py -3"; do
  if $c -c 'import sys' >/dev/null 2>&1; then PY=$c; break; fi
done
[[ -n "$PY" ]] || { echo "python not found" >&2; exit 1; }

emulator_api() { adb -s "$1" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r'; }

# Boots the AVD in a visible window on a fixed port and waits until Android has finished booting.
start_emulator() {
  local emu i
  emu=$(find_emulator) || { echo "No emulator running and no emulator binary found (set ANDROID_HOME)" >&2; exit 1; }
  "$emu" -list-avds | tr -d '\r' | grep -qx "$AVD" || {
    echo "No API $REQUIRED_API emulator running and no AVD named $AVD." >&2
    echo "Create one: sdkmanager \"system-images;android-$REQUIRED_API;google_apis;x86_64\", then" >&2
    echo "avdmanager create avd -n $AVD -d pixel_7_pro -k \"system-images;android-$REQUIRED_API;google_apis;x86_64\"" >&2
    exit 1
  }
  echo "No API $REQUIRED_API emulator running; starting $AVD"
  "$emu" -avd "$AVD" -port "$EMU_PORT" -no-snapshot-save -no-boot-anim >/dev/null 2>&1 &
  EMU_PID=$!
  ANDROID_SERIAL="emulator-$EMU_PORT"
  adb -s "$ANDROID_SERIAL" wait-for-device
  for ((i = 0; i < 150; i++)); do
    if [[ $(adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
      [[ $(emulator_api "$ANDROID_SERIAL") == "$REQUIRED_API" ]] || { echo "AVD $AVD is API $(emulator_api "$ANDROID_SERIAL"), need API $REQUIRED_API" >&2; exit 1; }
      return 0
    fi
    kill -0 "$EMU_PID" 2>/dev/null || { echo "Emulator exited during boot" >&2; exit 1; }
    sleep 2
  done
  echo "Emulator did not finish booting" >&2; exit 1
}

# Installed before the emulator boots so a failed or interrupted run still tears it down.
cleanup() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null 2>&1 || true
    [[ -z "${ORIGINAL_NIGHT:-}" ]] || adb shell cmd uimode night "$ORIGINAL_NIGHT" >/dev/null 2>&1 || true
    adb shell rm -rf "$SAMPLE_DIR" >/dev/null 2>&1 || true
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  fi
  if [[ -n "$EMU_PID" ]]; then   # only an emulator this script started is shut down
    echo "Shutting down emulator"
    adb -s "emulator-$EMU_PORT" emu kill >/dev/null 2>&1 || true
    for _ in {1..30}; do kill -0 "$EMU_PID" 2>/dev/null || break; sleep 1; done
    kill "$EMU_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

ANDROID_SERIAL=""
for serial in $(adb devices | tr -d '\r' | awk 'NR>1 && $2=="device" && $1~/^emulator-/ {print $1}'); do
  if [[ $(emulator_api "$serial") == "$REQUIRED_API" ]]; then ANDROID_SERIAL=$serial; break; fi
done
[[ -n "$ANDROID_SERIAL" ]] || start_emulator
export ANDROID_SERIAL
for _ in {1..60}; do adb shell ls /sdcard/Android >/dev/null 2>&1 && break; sleep 2; done   # storage mounts late after emulator boot
# Always screenshot the current code: ANDROID_SERIAL is the emulator, so Gradle installs only there.
if [[ -z "${SKIP_INSTALL:-}" ]]; then
  echo "Installing the debug app on $ANDROID_SERIAL"
  (cd "$ROOT" && ./gradlew -q installDebug) || { echo "installDebug failed" >&2; exit 1; }
fi
adb shell pm path "$PKG" >/dev/null || { echo "$PKG not installed" >&2; exit 1; }

ORIGINAL_NIGHT=$(adb shell cmd uimode night | awk '{print $NF}' | tr -d '\r')

# --- helpers -----------------------------------------------------------------------------

# Prints "cx cy" for the first on-screen node matching an attribute, e.g. find_node resource-id ':id/selectButton'
# (suffix match for resource-id, exact match for text). Exit 1 if absent.
find_node() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml | $PY -c '
import re, sys
attr, want = sys.argv[1], sys.argv[2]
for n in re.findall(r"<node[^>]*>", sys.stdin.read()):
    v = re.search(r" %s=\"([^\"]*)\"" % attr, n)
    if v and (v.group(1).endswith(want) if attr == "resource-id" else v.group(1) == want):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", n).group(1)))
        print((x1 + x2) // 2, (y1 + y2) // 2); sys.exit(0)
sys.exit(1)' "$1" "$2" | tr -d '\r'   # python prints CRLF on Windows
}

# Prints "cx cy" for every picker grid item, one per line, in on-screen order.
picker_items() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml | $PY -c '
import re, sys
for n in re.findall(r"<node[^>]*>", sys.stdin.read()):
    if "documentsui:id/item_root" in n:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", n).group(1)))
        if y2 - y1 > 400 and y2 <= 3000: print((x1 + x2) // 2, (y1 + y2) // 2 - 150)' | tr -d '\r'
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
# The picker's confirm button: text "Select" on newer pickers, "SELECT" (action_menu_select) on older ones.
tap_select() { tap_node resource-id ':id/action_menu_select' || tap_node text 'Select'; }

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
    tap_select
  elif ! tap_select; then
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
  adb push "$(winpath "$f")" "$SAMPLE_DIR/$n" >/dev/null 2>&1
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
