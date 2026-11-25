# Verification

## Pixel feedback after build 223

The owner reported five issues on a Pixel 10 Pro XL running [build 223](https://github.com/DKurocl/earbuds/releases/tag/build-223). The screenshots cover the Equalizer, Battery, lock screen dashboard, and compact home screen widget. The follow-up recording shows the pull-to-refresh indicator passing over the case badge about 1.8–2.0 seconds into the clip, after the app switch. The Equalizer and Device info summaries also change as hardware reads complete.

| Observation | Candidate verification |
|---|---|
| The EQ point appeared to jump on release | The touch regression moves the 16 kHz band with the gesture still down, checks that no write occurred, then checks for one write on release. The curve draws the held point from the current pointer delta. |
| The top refresh indicator flickered after resume | The regression compares the top-center pixels through two background read cycles and checks that a manual read draws the indicator and clears it on completion. |
| Home descriptions changed after resume | The home rows use short fixed descriptions. Equalizer and Device info retain their actual values on their own screens. |
| EQ and Battery had repetitive copy | The page goldens show the shorter text and preserve the gain values, battery estimate source and last-reading status. |
| The lock screen dashboard had oversized mode controls relative to battery readings | In the 416 × 275 dp reference, the battery rings grow from about 58 to 68 dp and the mode row changes from 64 to 54 dp. The 340 × 208 dp preview covers the compact layout. |
| The compact home widget had too much space around its modes | At 240 × 105 dp the segment height grows from about 44 to 63 dp. The RemoteViews interaction test covers both player-slot side edges and the center mode controls. |

The PNG comparisons under `app/src/screenshotTest/goldens` use the same Robolectric configuration as build 223. Physical gesture, haptic, launcher and lock screen verification for this candidate is pending.

## 3.0.2 candidate compared with 3.0.1

The baseline is [`f9af749`](https://github.com/DKurocl/earbuds/commit/f9af749313655f1e80fed2f8d043b954d4e681ed). The same JVM probe ran against both versions with identical inputs.

| Input | 3.0.1 | 3.0.2 candidate |
|---|---|---|
| Legacy case-only report with a cached earbud minimum of 20% | A 20% earbud observation entered the history | Case level is preserved with no earbud observation |
| Legacy right-only report at 85% with a cached left level of 20% | Minimum recorded as 20% | Right-earbud observation recorded at 85% |
| Left unavailable with a charging flag and right at 72% | Cached left level was reported as charging | Left keeps its cached level and loses charging and live status |

The Bluetooth stream test sends an oversized frame followed by a fragmented 4096-byte escaped payload. The automation tests cover an intervening manual choice and an invalidated timer or connection, plus a widget request superseded by a later choice in the app.
The standalone JVM run passed 42 tests across protocol, stream transport, scheduling and battery
history and health. The Android Gradle JVM run passed all 112 tests, including the mode-controller
tests. [The first candidate run](https://github.com/DKurocl/earbuds/actions/runs/45596239368) also passed
the build, lint, goldens and 20 Android emulator tests.

The launcher icon and widget layouts match the baseline at source level. The page behind the
current one stays drawn for predictive back with its controls excluded from the accessibility
tree. The instrumentation suite exercises both swipe edges and settings scroll position after
resume and recreation.

Row icon backgrounds use the same 40 dp circle for all categories, with the existing wallpaper
colors and error tone. All 32 screenshot test cases rendered locally. Fifteen reference images
changed, with visible pixel differences confined to the row-icon area. Compare the
[3.0.1 settings render](https://github.com/DKurocl/earbuds/blob/f9af749/app/src/screenshotTest/goldens/page-settings.png)
with the [candidate settings render](https://github.com/DKurocl/earbuds/blob/8c51abdf/app/src/screenshotTest/goldens/page-settings.png).

### Pixel 10 Pro XL acceptance

Record the candidate commit, Android build, WF-1000XM5 firmware and app settings for both passes. Keep system font size, display size, theme, wallpaper and brightness constant for the visual comparison.

| Reference | Earbuds comparison | Evidence still required on the Pixel |
|---|---|---|
| Pixel Buds and Pixel Settings | Home and Connected sheet show L/R/Case, current mode and what's available. Earbud settings, App settings, Diagnostics, Profiles, Vibrations and Backup use consistent row, label and back-button alignment. | Light and dark captures, maximum font scale, TalkBack order, and a recording of one bud being put away while the other stays connected. |
| Google Clock | Ambient timer gives a dependable countdown and a predictable return action. | Start, cancel, replace and expire a timer, including the system chip and lock-screen actions. Compare interaction timing and record the device's confirmed mode. |
| Calculator | EQ controls are legible and respond immediately while dragging. | Recording at 120 Hz of slider and graph drags, including finger release, interruption and reduced motion. |
| Wallet | State hierarchy makes available, pending, confirmed and unavailable controls distinguishable. | Light/dark contrast checks, TalkBack and a recording of a failed command, with the corresponding haptic outcome. |
| Pixel Launcher | All three widgets, the lock-screen dashboard and the adaptive/themed icon fit the existing workspace. | Resize and re-add each widget. Capture the player-slot widget next to the player, test each segment and both side edges, and compare icon weight in the launcher grid. |

Compare switching to another app and returning from Recents on Home, Battery, EQ and both settings pages. Capture the first visible frame and the settled frame with the same scroll position. On Home, record the case badge and the Equalizer and Device info rows during the first three seconds, then pull to refresh once. Test predictive-back commit and cancellation from both edges, followed by the same actions with system animations disabled.

For performance, run `coldLaunch` and `settingsTransitions` with the required release baseline
profile. Run the matching `WithoutBaselineProfile` methods to compare the same build without
precompilation. Run `hotResume` for an existing activity returning from Home. Startup reports
initial display and, when the launch intro completes, full display. Record frame-overrun
percentiles, observed refresh rate, final memory samples and Perfetto traces. Use matched
24-hour sessions for phone and WF-1000XM5 battery measurements on the physical Pixel.

For ecosystem acceptance, pair or reconnect real WF-1000XM5, exercise Quick Settings and Gemini, verify companion presence with the app closed, and export/restore the settings across installs. Record which actions the earbuds confirmed and which surfaces stayed in sync. The device pass, side-by-side media and owner approval are pending.

### Follow-up regressions

The [first emulator run of the recreation test](https://github.com/DKurocl/earbuds/actions/runs/45598925960/job/139154612599) preserved the row position after backgrounding and found a 48.76 dp difference after Activity recreation. A follow-up recorded the same scroll position and range before and after recreation (1059 px of 3224 px). The test checks absolute row position on normal resume, and the scroll offset plus the row position inside the viewport on recreation. A second test disables Android animator duration scale while exercising predictive back, then restores its previous value.

An isolated 96 ms battery-fill regression probe recorded six composition changes before moving the reading into the draw phase and zero afterward. It uses the same manual Compose clock and battery values for both runs. The light and dark palette checks cover text and selected controls with the app palette and the emulator wallpaper palette, plus battery widget text.

The Ambient countdown waits for the headphones to confirm Ambient, and a replaced timer keeps its original return mode. Existing alarm and notification actions carry the expiry they belong to. The App Functions battery response captures the snapshot while its connection lease is active, exposes current readings as numbers, and identifies cached levels in the spoken summary.

### Final test session

`bash docs/review/capture-pixel-evidence.sh baseline home` and the matching `candidate`
command save the visible screen, Android and app versions, display state, frame data, memory,
and phone battery for the connected Pixel. Set `ANDROID_SERIAL` if multiple devices are
attached. A snapshot is complete when its folder contains `status.txt`. The script preserves
the current screen and performance counters. Capture each matched scenario with the same
wallpaper, scale, app state and listening setup.

The automated integration suite opens Android Settings over Earbuds and returns to the
existing page and scroll position. It exercises both player-slot widget edges, the mode
segment's setup intent, both battery widget entry points and the Quick Settings cycle.
Use the Pixel Launcher and actual Bluetooth headset for the final trigger and delivery checks.

In the Battery page the weekly figure is an estimate of connected time between readings.
A case-only report keeps the playback estimate labeled as the last reading. If a bud has
reported, the estimate uses the reported buds. The cached level of an unavailable bud remains
visible as its last known value.

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

[Full CI run](https://github.com/DKurocl/earbuds/actions/runs/39755448269): build and emulator jobs passed.

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
