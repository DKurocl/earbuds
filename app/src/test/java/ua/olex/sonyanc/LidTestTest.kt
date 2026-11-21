package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test

class LidTestTest {
    @Test fun onlySonyEarbudsAnnouncementsAreDescribed() {
        assertNull(LidTest.describe("Pixel Watch", null, null))
        assertEquals("name \"WF-1000XM5\"", LidTest.describe("WF-1000XM5", null, null))
        assertEquals("Sony 01 02 · Fast Pair AA", LidTest.describe(null, byteArrayOf(1, 2), byteArrayOf(0xAA.toByte())))
    }
}
