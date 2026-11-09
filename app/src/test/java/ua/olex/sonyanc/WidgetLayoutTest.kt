package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test
import ua.olex.sonyanc.WidgetLayout.ModeVariant.*

/** Sizes are Pixel 10 Pro XL launcher slots (≈ 84 × 104 dp per cell on the 5-column grid). */
class WidgetLayoutTest {
    // "Ambient" at 14 sp in Google Sans Text Medium, and one line, measured at font scale 1.0 and 1.3.
    private val label = 54f
    private val line = 18f

    private fun modes(w: Int, h: Int, scale: Float = 1f) = WidgetLayout.modes(w.toFloat(), h.toFloat(), label * scale, line * scale)

    @Test fun oneRowSlotsKeepLabelsAndFitTheirHeight() {
        for (w in listOf(252, 340, 424)) {
            val s = modes(w, 96)
            assertTrue("$w: labels", s.labels)
            assertTrue("$w: fits", s.group <= 96 - 2 * WidgetLayout.PAD)
            assertNotEquals(COLUMN, s.variant)
        }
    }

    @Test fun wideShortSlotsPutLabelsBesideIcons() {
        assertEquals(INLINE, modes(340, 56).variant)
        assertEquals(INLINE, modes(424, 64).variant)
    }

    @Test fun buttonsNeverGrowIntoCirclesOrDShapes() {
        for (w in listOf(150, 164, 252, 340, 424)) for (h in listOf(96, 150, 208)) {
            val s = modes(w, h)
            if (s.variant == COLUMN) assertTrue((s.group - 2 * WidgetLayout.GAP) / 3 <= 64f)
            else assertTrue("$w×$h", s.group <= 80f)
        }
    }

    @Test fun narrowSlotsDropLabelsInsteadOfClipping() {
        val s = modes(150, 96)
        assertFalse(s.labels)
        assertEquals(ROW, s.variant)
        // A 24 dp icon still has room inside each tile.
        assertTrue((150 - 2 * WidgetLayout.PAD - 2 * WidgetLayout.GAP) / 3 >= 24f + 16f)
    }

    @Test fun tallSlotsStackTheButtons() {
        val s = modes(164, 208)
        assertEquals(COLUMN, s.variant)
        assertTrue(s.labels)
        assertTrue(s.group <= 208 - 2 * WidgetLayout.PAD)
    }

    @Test fun wideTallSlotsStayARowButDoNotBecomeAWall() {
        val s = modes(340, 208)
        assertNotEquals(COLUMN, s.variant)
        assertTrue(s.group <= 80f)
    }

    @Test fun largeTextNeverOverflowsALabel() {
        for (w in listOf(164, 200, 252, 340, 424)) for (h in listOf(56, 96, 208)) {
            val s = modes(w, h, scale = 1.3f)
            if (!s.labels) continue
            val inner = w - 2 * WidgetLayout.PAD
            val room = if (s.variant == COLUMN) inner else (inner - 2 * WidgetLayout.GAP) / 3
            val needs = 16f + label * 1.3f + if (s.variant == ROW) 0f else 24f + 8f
            assertTrue("$w×$h ${s.variant}: $room < $needs", room >= needs)
        }
    }

    @Test fun batteryTwoAndThreeCellsShowThreeRingsWithLabels() {
        for (w in listOf(164, 252, 340)) {
            val s = WidgetLayout.battery(w.toFloat(), 104f, 18f, 15f)
            assertFalse(s.compact)
            assertTrue(s.ring >= 40f)
            assertTrue(s.ring <= (w - 2 * WidgetLayout.PAD) / 3f - 8f)
        }
    }

    @Test fun batteryContentNeverExceedsTheSlot() {
        for (w in listOf(84, 164, 252, 340)) for (h in listOf(80, 104, 150, 208)) {
            val s = WidgetLayout.battery(w.toFloat(), h.toFloat(), 18f, 15f)
            val used = s.ring + 6 + 18 + (if (s.labels) 2 + 15 else 0) + (if (s.status) 8 + 15 else 0)
            assertTrue("$w×$h uses $used", used <= h - 2 * WidgetLayout.PAD + 0.01f)
        }
    }

    @Test fun oneCellBatteryIsCompactAndKeepsTheCaseLine() {
        val s = WidgetLayout.battery(84f, 104f, 18f, 15f)
        assertTrue(s.compact)
        assertTrue(s.status)
    }

    @Test fun lockScreenHubSlotGetsTheFullDashboard() {
        val s = WidgetLayout.dashboard(416f, 275f, 20f, 18f, 15f, label)
        assertNotNull(s); s!!
        assertTrue(s.estimate); assertTrue(s.labels)
    }

    @Test fun homeFourByTwoGetsADashboardWithoutClippedLabels() {
        val s = WidgetLayout.dashboard(340f, 208f, 20f, 18f, 15f, label)!!
        val tile = (340 - 32 - 2 * WidgetLayout.GAP) / 3
        if (s.labels) assertTrue(tile >= 16 + 24 + 8 + label)
    }

    @Test fun oneRowSlotsNeverBecomeADashboard() {
        for (w in listOf(252, 340, 424)) assertNull(WidgetLayout.dashboard(w.toFloat(), 96f, 20f, 18f, 15f, label))
        assertNull(WidgetLayout.dashboard(164f, 208f, 20f, 18f, 15f, label))
    }
}
