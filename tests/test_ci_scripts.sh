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
check "message names the folder and commit" 'git --git-dir="$t/remote.git" log -1 --format=%s $BRANCH | grep -q "pr-2 from 2222222"'

# a run of the same PR again: its folder is replaced, a picture that is gone goes
rm "$t/a/sleep.png"; echo "a2 today" > "$t/a/today.png"
SRC="$t/a" FOLDER=pr-1 SHA=1111112 "$PUB" >/dev/null 2>&1
check "rerun replaces only its folder" '[ "$(show pr-1/today.png)" = "a2 today" ] && ! files | grep -q "^pr-1/sleep.png" && [ "$(show pr-2/today.png)" = "b today" ]'

# a race: another pull request pushes after this run fetched; the lease fails, the retry keeps both
shots c; shots d
export SRC_D="$t/d" PUB
out=$(SRC="$t/c" FOLDER=pr-3 SHA=3333333 BEFORE_PUSH='[ "$try" = 1 ] && SRC="$SRC_D" FOLDER=pr-4 SHA=4444444 BEFORE_PUSH= "$PUB" >/dev/null 2>&1' "$PUB" 2>&1); code=$?
check "race: exit 0" '[ $code = 0 ]'
check "race: retried" 'echo "$out" | grep -q "attempt 2"'
check "race: both folders there" '[ "$(show pr-3/today.png)" = "c today" ] && [ "$(show pr-4/today.png)" = "d today" ] && [ "$(show pr-1/today.png)" = "a2 today" ]'

# closed pull requests lose their folder, an unknown state keeps it
cat > "$t/state.sh" <<'EOF'
#!/bin/bash
case "$1" in 2) echo closed ;; 3) exit 1 ;; *) echo open ;; esac
EOF
chmod +x "$t/state.sh"
PR_STATE="$t/state.sh" SRC="$t/a" FOLDER=pr-1 SHA=1111113 "$PUB" >/dev/null 2>&1
check "closed PR folder dropped" '! files | grep -q "^pr-2/"'
check "unknown state keeps the folder" 'files | grep -q "^pr-3/today.png"'
check "open PR folder kept" 'files | grep -q "^pr-4/today.png"'

# a remote that always refuses: a warning for previews, an error for main
out=$(REMOTE="$t/nothing.git" SRC="$t/a" FOLDER=pr-1 SHA=1 "$PUB" 2>&1); code=$?
check "preview refused: exit 0 with a warning" '[ $code = 0 ] && echo "$out" | grep -q "::warning::Preview screenshots not pushed"'
out=$(REMOTE="$t/nothing.git" SRC="$t/a" BRANCH=screenshots FOLDER= SHA=1 "$PUB" 2>&1); code=$?
check "main refused: exit 1 with an error" '[ $code = 1 ] && echo "$out" | grep -q "::error::"'

# main: exactly this run's pictures, no folders
SRC="$t/b" BRANCH=screenshots FOLDER= SHA=5555555 "$PUB" >/dev/null 2>&1
check "main: exactly the pictures" '[ "$(BRANCH=screenshots files | tr "\n" " ")" = "logcat.txt sleep.png today.png " ]'

# same_shots.sh (issue #160)
mkdir -p "$t/s"; echo 1 > "$t/s/a.png"; echo 2 > "$t/s/b.png"; echo 3 > "$t/s/c.png"
"$SAME" "$t/s" >/dev/null 2>&1
check "different shots pass" '[ $? = 0 ]'
echo 1 > "$t/s/settings-games.png"
out=$("$SAME" "$t/s" 2>&1); code=$?
check "identical shots fail and are named" '[ $code = 1 ] && echo "$out" | grep -q "Screenshots a and settings-games are the same picture"'

echo "CI scripts: $passes passed, $fails failed"
[ "$fails" = 0 ]
