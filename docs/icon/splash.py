"""Generates the animated splash icon (drawable/splash_icon.xml) from the launcher foreground.

    python3 docs/icon/splash.py

The launcher's case assembling and opening, in one second (Android's splash budget):

    0-300 ms    a drop of the case's blue grows in on a spring
    220-520     it flows into the case's silhouette (a real path morph, emphasized), turning upright
    490-580     the silhouette splits at the seam: lid and body, each in its own gradient, appear
                on top and the drop beneath goes, so no frame is ever half-transparent
    540-700     the lid opens on its hinge; the earbuds pop up, tilting outward, right one a beat
                later; a sheen of light runs across the case
    700-810     the earbuds drop back in
    800-950     the lid shuts with a small spring overshoot
    860-1000    the status light comes on and sends out one pulse

Every property is one keyframed animator over the whole second, so no step can override
another's start, and the first and last frames are exact (the last is the launcher icon).
MainActivity then flies the case into Home's case ring (setOnExitAnimationListener), and the
battery rings fill only once it has landed.
"""
import re
from pathlib import Path

RES = Path(__file__).resolve().parents[2] / "app/src/main/res"
fg = (RES / "drawable/ic_launcher_foreground.xml").read_text()
# A path is either self-closing or holds a gradient (whose items are self-closing themselves).
paths = re.findall(r"<path\b[^>]*/>|<path\b[^>]*[^/]>.*?</path>", fg, flags=re.S)
lid, body, led = paths  # generated in this order
led = led.replace("<path ", "<path android:name=\"led\" android:fillAlpha=\"0\" ", 1)

DECEL = "@interpolator/w_emphasized"  # emphasized decelerate
SPRING = "@interpolator/splash_spring"  # arrives with a small overshoot
ACCEL = "@interpolator/splash_accelerate"  # emphasized accelerate
LINEAR = "@android:interpolator/linear"
TOTAL = 1000
K = 0.5523  # cubic handle for a quarter circle


def track(prop, *keys, kind="floatType"):
    """One animator per property over the whole second: (ms, value[, interpolator into it])."""
    frames = "".join(
        f'<keyframe android:fraction="{k[0] / TOTAL:.3f}" android:value="{k[1]}"'
        + (f' android:interpolator="{k[2]}"' if len(k) > 2 else "") + "/>"
        for k in keys)
    return (f'<objectAnimator android:duration="{TOTAL}" android:interpolator="{LINEAR}">'
            f'<propertyValuesHolder android:propertyName="{prop}" android:valueType="{kind}">{frames}</propertyValuesHolder>'
            f'</objectAnimator>')


def morph(a, b, start, end, interp=DECEL):
    """A path morph from a to b between start and end ms (pathType can't be keyframed)."""
    return (f'<objectAnimator android:propertyName="pathData" android:valueType="pathType" android:valueFrom="{a}" '
            f'android:valueTo="{b}" android:startOffset="{start}" android:duration="{end - start}" android:interpolator="{interp}"/>')


def target(name, *animators):
    return (f'    <target android:name="{name}"><aapt:attr name="android:animation"><set>\n        '
            + "\n        ".join(animators) + "\n    </set></aapt:attr></target>\n")


def n(x):
    return f"{x:.2f}".rstrip("0").rstrip(".")


def rr(x0, y0, x1, y1, tl, tr, br, bl):
    """A rounded rectangle with the same commands whatever its radii, so any two morph."""
    return (f"M{n(x0 + tl)},{n(y0)} H{n(x1 - tr)} C{n(x1 - tr + tr * K)},{n(y0)} {n(x1)},{n(y0 + tr - tr * K)} {n(x1)},{n(y0 + tr)} "
            f"V{n(y1 - br)} C{n(x1)},{n(y1 - br + br * K)} {n(x1 - br + br * K)},{n(y1)} {n(x1 - br)},{n(y1)} "
            f"H{n(x0 + bl)} C{n(x0 + bl - bl * K)},{n(y1)} {n(x0)},{n(y1 - bl + bl * K)} {n(x0)},{n(y1 - bl)} "
            f"V{n(y0 + tl)} C{n(x0)},{n(y0 + tl - tl * K)} {n(x0 + tl - tl * K)},{n(y0)} {n(x0 + tl)},{n(y0)} Z")


DROP = rr(43, 43, 65, 65, 11, 11, 11, 11)          # a circle at the icon's centre
SILHOUETTE = rr(30, 37, 78, 71, 11, 11, 12.5, 12.5)  # lid and body as one, before the seam


def gradient(x0, y0, x1, y1, stops):
    items = "".join(f'<item android:offset="{o}" android:color="{c}"/>' for o, c in stops)
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="{x0}" android:startY="{y0}" '
            f'android:endX="{x1}" android:endY="{y1}">{items}</gradient></aapt:attr>')


def bud(name, cx, tilt):
    x0, x1, y0, y1, r = cx - 6, cx + 6, 44.0, 58.0, 5.5
    path = rr(x0, y0, x1, y1, r, r, r, r)
    return (f'<group android:name="{name}" android:pivotX="{cx}" android:pivotY="{y1}">'
            f'<path android:name="{name}_fill" android:fillAlpha="0" android:pathData="{path}">'
            + gradient(x0, y0, x1, y1, [(0, "#FFF1F3F4"), (0.6, "#FFDADCE0"), (1, "#FFBDC1C6")]) + '</path>'
            # a soft specular highlight along the top, as on the product's glossy shell
            f'<path android:name="{name}_tip" android:fillAlpha="0" android:fillColor="#FFFFFFFF" '
            f'android:pathData="{rr(x0 + 2.2, y0 + 1.6, x1 - 2.2, y0 + 4.2, 1.3, 1.3, 1.3, 1.3)}"/></group>')


BODY_CLIP = "M30,52.1 H78 V58.5 C78,65.375 72.375,71 65.5,71 H42.5 C35.625,71 30,65.375 30,58.5 Z"
sheen = ('<group android:name="sheen_clip"><clip-path android:pathData="' + BODY_CLIP + '"/>'
         '<group android:name="sheen" android:translateX="-40">'
         '<path android:name="sheen_band" android:fillColor="#FFFFFFFF" android:fillAlpha="0.5" '
         'android:pathData="M28,75 L39,48 L48,48 L37,75 Z"/></group></group>')
led_ring = ('<group android:name="pulse" android:pivotX="54" android:pivotY="60.5">'
            '<path android:name="pulse_ring" android:strokeColor="#34A853" android:strokeWidth="1.2" android:strokeAlpha="0" '
            'android:pathData="M51.4,60.5 a2.6,2.6 0 1,0 5.2,0 a2.6,2.6 0 1,0 -5.2,0 Z"/></group>')
body_parts = body.replace("<path ", '<path android:name="body" android:fillAlpha="0" ', 1)
lid_parts = lid.replace("<path ", '<path android:name="lid_fill" android:fillAlpha="0" ', 1)
shell = (f'<path android:name="shell" android:pathData="{DROP}">'
         + gradient(78, 37, 30, 71, [(0, "#FF2F7BF5"), (0.55, "#FF4A86F4"), (1, "#FFA497F2")]) + '</path>')

xml = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by docs/icon/splash.py from ic_launcher_foreground.xml; edit the script. -->
<animated-vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt">
    <aapt:attr name="android:drawable">
        <vector android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
            <group android:name="case" android:pivotX="54" android:pivotY="54" android:scaleX="0" android:scaleY="0">
                {bud("bud_l", 43.5, -10)}
                {bud("bud_r", 64.5, 10)}
                {shell}
                <group android:name="lid" android:pivotX="30" android:pivotY="50.5">
                    {lid_parts}
                </group>
                {body_parts}
                {sheen}
                {led}
                {led_ring}
            </group>
        </vector>
    </aapt:attr>
""" + target("case",
             track("scaleX", (0, 0), (300, 1, SPRING), (1000, 1)), track("scaleY", (0, 0), (300, 1, SPRING), (1000, 1)),
             track("rotation", (0, -20), (520, 0, SPRING), (1000, 0))) \
    + target("shell", morph(DROP, SILHOUETTE, 220, 520),
             track("fillAlpha", (0, 1), (530, 1), (580, 0), (1000, 0))) \
    + target("lid_fill", track("fillAlpha", (0, 0), (490, 0), (530, 1, DECEL), (1000, 1))) \
    + target("body", track("fillAlpha", (0, 0), (490, 0), (530, 1, DECEL), (1000, 1))) \
    + target("lid",
             track("rotation", (0, 0), (540, 0), (700, -18, DECEL), (800, -18), (950, 0, SPRING), (1000, 0)),
             track("translateY", (0, 0), (540, 0), (700, -3.5, DECEL), (800, -3.5), (950, 0, SPRING), (1000, 0))) \
    + target("bud_l",
             track("translateY", (0, 0), (560, 0), (700, -11, SPRING), (800, 0, ACCEL), (1000, 0)),
             track("rotation", (0, 0), (560, 0), (700, -10, DECEL), (800, 0, ACCEL), (1000, 0))) \
    + target("bud_r",
             track("translateY", (0, 0), (590, 0), (720, -11, SPRING), (810, 0, ACCEL), (1000, 0)),
             track("rotation", (0, 0), (590, 0), (720, 10, DECEL), (810, 0, ACCEL), (1000, 0))) \
    + "".join(target(f"{b}_{part}", track("fillAlpha", (0, 0), (t0, 0), (t0 + 40, 1), (t1, 1), (t1 + 30, 0), (1000, 0)))
              for b, t0, t1 in (("bud_l", 560, 790), ("bud_r", 590, 800)) for part in ("fill", "tip")) \
    + target("sheen", track("translateX", (0, -40), (560, -40), (820, 50, DECEL), (1000, 50))) \
    + target("led", track("fillAlpha", (0, 0), (860, 0), (920, 1, DECEL), (1000, 1))) \
    + target("pulse", track("scaleX", (0, 1), (870, 1), (1000, 3.2, DECEL)), track("scaleY", (0, 1), (870, 1), (1000, 3.2, DECEL))) \
    + target("pulse_ring", track("strokeAlpha", (0, 0), (870, 0), (885, 0.9), (1000, 0, DECEL))) \
    + "</animated-vector>\n"
(RES / "drawable/splash_icon.xml").write_text(xml)
