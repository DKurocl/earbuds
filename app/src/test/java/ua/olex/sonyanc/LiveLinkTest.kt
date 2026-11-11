package ua.olex.sonyanc

import android.companion.DevicePresenceEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveLinkTest {
    @Test fun onlyClassicConnectAndDisconnectChangeTheLink() {
        assertEquals(true, LiveLink.presenceChange(DevicePresenceEvent.EVENT_BT_CONNECTED))
        assertEquals(false, LiveLink.presenceChange(DevicePresenceEvent.EVENT_BT_DISCONNECTED))
        assertEquals(false, LiveLink.presenceChange(DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED))
        // BLE sightings say nothing about the control channel.
        assertEquals(null, LiveLink.presenceChange(DevicePresenceEvent.EVENT_BLE_APPEARED))
        assertEquals(null, LiveLink.presenceChange(DevicePresenceEvent.EVENT_BLE_DISAPPEARED))
    }
}
