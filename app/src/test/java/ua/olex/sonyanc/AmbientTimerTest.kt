package ua.olex.sonyanc

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientTimerTest {
    @Test fun countdownRoundsUpAndNeverGoesNegative() {
        assertEquals("4:12", AmbientTimer.countdown(251_200L))
        assertEquals("5:00", AmbientTimer.countdown(300_000L))
        assertEquals("0:01", AmbientTimer.countdown(1L))
        assertEquals("0:00", AmbientTimer.countdown(-5_000L))
    }

    @Test fun choicesAreShortBreaks() = assertEquals(listOf(2, 5, 10), AmbientTimer.CHOICES)
}
