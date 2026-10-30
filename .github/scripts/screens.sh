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
# an older date with nights before and after it in the strip (issue #180 made it faster, #229)
launch --ei tab 1 --es day "$(date -d '20 days ago' +%F)"; shot sleep-older
launch --ei tab 1 --ez analysis true --ei period 1
for i in $(seq 1 12); do swipe_up; done;  shot sleep-analysis 2
launch --ei tab 2;                          shot readiness
swipe_up; swipe_up;                       shot readiness-contributors 1   # temperature line as the card below (issue #163)
# touch the "Temperature vs usual" chart (issue #226): the readout shows the newest day with its sign and
# °C, as the big number above it. The chart has no text of its own, so it is found from the card's line
# "Deviation from your usual ...": the chart starts 8 dp below that line and is 120 dp high (Pages.java),
# so the tap goes 68 dp below it (the chart's middle) and 10 px inside its right end, where the newest
# dot is (the readout takes the dot nearest the touch). Slow swipes (no fling) until the chart is on screen
# (the first run found nothing after 3 short swipes: the Contributors list above it is long).
DENSITY=$(adb shell wm density 2>/dev/null | grep -o '[0-9]\+' | tail -1)
TEMP_H=$(adb shell wm size 2>/dev/null | grep -o '[0-9]\+x[0-9]\+' | tail -1 | cut -dx -f2 | tr -d '\r')
temp_xy() {
  adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  b=$(adb shell cat /sdcard/ui.xml 2>/dev/null | grep -o 'text="Deviation from your usual[^"]*"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b
  [ $# -eq 4 ] || return 1
  local y=$(( $4 + 68 * ${DENSITY:-420} / 160 ))
  # above the tab bar and the system buttons (about 450 px on this emulator)
  [ "$y" -lt $(( ${TEMP_H:-2400} - 450 )) ] || return 1
  echo "$(( $3 - 10 )) $y"
}
xy=""
# the card sits below the long Contributors list, so up to 8 slow swipes of 600 px
for i in 1 2 3 4 5 6 7 8; do
  xy=$(temp_xy) && break
  adb shell input swipe 30 1500 30 900 1500; sleep 1
done
if [ -n "$xy" ]; then
  log "temperature chart touch at $xy"
  adb shell input tap $xy;                shot readiness-temp-touch 1
else
  echo "::warning::issue #226 step skipped: the Temperature vs usual chart was not found on screen (lines naming it in the last screen dump: $(adb shell cat /sdcard/ui.xml 2>/dev/null | grep -c 'Deviation from your usual'))"
fi
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
# line coloured pixels on a circle of 300 px around the clock's middle (540, 950 after the swipe above), not
# the night band (dial_touch.py, issue #229), else 13:00.
adb exec-out screencap > "$OUT/.dial.raw"
xy=$(python3 -I .github/scripts/dial_touch.py "$OUT/.dial.raw" 2>/dev/null)
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
launch --es settings backups;               shot settings-backups   # automatic backups, the demo's example (issue #242)
# Find my ring (issues #74, #191), all on one screen without swiping: in demo a made up signal fills
# the gauge and the trail; finder_start shows the screen before a walk (you in the middle of the dial),
# the default a made up walk on the map with its circle, finder_turn a made up turn with the arrow, and
# finder_details the full last worn text and How it works opened. Every one shows the demo's made up
# "on a finger now" answer in the last worn strip (issue #192)
launch --ez finder true --ez finder_start true; shot finder 4
launch --ez finder true;                    shot finder-walk 3
launch --ez finder true --ez finder_turn true; shot finder-turn 3
launch --ez finder true --ez finder_details true; shot finder-details 3
# Make it blink (issue #192): the demo counts the 30 s down, the made up signal pauses as with a connected ring
launch --ez finder true --ez finder_blink true; shot finder-blink 3
# Live in demo mode (issue #185): made up readings, never the ring; with blood oxygen on, whose first value comes
# 20 s in and arrives with the batch at 24 s, so the shot waits 26 s. Then the charts below the tiles
launch --ez live true --ez live_spo2 true;  shot live 26
swipe_up; swipe_up;                         shot live-charts 1
# A time left out because someone else wore the ring (issue #254), last: the example changes the demo's results for
# every shot after it. left_out leaves out the day before yesterday 13:00 to 17:00, only in demo mode (the demo file),
# then the analysis runs again: the Body tab shows the grey band on Heart rate today, Today the Left out card, and
# Settings, Advanced the time in its list
LEFT_DAY=$(date -d '2 days ago' +%F)
launch --ez left_out true --ei tab 4 --es day "$LEFT_DAY"; shot body-left-out 10
launch --ei tab 0 --es day "$LEFT_DAY";     shot today-left-out
launch --es settings advanced;              shot settings-advanced

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
