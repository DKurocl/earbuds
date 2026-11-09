package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test

class BatteryHealthTest {
    private fun week(w: Long, minutes: Int) = WeekHealth(w, minutes, 5)

    @Test fun weeksStartOnMonday() {
        val monday = 1_762_732_800_000L // 2025-11-10 00:00 UTC, a Monday
        assertEquals(BatteryHealth.weekOf(monday), BatteryHealth.weekOf(monday + 6 * 86_400_000L + 86_399_000L))
        assertEquals(BatteryHealth.weekOf(monday) - 1, BatteryHealth.weekOf(monday - 1))
    }

    @Test fun recordsOnlyWithEnoughUseAndKeepsOneFigurePerWeek() {
        var h = BatteryHealth.record(emptyList(), 10, Drain(0.25, 30.0))
        assertTrue("half an hour is not enough", h.isEmpty())
        h = BatteryHealth.record(h, 10, Drain(0.25, 120.0))
        h = BatteryHealth.record(h, 10, Drain(0.20, 180.0))
        assertEquals(listOf(WeekHealth(10, 500, 3)), h) // 0.20 %/min: 500 min per charge
        assertEquals(h, BatteryHealth.decode(BatteryHealth.encode(h)))
    }

    @Test fun aShortHistoryIsOnlyTracked() {
        assertNull(BatteryHealth.verdict(emptyList()))
        assertTrue(BatteryHealth.verdict(listOf(week(1, 420), week(3, 400))) is BatteryHealth.Verdict.Tracking)
    }

    @Test fun steadyUnlessTenPercentShorterThanTheBestEarlyWeeks() {
        val steady = BatteryHealth.verdict(listOf(week(1, 420), week(2, 430), week(6, 400)))
        assertTrue(steady is BatteryHealth.Verdict.Steady)
        val declined = BatteryHealth.verdict(listOf(week(1, 420), week(2, 430), week(20, 370)))
        assertEquals(BatteryHealth.Verdict.Declined(1, 370, 430, 13), declined)
    }

    @Test fun keepsAboutAYear() {
        var h = emptyList<WeekHealth>()
        for (w in 0L until 60L) h = BatteryHealth.record(h, w, Drain(0.2, 120.0))
        assertEquals(BatteryHealth.MAX_WEEKS, h.size)
        assertEquals(8L, h.first().week)
    }
}
