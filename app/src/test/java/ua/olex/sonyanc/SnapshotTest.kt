package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test

class SnapshotTest {
    @Test fun batteryRoundTripsIncludingUnknownPartsAndCharging() {
        val b = SonyProtocol.Battery(left = 80, right = null, case = 45, leftCharging = true, caseCharging = true)
        assertEquals(b, Snapshot.decodeBattery(Snapshot.encodeBattery(b)))
    }

    @Test fun anAllUnknownBatteryIsNotCached() {
        assertNull(Snapshot.decodeBattery(Snapshot.encodeBattery(SonyProtocol.Battery())))
    }

    @Test fun corruptOrOutOfRangeCacheIsIgnoredRatherThanInvented() {
        listOf(null, "", "80,70", "80,70,60,0,0", "0,70,60,0,0,0", "101,70,60,0,0,0",
            "80,70,60,2,0,0", "80,x,60,0,0,0", "80,70,60,0,0,0,1").forEach {
            assertNull(it, Snapshot.decodeBattery(it))
        }
    }

    @Test fun ageLabelsAreShortAndNeverNegative() {
        val now = 10_000_000_000L
        val min = 60_000L
        assertNull(Snapshot.ago(now, 0L))
        assertEquals("just now", Snapshot.ago(now, now + 5 * min)) // clock skew
        assertEquals("just now", Snapshot.ago(now, now - 30_000L))
        assertEquals("5 min ago", Snapshot.ago(now, now - 5 * min))
        assertEquals("3 h ago", Snapshot.ago(now, now - 200 * min))
        assertEquals("yesterday", Snapshot.ago(now, now - 30 * 60 * min))
        assertEquals("4 days ago", Snapshot.ago(now, now - 4 * 24 * 60 * min))
    }

    @Test fun onConnectDefaultsToLeavingTheEarbudsAlone() {
        assertEquals(Snapshot.OnConnect.KEEP, Snapshot.OnConnect.from(null))
        assertEquals(Snapshot.OnConnect.KEEP, Snapshot.OnConnect.from("garbage"))
        assertNull(Snapshot.OnConnect.KEEP.mode)
        assertEquals(SonyProtocol.Mode.ANC, Snapshot.OnConnect.from("ANC").mode)
        assertEquals(SonyProtocol.Mode.AMBIENT, Snapshot.OnConnect.from("AMBIENT").mode)
        assertEquals(SonyProtocol.Mode.OFF, Snapshot.OnConnect.from("OFF").mode)
    }

    @Test fun connectSyncFitsInsideTheBroadcastDeadline() {
        // Background broadcasts must finish well before Android's receiver timeout.
        assertTrue(ConnectionReceiver.SETTLE_MS + ConnectionReceiver.SYNC_BUDGET_MS <= 10_000L)
    }
}
