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
# ---- README screenshots: seed a layout, clean status bar, capture ----
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | tr -d '\r' > "$out/activities.txt"
python3 - "$out/activities.txt" > /tmp/layout.json <<'PY'
import sys, re, json
comps = {}
allc = []
for line in open(sys.argv[1]):
    m = re.match(r'^\s*([\w.]+)/([\w.$]+)\s*$', line)
    if m:
        pkg, cls = m.groups()
        comps.setdefault(pkg, f"{pkg}/{cls}")
        allc.append(f"{pkg}/{cls}")
def c(pkg): return comps.get(pkg)
nid = [0]
def app(pkg, x, y):
    if not c(pkg): return None
    nid[0] += 1
    return {"id": nid[0], "type": "app", "x": x, "y": y, "w": 1, "h": 1, "component": c(pkg), "user": 0}
page = [a for a in [
    app("com.google.android.calendar", 0, 3), app("com.google.android.deskclock", 1, 3),
    app("com.google.android.youtube", 2, 3), app("com.google.android.apps.youtube.music", 3, 3),
    app("com.android.settings", 0, 4), app("com.google.android.documentsui", 1, 4),
    app("com.google.android.contacts", 2, 4),
] if a]
folder_apps = [{"component": c(p), "user": 0} for p in ["com.google.android.apps.docs", "com.google.android.gm",
               "com.google.android.apps.photos", "com.google.android.apps.maps"] if c(p)]
nid[0] += 1
page.append({"id": nid[0], "type": "folder", "x": 3, "y": 4, "w": 1, "h": 1, "name": "Google apps", "apps": folder_apps})
dock = [a for a in [app("com.google.android.dialer", 0, 0), app("com.google.android.apps.messaging", 1, 0),
                    app("com.android.chrome", 2, 0), app("com.android.camera2", 3, 0) or app("com.google.android.GoogleCamera", 3, 0)] if a]
seen = [f"{v}#0" for v in allc]
print(json.dumps({"version": 1, "nextId": nid[0] + 10, "settings": {"shape": "system"}, "pages": [page], "dock": dock, "seen": seen, "hidden": [], "icons": {}}))
PY
cp /tmp/layout.json "$out/seeded-layout.json"
adb shell am force-stop "$pkg"
adb push /tmp/layout.json /data/local/tmp/l.json >/dev/null
adb shell "chmod 644 /data/local/tmp/l.json; cat /data/local/tmp/l.json | run-as $pkg sh -c 'mkdir -p files && cat > files/layout.json'"
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1000 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
adb shell input keyevent KEYCODE_HOME
sleep 6
adb shell input keyevent KEYCODE_HOME
sleep 3
shot 10-seeded 1
# Drag-to-remove: lift YouTube, hold over the bar, screenshot while held, then drop on Remove
read -r yx yy < <(python3 - "$out/10-seeded.xml" <<'PY'
import sys, re, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter('node'):
    if n.attrib.get('content-desc') == 'YouTube':
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.attrib['bounds'])); print((x1+x2)//2, (y1+y2)//2); break
PY
)
printf 'youtube at %s,%s\n' "$yx" "$yy" >> "$out/info.txt"
adb shell input motionevent DOWN "$yx" "$yy"; sleep 1.3
for i in 1 2 3 4 5 6 7 8; do adb shell input motionevent MOVE $(( yx + (cx - yx) * i / 8 )) $(( yy + (150 - yy) * i / 8 )); sleep 0.08; done
sleep 1
adb exec-out screencap -p > "$out/11-dropbar-held.png"
adb shell input motionevent UP "$cx" 150
shot 12-after-remove 1.5
# Rotation lock: ask the system for landscape
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
shot 13-rotation-locked 3
adb shell settings put system user_rotation 0
sleep 2
# Settings: long-press empty space, tap Launcher settings, scroll through
adb shell input swipe "$cx" $((h * 45 / 100)) "$cx" $((h * 45 / 100)) 1500
shot 14-menu 1
read -r sx sy < <(python3 - "$out/14-menu.xml" <<'PY'
import sys, re, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter('node'):
    if n.attrib.get('text') == 'Launcher settings':
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.attrib['bounds'])); print((x1+x2)//2, (y1+y2)//2); break
PY
)
adb shell input tap "$sx" "$sy"
shot 15-settings-top 2
for n in 16 17 18 19; do
  adb shell input swipe "$cx" $((h * 80 / 100)) "$cx" $((h * 25 / 100)) 400
  shot "$n-settings-scroll" 1.2
done
adb logcat -d -b crash > "$out/crash.txt"
adb logcat -d > "$out/logcat.txt"
