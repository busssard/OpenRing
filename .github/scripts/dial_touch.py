#!/usr/bin/env python3
"""Where to touch the screen time clock in the emulator test (issues #109, #229, #261).

Usage: dial_touch.py <screencap raw file> --view <x1> <y1> <x2> <y2> <density>
   or: dial_touch.py <screencap raw file> [centre x] [centre y] [radius]

With --view (the clock's bounds from the uiautomator dump, content-desc "Phone use clock", and the
screen density) the circle follows the drawing in Charts.ScreenDial: its middle is the middle of the
view, its radius 0.87 of the dial's r (the use lines run from 0.75 r to r, r = half the smaller side
less 18 dp). The bounds are only taken when the view is its full 300 dp high (Pages.java), not cut off
at a screen edge. Then it prints "x y" just inside the trailing edge (clockwise) of the first lit phone
use line, or 13:00 on the same circle when there is none.

Without --view the defaults are 540 1047 300: the clock's middle on the emulator after one slow swipe,
measured on pr-238/screen-touch.png (the hour ring at x 540 spans y 805 to 1290). They only fit that
screen, swipe and layout; nothing is printed when no line is found (screens.sh then taps a fixed point).

The file is `adb exec-out screencap` without -p: a small header (width and height first, as little
endian 32 bit numbers), then RGBA pixels.
"""
import math
import struct
import sys

STEPS = 3600
DIAL_DP = 300  # the clock's height in Pages.java (Ui.sized(c, -1, 300))


def lit(r, g, b):
    # The use lines are Ui.OXY 0xFF7CC8FF drawn at 30 to 100 % over the card 0xFF131A27, the night band
    # is Ui.SLEEP 0xFF8E9BFF at 55 % (Charts.ScreenDial). Green over red tells them apart: about 76 for a
    # line at full strength (45 at 55 %), 13 for the sleep colour at full and 10 at 55 %. The other
    # colours on the dial (Ui.LINE, Ui.ACTIVE, Ui.TEXT, the hour labels) have too little blue or green.
    return b > 150 and g > r + 30


def circle(x1, y1, x2, y2, density):
    """The circle through the use lines for the clock's view bounds, or None when the view is cut off."""
    dp = density / 160.0
    w, h = x2 - x1, y2 - y1
    if w <= 0 or abs(h - DIAL_DP * dp) > 0.05 * DIAL_DP * dp:
        return None
    r = min(w, h) / 2.0 - 18 * dp
    return (x1 + x2) // 2, (y1 + y2) // 2, int(round(0.87 * r))


def point(cx, cy, radius, deg):
    a = math.radians(deg)
    return int(cx + radius * math.sin(a)), int(cy - radius * math.cos(a))


def find(data, cx=540, cy=1047, radius=300):
    if len(data) < 8:
        return None
    w, h = struct.unpack("<II", data[:8])
    head = len(data) - w * h * 4
    if w <= 0 or h <= 0 or head < 8:
        return None
    on = []
    for i in range(STEPS):
        x, y = point(cx, cy, radius, i / 10.0)
        if not (0 <= x < w and 0 <= y < h):
            on.append(False)
            continue
        o = head + (y * w + x) * 4
        on.append(lit(data[o], data[o + 1], data[o + 2]))
    for i in range(STEPS):
        if on[i] and not on[(i + 1) % STEPS]:
            return point(cx, cy, radius, (i - 2) / 10.0)
    return None


def main(argv):
    if len(argv) < 2:
        return 2
    with open(argv[1], "rb") as f:
        data = f.read()
    if len(argv) > 2 and argv[2] == "--view":
        if len(argv) < 8:
            return 2
        c = circle(*[int(v) for v in argv[3:8]])
        if c is None:
            return 0
        xy = find(data, *c) or point(c[0], c[1], c[2], 195)  # 13:00
    else:
        xy = find(data, *[int(v) for v in argv[2:5]])
    if xy:
        print(xy[0], xy[1])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
