package ua.olex.sonyanc

import android.graphics.drawable.Animatable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On a real framework, as the launcher does it (updates re-applied to the shown views):
 * an identical update animates nothing, a mode change morphs the buttons.
 */
class WidgetAnimationUiTest {
    private val ids = listOf(R.id.w_anc, R.id.w_amb, R.id.w_off)
    private fun segments(root: View) = ids.flatMap { id ->
        val row = root.findViewById<ViewGroup>(id).getChildAt(0) as ViewGroup
        (0 until row.childCount).map { (row.getChildAt(it) as ImageView).drawable }
    }
    private fun animating(root: View) = segments(root).any { (it.current as? Animatable)?.isRunning == true }

    @Test fun onlyARealModeChangeAnimates() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun views(current: SonyProtocol.Mode, pending: SonyProtocol.Mode? = null) =
                    ModeWidget.viewsFor(activity, 340f, 96f, current, pending = pending, interactive = false, linked = true)
                val host = FrameLayout(activity)
                activity.setContentView(host)
                val root = views(SonyProtocol.Mode.ANC).apply(activity, host)
                host.addView(root)
                assertFalse("first appearance is still", animating(root))
                views(SonyProtocol.Mode.ANC).reapply(activity, root)
                assertFalse("an identical update replays nothing", animating(root))
                views(SonyProtocol.Mode.ANC, pending = SonyProtocol.Mode.AMBIENT).reapply(activity, root)
                assertEquals(listOf(true, false, true), ids.map { root.findViewById<View>(it).isEnabled })
                assertTrue("the change morphs the buttons", animating(root))
            }
        }
    }
}
