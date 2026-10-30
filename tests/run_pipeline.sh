#!/bin/sh
# End to end test of storage, dating and analysis on the JVM (no phone needed).
# Fetches the SQLite JDBC driver and org.json sources from GitHub on first run.
set -e
cd "$(dirname "$0")/.."
DEPS=tests/.deps
mkdir -p "$DEPS"
[ -f "$DEPS/sqlite-jdbc.jar" ] || curl -sL -o "$DEPS/sqlite-jdbc.jar" https://github.com/xerial/sqlite-jdbc/releases/download/3.41.2.2/sqlite-jdbc-3.41.2.2.jar
[ -d "$DEPS/json" ] || git clone -q --depth 1 https://github.com/stleary/JSON-java "$DEPS/json"
OUT=${TEST_OUT:-$(mktemp -d)}
mkdir -p "$OUT"
javac -nowarn -source 8 -target 8 -Xlint:-options -d "$OUT" \
  $(find tests/pipeline/shim -name '*.java') $(find "$DEPS/json/src/main/java/org/json" -name '*.java') \
  $(find app/src/main/java/app/openring/core -name '*.java') \
  app/src/main/java/app/openring/data/Db.java app/src/main/java/app/openring/data/Prefs.java \
  app/src/main/java/app/openring/data/Json.java app/src/main/java/app/openring/data/Analyzer.java \
  app/src/main/java/app/openring/data/DataCheck.java app/src/main/java/app/openring/data/RightNow.java app/src/main/java/app/openring/data/Backup.java app/src/main/java/app/openring/data/SleepHistory.java app/src/main/java/app/openring/data/Demo.java app/src/main/java/app/openring/data/ScreenDays.java \
  app/src/main/java/app/openring/data/Exporter.java app/src/main/java/app/openring/data/BeforeUpdate.java app/src/main/java/app/openring/data/AutoBackup.java tests/pipeline/PipelineTest.java
TZ=Europe/Zurich java $JAVA_OPTS -cp "$OUT:$DEPS/sqlite-jdbc.jar" PipelineTest
