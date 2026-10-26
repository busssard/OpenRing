#!/bin/sh
# Compile and run the JVM tests for the protocol and analysis core (no Android needed).
set -e
cd "$(dirname "$0")/.."
OUT=${TEST_OUT:-$(mktemp -d)}
mkdir -p "$OUT"
# ui/Explain.java has no Android imports; it is compiled here so CoreTest can check its texts
if grep -q '^import android' app/src/main/java/app/openring/ui/Explain.java; then
  echo "FAIL  ui/Explain.java imports Android; keep it plain Java (CoreTest compiles it) or drop it from tests/run_tests.sh"
  exit 1
fi
javac -source 8 -target 8 -Xlint:-options -d "$OUT" $(find app/src/main/java/app/openring/core -name '*.java') app/src/main/java/app/openring/ui/Explain.java tests/java/CoreTest.java tests/java/FinderTest.java
java $JAVA_OPTS -cp "$OUT" CoreTest
java $JAVA_OPTS -cp "$OUT" FinderTest
# the emulator CI scripts: publishing the screenshots, the identical shot check (issues #57, #160)
bash tests/test_ci_scripts.sh
