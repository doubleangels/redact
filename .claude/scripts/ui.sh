#!/bin/bash
# Screen helper for driving Redact on the emulator from Git Bash. Set ANDROID_SERIAL first.
#
#   ui.sh dump [png]        print visible text/clickable nodes as "[x1,y1][x2,y2] id=… t='…' d='…'",
#                           saving a screenshot to <png> if given
#   ui.sh tap <text|id>     tap the centre of the first node whose text, content-desc or resource id matches
#   ui.sh adb <args…>       run adb with Git Bash path conversion turned off
#
# Path conversion is off for every adb call: Git Bash otherwise rewrites /sdcard/... into a Windows path.
set -euo pipefail
export MSYS_NO_PATHCONV=1
ADB="${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe"
[[ -x "$ADB" ]] || ADB=adb

nodes() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" exec-out cat /sdcard/ui.xml | python -c '
import re, sys
for m in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    t = m.group(0)
    g = lambda k: (re.search(k + r"=\"([^\"]*)\"", t) or [None, ""])[1]
    text, desc, rid = g("text"), g("content-desc"), g("resource-id").split("/")[-1]
    if text or desc or g("clickable") == "true" or g("checkable") == "true":
        chk = " chk=" + g("checked") if g("checkable") == "true" else ""
        b = g("bounds"); print(f"{b} id={rid} t={text!r} d={desc!r}{chk}")
'
}

case "${1:-}" in
  dump)
    [[ -n "${2:-}" ]] && "$ADB" exec-out screencap -p > "$2"
    nodes ;;
  tap)
    line=$(nodes | grep -F -m1 -e "t='$2'" -e "d='$2'" -e "id=$2 " || true)
    [[ -n "$line" ]] || { echo "no node matching '$2'" >&2; exit 1; }
    read -r x1 y1 x2 y2 < <(sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \2 \3 \4/' <<<"$line")
    "$ADB" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )) ;;
  adb)
    shift; exec "$ADB" "$@" ;;
  *)
    sed -n '2,8p' "$0"; exit 1 ;;
esac
