# Safe Listening protocol (from Sony Sound Connect 13.3.1, decompiled)

All frames use data type 0x0E (MDR table 2). Byte 1 is the inquired type:
0x00 HBS_1, 0x01 TWS_1, 0x02 HBS_2, 0x03 TWS_2 (TWS = true wireless, L and R logged separately).
On/off bytes: 0x00 = ON, 0x01 = OFF. Enable bytes: 0x00 = ENABLE, 0x01 = DISABLE.

| Op | Name | Payload |
|---|---|---|
| 0x50 / 0x51 | capability | get `50 t`; ret `51 t roundBase tsBase(4, BE) minIntervalSec logCapacityDays` (9 bytes) |
| 0x52 / 0x53 | status | get `52 t`; ret TWS_2 `53 03 stL stR recL(13) recR(13)` (30 bytes): current round per earbud |
| 0x54 | set status | `54 t stL stR lastL(6) lastR(6)`: SENDING(01) + last received (ts 4 BE, rtcRc 2 BE) asks for newer logs; NOT_SENDING(03) + zeros resets |
| 0x55 | notify status | TWS_2 `55 03 stL stR n rec(13) x n`; repeated until status COMPLETED(02) |
| 0x56 / 0x57 | param | get `56 t`; ret `57 t enable` |
| 0x58 / 0x59 | set / notify param | `58 t onoff1 onoff2`; Sony sends `ON, OFF` to start logging and `OFF, OFF` to stop |
| 0x5A / 0x5B | live level | get `5A t`; ret `5B t level errorCause`: level in dB, or 255 with cause 0 not playing, 1 in call, 2 removed. Sony polls every minIntervalSec while the screen is open |

Log status: 0 DISCONNECTED, 1 SENDING, 2 COMPLETED, 3 NOT_SENDING, 4 ERROR.

Record (13 bytes TWS_2, 12 bytes TWS_1 without the last byte):
`target(0 HBS,1 L,2 R) timestamp(4, BE, seconds, earbud clock) rtcResetCounter(2, BE) viewTimeMin(1) soundPressure(4, BE) ambientTime(1)`

Time correction (Sony): newest record time is matched to the phone time of the sync; older records keep their
spacing. A changed rtcResetCounter means the earbud clock restarted.

## Math (SlConstant)

- Energy E (Pa^2 s) = soundPressure / 100000
- Average level dB = 10 log10( (E / 60) / viewTimeMin / (20e-6)^2 )
- WHO allowance: NORMAL 1.6 Pa^2 h per 7 days (= 80 dB, 40 h), SENSITIVE 0.51 Pa^2 h (= 75 dB)
- Dose % over N days = (E / 60) * 100 / (allowanceMin * N / 7), allowanceMin = 96 (NORMAL) or 30.6 (SENSITIVE)
- TWS: L and R are averaged ((EL + ER) / 2, (tL + tR) / 2)
