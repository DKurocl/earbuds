package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test

class CaseReminderTest {
    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L

    private fun remind(enabled: Boolean = true, case: Int? = 18, charging: Boolean = false, readAgo: Long = hour, remindedAgo: Long = 48 * hour) =
        CaseReminder.shouldRemind(enabled, case, charging, now - readAgo, now - remindedAgo, now)

    @Test fun remindsForALowRecentNotChargingCase() = assertTrue(remind())
    @Test fun offUnlessTurnedOn() = assertFalse(remind(enabled = false))
    @Test fun notAboveTheThreshold() { assertTrue(remind(case = 25)); assertFalse(remind(case = 26)) }
    @Test fun notWhileCharging() = assertFalse(remind(charging = true))
    @Test fun notFromAnUnknownOrOldReading() { assertFalse(remind(case = null)); assertFalse(remind(readAgo = 13 * hour)) }
    @Test fun atMostTwiceADay() { assertFalse(remind(remindedAgo = 3 * hour)); assertTrue(remind(remindedAgo = 12 * hour)) }
    @Test fun aClockJumpBackwardsDoesNotRemind() = assertFalse(remind(readAgo = -hour))
}
