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
wait_demo() {  # until the demo is generated and analysed (up to 3 minutes)
  for i in $(seq 1 90); do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "made up person"; then return 0; fi
    sleep 2
  done
}
wait_demo

# The app ended while the demo is prepared, then opened from the launcher (issue #69): it must finish
# the demo, not show it half built. The demo above is built for today, so start from a cleared app:
# the preparation takes several seconds, ending it one second after the screen shows cuts it off.
log "ending the app while the demo is prepared, opening it again from the launcher (issue #69)"
adb shell pm clear $PKG >/dev/null
for perm in BLUETOOTH_SCAN BLUETOOTH_CONNECT POST_NOTIFICATIONS; do adb shell pm grant $PKG android.permission.$perm 2>/dev/null; done
launch --ez demo true
sleep 1
adb shell am force-stop $PKG
sleep 1
adb shell am start -W -n $MAIN -a android.intent.action.MAIN -c android.intent.category.LAUNCHER >/dev/null
wait_demo
if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "No data for this day yet"; then
  echo "::error::the demo stayed half built after the app was ended while preparing it (issue #69)"
  exit 1
fi
if adb logcat -d 2>/dev/null | grep -q "demo: finish check, built now"; then
  log "the demo cut off by ending the app was finished after opening it again"
else
  echo "::warning::issue #69 step: no cut off demo was finished after opening again (the preparation may have ended before the app was)"
fi
shot today 2

launch --ei tab 1;                          shot sleep
swipe_up;                                 shot sleep-phone 1
launch --ei tab 1; sleep 3
swipe_up; swipe_up;                       shot sleep-details 1
launch --ei tab 1 --ez analysis true --ei period 1
for i in $(seq 1 12); do swipe_up; done;  shot sleep-analysis 2
launch --ei tab 2;                          shot readiness
swipe_up; swipe_up;                       shot readiness-contributors 1   # temperature line as the card below (issue #163)
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
# read the clock by touch (issue #109): at the trailing edge of the first blue line, so the picture shows a
# use read from a line. The demo's uses follow the time of the run, so the line is found on the screen:
# blue pixels on a circle of 300 px around the clock's middle (540, 950 after the swipe above), else 13:00.
adb exec-out screencap > "$OUT/.dial.raw"
xy=$(python3 - "$OUT/.dial.raw" <<'PY' 2>/dev/null
import math, struct, sys
d = open(sys.argv[1], "rb").read()
w, h = struct.unpack("<II", d[:8])
head = len(d) - w * h * 4
lit = []
for i in range(3600):
    a = math.radians(i / 10.0)
    x, y = int(540 + 300 * math.sin(a)), int(950 - 300 * math.cos(a))
    o = head + (y * w + x) * 4
    r, g, b = d[o], d[o + 1], d[o + 2]
    lit.append(b > 150 and b > r + 40 and g > 100)
for i in range(3600):
    if lit[i] and not lit[(i + 1) % 3600]:
        a = math.radians((i - 2) / 10.0)
        print(int(540 + 300 * math.sin(a)), int(950 - 300 * math.cos(a)))
        break
PY
)
rm -f "$OUT/.dial.raw"
log "clock touch at ${xy:-462 1282}"
adb shell input tap ${xy:-462 1282};       shot screen-touch 1
swipe_up; swipe_up;                       shot screen-days 1
swipe_up; swipe_up; swipe_up;             shot screen-apps 1
launch --es settings top;                   shot settings
launch --es settings stages;                shot settings-stage-detail
# Count phone use as awake switched off and on (issue #204): when the nights are analysed again the
# preview above redraws, its phone line goes away and comes back without leaving the page. The card
# opens at the top with the switch below the screen: one slow swipe (no fling) brings both into view.
adb shell input swipe 30 1700 30 900 1500; sleep 1
ui_dump() { adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null; }
phone_line() { ui_dump | grep -q "on the phone, shown as awake"; }
SCREEN_H=$(adb shell wm size 2>/dev/null | grep -o '[0-9]\+x[0-9]\+' | tail -1 | cut -dx -f2 | tr -d '\r')
tap_phone_switch() {  # fails when the switch is not listed or its middle is off the screen
  b=$(ui_dump | grep -o 'text="Count phone use as awake"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b
  [ $# -eq 4 ] || return 1
  local x=$(( ($1 + $3) / 2 )) y=$(( ($2 + $4) / 2 ))
  [ "$y" -gt 0 ] && [ "$y" -lt "${SCREEN_H:-2400}" ] || return 1
  adb shell input tap $x $y
}
wait_phone_line() {  # $1: 0 gone, 1 shown; up to 60 seconds for the analysis
  for i in $(seq 1 30); do
    if phone_line; then [ "$1" = 1 ] && return 0; else [ "$1" = 0 ] && return 0; fi
    sleep 2
  done
  return 1
}
if ! phone_line; then
  echo "::warning::issue #204 step skipped: no phone line in the stage detail preview on screen"
elif ! tap_phone_switch; then
  echo "::warning::issue #204 step skipped: the switch Count phone use as awake is not on screen"
else
  wait_phone_line 0 || { echo "::error::the stage detail preview kept its phone line after switching phone use off (issue #204)"; exit 1; }
  shot settings-phone-off 4   # the "Updating the nights" toast (about 3.5 s) is gone by then
  tap_phone_switch || { echo "::error::the switch Count phone use as awake was not on screen to switch it on again (issue #204)"; exit 1; }
  wait_phone_line 1 || { echo "::error::the stage detail preview did not show its phone line again after switching phone use on (issue #204)"; exit 1; }
  log "the stage detail preview followed the phone use switch both ways"
fi
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
# two named shots that are the same picture: one of them did not get to its card or state (issue #160)
./.github/scripts/same_shots.sh "$OUT" || exit 1
if [ "$DIRTY" = 1 ]; then
  echo "::error::some screenshots show a system dialog; not publishing them as README pictures"
  exit 1
fi
