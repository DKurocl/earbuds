# Design audit, element by element (3.0.15)

Reference: the owner's Android 17 Settings and Google's Material 3 Expressive apps (Pixel Buds,
Clock, Settings). Each element: what it is now, and why it is this way and not another.

## Every page

| Element | Now | Why |
|---|---|---|
| Top bar | Back in a tonal circle, then the title; actions on the right | Android 17 Settings draws exactly this; a bare arrow read as Material 2 |
| Title | Google Sans Flex, regular weight, 28 sp | Settings uses one regular title size on every page; the earlier bold 26 sp title looked louder than the system |
| Top actions | Separate tonal circles, 8 dp apart | Settings never joins header buttons into one pill; joined buttons read as a segmented control |
| Page colour | Surface container | One tone above plain surface, as Settings; the old darker page made every card look stuck on |
| Rows | Surface container high in dark, white in light | The same steps Settings uses for its list rows |
| Row groups | Connected rows, 2 dp gaps, large outer corners | Material 3 Expressive segmented list, as Settings |
| Row icons | A circle coloured by kind (sound, connection, routine) | Settings tells kinds apart by colour, not by shape; varied shapes were tried and dropped |
| Section titles | Primary colour, title small | Settings section headers |

## Home

| Element | Now | Why |
|---|---|---|
| Device name | Page title, left | Pixel Buds names the device at the top left; centred text felt like a splash |
| Status line | "Connected" under the name | One short state, no codec here (it lives in Device info) |
| Profiles button | Tune icon | Kept; the Earbud settings row now uses the earbuds icon, so the two no longer share one icon |
| Battery rings | Three rings with a status chip on top | Pixel Device details battery; unchanged |
| Listening card | "Listening time left", the estimate, "The case adds …", an arrow | One card that leads to Battery; the arrow says it opens something; plain words instead of "+12 h from case" |
| Listening level card | Removed | The earbuds never answered the guessed command; Sony's Safe Listening protocol is not documented anywhere public (Gadgetbridge has none). No made-up numbers |
| Find / Bluetooth / Power off | Three round tonal buttons with labels | Quick actions as in Google Home and Pixel Buds |
| More controls | Equalizer, Earbud settings, Device info as rows | Settings-style navigation rows |
| Mode bar | Floating pill with ANC, Ambient, Off; nothing behind it | M3E floating toolbar; the list scrolls under it |
| Options above the bar | Speak-to-Chat, Ambient for a moment, Ambient level | Shown only for the mode they belong to, next to the thumb |

## Equalizer

| Element | Now | Why |
|---|---|---|
| Switch | "Use equalizer" row with the current sound under it | A Settings switch row; a giant preset name as the title read as a heading, not a control |
| Curve | Draggable curve, values under each band | Kept: the most direct way to shape sound |
| Clear Bass | Its own card with a centred slider | A separate control in Sony's own app |
| Presets | One scrolling row of chips, yours first | Pixel Buds lays presets out this way; the 3×4 grid took half the screen |
| Save / import | Two Settings rows under "Your presets" | Bare text buttons looked unfinished |
| Export | Copy icon in the top bar | A page action, as in Settings |

## Earbud settings, App settings, Battery, Device info, Profiles

Rows, switches, section titles and colours follow the shared rules above; no page has its own
style. The LDAC note in Device info is a tonal card with one action, as Settings shows advice.

## Battery

- Ambient while walking no longer polls every 5 s; it waits for steps, mode changes or a deadline.
- The listening level reads are gone with the feature.
