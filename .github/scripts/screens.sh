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
adb logcat -G 8M >/dev/null 2>&1   # room for the whole run, so early lines are still there at the end
adb logcat -c
sleep 20   # let the launcher and Play services finish their start up work first

# A slow emulator sometimes shows "<app> isn't responding" for a system app (the launcher, Play
# services). It is not about Open Ring, but it would cover every screenshot: tap "Wait" until it is gone.
system_dialog() { adb shell dumpsys window 2>/dev/null | grep -q "Application Not Responding"; }
clear_dialogs() {
  for i in 1 2 3 4 5; do
    system_dialog || return 0
    log "system dialog on screen, tapping Wait"
    adb shell uiautomator dump /sdcard/dlg.xml >/dev/null 2>&1
    b=$(adb shell cat /sdcard/dlg.xml 2>/dev/null | grep -o 'text="Wait"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
    set -- $b
    if [ $# -eq 4 ]; then adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); else adb shell input keyevent KEYCODE_BACK; fi
    sleep 2
  done
  system_dialog && { echo "::error::a system dialog stays on screen"; DIRTY=1; }
  return 0
}
DIRTY=0
shot() {
  sleep "${2:-3}"
  clear_dialogs
  adb exec-out screencap -p > "$OUT/$1.png"
  log "shot $1 ($(stat -c %s "$OUT/$1.png") bytes)"
}
swipe_up() { adb shell input swipe 30 1700 30 500 300; sleep 1; }  # along the left margin, never a tap on a night
launch() { adb shell am start -S -W -n $MAIN "$@" >/dev/null; }

log "first start: setup"
launch
shot setup 5

log "demo data, turning the phone twice while it is prepared (issue #29)"
adb shell settings put system accelerometer_rotation 0
launch --ez demo true
adb shell settings put system user_rotation 1   # landscape: the screen is recreated
sleep 3
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q -e "demo data" -e "5 weeks" -e "demo nights"; then
  log "demo progress shown again after turning"
else
  log "no demo progress on screen after turning (it may already be done)"
fi
adb shell settings put system user_rotation 0   # back to portrait for every screenshot
sleep 2
# keep any leaked window line now, before later screens can push it out of the log buffer
adb logcat -d 2>/dev/null | grep -E "has leaked window" > "$OUT/leaked.txt"
# the app ended while the demo is prepared, then opened from the launcher (issue #69): it must finish
# the demo, not show it half built. Only when the preparation still runs here; a fast runner skips it.
ENDED=0
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q -e "demo data" -e "5 weeks" -e "demo nights"; then
  log "ending the app while the demo is prepared, opening it again from the launcher (issue #69)"
  adb shell am force-stop $PKG
  sleep 1
  adb shell am start -W -n $MAIN -a android.intent.action.MAIN -c android.intent.category.LAUNCHER >/dev/null
  ENDED=1
  sleep 3
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q -e "demo data" -e "5 weeks" -e "demo nights"; then
    log "demo progress shown again after opening the app again"
  else
    log "no demo progress on screen after opening again (it may already be done)"
  fi
else
  log "demo already prepared, not ending the app during preparation (issue #69 step skipped)"
fi
# wait for the demo to be generated and analysed (up to 3 minutes)
for i in $(seq 1 90); do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "made up person"; then break; fi
  sleep 2
done
if [ "$ENDED" = 1 ] && adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "No data for this day yet"; then
  echo "::error::the demo stayed half built after the app was ended while preparing it (issue #69)"
  exit 1
fi
shot today 2

launch --ei tab 1;                          shot sleep
swipe_up;                                 shot sleep-phone 1
launch --ei tab 1; sleep 3
swipe_up; swipe_up;                       shot sleep-details 1
launch --ei tab 1 --ez analysis true --ei period 1
for i in $(seq 1 12); do swipe_up; done;  shot sleep-analysis 2
launch --ei tab 2;                          shot readiness
launch --ei tab 3;                          shot activity
# third demo day: the note that the weekly parts are based on 3 days so far (issue #44)
launch --ei tab 3 --es day "$(date -d '32 days ago' +%F)"
# two slow swipes (no fling, about 1700 px): the note sits just above Contributors
adb shell input swipe 30 1700 30 850 1500; adb shell input swipe 30 1700 30 850 1500
shot activity-first-days 1
launch --ei tab 4;                          shot body
swipe_up; swipe_up;                       shot body-trends 1
launch --ei tab 5;                          shot screen
adb shell input swipe 30 1700 30 900 1500; shot screen-numbers 1   # slow, no fling
adb shell input tap 462 1282;              shot screen-touch 1     # read the clock by touch (about 13:00)
swipe_up; swipe_up;                       shot screen-days 1
swipe_up; swipe_up; swipe_up;             shot screen-apps 1
launch --es settings top;                   shot settings
launch --es settings stages;                shot settings-stage-detail
launch --es settings screen;                shot settings-screen-time
launch --es settings games;                 shot settings-games
# Find my ring (issue #74): in demo a made up signal fills the meter, a made up walk shows the
# mini map with the circle, and (finder_turn) a made up turn shows the arrow
launch --ez finder true;                    shot finder 4
swipe_up; swipe_up;                       shot finder-walk 2
launch --ez finder true --ez finder_turn true; sleep 3
swipe_up; swipe_up;                       shot finder-turn 2

adb logcat -d > "$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then
  echo "::error::Open Ring crashed on the emulator"
  grep -A30 "FATAL EXCEPTION" "$OUT/logcat.txt"
  exit 1
fi
log "no crashes"
# a screen closed with its dialog still open (issue #29: the demo progress after turning the phone)
grep -E "has leaked window" "$OUT/logcat.txt" >> "$OUT/leaked.txt"
if grep -q "app\.openring\." "$OUT/leaked.txt"; then
  echo "::error::Open Ring left a dialog behind on a closed screen"
  grep "app\.openring\." "$OUT/leaked.txt" | head -20
  grep -A8 -E "has leaked window" "$OUT/logcat.txt" | head -40
  exit 1
fi
log "no leaked dialogs"
if [ "$DIRTY" = 1 ]; then
  echo "::error::some screenshots show a system dialog; not publishing them as README pictures"
  exit 1
fi
