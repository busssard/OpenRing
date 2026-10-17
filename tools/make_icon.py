#!/usr/bin/env python3
"""Draws the Open Ring icon: a ring seen slightly from above, its band thick at the top where a
small infinity sign is cut out (the ring keeps working for good, no cloud needed). One shape, so it
works in one plain colour: the launcher icon, Android's themed icon, the notification icon and the
README picture are all made from it.

    pip install shapely cairosvg     # cairosvg only for docs/images/icon.png
    python3 tools/make_icon.py
"""
import math, os
from shapely.geometry import Polygon, LineString
from shapely import affinity

HIGHLIGHT = "#8E9BFF"   # Ui.SLEEP, the app's highlight colour
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")


def ellipse(cx, cy, rx, ry, n=240):
    return Polygon([(cx + rx * math.cos(2 * math.pi * i / n), cy + ry * math.sin(2 * math.pi * i / n)) for i in range(n)])


def shape():
    """The icon in unit size: the ring's outer edge is 1 wide on each side of 0."""
    outer = ellipse(0, 0, 1.0, 0.93)
    hole = ellipse(0, 0.19, 0.735, 0.61)          # lower than the middle: thick band on top, thin below
    a = 0.44                                      # lemniscate of Bernoulli, half width a
    pts = []
    for i in range(400):
        t = 2 * math.pi * i / 400
        d = 1 + math.sin(t) ** 2
        pts.append((a * math.cos(t) / d, a * math.sin(t) * math.cos(t) / d))
    pts.append(pts[0])
    inf = LineString(pts).buffer(0.062, resolution=24)
    inf = affinity.translate(inf, 0, -0.665)
    return outer.difference(hole).difference(inf)


def path(geom, cx, cy, s, nd=2):
    """SVG and Android path data for the shape scaled by s around (cx, cy)."""
    g = affinity.scale(geom, s, s, origin=(0, 0)).simplify(0.01 * s / 20)
    g = affinity.translate(g, cx, cy)
    polys = list(g.geoms) if hasattr(g, "geoms") else [g]
    out = []
    f = "%." + str(nd) + "f"
    for p in polys:
        for ring in [p.exterior] + list(p.interiors):
            c = list(ring.coords)[:-1]
            out.append("M" + " L".join((f + "," + f) % xy for xy in c) + "Z")
    return "".join(out)


def write(rel, text):
    with open(os.path.join(ROOT, rel), "w") as fh:
        fh.write(text)


def main():
    g = shape()
    fg = path(g, 54, 54.5, 25)
    write("app/src/main/res/drawable/ic_launcher_foreground.xml", f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <!-- a ring seen slightly from above with a small infinity sign cut into its band: it keeps working
         for good, no cloud needed. Made by tools/make_icon.py; one shape, so the themed icon works too -->
    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd"
        android:pathData="{fg}" />
</vector>
''')
    st = path(g, 12, 12.4, 10.6)
    write("app/src/main/res/drawable/ic_stat_ring.xml", f'''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <!-- notification icon: the ring with its infinity sign, white on transparent (tools/make_icon.py) -->
    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd"
        android:pathData="{st}" />
</vector>
''')
    big = path(g, 256, 258, 160, 1)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">
  <rect width="512" height="512" rx="112" fill="{HIGHLIGHT}"/>
  <path fill="#FFFFFF" fill-rule="evenodd" d="{big}"/>
</svg>
'''
    write("docs/images/icon.svg", svg)
    try:
        import cairosvg
        cairosvg.svg2png(bytestring=svg.encode(), write_to=os.path.join(ROOT, "docs/images/icon.png"), output_width=512, output_height=512)
    except ImportError:
        print("cairosvg missing: docs/images/icon.png not updated")


if __name__ == "__main__":
    main()
