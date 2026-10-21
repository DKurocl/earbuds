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
}
