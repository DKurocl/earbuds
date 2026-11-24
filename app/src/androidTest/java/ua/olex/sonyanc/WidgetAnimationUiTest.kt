package ua.olex.sonyanc

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On a real framework, as the launcher does it (updates re-applied to the shown views): an
 * identical update moves nothing, a mode change slides the pill to the new mode's third.
 */
class WidgetAnimationUiTest {
    @Test fun thePillSlidesOnlyOnARealModeChange() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var root: View
            fun views(ctx: android.content.Context, current: SonyProtocol.Mode, pending: SonyProtocol.Mode? = null) =
                ModeWidget.viewsFor(ctx, 340f, 96f, current, pending = pending, interactive = false, linked = true)
            scenario.onActivity { activity ->
                val host = FrameLayout(activity)
                activity.setContentView(host)
                root = views(activity, SonyProtocol.Mode.ANC).apply(activity, host)
                host.addView(root)
            }
            Thread.sleep(800)
            fun row() = root.findViewById<ViewGroup>(R.id.w_knob_row)
            fun pill() = root.findViewById<View>(R.id.w_knob_slot)
            var third = 0f
            scenario.onActivity { third = row().width / 3f; assertEquals("ANC: first third", 0f, pill().x, 2f) }

            // The same update again (the launcher re-applies every refresh): nothing starts.
            scenario.onActivity { views(it, SonyProtocol.Mode.ANC).reapply(it, root) }
            Thread.sleep(50)
            scenario.onActivity { assertFalse("an identical update moves nothing", row().layoutTransition.isRunning) }

            // Tapping Ambient: the pill slides to the middle third.
            val seen = mutableListOf<Float>()
            scenario.onActivity { views(it, SonyProtocol.Mode.ANC, pending = SonyProtocol.Mode.AMBIENT).reapply(it, root) }
            repeat(20) { Thread.sleep(25); scenario.onActivity { seen += pill().x } }
            Thread.sleep(800)
            scenario.onActivity { assertEquals("Ambient: middle third", third, pill().x, 2f) }
            assertTrue("the pill passes through in-between places: $seen", seen.any { it > 2f && it < third - 2f })

            // The earbuds confirm Ambient: the same state again, nothing replays.
            scenario.onActivity { views(it, SonyProtocol.Mode.AMBIENT).reapply(it, root) }
            Thread.sleep(50)
            scenario.onActivity { assertFalse("the confirmation moves nothing", row().layoutTransition.isRunning) }
        }
    }
}
