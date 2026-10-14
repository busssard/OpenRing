#!/bin/bash
# Runs on an Android emulator in CI: installs the APK, opens every screen with demo data,
# saves screenshots to $OUT and fails if the app crashed anywhere.
set -u
OUT=${OUT:-screenshots}
PKG=app.openring
MAIN=$PKG/app.openring.ui.MainActivity
mkdir -p "$OUT"
log() { echo "== $*"; }

adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
adb install -r build/OpenRing.apk || exit 1
for perm in BLUETOOTH_SCAN BLUETOOTH_CONNECT POST_NOTIFICATIONS; do adb shell pm grant $PKG android.permission.$perm 2>/dev/null; done
# a calm status bar: 09:41, full battery, no notifications
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 >/dev/null
adb logcat -c

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; log "shot $1 ($(stat -c %s "$OUT/$1.png") bytes)"; }
swipe_up() { adb shell input swipe 30 1700 30 500 300; sleep 1; }  # along the left margin, never a tap on a night
launch() { adb shell am start -S -W -n $MAIN "$@" >/dev/null; }

log "first start: setup"
launch
shot setup 5

log "demo data"
launch --ez demo true
# wait for the demo to be generated and analysed (up to 3 minutes)
for i in $(seq 1 90); do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "made up person"; then break; fi
  sleep 2
done
shot today 2

launch --ei tab 1;                          shot sleep
swipe_up; swipe_up;                       shot sleep-details 1
launch --ei tab 1 --ez analysis true --ei period 1
for i in $(seq 1 12); do swipe_up; done;  shot sleep-analysis 2
launch --ei tab 2;                          shot readiness
launch --ei tab 3;                          shot activity
launch --ei tab 4;                          shot body
swipe_up; swipe_up;                       shot body-trends 1
launch --ei tab 5;                          shot play

adb logcat -d > "$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then
  echo "::error::Open Ring crashed on the emulator"
  grep -A30 "FATAL EXCEPTION" "$OUT/logcat.txt"
  exit 1
fi
log "no crashes"
