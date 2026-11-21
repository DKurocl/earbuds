# Earbuds design (1.4)

Reference: Pixel Buds device details and Android 17 Settings. The app should read like a
Google app on a Pixel: wallpaper colors, Google Sans, grouped lists, large collapsing titles,
predictive back.

Components come from Material 3 Expressive itself (`material3 1.5.0-rc01` on SDK 37: the owner's
Pixel runs Android 17), not imitations: `SegmentedListItem`, `ButtonGroup` with `ToggleButton`,
`LargeFlexibleTopAppBar`, `VerticalSlider` and `Slider` with `CenteredTrack`, `FilledTonalIconButton`
with its press morph, `LoadingIndicator`, emphasized type. Measures not taken from a component
come from Material's own tokens (`ExpressiveMotionTokens`, `ConnectedButtonGroupSmallTokens`,
`ListTokens`), read from the library sources.

## Foundations (`Theme.kt`, `Components.kt`)

- **Color**: dynamic color by default; without it, a warm tonal scheme from the icon's sunrise (#FF6536). Page background
  is `surfaceContainer` (light) / `surfaceContainerLow` (dark); cards one step up
  (`surfaceContainerLowest` / `surfaceContainerHigh`). See `Surfaces`.
- **Type**: Google Sans Flex is bundled (`res/font/google_sans_flex.ttf`, OFL, Latin subset) and
  used through its axes: `opsz` follows each style's size, display and headline styles are
  rounded (`ROND` 100), body and labels stay plain. Same look on every phone and in renders.
- **Motion**: `MotionScheme.expressive()`. Pages move exactly like Android's own screens
  (`Navigation.kt`, values from AOSP's CrossActivityBackAnimation): under a back swipe the page
  shrinks to 0.9 toward the far edge (8 dp margin), following the finger up and down, through
  Android's back-gesture curve; the previous page waits behind it at 0.9, 96 dp to the left, under
  a scrim (0.8 dark, 0.2 light). Letting go plays the system's 450 ms ending: the page slides
  96 dp further and fades out in its first fifth while the previous one grows to the screen. The
  back arrow plays the same ending; opening a page is its mirror. Pose and page swaps happen in
  one snapshot, so no frame ever shows a page in the wrong place, and the page beneath stays
  composed for an instant start. `PredictiveBackHandler`, `enableOnBackInvokedCallback`.
- **Lists**: `ListGroup` of Material's `SegmentedListItem`s: 16 dp outside, 4 dp between, 2 dp
  apart, and the pressed row morphs to fully rounded corners. Primary-colored section titles.
  - `SwitchRow`: the whole row toggles; the row stays neutral and the switch shows the state
    (Android settings), announced as a switch. A setting the earbuds did not report says
    "Not available" and is not toggleable at all.
  - `ChoiceRow`: shows the current value; opens a radio dialog (`ChoiceDialog`).
  - Settings show their last known values at once and keep their shape while the earbuds are
    asked again; only the top bar shows that a read is running, and a row quietly updates if
    the answer differs. Rows ignore taps while their own setting is being read or written.
- **Pages**: `PageScaffold` = `LargeFlexibleTopAppBar` that collapses on scroll, back arrow, actions.
- **Messages**: write failures appear as a snackbar (`LocalMessages`); link problems as a
  banner under the device header (`ErrorBanner`).

## Pages

| Page | Content |
|---|---|
| Home (`HomeScreen.kt`) | Settings button only (gear → Earbud settings), no logo or title. Device name, status line ("Connected · LDAC" / "Disconnected · 25 minutes ago"). Error banner. Battery rings L / case / R (disc, chip in the ring gap, wave while charging; tap to refresh). Listening-time tiles (tap → the Battery page grows out of the tile; no separate Battery row). Find (+ Connect when disconnected). More controls. Bottom **dock**: ANC / Ambient / Off as Material's connected `ButtonGroup` of `ToggleButton`s (56 dp, 2 dp apart, 8 dp inner corners, 4 dp pressed): the pressed button widens and its neighbours give way (`animateWidth`), the chosen one morphs into a pill; in ANC Speak-to-Chat (toggle button) and "Ambient · N min" (tonal button); in Ambient the timer countdown, Material's slider in whole steps, Low / Medium / High as a connected toggle group and Voice as its own toggle. |
| Equalizer | Presets as Material toggle buttons (round; the chosen one squares off), Off and Custom first; Saved 1/2 are the earbuds' slots. Custom: five `VerticalSlider`s with the centered track (fill from 0 dB), whole-dB steps, a haptic tick per dB and a firmer one at 0; Clear Bass is a centered `Slider`; Reset. Fixed presets offer "Use Custom". |
| Earbud settings | Sound, Controls, Playback & connection, Voice prompts. Every value is read from the earbuds and written with read-back. |
| Device info | Status, codec, firmware; LDAC hint; Find earbuds; Android Bluetooth settings; Turn off earbuds (confirmation). |
| App settings | Device picker only when needed, display name, Stay in sync, When earbuds connect, At home (+ Home), Ambient for a moment length, case battery reminder, low earbud battery, wallpaper colors, haptics, Quick Settings tile and its cycle, both widgets, diagnostics, version. |
| Diagnostics | Command log with copy and clear. |

The home screen has no logo or app name (only the settings button); the CI visual check greps for the device name "WF-1000XM5".
`DesignPreviewActivity` (debug only) renders every page from fixtures:
`anc`, `ambient`, `error`, `permission`, `settings`, `settings-loading`, `equalizer`,
`equalizer-preset`, `info`, `app`, `diagnostics`, `widgets`, `widgets-battery`.
`ScreenshotTest` (JVM, Robolectric, `-Pscreenshots=true`) renders every page, motion pose and widget size
to PNG and compares each with its golden (`app/src/screenshotTest/goldens`, quarter size; more than
0.2 % of pixels off fails, with a diff image). `-PrecordGoldens=true` rewrites them; CI runs the
comparison in its own job.

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
  0.5 to 1.5× the rating. The emptier earbud decides.
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

- Ear-tip fit test, in-ear detection status. (Activity-based Adaptive Sound Control was declined.)

## Widgets: colors and the away state

Backgrounds wear the wallpaper's secondary palette like Google's own widgets (Weather, Calendar):
tone 20 in dark (`system_accent2_800`), tone 95 in light; buttons and discs one step up. One-row
slots (3×1) show icons and percentages only; names appear in taller slots.

Like Pixel's own battery widget: each part is a flat glyph in a tonal disc (`w_disc_fill`, the
wallpaper's primary container) inside a ring in the wallpaper's accent (`w_ring`); only a level
of 20 % or less (not charging) turns the ring and number red. The earbud glyph is the
WF-1000XM5's own shape, traced from the product render (`docs/icon/device_pictures.py`); the case is
`ic_case_mark`. When the earbuds are away nothing is dimmed or greyed: the last reading is still
the truth, and the status line says when the earbuds left ("Disconnected 10:18"). Mode buttons
keep their colors too; a tap connects.

Large slots (lock-screen hub 416 × 275, home 4×2) get the dashboard: the name with the status on
the right ("Updated 14:32", "Back to ANC at 14:40"); a middle row that takes every dp the header
and the buttons leave, with how long you can listen as the big number (largest size that fits its
column, red under an hour) and the case's share under it, beside the three rings as large as the
row allows; the mode buttons at the bottom (64 dp in the hub, 56 dp in 4×2). `WidgetLayout.dashboard`.

## Pixel touches (1.3)

- **Live Update**: "Ambient for a moment" shows as an Android 16 QPR2+ Live Update, a status-bar
  chip counting down, with "Stay in Ambient" and "<mode> now" (`LiveTimer`).
- **Low earbud battery**: one planned check per session from the owner's own drain model, a
  heads-up at 15 % with the listening time left (`LowBattery`). No background connection.
- **At home**: a mode of its own when the earbuds connect at home, from the phone's last known
  location at that moment; the usual rule elsewhere or when the location is stale (`Places`).
- **Splash**: the launcher icon in its white circle, animated; see the opening below.
- **Large text**: one-line labels shrink to fit (`TextAutoSize`) at the largest font sizes;
  renders at 150 % and 200 % are part of `ScreenshotTest`, and `AccessibilityUiTest` runs
  Google's Accessibility Test Framework on every page on the emulator.

## The paper ideal (1.4)

- **Stay in sync** (`LiveLink`): with one consent in Android's own companion dialog, Earbuds becomes
  the earbuds' companion app like Pixel Buds' or a watch's. Android binds `LinkService` while the
  earbuds are connected (presence events, Android 16+) and Earbuds keeps the control channel open:
  the earbuds' notifications update mode and battery everywhere at once (a mode changed by touch
  on the earbuds shows in the widget), and taps need no connection first. No foreground service,
  notification or polling; only firmware that never pushes its battery gets one read per 15 min.
- **Container transform**: the listening tiles open the Battery page by growing it out of the tile
  (scaled to the tile's width, cut to its height, 28 → 0 dp corners, emphasized, 450 ms), and Back
  returns it into the tile, after the predictive swipe too. `PageOrigins` carries the tile's bounds;
  `PageLayers` draws it, so `ScreenshotTest` poses it like the slide.
- **Gemini** (`EarbudsFunctions`, App Functions): set noise control, Ambient for N minutes, read the
  battery as numbers and a sentence. The KDoc is the description the assistant reads; the service
  and its schema are generated by KSP.
- **New phone**: device-to-device transfer brings the settings (`data_extraction_rules.xml`); the cloud
  still gets nothing.

## Material 3 Expressive pass (1.5)

- **Icons** (`Artwork.kt`): every icon is a Material Symbols Rounded glyph from the variable font
  (`res/font/material_symbols.ttf`, subset; `material-icons-extended` is gone). `HushIcon` animates
  the font's `FILL` and `wght` axes: a pressed or chosen control turns bold (600) with a small
  spring pop, battery chips are filled. Widgets, tile, shortcuts and notification use drawables made
  from the same font (`docs/icon/symbols.py`), bold for the chosen mode.
- **Rings**: the battery rings fill in from empty when Home opens, and when the earbuds confirm a
  new mode a small swell runs through them, left, case, right: the answer comes from the earbuds,
  not from the button. (A mode badge at the top was tried and dropped: it repeated the dock.)
- **Equalizer curve**: one smooth line through the five thumbs with a soft fill to 0 dB, behind the
  sliders; it trails a drag on a quick spring and flows into a new preset's shape.
- **Presses**: mode buttons, big actions and the listening tiles respond to the press itself
  (bold icon; the tile's corners tighten 28 → 20 dp and it squeezes to 0.96).
- **Numbers** (`RollingText`): percentages, listening time and the ambient level roll digit by digit
  with tabular figures; a reader hears the whole value once.
- **Back curve**: `EmphasizedEasing` is Android's emphasized path evaluated in Kotlin (two cubic
  segments), so it runs in Robolectric renders too.
- **Haptics** follow the system's touch feedback setting as well as the app's own switch.
- CI fails if `material-icons-extended` or `Icons.` comes back.

## Floating toolbar, haptics, structure (1.6)

- **Dock**: Material 3 Expressive's floating toolbar. A pill above the page holds the three modes
  (no container at rest, a filled pill when chosen, the pressed one widens). What the chosen mode
  offers (Ambient's level and voice; Speak-to-Chat and "Ambient for a moment" with ANC) grows out
  of it as a floating card; scrolling down tucks the card away and the modes stay. The page
  scrolls under both, with a soft fade so the list never shows between them.
- **Haptics** (`Haptics.kt`): one vocabulary. ANC closes the world in (deep press), Ambient opens it
  (deep to crisp), Off lets go (a fade), a setting accepted is a click, a refusal two knocks.
  Shaped with Android 16 envelope effects on the Pixel, primitives elsewhere; low strength,
  USAGE_TOUCH in the app (follows the touch intensity setting) and the touch-feedback switch.
  Three takes per cue; App settings › Vibrations plays them and keeps the one chosen.
- **Structure**: `AppModel` (ViewModel) owns settings and actions; `App()` only wires pages and
  what needs an Activity. `Headphones` is the link (socket, reply matching, leases); noise
  control is `ModeControl`, codec and battery reads `DeviceReads`, earbud settings
  `EarbudSettingsLink`. The codec is remembered across links like the battery.
- **Mode read-back**: when the earbuds answer with another mode, Earbuds asks once more and then
  says which mode they stayed in; the log names each step.
- **Sound quality**: "Prefer sound quality" is remembered; if the earbuds report Stable connection
  again, Earbuds sets it back once per connection. When the earbuds allow LDAC but Android uses
  another codec, Device info points to Android's HD audio switch.

## Opening and widgets (1.7)

- **Opening** (`SplashHandoff.kt`, `Intro.kt`, `docs/icon/brand_icon.py`), as Google's apps do it,
  played off the launcher icon itself: Android shows the icon in its white circle and plays its
  gesture (580 ms: the sunrise slab settles from 88 %, the case lands on it with a small spring,
  its lid lifts once on the hinge and shuts). Then that same circle comes down onto
  Home's case ring, shrinking to the ring's disc on the emphasized curve while the splash
  dissolves (520 ms); the earbuds' rings come in, the case ring takes over as the circle lands, and
  the page fades up. Fresh starts only; nothing is drawn over the app.
  (Built and dropped on the way: vector shapes, product-photo cut-outs, an illustrated 1.5 s show
  with flying earbuds, generated video. None started from the icon Android shows, so none fitted.)
- **Widgets**: the mode buttons follow the floating toolbar. No container at rest; the chosen
  mode is a pill that grows in from 80 % and fades in (`docs/widget/segments.py`), the old one
  shrinks out. Same in the dashboard.
- **Page and dock**: the page ends where the dock's fade begins, so no row hides half under the
  toolbar (the accessibility check on the emulator holds this).

## Pictures of the earbuds (1.9)

As on a Pixel: device details show the device itself, small marks are flat. Sony's own product
shots, on transparency, are the source of both (`docs/icon/ref/wf1000xm5_buds.webp`,
`wf1000xm5_case.webp`; `docs/icon/device_pictures.py`), the same pictures Android's device details
show for this model:
- Home's battery rings, Device info and the connected sheet show the shots, posed as Pixel poses
  them (`device_bud_left`: three-quarter, tip to the left; `device_bud_right`; `device_case`);
- widgets, notifications and the case tile use one-tint silhouettes traced from them
  (`w_glyph_bud_*`: shell, the foam tip apart past its dark ring, the grille a hole;
  `ic_case_mark`: the case, its seam cut through, the status light a hole).
The launcher icon keeps the app's blue gradient but takes the case's real outline
(`docs/icon/google_style.py`: two superellipse quarters fitted to the shot, a domed lid, straight
sides, a rounder base, the seam at 44 %); the splash reads its hinge and light from it.

## Explained by doing (1.9)

- Earbud settings open with an illustration card (`Illustrations.kt`), as Pixel's settings pages
  do: ten scenes, one per setting, each a 4.8 s loop of Material's expressive shapes, the app's
  symbols and the earbud. It steps through them while nothing is changed and follows the setting
  you change; tapping it shows the next. With animations off it rests on one telling frame
  (`Topic.rest`). Drawn parts for richer scenes: `docs/illustrations/CHATGPT_BRIEF.md`.
- The connected sheet (`ConnectedSheet.kt`), as a Pixel shows its own earbuds: when the earbuds
  connect, a floating card springs up from the bottom over whatever is open, with the name and the
  three battery rings; it goes after 8 s untouched, on a swipe down, Done or a tap outside, and
  "Open Earbuds" opens the app. Android only lets a companion app do this from the background, so
  it needs "Stay in sync"; it stays away while the app is open, the screen is off or locked, and
  for 90 s after it last came up. App settings > "Show when connected" turns it off.

Home's listening tiles follow the mode the earbuds are in, not one just tapped, and the case tile
stays whenever the case's level is known, so nothing moves while a change is on its way.
