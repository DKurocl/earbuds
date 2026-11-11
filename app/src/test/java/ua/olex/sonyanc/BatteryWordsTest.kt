package ua.olex.sonyanc

import org.junit.Assert.assertEquals
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
}
