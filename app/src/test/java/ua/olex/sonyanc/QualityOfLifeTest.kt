package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test
import ua.olex.sonyanc.SonyProtocol.Mode

class QualityOfLifeTest {
    @Test fun walkingAsksForAmbientAndStillnessForNoiseCancelling() {
        assertEquals(Mode.AMBIENT, ActivityMode.decide(Mode.ANC, walking = true))
        assertEquals(Mode.ANC, ActivityMode.decide(Mode.AMBIENT, walking = false))
        assertNull(ActivityMode.decide(Mode.OFF, walking = true))     // Off is the owner's choice
        assertNull(ActivityMode.decide(Mode.AMBIENT, walking = true)) // already right
        assertNull(ActivityMode.decide(null, walking = true))
    }

    @Test fun usualTimeNeedsFiveDaysAndTakesTheMedian() {
        val week = (1..4).map { Triple(it, false, 8 * 60 + it) }
        assertNull(ChargeReminder.usualMinute(week, weekend = false))
        val more = week + Triple(5, false, 9 * 60) + Triple(6, true, 11 * 60)
        assertEquals(8 * 60 + 3, ChargeReminder.usualMinute(more, weekend = false))
        assertNull(ChargeReminder.usualMinute(more, weekend = true))
    }

    @Test fun weekCountsSessionsAndNoiseCancelling() {
        val h = 60 * 60_000L
        val now = 10 * h
        val log = listOf(
            BatterySample(1 * h, 90, false, Mode.ANC, 1), BatterySample(2 * h, 80, false, Mode.AMBIENT, 1),
            BatterySample(3 * h, 70, false, Mode.ANC, 1),
            BatterySample(4 * h, 70, true, Mode.ANC, 2), BatterySample(5 * h, 90, true, Mode.ANC, 2), // charging: not counted
        )
        assertEquals(BatteryLog.Week(120, 60), BatteryLog.week(log, now))
    }
}
