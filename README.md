# Hush 0.3.0

A personal, offline **Sony WF-1000XM5** controller for Android. English only.

## Interface

Material 3 Expressive theme and motion scheme, native morphing toggle buttons,
expressive loading indicator, official rounded Material icons, wallpaper colors,
optional haptics and a new rosette/H adaptive and themed app icon. A compact transparent silver WF-1000XM5 product cutout based on the owner’s reference
identifies the device; no decorative hero, slogans or idle animation. The primary screen is noise control.
The expressive dependency is deliberately pinned to `material3:1.4.0-alpha18`,
which exposes the public expressive APIs, rather than the stable 1.4.0 that removed them.

## Daily controls

- ANC / Ambient / Off; choices queue while connecting and rapid taps are conflated.
- Ambient level 0–20, Low/Medium/High shortcuts and voice focus.
- Battery L/R/case, read after the primary NC sync without speculative protocol families.
  Unknown/unavailable levels remain `—`; zero or invalid device values are not invented percentages.
- On-demand equalizer: read-back gate, known Sony presets, Clear Bass + five bands.
  Writes use the V2 custom layout and are verified by reading actual state.
- Device selection, reconnect and home-screen widget.
- Wallpaper colors and haptics switches; copyable bounded diagnostics.

## Running without Sony Sound Connect

Hush connects directly to the paired WF-1000XM5 over RFCOMM V2. It does not call,
query, launch or depend on the Sony app, its accounts, telemetry or online services.
Pairing remains Android's responsibility. Hush remembers the device address in its
own preferences. On each connection it reads current settings rather than resetting
the earbuds or overwriting an existing EQ on launch.

**Firmware updates are not implemented.** Sony's supported update procedure uses
Sound Connect. Removing it also removes its phone-side integrations such as location
based adaptive routines and Sony account features; Hush intentionally does not clone
those. Bluetooth audio, calls and Android codec/device settings are handled by Android.

Do not confuse protocol support with a hardware test: NC switching was previously
verified by the owner. The revised UI, battery and EQ implementation need an owner
check on the actual WF-1000XM5, especially all six EQ bands and voice focus.

## Transport

One serialized on-demand RFCOMM connection, secure V2 first, bounded blocking connects
with a socket-closing watchdog, bounded ACK waits, checksum/length/escape validation,
and a read-back after NC writes. The link remains available 12 s after leaving the app,
then closes. There is no foreground service, background notification or Internet permission.
Optional battery reads yield between requests and stop if a mode request is pending.
The widget labels its highlight as last known state because it has no persistent connection.

## Protocol evidence

Framing, ANC, battery and EQ facts were cross-checked against:

- [Gadgetbridge SonyProtocolImplV2](https://github.com/Freeyourgadget/Gadgetbridge/blob/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/protocol/impl/v2/SonyProtocolImplV2.java)
- [Gadgetbridge SonyProtocolImplV1 reply layouts](https://github.com/Freeyourgadget/Gadgetbridge/blob/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/protocol/impl/v1/SonyProtocolImplV1.java)
- [WF-1000XM5 coordinator capabilities](https://github.com/Freeyourgadget/Gadgetbridge/blob/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/coordinators/SonyWF1000XM5Coordinator.java)
- The owner's earlier device-confirmed NC type `17`, which takes precedence over generic model assumptions.

The Kotlin implementation is written for this app; protocol byte values and layouts
are interoperability facts. Battery: `22 09` / `22 0A`, replies `23` / notifications `25`.
EQ read `56 00`; reply `57` / notification `59` with preset, count and six values offset by 10.
EQ preset set `58 00 <preset> 00`; custom set `58 00 A0 06 <six offset values>`.

## Build

GitHub Actions → **Build APK**. Artifacts:

- **Hush-install** — optimized installable personal APK.
- **Hush-debug** — debug APK, including a read-only UI preview activity absent from release.
- **Hush-checks** — unit test, lint and build reports.
- **Hush-visual-check** — emulator screenshots (does not gate delivery of the built APK).

Unit tests cover recorded NC payloads, escaping, malformed frames, checksums, battery
layout validation and EQ bounds. Both debug and optimized release are built.
Release uses ephemeral Android debug signing for private use. No private signing key is
stored in the repo or Actions cache; different CI builds may require reinstalling.

Locally: Java 17, Android SDK 35, Gradle 8.11.1, then
`gradle :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`.

## Owner check

1. Pair and connect WF-1000XM5 in Android; open Hush and allow Bluetooth.
2. Tap ANC/Ambient/Off rapidly; final choice must match the audible mode.
3. Verify ambient 0/12/20 and voice focus; reopen after less/more than 12 s.
4. Check L/R/case readings; unavailable case/earbud data must remain unknown.
5. Open EQ, select a preset, adjust each band and reopen to verify actual read-back.
6. Add widget; test with the app closed, Bluetooth off and earbuds in the case.
7. Confirm the same behavior with Sound Connect uninstalled.

## Product artwork

`wf1000xm5_silver.webp` is an imagegen-assisted product cutout based on the owner’s
white/silver WF-1000XM5 reference, with transparency and gold microphone detail.
The prompt asks for faithful proportions, grey foam tips, no case/background/rings
and a compact side-by-side product composition. It is a static identification image,
not a 3D model. It is sized at 400×220 or smaller to keep decode and APK costs low.
