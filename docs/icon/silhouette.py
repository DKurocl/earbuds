"""Measure a case silhouette row by row and print its half-width profile.

usage: silhouette.py IMAGE MODE [THRESHOLD]
  MODE alpha  - object = alpha > THRESHOLD (transparent cutout)
  MODE light  - object = luminance > THRESHOLD (light object on dark background)
Writes IMAGE.mask.png next to the scratch dir for visual checking.
"""
import sys
import numpy as np
from PIL import Image

path, mode = sys.argv[1], sys.argv[2]
thr = int(sys.argv[3]) if len(sys.argv) > 3 else 128
img = Image.open(path).convert("RGBA")
a = np.asarray(img).astype(int)
if mode == "alpha":
    mask = a[:, :, 3] > thr
else:
    lum = 0.299 * a[:, :, 0] + 0.587 * a[:, :, 1] + 0.114 * a[:, :, 2]
    mask = lum > thr

# keep the largest connected blob roughly: use rows/cols with many pixels
rows = np.where(mask.sum(1) > mask.shape[1] * 0.05)[0]
top, bottom = rows.min(), rows.max()
h = bottom - top
print(f"size {img.size}, object rows {top}..{bottom} (h={h})")
widths = []
for frac in np.linspace(0, 1, 41):
    y = int(top + frac * h)
    xs = np.where(mask[y])[0]
    if len(xs) == 0:
        continue
    # longest contiguous run = the case at this row
    runs = np.split(xs, np.where(np.diff(xs) > 3)[0] + 1)
    run = max(runs, key=len)
    widths.append((frac, run[0], run[-1]))
wmax = max(r - l for _, l, r in widths)
cx = np.mean([(l + r) / 2 for _, l, r in widths])
print(f"max width {wmax}, aspect h/w {h / wmax:.3f}, center x {cx:.1f}")
for frac, l, r in widths:
    w = r - l
    bar = "#" * int(60 * w / wmax)
    print(f"{frac:5.3f}  L {(l - cx) / wmax:+.3f}  R {(r - cx) / wmax:+.3f}  w {w / wmax:.3f} {bar}")
Image.fromarray((mask * 255).astype(np.uint8)).save(path + ".mask.png")
