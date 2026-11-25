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

    @Test fun fasterDrainingEarbudCanLimitPlaybackEvenWithMoreCharge() {
        val log = listOf(
            sample(0, 100).copy(left = 100, right = 100),
            sample(60, 85).copy(left = 85, right = 93),
            sample(120, 70).copy(left = 70, right = 86),
        )
        val drain = BatteryLog.perEarbudDrain(log, Mode.ANC)!!
        assertEquals(0.25, drain.left.rate, 1e-9)
        assertEquals(14.0 / 120, drain.right.rate, 1e-9)
        assertEquals(320, drain.minutesLeft(SonyProtocol.Battery(80, 60, null), Mode.ANC))
        assertNull(BatteryLog.perEarbudDrain(log.map { it.copy(right = null) }, Mode.ANC))
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

    @Test fun fallbackUsesTheSonyLdacRatingWithoutReplacingLearnedDrain() {
        val ldac = CodecProtocol.LDAC
        val battery = SonyProtocol.Battery(50, 75, 50)
        assertEquals(150, BatteryLife.budsMinutes(battery, Mode.ANC, codec = ldac))
        assertEquals(240, BatteryLife.budsMinutes(battery, Mode.OFF, codec = ldac))
        assertEquals(300, BatteryLife.caseMinutes(battery, Mode.ANC, codec = ldac))
        assertEquals(200, BatteryLife.budsMinutes(battery, Mode.ANC, rate = .25, codec = ldac))
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

    @Test fun separateDrainAcceptsChangingPacketShapesAndIgnoresCase() {
        val log = listOf(
            sample(0, 90).copy(left = 90, right = 95),
            sample(20, 0).copy(level = null, charging = true, case = 65),
            sample(60, 75).copy(left = 75),
            sample(90, 86).copy(right = 86),
        )
        val drain = BatteryLog.perEarbudDrain(log, Mode.ANC)!!
        assertEquals(.25, drain.left.rate, 1e-9)
        assertEquals(.1, drain.right.rate, 1e-9)
        assertNull(BatteryLog.rate(log, Mode.ANC))
    }

    @Test fun alternatingCaseAndBudsDeduplicateWithoutHidingChanges() {
        val buds = sample(0, 80).copy(left = 80, right = 87)
        val case = sample(1, 0).copy(level = null, case = 92)
        var log = listOf(buds, case)
        for (minute in 2..9) {
            log = BatteryLog.add(log, buds.copy(at = minute * 60_000L))
            log = BatteryLog.add(log, case.copy(at = minute * 60_000L))
        }
        assertEquals(listOf(buds, case), log)

        val changed = buds.copy(at = 9 * 60_000L, right = 86)
        assertEquals(log + changed, BatteryLog.add(log, changed))
        val nextSession = buds.copy(at = 9 * 60_000L, session = buds.session + 1)
        assertEquals(log + nextSession, BatteryLog.add(log, nextSession))
        val later = buds.copy(at = 10 * 60_000L)
        assertEquals(log + later, BatteryLog.add(log, later))
    }

    @Test fun oldFiveFieldLogsStillReadAndTheCaseIsKept() {
        val old = BatteryLog.decode("60000,80,0,ANC,1")
        assertEquals(listOf(BatterySample(60000, 80, false, Mode.ANC, 1, null)), old)
        val withCase = sample(10, 70).copy(case = 45)
        assertEquals(listOf(withCase), BatteryLog.decode(BatteryLog.encode(listOf(withCase))))
    }

    @Test fun preservesEachEarbudEvenWhenTheLowerLevelIsUnchanged() {
        val older = BatteryLog.decode("60000,80,0,ANC,1,40").single()
        assertNull(older.left)
        assertNull(older.right)
        val first = sample(2, 80).copy(left = 80, right = 90)
        val second = sample(3, 80).copy(left = 80, right = 87)
        val updated = BatteryLog.add(listOf(older, first), second)
        assertEquals(listOf(older, first, second), BatteryLog.decode(BatteryLog.encode(updated)))
    }

    @Test fun historyKeepsAboutAWeek() {
        val day = 24 * 60L
        var log = emptyList<BatterySample>()
        for (d in 0..10) log = BatteryLog.add(log, sample(d * day, 90 - d))
        assertTrue(log.first().at >= log.last().at - BatteryLog.KEEP_MS)
        assertEquals(9, log.size) // days 2..10
    }

    @Test fun drainReportsHowMuchUseItRestsOn() {
        val d = BatteryLog.drain(listOf(sample(0, 100), sample(90, 70)), Mode.ANC)!!
        assertEquals(90.0, d.minutes, 1e-9)
    }
    @Test fun drainIgnoresIntervalsAcrossNoiseControlBuckets() {
        val log = listOf(sample(0, 100, mode = Mode.ANC), sample(60, 75, mode = Mode.OFF))
        assertNull(BatteryLog.rate(log, Mode.ANC))
        assertNull(BatteryLog.rate(log, Mode.OFF))
    }

    @Test fun recordsOnlyPartsReportedInThisFrame() {
        val cached = SonyProtocol.Battery(20, 85, 40, leftCharging = true)
        val case = BatterySample.fromReading(30 * min, cached, setOf(2), Mode.ANC, 1)!!
        val right = BatterySample.fromReading(60 * min, cached, setOf(1), Mode.ANC, 1)!!
        assertNull(case.level)
        assertEquals(40, case.case)
        assertEquals(85, right.level)
        assertNull(right.left)
        assertNull(right.case)
        assertFalse(right.charging)
        assertEquals(listOf(case, right), BatteryLog.decode(BatteryLog.encode(listOf(case, right))))
    }

    @Test fun caseOnlyPacketsAndChangingCoverageCannotInventDrain() {
        val first = sample(0, 85).copy(left = 90, right = 85)
        val caseOnly = sample(30, 70).copy(level = null, case = 40)
        val both = sample(60, 70).copy(left = 80, right = 70)
        val one = sample(60, 70).copy(right = 70)
        assertEquals(0.25, BatteryLog.rate(listOf(first, caseOnly, both), Mode.ANC)!!, 1e-9)
        assertNull(BatteryLog.rate(listOf(first, caseOnly, one), Mode.ANC))
        assertNull(BatteryLog.rate(listOf(first, caseOnly), Mode.ANC))
        assertEquals(BatteryLog.Week(0, 0), BatteryLog.week(listOf(first, caseOnly), 60 * min))
    }

    @Test fun legacyEightFieldCaseAndPartialPacketsDiscardTheCachedMinimum() {
        val log = BatteryLog.decode("60000,20,0,ANC,1,40,-,-;120000,20,0,ANC,1,40,-,85")
        assertNull(log[0].level)
        assertEquals(40, log[0].case)
        assertEquals(85, log[1].level)
    }

    @Test fun unknownModeAndChargingEndpointsDoNotTrainTheEstimate() {
        assertNull(BatteryLog.rate(listOf(sample(0, 90, mode = null), sample(60, 70)), Mode.ANC))
        assertEquals(BatteryLog.Week(0, 0), BatteryLog.week(listOf(sample(0, 90), sample(60, 70, charging = true)), 60 * min))
        assertEquals(2, BatteryLog.add(listOf(sample(0, 90)), sample(1, 90, mode = Mode.OFF)).size)
    }

    @Test fun listeningEstimatesUseFreshBudsAndFallBackToTheLastReading() {
        val cached = SonyProtocol.Battery(20, 85, 40)
        assertEquals(408, BatteryLife.budsMinutes(BatteryLife.forEstimate(cached, setOf(1)), Mode.ANC))
        assertEquals(96, BatteryLife.budsMinutes(BatteryLife.forEstimate(cached, setOf(2)), Mode.ANC))
        assertEquals(cached, BatteryLife.forEstimate(cached, setOf(0, 1, 2)))
    }
}
