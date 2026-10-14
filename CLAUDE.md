# Open Ring: notes for Claude sessions

Local first Android app for the Oura Ring (mainly Ring 4). Plain Java, no third party libraries,
UI built in code. See README.md for what the app does. This file is the shared memory of every
Claude session working here (the chat session that built the app, the nightly issue run, Claude
Code on the owner's laptop). Read all of it before changing anything.

## Who it is for
* The owner is GitHub `busssard`. The main tester wears a **Ring 4 (ORE_07, fw 2.6.7)** on an
  Android 13 phone, is not technical and reads everything on the phone.
* **The main tester really does wake up often at night.** Never "clean up" wake detection or filter awakenings
  away to make charts look nicer; make detail adjustable instead (see issue #9).
* Repo visitors read the README on phones; keep it short and plain, no marketing tone.
* Real health data is private: never commit, print in PRs or post anything derived from real
  data. Screenshots only ever come from demo mode.

## How a change should go (the process that worked)
1. **Understand before building.** Read the code involved, find the real cause, and say honestly
   what a change can and cannot do (example: a stage filter changes detail, not accuracy).
2. **Design first for anything not trivial:** post the plan as an issue comment (cause, proposal,
   presets or numbers, tests, what is out of scope) and wait for the owner's approval.
3. **Build** on a branch, in the existing style, with tests for the logic (`core/` and `data/`
   are testable on the JVM; add cases to tests/java/CoreTest.java or tests/pipeline/PipelineTest.java).
4. **Look at the result.** After pushing, the emulator workflow publishes screenshots of every
   screen to the branch `screenshots-preview`; fetch it and open the PNGs (the Read tool shows
   images) and check the change really looks right. New screens or states: extend
   `.github/scripts/screens.sh` so they get a screenshot too.
5. **Pull request** with what changed, why, how it was tested, and `Fixes #N`. Bump the version
   for user visible changes (merging then publishes the release). Never merge yourself.
6. Do not send APK files in chat; GitHub builds them.

## Build and test
* `./tests/run_tests.sh`: protocol and analysis unit tests (any JDK).
* `./tests/run_pipeline.sh`: storage, dating, analysis, backup, sleep history and demo data on the
  JVM with SQLite shims (fetches sqlite-jdbc and org.json into tests/.deps on first run).
* `./tests/check_privacy.sh`: the app must never get the INTERNET permission or networking/tracking code.
* `./build_apk.sh`: builds and signs build/OpenRing.apk without Gradle (needs aapt2, d8 or dx,
  zipalign, apksigner and `ANDROID_JAR=...`). Without an android.jar, rely on CI.
* Run all tests before every commit. CI (`build.yml`) runs them, builds the APK, and the emulator
  workflow (`emulator.yml`, script `.github/scripts/screens.sh`) opens every screen with demo data
  on Android 14 and fails on crashes. Check results with
  `gh api repos/busssard/OpenRing/commits/<sha>/check-runs` (the Actions API itself is blocked
  for Claude sessions, check runs and annotations are not).

## Versions and releases
* Bump `VERSION_CODE` and `VERSION_NAME` in build_apk.sh and `versionCode`/`versionName` in
  app/build.gradle together (the About text reads the installed version).
* Add a `## X.Y` section to CHANGELOG.md, written for users. When the bump reaches main, CI
  publishes the signed GitHub Release vX.Y by itself (secrets `KEYSTORE_B64`, `KEYSTORE_PASS`).
* Claude sessions cannot push tags, create releases or use GraphQL; open PRs with
  `gh api repos/busssard/OpenRing/pulls -f title=... -f head=... -f base=main -F body=@file`.

## Rules
* Never commit a keystore or a ring key. `.gitignore` excludes `*.keystore`.
* Never commit real health data (raw events, databases, screenshots of a real night).
* `core/` must stay free of Android imports so it runs in the JVM tests.
* Work on a branch and open a pull request; main is what users install from.
* User facing text: plain language, short, no dashes as punctuation (commas or brackets).
  Every number on screen gets a one line explanation (`ui/Explain.java` holds the longer texts).

## Map of the code
* `core/`: Protocol, Packet, Auth (AES nonce), RingClient (requests, history drain), EventCodec
  (event decoders), TimeLine (ring decisecond counter to wall clock), Tilt (games).
* `core/analysis/`: NightFinder (where sleeps are), Stager (stages from HR, HRV, motion; open_health
  port), NightAnalyzer (metrics), Scores, Wellness, SleepPattern (average night), LiveHrv.
* `data/`: Db (SQLite, real and demo files), Analyzer (runs analysis after sync), Json (night and
  day JSON), Prefs (settings, Keystore wrapped key), DataCheck, RightNow, Backup, Demo,
  SleepHistory, Exporter.
* `ble/`: GattLink (GATT, MTU, bonding, extra channels), RingScanner, RingManager (sync, live,
  motion; one worker thread and one `op` lock for the ring).
* `ui/`: MainActivity (tabs, date, follows the newest day), Pages (all tab content), Charts (hand
  drawn; `Scrub` gives touch readout), Ui (colors, cards, buttons, inputs), Settings, Setup, Live,
  Log (data check), games/.

## Lessons learned (do not repeat these bugs)
* Ring 4 fw 2.6.7 sends **no live stream**: live HR, HRV and SpO2 come from draining the ring's
  stored history every few seconds. SpO2 "live" mode is refused, "requested" mode works; feature
  latest returns zeros. Accelerometer streaming for games is unconfirmed.
* The ring's clock is a counter; events become dates only through time records. An interrupted
  sync once left everything undated; `Db.rebuildTime` repairs this, keep it.
* Only one Bluetooth session at a time: anything holding the ring (games, live) must release it
  in `onPause`, and other work waits on the `op` lock instead of failing.
* A night must not be stretched into time the ring itself marks as awake.
* After replacing a ScrollView's content, restore the scroll position in `post()`, after layout.
* The main screen follows the newest day until the person picks a date (morning sync shows last night).
* Charts with one value per day need dots (a single point draws no line) and a time axis that
  fits the data.
* Demo mode lives in its own database file; never mix demo and real data.
* Inputs need `Ui.input()` (visible outline); same colored backgrounds made the key field invisible.

## Messages to the owner
Start with the current date and time in Europe/Zurich, use no dashes as punctuation (commas or
brackets), keep it short, and end with one observation about a pattern in how he works if there
is a real one.
