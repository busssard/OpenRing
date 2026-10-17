# Changelog

## 1.21
* New icon from the owner's sketch: a ring seen slightly from above, the infinity sign cut into its band,
  in one plain colour (the app's deep sleep blue). Also the notification icon and the themed icon on Android 13

## 1.20
* Screen time counts while the phone is unlocked and the screen is on, close to the ScreenTime app. The lock
  screen lighting up for a message, a look at the clock or a screen saver behind the lock screen no longer
  count, so the total is much closer to other screen time apps. Phones without a lock screen count as before. The last week is counted again with the new rule;
  older days keep the old numbers
* Touch the clock on the Screen tab and slide around it to see the times: when each use started and ended
  and which app was in front
* "Turned on" is now "Uses"
* Each use can show as at least 5, 10 or 15 minutes on the clock and the day rows, so short looks do not
  flicker (default 5). The totals and the score always use the real minutes
* Apps: when another app comes to the front, the one before counts as gone to the back, also if the phone
  did not log it. A new line shows time with no app in front (home screen, system screens)

## 1.19
* Syncing stays quick with a long history: after each sync only the events whose time changed are written
  again, instead of all of them. With a year of data this part is about 5 times faster (#21)
* Live, games and the ring buttons in Settings no longer wait while a sync analyses your nights: the ring is
  free as soon as the download ends. The status still says "Analysing" until the new nights are ready (#21)

## 1.18
* Restoring a full backup no longer replaces newer history without asking. If this phone has nights the
  backup lacks, the app asks first, and "Key and settings only" keeps the history on the phone (#26)
* A backup with a damaged history, or one made by a newer Open Ring, is refused with a clear message and
  nothing on the phone changes. Before the history is swapped the old one is kept until the new one opens (#27)

## 1.17
* Re analysing all history no longer loses nights and days older than 400 days. All nights come back as long
  as their ring data is still stored, and changing the sleep stage detail now reaches the oldest nights too (#20)
* With a long history a re analysis can take a few minutes; the message says so. If it fails, Settings
  shows why instead of closing the app (#20)

## 1.16
* A thin orange line on the sleep chart shows where the clocks changed during the night (daylight saving),
  with one line below that says what changed. Sleep times always count the real hours (#22)
* Demo mode: bedtimes and wake times no longer jump by one hour after the clocks change (#22)

## 1.15
* Screen time gets its own tab (Screen) in place of the game tab: a screen score, the day as a clock dial
  next to your sleep, how often and how long you used the phone, the first look after waking, and the
  last 14 days as rows
* A fourth ring on Today: the screen score (screen time against your goal, phone use before bed and at night)
* Apps can be left out of screen time, for example maps while navigating (Screen tab, Apps on this day).
  Times per app start about a week before this version was installed
* Ring games moved to Settings (#15): a button tests whether your ring sends its movement, a switch shows the games

## 1.14
* New app icon: a ring carrying a small infinity sign, because the ring keeps working for good without any cloud

## 1.13
* Screen time (#13): how long and when this phone's screen was on, with a daily goal, on the Activity page.
  It needs Android's usage access (Settings, Screen time) and Android 9 or newer; it stays on the phone
* Phone use counts as awake in the sleep analysis: a night no longer starts while you use the phone, and
  checking the phone at night shows as a wake up. Can be switched off (Settings, Screen time), for example
  if you fall asleep with a video playing
* Android keeps about one week of screen history, so screen time starts about a week before you allow access

## 1.12
* Settings in a new order: updates at the top, then ring, profile and sleep stage detail; rarely used and risky
  actions at the end (#14)
* Smaller buttons in Settings, pairs side by side; long labels wrap instead of overlapping
* Profile: age, weight and height in one row, sex picked with a tap instead of typing a number
* Measurements on the ring: one row each, with a button that shows On or Off and switches it

## 1.11
* Sleep stage detail (Settings): choose how finely the sleep depth chart is drawn. Detailed shows every change,
  Balanced (new default) joins stages shorter than 3 minutes with the stage next to them, Calm draws long blocks
  similar in style to the official app. Being awake for 1 minute or more always stays in Balanced. A preview shows your last night
  with each choice. This changes the detail, not how accurate the stages are (#9)
* After updating, all nights are analysed again once with the new default

## 1.10
* Stepping through days with the arrows keeps your scroll position, so you can compare a chart day by day (#6)
* Settings shows the installed version with a Check for updates button that opens the releases page (#7)
* Body: clearer note on how the night time trends fill up

## 1.9
* Demo mode: explore every screen with five weeks of made up data, no ring needed (setup screen, or leave it in Settings).
  It uses its own database, your real data stays untouched
* Every change is now installed on an Android 14 emulator that opens all screens and fails on any crash;
  the README screenshots come from that run
* Releases list their SHA-256, signing certificate and build provenance, and every build checks that the app
  has no internet permission and no tracking code
* New README for everyone, with install help for the Play Protect warning

## 1.8
* Recent nights on the Sleep page: the last 30 nights as small stage bars with their score; tap one to open it
* Further analysis (fold out at the end of the Sleep page): your average night for the last week, month, year or all time,
  showing for each time of night how often you are in deep, light, REM or awake, plus typical bedtime, wake time,
  stage minutes, efficiency, wake ups, lowest heart rate, HRV and how regular your bedtime is

## 1.7
* Clear key field with a Paste button on the setup screen
* Backup and restore: key and settings, optionally with all history; a text file holding just the key works too
* Play is a normal tab with the bottom bar; each game opens full screen, back returns to the tab
* Settings regrouped (ring, measurements, profile, key and backup, export, help) with quick jump chips and a gear icon

## 1.6
* Touch or slide along any chart to read time and value; the sleep depth chart shows stage and its duration
* Night timeline: falling asleep, wake ups of 5 minutes or more, lowest heart rate, final wake up
* 30 day charts show dots per night and fit their time range to the data
* A night no longer stretches into morning time the ring itself marks as awake

## 1.5
* The main screen follows the newest day, so the morning sync shows last night
* Game mode releases the ring when you leave it; syncs wait briefly instead of failing

## 1.4
* Live heart rate, HRV and blood oxygen read from the ring's own measurements every few seconds

## 1.0 to 1.3
* First versions: pairing, history sync, sleep staging, scores, Body, Live and Play
