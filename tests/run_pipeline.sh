#!/bin/sh
# End to end test of storage, dating and analysis on the JVM (no phone needed).
# Fetches the SQLite JDBC driver and org.json sources (both pinned) from GitHub on first run.
set -e
cd "$(dirname "$0")/.."
DEPS=tests/.deps
mkdir -p "$DEPS"
[ -f "$DEPS/sqlite-jdbc.jar" ] || curl -sL -o "$DEPS/sqlite-jdbc.jar" https://github.com/xerial/sqlite-jdbc/releases/download/3.41.2.2/sqlite-jdbc-3.41.2.2.jar
# org.json is pinned to one release tag of stleary/JSON-java (issue #239); a folder from an older,
# unpinned clone (no marker or another tag) is replaced. To update: change the tag and run the tests.
JSON_TAG=20260814
if [ "$(cat "$DEPS/json/.openring-tag" 2>/dev/null)" != "$JSON_TAG" ]; then
  rm -rf "$DEPS/json"
  git -c advice.detachedHead=false clone -q --depth 1 --branch "$JSON_TAG" https://github.com/stleary/JSON-java "$DEPS/json"
  echo "$JSON_TAG" > "$DEPS/json/.openring-tag"
fi
# Every folder and file the tests create goes into one run folder, removed at the end even when a
# test fails or the run is stopped (issue #214). KEEP_TEST_DIRS=1 keeps it for debugging.
RUN=$(mktemp -d "${TMPDIR:-/tmp}/openring-test-$$-XXXXXX")
mkdir -p "$RUN/tmp"
if [ "${KEEP_TEST_DIRS:-0}" = 1 ]; then
  echo "test folders kept in $RUN"
else
  trap 'rm -rf "$RUN"' EXIT
  trap 'exit 130' INT TERM
fi
OUT=${TEST_OUT:-$RUN/classes}
mkdir -p "$OUT"
javac -nowarn -source 8 -target 8 -Xlint:-options -d "$OUT" \
  $(find tests/pipeline/shim -name '*.java') $(find "$DEPS/json/src/main/java/org/json" -name '*.java') \
  $(find app/src/main/java/app/openring/core -name '*.java') \
  app/src/main/java/app/openring/data/Db.java app/src/main/java/app/openring/data/Periods.java app/src/main/java/app/openring/data/Prefs.java \
  app/src/main/java/app/openring/data/Json.java app/src/main/java/app/openring/data/Analyzer.java \
  app/src/main/java/app/openring/data/DataCheck.java app/src/main/java/app/openring/data/RightNow.java app/src/main/java/app/openring/data/Backup.java app/src/main/java/app/openring/data/SleepHistory.java app/src/main/java/app/openring/data/Demo.java app/src/main/java/app/openring/data/ScreenDays.java \
  app/src/main/java/app/openring/data/Exporter.java app/src/main/java/app/openring/data/BeforeUpdate.java app/src/main/java/app/openring/data/AutoBackup.java tests/pipeline/PipelineTest.java
TZ=Europe/Zurich java -Djava.io.tmpdir="$RUN/tmp" $JAVA_OPTS -cp "$OUT:$DEPS/sqlite-jdbc.jar" PipelineTest
