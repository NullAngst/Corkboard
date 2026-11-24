#!/usr/bin/env bash
# Installs Corkboard, makes it home, pokes it with gestures, saves screenshots, UI dumps and logcat.
set -u
out=devtest-out
mkdir -p "$out"
pkg=io.github.nullangst.corkboard
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true
adb shell cmd role add-role-holder --user 0 android.app.role.HOME "$pkg" || true
adb logcat -c
adb shell input keyevent KEYCODE_HOME
sleep 6
size=$(adb shell wm size | tr -d '\r' | awk '{print $3}')
w=${size%x*}; h=${size#*x}
cx=$((w / 2)); cy=$((h / 2))
shot() { sleep "${2:-1.5}"; adb exec-out screencap -p > "$out/$1.png"; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$out/$1.xml" >/dev/null 2>&1; }
# Center of the first node in our package with a content-desc (an icon), from a UI dump.
first_icon() {
  python3 - "$1" <<'PY'
import sys, re, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter('node'):
    a = n.attrib
    if a.get('package') == 'io.github.nullangst.corkboard' and a.get('content-desc') and a.get('class') == 'android.view.View':
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', a['bounds']))
        print((x1 + x2) // 2, (y1 + y2) // 2, a['content-desc'].replace(' ', '_'))
        break
PY
}
hold_drag() { # x1 y1 x2 y2
  adb shell input motionevent DOWN "$1" "$2"; sleep 1.3
  for i in 1 2 3 4 5 6 7 8; do
    adb shell input motionevent MOVE $(( $1 + ($3 - $1) * i / 8 )) $(( $2 + ($4 - $2) * i / 8 )); sleep 0.08
  done
  sleep 0.8; adb shell input motionevent UP "$3" "$4"
}
shot 01-home
adb shell input swipe "$cx" "$cy" "$cx" "$cy" 1500
shot 02-longpress-menu
adb shell input keyevent KEYCODE_BACK
adb shell input swipe "$cx" $((h * 70 / 100)) "$cx" $((h * 25 / 100)) 500
shot 03-drawer 1.5
read -r ix iy name < <(first_icon "$out/03-drawer.xml")
printf 'first drawer icon: %s at %s,%s\n' "$name" "$ix" "$iy" > "$out/info.txt"
hold_drag "$ix" "$iy" "$cx" $((h * 35 / 100))
shot 04-placed 2
read -r px py pname < <(first_icon "$out/04-placed.xml")
printf 'placed icon: %s at %s,%s\n' "$pname" "$px" "$py" >> "$out/info.txt"
adb shell input swipe "$px" "$py" "$px" "$py" 1200
shot 05-app-menu
adb shell input keyevent KEYCODE_BACK
adb shell input swipe "$cx" $((h * 40 / 100)) "$cx" $((h * 75 / 100)) 250
shot 06-swipe-down 1.5
adb shell input keyevent KEYCODE_HOME
sleep 1
# Drawer long-press without moving should show the menu over the drawer
adb shell input swipe "$cx" $((h * 70 / 100)) "$cx" $((h * 25 / 100)) 250
sleep 1.5
adb shell input swipe "$ix" "$iy" "$ix" "$iy" 1300
shot 07-drawer-menu
adb shell input keyevent KEYCODE_HOME
shot 08-home-final 2
adb logcat -d > "$out/logcat.txt"
adb logcat -d -s CorkTouch AndroidRuntime > "$out/cork.txt"
adb logcat -d -b crash > "$out/crash.txt"
