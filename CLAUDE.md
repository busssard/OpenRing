# Open Ring: notes for Claude sessions

Local first Android app for the Oura Ring (mainly Ring 4). Plain Java, no third party libraries,
UI built in code. See README.md for what the app does and how the pieces fit.

## Build and test
* `./tests/run_tests.sh`: protocol and analysis unit tests (any JDK).
* `./tests/run_pipeline.sh`: storage, dating, analysis and backup on the JVM with SQLite shims
  (fetches sqlite-jdbc and org.json into tests/.deps on first run).
* `./build_apk.sh`: builds and signs build/OpenRing.apk without Gradle. Needs aapt2, d8 or dx,
  zipalign, apksigner and an android.jar (`ANDROID_JAR=...`). Gradle files exist for Android Studio.
* `./tests/check_privacy.sh`: the app must never get the INTERNET permission or networking/tracking code.
* Run all three before every commit. CI runs them too, builds an APK per push, and the emulator
  workflow opens every screen with demo data (`.github/scripts/screens.sh`) and fails on crashes.
* Demo mode (`data/Demo.java`, own database file) is what screenshots and visitors see; keep it realistic.

## Versions and releases
* Bump `VERSION_CODE` and `VERSION_NAME` in build_apk.sh and `versionCode`/`versionName` in
  app/build.gradle together (the About text reads the installed version).
* Add a `## X.Y` section to CHANGELOG.md. When the bump reaches main, CI publishes the signed
  GitHub Release vX.Y by itself (secrets `KEYSTORE_B64`, `KEYSTORE_PASS`). Claude sessions cannot
  push tags or create releases; they do not need to.

## Rules
* Never commit a keystore or a ring key. The release key signs updates; whoever holds it can
  replace the installed app. `.gitignore` excludes `*.keystore`.
* Never commit real health data (raw events, databases, screenshots of someone's night).
* `core/` must stay free of Android imports so it runs in the JVM tests.
* Work on a branch and open a pull request; main is what users install from.
* User facing text: plain language, no dashes as punctuation (use commas or brackets).

## Ring facts worth knowing (Ring 4, fw 2.6.7, ORE_07)
* No live HR stream on 98ed0003; live values come from draining stored history every few seconds.
* SpO2 live mode is refused, "requested" mode works. Feature latest returns zeros.
* Accelerometer streaming (games) is unconfirmed on this firmware.
