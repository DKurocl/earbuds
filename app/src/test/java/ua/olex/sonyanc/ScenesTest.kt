package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test
import ua.olex.sonyanc.SonyProtocol.Mode
import ua.olex.sonyanc.SonyProtocol.NcState

class ScenesTest {
    @Test fun scenesSurviveStorageIncludingAwkwardNames() {
        val scenes = listOf(
            Scene("a", "Gym | loud\nnight", NcState(Mode.AMBIENT, voice = true, level = 17), 0x16),
            Scene("b", "Focus", NcState(Mode.ANC)),
        )
        val back = Scenes.decode(Scenes.encode(scenes))
        assertEquals(2, back.size)
        assertEquals("Gym   loud night", back[0].name)
        assertEquals(scenes[0].nc, back[0].nc)
        assertEquals(0x16, back[0].eqPreset)
        assertNull(back[1].eqPreset)
    }

    @Test fun corruptStorageIsSkippedNotCrashing() {
        val text = "x|Ok|ANC|0|20|-1\nbroken\ny|Bad mode|LOUD|0|20|-1\nz|Bad eq|OFF|0|5|999\nx|Duplicate|OFF|0|5|-1"
        val back = Scenes.decode(text)
        assertEquals(listOf("x", "z"), back.map { it.id })
        assertNull("unknown preset dropped, scene kept", back[1].eqPreset)
    }

    @Test fun ambientScenesMatchLevelAndVoiceOthersOnlyTheMode() {
        val talk = Scene("t", "Talk", NcState(Mode.AMBIENT, voice = true, level = 20))
        assertTrue(talk.matches(NcState(Mode.AMBIENT, voice = true, level = 20), null))
        assertFalse(talk.matches(NcState(Mode.AMBIENT, voice = false, level = 20), null))
        assertFalse(talk.matches(NcState(Mode.AMBIENT, voice = true, level = 12), null))
        val focus = Scene("f", "Focus", NcState(Mode.ANC, level = 3))
        assertTrue(focus.matches(NcState(Mode.ANC, voice = true, level = 15), null))
        val bass = focus.copy(eqPreset = 0x16)
        assertFalse(bass.matches(NcState(Mode.ANC), null))
        assertTrue(bass.matches(NcState(Mode.ANC), EqualizerProtocol.State(0x16, emptyList())))
    }

    @Test fun nonAmbientScenesKeepTheUsersAmbientSetup() {
        val current = NcState(Mode.AMBIENT, voice = true, level = 7)
        assertEquals(NcState(Mode.ANC, voice = true, level = 7), Scene("f", "Focus", NcState(Mode.ANC)).target(current))
        val talk = NcState(Mode.AMBIENT, voice = false, level = 20)
        assertEquals(talk, Scene("t", "Talk", talk).target(current))
    }

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
}
