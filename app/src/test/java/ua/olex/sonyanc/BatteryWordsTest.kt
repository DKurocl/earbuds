package ua.olex.sonyanc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryWordsTest {
    private fun b(l: Int?, r: Int?, c: Int? = null, charging: Boolean = false) =
        SonyProtocol.Battery(l, r, c, leftCharging = charging, rightCharging = charging)

    @Test fun equalEarbudsAreOneNumber() =
        assertEquals("Both earbuds are at 80%, the case 55%. About 6 h of listening left.", BatteryWords.sentence(b(80, 80, 55), "6 h", linked = true))

    @Test fun differentEarbudsAreNamed() =
        assertEquals("Left earbud 40%, right 60%.", BatteryWords.sentence(b(40, 60), null, linked = true))

    @Test fun inTheCaseNoListeningTime() =
        assertEquals("Left earbud 40%, right 60%, the case 90%.", BatteryWords.sentence(b(40, 60, 90), "3 h", linked = false))

    @Test fun chargingSaysSo() =
        assertEquals("Both earbuds are at 70%. Charging.", BatteryWords.sentence(b(70, 70, charging = true), "5 h", linked = true))

    @Test fun unknownSaysWhatToDo() =
        assertEquals("The earbuds' battery isn't known yet. Take them out of the case.", BatteryWords.sentence(b(null, null), null, linked = false))

    @Test fun assistantDistinguishesFreshRightBudFromCachedLeftAndCase() {
        val state = DeviceState(battery = b(20, 85, 40, charging = true).copy(rightCharging = false), liveBatteryParts = setOf(1))
        val answer = BatteryWords.forAssistant(state, null)
        assertEquals(-1, answer.leftPercent)
        assertEquals(85, answer.rightPercent)
        assertEquals(-1, answer.casePercent)
        assertFalse(answer.charging)
        assertEquals(408, answer.listeningMinutesLeft)
        assertTrue(answer.summary.endsWith("Last known readings: left earbud 20%, case 40%."))
    }

    @Test fun assistantCaseOnlyReportDoesNotClaimThatCachedBudsAreCharging() {
        val state = DeviceState(battery = b(20, 85, 40, charging = true), liveBatteryParts = setOf(2))
        val answer = BatteryWords.forAssistant(state, null)
        assertEquals(40, answer.casePercent)
        assertEquals(-1, answer.listeningMinutesLeft)
        assertFalse(answer.charging)
        assertEquals("Case battery 40%. Last known readings: left earbud 20%, right earbud 85%.", answer.summary)
    }
}
