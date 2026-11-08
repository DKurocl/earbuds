"""Write the case icon VectorDrawables from the traced outline.

usage: make_icon.py CASE_PATH_TXT RES_DIR
Geometry (108-unit adaptive canvas): outline x 24..84, y 34.71..73.29,
lid seam at y 49.8..51.2 (41 % of height), status light at (54, 54.7).
The launcher layers scale that by ICON_SCALE around the centre so the case sits on
Google's icon keylines (about 46 units wide) instead of filling the safe zone.
"""
import sys
from pathlib import Path

case = Path(sys.argv[1]).read_text().strip()
res = Path(sys.argv[2])
NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'
AAPT = 'xmlns:aapt="http://schemas.android.com/aapt"'

LID = "M20,30h68v19.8h-68z"
SEAM_GAP_TOP = 49.8
BASE = "M20,51.2h68v26h-68z"
HIGHLIGHT = "M31,38.2a15,2 0,1 0,30 0a15,2 0,1 0,-30 0z"
ICON_SCALE = 0.76
LIGHT = "M52,54.7a2,2 0,1 0,4 0a2,2 0,1 0,-4 0z"

def linear(x1, y1, x2, y2, start, end):
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="linear" '
            f'android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}" '
            f'android:startColor="{start}" android:endColor="{end}"/></aapt:attr>')

def vector(body, size="108dp", viewport=108, extra=""):
    return (f'<vector {NS} {extra}android:width="{size}" android:height="{size}" '
            f'android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n{body}\n</vector>\n')

background = vector(
    f'    <path android:pathData="M0,0h108v108h-108z">{linear(54, 0, 54, 108, "#FFFFFFFF", "#FFEDF0F4")}</path>',
    extra=AAPT + " ")

SCALED = f'<group android:pivotX="54" android:pivotY="54" android:scaleX="{ICON_SCALE}" android:scaleY="{ICON_SCALE}">'

foreground = vector(f'''    {SCALED}
    <group>
        <clip-path android:pathData="{case}"/>
        <path android:pathData="{LID}">{linear(24, 34, 84, 51, "#FF4C8DF8", "#FF7B5CF0")}</path>
        <path android:pathData="{BASE}">{linear(24, 51, 84, 74, "#FFAFCBFF", "#FFCDBDFF")}</path>
        <path android:fillColor="#FFFFFFFF" android:pathData="M20,{SEAM_GAP_TOP}h68v1.4h-68z"/>
        <path android:fillColor="#FFFFFFFF" android:fillAlpha="0.38" android:pathData="{HIGHLIGHT}"/>
    </group>
    <path android:pathData="{LIGHT}"><aapt:attr name="android:fillColor"><gradient android:type="radial" android:centerX="53.2" android:centerY="53.9" android:gradientRadius="2.8" android:startColor="#FF7EE3A0" android:endColor="#FF1E9E50"/></aapt:attr></path>
    </group>''',
    extra=AAPT + " ")

def mono_body(indent="    "):
    return (f'{indent}<group>\n'
            f'{indent}    <clip-path android:pathData="{case}"/>\n'
            f'{indent}    <path android:fillColor="#FFFFFFFF" android:pathData="{LID}"/>\n'
            f'{indent}    <path android:fillColor="#FFFFFFFF" android:fillAlpha="0.45" android:pathData="{BASE}"/>\n'
            f'{indent}</group>\n'
            f'{indent}<path android:fillColor="#FFFFFFFF" android:pathData="{LIGHT}"/>')

monochrome = vector(f"    {SCALED}\n" + mono_body("        ") + "\n    </group>")
# Same artwork cropped to the case for in-app use (title bar mark), tinted by Compose.
mark = vector('    <group android:translateX="-22" android:translateY="-22">\n'
              + mono_body("        ") + '\n    </group>', size="24dp", viewport=64)

out = res / "drawable"
for name, text in {"ic_launcher_background": background, "ic_launcher_foreground": foreground,
                   "ic_launcher_monochrome": monochrome, "ic_case_mark": mark}.items():
    (out / f"{name}.xml").write_text(text, encoding="utf-8")
    print("wrote", name, len(text))
