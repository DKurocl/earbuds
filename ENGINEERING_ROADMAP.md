# Earbuds engineering roadmap

- [x] Home. Clearer charge and connected estimate tiles, one stale-reading timestamp, compact Find. Floating dock, stretch and mode glyph motion retained.
- [x] Equalizer. Supersede queued edits, preserve active gestures across read-backs, restore the confirmed preset after Off. Disable graph and bass edits while EQ is Off.
- [x] Settings. Condense connection, reminder and earbud summaries. Preserve connected list shapes and entry points.
- [x] Transport. Do not deliver empty command frames to payload readers. Reject read-backs across connection generations. Format packet diagnostics without a formatter per byte.
- [x] Widgets. Skip unchanged mode-only RemoteViews while allowing forced size and host updates.
- [ ] Transport. Verify Sony ACK sequencing and delayed replies with traces from WF-1000XM5. Review request result typing and packet-reader costs with those traces.
- [ ] Device integrations. Verify widgets on both hosts, App Functions, Quick Settings, shortcuts, Companion Device presence, timers and Live Updates on Android 17.
- [ ] Persistence. Exercise process death, backup/restore, profile read-back and automation conflicts with real hardware.
- [ ] Startup. Compare cold starts on Pixel with and without the baseline profile.

## Battery and idle efficiency

- [x] Battery history. Select L/R/Case independently, keep observed gaps and timestamp each point. Cache one parsed history and its drain rates. Deduplicate interleaved unchanged packets within the existing ten-minute window.
- [ ] Idle on Pixel. Compare 3.0.3 and current release in matching 60-minute screen-off runs with WF-1000XM5 disconnected and connected, and repeat connected with Stay in sync enabled. Record app UID CPU time, alarms, wake locks, Bluetooth reads and CPU wakeups with `dumpsys batterystats` and Perfetto. Expect zero periodic Bluetooth reads when the UI is closed and no command is running.
- [ ] Storage and widgets. Count writes of history and last-reading preferences and RemoteViews submissions per Bluetooth packet. Keep charge changes and reconnects visible. Coalesce work only where the trace shows duplicates.
- [ ] Memory and frames. Compare PSS with `dumpsys meminfo` after cold start, after repeated Battery/Home navigation and after 30 minutes idle. Check retained activities, allocations and 120 Hz frame timing in Perfetto. Verify CPU returns to idle after updates and PSS stabilizes over repeated runs.
- [ ] Recovery. Exercise reconnect, process kill, missing case reads, and concurrent widget/timer mode requests on WF-1000XM5. Verify pending commands recover and stale data stays marked. Repeat matched 24-hour idle runs before claiming an autonomy improvement.
