#!/bin/bash
# Runs on an Android emulator in CI: installs the APK, opens every screen with demo data,
# saves screenshots to $OUT and fails if the app crashed anywhere.
set -u
OUT=${OUT:-screenshots}
PKG=app.openring
MAIN=$PKG/app.openring.ui.MainActivity
mkdir -p "$OUT"
log() { echo "== $*"; }

# Every adb call has a time limit (issue #278): a stuck emulator fails the run within minutes with a
# clear line, instead of waiting for the job timeout. ADB_WAIT=<seconds> sets the limit of the next call.
# The error lines go to descriptor 3 (the log), since most calls have their output captured or redirected.
exec 3>&2
BOOT_WAIT=${BOOT_WAIT:-360}
ADB_TIMEOUT=${ADB_TIMEOUT:-60}
adb() {
  local limit=${ADB_WAIT:-$ADB_TIMEOUT} rc
  timeout -k 10 "$limit" adb "$@"
  rc=$?
  if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
    echo "::error::adb $* did not answer within $limit s. The emulator is stuck (not caused by the app); push again to retry." >&3
    kill -TERM $$ 2>/dev/null
    exit 1
  fi
  return $rc
}
# Both boot waits share one limit of BOOT_WAIT seconds.
boot_wait() {
  local left=$((BOOT_DEADLINE - SECONDS)) rc
  [ $left -lt 1 ] && left=1
  timeout -k 10 "$left" adb "$@" >/dev/null 2>&1
  rc=$?
  [ $rc -eq 0 ] && return 0
  if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
    echo "::error::The emulator did not finish starting within $BOOT_WAIT s (not caused by the app); push again to retry." >&3
  else
    echo "::error::adb could not reach the emulator while it was starting (adb $1 ended with code $rc, not caused by the app); push again to retry." >&3
  fi
  exit 1
}
BOOT_DEADLINE=$((SECONDS + BOOT_WAIT))
boot_wait wait-for-device
boot_wait shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
ADB_WAIT=180 adb install -r build/OpenRing.apk || exit 1
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
TOUR_FAIL=0
shot() {
  sleep "${2:-3}"
  clear_dialogs
  adb exec-out screencap -p > "$OUT/$1.png"
  log "shot $1 ($(stat -c %s "$OUT/$1.png") bytes)"
}
swipe_up() { adb shell input swipe 30 1700 30 500 300; sleep 1; }  # along the left margin, never a tap on a night
# Every step starts with every tab in the standard layout (owner's wish of 18 November: tiles move and fold, and stay so);
# the extra is for demo mode only. launch_kept keeps the layout the steps before made (the tiles steps below).
# tour -1 (issue #453), with both: the short tour does not start by itself on these starts, so it covers no other shot; a
# later --ei tour on the same line wins (the tour steps below)
launch() { adb shell am start -S -W -n $MAIN --ez layout_reset true --ei tour -1 "$@" >/dev/null; }
launch_kept() { adb shell am start -S -W -n $MAIN --ei tour -1 "$@" >/dev/null; }

log "first start: setup"
launch
shot setup 5
# Getting started (issue #373): Get started leads to the device choice, a ring, a watch or just this phone
tap_text() {  # $1 the exact text of a view: taps its middle; fails when not on screen
  local b
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  b=$(adb shell cat /sdcard/ui.xml 2>/dev/null | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b
  [ $# -eq 4 ] || return 1
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
if tap_text "Get started"; then shot setup-choice 2
else echo "::warning::issue #373 step: Get started was not on the first screen"; fi

# The short tour (issue #453): until its bubble shows the text $1 (a step's count, or Keep looking on the demo's ending), up
# to 8 s, since the bubble is placed a moment after the page; tour_shot takes the shot once it is there, else fails the run
tour_wait() {
  for i in 1 2 3 4 5 6 7 8; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "$1" && return 0
    sleep 1
  done
  return 1
}
tour_shot() {
  tour_wait "$2" || { echo "::error::issue #453 step: the tour's bubble ($2) did not show for $1"; TOUR_FAIL=1; }
  shot "$1" 1
}

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
    # the page, or the short tour's bubble over it (issue #453)
    if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q -e "made up person" -e "Skip the tour"; then return 0; fi
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
# ended half a second after the screen shows (launch waits for it): with a whole second the slow runner of PR #400 let
# the preparation finish first, and the step found nothing cut off to finish
sleep 0.5
adb shell am force-stop $PKG
sleep 1
adb shell am start -W -n $MAIN -a android.intent.action.MAIN -c android.intent.category.LAUNCHER >/dev/null
wait_demo
# The short tour (issue #453) starts by itself the first time the demo opens: this start from the launcher has no extras and
# the app was cleared above. Next twice (Sync, the scores, then the tab bar), then Skip the tour (remembered, so no later
# start shows it by itself) and a fresh look at the page for the check below; Show the tour again below shows step 1
# (the page may show a moment before the bubble is placed, so the bubble is waited for)
if tour_wait "Skip the tour"; then
  adb logcat -d -s OpenRing:I 2>/dev/null | grep -q "tour: started by itself (demo)" || echo "::warning::issue #453 step: the tour shows, but its line in the log is missing"
  tap_text "Next" && tour_wait "2 of 7" && tap_text "Next" || echo "::warning::issue #453 step: Next could not be tapped twice"
  tour_shot tour-tabs "3 of 7"
  tap_text "Skip the tour" || echo "::warning::issue #453 step: Skip the tour could not be tapped"
  sleep 2
  wait_demo
else
  echo "::error::the short tour did not start by itself the first time the demo opened (issue #453)"
  TOUR_FAIL=1
fi
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
# Today's first screen (owner's wish of 17 November): the day's sentence and line must be on it without scrolling, with a
# ring, only a watch and only the phone, with the demo's badges. The app logs in demo mode where the line ends on the page
# and how much the first screen shows (MainActivity.firstScreen); a line that does not fit fails the run at the end
FIRST_CUT=0
first_screen() {  # $1 ring, watch or phone: the newest measurement of that Today
  local l
  l=$(adb logcat -d -s OpenRing:I 2>/dev/null | grep "today first screen ($1)" | tail -1)
  if [ -z "$l" ]; then echo "::warning::Today ($1): no first screen measurement in the log"; return 0; fi
  log "${l#*: }"
  case "$l" in
    *", fits"*) ;;
    *) echo "::error::Today ($1): the day's sentence and line are not on the first screen: ${l#*: }"; FIRST_CUT=1 ;;
  esac
}
first_screen ring
# the battery of the demo's ring and watch right under the score rings, one folded line each (owner's wishes of 15 and 17
# November), is in the today shot above (today-battery-open below opens it), the watch's alone in today-watch-only, none in
# today-phone-only. Recent badges come after Your day, each badge chip the same size in two columns (owner's wish of 17
# November), framed in the colour of its tab (12 November); today-period below shows them
# Today's Your day card (owner's wish of 16 November): the sentence with the day's facts, the line of the day, and Why this
# line opened (extra day_line_why, demo mode only: each check of the tree, top to bottom); then the same card with only the
# watch and only the phone (extra devices, nothing stored)
launch --ei tab 0 --es card YOUR_DAY;                         shot today-line 2
launch --ei tab 0 --es card YOUR_DAY --ez day_line_why true;  shot today-line-why 1
launch --ei tab 0 --ei devices 1 --es card YOUR_DAY;          shot today-line-watch 2
launch --ei tab 0 --ei devices 2 --es card YOUR_DAY;          shot today-line-phone 2
# Life factors (issue #292, phase 1): Today's row "What was today like?" right after Your day (the demo logged Meditation
# today), its More opened (extra life_more, demo mode only: All factors, Several days, Add your own, Choose the chips), the
# line beside last night on Sleep ("Before this night: ..."), the Readiness morning, and Body's month view touched in the
# middle of its width (a tap reads the day). Made up tags of the demo file only, never Intimacy
launch --ei tab 0 --es card WHAT_WAS;                                 shot today-life 2
launch --ei tab 0 --es card WHAT_WAS --ez life_more true;             shot today-life-more 2
launch --ei tab 1 --es card LIFE_FACTORS;                           shot sleep-life 2
launch --ei tab 2 --es card LIFE_FACTORS;                           shot readiness-life 2
# When and how long (issue #435): the sheet opened on Today's row for Sauna (extra life_time, demo mode only; nothing is saved
# without a tap): From as two wheels, How long as the tag's usual lengths, Save, Without a time
launch --ei tab 0 --es card WHAT_WAS --ez life_time true;           shot today-life-time 3
# A night by hand (issue #427), before the left out examples below change the demo file: hand_night (demo mode only, nothing
# stored) shows today and the day before yesterday as without a device night, so Today asks for last night and the demo's
# made up night by hand shows on its day; hand_entry opens the entry for last night with two wake marks (nothing is saved),
# its times worked out as at 09:00 of today, so the picture does not depend on the hour this runs (issue #458)
launch --ei tab 0 --ez hand_night true --es card No_night_for_last;  shot today-hand-ask 2
launch --ez hand_entry true;                                         shot hand-entry 3
launch --ei tab 1 --ez hand_night true --es day "$(date -d '2 days ago' +%F)"; shot sleep-hand 3
launch --ei tab 1 --ez hand_night true --es day "$(date -d '2 days ago' +%F)" --es card YOUR_NIGHT; shot sleep-hand-night 2

launch --ei tab 1;                          shot sleep
swipe_up;                                 shot sleep-phone 1
launch --ei tab 1; sleep 3
swipe_up; swipe_up;                       shot sleep-details 1
# the Details card with Total sleep first, next to Time in bed (issue #409); sleep-details above scrolls past it
launch --ei tab 1 --es card DETAILS;        shot sleep-details-card 2
# Sleep sounds (issue #380, phase 1b), demo mode only, which never records: the card with the demo night's clips (tones made in
# code), the consent before the first night, the microphone test's made up result, the clip list, Settings' part in the Phone block
launch --ei tab 1 --es card SLEEP_SOUNDS;   shot sleep-sounds-card 2
launch --es sounds consent;                 shot sleep-sounds-consent 2
launch --es sounds test;                    shot sleep-sounds-test 2
launch --es sounds list;                    shot sleep-sounds-list 3
launch --es settings sounds;                shot settings-sounds 3
# an older date with nights before and after it in the strip (issue #180 made it faster, #229)
launch --ei tab 1 --es day "$(date -d '20 days ago' +%F)"; shot sleep-older
# An older day keeps the newer days in the charts (owner, 23 November): 3 days back, the 7 day bars still end today with the
# picked day's bar highlighted, and Ready's 30 day HRV still ends today with a line on the picked day
launch --ei tab 1 --es day "$(date -d '3 days ago' +%F)" --es card TOTAL_SLEEP;   shot sleep-week-picked 2
launch --ei tab 2 --es day "$(date -d '3 days ago' +%F)" --es card HRV;           shot ready-month-picked 2
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
# the screen's density and height, read once for the touch steps below
DENSITY=$(adb shell wm density 2>/dev/null | grep -o '[0-9]\+' | tail -1)
SCREEN_H=$(adb shell wm size 2>/dev/null | grep -o '[0-9]\+x[0-9]\+' | tail -1 | cut -dx -f2 | tr -d '\r')
ui_dump() { adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null; }
temp_xy() {
  local b
  b=$(ui_dump | grep -o 'text="Deviation from your usual[^"]*"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b
  [ $# -eq 4 ] || return 1
  local y=$(( $4 + 68 * ${DENSITY:-420} / 160 ))
  # above the tab bar and the system buttons (about 450 px on this emulator)
  [ "$y" -lt $(( ${SCREEN_H:-2400} - 450 )) ] || return 1
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
# Body opens with the period tracker (issue #252, the demo's made up cycles): its card, then one slow swipe (no fling) to
# its month calendar with the expected period, ovulation range and fertile days, then the day editor (extra period_edit,
# demo mode only, a day of the newest period picked; nothing is saved without a tap)
launch --ei tab 4;                          shot body
# the card names the most likely day of the next period (owner's feedback of 11 November)
ui_dump | grep -q "Next period expected on" || echo "::warning::period step: the line with the expected day was not on the Period card"
adb shell input swipe 30 1700 30 700 1500; shot body-period-calendar 1
# the same calendar six months ahead (extra period_month, demo mode only): each expected period and ovulation shaded darkest
# on its most likely day (dashed ring), the ranges wider than next month's
launch --ei tab 4 --ei period_month 6; sleep 3
adb shell input swipe 30 1700 30 700 1500
shot body-period-ahead 1
launch --ei tab 4 --ez period_edit true;    shot period-edit 3
# the trend charts below the period card (README picture): slow swipes until the Resting heart rate caption is in the
# upper third of the screen
launch --ei tab 4; sleep 3
trend_top() { ui_dump | grep -o 'text="RESTING HEART RATE, 30 DAYS"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | sed -n 2p; }
for i in $(seq 1 14); do
  y=$(trend_top)
  if [ -n "$y" ] && [ "$y" -gt 0 ] && [ "$y" -lt $(( ${SCREEN_H:-2400} / 3 )) ]; then break; fi
  adb shell input swipe 30 1500 30 900 1500; sleep 1
done
[ -n "$y" ] || echo "::warning::issue #252 step: the Resting heart rate chart was not found on Body, body-trends shows where the swipes ended"
shot body-trends 1
# the battery lines on Today folded open (owner's wish of 17 November): what the numbers mean and the way to each history
launch --ei tab 0 --ez battery_open true;   shot today-battery-open 2
# Today's compact period card near the bottom (issue #252)
launch --ei tab 0; sleep 3
swipe_up; swipe_up; swipe_up; swipe_up;   shot today-period 1
# The tiles of every tab (owner's wishes of 18 and 20 November): a tap on a tile's title folds it, and moving happens in an
# edit session. Settings, Layout of the tabs, Rearrange tiles opens Today with a frame and a handle on every tile and Reset and
# Save in the header. A real touch, step by step: down on Your day's handle, moved up in steps, the shot while it is still
# held, then up above the scores and let go. A tap on the Scores handle opens the menu (Move up, Move down, Fold), then three
# tiles folded with a tap on their titles, each with its key number in one line, then Save. The order and folds stay (checked
# after a fresh start), and Settings' Reset layout with its question puts everything back.
text_mid() {  # $1 the exact text: "x y" of its middle; fails when not on screen
  local b
  b=$(ui_dump | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b
  [ $# -eq 4 ] || return 1
  echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}
text_y() { local m; m=$(text_mid "$1") || return 1; echo "${m#* }"; }
desc_mid() {  # $1 the exact content description: "x y" of its middle; fails when not on screen
  local b
  b=$(ui_dump | grep -o "content-desc=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b
  [ $# -eq 4 ] || return 1
  echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}
launch --ei tab 0; sleep 3
launch_kept --es settings layout; sleep 2
if tap_text "Rearrange tiles"; then
  shot today-edit 3
  if xy=$(desc_mid "Move Your day"); then
    set -- $xy
    dx=$1; dy=$2
    adb shell input motionevent DOWN $dx $dy >/dev/null
    sleep 0.3
    for step in 1 2 3 4; do dy=$(( dy - 110 )); adb shell input motionevent MOVE $dx $dy >/dev/null; done
    sleep 0.5
    adb exec-out screencap -p > "$OUT/today-tile-lifted.png"; log "shot today-tile-lifted (held mid drag)"
    while [ "$dy" -gt 420 ]; do dy=$(( dy - 110 )); adb shell input motionevent MOVE $dx $dy >/dev/null; done
    adb shell input motionevent UP $dx $dy >/dev/null
    shot today-tile-moved 2
    yd=$(text_y "YOUR DAY"); ys=$(text_y "SCORES")
    if [ -n "$yd" ] && [ -n "$ys" ] && [ "$yd" -lt "$ys" ]; then log "Your day moved above the scores"
    else echo "::warning::tiles step: Your day did not land above the scores (Your day at ${yd:-?}, Scores at ${ys:-?})"; fi
  else echo "::warning::tiles step: the handle of Your day was not on the first screen of the edit session"; fi
  # a tap on a handle: the menu
  if xy=$(desc_mid "Move Scores"); then
    adb shell input tap $xy; shot today-tile-menu 2
    adb shell input keyevent KEYCODE_BACK; sleep 1
  else echo "::warning::tiles step: the Scores handle was not on screen for the menu"; fi
  # three tiles folded with a tap on their titles: they start folded once saved
  for t in "SCORES" "YOUR DAY" "WHAT WAS TODAY LIKE?"; do
    if xy=$(text_mid "$t"); then adb shell input tap 540 ${xy#* }; sleep 1
    else echo "::warning::tiles step: the title $t was not on screen to fold it"; fi
  done
  shot today-tiles-folded 2
  tap_text "Save" || tap_text "SAVE" || echo "::warning::tiles step: Save was not found in the edit header"
  shot today-edit-saved 2
  # a fresh start keeps the order and the folds
  launch_kept --ei tab 0; sleep 3
  if ui_dump | grep -q 'text="Readiness [0-9]'; then log "the folded Scores tile stays folded after a fresh start"
  else echo "::warning::tiles step: after a fresh start the Scores tile was not folded with its line of scores"; fi
  launch_kept --es settings layout;           shot settings-layout 2
  if tap_text "Reset layout"; then
    shot settings-layout-reset 2
    tap_text "Reset" || tap_text "RESET" || echo "::warning::tiles step: the question's Reset button was not found"
    sleep 1
    launch_kept --ei tab 0; sleep 3
    if ui_dump | grep -q 'text="Readiness [0-9]'; then echo "::warning::tiles step: Reset layout left the Scores tile folded"
    else log "Reset layout put Today back to the standard layout"; fi
  else echo "::warning::tiles step: Reset layout was not found in Settings"; fi
else
  echo "::warning::tiles step: Rearrange tiles was not found in Settings, Layout of the tabs"
fi
# A folded score card shows a small dial with its score (owner's wish of 21 November): Ready's score folded, then opened again
launch_kept --ei tab 2; sleep 3
if xy=$(text_mid "READINESS SCORE"); then
  adb shell input tap 540 ${xy#* }; shot ready-score-folded 2
  adb shell input tap 540 ${xy#* }; sleep 1
else echo "::warning::score dial step: the title READINESS SCORE was not on screen"; fi
# the Sleep dial folded with a tap on its title (review of #415 with #411): a small dial with the score, and its line keeps
# the word and the time asleep, "Optimal, 7h 28m asleep" (owner's wish of 21 November); the next launch puts the standard
# layout back
launch --ei tab 1; sleep 3
if xy=$(text_mid "SLEEP SCORE"); then
  adb shell input tap 540 ${xy#* }; sleep 1
  shot sleep-dial-folded 1
  if ui_dump | grep -q 'text="[A-Za-z ]*, [0-9]*h [0-9]*m asleep"'; then log "the folded Sleep dial says the time asleep"
  else echo "::warning::tiles step: the folded Sleep dial has no line with the time asleep"; fi
else echo "::warning::tiles step: the Sleep score title was not on screen to fold it"; fi
# Phase 1a2 of #252 at the end of Body: "Your cycles" (cycle day, next period, the cycle length dots next to the typical
# length, then period length and amount), touched on the newest cycle dot, and the night temperature line next to the
# cycles (the demo's nights are warmer after ovulation), touched on its newest night. Slow swipes until the caption is in
# the upper third; a chart is found by its content description and touched 10 px inside its right end, where the newest is
caption_top() { ui_dump | grep -o "text=\"$1[^\"]*\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | sed -n 2p; }  # $1 the text or its start
scroll_to() {  # $1 caption text, $2 the most swipes. Done once the caption is in the upper third, or on screen when the
  # page cannot move further (a card near the end of a page never reaches the upper third)
  local y prev="" now
  for i in $(seq 1 "$2"); do
    y=$(caption_top "$1")
    if [ -n "$y" ] && [ "$y" -gt 0 ] && [ "$y" -lt $(( ${SCREEN_H:-2400} / 3 )) ]; then return 0; fi
    now=$(ui_dump | grep -o 'bounds="[^"]*"' | head -40 | tr -d '\n')
    if [ -n "$prev" ] && [ "$now" = "$prev" ]; then
      [ -n "$y" ] && [ "$y" -gt 0 ] && [ "$y" -lt $(( ${SCREEN_H:-2400} - 300 )) ] && return 0
      return 1
    fi
    prev=$now
    adb shell input swipe 30 1500 30 900 1500; sleep 1
  done
  return 1
}
# a tile's header reads its title too ("Steps, last 7 days, ..., open, tap to close"): only the chart itself is touched
touch_desc() {  # $1 the start of a content description, $2 where to tap in percent of its width from the left (default:
  # 10 px inside the right end), in the middle; fails when not on screen
  local b at=${2:-}
  b=$(ui_dump | grep -o "content-desc=\"$1[^\"]*\"[^>]*bounds=\"[^\"]*\"" | grep -v -e 'tap to close"' -e 'tap to open"' | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b
  [ $# -eq 4 ] || return 1
  local y=$(( ($2 + $4) / 2 )) x=$(( $3 - 10 ))
  [ -n "$at" ] && x=$(( $1 + ($3 - $1) * at / 100 ))
  [ "$y" -gt 0 ] && [ "$y" -lt $(( ${SCREEN_H:-2400} - 450 )) ] || return 1
  adb shell input tap $x $y
}
# Body's one temperature chart carries the cycle (owner's feedback of 11 November): period days, the measured rise, the
# expected period and ovulation range ahead, touched at 60 % of its width (a night after the demo's measured ovulation:
# date, temperature, cycle day and phase). It sits above Your cycles, so it comes first.
launch --ei tab 4 --es card TEMPERATURE_DEVIATION_AND_YOUR_CYCLE; sleep 3
scroll_to "TEMPERATURE DEVIATION AND YOUR CYCLE" 3 || echo "::warning::period step: the temperature chart with the cycle was not found on Body"
if touch_desc "Night temperature since" 60; then shot body-period-temp 1
else echo "::warning::period step: the temperature chart with the cycle was not on screen to touch it"; shot body-period-temp 1; fi
launch --ei tab 4 --es card LIFE_FACTORS; sleep 3
scroll_to "LIFE FACTORS" 3 || echo "::warning::issue #292 step: the Life factors card was not found on Body"
shot body-life 1
# a day read as a touch reads it: yesterday (extra life_read, days before today, demo mode only), which has the demo's Alcohol
# and Meditation; computed in the app, so it is a past day with tags on any date CI runs (a fixed tap point is not)
launch --ei tab 4 --es card LIFE_FACTORS --ei life_read 1; sleep 3
scroll_to "LIFE FACTORS" 3 || echo "::warning::issue #292 step: the Life factors card was not found on Body for the read day"
shot body-life-touch 1
# how the nights went after each factor (issue #292, phase 2): the card with every row folded, then Alcohol's row opened (the
# demo's made up clear effect: the sentence, the range bar, the chart of the nights touched on its newest night after Alcohol)
# and Meditation's (a number that is not clear yet); extra life_effect is a factor's key, demo mode only
launch --ei tab 4 --es card LIFE_FACTORS_AND; sleep 3
scroll_to "LIFE FACTORS AND YOUR NIGHTS" 3 || echo "::warning::issue #292 step: the card Life factors and your nights was not found on Body"
shot body-life-effect 1
launch --ei tab 4 --es card LIFE_FACTORS_AND --es life_effect alcohol; sleep 3
scroll_to "After alcohol" 4 || echo "::warning::issue #292 step: Alcohol's row was not open on Body"
shot body-life-effect-clear 1
launch --ei tab 4 --es card LIFE_FACTORS_AND --es life_effect meditation; sleep 3
scroll_to "After meditation" 4 || echo "::warning::issue #292 step: Meditation's row was not open on Body"
shot body-life-effect-unclear 1
# the demo's sauna yesterday at 19:00 for 45 minutes as a band on yesterday's heart rate (issue #435), touched in the band (82 % of
# the chart's width is about 19:20), and the line under the chart: what the ring measured during it next to the day's average
launch --ei tab 4 --es day "$(date -d '1 day ago' +%F)" --es card HEART_RATE_TODAY; sleep 3
if touch_desc "Heart rate of the day with your logged times" 82; then shot body-life-band 1
else echo "::warning::issue #435 step: the day's heart rate chart with the sauna band was not on screen to touch it"; shot body-life-band 1; fi
launch --ei tab 4 --es card YOUR_CYCLES; sleep 3
scroll_to "YOUR CYCLES" 3 || echo "::warning::issue #252 step: the Your cycles card was not found on Body, body-cycles shows where the swipes ended"
shot body-cycles 1
if touch_desc "Cycle lengths"; then shot body-cycles-touch 1
else echo "::warning::issue #252 step skipped: the cycle length chart was not on screen to touch it"; fi
# issue #383: "How close the estimates were" after Your cycles: each prediction method's error and range hits on the demo's
# 3 made up years (the one in use marked), then the chart of each period's error with the method in use, touched at 70 %
# of its width (a period: expected day, how many days later or earlier, inside or outside the range)
launch --ei tab 4 --es card HOW_CLOSE_THE_ESTIMATES_WERE; sleep 3
scroll_to "HOW CLOSE THE ESTIMATES WERE" 3 || echo "::warning::issue #383 step: the card How close the estimates were was not found on Body"
shot body-period-accuracy 1
scroll_to "Each period, " 12 || echo "::warning::issue #383 step: the chart of each period's error was not found"
if touch_desc "Days each period came" 70; then shot body-period-accuracy-touch 1
else echo "::warning::issue #383 step skipped: the chart of each period's error was not on screen to touch it"; fi
# Phase 2 of #252: "Ovulation from your temperature" in Your cycles (this cycle's measured day, in how many cycles a rise
# was found, the own time from ovulation to the period, a line under each cycle), the Period card's measured lines, and the
# rise marked on the night temperature line (baseline, ringed high nights, the ovulation line), opened touched on its first
# high night (extra period_rise, demo mode only)
launch --ei tab 4 --es card YOUR_CYCLES; sleep 3
scroll_to "Ovulation from your temperature" 8 || echo "::warning::issue #252 phase 2 step: Ovulation from your temperature was not found on Body"
shot body-cycles-ovulation 1
launch --ei tab 4 --es card PERIOD; sleep 3
scroll_to "Likely fertile days were" 6 || echo "::warning::issue #252 phase 2 step: the measured lines were not found on the Period card"
shot body-period-measured 1
launch --ei tab 4 --ez period_rise true --es card TEMPERATURE_DEVIATION_AND_YOUR_CYCLE; sleep 3
scroll_to "TEMPERATURE DEVIATION AND YOUR CYCLE" 3 || echo "::warning::issue #252 phase 2 step: the temperature chart with the cycle was not found on Body"
shot body-period-rise 1
# How it works at the bottom of the Period card, opened (extra period_how, demo mode only): the shots above show it
# closed, as everyone sees it first (owner's feedback of 3 November, issue #252)
launch --ei tab 4 --ez period_how true --es card PERIOD; sleep 3
scroll_to "How it works" 8 || echo "::warning::issue #252 step: How it works was not found on the Period card"
shot body-period-how 1
# the same launch opens How it works in Your cycles and on the night temperature card too (issue #363). Every card on
# Body ends with a "How it works" row, so the one of Your cycles is the last such row above its list's "Cycle length
# chart" text; slow swipes from the Your cycles caption until that row is in the upper third
how_above() {  # $1 the start of a text in an opened list: the top of the last "How it works" row above it, when both are shown
  local d t
  d=$(ui_dump)
  t=$(echo "$d" | grep -o "text=\"$1[^\"]*\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | sed -n 2p)
  [ -n "$t" ] || return 0
  echo "$d" | grep -o 'text="How it works"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' \
    | sed 's/bounds="\[[0-9]*,\([0-9]*\)\].*/\1/' | awk -v t="$t" '$1 < t' | tail -1
}
cycles_how=0
launch --ei tab 4 --ez period_how true --es card YOUR_CYCLES; sleep 3
scroll_to "YOUR CYCLES" 3 || echo "::warning::issue #363 step: the Your cycles card was not found on Body"
for i in $(seq 1 12); do
  y=$(how_above "Cycle length chart")
  if [ -n "$y" ] && [ "$y" -gt 0 ] && [ "$y" -lt $(( ${SCREEN_H:-2400} / 3 )) ]; then cycles_how=1; break; fi
  adb shell input swipe 30 1500 30 900 1500; sleep 1
done
[ $cycles_how = 1 ] || echo "::warning::issue #363 step: How it works of Your cycles was not found, body-cycles-how shows where the swipes ended"
shot body-cycles-how 1
# the temperature chart with the cycle sits above Your cycles: a fresh launch, then slow swipes until its How it works
# text (opened) is in the upper half
launch --ei tab 4 --ez period_how true --es card TEMPERATURE_DEVIATION_AND_YOUR_CYCLE; sleep 3
scroll_to "TEMPERATURE DEVIATION AND YOUR CYCLE" 3 || echo "::warning::issue #363 step: the temperature chart with the cycle was not found on Body"
for i in $(seq 1 6); do
  y=$(ui_dump | grep -o 'text="[^"]*In many people it rises[^"]*"[^>]*bounds="[^"]*"' | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | sed -n 2p)
  if [ -n "$y" ] && [ "$y" -gt 0 ] && [ "$y" -lt $(( ${SCREEN_H:-2400} / 2 )) ]; then break; fi
  adb shell input swipe 30 1500 30 1000 1500; sleep 1
done
ui_dump | grep -q "In many people it rises" || echo "::warning::issue #363 step: How it works of the temperature chart is not open on screen"
shot body-temp-how 1
# the gentle question "Have your periods stopped?" (extra period_ask, demo mode only; nothing is saved without a tap)
launch --ei tab 4 --ez period_ask true;    shot period-ask 3
# Import from drip (phase 1b of #252): in demo mode the preview of a made up drip file against the demo's log (extra drip;
# demo mode never imports), scrolled down to its short periods and the days that differ, then Correct on its first short
# period (drip_correct) with the day editor's calendar. The night temperature shot above shows the demo's mouth line.
launch --ez drip true;                      shot drip-preview 4
swipe_up; swipe_up;                         shot drip-preview-more 1
launch --ez drip true --ez drip_correct true; shot drip-correct 4
launch --ei tab 5;                          shot screen
adb shell input swipe 30 1700 30 900 1500; shot screen-numbers 1   # slow, no fling
# read the clock by touch (issue #109): at the trailing edge of the first blue line, so the picture shows a
# use read from a line. The demo's uses follow the time of the run, so the line is found on the screen:
# line coloured pixels on a circle through the use lines, not the night band (dial_touch.py, issue #229).
# The circle comes from the clock's bounds in the screen dump (content-desc "Phone use clock", issue #261),
# else 13:00 on it. Without the bounds (clock cut off or not listed) the fixed middle 540 1047 measured on an
# earlier run, else a fixed tap at 13:00 on that fixed circle (dial_touch.py's point(540, 1047, 300, 195),
# checked by tests/test_ci_scripts.sh).
DIAL_FALLBACK="462 1336"
DIAL_VIEW=$(ui_dump | grep -o 'content-desc="Phone use clock[^"]*"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
adb exec-out screencap > "$OUT/.dial.raw"
xy=""
if [ "$(echo $DIAL_VIEW | wc -w)" = 4 ]; then
  xy=$(python3 -I .github/scripts/dial_touch.py "$OUT/.dial.raw" --view $DIAL_VIEW ${DENSITY:-420} 2>/dev/null)
fi
[ -n "$xy" ] || { log "clock bounds not usable (${DIAL_VIEW:-not listed}), fixed middle"; xy=$(python3 -I .github/scripts/dial_touch.py "$OUT/.dial.raw" 2>/dev/null); }
rm -f "$OUT/.dial.raw"
log "clock touch at ${xy:-$DIAL_FALLBACK}"
adb shell input tap ${xy:-$DIAL_FALLBACK}; shot screen-touch 1
swipe_up; swipe_up;                       shot screen-days 1
swipe_up; swipe_up; swipe_up;             shot screen-apps 1
# the Badges page (issue #265): calendar with a dot in its colour for each badge of a day and the colours' names under it,
# then each kind in its colour (frame, disc and name) with how often and its rule
launch --ez badges true;                    shot badges 4
swipe_up; swipe_up; swipe_up;               shot badges-kinds 1
# Trends (issue #374) on the demo's made up year before its five weeks: the card at the bottom of Sleep ("YOUR MONTHS", its caption), Sleep
# by month (time asleep, about 25 minutes more from four months ago), one month touched (the chart found by its content
# description, touched at 45 % of its width: the bubble and the full readout under the chart), steps by year (extra trends is
# the group, trends_measure 10 steps, trends_period 2 years), and with only the watch the line for under two months
launch --ei tab 1 --es card YOUR_MONTHS;    shot sleep-trends-card
launch --ei trends 0;                       shot trends-sleep 4
if touch_desc "Trends chart" 45; then shot trends-touch 1
else echo "::warning::issue #374 step: the Trends chart was not on screen to touch it"; shot trends-touch 1; fi
# Trends, phase 2 (issue #374): did something change. Time asleep by month opened at the month four months ago (the demo's
# longer nights start then): the dashed line with the average before and after and that month's readout with the step, then
# scrolled to the card "Did something change?" with its sentence and what it cannot say
launch --ei trends 0 --es day "$(date -d '4 months ago' +%F)"; shot trends-change 4
if scroll_to "DID SOMETHING CHANGE?" 4; then shot trends-change-card 1
else echo "::warning::issue #374 phase 2 step: the card DID SOMETHING CHANGE? was not found"; shot trends-change-card 1; fi
launch --ei trends 2 --ei trends_measure 10 --ei trends_period 2; shot trends-steps-year 4
launch --ei trends 0 --ei devices 1;        shot trends-watch-only 4
launch --es settings top;                   shot settings
# The short tour (issue #453): Show the tour again on the demo's card in Settings opens it at its first step (Sync); then
# the steps by the extra tour: the scores, the chart on Sleep (the tour opens the tab), its tile's title, the life factors on
# Today (scrolled to), the gear, and the demo's ending with Add my device and Keep looking
if tap_text "Show the tour again"; then tour_shot tour-again "1 of 7"
else echo "::error::issue #453 step: Show the tour again was not on the demo's Settings page"; TOUR_FAIL=1; fi
launch --ei tour 1;                         tour_shot tour-scores "2 of 7"
launch --ei tour 3;                         tour_shot tour-chart "4 of 7"
launch --ei tour 4;                         tour_shot tour-tile "5 of 7"
launch --ei tour 5;                         tour_shot tour-life "6 of 7"
launch --ei tour 6;                         tour_shot tour-settings "7 of 7"
launch --ei tour 7;                         tour_shot tour-end "Keep looking"
# every section folded, as every time Settings opens (owner's wishes of 3 and 17 November: a title, one status line and a
# chevron each); stages, watch, period and the others below open their own section first
launch --es settings blocks;                shot settings-blocks
launch --es settings stages;                shot settings-stage-detail
launch --es settings ring;                  shot settings-ring   # where a tap on Today's ring battery row leads
# Count phone use as awake switched off and on (issue #204): when the nights are analysed again the
# preview above redraws, its phone line goes away and comes back without leaving the page. The card
# opens at the top with the switch below the screen: one slow swipe (no fling) brings both into view,
# and short ones after it while the switch is still below (the closed Watch and Phone blocks and the Period tracker sit in
# between). The Screen time section is closed like every section (owner's wish of 17 November): its header is tapped open
# once it is on screen.
adb shell input swipe 30 1700 30 900 1500; sleep 1
phone_line() { ui_dump | grep -q "on the phone, shown as awake"; }
switch_shown() { ui_dump | grep -o 'text="Count phone use as awake"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tail -1 | { read b; [ -n "$b" ] && [ "$b" -lt "${SCREEN_H:-2400}" ]; }; }
for i in 1 2 3 4 5 6; do
  switch_shown && break
  if ui_dump | grep -q 'content-desc="Screen time, [^"]*closed, tap to open"' && touch_desc "Screen time, " 50; then sleep 1; continue; fi
  adb shell input swipe 30 1500 30 1250 1000; sleep 1
done
tap_phone_switch() {  # fails when the switch is not listed or its middle is off the screen
  local b
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
# the period tracker's reminder and "My periods have stopped" (issue #252, phase 1a2); the reminder follows the real log only
launch --es settings period;                shot settings-period
# the card starts with Prediction (issue #383): Best for you and each method, and which one Best for you uses now; the
# reminder below it gets its own shot
scroll_to "Remind me before my period" 8 || echo "::warning::issue #383 step: the reminder was not found below Prediction"
shot settings-period-remind 1
launch --es settings games;                 shot settings-games
launch --es settings backups;               shot settings-backups   # automatic backups, the demo's example (issue #242)
# The Device check (issue #251, phase 1a, once the Watch check; issue #418): the Settings card with its row Check a ring or
# watch, then in demo mode the made up scan (never Bluetooth, a watch, a QRing ring and an Oura ring among it) and the made up report,
# built by the same code as a real one (the screen scrolls to it, copy buttons included)
launch --es settings watch;                 shot settings-watch
launch --ez watch true;                     shot watch 3
launch --ez watch true --ez watch_report true; shot watch-report 3   # scrolls to the report itself: copy buttons, Use this watch for syncing (phase 1b)
# the made up QRing ring checked (issue #418, phase 1): its report without messages and the line of its family
launch --ez watch true --ez device_ring true; shot device-ring 3
# the made up Oura ring checked (issue #458): the check leaves the ring Open Ring reads alone, two lines, no report, no Copy
launch --ez watch true --ez device_oura true; shot device-oura 3
# The Watch page (issue #251, phase 1b): the demo's made up watch, decoded by the same code as a real one: steps per
# day, workouts, heart rate, Withings' own sleep and the battery
# The watch setup (issue #251): every step with its state, what is sent from the profile, then the alarm, then the hand
# calibration's controls (demo mode: a made up watch, every step done, nothing sent)
launch --ez watch_setup true;               shot watch-setup 3
launch --ez watch_setup true --ei watch_setup_part 1; shot watch-setup-alarm 3
launch --ez watch_setup true --ei watch_setup_part 2 --ez watch_setup_hands true; shot watch-setup-hands 3
launch --ez watch_data true;                shot watch-data 4
swipe_up;                                   shot watch-data-heart 1   # the heart rate curve
swipe_up; swipe_up;                         shot watch-data-sleep 1   # sleep and battery at the end of the page
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
# A saved ring key this phone cannot read (issue #199): the status line says so and leads to Setup. key_unreadable
# only shows the line in demo mode, nothing is stored or sent to a ring
launch --ez key_unreadable true --ei tab 0; shot today-key-unreadable
# One set of numbers (issue #251, main device): the demo's ring is on its charger yesterday 14:00 to 16:00 and the made up
# watch fills that time. Activity names the steps from the watch (with the watch's workouts below), Body the heart rate
# from the watch; settings-watch above shows the Main device chips with Ring picked. Then with the watch as main device
# (extra main_device, demo mode only): Settings with Watch picked, and Sleep with Withings' own night and the line naming
# the ring's night. Back to Ring after, so the shots below stay as they were
FILL_DAY=$(date -d '1 day ago' +%F)
launch --ei tab 3 --es day "$FILL_DAY"; sleep 3
scroll_to "GOAL PROGRESS" 6 || echo "::warning::issue #251 step: the Goal progress card was not found on Activity"
shot activity-watch-fill 1
launch --ei tab 4 --es day "$FILL_DAY"; sleep 3
scroll_to "HEART RATE TODAY" 12 || echo "::warning::issue #251 step: the Heart rate today card was not found on Body"
shot body-watch-fill 1
launch --es settings watch --ei main_device 1; shot settings-main-watch
launch --ei tab 1 --ei main_device 1;       shot sleep-watch-main
swipe_up;                                   shot sleep-watch-main-stages 1
launch --ei tab 0 --ei main_device 0;       sleep 3
# Only a watch (issue #373, extra devices 1, demo mode only, nothing stored): Today with the dials of the scores from the
# watch (issue #379) and the watch's day; Ready, Sleep and Activity with the watch's score, then each one's Contributors
# and Left out (what the score could not use and why)
launch --ei tab 0 --ei devices 1;           shot today-watch-only
first_screen watch
launch --ei tab 2 --ei devices 1;           shot ready-watch-only
launch --ei tab 2 --ei devices 1 --es card CONTRIBUTORS; sleep 3
scroll_to "CONTRIBUTORS" 6 || echo "::warning::issue #379 step: Contributors was not found on Ready with only the watch"
shot ready-watch-only-parts 1
launch --ei tab 1 --ei devices 1;           shot sleep-watch-only
launch --ei tab 1 --ei devices 1 --es card CONTRIBUTORS; sleep 3
scroll_to "CONTRIBUTORS" 10 || echo "::warning::issue #379 step: Contributors was not found on Sleep with only the watch"
shot sleep-watch-only-parts 1
launch --ei tab 1 --ei devices 1 --es card DETAILS; shot sleep-watch-only-details 2   # Total sleep first (issue #409)
launch --ei tab 3 --ei devices 1;           shot activity-watch-only
launch --ei tab 3 --ei devices 1 --es card CONTRIBUTORS; sleep 3
scroll_to "CONTRIBUTORS" 14 || echo "::warning::issue #379 step: Contributors was not found on Activity with only the watch"
shot activity-watch-only-parts 1
# Only the phone (extra devices 2): Today with the Activity and Screen dials, Activity with the score from the phone's steps
launch --ei tab 0 --ei devices 2;           shot today-phone-only
first_screen phone
launch --ei tab 3 --ei devices 2;           shot activity-phone-only
# Steps from this phone (issue #329, phase 1b): the demo's made up phone readings (in the demo file only) as light bars under
# the ring's steps per hour on Activity, then touched in the afternoon (the readout names the ring's hour and the phone's
# interval there); Activity's card that asks for Physical activity (extra phone_ask, demo mode only, nothing is asked); the
# Phone block in Settings with the Main device chips (Ring, Watch, Phone); the phone as main device (extra main_device 2):
# Activity with the phone's own steps. Back to Ring after, so the shots below stay as they were
tap_desc_at() {  # $1 the start of a content description, $2 percent of its width from the left: taps there, in the middle
  local b
  b=$(ui_dump | grep -o "content-desc=\"$1[^\"]*\"[^>]*bounds=\"[^\"]*\"" | grep -v -e 'tap to close"' -e 'tap to open"' | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b $2
  [ $# -eq 5 ] || return 1
  local y=$(( ($2 + $4) / 2 ))
  [ "$y" -gt 0 ] && [ "$y" -lt $(( ${SCREEN_H:-2400} - 450 )) ] || return 1
  adb shell input tap $(( $1 + ($3 - $1) * $5 / 100 )) $y
}
launch --ei tab 3 --es day "$FILL_DAY"; sleep 3
scroll_to "STEPS FROM THIS PHONE" 10 || echo "::warning::issue #329 step: the phone's card was not found on Activity"
shot activity-phone 1
if tap_desc_at "Steps through the day" 62; then shot activity-phone-touch 1
else echo "::warning::issue #329 step skipped: the phone's day line was not on screen to touch it"; fi
launch --ei tab 3 --ez phone_ask true; sleep 3
scroll_to "STEPS FROM THIS PHONE" 10 || echo "::warning::issue #329 step: the card that asks for Physical activity was not found on Activity"
shot activity-phone-ask 1
launch --es settings phone;                 shot settings-phone
launch --ei tab 3 --ei main_device 2 --es day "$FILL_DAY"; shot activity-phone-main
launch --ei tab 0 --ei main_device 0;       sleep 3
# The owner's first real watch data (issue #251, 11 November): with only the watch (extra devices 1, demo mode only),
# Activity's Steps through the day from the watch's records, then touched (the hour and its steps); Sleep with the watch's
# night (awake, REM, light, deep) and its heart rate under the stages, touched (time, stage and the nearest reading)
launch --ei tab 3 --ei devices 1; sleep 3
scroll_to "STEPS THROUGH THE DAY" 8 || echo "::warning::issue #251 step: Steps through the day was not found on Activity"
shot activity-watch-steps 1
if tap_desc_at "Watch steps through the day" 40; then shot activity-watch-steps-touch 1
else echo "::warning::issue #251 step skipped: the watch's steps chart was not on screen to touch it"; fi
launch --ei tab 1 --ei devices 1; sleep 3
scroll_to "SLEEP DEPTH THROUGH THE NIGHT" 4 || echo "::warning::issue #251 step: the watch's night was not found on Sleep"
shot sleep-watch-heart 1
if tap_desc_at "Sleep stages and heart rate" 30; then shot sleep-watch-heart-touch 1
else echo "::warning::issue #251 step skipped: the watch's night chart was not on screen to touch it"; fi
# The statistics without a ring (owner's report of 13 November): with only the watch (extra devices 1, demo mode only,
# nothing stored) each tab shows its 7 and 30 day charts from the watch. The extra card (demo mode only, underscores for
# spaces) opens the page scrolled to a text, so these shots need no swipes. The demo's watch is like the owner's Steel HR
# and sends no REM, so its nights show Light and REM as one stage (one row, no REM part in the score). Sleep: the stage
# list with Light and REM, sleep debt and the 7 day chart, the sleep times touched on a night, Further analysis of the
# watch's nights; Ready, Activity (steps touched, workouts) and Body their charts; Activity with only the phone its steps
launch --ei tab 1 --ei devices 1 --es card "Light_and_REM,_and_deep"; shot sleep-watch-light-rem
launch --ei tab 1 --ei devices 1 --es card SLEEP_DEBT;             shot sleep-watch-only-stats
launch --ei tab 1 --ei devices 1 --es card SLEEP_TIMES; sleep 3
if tap_desc_at "Sleep times, last 30 days" 75; then shot sleep-watch-only-times 1
else echo "::warning::watch statistics step skipped: the sleep times chart was not on screen to touch it"; fi
launch --ei tab 1 --ei devices 1 --ez analysis true --ei period 1 --es card Further_analysis; sleep 5
scroll_to "AVERAGE NIGHT" 4 || echo "::warning::watch statistics step: Further analysis of the watch's nights was not found"
shot sleep-watch-only-analysis 1
launch --ei tab 2 --ei devices 1 --es card LAST_NIGHT;             shot ready-watch-only-stats
launch --ei tab 3 --ei devices 1 --es card "STEPS,_LAST_7"; sleep 3
if tap_desc_at "Steps, last 7 days" 50; then shot activity-watch-only-stats 1
else echo "::warning::watch statistics step: Steps, last 7 days was not on screen to touch it"; shot activity-watch-only-stats 1; fi
launch --ei tab 3 --ei devices 1 --es card "WORKOUTS,_LAST_30";    shot activity-watch-only-workouts
launch --ei tab 4 --ei devices 1 --es card "RESTING_HEART_RATE,_30"; shot body-watch-only-stats
launch --ei tab 3 --ei devices 2 --es card "STEPS,_LAST_7";        shot activity-phone-only-stats
# Phone steps, phase 2 (issue #329): with only the phone, calories from walking (an estimate, how and how far off under it), the
# card that lists the last 7 days to hold next to the phone's own health app, and the Badges page whose rules say what counts
# with the phone (Goal reached by the step goal, the sleep badges need a ring or a watch)
launch --ei tab 3 --ei devices 2 --es card CALORIES_FROM;           shot activity-phone-calories
launch --ei tab 3 --ei devices 2 --es card COMPARE_WITH;            shot activity-phone-compare
launch --ez badges true --ei devices 2;     shot badges-phone-only 4
swipe_up; swipe_up; swipe_up;               shot badges-phone-only-kinds 1
# The Screen tab with only the watch (owner's report of 14 November, extra devices 1, demo mode only): the 14 days with the
# watch's nights in purple and the green first look, and the day's numbers (first look, before bed, in the night) from them
launch --ei tab 5 --ei devices 1 --es card LAST_14_DAYS;            shot screen-watch-only-days
launch --ei tab 5 --ei devices 1 --es card THIS_DAY;                shot screen-watch-only-numbers
# A time left out because someone else wore the ring (issue #254), last: the example changes the demo's results for
# every shot after it. left_out leaves out the day before yesterday 13:00 to 17:00, only in demo mode (the demo file),
# then the analysis runs again: the Body tab shows the grey band on Heart rate today, Today the Left out card, and
# Settings, Advanced the time in its list
LEFT_DAY=$(date -d '2 days ago' +%F)
launch --ez left_out true --ei tab 4 --es day "$LEFT_DAY"; shot body-left-out 10
launch --ei tab 0 --es day "$LEFT_DAY";     shot today-left-out
launch --es settings advanced;              shot settings-advanced
# A day off the finger (issue #240), after the left out example for the same reason: unworn leaves out the day before
# yesterday 00:00 to 21:30 in the demo file (under an hour of ring data is left; the night that ends yesterday morning
# starts later and stays whole), then the analysis runs again. Readiness of yesterday, pinned so the shot does not depend
# on the time CI runs, leaves Previous day activity out and says why in a card above Contributors
launch --ez unworn true --ei tab 2 --es day "$(date -d '1 day ago' +%F)"; sleep 12
scroll_to "Previous day activity is left out" 8 || echo "::warning::issue #240 step: the unworn card was not found on Ready, readiness-unworn shows where the swipes ended"
shot readiness-unworn 1

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
if [ "$TOUR_FAIL" = 1 ]; then
  echo "::error::the short tour did not show where it should (see the lines above)"
  exit 1
fi
if [ "$FIRST_CUT" = 1 ]; then
  echo "::error::Today's sentence and line need scrolling on the first screen (see the lines above)"
  exit 1
fi
if [ "$DIRTY" = 1 ]; then
  echo "::error::some screenshots show a system dialog; not publishing them as README pictures"
  exit 1
fi
