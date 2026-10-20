#!/bin/bash
# Runs the JVM test suites with JaCoCo and writes a coverage report:
#   build/coverage/summary.md   table per package (also shown in the CI run summary)
#   build/coverage/html/        full report, line by line
#   build/coverage/badges/      tests.json and coverage.json for the README badges
#
# What it measures: the code the JVM tests can run (core/ and data/, the protocol, decoders, dating,
# analysis, storage, backup, demo). The screens (ui/), Bluetooth (ble/) and background sync (sync/)
# need Android; they are exercised by the emulator test and by real rings, not counted here.
set -euo pipefail
cd "$(dirname "$0")/.."
DEPS=tests/.deps
JACOCO=0.8.12
mkdir -p "$DEPS"
if [ ! -f "$DEPS/jacoco/lib/jacococli.jar" ]; then
  curl -sSL -o "$DEPS/jacoco.zip" "https://github.com/jacoco/jacoco/releases/download/v$JACOCO/jacoco-$JACOCO.zip"
  unzip -q -o "$DEPS/jacoco.zip" lib/jacocoagent.jar lib/jacococli.jar -d "$DEPS/jacoco"
  rm -f "$DEPS/jacoco.zip"
fi
AGENT="$DEPS/jacoco/lib/jacocoagent.jar"
CLI="$DEPS/jacoco/lib/jacococli.jar"
C=build/coverage
rm -rf "$C"; mkdir -p "$C/badges"

TEST_OUT="$C/classes-core" JAVA_OPTS="-javaagent:$AGENT=destfile=$C/core.exec" ./tests/run_tests.sh | tee "$C/core.txt"
TEST_OUT="$C/classes-pipeline" JAVA_OPTS="-javaagent:$AGENT=destfile=$C/pipeline.exec" ./tests/run_pipeline.sh | tee "$C/pipeline.txt"

# every app class the tests compiled (core and data), without the Android stand ins and org.json
mkdir -p "$C/classes"
cp -r "$C/classes-pipeline/app" "$C/classes/"
java -jar "$CLI" merge "$C/core.exec" "$C/pipeline.exec" --destfile "$C/all.exec" >/dev/null
java -jar "$CLI" report "$C/all.exec" --classfiles "$C/classes" --sourcefiles app/src/main/java \
  --csv "$C/coverage.csv" --html "$C/html" --name "Open Ring JVM tests" >/dev/null

python3 - "$C" <<'EOF'
import csv, json, os, re, sys
c = sys.argv[1]
pk = {}
for r in csv.DictReader(open(os.path.join(c, "coverage.csv"))):
    p = r["PACKAGE"].replace("app.openring.", "")
    d = pk.setdefault(p, [0, 0, 0, 0])
    d[0] += int(r["LINE_COVERED"]); d[1] += int(r["LINE_MISSED"])
    d[2] += int(r["BRANCH_COVERED"]); d[3] += int(r["BRANCH_MISSED"])
def pct(a, b): return 100.0 * a / (a + b) if a + b else 0.0
tl = sum(v[0] for v in pk.values()); tm = sum(v[1] for v in pk.values())
tb = sum(v[2] for v in pk.values()); tbm = sum(v[3] for v in pk.values())
passed = failed = 0
for f in ("core.txt", "pipeline.txt"):
    for m in re.finditer(r"(\d+) passed, (\d+) failed", open(os.path.join(c, f)).read()):
        passed += int(m.group(1)); failed += int(m.group(2))
# lines of Android only code that the JVM tests cannot reach (for an honest overall picture)
android_only = 0
for d in ("ui", "ble", "sync"):
    for root, _, files in os.walk("app/src/main/java/app/openring/" + d):
        for f in files:
            if f.endswith(".java"):
                android_only += sum(1 for l in open(os.path.join(root, f)) if l.strip() and not l.strip().startswith(("//", "*", "/*", "import", "package")))
lines = ["| Package | Lines covered | Branches covered |", "| --- | ---: | ---: |"]
for p in sorted(pk):
    v = pk[p]
    lines.append(f"| `{p}` | {pct(v[0], v[1]):.0f}% ({v[0]}/{v[0]+v[1]}) | {pct(v[2], v[3]):.0f}% |")
lines.append(f"| **core and data** | **{pct(tl, tm):.0f}%** ({tl}/{tl+tm}) | **{pct(tb, tbm):.0f}%** |")
summary = "\n".join([
    "## Test results", "",
    f"**{passed} tests passed, {failed} failed** (JVM unit and pipeline tests).", "",
    "## Coverage of the code the JVM tests can run", "",
    *lines, "",
    f"Not counted: about {android_only} lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every",
    "screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as",
    f"untested, the JVM tests reach about {100.0 * tl / (tl + tm + android_only):.0f}% of all app code.", ""])
open(os.path.join(c, "summary.md"), "w").write(summary)
cov = pct(tl, tm)
color = "brightgreen" if cov >= 80 else "green" if cov >= 70 else "yellowgreen" if cov >= 60 else "yellow" if cov >= 50 else "orange"
json.dump({"schemaVersion": 1, "label": "tests", "message": f"{passed} passed" + (f", {failed} failed" if failed else ""),
           "color": "brightgreen" if failed == 0 else "red"}, open(os.path.join(c, "badges/tests.json"), "w"))
json.dump({"schemaVersion": 1, "label": "coverage (logic)", "message": f"{cov:.0f}%", "color": color},
          open(os.path.join(c, "badges/coverage.json"), "w"))
print(summary)
EOF
