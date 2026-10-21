# Hush

A small, personal controller for **Sony WF-1000XM5**. Ukrainian UI, Material 3,
expressive shape/color transitions, wallpaper colors, haptic mode selection and
an entirely native shaded earbud illustration. No account, network permission,
analytics, ads, foreground service, 3D runtime or permanent notification.

## Everyday controls

- ANC / ambient / off, always one tap away. You can choose while connecting.
- Ambient level 0–20 and focus on voice; Street (12) and Conversation (20 + voice) presets.
- Home-screen widget; add it from the app's settings sheet.
- Automatically select a paired WF-1000XM5 if there is exactly one; remember the address.
- Reconnect on foreground; keep an existing socket for 12 seconds after leaving the app.
- Separate diagnostics sheet with bounded log, copy action and opt-in experimental battery query.

The title and main mode controls render immediately from last known state. Pending
choices are distinguished from device read-back. Failed commands show an actionable
error and do not persist a fake successful selection. The widget explicitly labels its
highlight as the **last known mode**, since there is no background connection.

Only WF-1000XM5 devices appear in the picker. Pair them in Android settings first.
Close/force-stop Sony Sound Connect if it occupies the same RFCOMM service.

## Reliability and startup

`Headphones` owns a single shared transport. All commands are serialized. Rapid UI
choices use a conflated queue: a command already in progress finishes, then the newest
queued choice wins. A mode command is followed by a state read-back. Speculative battery
requests never run during normal startup or mode switching.

`SonyConnection` tries the verified secure V2 UUID first, then insecure V2 only. Each
blocking socket connect has a close watchdog (2.4 s per attempt) and cancellation closes
the candidate. ACK waits are bounded to 1.2 s. A missing ACK closes the session, so its late
reply cannot accidentally acknowledge a later command. Command and reader ACK writes
share an output lock. Bad checksums, invalid sequence numbers, malformed escape sequences,
overflowing lengths and oversized frames are rejected.

There is no claim that Hush can shorten Bluetooth's physical reconnect time. A warm
connection avoids that work; a cold start immediately renders the controls and queues
your choice. No invented millisecond benchmark is used.

## Verified vs. still needing the owner's device

| Feature | Evidence |
|---|---|
| Secure V2 RFCOMM, ANC / ambient / off payloads | Previously verified on the owner's WF-1000XM5 |
| New UI, resources and build variants | Checked by CI; see latest Build APK run |
| Ambient level / voice focus | Implemented using existing V2 layout; hardware check still needed |
| Mode notifications and read-back | Implemented; confirm real behavior after updating |
| New queue, reconnect, widget taps | Software checked; phone + earbuds validation still required |
| Battery L/R/case | Existing speculative parser retained, diagnostics-only query; no fabricated percentages |

EQ, codec changes, multipoint, Speak-to-Chat, adaptive location modes and firmware updates
are deliberately absent. They need verified protocol support and are outside this lean
instant-control scope.

## Structure

```
app/src/main/java/ua/olex/sonyanc/
  MainActivity.kt    lifecycle, real state, home screen, ambient controls, sheets
  Artwork.kt        small Canvas icons, Hush mark, shaded earbud illustration
  Headphones.kt     link ownership, serialized commands, conflated UI requests
  SonyConnection.kt bounded WF-XM5 RFCOMM transport + ACK reader
  SonyProtocol.kt   framing and V2 payload parsing
  Prefs.kt          selected device + acknowledged last known mode
  ModeWidget.kt     on-demand widget actions via goAsync (8.5 s budget)
```

The package/application ID remains `ua.olex.sonyanc` to preserve app identity. Brand is
**Hush**, version **0.2.0**. Adaptive icon includes Android themed-icon monochrome art.
The illustration is native shaded vector artwork, **not a rotatable 3D model**. It avoids
model parsing, textures, a graphics engine and continuous idle animation on the critical path.
Animation is limited to state changes and respects Android/Compose animation scaling.

## Build and checks

GitHub Actions → **Build APK**:

1. Unit tests for recorded XM5 payloads, reserved-byte escaping, all-byte round-trips,
   malformed input, invalid checksums and invalid setting replies.
2. Android lint, debug APK and optimized installable personal release build (R8 + resource shrinking).
3. API 35 emulator smoke run with actual Compose screenshots: light ANC, dark ambient,
   larger text/error layout, then installs and opens the optimized release APK. A debug-only, read-only `DesignPreviewActivity` supplies
   deterministic UI state without Bluetooth access; it is absent from release.

Artifacts: **Hush-install** (optimized personal APK), **Hush-debug** (debug harness), **Hush-checks** (reports/build log),
**Hush-visual-check** (screenshots/crash log). No local Android SDK is required for CI.
The personal release uses Android’s standard debug signing configuration for private
use, not store distribution. No signing key is stored in the repository or Actions
cache. Different CI runs can have different signatures and require reinstalling the app.

Local build: Gradle 8.11.1, Java 17 and Android SDK 35; run
`gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.

## Owner's quick hardware check

1. Open with WF-1000XM5 connected to the phone. Confirm auto-selection and state read-back.
2. Tap ANC → ambient → off, including several fast taps; final choice must match the earbuds.
3. In ambient, try 0 / 12 / 20 and voice focus. Confirm the actual audible effect.
4. Leave and reopen inside 12 seconds, then after the grace period.
5. Add the widget and test each button with the app closed.
6. Turn Bluetooth off / put earbuds in the case; retry must remain responsive and show a clear error.
7. Optionally query battery once in diagnostics and inspect actual replies before trusting the parser.

## Protocol reference (previous device session)

Frame: `3E | type | seq | len(4, BE) | payload | checksum | 3C`.
Checksum is sum(type..payload) & 0xFF. Escape `3C/3D/3E` as `3D, b & EF`.
Command `0C`, ACK `01`; response ACK sequence `1 - incoming seq`.

V2 UUID: `956C7B26-D49A-4BA8-B03F-B17D393CB6E2`. Init `00 00`.
NC get `66 17`; replies `67 17 01 <on> <ambient> <voice> <level>` / notification `69`.
Set `68 17 01 <on> <ambient> <voice> <level>`.
ANC: `68 17 01 01 00 00 14`; ambient: `68 17 01 01 01 00 14`;
off: `68 17 01 00 00 00 14`.
