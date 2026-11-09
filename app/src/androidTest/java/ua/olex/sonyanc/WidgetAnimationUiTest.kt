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
            lateinit var root: View
            fun views(current: SonyProtocol.Mode, pending: SonyProtocol.Mode? = null, ctx: android.content.Context) =
                ModeWidget.viewsFor(ctx, 340f, 96f, current, pending = pending, interactive = false, linked = true)
            // Each step runs on the UI thread, then the test waits: vector animations start on
            // the RenderThread with the next drawn frame, as they do in the launcher.
            fun step(wait: Long = 120, action: (ComponentActivity) -> Unit) { scenario.onActivity(action); Thread.sleep(wait) }
            step(wait = 800) { activity ->
                val host = FrameLayout(activity)
                activity.setContentView(host)
                root = views(SonyProtocol.Mode.ANC, ctx = activity).apply(activity, host)
                host.addView(root)
            }
            scenario.onActivity { assertFalse("first appearance is still", animating(root)) }
            step { views(SonyProtocol.Mode.ANC, ctx = it).reapply(it, root) }
            scenario.onActivity { assertFalse("an identical update replays nothing", animating(root)) }
            step(wait = 60) { views(SonyProtocol.Mode.ANC, pending = SonyProtocol.Mode.AMBIENT, ctx = it).reapply(it, root) }
            scenario.onActivity {
                assertEquals(listOf(true, false, true), ids.map { id -> root.findViewById<View>(id).isEnabled })
                assertTrue("the change morphs the buttons", animating(root))
            }
            Thread.sleep(800)
            step { views(SonyProtocol.Mode.AMBIENT, ctx = it).reapply(it, root) }
            scenario.onActivity { assertFalse("the confirmation replays nothing", animating(root)) }
        }
    }
}
