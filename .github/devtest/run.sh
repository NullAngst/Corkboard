#!/usr/bin/env bash
# Installs Corkboard, makes it home, pokes it with gestures, saves screenshots, UI dumps and logcat.
set -u
out=devtest-out
mkdir -p "$out"
pkg=io.github.nullangst.corkboard
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true
adb shell cmd role add-role-holder --user 0 android.app.role.HOME "$pkg" || true
adb shell cmd role get-role-holders --user 0 android.app.role.HOME > "$out/home-role.txt" 2>&1
adb logcat -c
adb shell input keyevent KEYCODE_HOME
sleep 6
size=$(adb shell wm size | tr -d '\r' | awk '{print $3}')
w=${size%x*}; h=${size#*x}
cx=$((w / 2)); cy=$((h / 2))
printf 'size %s\n' "$size" > "$out/info.txt"
shot() { sleep "${2:-1.5}"; adb exec-out screencap -p > "$out/$1.png"; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$out/$1.xml" >/dev/null 2>&1; }
shot 01-home
adb shell dumpsys activity activities | grep -iE "mResumedActivity|topResumed" > "$out/resumed.txt" 2>&1
# Long-press empty space in the middle
adb shell input swipe "$cx" "$cy" "$cx" "$cy" 1500
shot 02-longpress
adb shell input keyevent KEYCODE_BACK
shot 03-after-back 1
# Swipe up from the middle (not the gesture-nav edge)
adb shell input swipe "$cx" $((h * 70 / 100)) "$cx" $((h * 25 / 100)) 250
shot 04-swipe-up 1.5
adb shell input keyevent KEYCODE_BACK
shot 05-after-back 1
# Swipe up from lower area just above the nav bar
adb shell input swipe "$cx" $((h * 88 / 100)) "$cx" $((h * 40 / 100)) 250
shot 06-swipe-up-low 1.5
adb shell input keyevent KEYCODE_HOME
shot 07-home-again 2
adb shell input swipe "$cx" "$cy" "$cx" "$cy" 1500
shot 08-longpress-again
adb shell dumpsys window windows | grep -E "Window #|mCurrentFocus|mFocusedApp" > "$out/windows.txt" 2>&1
adb logcat -d > "$out/logcat.txt"
adb logcat -d -b crash > "$out/crash.txt"
