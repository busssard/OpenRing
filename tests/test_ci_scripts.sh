#!/bin/bash
# Tests for the emulator CI scripts (issues #57, #160 and #229), with a local bare repository as GitHub.
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

# dial_touch.py (issues #109, #229, #261): made up screen pictures of 200 x 200 px with the clock's middle at
# 100 100 and a circle of 60 px; a ring from 55 to 65 px drawn from 30 to 300 degrees (clockwise from the
# top, or other angles given) in a colour blended over the card, the rest the card
DIAL="$here/.github/scripts/dial_touch.py"
picture() {  # $1 file, $2 colour (hex, empty for none), $3 strength, $4 $5 from and to angle (30 300)
  python3 -I - "$1" "$2" "$3" "${4:-30}" "${5:-300}" <<'PY'
import math, struct, sys
path, colour, a = sys.argv[1], sys.argv[2], float(sys.argv[3])
start, end = float(sys.argv[4]), float(sys.argv[5])
card = (0x13, 0x1A, 0x27)
w = h = 200
px = bytearray(bytes((card[0], card[1], card[2], 255)) * (w * h))
if colour:
    c = int(colour, 16)
    rgb = tuple(round(((c >> s) & 255) * a + k * (1 - a)) for s, k in zip((16, 8, 0), card))
    for y in range(h):
        for x in range(w):
            d = math.hypot(x - 100, y - 100)
            ang = math.degrees(math.atan2(x - 100, 100 - y)) % 360
            if 55 <= d <= 65 and (start <= ang <= end if start <= end else (ang >= start or ang <= end)):
                o = (y * w + x) * 4
                px[o:o + 3] = bytes(rgb)
open(path, "wb").write(struct.pack("<IIII", w, h, 1, 1) + bytes(px))
PY
}
# the angle (clockwise from the top) of a printed point, empty when nothing was printed
angle() { [ -n "$1" ] && python3 -I -c 'import math, sys; x, y = map(int, sys.argv[1:3]); print(round(math.degrees(math.atan2(x - 100, 100 - y)) % 360))' $1; }
for strength in 1 0.6; do  # below about 53 % the blue stays under 150 (as before #229)
  picture "$t/line.raw" 7CC8FF $strength
  out=$(python3 -I "$DIAL" "$t/line.raw" 100 100 60); a=$(angle "$out")
  check "dial: a use line at $strength finds its trailing edge" '[ -n "$a" ] && [ "$a" -ge 297 ] && [ "$a" -le 300 ]'
done
for strength in 0.55 1; do
  picture "$t/sleep.raw" 8E9BFF $strength
  out=$(python3 -I "$DIAL" "$t/sleep.raw" 100 100 60)
  check "dial: the night band colour at $strength is not taken" '[ -z "$out" ]'
done
picture "$t/card.raw" "" 1
out=$(python3 -I "$DIAL" "$t/card.raw" 100 100 60)
check "dial: an empty card prints nothing" '[ -z "$out" ]'
out=$(python3 -I "$DIAL" "$t/card.raw" 2>&1); code=$?
check "dial: a circle off the picture (default 540 1047 300) prints nothing" '[ $code = 0 ] && [ -z "$out" ]'
# a ring across midnight (issue #261): from 300 to 30 degrees, the trailing edge is the one near 30
picture "$t/midnight.raw" 7CC8FF 1 300 30
out=$(python3 -I "$DIAL" "$t/midnight.raw" 100 100 60); a=$(angle "$out")
check "dial: a ring across 0 degrees finds its edge near 30" '[ -n "$a" ] && [ "$a" -ge 27 ] && [ "$a" -le 30 ]'
# lit all round there is no trailing edge
picture "$t/full.raw" 7CC8FF 1 0 360
out=$(python3 -I "$DIAL" "$t/full.raw" 100 100 60)
check "dial: a ring lit all round prints nothing" '[ -z "$out" ]'
# what a failed screencap gives: the header only (1080 x 2400, no pixels), or not even that
python3 -I -c 'import struct, sys; open(sys.argv[1], "wb").write(struct.pack("<II", 1080, 2400))' "$t/head.raw"
out=$(python3 -I "$DIAL" "$t/head.raw" 2>&1); code=$?
check "dial: a header only file prints nothing" '[ $code = 0 ] && [ -z "$out" ]'
: > "$t/empty.raw"
out=$(python3 -I "$DIAL" "$t/empty.raw" 2>&1); code=$?
check "dial: an empty file prints nothing" '[ $code = 0 ] && [ -z "$out" ]'
# --view: the circle from the clock's bounds in the screen dump. A made up clock view of 180 x 160 px at
# density 85 (so its 300 dp are 160 px): r = 80 - 18 * 85 / 160 = 70.4, the circle 0.87 r = 61 px around
# 100 100, on the ring from 55 to 65 px
out=$(python3 -I "$DIAL" "$t/line.raw" --view 10 20 190 180 85 2>&1); a=$(angle "$out")
check "dial: --view finds the line from the clock's bounds" '[ -n "$a" ] && [ "$a" -ge 297 ] && [ "$a" -le 300 ]'
out=$(python3 -I "$DIAL" "$t/card.raw" --view 10 20 190 180 85 2>&1); a=$(angle "$out")
check "dial: --view with no line touches 13:00" '[ "$a" = 195 ]'
out=$(python3 -I "$DIAL" "$t/line.raw" --view 10 20 190 120 85 2>&1); code=$?
check "dial: --view with a clock cut off at the screen edge prints nothing" '[ $code = 0 ] && [ -z "$out" ]'
# the fixed tap in screens.sh when dial_touch.py prints nothing is 13:00 on its fixed circle (issue #300)
fallback=$(sed -n 's/^DIAL_FALLBACK="\(.*\)"$/\1/p' "$here/.github/scripts/screens.sh")
expected=$(python3 -I -B -c 'import importlib.util, sys
s = importlib.util.spec_from_file_location("dial_touch", sys.argv[1]); m = importlib.util.module_from_spec(s); s.loader.exec_module(m)
print(*m.point(540, 1047, 300, 195))' "$DIAL")
check "dial: the fallback tap in screens.sh ($fallback) is 13:00 on the fixed circle ($expected)" '[ -n "$fallback" ] && [ "$fallback" = "$expected" ]'

# screens.sh with an adb stand in that gets stuck (issue #278): the run must stop within its limits,
# with a line that says what happened, instead of waiting for the job timeout.
SCREENS="$here/.github/scripts/screens.sh"
mkdir -p "$t/adbbin"
cat > "$t/adbbin/adb" <<'EOF2'
#!/bin/bash
case "$FAKE_ADB:$1:$2" in
  noboot:shell:while*) sleep 30 ;;
  nodevice:wait-for-device:*) sleep 30 ;;
  stuck:shell:pm) sleep 30 ;;
  quiet:shell:am) sleep 30 ;;
  offline:shell:while*) echo "error: device offline" >&2; exit 1 ;;
esac
exit 0
EOF2
chmod +x "$t/adbbin/adb"
stuck_run() {
  local start=$SECONDS
  out=$(cd "$t" && FAKE_ADB=$1 PATH="$t/adbbin:$PATH" OUT="$t/shots278" BOOT_WAIT=2 ADB_TIMEOUT=2 bash "$SCREENS" 2>&1); code=$?
  took=$((SECONDS - start))
}
stuck_run noboot
check "screens: an emulator that never finishes booting fails within the limit" '[ $code != 0 ] && [ $took -lt 15 ]'
check "screens: ... and says the emulator did not finish starting" 'echo "$out" | grep -q "did not finish starting within 2 s (not caused by the app)"'
stuck_run nodevice
check "screens: no device at all fails with the same line" '[ $code != 0 ] && [ $took -lt 15 ] && echo "$out" | grep -q "did not finish starting"'
stuck_run stuck
check "screens: a stuck adb call after booting fails within its limit" '[ $code != 0 ] && [ $took -lt 15 ]'
check "screens: ... and names the call" 'echo "$out" | grep -q "adb shell pm grant app.openring android.permission.BLUETOOTH_SCAN did not answer within 2 s"'
stuck_run quiet
check "screens: a stuck call with its output sent away still prints its line" '[ $code != 0 ] && [ $took -lt 15 ] && echo "$out" | grep -q "adb shell am broadcast -a com.android.systemui.demo -e command enter did not answer within 2 s"'
stuck_run offline
check "screens: adb failing at once while starting says so, not a time limit" '[ $code != 0 ] && [ $took -lt 5 ] && echo "$out" | grep -q "could not reach the emulator while it was starting (adb shell ended with code 1"'

# ---- branch cleanup (.github/scripts/stale_branches.py): which branches go, which stay ----
bc=$(mktemp -d)
cat > "$bc/plan.json" <<'JSON'
{"now": "2026-11-03T20:00:00Z",
 "branches": {"main": "m1", "dev": "d1", "screenshots-preview": "s1", "badges": "b1",
   "feat/merged": "a1", "feat/moved": "a2", "feat/base-of-open": "c1", "feat/open": "o1",
   "feat/closed-old": "x1", "feat/closed-new": "y1", "feat/closed-moved": "z2", "wip/no-pr": "w1"},
 "prs": [
   {"head": "feat/merged", "head_sha": "a1", "base": "dev", "state": "closed", "merged_at": "2026-11-01T10:00:00Z", "closed_at": "2026-11-01T10:00:00Z"},
   {"head": "feat/moved", "head_sha": "a1x", "base": "dev", "state": "closed", "merged_at": "2026-11-01T10:00:00Z", "closed_at": "2026-11-01T10:00:00Z"},
   {"head": "feat/base-of-open", "head_sha": "c1", "base": "dev", "state": "closed", "merged_at": "2026-11-02T10:00:00Z", "closed_at": "2026-11-02T10:00:00Z"},
   {"head": "feat/open", "head_sha": "o1", "base": "feat/base-of-open", "state": "open", "merged_at": null, "closed_at": null},
   {"head": "feat/closed-old", "head_sha": "x1", "base": "dev", "state": "closed", "merged_at": null, "closed_at": "2026-10-01T10:00:00Z"},
   {"head": "feat/closed-new", "head_sha": "y1", "base": "dev", "state": "closed", "merged_at": null, "closed_at": "2026-10-30T10:00:00Z"},
   {"head": "feat/closed-moved", "head_sha": "z1", "base": "dev", "state": "closed", "merged_at": null, "closed_at": "2026-10-01T10:00:00Z"},
   {"head": "dev", "head_sha": "d0", "base": "main", "state": "closed", "merged_at": "2026-11-03T10:00:00Z", "closed_at": "2026-11-03T10:00:00Z"},
   {"head": "main", "head_sha": "zz", "base": "dev", "state": "closed", "merged_at": null, "closed_at": "2026-09-01T10:00:00Z", "same_repo": false}
 ]}
JSON
out=$(python3 .github/scripts/stale_branches.py --plan "$bc/plan.json")
check "branches: a merged branch is deleted" 'echo "$out" | grep -qx "delete feat/merged (merged)"'
check "branches: commits after the merge keep it" 'echo "$out" | grep -qx "keep feat/moved (commits after the merge)"'
check "branches: the base of an open pull request stays (stacked)" 'echo "$out" | grep -qx "keep feat/base-of-open (an open pull request uses it)"'
check "branches: an open pull request keeps its branch" 'echo "$out" | grep -qx "keep feat/open (an open pull request uses it)"'
check "branches: closed without merging over 14 days ago is deleted" 'echo "$out" | grep -qx "delete feat/closed-old (closed without merging)"'
check "branches: closed without merging lately stays" 'echo "$out" | grep -q "^keep feat/closed-new (closed without merging less than"'
check "branches: commits after closing keep it" 'echo "$out" | grep -qx "keep feat/closed-moved (commits after it was closed)"'
check "branches: no pull request yet stays" 'echo "$out" | grep -qx "keep wip/no-pr (no pull request yet)"'
check "branches: main, dev, screenshots and badges always stay" '[ "$(echo "$out" | grep -c "(always kept)")" = 4 ] && ! echo "$out" | grep -q "^delete \(main\|dev\|badges\|screenshots-preview\) "'
check "branches: exactly two deleted" '[ "$(echo "$out" | grep -c "^delete ")" = 2 ]'
rm -rf "$bc"

echo "CI scripts: $passes passed, $fails failed"
[ "$fails" = 0 ]
