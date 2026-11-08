"""Build the case outline from the measured photo profile and check it against the photo.

Profile: right half-width (share of max width) at fractions of height, measured from
the well-lit right side of the silver case photo by silhouette.py.
Prints an SVG/VectorDrawable path for a 108-unit icon and writes an overlay PNG.
"""
import sys
import numpy as np
from PIL import Image, ImageDraw

PROFILE = [  # (fraction of height, half-width / max width); thr 75, centre-corrected
    (0.000, 0.000), (0.006, 0.120), (0.025, 0.2225), (0.050, 0.2975), (0.100, 0.390),
    (0.150, 0.444), (0.200, 0.473), (0.250, 0.488), (0.300, 0.4965), (0.375, 0.500),
    (0.450, 0.4965), (0.550, 0.4935), (0.650, 0.489), (0.700, 0.481), (0.750, 0.467),
    (0.800, 0.449), (0.850, 0.420), (0.900, 0.378), (0.950, 0.329), (0.975, 0.300),
    (0.993, 0.255), (1.000, 0.160), (1.000, 0.000),
]
MAXW = 1.0
ASPECT = 0.643

def outline(width, cx, cy):
    h = width * ASPECT
    right = [(cx + hw / MAXW * width, cy - h / 2 + f * h) for f, hw in PROFILE]
    left = [(2 * cx - x, y) for x, y in reversed(right[1:-1])]
    return right + left  # closed loop starting at top centre, clockwise

def bezier(points):
    """Closed Catmull-Rom through points, as cubic Bezier segments."""
    n = len(points)
    p = np.array(points)
    segs = []
    for i in range(n):
        p0, p1, p2, p3 = p[i - 1], p[i], p[(i + 1) % n], p[(i + 2) % n]
        segs.append((p1, p1 + (p2 - p0) / 6, p2 - (p3 - p1) / 6, p2))
    return segs

def path_d(segs):
    f = lambda v: f"{v:.2f}".rstrip("0").rstrip(".")
    d = f"M{f(segs[0][0][0])} {f(segs[0][0][1])}"
    for _, c1, c2, e in segs:
        d += f"C{f(c1[0])} {f(c1[1])} {f(c2[0])} {f(c2[1])} {f(e[0])} {f(e[1])}"
    return d + "Z"

def sample(segs, k=12):
    pts = []
    for s, c1, c2, e in segs:
        for t in np.linspace(0, 1, k, endpoint=False):
            pts.append(tuple((1 - t) ** 3 * s + 3 * (1 - t) ** 2 * t * c1 + 3 * (1 - t) * t * t * c2 + t ** 3 * e))
    return pts

if __name__ == "__main__":
    width = float(sys.argv[1]) if len(sys.argv) > 1 else 60
    print(path_d(bezier(outline(width, 54, 54))))
    if len(sys.argv) > 2:  # overlay on the photo: case spans w 832, rows 301..836
        photo = Image.open(sys.argv[2]).convert("RGB")
        segs = bezier(outline(832, 609.9, 301 + 535 / 2))
        d = ImageDraw.Draw(photo)
        d.line(sample(segs) + [sample(segs)[0]], fill=(255, 40, 160), width=3)
        photo.save(sys.argv[3])
