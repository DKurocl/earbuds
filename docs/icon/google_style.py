"""Archived blue launcher concept: white background, one bold geometric shape,
saturated blues with smooth top-down gradients for soft volume, a small green accent.
Writes preview assets to the given directory. The active launcher and splash use brand_icon.py.

Run from the repository root:  python3 docs/icon/google_style.py <preview-dir>
"""
import sys
from pathlib import Path

# Geometry in the 108 x 108 adaptive-icon viewport (66 dp safe zone = 21..87). The outline is the
# real case's, measured from Sony's front shot (docs/icon/ref/wf1000xm5_case.webp): a domed lid that
# rounds in over its top third, straight sides, a base that tucks in towards a flatter bottom.
L, R = 29.0, 79.0          # case width 50: the visual weight of Google's Docs/Drive glyphs
TOP, BOTTOM = 38.0, 70.6   # height / width 0.652, as the case
SEAM = 51.9                # the lid's seam at 43.8 % of the height, as on the case
GAP = 1.6                  # white seam between lid and body
# (fraction of height, half-width as a share of the width), from the shot.
PROFILE = [
    (0.000, 0.000), (0.004, 0.110), (0.012, 0.175), (0.025, 0.225), (0.045, 0.282), (0.070, 0.345),
    (0.100, 0.393), (0.150, 0.444), (0.200, 0.472), (0.300, 0.497), (0.400, 0.500), (0.500, 0.4995),
    (0.600, 0.497), (0.700, 0.486), (0.800, 0.454), (0.850, 0.430), (0.900, 0.399), (0.940, 0.366),
    (0.970, 0.327), (0.988, 0.270), (0.997, 0.180), (1.000, 0.000),
]


# The profile, fitted: two superellipse quarters, the lid's dome over the top 42 % and the base's
# rounder curve below 45 %, with straight sides between (fit error under 0.5 % of the width).
DOME = (0.42, 3.15, 2.25)   # (where it ends, exponent along the height, exponent across)
BASE = (0.45, 2.40, 4.55)


def half_width(f):
    import math
    for mid, end, (n, m) in ((DOME[0], 0.0, DOME[1:]), (BASE[0], 1.0, BASE[1:])):
        if (f <= mid) if end == 0.0 else (f >= mid):
            d = min(1.0, abs(f - mid) / abs(end - mid))
            return 0.5 * max(0.0, 1 - d ** n) ** (1 / m)
    return 0.5


def _side(f0, f1):
    """The right side of the outline between two heights (top to bottom), sampled along each
    superellipse by angle so the rounded ends get their points."""
    import math
    fs = set()
    for mid, end, (n, m) in ((DOME[0], 0.0, DOME[1:]), (BASE[0], 1.0, BASE[1:])):
        for k in range(17):
            th = math.pi / 2 * k / 16
            d = math.sin(th) ** (2 / n)
            fs.add(round(mid + (end - mid) * d, 6))
    fs = sorted(f for f in fs if f0 < f < f1) 
    fs = [f0] + fs + [f1]
    w, h, cx = R - L, BOTTOM - TOP, (L + R) / 2
    return [(cx + half_width(f) * w, TOP + f * h) for f in fs]


def _curve(points):
    """A smooth curve through points (Catmull-Rom as cubic Beziers), from the first point on."""
    f = lambda v: f"{v:.2f}".rstrip("0").rstrip(".")
    d = ""
    n = len(points)
    for i in range(n - 1):
        p0 = points[max(i - 1, 0)]; p1 = points[i]; p2 = points[i + 1]; p3 = points[min(i + 2, n - 1)]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        d += f" C{f(c1[0])},{f(c1[1])} {f(c2[0])},{f(c2[1])} {f(p2[0])},{f(p2[1])}"
    return d


def _mirror(points):
    return [(L + R - x, y) for x, y in points]


def _part(f0, f1):
    """The case between heights f0..f1 (shares of its height): down the right side, across the
    cut (or the bottom), up the left side; a straight edge where the case is cut at the seam."""
    f = lambda v: f"{v:.2f}".rstrip("0").rstrip(".")
    right = _side(f0, f1)
    left = list(reversed(_mirror(right)))
    return f"M{f(right[0][0])},{f(right[0][1])}" + _curve(right) + f" L{f(left[0][0])},{f(left[0][1])}" + _curve(left) + " Z"


_H = BOTTOM - TOP
LID = _part(0.0, (SEAM - TOP) / _H)
BODY = _part((SEAM + GAP - TOP) / _H, 1.0)
LED = (54.0, 58.4, 2.5)
HINGE = (R - 2.0, SEAM)    # the lid opens on its back edge; seen from the front, about here

# Diagonal gradients, top-right to bottom-left, as in Google's 2025 icons (Docs, Calendar):
# saturated blue easing into lavender. The lid is the lighter "fold".
LID_STOPS = [(0.0, "#8AB4F8"), (1.0, "#C3D3FB")]
BODY_STOPS = [(0.0, "#2F7BF5"), (0.55, "#4A86F4"), (1.0, "#A497F2")]
LED_COLOR = "#34A853"


def circle_path(cx, cy, r):
    return f"M{cx - r},{cy} a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0 Z"


def grad(x0, y0, x1, y1, stops):
    items = "".join(f'<item android:offset="{o}" android:color="#FF{c[1:]}"/>' for o, c in stops)
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="{x0}" android:startY="{y0}" '
            f'android:endX="{x1}" android:endY="{y1}">{items}</gradient></aapt:attr>')


HEAD = ('<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" '
        'android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n')

foreground = (HEAD
    + "    <!-- Generated by docs/icon/google_style.py -->\n"
    + f'    <path android:pathData="{LID}">{grad(R, TOP, L, SEAM, LID_STOPS)}</path>\n'
    + f'    <path android:pathData="{BODY}">{grad(R, SEAM + GAP, L, BOTTOM, BODY_STOPS)}</path>\n'
    + f'    <path android:fillColor="{LED_COLOR}" android:pathData="{circle_path(*LED)}"/>\n'
    + "</vector>\n")
background = (HEAD + '    <path android:fillColor="#FFFFFFFF" android:pathData="M0,0h108v108h-108z"/>\n</vector>\n')
# Themed icons: one tint; the seam and the indicator stay cut out.
monochrome = (HEAD
    + f'    <path android:fillColor="#FF000000" android:pathData="{LID}"/>\n'
    + f'    <path android:fillColor="#FF000000" android:fillType="evenOdd" android:pathData="{BODY} {circle_path(*LED)}"/>\n'
    + "</vector>\n")

# At 22-24 dp a 1.6-unit seam is under 1 dp, so the mark opens it to 2.6 and enlarges the LED.
MARK_LID = _part(0.0, (SEAM - 0.5 - TOP) / _H)
MARK_BODY = _part((SEAM + GAP + 0.5 - TOP) / _H, 1.0)

# In-app mark (the "From the case" tile, the case notification): the same case, one tint, 24 dp.
# A 56-unit window around the glyph so it fills the icon box like Material Symbols do.
mark = ('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" '
        'android:viewportWidth="56" android:viewportHeight="56">\n'
        '    <!-- Generated by docs/icon/google_style.py: the launcher glyph as a single-tint mark. -->\n'
        '    <group android:translateX="-26" android:translateY="-26">\n'
        + f'        <path android:fillColor="#FF000000" android:pathData="{MARK_LID}"/>\n'
        + f'        <path android:fillColor="#FF000000" android:fillType="evenOdd" android:pathData="{MARK_BODY} {circle_path(LED[0], LED[1], 3.2)}"/>\n'
        + "    </group>\n</vector>\n")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 docs/icon/google_style.py <preview-dir>")
    preview = Path(sys.argv[1])
    preview.mkdir(parents=True, exist_ok=True)
    for name, text in [("ic_launcher_foreground", foreground), ("ic_launcher_background", background),
                       ("ic_launcher_monochrome", monochrome)]:
        (preview / f"{name}.xml").write_text(text)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">
<defs>
 <linearGradient id="lid" x1="{R}" y1="{TOP}" x2="{L}" y2="{SEAM}" gradientUnits="userSpaceOnUse">{"".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in LID_STOPS)}</linearGradient>
 <linearGradient id="body" x1="{R}" y1="{SEAM + GAP}" x2="{L}" y2="{BOTTOM}" gradientUnits="userSpaceOnUse">{"".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in BODY_STOPS)}</linearGradient>
</defs>
<rect width="108" height="108" fill="#fff"/>
<path d="{LID}" fill="url(#lid)"/><path d="{BODY}" fill="url(#body)"/><path d="{circle_path(*LED)}" fill="{LED_COLOR}"/>
</svg>'''
    (preview / "icon.svg").write_text(svg)
