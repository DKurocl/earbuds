package ua.olex.sonyanc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    // Khmelnytskyi city centre and points around it.
    private val lat = 49.4229; private val lon = 26.9871

    @Test fun distanceIsMetres() {
        // 0.001° of latitude is about 111 m.
        assertEquals(111.0, Places.distanceM(lat, lon, lat + 0.001, lon), 1.0)
    }

    @Test fun nearbyFixIsHome() = assertTrue(Places.isHome(lat, lon, lat + 0.001, lon, 20f))

    @Test fun acrossTownIsNotHome() = assertFalse(Places.isHome(lat, lon, lat + 0.02, lon, 20f))

    @Test fun aRoughFixCountsHalfItsUncertainty() {
        // 400 m away with a 400 m wide fix: could well be home.
        assertTrue(Places.isHome(lat, lon, lat + 0.0036, lon, 400f))
        assertFalse(Places.isHome(lat, lon, lat + 0.0036, lon, 20f))
    }
}
