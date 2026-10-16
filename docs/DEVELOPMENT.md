# Development guide

Everything you need to build, test, change and release Open Ring. Read
[ARCHITECTURE.md](ARCHITECTURE.md) for how the app works inside.

## Repository layout

| Path | What it is |
| --- | --- |
| `app/src/main/java/app/openring/` | The app (plain Java, no third party libraries). Packages explained in ARCHITECTURE.md |
| `app/src/main/res/` | Icons (vector drawables), app name, theme. All screens are built in code, there are no layouts |
| `app/src/main/AndroidManifest.xml` | Permissions, activities, services. Must never request `INTERNET` |
| `tests/java/CoreTest.java` | Unit tests for `core/` (protocol, decoders, analysis), run by `tests/run_tests.sh` |
| `tests/pipeline/` | End to end test of `data/` on the JVM, with small stand ins for Android classes (`shim/`), run by `tests/run_pipeline.sh` |
| `tests/check_privacy.sh`, `tests/check_version.sh` | Fail if the app gains network access or tracking code, or if version numbers and changelog disagree |
| `tests/coverage.sh` | Runs the JVM suites with JaCoCo and writes the coverage report and badge files |
| `build_apk.sh` | Builds and signs the APK without Gradle (used by CI) |
| `build.gradle`, `settings.gradle`, `app/build.gradle`, `gradle.properties`, `gradle/` | Gradle files for Android Studio (no wrapper script committed; CI does not use Gradle) |
| `.github/workflows/` | CI: `build.yml` (tests, APK, releases), `emulator.yml` (Android 14 screen test and screenshots), `codeql.yml` (security scan) |
| `.github/scripts/screens.sh`, `.github/scripts/badge.sh` | The emulator script that opens every screen with demo data; the README badge publisher |
| `.github/ISSUE_TEMPLATE/`, `.github/PULL_REQUEST_TEMPLATE.md` | Templates for issues (bug, idea) and pull requests |
| `docs/` | This guide and the architecture notes |
| `CHANGELOG.md` | User facing changes per version; the release notes come from here |
| `README.md`, `CONTRIBUTING.md`, `SECURITY.md` | For users, contributors, and security and privacy |
| `LICENSE`, `NOTICE` | MIT license; credits for the ported open_oura and open_health code |
| `CLAUDE.md` | Rules and context for Claude sessions working on the repository |

## Building

* **Android Studio:** open the folder, let Gradle sync, then *Build > Build APK(s)*. Release builds are
  signed with the debug key so they install; that APK cannot update a release from GitHub. This path is
  not tested in CI; if it breaks, `build_apk.sh` is the reference build.
* **Command line, no Gradle:** `./build_apk.sh` produces `build/OpenRing.apk`. It needs a JDK (javac,
  jar, keytool), `zip`, the Android build tools (aapt2, d8 or dx, zipalign, apksigner) and an `android.jar`
  (API 34). It finds them in `$ANDROID_HOME` or `$ANDROID_SDK_ROOT`; otherwise install the tools (Ubuntu:
  `sudo apt install aapt apksigner zipalign dalvik-exchange`) and point `ANDROID_JAR=/path/to/android.jar`
  at the platforms/android-34 folder of any Android SDK (the apt packages do not include it). Without `KEYSTORE=...` it creates a local
  `release.keystore` (ignored by git); only the owner's key can produce updates for installed releases.
* **GitHub:** every push builds an APK and attaches it to the workflow run.

## Testing

Run all of them before every commit; CI runs them too.

```
./tests/run_tests.sh       # protocol framing, auth, history drain, decoders, staging, scores, sleep pattern
./tests/run_pipeline.sh    # storage, dating, interrupted sync, analysis, backup, sleep history, demo data
./tests/check_privacy.sh   # no INTERNET permission, no networking, analytics, ads or crash reporting code
./tests/check_version.sh   # version numbers agree and the changelog has a section for this version
./tests/coverage.sh        # the two JVM suites again, with a coverage report (optional locally)
```

**Coverage:** `./tests/coverage.sh` runs both JVM suites with JaCoCo and writes
`build/coverage/summary.md` (table per package), `build/coverage/html/` (line by line) and the badge
files. It measures the code the JVM tests can run (`core/`, `data/`); `ui/`, `ble/` and `sync/` need
Android and are covered by the emulator test and real rings, the summary says how much that is. CI runs
the tests through this script, shows the table in the run summary and keeps the HTML report as the
`coverage-report` artifact. When you add logic, add tests until its package stays at or above its
current coverage.

**Badges:** on `main`, CI writes small status files to the branch `badges` (`tests.json`,
`coverage.json`, `screenshots.json`, `summary.md`) that the README badges read through shields.io
(`.github/scripts/badge.sh`). The screenshots badge shows *generating* while the emulator runs.

`run_pipeline.sh` downloads sqlite-jdbc and org.json into `tests/.deps/` on its first run (JaCoCo too,
for the coverage script). It compiles a
fixed list of `data/` files; when you add a file there that the pipeline needs, add it to the list.

**On Android:** `emulator.yml` runs for pull requests and pushes to `main`. It installs the APK on an
Android 14 emulator, opens every screen with demo data and fails on any crash. Its screenshots are pushed
to the branch `screenshots` (from `main`, used by the README) or `screenshots-preview` (from pull
requests in this repository; shared by all of them, the commit message names the PR head commit). Pull
requests from forks get them as the `screenshots` artifact of the run. The emulator uses the plain
Android image (no Google apps) with 4 GB of memory; if a system "isn't responding" dialog appears,
the script taps *Wait*, and it fails if the dialog stays, so the README pictures are never replaced
by spoiled ones. Look at them before asking for a
review. When you add a screen or a state worth seeing, add it to
`.github/scripts/screens.sh`. The main screen accepts these extras for automation:
`demo` (boolean), `tab` (0 Today, 1 Sleep, 2 Ready, 3 Activity, 4 Body, 5 Screen), `analysis`
(boolean, unfolds Further analysis), `period` (0 week, 1 month, 2 year, 3 all), `day` (yyyy-mm-dd),
`settings` (opens Settings at a card: `top`, `stages`, `screen` or `games`).

**With a ring:** Settings, Help, *Data check and log* shows each step from ring to screen and the
Bluetooth log. The data check holds counts and dates only, so it is safe to share; raw events are not.

## Making a change

1. Open or pick an issue. For anything not trivial, write the design on the issue first: the cause
   (with file names), the proposal (numbers, texts), what it cannot do, the tests.
2. Branch from `main` (`fix/issue-12-short-name`, `feature/...`, `docs/...`). Never commit to `main`.
3. Follow the existing style: Java 8 language level, 4 spaces, no libraries, UI in code with `Ui`
   helpers and `Charts` views, colors from `Ui`, explanations in `ui/Explain.java`.
4. User facing text: plain and short, explain every number, no dashes as punctuation (commas or
   brackets instead).
5. Add tests for logic. Keep `core/` free of Android imports.
6. Open a pull request with what changed, why, how it was tested, and `Fixes #N`. Check that the checks
   pass (Build APK, Android emulator test, CodeQL) and look at the preview screenshots.

### Common tasks

* **New setting:** add a getter and setter in `data/Prefs.java`, the control in `ui/SettingsActivity.java`
  (inside the matching section card), and re-run the analysis (`Analyzer.runAll`) if it changes results.
* **New decoder for a ring event:** add the tag constant and decoder to `core/EventCodec.java`, a unit
  test with real bytes in `CoreTest`, then use it in `data/Analyzer.java`.
* **New chart:** add a view to `ui/Charts.java`; extend `Charts.Scrub` for touch readout.
* **New game:** subclass `ui/games/Game.java`, add it to `GamesActivity.create` before the motion check (which stays last) and raise `COUNT`. Games live in Settings, card *Ring games* (issue #15).
* **Demo data:** `data/Demo.java` generates five weeks in the ring's own format; keep it realistic,
  it is what screenshots and visitors see.

## Versions and releases

1. Bump `VERSION_CODE` (always up by one, Android refuses lower codes) and `VERSION_NAME` in
   `build_apk.sh`, and `versionCode` / `versionName` in `app/build.gradle` to the same values
   (`tests/check_version.sh` fails if they differ). The About screen reads the installed version.
2. Add a section at the top of `CHANGELOG.md` whose heading is exactly `## <VERSION_NAME>`, written for
   users; it becomes the release notes.
3. When this reaches `main`, `build.yml` builds the APK signed with the release key (repository secrets
   `KEYSTORE_B64`, `KEYSTORE_PASS`), attests its provenance and publishes the GitHub Release `vX.Y` with
   the changelog section, checksum and certificate. Versions that already have a release are skipped.

Changes without a version bump (docs, CI) publish nothing.
