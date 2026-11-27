#!/usr/bin/env bash
# TEMPORARY: reproduces (and checks the fix for) the drawer's first app not opening on tap. Removed before merge.
set -u
out=devtest-out
mkdir -p "$out"
pkg=io.github.nullangst.corkboard
step() { printf '\n=== %s\n' "$1" | tee -a "$out/steps.txt"; }
note() { printf '%s\n' "$1" | tee -a "$out/steps.txt"; }
shot() { adb exec-out screencap -p > "$out/$1.png"; }
ui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$out/ui-$1.xml" >/dev/null 2>&1; }
# Prints "label cx cy left top right bottom" for the drawer's app cells, top-left first.
cells() {
  python3 - "$out/ui-$1.xml" "$pkg" <<'PY'
import sys, re
try:
    x = open(sys.argv[1], encoding="utf-8").read()
except OSError:
    sys.exit(0)
rows = []
for n in re.findall(r'<node [^>]*>', x):
    cl = (re.search(r' class="([^"]*)"', n) or [None, ""])[1]
    pk = (re.search(r' package="([^"]*)"', n) or [None, ""])[1]
    cd = (re.search(r'content-desc="([^"]*)"', n) or [None, ""])[1]
    if pk != sys.argv[2] or cl != "android.view.View" or not cd:
        continue
    b = list(map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()))
    rows.append((b[1], b[0], cd, b))
rows.sort()
for top, left, cd, b in rows[:7]:
    print(cd.replace(" ", "_"), (b[0] + b[2]) // 2, (b[1] + b[3]) // 2, *b)
PY
}
topact() { adb shell dumpsys activity activities | tr -d '\r' | grep -m1 topResumedActivity | sed 's/.*{[^ ]* [^ ]* //; s/ .*//'; }
home() { adb shell input keyevent KEYCODE_HOME; sleep "${1:-2}"; }
open_drawer() { home 2; adb shell input swipe 540 1900 540 500 250; sleep 2; }
tap_at() {
  adb logcat -c
  adb shell input tap "$2" "$3"
  sleep 3
  note "$1: tap $2,$3 -> top activity $(topact)"
  shot "$1"
  adb logcat -d -s CBDBG:D > "$out/$1-log.txt"
}
press_at() {
  adb logcat -c
  adb shell input motionevent DOWN "$2" "$3"
  sleep 0.15
  adb shell input motionevent UP "$2" "$3"
  sleep 3
  note "$1: press $2,$3 -> top activity $(topact)"
  shot "$1"
  adb logcat -d -s CBDBG:D > "$out/$1-log.txt"
}

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true
adb shell cmd role add-role-holder --user 0 android.app.role.HOME "$pkg" || true
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | tr -d '\r' > "$out/activities.txt"
python3 - "$out/activities.txt" > /tmp/layout.json <<'PY'
import sys, re, json
allc = []
for line in open(sys.argv[1]):
    m = re.match(r'^\s*([\w.]+)/([\w.$]+)\s*$', line)
    if m:
        allc.append(f"{m.group(1)}/{m.group(2)}#0")
print(json.dumps({"version": 1, "nextId": 50, "settings": {}, "pages": [[]], "dock": [], "seen": allc, "hidden": [], "icons": {}}))
PY
adb push /tmp/layout.json /data/local/tmp/l.json >/dev/null
adb shell "chmod 644 /data/local/tmp/l.json"
adb shell "cat /data/local/tmp/l.json | run-as $pkg sh -c 'mkdir -p files && cat > files/layout.json'"
adb shell am force-stop "$pkg"
home 20
home 3
shot 00-home

step "1 gesture navigation"
open_drawer
ui 01
cells 01 | tee "$out/01-cells.txt"
adb shell dumpsys activity top | tr -d '\r' > "$out/01-viewtree.txt"
set -- $(sed -n 1p "$out/01-cells.txt")
c1x=${2:-0}; c1y=${3:-0}; c1l=${4:-0}; c1t=${5:-0}
set -- $(sed -n 2p "$out/01-cells.txt")
c2x=${2:-0}; c2y=${3:-0}
note "first cell $c1x,$c1y (left $c1l top $c1t), second cell $c2x,$c2y"
note "first app visible to accessibility (left edge 0): $([ "$c1l" = 0 ] && echo yes || echo no)"
tap_at 01a-first "$c1x" "$c1y"
open_drawer
tap_at 01b-second "$c2x" "$c2y"
open_drawer
press_at 01c-first-press "$c1x" "$c1y"
open_drawer
tap_at 01d-first-again "$c1x" "$c1y"
open_drawer
tap_at 01e-first-right "$(( c1x + 40 ))" "$(( c1y + 20 ))"
open_drawer
tap_at 01f-fixed-top-left 127 411

step "2 search"
open_drawer
ui 02a
sxy=$(python3 - "$out/ui-02a.xml" <<'PY'
import re, sys
x = open(sys.argv[1], encoding="utf-8").read()
for n in re.findall(r'<node [^>]*>', x):
    if 'class="android.widget.EditText"' in n:
        b = list(map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()))
        print((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
        break
PY
)
note "search box at ${sxy:-NOT FOUND}"
[ -n "$sxy" ] && adb shell input tap $sxy
sleep 2
adb shell input text c
sleep 2
ui 02b
cells 02b | tee "$out/02-cells.txt"
shot 02b-search
set -- $(sed -n 1p "$out/02-cells.txt")
tap_at 02c-search-first "${2:-0}" "${3:-0}"

step "3 three-button navigation"
adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton || true
sleep 3
open_drawer
ui 03
cells 03 | tee "$out/03-cells.txt"
set -- $(sed -n 1p "$out/03-cells.txt")
tap_at 03a-first-3button "${2:-0}" "${3:-0}"

adb logcat -d -b crash > "$out/crash.txt"
