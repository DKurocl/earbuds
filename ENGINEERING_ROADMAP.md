# Earbuds engineering roadmap

- [x] Home. Clearer charge and connected estimate tiles, one stale-reading timestamp, compact Find. Floating dock, stretch and mode glyph motion retained.
- [x] Equalizer. Supersede queued edits, preserve active gestures across read-backs, restore the confirmed preset after Off. Disable graph and bass edits while EQ is Off.
- [x] Battery. Estimate from separate earbuds when both have sufficient history, retain case reserve and show the source once. Keep isolated readings visible around chart gaps.
- [x] Settings. Condense connection, reminder and earbud summaries. Preserve connected list shapes and entry points.
- [x] Transport. Do not deliver empty command frames to payload readers. Reject read-backs across connection generations. Format packet diagnostics without a formatter per byte.
- [x] Widgets. Skip unchanged mode-only RemoteViews while allowing forced size and host updates.
- [ ] Transport. Verify Sony ACK sequencing and delayed replies with traces from WF-1000XM5. Review request result typing and packet-reader costs with those traces.
- [ ] Device integrations. Verify widgets on both hosts, App Functions, Quick Settings, shortcuts, Companion Device presence, timers and Live Updates on Android 17.
- [ ] Persistence. Exercise process death, backup/restore, profile read-back and automation conflicts with real hardware.
- [ ] Performance. Record 120 Hz frame timing, startup with and without the baseline profile, memory, I/O and matched 24-hour battery sessions on Pixel.
