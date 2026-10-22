# Hush 0.9.0 verification

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
