#!/bin/bash
# Publish small status files to the branch "badges" (read by the README badges through shields.io).
# Usage: badge.sh <name> <label> <message> <color> [file to copy alongside ...]
# Several workflows write here at the same time, so it retries on a rejected push.
set -u
name=$1 label=$2 message=$3 color=$4
shift 4
repo=${BADGE_REPO:-"https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"}
for attempt in 1 2 3 4 5; do
  dir=$(mktemp -d)
  if git clone -q --depth 1 -b badges "$repo" "$dir" 2>/dev/null; then :; else
    git init -q -b badges "$dir"
    git -C "$dir" remote add origin "$repo"
  fi
  printf '{"schemaVersion":1,"label":"%s","message":"%s","color":"%s"}\n' "$label" "$message" "$color" > "$dir/$name.json"
  for f in "$@"; do cp "$f" "$dir/"; done
  git -C "$dir" add -A
  git -C "$dir" -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
    commit -q -m "$name: $message" || { echo "badge $name unchanged"; exit 0; }
  if git -C "$dir" push -q origin badges; then echo "badge $name: $message"; exit 0; fi
  sleep $((attempt * 3))
done
echo "::warning::could not publish badge $name"
