# Open Ring

A local first Android companion for the Oura Ring (built for Ring 4, also speaks to Gen 3 Horizon and Ring 5).
It talks to the ring directly over Bluetooth, stores the raw data in a database on your phone and computes
everything on the phone: sleep stages, Sleep, Readiness and Activity scores, heart rate, HRV, skin temperature,
blood oxygen, breathing rate, sleep debt, a symptom radar and a cardio capacity estimate.
No account, no cloud, no subscription, no third party libraries.

> Not affiliated with Oura Health. Not a medical device. Use at your own risk.

**Download:** the newest APK is on the [Releases page](https://github.com/busssard/OpenRing/releases).

## Install

**Option A, ready made APK:** download it from the [Releases page](https://github.com/busssard/OpenRing/releases) on your phone and open it (allow "install unknown apps"
for your file manager or browser when Android asks). Android 8.0 or newer.

**Option B, build it yourself**

* *Android Studio:* open this folder, let it sync, then *Build > Build APK(s)*.
* *Command line with Gradle:* `gradle assembleRelease` (or create a wrapper first with `gradle wrapper`).
  The APK lands in `app/build/outputs/apk/release/`.
* *No Gradle at all:* `./build_apk.sh` builds and signs `build/OpenRing.apk` with just the Android build
  tools (aapt2, d8 or dx, zipalign, apksigner) and an `android.jar`. On Ubuntu:
  `sudo apt install aapt apksigner zipalign dalvik-exchange` and point `ANDROID_JAR` at an API 34 `android.jar`.
* *GitHub:* push the folder to a repository; the included workflow (`.github/workflows/build.yml`) runs the
  tests and attaches the APK to the run.

Run the protocol and analysis tests on any JDK: `./tests/run_tests.sh`.

## First start

1. Allow Bluetooth access.
2. Enter age, sex, weight and height (used for calories, goals and age adjusted sleep targets).
3. Connect the ring. It only talks to apps that know its 16 byte pairing key. Two ways:
   * **Use the key from the official app** (the official app keeps working too).
     iPhone: make an unencrypted local backup and read `assa.sqlite` (table `ringconfiguration`, column `auth_key`).
     Rooted Android: `tools/android_oura_key_extract.py` from the open_oura project.
     Or paste a key you backed up from this app (tick "Install this key" if the ring was reset since).
   * **Restore from an Open Ring backup** (new phone or reinstall): Settings, Key and backup saves a zip with
     the key and settings, optionally with all history; the setup screen restores it. A text file holding only
     the key works too. The backup contains the key in readable form, keep it private.
   * **Pair a factory reset ring.** The app creates a new key. The official app then stops working until the
     ring is reset again. Reset on the Gen 3 or Ring 4 dock without a button: put the ring on the dock and wait
     2 seconds, flip the dock upside down until blue, upright until red, upside down until purple, upright until
     yellow (reset started); blinking blue means done.
4. Back up the key the app shows you. It cannot be read back from the ring.

Keep the ring on its charger near the phone for the first sync, which copies the ring's whole history and can
take a while. Close the official Oura app during syncs, the ring accepts a single Bluetooth link.
Afterwards the app syncs when you open it and about every three hours in the background.

## What you get

| Screen | Contents |
| --- | --- |
| Today | Readiness, Sleep and Activity rings, a plain language summary, key metrics, symptom radar, live heart rate |
| Sleep | Score and contributors, hypnogram (awake, REM, light, deep), time in bed, efficiency, latency, wake ups, cycles, night heart rate and HRV curves, blood oxygen, sleep debt, naps, 7 day chart |
| Readiness | Score and contributors, temperature deviation, 30 day HRV and resting heart rate |
| Activity | Score and contributors, goal progress, estimated steps and distance, active and total calories, intensity minutes, hourly intensity, 7 day chart |
| Body | Daytime heart rate, live heart rate, symptom radar, cardio capacity estimate, 30 day trends |
| Live | Heart rate and HRV from live beats, blood oxygen on demand (experimental), sensor temperature, breath hold timer with holds marked on the charts |
| Play | A tab like the others; each game opens full screen and back returns to the tab. Game mode: the ring's accelerometer turns hand tilt into a joystick. Asteroid Dodge, Orb Catcher and a motion check; add a game by writing one `Game` subclass (`ui/games`) |
| Settings | Ring info and battery, measurement switches (daytime HR, SpO2), profile and goals, key backup, CSV and database export, diagnostics, factory reset |

## Troubleshooting: synced, but nothing shows

Open *Settings > Data check and diagnostics*. The **Data check** follows the data from the ring to the
screen and marks the step where it stops:

1. **Ring link**: events were copied from the ring.
2. **Measurement data**: the streams the analysis needs are present (heart rate, HRV, activity, sleep).
3. **Placing data in time**: the ring's clock is only a counter; it becomes a date through a time record.
   A sync that never finished can leave everything undated. Sync again with the ring nearby and let it finish.
4. **Analysis**: nights and days were computed.

*Repair* re-dates and re-analyses everything already stored, without a new sync. The data check holds counts
and dates only, so it is safe to copy and share in a bug report. The *Log* tab also shows the raw frames of
a live heart rate session.

Tests that run without a phone: `./tests/run_tests.sh` (protocol and algorithms) and
`./tests/run_pipeline.sh` (a simulated three day ring history through storage, dating and analysis,
including an interrupted first sync).

## How it works

```
ring ──BLE──> GattLink ──> RingClient (auth, time sync, history drain) ──> SQLite (raw events, dated)
                                                                          │
                       Analyzer: NightFinder ─> Stager ─> NightAnalyzer ─> Scores ─> nights and days tables ─> UI
```

* `app/src/main/java/app/openring/core` is plain Java with no Android imports (tested on the JVM):
  protocol framing, AES nonce auth, the extended and legacy history drain with its reboot and replay
  safeguards, event decoders, sleep detection and staging, scoring.
* `data` holds the database, event dating (ring time is a per boot decisecond counter, mapped to wall
  clock time with the ring's own time sync events plus phone anchors) and the analysis runner.
* `ble` is the Android Bluetooth layer (bonding, MTU, serialized GATT writes) and the sync orchestration.
* `ui` is built in code with hand drawn charts.

### Honest limits

* **Scores are close, not identical.** Oura computes its scores with tables and models that are not public.
  This app uses the same contributors and the combiner weights open_health recovered from Oura's engine
  (Sleep 35/15/10/10/10/10/10 for total sleep, restfulness, efficiency, REM, deep, latency and timing;
  Readiness and Activity weights from the same work), with transparent contributor curves based on published
  sleep science. Baseline driven contributors become personal after about 14 nights.
* **Sleep stages.** Ring 4 and 5 do not stage sleep on the ring; the official app uses a proprietary neural
  network. When the ring provides its own hypnogram it is used, otherwise stages are estimated from heart rate,
  HRV and movement with open_health's model free stager. Sleep versus wake is reliable, the deep and REM split
  is an estimate.
* **Steps** are estimated from activity intensity (the ring's own step decoder is a proprietary model).
* **Not ported:** workout auto detection, cycle insights, cardiovascular age, firmware updates.
* Built and unit tested here, but not yet exercised against every ring and firmware combination. The
  diagnostics screen (Settings) shows a copyable log for bug reports.

## Credits

Protocol, decoders and algorithms are ported from
[open_oura](https://github.com/Th0rgal/open_oura) and [open_health](https://github.com/Th0rgal/open_health)
(MIT, Thomas Marchand), cross checked with the
[ringverse Ring 4 BLE notes](https://github.com/ringverse/protocol/blob/main/oura/BLE.md),
[OpenStrap](https://github.com/OpenStrap/protocol), [BrightOura](https://github.com/gi-os/BrightOura)
and the Gadgetbridge Ring 4 work in progress. MIT licensed, see LICENSE.
