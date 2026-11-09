package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test

class CodecProtocolTest {
    @Test fun decodesReadAndLiveNotificationAsReportedCodec() {
        assertEquals("12 02",SonyProtocol.hex(CodecProtocol.get))
        listOf(0x01 to "SBC",0x02 to "AAC",0x10 to "LDAC",0x20 to "aptX",0x21 to "aptX HD").forEach {(code,name)->
            listOf(0x13,0x15).forEach {type->
                val parsed=CodecProtocol.parse(byteArrayOf(type.toByte(),0x02,code.toByte()))!!
                assertEquals(name,parsed.label)
                assertTrue(parsed.isKnown)
            }
        }
    }
    @Test fun unknownCodeNeverBecomesAnAssumedLdacOrLc3() {
        val missing=CodecProtocol.parse(SonyProtocol.parseHex("13 02 00"))!!
        assertEquals("Not reported",missing.label)
        assertFalse(missing.isKnown)
        val future=CodecProtocol.parse(SonyProtocol.parseHex("15 02 FE"))!!
        assertEquals("Unknown (0xFE)",future.label)
        assertFalse(future.isKnown)
    }
    @Test fun unrelatedAndTruncatedRepliesCannotBecomeCodec() {
        listOf("12 02 10","13 00 10","13 02","13 02 10 00","23 02 10","").forEach {
            assertNull(it,CodecProtocol.parse(SonyProtocol.parseHex(it)))
        }
    }
}
