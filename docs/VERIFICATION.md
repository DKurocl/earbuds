# Verification

## 2.1 on the Pixel 10 Pro XL

Use a physical Pixel 10 Pro XL with Smooth Display enabled and its WF-1000XM5 paired. Record the
app build, Android version, Sony firmware, transparency or noise-control mode, and codec with the
measurements. Emulator results are useful for UI regressions, with device measurements recorded
separately.

| Check | How to run | Record |
|---|---|---|
| Startup | Run `StartupBenchmark#coldLaunch` and `StartupBenchmark#warmLaunch` from the benchmark module on the Pixel. | Median and range of initial display time, with 10 iterations for each launch mode. |
| Transitions | Run `StartupBenchmark#settingsTransitions`. Check the active refresh rate during the transition using Android's refresh-rate overlay or the Perfetto timeline. | Actual refresh rate, p95 frame overrun, and the trace for any visible hitch. |
| Background use | Start from a saved battery reading. Use the phone normally for 24 hours, including repeated app switches, time with the earbuds in the case, and at least one listening session. Leave Stay in sync in its usual configuration. | Starting and ending phone and earbud levels, Android's per-app usage, listening time, and whether the control link was idle after closing. |
| Home screen | Place the 322 × 105 dp Earbuds widget beside the music player. Tap each noise-control icon and both side edges. Repeat after disconnecting the buds. | Screenshot of the two widgets together, confirmed mode, vibration and app opening. |
| Gemini and Android | Invoke ANC, Ambient and battery queries in Gemini. Check Quick Settings, launcher shortcuts, notifications, Stay in sync and Android's app-functions listing. | Which surfaces confirmed the same device mode and which commands Gemini routed. |
| WF-1000XM5 | Switch ANC, Ambient and Off rapidly. Try voice focus, level, EQ, both profiles, DSEE and wearing detection. Put the buds away and reopen Earbud settings. | Read-back outcomes, measured Bluetooth queue and wire timing, and whether the settings stay visible across app switches. |

Macrobenchmark tests are under `benchmark/src/main`. With a single Android device connected, run
`bash gradlew :benchmark:connectedBenchmarkAndroidTest` from the repository root. Use a release-
equivalent build and compare results recorded on the same device and OS.

## 1.4 on the Pixel (deferred by the owner)

Everything below builds, passes the JVM, lint, golden and emulator checks in CI, but needs the
real phone and earbuds. About 15 minutes.

1. **Stay in sync**: App settings → Stay in sync → allow in Android's dialog. Take the earbuds out,
   wait 5 s, change the mode with a touch on an earbud: the widget follows within a second.
   Tap a mode in the widget: it confirms at once. Back in the case: Settings → Apps → Earbuds shows
   nothing running.
2. **Gemini**: "Hey Google, turn on ambient sound on my earbuds", "ambient for five minutes",
   "how much battery do my earbuds have". (Gemini decides when to use an app's functions; if it
   does not offer Earbuds yet, the functions are still listed for it in Android's App Functions.)
3. **Tiles**: tap "Listening left": Battery grows out of the tile; Back (button and swipe) returns
   it into the tile.
4. **Live Update**: Ambient · 5 min in the dock: a countdown chip in the status bar.
5. **At home**: set Home, allow location all the time, put the earbuds away and take them out.
6. **Low earbud battery**: listen down to 15 %: one heads-up with the time left.
7. **New phone** (whenever it happens): transfer during setup brings the settings.

---

## Hush 0.9.0 verification (historical)

Date: 2026-10-22

Tested source: `7d2465c1a3c8dd8aafdf48fd02da3af7bfc18573`.

[Full CI run](https://github.com/DKurocl/xm5/actions/runs/39755448269): build and emulator jobs passed.

## Results

| Check | Result |
|---|---|
| Debug JVM | 31 tests, zero failures/errors/skips |
| Release JVM | 31 tests, zero failures/errors/skips |
| Android UI, API 35 emulator | 7 tests passed in 109.642 seconds |
| Debug/release lint | Zero errors, 10 warnings per variant |
| Debug, optimized release, instrumentation APK assembly | Passed |
| Optimized release installation and cold launch | Passed; no recorded fatal crash |
| After Home, without force-stop | No Hush services, scheduled jobs or alarms |
| Large text, light/dark, settings, widget sizes | Screenshots reviewed; large-font setting values no longer overlap labels |

The 10 lint warnings concern six dependency upgrades, two vector path optimizations, the target API level, and the legacy backup-content attribute. Backup is disabled and API 31+ extraction rules explicitly exclude application data. These are retained warnings, not a zero-warning claim.

## Functional changes

- Serialized transport writes and ACK handling; bounded blocked writes, cancellation and reconnect cleanup.
- Field-specific setting edits preserve other current values. Successful configuration requires recognized device readback; notifications cannot impersonate a read reply.
- Disconnection invalidates writable state. Partial battery replies distinguish live parts from cached values. Quality inquiries accept known subtype variants.
- Superseded mode requests are dropped during connection. Settings are one tap from the main gear, with inline switches and choices.
- Last foreground owner cancels polling and closes the control connection immediately, including pending native connection attempts. A widget or optional tile acquires only a short action lease and never starts battery/codec polling.
- Returning to an open settings sheet refreshes it. Release uses R8 and resource shrinking.

## APK

Filename: Hush-0.9.0.apk

Size: 1,414,228 bytes.

SHA-256: `df1ad3411b89d9127b298e077857681722ccd0c27685df38416066b93c559b59`.

## Verification limits

Emulator tests use settings fixtures, and stream tests use in-memory streams. No physical WF-1000XM5 or owner phone was connected to this environment. These results do not prove every command on the owner's firmware, actual Bluetooth latency, battery drain or complete Sound Connect parity.

Firmware delivery, ear-tip measurement, adaptive activity/location behavior, language downloads and Sony/partner provisioning remain unavailable. See [the feature audit](SOUND_CONNECT_PARITY.md). Android may retain a cached process after closing the UI; no persistent service or polling is required. Widget state refreshes when Hush runs or an action is performed, rather than through background polling.
