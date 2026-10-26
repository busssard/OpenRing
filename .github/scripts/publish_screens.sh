#!/bin/bash
# Publishes the emulator screenshots (issue #57). Called by emulator.yml with:
#   SRC     folder with the pictures (default "screenshots")
#   BRANCH  "screenshots" (main, README pictures) or "screenshots-preview" (pull requests)
#   FOLDER  empty for main: the branch becomes exactly these pictures. Otherwise the folder on the
#           shared preview branch that belongs to this run ("pr-57"); only that folder and the root
#           (the latest run, for old links) are replaced, the other pull requests' folders stay.
#   SHA     the commit the pictures show (the pull request head)
#   REMOTE  the repository URL to push to
#   SOURCE_BRANCH  for a manual run on a branch (folder "branch-..."): the branch name, kept in
#           BRANCH.txt so the folder can go once that branch is deleted
# Several pull requests run at the same time: each push only goes through if the branch is still at
# the commit this run fetched (--force-with-lease), otherwise it fetches again and retries. A preview
# that cannot be pushed only warns; it never turns a passed emulator test red.
set -u
SRC=${SRC:-screenshots}
BRANCH=${BRANCH:?}
FOLDER=${FOLDER:-}
SHA=${SHA:-unknown}
REMOTE=${REMOTE:?}
TRIES=${TRIES:-5}
PAUSE=${PAUSE:-3}

# the state of pull request $1 ("open" or "closed"); prints nothing when it cannot be read
pr_state_default() { gh api "repos/${GITHUB_REPOSITORY:-}/pulls/$1" --jq .state 2>/dev/null; }
PR_STATE=${PR_STATE:-pr_state_default}
# "gone" when branch $1 no longer exists on the remote, "there" when it does, nothing when unknown
branch_state() {
  local out
  out=$(git ls-remote --heads "$REMOTE" "refs/heads/$1" 2>/dev/null) || return 0
  [ -n "$out" ] && echo there || echo gone
}
# each state is read once per run, also when the push is retried; unknown keeps the folder
declare -A KEEP
keep_folder() {
  local d=$1 state
  if [ -z "${KEEP[$d]:-}" ]; then
    case "$d" in
      pr-*) state=$($PR_STATE "${d#pr-}")
            if [ "$state" = closed ]; then KEEP[$d]=no; echo "pr ${d#pr-} is closed, dropping $d"
            else KEEP[$d]=yes; [ "$state" = open ] || echo "state of $d unknown, kept"; fi ;;
      branch-*) if [ -f "$d/BRANCH.txt" ]; then state=$(branch_state "$(cat "$d/BRANCH.txt")"); else state=; fi
            if [ "$state" = gone ]; then KEEP[$d]=no; echo "branch of $d is deleted, dropping $d"
            else KEEP[$d]=yes; [ "$state" = there ] || echo "state of $d unknown, kept"; fi ;;
      *) KEEP[$d]=yes ;;
    esac
  fi
  [ "${KEEP[$d]}" = yes ]
}
case "$FOLDER" in pr-*) LABEL="#${FOLDER#pr-}" ;; *) LABEL=$FOLDER ;; esac

if [ ! -d "$SRC" ] || ! ls "$SRC"/*.png >/dev/null 2>&1; then
  echo "::notice::No screenshots to publish (the run stopped before the screen test)"
  exit 0
fi
SRC=$(cd "$SRC" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export GIT_AUTHOR_NAME="github-actions[bot]" GIT_COMMITTER_NAME="github-actions[bot]"
export GIT_AUTHOR_EMAIL="41898282+github-actions[bot]@users.noreply.github.com"
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"

if [ -z "$FOLDER" ]; then
  # main: one fresh commit with exactly these pictures (runs on main wait for each other)
  cd "$work" && git init -q -b "$BRANCH" && cp -a "$SRC/." . && git add -A
  git commit -q -m "Screenshots from ${SHA::7} (demo data)"
  for try in $(seq 1 "$TRIES"); do
    git push -q -f "$REMOTE" "$BRANCH" && { echo "Published to $BRANCH"; exit 0; }
    [ "$try" -lt "$TRIES" ] && sleep $((PAUSE * try))
  done
  echo "::error::Screenshots not pushed to $BRANCH"
  exit 1
fi

for try in $(seq 1 "$TRIES"); do
  rm -rf "$work/r" && mkdir "$work/r" && cd "$work/r" && git init -q -b "$BRANCH"
  base=
  if git fetch -q --depth 1 "$REMOTE" "refs/heads/$BRANCH" 2>/dev/null; then
    base=$(git rev-parse FETCH_HEAD)
    git reset -q --hard FETCH_HEAD
  fi
  # the root shows the latest run: its old files go, the folders stay
  find . -maxdepth 1 -type f -delete
  # folders of closed pull requests and deleted branches go; a state that cannot be read keeps the folder
  for d in pr-*/ branch-*/; do
    [ -d "$d" ] || continue
    d=${d%/}
    [ "$d" = "$FOLDER" ] && continue
    keep_folder "$d" || rm -rf "$d"
  done
  rm -rf "$FOLDER" && mkdir -p "$FOLDER"
  cp -a "$SRC/." "$FOLDER/"
  echo "$SHA" > "$FOLDER/HEAD.txt"
  [ -n "${SOURCE_BRANCH:-}" ] && echo "$SOURCE_BRANCH" > "$FOLDER/BRANCH.txt"
  cp -a "$FOLDER/." .
  rm -f BRANCH.txt
  echo "$FOLDER" > LATEST.txt
  git add -A
  # always one commit, so the branch does not grow with every run
  commit=$(git commit-tree "$(git write-tree)" -m "Screenshots of ${LABEL} from ${SHA::7} (demo data)")
  if git push -q --force-with-lease="refs/heads/$BRANCH:$base" "$REMOTE" "$commit:refs/heads/$BRANCH"; then
    echo "Published to $BRANCH/$FOLDER (attempt $try)"
    exit 0
  fi
  echo "Push to $BRANCH lost against another run (attempt $try of $TRIES), trying again"
  [ "$try" -lt "$TRIES" ] && sleep $((PAUSE * try))
done
echo "::warning::Preview screenshots not pushed to $BRANCH after $TRIES attempts; they are in the run's screenshots artifact"
exit 0
