#!/usr/bin/env bash
# TEMPORARY emulator checks for icon rendering, themed icons, drawer button, widget search, haptics.
set -u
out=devtest-out
mkdir -p "$out"
pkg=io.github.nullangst.corkboard
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true
adb shell cmd role add-role-holder --user 0 android.app.role.HOME "$pkg" || true
size=$(adb shell wm size | tr -d '\r' | awk '{print $3}')
w=${size%x*}; h=${size#*x}
cx=$((w / 2))
shot() { sleep "${2:-1.5}"; adb exec-out screencap -p > "$out/$1.png"; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$out/$1.xml" >/dev/null 2>&1; }
find_desc() { # xml, content-desc or text -> "x y"
  python3 - "$1" "$2" <<'PY'
import sys, re, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter('node'):
    a = n.attrib
    if a.get('content-desc') == sys.argv[2] or a.get('text') == sys.argv[2]:
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', a['bounds'])); print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
}
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | tr -d '\r' > "$out/activities.txt"
seed() { # themed 0|1
  python3 - "$out/activities.txt" "$1" > /tmp/layout.json <<'PY'
import sys, re, json
comps, allc = {}, []
for line in open(sys.argv[1]):
    m = re.match(r'^\s*([\w.]+)/([\w.$]+)\s*$', line)
    if m:
        p, c = m.groups(); comps.setdefault(p, f"{p}/{c}"); allc.append(f"{p}/{c}")
themed = sys.argv[2] == "1"
nid = [0]
def app(pkg, x, y):
    if pkg not in comps: return None
    nid[0] += 1
    return {"id": nid[0], "type": "app", "x": x, "y": y, "w": 1, "h": 1, "component": comps[pkg], "user": 0}
page = [a for a in [
    app("com.google.android.calendar", 0, 2), app("com.google.android.deskclock", 1, 2),
    app("com.google.android.youtube", 2, 2), app("com.android.chrome", 3, 2),
    app("com.android.settings", 0, 3), app("com.google.android.documentsui", 1, 3),
    app("com.google.android.apps.youtube.music", 2, 3), app("com.google.android.contacts", 3, 3),
    app("com.google.android.apps.maps", 0, 4), app("com.google.android.gm", 1, 4),
] if a]
nid[0] += 1
dock = [a for a in [app("com.google.android.dialer", 0, 0), app("com.google.android.apps.messaging", 1, 0),
                    {"id": 99, "type": "drawer", "x": 2, "y": 0, "w": 1, "h": 1},
                    app("com.android.camera2", 3, 0)] if a]
def k(p): return f"app:{comps[p]}#0"
icons = {
    k("com.google.android.calendar"): {"source": "custom", "file": "icons/test-a.png"},
    k("com.google.android.deskclock"): {"source": "custom", "file": "icons/test-b.png"},
    k("com.google.android.youtube"): {"source": "custom", "file": "icons/test-c.png"},
    k("com.android.chrome"): {"background": "black"},
}
if themed:
    icons[k("com.google.android.apps.youtube.music")] = {"mono": "image", "monoFile": "icons/test-d.png"}
print(json.dumps({"version": 1, "nextId": 200, "settings": {"shape": "system", "drawerButton": True, "themedIcons": themed, "themedGenerate": True},
                  "pages": [page], "dock": dock, "seen": [f"{c}#0" for c in allc], "hidden": [], "icons": icons}))
PY
  adb shell am force-stop "$pkg"
  adb push /tmp/layout.json /data/local/tmp/l.json >/dev/null
  for f in a:legacy-square b:legacy-circle c:legacy-logo d:mono-logo; do
    adb push ".github/devtest/${f#*:}.png" "/data/local/tmp/test-${f%%:*}.png" >/dev/null
  done
  adb shell "chmod 644 /data/local/tmp/*.png /data/local/tmp/l.json; run-as $pkg sh -c 'mkdir -p files/icons && rm -rf cache/icons'"
  for x in a b c d; do adb shell "cat /data/local/tmp/test-$x.png | run-as $pkg sh -c 'cat > files/icons/test-$x.png'"; done
  adb shell "cat /data/local/tmp/l.json | run-as $pkg sh -c 'cat > files/layout.json'"
  adb shell input keyevent KEYCODE_HOME
  sleep 6
  adb shell input keyevent KEYCODE_HOME
}
seed 0
shot 01-home-custom 2
# Swipe up must not open the drawer in button mode
adb shell input swipe "$cx" $((h * 70 / 100)) "$cx" $((h * 25 / 100)) 300
shot 02-swipe-up-button-mode 1.5
read -r ax ay < <(find_desc "$out/02-swipe-up-button-mode.xml" "Apps")
printf 'apps button %s,%s\n' "$ax" "$ay" > "$out/info.txt"
adb shell input tap "$ax" "$ay"
shot 03-drawer-from-button 1.5
adb shell input keyevent KEYCODE_HOME
sleep 1.5
# Widget picker with search
adb shell input swipe "$cx" $((h * 20 / 100)) "$cx" $((h * 20 / 100)) 1500
shot 04-menu 1
read -r wx wy < <(find_desc "$out/04-menu.xml" "Widgets")
adb shell input tap "$wx" "$wy"
shot 05-picker 2.5
read -r sx sy < <(find_desc "$out/05-picker.xml" "Search widgets")
adb shell input tap "$sx" "$sy"; sleep 0.5; adb shell input text clock
shot 06-picker-search 2
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_HOME
sleep 1.5
# Haptics: lift an icon, move it a little and drop; then dump the vibrator history
read -r gx gy < <(find_desc "$out/01-home-custom.xml" "Gmail")
adb shell input motionevent DOWN "$gx" "$gy"; sleep 1.3
for i in 1 2 3 4 5 6; do adb shell input motionevent MOVE $((gx + i * 40)) "$gy"; sleep 0.08; done
sleep 0.6; adb shell input motionevent UP $((gx + 240)) "$gy"
shot 07-after-drag 1.5
adb shell dumpsys vibrator_manager > "$out/vibrator.txt" 2>&1
# Themed icons with generation on
seed 1
shot 08-home-themed 2
adb shell input tap "$ax" "$ay"
shot 09-drawer-themed 1.5
adb logcat -d -b crash > "$out/crash.txt"
