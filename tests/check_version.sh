#!/bin/sh
# The version must be the same in build_apk.sh and app/build.gradle, and CHANGELOG.md must have a
# section for it (its heading becomes the release notes). Fails the build otherwise.
set -e
cd "$(dirname "$0")/.."
code=$(sed -n 's/^VERSION_CODE=//p' build_apk.sh)
name=$(sed -n 's/^VERSION_NAME=//p' build_apk.sh)
gcode=$(sed -n "s/.*versionCode \([0-9]*\).*/\1/p" app/build.gradle)
gname=$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" app/build.gradle)
fail=0
if [ "$code" != "$gcode" ] || [ "$name" != "$gname" ]; then
  echo "FAIL  build_apk.sh has $code / $name, app/build.gradle has $gcode / $gname"; fail=1
else
  echo "ok    version $name (code $code) in build_apk.sh and app/build.gradle"
fi
if grep -qx "## $name" CHANGELOG.md; then
  echo "ok    CHANGELOG.md has a section for $name"
else
  echo "FAIL  CHANGELOG.md has no '## $name' section"; fail=1
fi
exit $fail
