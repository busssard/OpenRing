#!/bin/sh
# Versions are counted from the change notes in changes/ (see changes/README.md), never typed.
# Checks: build_apk.sh and app/build.gradle use the same rule, the notes are well formed, and a pull
# request that changes the app has a note (a warning only: a change nobody notices needs none).
set -e
cd "$(dirname "$0")/.."
fail=0
set -- $(./tools/version.sh)
code=$1; name=$2
if grep -q '^VERSION_CODE=[0-9]' build_apk.sh || grep -q 'versionCode [0-9]' app/build.gradle; then
  echo "FAIL  a version number is typed in build_apk.sh or app/build.gradle; it is counted from changes/ now"; fail=1
elif ! grep -q 'tools/version.sh' build_apk.sh || ! grep -q '29 + changeNotes' app/build.gradle || ! grep -q "'1.' + (28 + changeNotes)" app/build.gradle; then
  echo "FAIL  build_apk.sh and app/build.gradle no longer count the version the same way as tools/version.sh"; fail=1
else
  echo "ok    version $name (code $code), counted from changes/"
fi
for f in changes/*.md; do
  b=$(basename "$f")
  [ "$b" = README.md ] && continue
  if ! echo "$b" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}-[a-z0-9-]+\.md$'; then
    echo "FAIL  $f: name must be YYYY-MM-DD-short-name.md"; fail=1
  elif ! grep -q '^\* ' "$f"; then
    echo "FAIL  $f: needs at least one bullet line starting with '* '"; fail=1
  fi
done
[ $fail = 0 ] && echo "ok    change notes well formed"
# in a pull request: app changes without a new note only warn
base=${GITHUB_BASE_REF:+origin/$GITHUB_BASE_REF}
[ -z "$base" ] && git rev-parse -q --verify origin/main >/dev/null && base=origin/main
if [ -n "$base" ] && git rev-parse -q --verify "$base" >/dev/null; then
  mb=$(git merge-base HEAD "$base" 2>/dev/null || true)
  if [ -n "$mb" ]; then
    app=$(git diff --name-only "$mb" HEAD -- app/ | wc -l | tr -d ' ')
    notes=$(git diff --name-only --diff-filter=A "$mb" HEAD -- 'changes/[0-9]*.md' | wc -l | tr -d ' ')
    if [ "$notes" -gt 1 ]; then echo "note  this branch adds $notes change notes; one per pull request is the rule"; fi
    if [ "$app" -gt 0 ] && [ "$notes" = 0 ]; then
      echo "note  the app changed but no file was added to changes/; add one if people will notice (see changes/README.md)"
    fi
  fi
fi
exit $fail
