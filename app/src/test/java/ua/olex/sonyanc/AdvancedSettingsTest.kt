package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test

class AdvancedSettingsTest {
    @Test fun recordedWfXm5FirmwareAndGuidance() {
        assertEquals("6.1.0",AdvancedSettings.firmware(SonyProtocol.parseHex("05 02 05 36 2E 31 2E 30")))
        val guidance=AdvancedSettings.parse(0x0E,SonyProtocol.parseHex("47 01 00 01"))!!
        assertEquals(AdvancedSettings.Key.GUIDANCE,guidance.first)
        assertEquals(listOf(0,1),guidance.second)
        assertNull(AdvancedSettings.parse(0x0C,SonyProtocol.parseHex("47 01 00 01")))
        assertNull(AdvancedSettings.firmware(SonyProtocol.parseHex("05 02 06 36 2E 31 2E 30")))
    }
    @Test fun partialBatteryDoesNotEraseOtherEarbud() {
        val old=SonyProtocol.Battery(left=87,right=91,case=63)
        val partial=SonyProtocol.parseBattery(SonyProtocol.parseHex("25 09 00 02 64 03 64 64"),old)!!
        assertEquals(87,partial.left)
        assertEquals(100,partial.right)
        assertEquals(63,partial.case)
        assertFalse(partial.rightCharging)
    }
    @Test fun settingsAreReadGatedAndPreserveUneditedFields() {
        val key=AdvancedSettings.Key.SIDETONE
        val value=AdvancedSettings.parse(0x0C,SonyProtocol.parseHex("F7 08 00 07 09"))!!.second
        assertEquals("F8 08 01 07 09",SonyProtocol.hex(key.set(value.toMutableList().apply{this[0]=1})))
        assertNull(AdvancedSettings.parse(0x0C,SonyProtocol.parseHex("FB 03 01 35 01 00 05")))
        // A touch function this app doesn't name is kept (shown as "Device setting"), a wrong shape is not.
        assertEquals(listOf(2,0x80,0x20),AdvancedSettings.parse(0x0C,SonyProtocol.parseHex("F7 03 02 80 20"))!!.second)
        assertNull(AdvancedSettings.parse(0x0C,SonyProtocol.parseHex("F7 03 01 00 20")))
        // And only named functions are written: the unknown side is preserved untouched.
        assertEquals(listOf(2,0x80,0x10),AdvancedSettings.patch(AdvancedSettings.Key.TOUCH,listOf(2,0x80,0x20),2,0x10))
        assertNull(AdvancedSettings.patch(AdvancedSettings.Key.TOUCH,listOf(2,0x80,0x20),2,0x80))
        assertNull(AdvancedSettings.parse(0x0C,SonyProtocol.parseHex("F7 0C 02 01")))
    }
    @Test fun touchFunctionsSwapRatherThanRepeat() {
        val key=AdvancedSettings.Key.TOUCH
        // Left: noise control, right: playback (the XM5's default).
        val current=listOf(2,0x35,0x20)
        // Right to noise control: the left earbud takes playback instead.
        assertEquals(listOf(2,0x20,0x35),AdvancedSettings.patch(key,current,2,0))
        // Right to volume: nothing to swap.
        assertEquals(listOf(2,0x35,0x10),AdvancedSettings.patch(key,current,2,0x10))
        // Left to playback: the right earbud takes noise control.
        assertEquals(listOf(2,0x20,0x35),AdvancedSettings.patch(key,current,1,0x20))
        // Off can be on both.
        assertEquals(listOf(2,0xFF,0xFF),AdvancedSettings.patch(key,listOf(2,0xFF,0x20),2,0xFF))
    }

    @Test fun noiseControlTriesTheOtherCodeToo() {
        assertEquals(listOf(0,0x35), AdvancedSettings.touchCodes(listOf(2,0xFF,0x20),0))
        assertEquals(listOf(0x35,0), AdvancedSettings.touchCodes(listOf(2,0x35,0x20),0))
        assertEquals(listOf(0x10), AdvancedSettings.touchCodes(listOf(2,0xFF,0x20),0x10))
        assertEquals(listOf(2,0x35,0x20), AdvancedSettings.patch(AdvancedSettings.Key.TOUCH,listOf(2,0xFF,0x20),1,0,0x35))
        assertEquals(listOf(2,0,0x20), AdvancedSettings.patch(AdvancedSettings.Key.TOUCH,listOf(2,0xFF,0x20),1,0,0))
    }
}
