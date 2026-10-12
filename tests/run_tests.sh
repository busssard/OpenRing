#!/bin/sh
# Compile and run the JVM tests for the protocol and analysis core (no Android needed).
set -e
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
javac -source 8 -target 8 -Xlint:-options -d "$OUT" $(find app/src/main/java/app/openring/core -name '*.java') tests/java/CoreTest.java
java -cp "$OUT" CoreTest
