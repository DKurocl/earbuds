# Main screen design — Pixel Buds style

Reference: Pixel "Device details" for Pixel Buds (Android Bluetooth settings).
The main screen (`HushScreen` in `MainActivity.kt`) follows that layout, top to bottom:

1. **Top bar**: Hush mark and "Hush" on the left, settings icon on the right.
   The CI visual check greps for the text "Hush", so keep it.
2. **Header**, centered: device name (`headlineMedium`). Under it a status line:
   "Active · LDAC" / "Connecting…" / "Not connected", plus a small `LoadingIndicator` while busy.
3. **Battery rings**: Left · Case · Right (`BatteryRing`). Each is a 100 dp ring with a 6 dp stroke
   and a 44° gap at the top. A 26 dp round chip with a battery icon sits in that gap.
   Colors: green (`#1E8E3E` light / `#6DD58C` dark), error at 20 % or below, outline when unknown,
   alpha .55 when the value is stale.
   Art: the left and right buds are cropped from `wf1000xm5_silver.webp`
   (`ContentScale.Crop` + CenterStart/End), without circular clipping. The case is a transparent photo cutout (`wf1000xm5_case.webp`).
   Tapping the row refreshes the battery.
4. **Two big actions** (`BigAction`): a 76×56 dp pill in `secondary`/`onSecondary` with a label
   under it. The actions are Bluetooth settings and Connect/Disconnect.
5. **"Active noise control"**: a connected button group (`ModeSegment`) with 3 segments, 64 dp tall,
   4 dp gaps and the icon inside. Outer corners are 32 dp, inner corners 10 dp. The selected segment
   morphs to a full pill in `primary`, and a pressed segment goes to 14 dp corners (spring, medium bouncy).
   Each label sits under its segment. While applying, the segment icon becomes a `LoadingIndicator`.
   In Ambient mode, the `AmbientPanel` card (level slider, presets, voice focus) expands below.
6. **"More controls"**: a grouped list (`ListCard`) with 24 dp outer and 4 dp inner corners and 2 dp
   gaps. Rows: Sound (EQ sheet), Earbud settings, Connection (codec), App settings.

Surfaces: the background is `surfaceContainer` (light) / `surface` (dark). Cards are
`surfaceContainerLowest` (light) / `surfaceContainerHigh` (dark). The look stays neutral and
wallpaper-driven through dynamic color.

## Not done yet (candidates)

- Local display name is editable from the pencil next to the name and Settings. It does not change the Bluetooth broadcast name.
- Touch-control customization screen ("Customize left/right" checklist of which modes
  press-and-hold cycles through). Needs the Sony V2 touch-function command to be probed first.
- Ear-tip fit test. Sony has a protocol command for it, but it is unknown/unverified here.
- 4th mode "Adaptive" (Sony's adaptive sound control) — protocol unknown.
- Quick Settings tile cycles ANC → Ambient → Off through the same bounded command path as the widget. Add it from Settings on Android 13+, or the system tile editor.

## 0.7.0 photo asset

`app/src/main/res/drawable-nodpi/wf1000xm5_case.webp` was edited using the built-in image generation tool from the user-supplied closed-case photo, then alpha-cropped and resized for Android.

Prompt: Faithful transparent product cutout of the supplied silver Sony WF-1000XM5 closed case. Preserve rounded oblong geometry, silver ivory finish, lid seam, centered LED and subtle SONY mark. Improve clarity and neutral lighting; front view, entire case visible; no invented buttons, stand, scene or extra text.

Earbuds use the existing transparent silver product asset with corrected rectangular clipping; no synthetic case drawing remains.
