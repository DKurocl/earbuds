package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test

class OnConnectTest {
    @Test fun defaultsToLeavingTheEarbudsAlone() {
        assertEquals(OnConnect.KEEP, OnConnect.from(null))
        assertEquals(OnConnect.KEEP, OnConnect.from("garbage"))
        assertNull(OnConnect.KEEP.mode)
    }

    @Test fun eachChoiceMapsToItsMode() {
        assertEquals(SonyProtocol.Mode.ANC, OnConnect.from("ANC").mode)
        assertEquals(SonyProtocol.Mode.AMBIENT, OnConnect.from("AMBIENT").mode)
        assertEquals(SonyProtocol.Mode.OFF, OnConnect.from("OFF").mode)
    }

    @Test fun connectSyncFitsInsideTheBroadcastDeadline() {
        assertTrue(ConnectionReceiver.SETTLE_MS + ConnectionReceiver.SYNC_BUDGET_MS <= 10_000L)
    }
}
