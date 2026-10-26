#!/bin/bash
# Tests for the emulator CI scripts (issues #57 and #160), with a local bare repository as GitHub.
set -u
cd "$(dirname "$0")/.."
here=$(pwd)
PUB="$here/.github/scripts/publish_screens.sh"
SAME="$here/.github/scripts/same_shots.sh"
t=$(mktemp -d)
trap 'rm -rf "$t"' EXIT
fails=0
passes=0
ok() { echo "ok    $1"; passes=$((passes + 1)); }
bad() { echo "FAIL  $1"; fails=$((fails + 1)); }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
git init -q --bare "$t/remote.git"
export REMOTE="$t/remote.git" PAUSE=0 TRIES=3 BRANCH=screenshots-preview
export PR_STATE="echo open"
shots() { rm -rf "$t/$1"; mkdir -p "$t/$1"; for n in today sleep; do echo "$1 $n" > "$t/$1/$n.png"; done; echo "log $1" > "$t/$1/logcat.txt"; }
show() { git --git-dir="$t/remote.git" show "$BRANCH:$1" 2>/dev/null; }
files() { git --git-dir="$t/remote.git" ls-tree -r --name-only "$BRANCH" 2>/dev/null; }

# A stand in for git, put first in PATH only by trouble(): FAIL_ONCE=<git command> makes that
# command fail once; RACE_ONCE=<script> runs another publish (with the real git) right before the
# first push, as a second pull request finishing at the same moment would. The script itself has no
# test hook.
export T="$t" REAL_GIT="$(command -v git)" PUB
mkdir -p "$t/bin"
cat > "$t/bin/git" <<'EOF'
#!/bin/bash
if [ -n "${FAIL_ONCE:-}" ] && [ "$1" = "$FAIL_ONCE" ] && [ ! -e "$T/failed" ]; then touch "$T/failed"; exit 1; fi
if [ -n "${RACE_ONCE:-}" ] && [ "$1" = push ] && [ ! -e "$T/raced" ]; then
  touch "$T/raced"
  env -u FAIL_ONCE -u RACE_ONCE PATH="${PATH#$T/bin:}" "$RACE_ONCE"
fi
exec "$REAL_GIT" "$@"
EOF
chmod +x "$t/bin/git"
trouble() { rm -f "$t/failed" "$t/raced"; PATH="$t/bin:$PATH" "$@"; }

# no screenshots at all (the emulator failed first): clean skip
out=$(SRC="$t/missing" FOLDER=pr-1 SHA=aaaaaaa "$PUB" 2>&1); code=$?
check "no folder: exit 0" '[ $code = 0 ]'
check "no folder: notice" 'echo "$out" | grep -q "No screenshots to publish"'
mkdir -p "$t/empty"
SRC="$t/empty" FOLDER=pr-1 SHA=aaaaaaa "$PUB" >/dev/null 2>&1
check "empty folder: exit 0, nothing pushed" '[ $? = 0 ] && [ -z "$(files)" ]'

# two pull requests: each keeps its own folder, the root shows the latest
shots a; shots b
SRC="$t/a" FOLDER=pr-1 SHA=1111111aaaa "$PUB" >/dev/null 2>&1
SRC="$t/b" FOLDER=pr-2 SHA=2222222bbbb "$PUB" >/dev/null 2>&1
check "pr-1 kept after pr-2" '[ "$(show pr-1/today.png)" = "a today" ]'
check "pr-2 has its pictures" '[ "$(show pr-2/sleep.png)" = "b sleep" ]'
check "HEAD.txt names the head commit" '[ "$(show pr-1/HEAD.txt)" = "1111111aaaa" ] && [ "$(show pr-2/HEAD.txt)" = "2222222bbbb" ]'
check "root is the latest run" '[ "$(show today.png)" = "b today" ] && [ "$(show LATEST.txt)" = "pr-2" ]'
check "branch stays one commit" '[ "$(git --git-dir="$t/remote.git" rev-list --count $BRANCH)" = 1 ]'
check "message names the PR and commit" '[ "$(git --git-dir="$t/remote.git" log -1 --format=%s $BRANCH)" = "Screenshots of #2 from 2222222 (demo data)" ]'

# a run of the same PR again: its folder is replaced, a picture that is gone goes
rm "$t/a/sleep.png"; echo "a2 today" > "$t/a/today.png"
SRC="$t/a" FOLDER=pr-1 SHA=1111112 "$PUB" >/dev/null 2>&1
check "rerun replaces only its folder" '[ "$(show pr-1/today.png)" = "a2 today" ] && ! files | grep -q "^pr-1/sleep.png" && [ "$(show pr-2/today.png)" = "b today" ]'

# a race: another pull request pushes after this run fetched; the lease refuses, the retry keeps both
shots c; shots d
cat > "$t/other.sh" <<'EOF'
#!/bin/bash
SRC="$T/d" FOLDER=pr-4 SHA=4444444 "$PUB" >/dev/null 2>&1
EOF
chmod +x "$t/other.sh"
out=$(SRC="$t/c" FOLDER=pr-3 SHA=3333333 RACE_ONCE="$t/other.sh" trouble "$PUB" 2>&1); code=$?
check "race: exit 0" '[ $code = 0 ]'
check "race: retried" 'echo "$out" | grep -q "attempt 2"'
check "race: both folders there" '[ "$(show pr-3/today.png)" = "c today" ] && [ "$(show pr-4/today.png)" = "d today" ] && [ "$(show pr-1/today.png)" = "a2 today" ]'

# the fetch fails once although the branch exists: the lease (expecting no branch) refuses, the retry works
out=$(SRC="$t/c" FOLDER=pr-3 SHA=3333334 FAIL_ONCE=fetch trouble "$PUB" 2>&1); code=$?
check "failed fetch: refused, then pushed with every folder" '[ $code = 0 ] && echo "$out" | grep -q "attempt 2" && [ "$(show pr-3/HEAD.txt)" = 3333334 ] && [ "$(show pr-4/today.png)" = "d today" ] && [ "$(show pr-1/today.png)" = "a2 today" ]'

# closed pull requests lose their folder, an unknown state keeps it (and says so); with a refused
# first push, so it also shows that each state is read only once
cat > "$t/state.sh" <<'EOF'
#!/bin/bash
echo "$1" >> "$T/asked"
case "$1" in 2) echo closed ;; 3) exit 1 ;; *) echo open ;; esac
EOF
chmod +x "$t/state.sh"; rm -f "$t/asked"
out=$(PR_STATE="$t/state.sh" SRC="$t/a" FOLDER=pr-1 SHA=1111113 FAIL_ONCE=push trouble "$PUB" 2>&1)
check "closed PR folder dropped" '! files | grep -q "^pr-2/" && echo "$out" | grep -q "pr 2 is closed, dropping pr-2"'
check "unknown state keeps the folder and says so" 'files | grep -q "^pr-3/today.png" && echo "$out" | grep -q "state of pr-3 unknown, kept"'
check "open PR folder kept" 'files | grep -q "^pr-4/today.png"'
check "each state read once despite the retry" 'echo "$out" | grep -q "attempt 2" && [ "$(sort "$t/asked" | tr "\n" " ")" = "2 3 4 " ]'

# a manual run on a branch: its folder stays while the branch exists and goes once it is deleted
git --git-dir="$t/remote.git" update-ref refs/heads/feature/x "$(git --git-dir="$t/remote.git" rev-parse $BRANCH)"
SRC="$t/b" FOLDER=branch-feature-x SOURCE_BRANCH=feature/x SHA=6666666 "$PUB" >/dev/null 2>&1
check "branch folder keeps its branch name" '[ "$(show branch-feature-x/BRANCH.txt)" = feature/x ] && ! files | grep -q "^BRANCH.txt"'
SRC="$t/a" FOLDER=pr-1 SHA=1111114 "$PUB" >/dev/null 2>&1
check "branch folder kept while the branch exists" 'files | grep -q "^branch-feature-x/today.png"'
git --git-dir="$t/remote.git" update-ref -d refs/heads/feature/x
out=$(SRC="$t/a" FOLDER=pr-1 SHA=1111115 "$PUB" 2>&1)
check "branch folder dropped after the branch is deleted" '! files | grep -q "^branch-feature-x/" && echo "$out" | grep -q "branch of branch-feature-x is deleted"'

# a remote that always refuses: a warning for previews, an error for main
out=$(REMOTE="$t/nothing.git" SRC="$t/a" FOLDER=pr-1 SHA=1 "$PUB" 2>&1); code=$?
check "preview refused: exit 0 with a warning" '[ $code = 0 ] && echo "$out" | grep -q "::warning::Preview screenshots not pushed"'
out=$(REMOTE="$t/nothing.git" SRC="$t/a" BRANCH=screenshots FOLDER= SHA=1 "$PUB" 2>&1); code=$?
check "main refused: exit 1 with an error" '[ $code = 1 ] && echo "$out" | grep -q "::error::"'

# main: a failed push is retried, then exactly this run's pictures, no folders
out=$(SRC="$t/a" BRANCH=screenshots FOLDER= SHA=7777777 FAIL_ONCE=push trouble "$PUB" 2>&1); code=$?
check "main: pushed on the retry" '[ $code = 0 ] && [ -e "$t/failed" ] && [ "$(BRANCH=screenshots show today.png)" = "a2 today" ]'
SRC="$t/b" BRANCH=screenshots FOLDER= SHA=5555555 "$PUB" >/dev/null 2>&1
check "main: exactly the pictures" '[ "$(BRANCH=screenshots files | tr "\n" " ")" = "logcat.txt sleep.png today.png " ]'

# same_shots.sh (issue #160)
mkdir -p "$t/s"; echo 1 > "$t/s/a.png"; echo 2 > "$t/s/b.png"; echo 3 > "$t/s/c.png"
"$SAME" "$t/s" >/dev/null 2>&1
check "different shots pass" '[ $? = 0 ]'
out=$("$SAME" "$t/nowhere" 2>&1); code=$?
check "no shots to compare fails" '[ $code = 1 ] && echo "$out" | grep -q "::error::No screenshots in"'
echo 1 > "$t/s/settings-games.png"
out=$("$SAME" "$t/s" 2>&1); code=$?
check "identical shots fail and are named" '[ $code = 1 ] && echo "$out" | grep -q "Screenshots a and settings-games are the same picture"'

echo "CI scripts: $passes passed, $fails failed"
[ "$fails" = 0 ]
