package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SonyFeaturesTest {
    private fun hex(s: String) = SonyProtocol.parseHex(s)

    @Test fun supportListsReadBothTables() {
        val t1 = SupportedFunctions.parse(hex("07 00 02 F6 01 30 02"), 1)!!
        assertEquals(setOf(0x1F6, 0x130), t1)
        val t2 = SupportedFunctions.parse(hex("07 00 01 53 01"), 2)!!
        assertEquals(setOf(SupportedFunctions.SL_TWS_2), t2)
        assertNull(SupportedFunctions.parse(hex("07 00 02 F6 01"), 1))
        assertEquals(t1 + t2, SupportedFunctions.decode(SupportedFunctions.encode(t1 + t2)))
        assertEquals("Safe listening TWS 2", SONY_FUNCTION_NAMES[SupportedFunctions.SL_TWS_2])
        assertEquals(3, SafeListening.type(t2))
        assertNull(SafeListening.type(t1))
    }

    @Test fun safeListeningLogsAndStatusFollowSonyLayout() {
        // TWS_2: statuses left/right, count, then 13-byte records.
        val rec = "01 00 00 0E 10 00 05 3C 00 98 96 80 07"
        val batch = SafeListening.logs(hex("55 03 01 01 01 $rec"), 3)!!
        assertEquals(listOf(1, 1), batch.statuses)
        val r = batch.records.single()
        assertEquals(1, r.side); assertEquals(3600L, r.timestamp); assertEquals(5, r.rtc)
        assertEquals(60, r.minutes); assertEquals(10_000_000L, r.pressure)
        assertNull(SafeListening.logs(hex("55 03 01 01 02 $rec"), 3))

        val status = SafeListening.status(hex("53 03 02 02 $rec 02 00 00 1C 20 00 05 0A 00 00 00 10 00"), 3)!!
        assertEquals(listOf(2, 2), status.statuses)
        assertEquals(2, status.records[1].side)
        assertEquals(7200L, status.records[1].timestamp)

        val ask = SafeListening.request(3, mapOf(1 to (3600L to 5)))
        assertArrayEquals(hex("54 03 01 01 00 00 0E 10 00 05 00 00 00 00 00 00"), ask)
        assertEquals(SafeListening.Live(68, null), SafeListening.live(hex("5B 03 44 00"), 3))
        assertEquals(SafeListening.Live(null, 1), SafeListening.live(hex("5B 03 FF 01"), 3))
        assertEquals(SafeListening.Capability(60, 5, 7), SafeListening.capability(hex("51 03 3C 00 00 00 00 05 07"), 3))
        assertEquals(true, SafeListening.available(hex("57 03 00"), 3))
        assertArrayEquals(hex("58 03 00 01"), SafeListening.start(3))
    }

    @Test fun doseAndLevelMatchTheWhoReference() {
        // 80 dB for 40 h: (20 µPa · 10^4)^2 = 0.04 Pa^2 for 2400 min = 96 Pa^2 min = 100 % of the week.
        val minutes = 2400.0
        val pressure = 0.04 * minutes * 60 * 100_000
        assertEquals(1.0, SafeListening.dose(pressure, SafeListening.Reference.STANDARD), 1e-9)
        assertEquals(80.0, SafeListening.averageDb(pressure, minutes)!!, 0.01)
        assertTrue(abs(SafeListening.dose(pressure, SafeListening.Reference.SENSITIVE) - 96 / 30.6) < 1e-9)
        assertNull(SafeListening.averageDb(0.0, 0.0))
    }

    @Test fun recordsArePlacedOnThePhoneClockAndSummedPerWeek() {
        val now = 1_700_000_000_000L
        val anchor = mapOf(1 to SafeListening.Record(1, 10_000, 3, 10, 0), 2 to SafeListening.Record(2, 10_000, 3, 10, 0))
        val placed = SafeListening.place(listOf(
            SafeListening.Record(1, 6_400, 3, 60, 1000), SafeListening.Record(2, 6_400, 4, 60, 1000),
        ), anchor, now)
        assertEquals(1, placed.size)
        assertEquals(now - 3_600_000L, placed[0].at)
        val week = SafeListening.summary(placed + SafeListening.Entry(now - 3_600_000L, 2, 60, 3000), emptyList(), now, sides = 2)
        assertEquals(2000.0, week.pressure, 1e-9)
        assertEquals(60.0, week.minutes, 1e-9)
        assertEquals(7, week.days.size)
        val stored = SafeListening.decode(SafeListening.encode(placed))
        assertEquals(placed, stored)
    }

    @Test fun fitTestFramesFollowTheSystemTable() {
        assertArrayEquals(hex("F4 06 01 01"), FitTest.mode(true))
        assertArrayEquals(hex("F8 06 00 00 01 FF"), FitTest.start(1))
        assertEquals(FitTest.Progress(2, 0), FitTest.progress(hex("F9 06 02 00 01 00 01 FF")))
        assertEquals(true to false, FitTest.result(hex("FD 06 00 01 01 01 02 02")))
        assertEquals(1, FitTest.series(hex("F7 07 01")))
        assertNull(FitTest.result(hex("FD 07 00 01 01 01 02 02")))
    }

    @Test fun multipointListsDevicesAndActions() {
        val a = "41 41 3A 42 42 3A 43 43 3A 44 44 3A 45 45 3A 46 46" // AA:BB:CC:DD:EE:FF
        val b = "31 31 3A 32 32 3A 33 33 3A 34 34 3A 35 35 3A 36 36" // 11:22:33:44:55:66
        val list = Multipoint.parse(hex("37 00 02 $a 01 05 50 69 78 65 6C $b 00 02 50 43 01"), 0)!!
        assertEquals(listOf("Pixel", "PC"), list.devices.map { it.name })
        assertTrue(list.devices[0].connected); assertFalse(list.devices[1].connected)
        assertEquals(1, list.playing)
        val withClass = Multipoint.parse(hex("37 02 01 $a 01 5A 02 0C 05 50 69 78 65 6C 01"), 2)!!
        assertEquals("Pixel", withClass.devices.single().name)
        assertNull(Multipoint.parse(hex("37 00 01 $a 01 05 50 69 78"), 0))
        assertArrayEquals(hex("3C 00 00 01 $a"), Multipoint.action(0, 1, "AA:BB:CC:DD:EE:FF"))
        assertArrayEquals(hex("3C 01 $a"), Multipoint.switchTo("AA:BB:CC:DD:EE:FF"))
        assertEquals(0x10, Multipoint.outcome(hex("3D 00 01 10 $a")))
        assertEquals(0, Multipoint.outcome(hex("3D 01 00 $a")))
    }
}
