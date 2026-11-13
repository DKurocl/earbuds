"""Generates the widget's earbud glyph (drawable/w_glyph_bud_left.xml and _right.xml).

    pip install numpy pillow potracer
    python3 docs/icon/bud_glyph.py

A flat, one-colour WF-1000XM5 earbud for the tonal discs of the widgets, after Pixel's own
battery widget: the silhouette of the product render (docs/icon/ref/wf1000xm5_bud.webp), the
ear tip set apart by a gap along the seam, and the round grille cut out. The seam is an arc of a
circle through two points on it; the grille's centre and size are measured from its gold colour.
"""
import math
from pathlib import Path

import numpy as np
import potrace
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
RENDER = ROOT / "docs/icon/ref/wf1000xm5_bud.webp"
OUT = ROOT / "app/src/main/res/drawable"
S = 4  # work at 4x the render for smooth edges

SEAM_CENTER, SEAM_RADIUS = (132.0, 80.0), 107.5  # render pixels; the arc passes (25, 92) and (88, 178)
GAP = 5.0  # between the shell and the ear tip
HOLE = 1.1  # grille hole relative to the measured grille


def mask() -> np.ndarray:
    src = Image.open(RENDER).convert("RGBA")
    size = src.size[0] * S
    src = src.resize((size, size), Image.LANCZOS)
    r, g, b, a = (np.asarray(c, dtype=float) for c in src.split())
    shape = a > 128
    gold = (r > g + 10) & (g > b + 25) & shape & (r > 90)
    ys, xs = np.nonzero(gold)
    gx, gy, gr = xs.mean(), ys.mean(), math.sqrt(gold.sum() / math.pi) * HOLE

    yy, xx = np.mgrid[0:size, 0:size] / S
    dist = np.hypot(xx - SEAM_CENTER[0], yy - SEAM_CENTER[1])
    glyph = shape & ((dist <= SEAM_RADIUS) | (dist >= SEAM_RADIUS + GAP))
    img = Image.fromarray((glyph * 255).astype("uint8"))
    ImageDraw.Draw(img).ellipse([gx - gr, gy - gr, gx + gr, gy + gr], fill=0)
    img = img.filter(ImageFilter.GaussianBlur(2)).point(lambda v: 255 if v > 127 else 0)
    return np.asarray(img) > 127


def path(m: np.ndarray) -> str:
    h, w = m.shape
    curves = []
    for c in potrace.Bitmap(m).trace(turdsize=200, alphamax=1.1, opticurve=True, opttolerance=0.8):
        xs = [c.start_point.x] + [s.end_point.x for s in c.segments]
        if min(xs) < 1 and max(xs) > w - 1:
            continue  # the frame of the bitmap itself
        curves.append(c)
    ys, xs = np.nonzero(m)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    scale = 22.0 / max(x1 - x0, y1 - y0)  # 24-unit viewport, 1-unit margin
    ox = 1 + (22 - (x1 - x0) * scale) / 2 - x0 * scale
    oy = 1 + (22 - (y1 - y0) * scale) / 2 - y0 * scale
    f = lambda p: f"{p.x * scale + ox:.1f},{p.y * scale + oy:.1f}"
    parts = []
    for c in curves:
        seg = [f"M{f(c.start_point)}"]
        for s in c.segments:
            seg.append(f"L{f(s.c)} L{f(s.end_point)}" if s.is_corner else f"C{f(s.c1)} {f(s.c2)} {f(s.end_point)}")
        parts.append(" ".join(seg) + "Z")
    return " ".join(parts)


def main():
    d = path(mask())
    for name, mirror in [("w_glyph_bud_left", False), ("w_glyph_bud_right", True)]:
        group = '<group android:pivotX="12" android:scaleX="-1">' if mirror else "<group>"
        (OUT / f"{name}.xml").write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- A WF-1000XM5 earbud as a flat glyph: the shell with the round grille, and the ear tip.
     Traced from the product render by docs/icon/bud_glyph.py. Tinted by the widget. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"
    tools:ignore="VectorPath">
    {group}<path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{d}"/></group>
</vector>
''')


if __name__ == "__main__":
    main()
