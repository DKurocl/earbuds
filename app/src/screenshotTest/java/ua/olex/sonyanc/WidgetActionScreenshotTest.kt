package ua.olex.sonyanc

import android.content.Intent
import android.widget.FrameLayout
import android.widget.RemoteViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ua.olex.sonyanc.SonyProtocol.Mode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetActionScreenshotTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun playerSlotEdgesOpenTheAppAndTheModeSegmentCarriesItsOwnChoice() {
        val views = ModeWidget.viewsFor(app, 322f, 105f, Mode.ANC, linked = true, showDashboard = false)
        for (edge in listOf(R.id.w_open_left, R.id.w_open_right)) {
            val intent = launchFrom(views, edge)
            assertFalse(intent.hasExtra("hush.mode"))
        }
        assertEquals(Mode.AMBIENT.name, launchFrom(views, R.id.w_amb).getStringExtra("hush.mode"))
    }

    @Test fun batteryAndStatusWidgetsOpenTheAppWithoutAModeChange() {
        val battery = BatteryWidget.viewsFor(app, 252f, 104f, BatteryWidget.SAMPLE, 0L, true, 0L)
        val status = StatusWidget.viewsFor(app, Mode.ANC, BatteryWidget.SAMPLE to 0L, true)
        for (views in listOf(battery, status)) {
            assertFalse(launchFrom(views, android.R.id.background).hasExtra("hush.mode"))
        }
    }

    @Test fun tileUsesTheWidgetCommandInBothCycleStyles() {
        assertEquals(Mode.ANC, NoiseTileService.nextMode(null, twoWay = false))
        assertEquals(Mode.AMBIENT, NoiseTileService.nextMode(Mode.ANC, twoWay = false))
        assertEquals(Mode.ANC, NoiseTileService.nextMode(Mode.AMBIENT, twoWay = true))
        assertEquals(Mode.ANC, NoiseTileService.nextMode(Mode.OFF, twoWay = false))
        val off = ModeWidget.modeIntent(app, NoiseTileService.nextMode(Mode.AMBIENT, twoWay = false))
        assertEquals(ModeWidget::class.java.name, off.component?.className)
        assertEquals("ua.olex.sonyanc.SET_MODE", off.action)
        assertEquals(Mode.OFF.name, off.getStringExtra("mode"))
    }

    @Test fun aDeliveredModeShortcutIsRemovedFromTheLaunchIntent() {
        val launch = Intent(app, MainActivity::class.java).putExtra("hush.mode", Mode.OFF.name)
        Robolectric.buildActivity(MainActivity::class.java, launch).use { activity ->
            activity.create()
            assertFalse(activity.get().intent.hasExtra("hush.mode"))
        }
    }

    private fun launchFrom(views: RemoteViews, id: Int): Intent {
        val root = views.apply(app, FrameLayout(app))
        assertTrue(root.findViewById<android.view.View>(id).performClick())
        val intent = checkNotNull(shadowOf(app).nextStartedActivity) { "Widget did not start an activity" }
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        return intent
    }
}
