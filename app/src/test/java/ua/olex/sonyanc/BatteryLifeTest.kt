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
}
