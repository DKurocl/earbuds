package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test
import ua.olex.sonyanc.SonyProtocol.Mode

class BatteryLifeTest {
    @Test fun listeningTimeUsesTheEmptierBudAndTheMode() {
        val b = SonyProtocol.Battery(left = 80, right = 60, case = 50)
        assertEquals(288, BatteryLife.budsMinutes(b, Mode.ANC))   // 60 % of 8 h
        assertEquals(432, BatteryLife.budsMinutes(b, Mode.OFF))   // 60 % of 12 h
        assertEquals(480, BatteryLife.caseMinutes(b, Mode.ANC))   // half of two 8 h charges
        assertEquals("About 4 h 40 min of listening · case adds 8 h", BatteryLife.summary(b, Mode.ANC))
        assertNull(BatteryLife.summary(SonyProtocol.Battery(case = 50), Mode.ANC))
        assertEquals("Under 10 min of listening", BatteryLife.summary(SonyProtocol.Battery(left = 1, right = 1), Mode.ANC))
        assertEquals("About 1 h of listening", BatteryLife.summary(SonyProtocol.Battery(left = 13), Mode.ANC))
    }

    @Test fun shortDurationsForTheBigNumber() {
        assertEquals("< 10 min", BatteryLife.short(4))
        assertEquals("5 h 30 min", BatteryLife.short(335))
        assertEquals("12 h", BatteryLife.short(720))
    }

    private val min = 60_000L
    private fun sample(minute: Long, level: Int, session: Int = 1, charging: Boolean = false, mode: Mode? = Mode.ANC) =
        BatterySample(minute * min, level, charging, mode, session)

    @Test fun learnsTheRealDrainWithinOneSession() {
        // 100 % -> 70 % in two hours of noise cancelling: 0.25 %/min, so 400 min per charge.
        val log = listOf(sample(0, 100), sample(60, 85), sample(120, 70))
        val rate = BatteryLog.rate(log, Mode.ANC)!!
        assertEquals(0.25, rate, 1e-9)
        assertEquals(400, BatteryLife.fullChargeMinutes(Mode.ANC, rate))
        assertEquals(280, BatteryLife.budsMinutes(SonyProtocol.Battery(70, 75, null), Mode.ANC, rate))
    }

    @Test fun ignoresCaseTimeChargingAndOtherSessions() {
        val log = listOf(
            sample(0, 90), sample(30, 88),                  // too little
            sample(40, 60, charging = true), sample(80, 80, charging = true),
            sample(90, 80, session = 2), sample(400, 40, session = 3), // across sessions
        )
        assertNull(BatteryLog.rate(log, Mode.ANC))
    }

    @Test fun offHasItsOwnBucketAndRatingsStayTheFallback() {
        val log = listOf(sample(0, 100, mode = Mode.OFF), sample(120, 80, mode = Mode.OFF))
        assertNull(BatteryLog.rate(log, Mode.ANC))
        assertEquals(8 * 60, BatteryLife.fullChargeMinutes(Mode.ANC, null))
        assertEquals(600, BatteryLife.fullChargeMinutes(Mode.OFF, BatteryLog.rate(log, Mode.OFF)))
    }

    @Test fun anOddSessionCannotPushTheEstimateFarFromTheRating() {
        assertEquals(240, BatteryLife.fullChargeMinutes(Mode.ANC, 5.0))   // would be 20 min
        assertEquals(720, BatteryLife.fullChargeMinutes(Mode.ANC, 0.01))  // would be 166 h
    }

    @Test fun logRoundTripsAndSkipsRepeats() {
        var log = emptyList<BatterySample>()
        log = BatteryLog.add(log, sample(0, 80))
        log = BatteryLog.add(log, sample(5, 80))      // same level, 5 min later: skipped
        log = BatteryLog.add(log, sample(6, 79))
        assertEquals(2, log.size)
        assertEquals(log, BatteryLog.decode(BatteryLog.encode(log)))
        assertEquals(emptyList<BatterySample>(), BatteryLog.decode("garbage;1,2"))
    }
}
