#!/bin/sh
# Prints "VERSION_CODE VERSION_NAME". The version is counted from the change notes in changes/
# (one file per user visible pull request, never deleted), so pull requests never edit a version
# number and never conflict over it. Up to 1.28 versions were typed by hand: that is the base.
BASE_CODE=29
BASE_MINOR=28
cd "$(dirname "$0")/.."
n=$(ls changes/[0-9]*.md 2>/dev/null | wc -l | tr -d ' ')
echo "$((BASE_CODE + n)) 1.$((BASE_MINOR + n))"
