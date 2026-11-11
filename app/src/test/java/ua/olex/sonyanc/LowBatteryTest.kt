package ua.olex.sonyanc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.olex.sonyanc.SonyProtocol.Battery
import ua.olex.sonyanc.SonyProtocol.Mode

class LowBatteryTest {
    @Test fun alreadyLowNotifiesNow() = assertEquals(0, LowBattery.minutesToLow(Battery(12, 40, 50), Mode.ANC, null))

    @Test fun theEmptierEarbudDecides() {
        val both = LowBattery.minutesToLow(Battery(60, 30, 50), Mode.ANC, null)!!
        val right = LowBattery.minutesToLow(Battery(30, 30, 50), Mode.ANC, null)!!
        assertEquals(right, both)
        assertTrue(both > 0)
    }

    @Test fun moreBatteryMeansLaterCheck() {
        val low = LowBattery.minutesToLow(Battery(30, 30, null), Mode.ANC, null)!!
        val high = LowBattery.minutesToLow(Battery(90, 90, null), Mode.ANC, null)!!
        assertTrue(high > low)
    }

    @Test fun nothingToPlanWhileChargingOrUnknown() {
        assertNull(LowBattery.minutesToLow(Battery(10, 10, 50, leftCharging = true), Mode.ANC, null))
        assertNull(LowBattery.minutesToLow(Battery(null, null, 50), Mode.ANC, null))
    }
}
