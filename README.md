<div align="center">

# Open Ring

**Your Oura Ring, your phone, your data.**

A free Android app that reads your Oura Ring directly and works out your sleep, readiness,
activity and body signals right on your phone. No account, no subscription, no cloud.

[![Latest release](https://img.shields.io/github/v/release/busssard/OpenRing?label=download&color=7c8cff)](https://github.com/busssard/OpenRing/releases/latest)
[![Tests and build](https://github.com/busssard/OpenRing/actions/workflows/build.yml/badge.svg)](https://github.com/busssard/OpenRing/actions/workflows/build.yml)
[![Android emulator test](https://github.com/busssard/OpenRing/actions/workflows/emulator.yml/badge.svg)](https://github.com/busssard/OpenRing/actions/workflows/emulator.yml)
[![CodeQL](https://github.com/busssard/OpenRing/actions/workflows/codeql.yml/badge.svg)](https://github.com/busssard/OpenRing/actions/workflows/codeql.yml)
![No internet permission](https://img.shields.io/badge/internet%20access-none-3fb950)
![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
[![MIT license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/today.png" width="19%" alt="Today"> <img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/sleep.png" width="19%" alt="Sleep">
<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/sleep-analysis.png" width="19%" alt="Your average night"> <img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/readiness.png" width="19%" alt="Readiness">
<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/body-trends.png" width="19%" alt="Body trends">

<sub>Screenshots are taken automatically on an Android emulator with the app's demo data (a made up person).</sub>

</div>

## Why Open Ring

* **Private by design.** The app has no internet access at all, Android itself blocks it. Your nights,
  heart rate and temperature never leave your phone unless you export them yourself.
* **Everything the ring measures, explained.** Sleep stages through the night, a timeline of when you fell
  asleep and woke up, readiness with plain language reasons, heart rate, HRV, skin temperature, blood
  oxygen, breathing, activity and calories.
* **See your patterns.** Browse your last nights at a glance, and see your average night over a week,
  month, year or all time: when you usually fall asleep, when deep sleep happens, how regular you are.
* **Live on demand.** Heart rate, HRV and blood oxygen right now, with a breath hold timer for
  freediving practice.
* **Just curious?** The app has a demo with five weeks of made up data. Install it and tap
  *Explore with demo data*, no ring needed.
* **Free and open.** Every line of code is here to read. No ads, no tracking, no subscription.

> Not affiliated with Oura Health. Not a medical device. Scores are close to Oura's but not identical.

## Install

You need an Android phone with Android 8.0 or newer.

1. On your phone, open the [latest release](https://github.com/busssard/OpenRing/releases/latest) and tap
   **OpenRing-x.y.apk** to download it.
2. Open the downloaded file. Android asks whether your browser may install apps: tap **Settings**, switch on
   **Allow from this source**, then go back and tap **Install**.
3. **Google Play Protect may warn you** because the app does not come from the Play Store. That warning is
   about where the app comes from, not about anything found in it. Tap **More details**, then
   **Install anyway** (on some phones the button reads *Install without scanning*).
4. Open Open Ring. Updates install the same way, over the old version, keeping your data.

<details>
<summary><b>Why can I trust this download?</b></summary>

* **It cannot send your data anywhere.** The app does not ask for internet access, so Android does not let it
  connect to anything. Every build checks this automatically and fails if it ever changes
  ([tests/check_privacy.sh](tests/check_privacy.sh)).
* **Built in public.** Every APK on the release page is built by GitHub from the code you see here, after the
  tests passed. The release notes link the exact build, the file's SHA-256 checksum and the signing
  certificate, and the build carries a signed [provenance attestation](https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations)
  you can check with `gh attestation verify OpenRing-x.y.apk -R busssard/OpenRing`.
* **Tested on Android.** Each change is installed on an Android 14 emulator that opens every screen and fails
  on any crash; the screenshots above come from that test.
* **Scanned.** GitHub's CodeQL security scanner checks the code on every change.
* **Same signature for every update.** Android only installs an update over Open Ring if it carries the same
  signing key, so nobody else can push a fake update to your phone.

More in [SECURITY.md](SECURITY.md).
</details>

<details>
<summary><b>Coming changes to installing apps outside the Play Store</b></summary>

Google is introducing developer verification for Android apps. It starts in a few countries from
September 2026 and is planned worldwide for 2027. Apps from developers that are not verified will then need a
one time setting (an "advanced flow" in the developer options, with a waiting time) before they can be
installed. If this affects you, this page will explain the steps.
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


## What you get

| Tab | What you see |
| --- | --- |
| Today | Readiness, Sleep and Activity scores, a one line summary of your day, key numbers, live heart rate |
| Sleep | Sleep depth through the night (touch it to see times), night timeline, your last 30 nights, heart rate, HRV and blood oxygen through the night, sleep debt, and *Further analysis*: your average night for a week, month, year or all time |
| Readiness | Score with the reasons behind it, temperature deviation, HRV and resting heart rate trends |
| Activity | Score, goal progress, estimated steps and distance, calories, intensity through the day |
| Body | Latest readings, daytime heart rate, symptom radar, cardio capacity estimate, 30 day trends |
| Live | Heart rate, HRV, blood oxygen and temperature right now, breath hold timer |
| Play | Small games steered by tilting your hand (where the ring firmware streams motion), touch steering otherwise |
| Settings | Ring and battery, measurements on the ring, profile and goals, key and backup, export, data check |

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
