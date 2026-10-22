# WF-1000XM5 replacement audit — Hush 0.8.0

Goal: independent everyday control, with no Sony app, account, network permission, notification service or simulated settings.

## Everyday use

| Function | Hush implementation |
|---|---|
| ANC / ambient / off | Direct V2 command, ACK and state readback |
| Ambient intensity and voice focus | 0–20 slider and presets |
| Codec | Device report, not inferred from phone preferences |
| Left / right / case battery | Direct replies, partial updates preserve last known values; unknown remains unknown |
| Charging | Accepts known V2 charging/charged/unknown states |
| EQ and Clear Bass | Device presets and six editable values, readback |
| DSEE | Read-gated switch |
| Wearing auto-pause | Corrected F6 01 inquiry, read-gated switch |
| Home widget / launcher shortcuts | Direct on-demand mode selection |
| Quick Settings | Optional cycling tile, no notification |
| Device nickname | Local display name, independent of Bluetooth name |
| Connection refresh / diagnostics | Reconnect, codec, battery, exportable command log |

## Additional device controls

Each control is read when opened. Writes are enabled only after the device returns a recognized layout; ACK alone is never shown as successful configuration. Unsupported firmware replies leave the control unavailable. These protocol implementations are not a claim of hardware verification on the owner's firmware.

| Function | Status |
|---|---|
| Speak-to-Chat switch | Implemented, readback required |
| Conversation sensitivity / resume delay | Implemented, readback required |
| Left / right touch presets | Implemented, preserves the opposite side |
| Noise-control button cycle | Four known combinations implemented |
| Automatic power off | Removed-from-ears / disabled implemented |
| Call sidetone | Switch implemented, preserves reported volume fields |
| Head gestures | Switch implemented for recognized reply |
| Connection quality priority | Sound / stability implemented for recognized classic reply |
| Voice guidance | Switch over the second command table; language preserved |
| Guidance volume | Five levels implemented |
| Firmware version | Read-only, validated length and characters |
| Earbud shutdown | Explicit user action with confirmation |

## Platform-owned and remaining gaps

| Function | Delivery / limitation |
|---|---|
| Pairing and forgetting | Android Bluetooth settings |
| Multipoint and host switching | Android device settings on the owner's Pixel; no independent Hush protocol control yet |
| Spatial audio / head tracking | Android settings; no Hush DSP or fake toggle |
| LE Audio | Android settings; Hush's RFCOMM controls may be unavailable in LE-only operation |
| Playback / volume | Existing system player and earbud actions; no duplicate player in Hush |
| Voice assistant / wake word | Android and assistant setup; not provisioned by Hush |
| Quick Access / Service Link | Partner provisioning not implemented |
| Voice language packages | Download/transfer not implemented; existing language preserved |
| Firmware updates / automatic updates | Not implemented; Sony app still required for update delivery |
| Ear-tip seal measurement | Not implemented; requires a verified device measurement session |
| Adaptive Sound Control | No activity/location background service implemented |
| 360 Reality Audio personalization | Sony/service provisioning not implemented |
| Safe-listening history / Auto Play / Auto Switch | Not implemented; not presented as functioning |
| Initialize / factory reset | Deliberately absent from routine controls |
| Usage history / charging alerts | No tracking or notification system |

Hush is not yet a complete replacement for firmware servicing, ear-tip measurement, adaptive behavior or Sony/partner provisioning. Installing this APK does not establish parity. Routine controls can work independently; remaining device paths need captures and physical verification.

## Evidence and protocol references

- Sony WF-1000XM5 official app feature list: https://helpguide.sony.net/mdr/2963/v1/en/contents/TP1000781980.html
- Gadgetbridge V2 protocol and tests (command layouts; not copied implementation): https://github.com/Freeyourgadget/Gadgetbridge/tree/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/protocol/impl/v2
- SonyHeadphonesClient V2 type definitions and WF-1000XM5 6.1.0 packet fixtures at db0ae2574d8f8e1407e8ea28a7ee87db9dde1c24: https://github.com/mos9527/SonyHeadphonesClient/tree/db0ae2574d8f8e1407e8ea28a7ee87db9dde1c24/tests/WF-1000XM5-6.1.0

## Visual refinements

The two earbuds now use the same front-facing shot mirrored in layout, at equal size. No differently angled pair is cropped into the rings. The case uses the existing transparent photo. Dark background uses a Material container surface, action buttons use secondary-container colors, battery green is #43C465, and inactive/unknown states remain visually separate from current readings. ANC labels are compact and retain a fixed row height.
