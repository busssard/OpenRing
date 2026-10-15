#!/bin/sh
# Compile and run the JVM tests for the protocol and analysis core (no Android needed).
set -e
cd "$(dirname "$0")/.."
OUT=${TEST_OUT:-$(mktemp -d)}
mkdir -p "$OUT"
javac -source 8 -target 8 -Xlint:-options -d "$OUT" $(find app/src/main/java/app/openring/core -name '*.java') tests/java/CoreTest.java
java $JAVA_OPTS -cp "$OUT" CoreTest
