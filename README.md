# Earbuds

Formerly Hush. A personal, offline **Sony WF-1000XM5** controller for Android. English only.

## Interface

Pixel-style: wallpaper colors, Google Sans on Pixel phones, Material 3 Expressive motion,
full-screen pages with large collapsing titles and the predictive back gesture, Android 16
style grouped lists, and an adaptive themed icon of the WF-1000XM5 case traced from a photo.
Pages: Home, Equalizer, Earbud settings, Device info, App settings, Diagnostics.
See [docs/DESIGN.md](docs/DESIGN.md).

The expressive dependency is deliberately pinned to `material3:1.4.0-alpha18`,
which exposes the public expressive APIs, rather than the stable 1.4.0 that removed them.

## Daily controls

- Scenes: one-tap setups of noise mode, ambient level, voice focus and EQ preset; also as launcher shortcuts.
- Estimated listening time left (earbuds and case) and the last battery reading shown at launch.
- ANC / Ambient / Off; choices queue while connecting and rapid taps are conflated.
- Ambient level 0–20, Low/Medium/High shortcuts and voice focus.
- Battery L/R/case, read after the primary NC sync without speculative protocol families.
  Unknown/unavailable levels remain `—`; zero or invalid device values are not invented percentages.
- On-demand equalizer: read-back gate, known Sony presets, Clear Bass + five bands.
  Writes use the V2 custom layout and are verified by reading actual state.
- Read-backed DSEE and pause-on-removal controls, with verified writes.
- Current codec reported directly by Sony V2 (`12 02`, replies `13 02` / notifications `15 02`).
  SBC/AAC/LDAC and other known Sony values are decoded; unknown codes stay unknown.
  No hidden Android APIs, preferred-codec inference, invented bitrate or sample rate.
  Changes update via notifications and foreground polling; closing the link clears the codec.
- Device selection, reconnect, home-screen widget and launcher ANC/Ambient/Off shortcuts.
  The widget contains only ANC / Ambient / Off: no app name, logo, battery or codec.
  The widget waits for command delivery rather than optional read-back completion,
  reconnects once after a stale-socket write failure and can retry from the widget.
  Missing pairing/permission opens the app for setup.
- Wallpaper colors and haptics switches; copyable bounded diagnostics.
- Minimal home screen without repeated mode captions; interruptible shape/width motion,
  top-anchored ambient transitions and last-choice-wins mode requests.
  Mode targets keep their width during switching; battery no longer pushes controls down.

## Running without Sony Sound Connect

Earbuds connects directly to the paired WF-1000XM5 over RFCOMM V2. It does not call,
query, launch or depend on the Sony app, its accounts, telemetry or online services.
Pairing remains Android's responsibility. Earbuds remembers the device address in its
own preferences. On each connection it reads current settings rather than resetting
the earbuds or overwriting an existing EQ on launch.

**Firmware updates are not implemented.** Sony's supported update procedure uses
Sound Connect. Removing it also removes its phone-side integrations such as location
based adaptive routines and Sony account features; Earbuds intentionally does not clone
those. Bluetooth audio, calls and Android codec/device settings are handled by Android.

Do not confuse protocol support with a hardware test: NC switching was previously
verified by the owner. The revised UI, battery and EQ implementation need an owner
check on the actual WF-1000XM5, especially all six EQ bands and voice focus.

## Transport

One serialized on-demand RFCOMM connection, secure V2 first, bounded blocking connects
with a socket-closing watchdog, bounded ACK waits, checksum/length/escape validation,
and a read-back after NC writes. The link remains available 12 s after leaving the app,
then closes. There is no foreground service, background notification or Internet permission.
Optional battery reads yield between requests and defer while a mode request is pending.
Polling retries unavailable values and handles both V2 dual-battery formats (01/09),
case format 0A and command frame types 0C/0E. Battery refresh is available on the home screen.
The widget labels its highlight as last known state because it has no persistent connection.

## Protocol evidence

Framing, ANC, battery and EQ facts were cross-checked against:

- [Gadgetbridge SonyProtocolImplV2](https://github.com/Freeyourgadget/Gadgetbridge/blob/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/protocol/impl/v2/SonyProtocolImplV2.java)
- [Gadgetbridge SonyProtocolImplV1 reply layouts](https://github.com/Freeyourgadget/Gadgetbridge/blob/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/protocol/impl/v1/SonyProtocolImplV1.java)
- [WF-1000XM5 coordinator capabilities](https://github.com/Freeyourgadget/Gadgetbridge/blob/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/coordinators/SonyWF1000XM5Coordinator.java)
- The owner's earlier device-confirmed NC type `17`, which takes precedence over generic model assumptions.

The Kotlin implementation is written for this app; protocol byte values and layouts
are interoperability facts. Battery: `22 01` / `22 09` / `22 0A`, replies `23` / notifications `25`.
EQ read `56 00`; reply `57` / notification `59` with preset, count and six values offset by 10.
EQ preset set `58 00 <preset> 00`; custom set `58 00 A0 06 <six offset values>`.

## Build

GitHub Actions → **Build APK**. Artifacts:

- **Earbuds-install** — optimized installable personal APK.
- **Earbuds-debug** — debug APK, including a read-only UI preview activity absent from release.
- **Earbuds-checks** — unit test, lint and build reports.
- **Earbuds-visual-check** — emulator screenshots (does not gate delivery of the built APK).

Unit tests cover recorded NC payloads, escaping, malformed frames, checksums, battery
layout validation, unavailable battery values, feature switch polarity, codec response validation and EQ bounds. Both debug and optimized release are built.
Release uses ephemeral Android debug signing for private use. No private signing key is
stored in the repo or Actions cache; different CI builds may require reinstalling.

Locally: Java 17, Android SDK 35, Gradle 8.11.1, then
`gradle :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`.

## Owner check

1. Pair and connect WF-1000XM5 in Android; open Earbuds and allow Bluetooth.
2. Tap ANC/Ambient/Off rapidly; final choice must match the audible mode.
3. Verify ambient 0/12/20 and voice focus; reopen after less/more than 12 s.
4. Check L/R/case readings; unavailable case/earbud data must remain unknown.
5. Open EQ, select a preset, adjust each band and reopen to verify actual read-back.
6. Add widget; test with the app closed, Bluetooth off and earbuds in the case.
7. Confirm the same behavior with Sound Connect uninstalled.
8. Play audio and compare the codec with Android's connected-device/developer codec display.
   Reconnect, switch codec in Android and confirm Earbuds updates; unknown must never imply LDAC.
9. Tap the device card, refresh connection information and use Back between settings sheets.

## Product artwork

`wf1000xm5_silver.webp` is an imagegen-assisted product cutout based on the owner’s
white/silver WF-1000XM5 reference, with transparency and gold microphone detail.
The prompt asks for faithful proportions, grey foam tips, no case/background/rings
and a compact side-by-side product composition. It is a static identification image,
not a 3D model. It is sized at 400×220 or smaller to keep decode and APK costs low.

## Widget 0.6

A 64 dp connected Material-style control rail with official rounded Material Symbols,
wallpaper colors, ripple feedback and a capsule for the confirmed mode. It stays at
64 dp when the launcher slot grows taller. Android 12+ gets responsive RemoteViews
maps; older launchers update from widget options. Wide sizes use icon + label in a
row, medium sizes stack them, compact sizes retain accessible icon-only targets.
Breakpoints account for font scale. No app name, logo, codec or battery in the widget.
The debug gallery inflates the production RemoteViews at 360/280/200 dp inside a
104 dp launcher slot and checks large text; screenshots are included in visual checks.

Widget glyphs are from [Google Material Symbols](https://github.com/google/material-design-icons),
licensed under Apache 2.0. The SVG path coordinates are retained in native Android vectors.
