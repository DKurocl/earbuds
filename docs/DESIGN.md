# Earbuds design (1.0)

Reference: Pixel Buds device details and Android 16 Settings. The app should read like a
Google app on a Pixel: wallpaper colors, Google Sans, grouped lists, large collapsing titles,
predictive back.

## Foundations (`Theme.kt`, `Components.kt`)

- **Color**: dynamic color by default; a blue fallback scheme matches the icon. Page background
  is `surfaceContainer` (light) / `surfaceContainerLow` (dark); cards one step up
  (`surfaceContainerLowest` / `surfaceContainerHigh`). See `Surfaces`.
- **Type**: Pixel phones expose Google Sans as system families (`google-sans-flex`,
  `google-sans`, `google-sans-text`). The theme uses them when present, otherwise the default
  sans. No font files are bundled.
- **Motion**: `MotionScheme.expressive()`. Page changes slide and fade; the system back gesture
  shrinks the page and follows the finger before it pops (`PredictiveBackHandler`,
  `enableOnBackInvokedCallback`).
- **Lists**: `ListGroup` + rows. Group corners are 24 dp outside and 4 dp between items, 2 dp
  gaps, a primary-colored section title. Rows are at least 64 dp: icon, title, summary.
  - `SwitchRow`: the whole row toggles. A setting the earbuds did not report says
    "Not available" and is not toggleable at all.
  - `ChoiceRow`: shows the current value; opens a radio dialog (`ChoiceDialog`).
  - Loading shows "Reading…" in the row and one indicator in the top bar; a row spinner
    appears only while that one setting is being written.
- **Pages**: `PageScaffold` = `LargeTopAppBar` that collapses on scroll, back arrow, actions.
- **Messages**: write failures appear as a snackbar (`LocalMessages`); link problems as a
  banner under the device header (`ErrorBanner`).

## Pages

| Page | Content |
|---|---|
| Home (`HomeScreen.kt`) | App bar (case mark, "Earbuds", gear → Earbud settings). Device name with rename, status line ("Connected · LDAC"). Error banner. Battery rings L / case / R with a chip in the ring gap, large percentage and label (tap to refresh). Bluetooth and Connect/Disconnect pills. Noise control: connected button group, Ambient panel (level, presets, voice focus). More controls: Equalizer, Earbud settings, Device info, App settings. |
| Equalizer | Preset chips. Custom: five vertical bands (drag or tap, TalkBack adjustable) over a response curve, Clear Bass slider, Reset. Fixed presets offer "Use Custom". |
| Earbud settings | Sound, Controls, Playback & connection, Voice prompts. Every value is read from the earbuds and written with read-back. |
| Device info | Status, codec, firmware; Android Bluetooth settings; Diagnostics; Turn off earbuds (confirmation). |
| App settings | Paired WF-1000XM5 selection, display name, wallpaper colors, haptics, Quick Settings tile, widget, diagnostics, version. |
| Diagnostics | Command log with copy and clear. |

The CI visual check greps the home screen for the text "Earbuds", so keep it in the app bar.
`DesignPreviewActivity` (debug only) renders every page from fixtures:
`anc`, `ambient`, `error`, `permission`, `settings`, `settings-loading`, `equalizer`,
`equalizer-preset`, `info`, `app`, `diagnostics`, `widgets`.

## Icon

`docs/icon`: the case outline is measured from a photo (`silhouette.py`), smoothed
(`casepath.py`) and written as adaptive icon layers (`make_icon.py`). The launcher layers are
scaled to 76 % so the case sits on Google's icon keylines rather than filling the safe zone.

## Not done yet (needs captures from the real earbuds first)

- Ear-tip fit test, in-ear detection status, Adaptive Sound Control.
