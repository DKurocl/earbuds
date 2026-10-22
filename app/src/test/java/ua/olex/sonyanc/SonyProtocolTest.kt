package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test

class SonyProtocolTest {
    @Test fun recordedXm5Commands() {
        assertEquals("68 17 01 01 00 00 14", SonyProtocol.hex(SonyProtocol.ncSet(SonyProtocol.NcState(SonyProtocol.Mode.ANC))))
        assertEquals("68 17 01 01 01 00 14", SonyProtocol.hex(SonyProtocol.ncSet(SonyProtocol.NcState(SonyProtocol.Mode.AMBIENT))))
        assertEquals("68 17 01 00 00 00 14", SonyProtocol.hex(SonyProtocol.ncSet(SonyProtocol.NcState(SonyProtocol.Mode.OFF))))
        val reply = SonyProtocol.parseNc(SonyProtocol.parseHex("67 17 01 01 01 01 0C"))!!
        assertEquals(SonyProtocol.Mode.AMBIENT, reply.mode)
        assertTrue(reply.voice)
        assertEquals(12,reply.level)
    }
    @Test fun framingEscapesAllReservedBytes() {
        val payload=byteArrayOf(0x3C,0x3D,0x3E,0x00,0x7F,0xFF.toByte())
        val frame=SonyProtocol.frame(SonyProtocol.TYPE_COMMAND,0,payload)
        assertEquals(0x3E,frame.first().toInt())
        assertEquals(0x3C,frame.last().toInt())
        val parsed=SonyProtocol.parse(frame.copyOfRange(1,frame.size-1))!!
        assertTrue(parsed.checksumOk)
        assertArrayEquals(payload,parsed.payload)
    }
    @Test fun hostileLengthsAndBrokenEscapesAreRejected() {
        listOf("0C 00 7F FF FF FF 00", "0C 00 FF FF FF FF 00", "0C 00 00 00 00 00 0C 00", "0C 00 00 00 00 00 3D", "0C 00 00 00 00 00 3D 00").forEach {
            assertNull(it,SonyProtocol.parse(SonyProtocol.parseHex(it)))
        }
    }
    @Test fun invalidChecksumCannotBeConsideredValid() {
        assertFalse(SonyProtocol.parse(SonyProtocol.parseHex("0C 00 00 00 00 02 66 17 00"))!!.checksumOk)
    }
    @Test fun unrelatedOrOutOfRangeSettingsNeverBecomeNcState() {
        listOf("67 18 01 01 01 00 14","67 17 02 01 01 00 14","67 17 01 02 01 00 14","67 17 01 01 01 00 FF","67 17 01 01 01","67 17 01 01 01 00 14 00").forEach {
            assertNull(it,SonyProtocol.parseNc(SonyProtocol.parseHex(it)))
        }
    }
    @Test fun allByteValuesRoundTrip() {
        val payload=ByteArray(256){it.toByte()}
        val frame=SonyProtocol.frame(SonyProtocol.TYPE_COMMAND,1,payload)
        val parsed=SonyProtocol.parse(frame.copyOfRange(1,frame.size-1))!!
        assertTrue(parsed.checksumOk)
        assertArrayEquals(payload,parsed.payload)
    }
    @Test fun v2BatteryOnlyAcceptsKnownLayouts() {
        val b=SonyProtocol.parseBattery(SonyProtocol.parseHex("23 09 50 00 46 01"),SonyProtocol.Battery())!!
        assertEquals(80,b.left)
        assertEquals(70,b.right)
        assertTrue(b.rightCharging)
        val c=SonyProtocol.parseBattery(SonyProtocol.parseHex("25 0A 32 00"),b)!!
        assertEquals(50,c.case)
        assertEquals(80,c.left)
        assertNull(SonyProtocol.parseBattery(SonyProtocol.parseHex("23 02 50 00"),b))
        assertNull(SonyProtocol.parseBattery(SonyProtocol.parseHex("23 09 50 00"),b))
        assertEquals(80,SonyProtocol.parseBattery(SonyProtocol.parseHex("23 09 FF 00 00 00"),b)!!.left)
    }
    @Test fun equalizerUsesBoundedV2Bands() {
        val state=EqualizerProtocol.parse(SonyProtocol.parseHex("57 00 A0 06 14 0D 0C 0C 0D 0E"))!!
        assertEquals(listOf(10,3,2,2,3,4),state.bands)
        assertEquals("58 00 A0 06 14 0D 0C 0C 0D 0E",SonyProtocol.hex(EqualizerProtocol.custom(state.bands)))
        assertEquals("58 00 00 00",SonyProtocol.hex(EqualizerProtocol.preset(0)))
        assertNull(EqualizerProtocol.parse(SonyProtocol.parseHex("57 00 A0 06 FF 0A 0A 0A 0A 0A")))
        assertNull(EqualizerProtocol.parse(SonyProtocol.parseHex("57 00 A0 07 0A 0A 0A 0A 0A 0A")))
    }
    @Test fun dualBatteryFallbackAndNotificationKeepCase() {
        val previous=SonyProtocol.Battery(case=61)
        val b=SonyProtocol.parseBattery(SonyProtocol.parseHex("25 01 64 01 4F 00 00"),previous)!!
        assertEquals(100,b.left)
        assertEquals(79,b.right)
        assertEquals(61,b.case)
        assertTrue(b.leftCharging)
        assertFalse(b.rightCharging)
        val missing=SonyProtocol.parseBattery(SonyProtocol.parseHex("23 01 00 00 FF 00"),previous)!!
        assertNull(missing.left)
        assertNull(missing.right)
        assertNull(SonyProtocol.parseBattery(SonyProtocol.parseHex("23 01 50 04 50 00"),previous))
        assertEquals(listOf("22 01","22 09","22 0A"),SonyProtocol.BATTERY_GETS.map{SonyProtocol.hex(it)})
    }
    @Test fun featureBooleansUseTheirOwnSonyPolarity() {
        val dsee=DeviceSettingsProtocol.Feature.DSEE
        val pause=DeviceSettingsProtocol.Feature.AUTO_PAUSE
        assertEquals("E8 01 01",SonyProtocol.hex(dsee.set(true)))
        assertEquals("F8 01 00",SonyProtocol.hex(pause.set(true)))
        assertEquals(dsee to true,DeviceSettingsProtocol.parse(SonyProtocol.parseHex("E7 01 01")))
        assertNull(DeviceSettingsProtocol.parse(SonyProtocol.parseHex("27 01 00")))
        assertEquals("F6 01",SonyProtocol.hex(pause.get))
        assertEquals(pause to false,DeviceSettingsProtocol.parse(SonyProtocol.parseHex("F9 01 01")))
        listOf("E7 02 01","E7 01 FF","E7 01 01 00","27 01","26 01 00").forEach {
            assertNull(it,DeviceSettingsProtocol.parse(SonyProtocol.parseHex(it)))
        }
    }
}
