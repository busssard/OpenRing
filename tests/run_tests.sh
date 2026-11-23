#!/bin/sh
# Compile and run the JVM tests for the protocol and analysis core (no Android needed).
set -e
cd "$(dirname "$0")/.."
if [ -z "${TEST_OUT:-}" ]; then
  OUT=$(mktemp -d)
  trap 'rm -rf "$OUT"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
else
  OUT=$TEST_OUT
fi
mkdir -p "$OUT"
# ui/Explain.java has no Android imports; it is compiled here so CoreTest can check its texts
if grep -q '^import android' app/src/main/java/app/openring/ui/Explain.java; then
  echo "FAIL  ui/Explain.java imports Android; keep it plain Java (CoreTest compiles it) or drop it from tests/run_tests.sh"
  exit 1
fi
javac -source 8 -target 8 -Xlint:-options -d "$OUT" $(find app/src/main/java/app/openring/core -name '*.java') app/src/main/java/app/openring/ui/Explain.java tests/java/CoreTest.java tests/java/FinderTest.java tests/java/WatchTest.java tests/java/ForecastTest.java tests/java/BatteryTest.java tests/java/DayLineTest.java tests/java/TodayTest.java tests/java/LifeTest.java tests/java/LayoutTest.java tests/java/TrendsTest.java tests/java/RingsTest.java
java $JAVA_OPTS -cp "$OUT" CoreTest
# the readiness baselines of core/analysis/Baselines against the old Analyzer code, bit for bit (issue #292, phase 1.5), on lines of its own
javac -source 8 -target 8 -Xlint:-options -cp "$OUT" -d "$OUT" tests/java/BaselinesTest.java
java $JAVA_OPTS -cp "$OUT" BaselinesTest
java $JAVA_OPTS -cp "$OUT" FinderTest
java $JAVA_OPTS -cp "$OUT" WatchTest
java $JAVA_OPTS -cp "$OUT" ForecastTest
java $JAVA_OPTS -cp "$OUT" BatteryTest
java $JAVA_OPTS -cp "$OUT" DayLineTest
java $JAVA_OPTS -cp "$OUT" TodayTest
java $JAVA_OPTS -cp "$OUT" LifeTest
java $JAVA_OPTS -cp "$OUT" RingsTest
java $JAVA_OPTS -cp "$OUT" LayoutTest
java $JAVA_OPTS -cp "$OUT" TrendsTest
# the emulator CI scripts: publishing the screenshots, the identical shot check (issues #57, #160)
bash tests/test_ci_scripts.sh
# the sleep sound detector (issue #380), compiled on its own line so other new suites never touch the same line
javac -source 8 -target 8 -Xlint:-options -cp "$OUT" -d "$OUT" tests/java/SoundTest.java
java $JAVA_OPTS -cp "$OUT" SoundTest
# nights entered by hand (issue #427), compiled on its own line so other new suites never touch the same line
javac -source 8 -target 8 -Xlint:-options -cp "$OUT" -d "$OUT" tests/java/HandTest.java
java $JAVA_OPTS -cp "$OUT" HandTest
# ble/Gatt, the Bluetooth write and close calls the ring and watch links share, with stand ins for Android's classes
# (tests/ble/shim); its own output folder, so the stand ins never reach the other suites
mkdir -p "$OUT/ble"
javac -source 8 -target 8 -Xlint:-options -d "$OUT/ble" $(find tests/ble/shim -name '*.java') app/src/main/java/app/openring/ble/Gatt.java tests/ble/GattTest.java
java $JAVA_OPTS -cp "$OUT/ble" app.openring.ble.GattTest
