#!/bin/sh
# Prints "VERSION_CODE VERSION_NAME". Nothing is typed by hand, so pull requests never conflict over a version.
#
# VERSION_CODE = 29 + the number of change notes in changes/ (one file per user visible pull request, never
# deleted), so it only grows and a nightly and the stable release after it share it (either installs over the other).
#
# VERSION_NAME (owner's decision, 3 November 2026: from 2.0 on, stable 2.y, nightlies 2.y.x):
# * stable (VERSION_CHANNEL=stable, builds of main): 2.0 for the first release of 2.x, then 2.1, 2.2 ... one up for
#   each release that brings new change notes; a build of main without new notes keeps the last stable's name
#   (build.yml then publishes nothing, the release exists);
# * nightly (any other build): 2.y.x, y the last stable release, x the change notes added since it
#   (2.0.0 is the stable 2.0 itself, 2.0.1 the first nightly change after it);
# * before the first 2.x release a nightly keeps the old rule 1.(28 + notes); the next stable is 2.0.
# The stable releases are read from the tags v2.<y> that build.yml makes (fetched with the history); a copy without
# tags or without git counts as "no 2.x release yet".
BASE_CODE=29
BASE_MINOR=28
cd "$(dirname "$0")/.."
count() { grep -c '^[0-9].*\.md$' || true; }
n=$(ls changes 2>/dev/null | count)
code=$((BASE_CODE + n))
last=$(git tag -l 'v2.*' 2>/dev/null | sed -n 's/^v2\.\([0-9][0-9]*\)$/\1/p' | sort -n | tail -1)
if [ -z "$last" ]; then
  if [ "${VERSION_CHANNEL:-nightly}" = stable ]; then echo "$code 2.0"; exit 0; fi
  echo "$code 1.$((BASE_MINOR + n))"; exit 0
fi
# a stable build of a commit that carries a release tag (a rebuild of v2.0 after v2.1 exists) is that release
if [ "${VERSION_CHANNEL:-nightly}" = stable ]; then
  here=$(git tag --points-at HEAD 'v2.*' 2>/dev/null | sed -n 's/^v2\.\([0-9][0-9]*\)$/\1/p' | sort -n | tail -1)
  if [ -n "$here" ]; then echo "$code 2.$here"; exit 0; fi
fi
at=$(git ls-tree --name-only "v2.$last:changes" 2>/dev/null | count)
new=$((n - at)); [ "$new" -lt 0 ] && new=0
if [ "${VERSION_CHANNEL:-nightly}" = stable ]; then
  if [ "$new" -gt 0 ]; then echo "$code 2.$((last + 1))"; else echo "$code 2.$last"; fi
else
  echo "$code 2.$last.$new"
fi
