# Sony ANC — lean replacement for Sony Sound Connect

Minimal Android app + home-screen widget that controls Sony WF-1000XM5 (and likely other
XM5-generation devices) over Bluetooth RFCOMM. Goal: replace the official app for everyday
use — no account, no telemetry, no background service, no notifications.

Kotlin, Jetpack Compose (Material 3, expressive-style motion), minSdk 26, targetSdk 35.
Dependencies: Compose BOM, activity-compose, kotlinx-coroutines. Nothing else, on purpose.

## Status

| Feature | State |
|---|---|
| RFCOMM connect to WF-1000XM5 | ✅ verified on device (UUID `956C7B26-…`, secure socket) |
| ANC / Ambient / Off switching | ✅ verified on device |
| Live mode updates when changed on earbuds (`69 17 …`) | ⚠️ implemented, not verified |
| Ambient level slider 0–20, focus-on-voice switch | ⚠️ implemented (same payload layout), not verified |
| Battery L/R/case | ❓ guessed request format, unverified — may show nothing |
| Home-screen widget (3 modes, dynamic colors) | ⚠️ implemented, builds, not verified |
| EQ, Speak-to-Chat, codec, multipoint | ❌ not started — needs probing |

## Architecture

```
app/src/main/java/ua/olex/sonyanc/
  SonyProtocol.kt   framing, escaping, checksum; NC/ambient + battery payload builders/parsers
  SonyConnection.kt one RFCOMM socket, reader loop, stop-and-wait ACK handling
  Headphones.kt     process-wide singleton: link ownership, StateFlows (status, nc, battery, log)
  Prefs.kt          SharedPreferences: chosen device, last known NC state (widget reads it)
  ModeWidget.kt     AppWidgetProvider (RemoteViews); tap = goAsync → connect → send → release
  MainActivity.kt   Compose UI: device picker, status/battery pills, mode tiles, ambient card,
                    diagnostics (raw hex send, probe 66 00..2F, log)
.github/workflows/build.yml   CI build → APK artifact; compile errors surfaced as annotations
```

**Link lifetime:** users `hold()`/`release()` the link (activity while visible, widget for one
tap). When nobody holds it, the socket closes after a grace period (4 s app / 1.5 s widget).
No foreground service, so nothing runs in the background.

**Concurrency:** all sends go through a `Mutex` in `Headphones`; the protocol is stop-and-wait,
so `SonyConnection.send()` waits up to 1.5 s for the ACK before returning.

## Protocol notes (reverse-engineered, verified on WF-1000XM5)

Frame: `3E | type | seq | len(4, BE) | payload | checksum | 3C`
- checksum = sum(type..payload) & 0xFF
- inside the frame, `3C/3D/3E` are escaped as `3D, b & 0xEF`
- type `0C` = command, `01` = ACK. Reply ACK with `seq = 1 - incoming seq`; after receiving an
  ACK, the next outgoing message uses the seq from that ACK.

RFCOMM service UUID for XM5 generation: `956C7B26-D49A-4BA8-B03F-B17D393CB6E2`
(older XM3/XM4: `96CC203E-5068-46AD-B32D-E316F5E069BA`).

Init: send `00 00` → device replies `01 00 03 00 30 18 00 00` (protocol info).

**Noise cancelling / ambient (type `17`):**
- get `66 17` → reply `67 17 01 <on> <amb> <voice> <level>`
- set `68 17 01 <on> <amb> <voice> <level>`
- notify `69 17 …` (same layout)
- `on`: 0 = everything off, 1 = effect on; `amb`: 0 = NC, 1 = ambient;
  `voice`: focus on voice; `level`: ambient level 0..20 (0x14 = 20)
- verified commands: ANC `68 17 01 01 00 00 14`, ambient `68 17 01 01 01 00 14`,
  off `68 17 01 00 00 00 14`

Probing `66 00..2F` on WF-1000XM5 got replies only for `17` and `18`
(`67 18 01 01 01 00 14` — same layout, purpose unknown, maybe adaptive/auto-ambient).

**Battery (unverified guess):** sends `22 09`, `22 0A`, `22 01`, `22 02`; parser accepts replies
starting with `23`/`11`/`25`/`13`: type `01/09` → `L% Lchg R% Rchg`, `02/0A` → `case% chg`.
If nothing appears, probe other command families the same way as `66 xx` (try `22 xx`, `10 xx`)
via Diagnostics and read the log.

**How to discover new settings:** Diagnostics → "Зондувати" sends `66 00..2F` and lists replies.
A reply `67 XX …` means setting `XX` exists; the setter is usually `68 XX` + same layout.
Changing a setting on the earbuds while connected shows `69 XX …` notifications in the log.

## Build

No local SDK needed: push to GitHub → Actions → "Build APK" → download artifact
`SonyAnc-debug` (zip with `app-debug.apk`). Runner pinned to `ubuntu-24.04` (preinstalled SDK).

Locally: open in Android Studio, or `gradle :app:assembleDebug` with Gradle 8.11+ and SDK 35.
There is no Gradle wrapper in the repo (CI uses `gradle/actions/setup-gradle`).

## Testing notes

- Force-stop Sony Sound Connect before testing: it holds the same RFCOMM channel and causes
  `read failed, socket might closed`.
- Owner's device: WF-1000XM5.

## Design constraints (owner's preferences)

- Lean: no extra libraries, no background services, no notifications, no analytics.
- Material 3 with expressive-style motion (spring shape morphing), dynamic color.
- UI language: Ukrainian.
