#!/bin/bash
# Fails when two screenshots in folder $1 are byte identical (issue #160): a shot that should show
# another card or state but did not move anywhere.
set -u
dir=${1:-screenshots}
dups=$(cd "$dir" && md5sum -- *.png 2>/dev/null | sort | awk '{ if ($1 == last) print prev " " $2; last = $1; prev = $2 }')
if [ -n "$dups" ]; then
  echo "$dups" | while read -r a b; do echo "::error::Screenshots ${a%.png} and ${b%.png} are the same picture"; done
  exit 1
fi
echo "== all screenshots differ"
