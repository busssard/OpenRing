# Open Ring

An Android app for the Oura Ring that works without the Oura app, account or subscription.
It reads the ring over Bluetooth and does all the math on your phone. It has no internet access,
so your data stays with you.

Hobby project, built on the reverse engineering work of [open_oura and open_health](#credits).
Not affiliated with Oura, not a medical device.

[![Download](https://img.shields.io/github/v/release/busssard/OpenRing?label=download&color=7c8cff)](https://github.com/busssard/OpenRing/releases/latest)
[![Build](https://github.com/busssard/OpenRing/actions/workflows/build.yml/badge.svg)](https://github.com/busssard/OpenRing/actions/workflows/build.yml)
![Internet access: none](https://img.shields.io/badge/internet%20access-none-3fb950)

<p>
<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/today.png" width="45%" alt="Today">&nbsp;<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/sleep.png" width="45%" alt="Sleep">
<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/sleep-analysis.png" width="45%" alt="Average night">&nbsp;<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/body-trends.png" width="45%" alt="Body trends">
</p>
<sub>Demo data on an Android emulator, captured automatically for every release.</sub>

## What it does

* Sleep stages, night timeline, your last nights and your average night
* Readiness, activity, heart rate, HRV, temperature, blood oxygen
* Live heart rate and blood oxygen on demand
* A demo mode, so you can look around without a ring

## Install

Android 8.0 or newer.

1. Download the APK from the [latest release](https://github.com/busssard/OpenRing/releases/latest) on your phone and open it.
2. Allow your browser to install apps when Android asks, then tap **Install**.
3. If Play Protect warns you (it does for anything not from the Play Store): **More details**, then **Install anyway**.

Updates install over the old version and keep your data.

<details>
<summary>Is it safe?</summary>

* The app has no internet permission, so Android blocks it from sending anything anywhere.
  [A check](tests/check_privacy.sh) in every build makes sure that never changes.
* Every release APK is built by GitHub from this code after the tests pass. The release notes list
  its checksum, signing certificate and a link to the build, and it has a
  [provenance attestation](https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations)
  (`gh attestation verify OpenRing-x.y.apk -R busssard/OpenRing`).
* Each change is tested on an Android 14 emulator ([results](https://github.com/busssard/OpenRing/actions/workflows/emulator.yml))
  and scanned by [CodeQL](https://github.com/busssard/OpenRing/actions/workflows/codeql.yml).
* All releases share one signing key, so nobody else can push an update to your phone.

More in [SECURITY.md](SECURITY.md).
</details>

<details>
<summary>Google's upcoming install rules</summary>

Google is starting developer verification for apps outside the Play Store (some countries from
September 2026, worldwide planned for 2027). Unverified apps will then need a one time setting in the
developer options. This page will explain the steps if it comes to that.
</details>

## Getting started

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


---

# For developers

## Build it yourself

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


Tests that run on any JDK: `./tests/run_tests.sh` (protocol and algorithms), `./tests/run_pipeline.sh`
(storage, dating, analysis, backup and demo data on the JVM) and `./tests/check_privacy.sh`.
Contributing with Claude or by hand: see [CLAUDE.md](CLAUDE.md) for the rules of the repository.

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
