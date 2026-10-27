<img src="docs/images/icon.png" width="96" align="right" alt="Open Ring icon: a ring with an infinity sign cut into its band">

# Open Ring

An Android app for the Oura Ring that works without the Oura app, account or subscription.
It reads the ring over Bluetooth and does all the math on your phone. It has no internet access,
so your data stays with you.

Hobby project, built on the reverse engineering work of [open_oura and open_health](#credits-and-license).
Not affiliated with Oura, not a medical device.

[![Download](https://img.shields.io/github/v/release/busssard/OpenRing?label=download&color=7c8cff)](https://github.com/busssard/OpenRing/releases/latest)
[![Tests](https://img.shields.io/endpoint?cacheSeconds=300&url=https%3A%2F%2Fraw.githubusercontent.com%2Fbusssard%2FOpenRing%2Fbadges%2Ftests.json)](https://github.com/busssard/OpenRing/blob/badges/summary.md)
[![Coverage](https://img.shields.io/endpoint?cacheSeconds=300&url=https%3A%2F%2Fraw.githubusercontent.com%2Fbusssard%2FOpenRing%2Fbadges%2Fcoverage.json)](https://github.com/busssard/OpenRing/blob/badges/summary.md)
[![Screenshots](https://img.shields.io/endpoint?cacheSeconds=300&url=https%3A%2F%2Fraw.githubusercontent.com%2Fbusssard%2FOpenRing%2Fbadges%2Fscreenshots.json)](https://github.com/busssard/OpenRing/actions/workflows/emulator.yml)
![Internet access: none](https://img.shields.io/badge/internet%20access-none-3fb950)

<p>
<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/today.png" width="45%" alt="Today">&nbsp;<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/sleep.png" width="45%" alt="Sleep">
<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/sleep-analysis.png" width="45%" alt="Average night">&nbsp;<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/body-trends.png" width="45%" alt="Body trends">
<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/readiness.png" width="45%" alt="Readiness">&nbsp;<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/screen.png" width="45%" alt="Screen time clock">
<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/finder.png" width="45%" alt="Find my ring: hot and cold">&nbsp;<img src="https://raw.githubusercontent.com/busssard/OpenRing/screenshots/finder-walk.png" width="45%" alt="Find my ring: search walk map">
</p>
<sub>Demo data on an Android emulator, updated automatically from the main branch.</sub>

## What it does

* Sleep: stages, a night timeline with every wake up, your last 30 nights and your average night.
  You choose how detailed the sleep chart is; the numbers stay the same
* Sleep, readiness and activity scores, each with the parts it is made of
* Heart rate through the day, HRV, skin temperature, blood oxygen and their trends
* Your phone's screen time with a score, shown as a clock next to your sleep; apps like maps can be
  left out. Phone use at night counts as awake (can be switched off)
* Live heart rate, HRV and blood oxygen on demand
* Find my ring, when the ring is lost at home (see below)
* Nights keep the time zone you slept in, so a trip does not move your other nights
* Touch the night timeline, the heart rate and trend lines or the screen time clock to see the exact
  time and value
* Backups of the key and all history, checked before they are saved, and a CSV export, all as files on your phone
* A demo mode, so you can look around without a ring

### Find my ring

Lost the ring at home? Find my ring (on Today, and in Settings, Ring) shows when it was last on a
finger and when this phone last heard it. Then walk around: a ring fills and the phone clicks faster
as you get closer. Turn on the spot with the phone against your chest and an arrow points to the
strongest signal. Or walk slowly through the room: a small map shows your path with the signal at
each spot, and a circle marks where to start looking.

It uses the ring's Bluetooth signal, so it tells warmer or colder, not metres; walls, blankets and
your body change it. The circle is an area of about a couple of metres, not a pin. The ring makes
no sound itself (the clicks come from the phone), and it only works while the ring has battery and
is within Bluetooth range.

## Install

Android 8.0 or newer.

1. Download the APK from the [latest release](https://github.com/busssard/OpenRing/releases/latest) on your phone and open it.
2. Allow your browser to install apps when Android asks (*Install unknown apps*), then tap **Install**.
3. If Play Protect warns you (it does for anything not from the Play Store): **More details**, then **Install anyway**.
4. If Android says the app is from an unverified developer, see the next fold.

Updates install over the old version and keep your data.

<details>
<summary>Android says "unverified developer"</summary>

This is Google's new check for apps from outside the Play Store. The setting below exists since
August 2026; Google plans to require it for apps from websites like this one in 2027 (a few countries
and app stores earlier). To install anyway, turn on one setting once:

1. Turn on the developer options: Settings, About phone, tap **Build number** seven times.
2. In Settings, System, Developer options, open **Apps from unverified developers** and turn it on.
   Android asks you to confirm that nobody is pressuring you.
3. Restart the phone, then wait one day. The wait is Google's protection against scam calls.
4. Go back to the setting, confirm with your fingerprint or PIN, and choose to allow it
   **always** (with 7 days, updates of Open Ring fail once the 7 days are over).
5. Open the APK again. Android warns once more, tap **Install anyway**.

The developer options can be turned off again afterwards. Menu names differ a little between phone
makers. Google's own description: [developer verification FAQ](https://developer.android.com/developer-verification/guides/faq).
</details>

<details>
<summary>Is it safe?</summary>

* The app has no internet permission, so Android blocks it from sending anything anywhere.
  [A check](tests/check_privacy.sh) in every build makes sure that never changes.
* Every release APK is built by GitHub from this code after the tests pass. The release notes list
  its checksum, signing certificate and a link to the build, and it has a
  [provenance attestation](https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations)
  (`gh attestation verify OpenRing-x.y.apk -R busssard/OpenRing`).
* Each change runs more than 2000 automated tests ([results and coverage](https://github.com/busssard/OpenRing/blob/badges/summary.md)),
  is tested on an Android 14 emulator ([runs](https://github.com/busssard/OpenRing/actions/workflows/emulator.yml))
  and scanned by [CodeQL](https://github.com/busssard/OpenRing/actions/workflows/codeql.yml).
* All releases share one signing key, so nobody else can push an update to your phone.

More in [SECURITY.md](SECURITY.md).
</details>

## Getting started

Works with Oura Ring Gen 3, Ring 4 and Ring 5 (tested mostly on Ring 4). Or tap
**Just looking? Explore with demo data** on the first screen to look around without a ring.

1. Allow Bluetooth access.
2. Enter age, sex, weight and height (used for calories, goals and age adjusted sleep targets).
3. Connect the ring. It only talks to apps that know its pairing key (32 characters). Three ways:
   * **Use my existing key**: the key the official Oura app uses; the official app keeps working too.
     Paste it with the **Paste** button. Tick *Install this key on a factory reset ring* only if the
     ring was reset since.
   * **Restore from a backup**: a backup file saved in Open Ring (Settings, Key and backup) brings back
     the key and settings, a full backup also all history. A text file holding only the key works too.
   * **Pair and create a new key**: for a factory reset ring. The official app then stops working until
     the ring is reset again. To reset a Gen 3 or Ring 4 on its dock: take the ring off, put it back and
     wait 2 seconds, then flip the dock upside down until it glows blue, upright until red, upside down
     until purple, upright until yellow. Yellow means the reset started; blinking blue means done.
4. Back up the key the app shows you. It cannot be read back from the ring.

<details>
<summary>Where do I find the key of the official app?</summary>

* iPhone: make an unencrypted local backup and read `assa.sqlite` (table `ringconfiguration`, column `auth_key`).
* Rooted Android: `tools/android_oura_key_extract.py` from the [open_oura](https://github.com/Th0rgal/open_oura) project.
</details>

Keep the ring on its charger near the phone for the first sync, which copies the ring's whole history and can
take a while. Close the official Oura app during syncs; the ring accepts only one Bluetooth link.
Afterwards the app syncs when you open it (at most every half hour) and about every three hours in the
background (can be switched off in Settings).

## If something does not show up

A sync that fails tries again by itself and then says in plain words what to try. If data is still
missing, open Settings, Help, **Data check and log**. It follows your data from the ring to the screen and
shows the step where it stops, with a hint what to do. *Repair* dates and analyses everything already
stored again, without a new sync.
The data check contains no health values, so you can paste it into a
[bug report](https://github.com/busssard/OpenRing/issues/new/choose).

## How the numbers are worked out

Everything is calculated on your phone from what the ring stores. Oura's formulas are not public,
so the scores are close to Oura's but not the same; deep and REM sleep are estimates.

* **Not included:** workout detection, cycle insights, cardiovascular age, firmware updates. Steps are
  estimated from activity, not counted.

<details>
<summary>How it compares with Oura</summary>

* **Sleep stages.** When the ring sends its own stages (Gen 3 can), the app uses them. Ring 4 and
  Ring 5 send none, so Open Ring estimates a stage for every 30 seconds from heart rate, HRV and
  movement, with the rules of open_health. Sleep versus awake is reliable; deep and REM are estimates
  and have not been checked against a sleep lab. Oura's app uses a machine learning model trained on
  over 1,200 nights in sleep labs, which also uses temperature; Oura reports 79% agreement with a sleep
  lab for the four stages ([Oura blog](https://ouraring.com/blog/new-sleep-staging-algorithm/)).
* **Scores.** Each score is a weighted mix of its parts, with the weights open_health worked out from
  Oura's scoring engine. For sleep: total sleep 35%, restfulness 15%, and efficiency, REM, deep sleep,
  time to fall asleep and timing 10% each. How many points a part gives (for example for 7 hours of
  sleep) is not public, so those curves are our own, based on sleep research and Oura's help pages, and
  can be read in [the code](app/src/main/java/app/openring/core/analysis/Scores.java). A part the ring
  did not record is left out instead of lowering the score.
* **Your normal.** Readiness compares your resting heart rate, HRV and temperature with your own usual
  values. They become personal after about 14 nights; until then the app says so and early scores move more.
* **Known differences.** Where we know our count differs from Oura's, there is an issue, for example
  active calories ([#117](https://github.com/busssard/OpenRing/issues/117)) and sleep efficiency
  ([#107](https://github.com/busssard/OpenRing/issues/107)).

</details>

More detail in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#analysis).

## For developers

* [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): build, test, make a change, release
* [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how the app talks to the ring and analyses the data
* [CONTRIBUTING.md](CONTRIBUTING.md) and [CLAUDE.md](CLAUDE.md) (rules for AI assisted work)

## Credits and license

Built on the reverse engineering work of [open_oura](https://github.com/Th0rgal/open_oura) and
[open_health](https://github.com/Th0rgal/open_health) by Thomas Marchand, cross checked with
[ringverse](https://github.com/ringverse/protocol/blob/main/oura/BLE.md),
[OpenStrap](https://github.com/OpenStrap/protocol), [BrightOura](https://github.com/gi-os/BrightOura)
and Gadgetbridge. The Screen tab's clock and day rows follow the idea of
[ScreenTime](https://github.com/markusfisch/ScreenTime) by Markus Fisch (no code copied).
MIT license, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
