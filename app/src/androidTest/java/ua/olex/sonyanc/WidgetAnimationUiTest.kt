package ua.olex.sonyanc

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ViewFlipper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * On a real framework: a widget update re-applied to the shown views (as the launcher does)
 * animates the mode change instead of jumping.
 */
class WidgetAnimationUiTest {
    private fun flippers(v: View): List<ViewFlipper> = when (v) {
        is ViewFlipper -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { flippers(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun reappliedModeChangeStartsTheSelectionAnimation() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = FrameLayout(activity)
                activity.setContentView(host)
                val root = ModeWidget.viewsFor(activity, 340f, 96f, SonyProtocol.Mode.ANC, interactive = false, linked = true).apply(activity, host)
                host.addView(root)
                ModeWidget.viewsFor(activity, 340f, 96f, SonyProtocol.Mode.ANC, pending = SonyProtocol.Mode.AMBIENT, interactive = false, linked = true)
                    .reapply(activity, root)
                val f = flippers(root)
                assertEquals(listOf(0, 1, 0), f.map { it.displayedChild })
                assertNotNull("the chosen pill animates in", f[1].getChildAt(1).animation)
                assertNotNull("the old pill animates out", f[0].getChildAt(1).animation)
            }
        }
    }
}
