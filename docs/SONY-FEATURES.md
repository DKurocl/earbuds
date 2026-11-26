# Functions read from the earbuds (from Sony Sound Connect 13.3.1, decompiled)

Table 1 frames use data type 0x0C, table 2 frames 0x0E.

## Support functions (both tables)

`06 00` asks; the reply is `07 00 n (code version) x n`. Codes and names are in
`SonyFunctionNames.kt` (key = table shl 8 or code). The app reads both lists once per connection and
shows a function only when the earbuds list it. Device info lists all of them.

| Function | Key | Used for |
|---|---|---|
| Safe listening HBS 1/TWS 1/HBS 2/TWS 2 | 0x250 to 0x253 | Listening level (docs/SAFE-LISTENING.md) |
| Wearing status detector | 0x1F6 | Ear tip fit |
| Pairing device management classic BT / with class of device | 0x230 / 0x232 | Connected devices |
| Source switch control | 0x231 | Play here |

## Ear tip fit (table 1, system 0xF0 to 0xFD, inquired type 0x06)

1. `F6 07` reads the tip series set in the earbuds (reply `F7 07 series`; 0xFF not determined).
2. `F4 06 01 01` enters the test mode for one tip set.
3. `F8 06 00 00 series FF` starts (operation 0 start, 1 cancel, 2 start forcefully; index 0; size not determined).
4. The earbuds notify `F9 06 status error count index series size`: status 1 started, 2 finished, 3 finished
   unsuccessfully; error 1/2 left/right not connected, 3 unavailable, 4/5/6 left/right/both fit errors, 7 measuring error.
5. Result `FD 06 left right seriesL seriesR sizeL sizeR` (or ask `FA 06`): 0 good seal, 1 poor.
6. `F4 06 00 01` leaves the test mode.

## Connected devices (table 2, peripheral 0x30 to 0x3D)

- `36 t` reads the list (t = 0 classic BT, 2 with class of device). Reply/notify `37|39 t n records playbackSlot`;
  record = address (17 ASCII), slot (0 = not connected, else the connection number), [class of device, 3 bytes
  when t = 2], name length, name (UTF-8). The device whose slot equals playbackSlot is playing.
- `3C t 00 action address` connects (1), disconnects (0) or unpairs (2). Result `3D t action result address`;
  result low nibble 0 success, 1 error, 2 in progress, 3 busy.
- `3C 01 address` moves playback to a connected device (source switch); result `3D 01 result address`, 0 success.

## Not built yet

Readable in the same app, left for when the earbuds' own list shows them: earbud-side adaptive sound control
(AUTO_NCASM), LE Audio connection mode, safe volume control and the maximum volume limit, and the preset list
from the equalizer capability (`50`/`51`).
