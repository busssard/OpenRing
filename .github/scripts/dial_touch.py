#!/usr/bin/env python3
"""Where to touch the screen time clock in the emulator test (issues #109, #229).

Usage: dial_touch.py <screencap raw file> [centre x] [centre y] [radius]
(defaults 540 950 300, the clock's middle on the emulator after one slow swipe).
Looks at the pixels on a circle around the clock's middle and prints "x y" just inside the trailing
edge (clockwise) of the first lit phone use line, or nothing when there is none (screens.sh then taps
13:00). The file is `adb exec-out screencap` without -p: a small header (width and height first, as
little endian 32 bit numbers), then RGBA pixels.
"""
import math
import struct
import sys

STEPS = 3600


def lit(r, g, b):
    # The use lines are Ui.OXY 0xFF7CC8FF drawn at 30 to 100 % over the card 0xFF131A27, the night band
    # is Ui.SLEEP 0xFF8E9BFF at 55 % (Charts.ScreenDial). Green over red tells them apart: about 76 for a
    # line at full strength (45 at 55 %), 13 for the sleep colour at full and 10 at 55 %. The other
    # colours on the dial (Ui.LINE, Ui.ACTIVE, Ui.TEXT, the hour labels) have too little blue or green.
    return b > 150 and g > r + 30


def find(data, cx=540, cy=950, radius=300):
    w, h = struct.unpack("<II", data[:8])
    head = len(data) - w * h * 4
    if w <= 0 or h <= 0 or head < 8:
        return None

    def point(i):
        a = math.radians(i / 10.0)
        return int(cx + radius * math.sin(a)), int(cy - radius * math.cos(a))

    on = []
    for i in range(STEPS):
        x, y = point(i)
        if not (0 <= x < w and 0 <= y < h):
            on.append(False)
            continue
        o = head + (y * w + x) * 4
        on.append(lit(data[o], data[o + 1], data[o + 2]))
    for i in range(STEPS):
        if on[i] and not on[(i + 1) % STEPS]:
            return point(i - 2)
    return None


def main(argv):
    if len(argv) < 2:
        return 2
    with open(argv[1], "rb") as f:
        data = f.read()
    args = [int(v) for v in argv[2:5]]
    xy = find(data, *args)
    if xy:
        print(xy[0], xy[1])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
