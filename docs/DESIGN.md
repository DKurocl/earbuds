# Earbuds design (1.3)

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
- **Motion**: `MotionScheme.expressive()`. Pages move like Android's own predictive back between
  screens (`Navigation.kt`): while the finger moves, the page shrinks to 90 % toward the far edge
  (8 dp from it) with 32 dp corners and a shadow, follows the finger a little up and down, and the
  previous page waits underneath, dimmed and already live. Letting go sends the page off that edge
  (300 ms, emphasized); cancelling springs it back. Opening a page slides it in from the right over
  the old one (400 ms, emphasized); the back arrow is the reverse. Pages keep their saved state
  while covered. `PredictiveBackHandler`, `enableOnBackInvokedCallback`.
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
| Home (`HomeScreen.kt`) | Settings button only (gear → Earbud settings), no logo or title. Device name, status line ("Connected · LDAC" / "Disconnected · 25 minutes ago"). Error banner. Battery rings L / case / R (disc, chip in the ring gap, wave while charging; tap to refresh). Listening-time tiles (tap → Battery page; no separate Battery row). Find (+ Connect when disconnected). More controls. Bottom **dock**: ANC / Ambient / Off (soft connected group, 56 dp); in ANC Speak-to-Chat and "Ambient · N min"; in Ambient the timer countdown, level slider, Low / Medium / High / Voice pills. |
| Equalizer | Centered soft preset pills (Off, Custom first; Saved 1/2 are the earbuds' slots). Custom: five vertical bands with a fixed pill thumb, thin fill from 0 dB, haptic tick per dB; Clear Bass slider; Reset. Fixed presets offer "Use Custom". |
| Earbud settings | Sound, Controls, Playback & connection, Voice prompts. Every value is read from the earbuds and written with read-back. |
| Device info | Status, codec, firmware; LDAC hint; Find earbuds; Android Bluetooth settings; Turn off earbuds (confirmation). |
| App settings | Device picker only when needed, display name, When earbuds connect, Ambient for a moment length, case battery reminder, wallpaper colors, haptics, Quick Settings tile and its cycle, both widgets, diagnostics, version. |
| Diagnostics | Command log with copy and clear. |

The home screen has no logo or app name (only the settings button); the CI visual check greps for the device name "WF-1000XM5".
`DesignPreviewActivity` (debug only) renders every page from fixtures:
`anc`, `ambient`, `error`, `permission`, `settings`, `settings-loading`, `equalizer`,
`equalizer-preset`, `info`, `app`, `diagnostics`, `widgets`, `widgets-battery`.
`ScreenshotTest` (JVM, Robolectric, `-Pscreenshots=true`) renders every page and widget size to PNG.

Widget mode buttons (`docs/widget/segments.py` generates the drawables and layouts): each button is
a left cap, a stretching middle and a right cap, and a cap is half as wide as the button is tall, so
its vector scales uniformly. The chosen mode is disabled; every part is an animated selector on that
state, so only a real change animates: the caps morph from the connected-group shape (outer corners
round, inner 16 of 64) into a pill in 350 ms (emphasized), the fill and the cross-faded content
blend in 250 ms. A launcher re-applying the same update replays nothing. Buttons are never taller
than wide (`WidgetLayout.modes`). `WidgetAnimationTest` seeks the animators and renders the frames.

## Small things that do real work

- **Listening time**: two tiles under the rings. Starts from Sony's rating (8 h with ANC or
  ambient, 12 h off, case two charges) and switches to the owner's measured drain (`BatteryLog`:
  same out-of-case session, not charging, falling readings, ≥ 30 min / ≥ 5 %), kept within
  0.5–1.5× the rating. The emptier earbud decides.
- **Instant start**: the last battery reading is stored and shown at launch, dimmed, with
  "Last seen 5 min ago", until the earbuds answer.
- **LDAC hint**: Device info reads Bluetooth quality; when it is Stable connection and the codec
  is not LDAC, a card explains why and switches to sound quality in one tap.
- **Quick Settings tile** subtitle shows the mode and the emptier earbud's level.
- Launcher shortcuts: ANC / Ambient / Off with mode icons.

## Icon

Google's 2025 Pixel icon style (Docs, Calendar, Drive): a white background, one bold geometric
glyph and diagonal gradients from saturated blue into lavender. The glyph is the WF-1000XM5 case:
a lighter lid (the "fold"), a white seam, the body and a green status light as the second colour.
`docs/icon/google_style.py` writes the foreground, background and themed (monochrome) vectors from
one geometry, and an SVG preview. The case is 48 dp wide, inside the 66 dp safe zone.

## Not done yet (needs captures from the real earbuds first)

- Ear-tip fit test, in-ear detection status, Adaptive Sound Control.
