package ua.olex.sonyanc

import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * On a real framework, as the launcher does it (updates re-applied to the shown views):
 * an identical update animates nothing, a mode change morphs the buttons.
 */
class WidgetAnimationUiTest {
    private val ids = listOf(R.id.w_anc, R.id.w_amb, R.id.w_off)

    /**
     * Counts transitions that really start. A vector transition starts on the RenderThread with
     * the first frame drawn after the change, which on a slow emulator can take a while, so the
     * test listens for the start instead of polling isRunning at a guessed moment.
     */
    private class Starts {
        // Distinct drawables: the platform may report one transition's start more than once.
        val started: MutableSet<Drawable> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
        val callback = object : Animatable2.AnimationCallback() {
            override fun onAnimationStart(drawable: Drawable) { started += drawable }
        }
        fun watch(root: View) = segments(root).forEach { (it.current as? AnimatedVectorDrawable)?.registerAnimationCallback(callback) }
        private fun segments(root: View) = listOf(R.id.w_anc, R.id.w_amb, R.id.w_off).flatMap { id ->
            val row = root.findViewById<ViewGroup>(id).getChildAt(0) as ViewGroup
            (0 until row.childCount).map { (row.getChildAt(it) as ImageView).drawable }
        }
        fun await(expected: Int, ms: Long): Int {
            val end = System.currentTimeMillis() + ms
            while (started.size < expected && System.currentTimeMillis() < end) Thread.sleep(20)
            Thread.sleep(300) // and nothing beyond what was expected
            return started.size
        }
    }

    @Test fun onlyARealModeChangeAnimates() {
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

            // The same update again (the launcher re-applies every refresh): nothing starts.
            val same = Starts()
            scenario.onActivity { views(it, SonyProtocol.Mode.ANC).reapply(it, root); same.watch(root) }
            assertEquals("an identical update replays nothing", 0, same.await(1, 1500))

            // Tapping Ambient: ANC's three segments morph back, Ambient's three into a pill.
            val change = Starts()
            scenario.onActivity {
                views(it, SonyProtocol.Mode.ANC, pending = SonyProtocol.Mode.AMBIENT).reapply(it, root)
                assertEquals(listOf(true, false, true), ids.map { id -> root.findViewById<View>(id).isEnabled })
                change.watch(root)
            }
            assertEquals("the change morphs both buttons", 6, change.await(6, 5000))
            Thread.sleep(1000)

            // The earbuds confirm Ambient: the same state again, nothing replays.
            val confirm = Starts()
            scenario.onActivity { views(it, SonyProtocol.Mode.AMBIENT).reapply(it, root); confirm.watch(root) }
            assertEquals("the confirmation replays nothing", 0, confirm.await(1, 1500))
        }
    }
}
