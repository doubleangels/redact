#!/usr/bin/env bash
# Dev tool: capture light and dark screenshots of the Clean, Scan and Convert tabs on an Android
# emulator, using the media in icons/sample. Clean and Convert select every sample file, Scan one.
# Output: build/screenshots/1.png .. 6.png, grouped by screen, light then dark: 1/2 Clean, 3/4 Scan,
# 5/6 Convert.
#
# Requires: adb, awk (gawk), emulator unlocked. It builds and installs the current debug app on the
# emulator first (./gradlew installDebug); set SKIP_INSTALL=1 to reuse what is installed.
# Windows: run it from Git Bash (adb and the emulator are found via PATH or the SDK in local.properties).
# Android 17 (API 37) emulator only: physical devices and emulators on any other API level are never used
# or touched (an inherited ANDROID_SERIAL is ignored). Uses a running API 37 emulator if there is one (and
# leaves it running); otherwise boots the AVD named by $AVD (default Pixel_10_Pro, must be API 37) in a
# visible window and shuts it down on exit.
# Emulator setup: sdkmanager "system-images;android-37;google_apis;x86_64", then avdmanager create avd
# -n Pixel_10_Pro -d pixel_7_pro -k <that image>.
# Changes it makes on the emulator are undone on exit: system dark mode, the monochrome (black and white)
# system theme, animation scales, the demo-mode status bar, and the pushed files in
# /sdcard/Pictures/redact-samples.
#
# Speed: a uiautomator dump costs ~2s however fast the emulator is, so dumps are kept to a minimum. Button
# positions are looked up once and cached, window focus (cheap) detects when the picker or the app is up,
# the picker's "select all" shortcut replaces tapping each file, and the dark pass flips the system theme
# under the running app instead of repeating every pick.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.doubleangels.redact
SAMPLE_DIR=/sdcard/Pictures/redact-samples
OUT="$ROOT/build/screenshots"
PICKER_SEARCH="redact-sample"   # every pushed file is prefixed with this; the picker search finds them
SAMPLE_COUNT=$(ls "$ROOT/icons/sample" | wc -l | tr -d ' ')

AVD="${AVD:-Pixel_10_Pro}"
REQUIRED_API=37
EMU_PORT=5570
EMU_PID=""
unset ANDROID_SERIAL   # never inherit a physical device from the environment

# Monochrome system theme (Settings > Wallpaper & style > Basic colors > black and white).
THEME_KEY=theme_customization_overlay_packages
MONO_THEME_BODY='"android.theme.customization.theme_style":"MONOCHROMATIC","android.theme.customization.color_source":"preset","android.theme.customization.seed_color_list":["333333"],"android.theme.customization.system_palette":"333333","android.theme.customization.accent_color":"333333"}'
ANIM_KEYS=(window_animation_scale transition_animation_scale animator_duration_scale)
ANIM_ORIG=()
THEME_CAPTURED=0
MONO_OVERLAY_ENABLED=0
ORIGINAL_THEME=""
ORIGINAL_NIGHT=""

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

# restore_setting <namespace> <key> <original>: "null" means the setting did not exist.
restore_setting() {
  if [[ "$3" == null ]]; then
    adb shell settings delete "$1" "$2" >/dev/null 2>&1 || true
  else
    adb shell "settings put $1 $2 '$3'" >/dev/null 2>&1 || true
  fi
}

# Installed before the emulator boots so a failed or interrupted run still tears it down.
cleanup() {
  local i
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null 2>&1 || true
    [[ -z "$ORIGINAL_NIGHT" ]] || adb shell cmd uimode night "$ORIGINAL_NIGHT" >/dev/null 2>&1 || true
    ((THEME_CAPTURED)) && restore_setting secure "$THEME_KEY" "$ORIGINAL_THEME"
    ((MONO_OVERLAY_ENABLED)) && adb shell cmd overlay disable --user 0 android:dynamic_0 >/dev/null 2>&1 || true
    for i in "${!ANIM_ORIG[@]}"; do restore_setting global "${ANIM_KEYS[i]}" "${ANIM_ORIG[i]}"; done
    adb shell rm -rf "$SAMPLE_DIR" >/dev/null 2>&1 || true
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  fi
  if [[ -n "$EMU_PID" ]]; then   # only an emulator this script started is shut down
    echo "Shutting down emulator"
    adb -s "emulator-$EMU_PORT" emu kill >/dev/null 2>&1 || true
    for _ in {1..30}; do kill -0 "$EMU_PID" 2>/dev/null || break; sleep 1; done
    kill "$EMU_PID" >/dev/null 2>&1 || true
  fi
  return 0
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

# --- UI helpers --------------------------------------------------------------------------
# A uiautomator dump takes ~2s, so the screen is dumped into $UI only when something must be read from it.

ui_dump() { UI=$(adb exec-out uiautomator dump /dev/stdout 2>/dev/null | tr -d '\r' || true); }

# Prints "cx cy" for the first node in $UI whose attribute matches (suffix match for resource-id, exact
# match for anything else, e.g. text). Exit 1 if absent.
node_center() {
  awk -v attr="$1" -v want="$2" '
    BEGIN { RS = "<node " }
    {
      if (!match($0, attr "=\"[^\"]*\"")) next
      v = substr($0, RSTART + length(attr) + 2, RLENGTH - length(attr) - 3)
      if (attr == "resource-id") ok = (length(v) >= length(want) && substr(v, length(v) - length(want) + 1) == want)
      else ok = (v == want)
      if (!ok || !match($0, /bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"/)) next
      b = substr($0, RSTART + 8, RLENGTH - 9)
      gsub(/[][,]/, " ", b); split(b, p, " ")
      print int((p[1] + p[3]) / 2), int((p[2] + p[4]) / 2); found = 1; exit
    }
    END { exit !found }' <<<"$UI"
}

# Prints "cx cy" for every picker grid item in $UI, one per line, in on-screen order.
picker_items() {
  awk 'BEGIN { RS = "<node " }
    index($0, "documentsui:id/item_root") && match($0, /bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"/) {
      b = substr($0, RSTART + 8, RLENGTH - 9)
      gsub(/[][,]/, " ", b); split(b, p, " ")
      if (p[4] - p[2] > 400 && p[4] <= 3000) print int((p[1] + p[3]) / 2), int((p[2] + p[4]) / 2) - 150
    }' <<<"$UI"
}

# Prints the file name shown under every picker grid item in $UI.
picker_titles() {
  awk 'BEGIN { RS = "<node " }
    index($0, "resource-id=\"android:id/title\"") && match($0, / text="[^"]*"/) { print substr($0, RSTART + 7, RLENGTH - 8) }' <<<"$UI"
}

# wait_for <attr> <value> [tries]: dumps until the node is on screen, leaving that dump in $UI.
wait_for() {
  local i
  for ((i = 0; i < ${3:-20}; i++)); do
    ui_dump
    if node_center "$1" "$2" >/dev/null; then return 0; fi
  done
  echo "Timed out waiting for $1=$2" >&2; return 1
}

# wait_focus <substring> [tries]: waits (cheaply, no dump) until the focused window's name contains it.
wait_focus() {
  local i
  for ((i = 0; i < ${2:-60}; i++)); do
    if [[ $(adb shell "dumpsys window | grep mCurrentFocus" | tr -d '\r') == *"$1"* ]]; then return 0; fi
    sleep 0.25
  done
  echo "Timed out waiting for focus on $1" >&2; return 1
}

# Position cache: screen geometry is fixed, so each button is located once and tapped by coordinates after.
declare -A POS=()
cache_pos() { local p; p=$(node_center "$2" "$3") || return 1; POS[$1]=$p; }   # cache_pos <key> <attr> <want> (from $UI)
# ensure_pos <key> <attr> <want> [<attr> <want> ...]: caches the first alternative that is on screen,
# dumping until one is.
ensure_pos() {
  local key=$1 i
  shift
  [[ -z "${POS[$key]:-}" ]] || return 0
  for ((i = 0; i < 30; i++)); do
    ui_dump
    local args=("$@")
    while ((${#args[@]} >= 2)); do
      if cache_pos "$key" "${args[0]}" "${args[1]}"; then return 0; fi
      args=("${args[@]:2}")
    done
  done
  echo "Timed out finding $key" >&2; return 1
}
tap_pos() { local x y; read -r x y <<<"${POS[$1]}"; adb shell input tap "$x" "$y"; }

# Waits until the app is back in the foreground, loaded, and showing no progress text ("Analyzing…").
settle() {
  local i
  for ((i = 0; i < 60; i++)); do
    ui_dump
    if node_center resource-id ':id/bottomNavigation' >/dev/null && ! grep -q 'text="[^"]*…' <<<"$UI"; then
      sleep 0.6   # let thumbnails finish
      return 0
    fi
  done
  echo "Timed out waiting for the app to settle" >&2; return 1
}

TAB=clean
SLOW=0   # 1 = retry mode: re-discover positions and wait on the screen instead of on timers

# One attempt at opening the picker from the current (empty) tab and selecting <count> sample files.
# Returns 1 if the selection did not take.
pick_once() {
  local count=$1 x y titles i items=()
  ensure_pos "$TAB:empty" resource-id ':id/emptyStateSelectButton' resource-id ':id/selectButton' || return 1
  tap_pos "$TAB:empty"
  wait_focus documentsui 60 || return 1

  # Search for the sample files (the picker also lists everything else on the device).
  ensure_pos picker:search resource-id 'documentsui:id/option_menu_search' resource-id 'documentsui:id/search_bar' || return 1
  if ((SLOW)); then sleep 0.8; else sleep 0.6; fi
  tap_pos picker:search
  if ((SLOW)); then
    wait_for resource-id 'documentsui:id/search_src_text' 10 || return 1
    sleep 0.6
  else
    sleep 0.7
  fi
  adb shell input text "$PICKER_SEARCH"
  adb shell input keyevent KEYCODE_ENTER

  # Find the first result; until the list is filtered it shows other files, so wait for ours only.
  if ((SLOW)) || [[ -z "${POS[picker:item1]:-}" ]]; then
    for ((i = 0; i < 20; i++)); do
      ui_dump
      titles=$(picker_titles)
      if [[ -n "$titles" ]] && ! grep -qv "^$PICKER_SEARCH" <<<"$titles"; then break; fi
    done
    mapfile -t items < <(picker_items)
    ((${#items[@]} > 0)) || return 1
    POS[picker:item1]=${items[0]}
  else
    sleep 1.3
  fi
  read -r x y <<<"${POS[picker:item1]}"
  adb shell input swipe "$x" "$y" "$x" "$y" 700   # long-press starts multi-select
  sleep 0.6

  if ((count > 1)); then
    adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A   # select all search results
    sleep 0.5
    ui_dump
    grep -q "text=\"$count selected\"" <<<"$UI" || { echo "  picker did not report $count selected" >&2; return 1; }
    cache_pos picker:confirm resource-id ':id/action_menu_select' || cache_pos picker:confirm text 'Select' || return 1
    tap_pos picker:confirm
  else
    # Single pick: confirm the long-press selection if the picker offers it, else tap the file.
    if [[ -z "${POS[picker:confirm]:-}" ]]; then
      ui_dump
      cache_pos picker:confirm resource-id ':id/action_menu_select' || cache_pos picker:confirm text 'Select' || true
    fi
    if [[ -n "${POS[picker:confirm]:-}" ]]; then tap_pos picker:confirm; else adb shell input tap "$x" "$y"; fi
  fi

  wait_focus "$PKG" 80 || return 1
  settle || return 1
  # The tab left its empty state iff the selection took.
  if node_center resource-id ':id/emptyStateSelectButton' >/dev/null; then return 1; fi
  return 0
}

# pick_files <count|all>: selects that many sample files on the current tab, retrying once the slow way.
pick_files() {
  local count=$1
  [[ "$count" != all ]] || count=$SAMPLE_COUNT
  if pick_once "$count"; then return 0; fi
  echo "  selection did not take; retrying with full waits" >&2
  adb shell am force-stop com.google.android.documentsui
  unset 'POS[picker:search]' 'POS[picker:item1]' 'POS[picker:confirm]'
  wait_focus "$PKG" 20 || true
  SLOW=1
  pick_once "$count" || { echo "Could not select $count sample files" >&2; return 1; }
  SLOW=0
  return 0
}

# shot <clean|scan|convert>: screens are numbered in pairs, light first.
shot() {
  local n
  case $1 in clean) n=1 ;; scan) n=3 ;; convert) n=5 ;; esac
  [[ "$MODE" != dark ]] || n=$((n + 1))
  adb exec-out screencap -p >"$OUT/$n.png"
  echo "  $n.png ($1, $MODE)"
}

go_tab() {
  TAB=$1
  ensure_pos "nav:$1" resource-id ":id/navigation_$1"
  tap_pos "nav:$1"
  sleep 0.5
}

# --- run ---------------------------------------------------------------------------------

mkdir -p "$OUT"
rm -f "$OUT"/[1-6].png

# Push every sample file (prefixed so the picker search finds them) in one adb call, then have the
# media scanner index them in one shell.
stage=$(mktemp -d)
for f in "$ROOT"/icons/sample/*; do cp "$f" "$stage/redact-sample-$(basename "$f")"; done
adb shell mkdir -p "$SAMPLE_DIR"
adb push "$(winpath "$stage")/." "$SAMPLE_DIR/" >/dev/null 2>&1
rm -rf "$stage"
adb shell 'for f in '"$SAMPLE_DIR"'/*; do am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$f >/dev/null; done'

adb shell 'for p in POST_NOTIFICATIONS READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_VISUAL_USER_SELECTED ACCESS_MEDIA_LOCATION; do pm grant '"$PKG"' android.permission.$p 2>/dev/null; done' || true

# Clean status bar: 12:00, full battery, no notification icons.
adb shell "settings put global sysui_demo_allowed 1; for c in 'enter' 'clock -e hhmm 1200' 'battery -e level 100 -e plugged false' 'notifications -e visible false'; do am broadcast -a com.android.systemui.demo -e command \$c >/dev/null; done"

# No animations: nothing to wait out between taps, and no mid-transition screenshots.
for k in "${ANIM_KEYS[@]}"; do
  ANIM_ORIG+=("$(adb shell settings get global "$k" | tr -d '\r')")
  adb shell settings put global "$k" 0
done

# Black and white system theme instead of the default blue.
ORIGINAL_THEME=$(adb shell settings get secure "$THEME_KEY" | tr -d '\r')
THEME_CAPTURED=1
# Always written, with a fresh timestamp: a stored value can say MONOCHROMATIC while the colours in effect
# are still the old ones, and SystemUI only re-applies the overlays when the setting actually changes.
adb shell "settings put secure $THEME_KEY '{\"_applied_timestamp\":$(date +%s%3N),$MONO_THEME_BODY'"
sleep 4   # SystemUI re-applies the colour overlays asynchronously
# SystemUI registers the generated palette as the fabricated overlay android:dynamic_0, but on some emulator
# images it leaves it disabled, so the app keeps the old colours. Enable it explicitly.
if adb shell cmd overlay list --user 0 | grep -q 'android:dynamic_0'; then
  adb shell cmd overlay enable --user 0 android:dynamic_0 >/dev/null
  MONO_OVERLAY_ENABLED=1
  sleep 2
fi

ORIGINAL_NIGHT=$(adb shell cmd uimode night | awk '{print $NF}' | tr -d '\r')
adb shell input keyevent KEYCODE_WAKEUP

echo "light:"
MODE=light
adb shell cmd uimode night no >/dev/null
adb shell am force-stop com.google.android.documentsui
adb shell am force-stop "$PKG"
adb shell input keyevent KEYCODE_HOME   # leave any picker/other task from a previous run
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
wait_for resource-id ':id/bottomNavigation' 30
for t in clean scan convert; do cache_pos "nav:$t" resource-id ":id/navigation_$t" || true; done

pick_files all; shot clean
go_tab scan;    pick_files 1;   shot scan
go_tab convert; pick_files all; shot convert

# Dark: flip the system theme under the running app. The picks normally survive the activity
# recreation; if a tab fell back to its empty state it is picked again.
echo "dark:"
MODE=dark
adb shell cmd uimode night yes >/dev/null
sleep 1
for t in convert scan clean; do
  go_tab "$t"
  settle
  if node_center resource-id ':id/emptyStateSelectButton' >/dev/null; then
    if [[ "$t" == scan ]]; then pick_files 1; else pick_files all; fi
  fi
  shot "$t"
done
echo "Done: $OUT (${SECONDS}s)"
