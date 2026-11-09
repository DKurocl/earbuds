package ua.olex.sonyanc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The launcher re-applies every widget update to the views it already shows. This does the same:
 * an identical update must not animate anything; tapping Ambient morphs ANC's pill back into a
 * segment and Ambient's segment into a pill. Frames go to build/screenshots/widget-anim-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w448dp-h960dp-night-xxhdpi")
class WidgetAnimationTest {
    private val ids = listOf(R.id.w_anc, R.id.w_amb, R.id.w_off)
    private fun segments(root: View, id: Int): List<ImageView> {
        val row = root.findViewById<ViewGroup>(id).getChildAt(0) as ViewGroup
        return (0 until row.childCount).map { row.getChildAt(it) as ImageView }
    }
    /** Segments that switched to a transition, i.e. are animating a change. */
    private fun transitions(root: View) =
        ids.flatMap { segments(root, it) }.mapNotNull { it.drawable.current as? android.graphics.drawable.AnimatedVectorDrawable }

    /**
     * Robolectric can't run the RenderThread animator, so the frames would jump straight to the
     * end. Restart each transition on the UI-thread animator (as software rendering would) to
     * capture real in-between frames.
     */
    private fun onUiThread(avds: List<android.graphics.drawable.AnimatedVectorDrawable>) = avds.forEach {
        it.stop()
        android.graphics.drawable.AnimatedVectorDrawable::class.java.getDeclaredMethod("forceAnimationOnUI").apply { isAccessible = true }.invoke(it)
        it.start()
    }

    private fun stateAnimated(v: View): List<View> =
        (if (v.stateListAnimator != null) listOf(v) else emptyList()) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { stateAnimated(v.getChildAt(it)) } else emptyList()

    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            runCatching { return c.getDeclaredField(name).apply { isAccessible = true }.get(o) }
            c = c.superclass
        }
        error("no $name on ${o.javaClass}")
    }

    /**
     * Robolectric's clock finishes any animator on its first step, so the frames are taken by
     * seeking: the morph (each transition's AnimatorSet) and the content cross-fade (each view's
     * state list animator) are moved to the same moment, start delays included.
     */
    private fun seek(avds: List<android.graphics.drawable.AnimatedVectorDrawable>, views: List<View>, at: Long) {
        for (avd in avds) {
            val set = field(field(avd, "mAnimatorSet")!!, "mSet") as android.animation.AnimatorSet
            set.currentPlayTime = at.coerceAtMost(set.totalDuration)
            avd.invalidateSelf()
        }
        for (v in views) {
            val running = field(v.stateListAnimator, "mRunningAnimator") as? android.animation.ValueAnimator ?: continue
            running.currentPlayTime = (at - running.startDelay).coerceIn(0, running.duration)
        }
    }

    @Test fun onlyARealModeChangeAnimates() {
        assumeTrue(System.getProperty("screenshots") == "true")
        // Robolectric runs animators at duration scale 0 unless told otherwise; frames need real time.
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 1f)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val ctx: android.content.Context = activity
        val dir = File(System.getProperty("screenshotDir")!!).apply { mkdirs() }
        val d = ctx.resources.displayMetrics.density
        val w = (340 * d).toInt(); val h = (96 * d).toInt()
        val host = FrameLayout(ctx)
        val looper = shadowOf(Looper.getMainLooper())
        fun views(current: SonyProtocol.Mode, pending: SonyProtocol.Mode? = null) =
            ModeWidget.viewsFor(ctx, 340f, 96f, current, pending = pending, interactive = false, linked = true)
        val root = views(SonyProtocol.Mode.ANC).apply(ctx, host).also { host.addView(it, FrameLayout.LayoutParams(w, h)) }
        activity.setContentView(host)
        looper.idleFor(Duration.ofMillis(600))
        // Android skips state animations on views it thinks are hidden (View.isAggregatedVisible);
        // Robolectric never marks its window shown, a launcher does. Mark the tree visible.
        View::class.java.getDeclaredMethod("dispatchVisibilityAggregated", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(host, true)
        fun frame(name: String) {
            host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, w, h)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(b).apply { drawColor(Color.parseColor("#0B1530")) }.also { host.draw(it) }
            File(dir, "widget-anim-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertEquals(listOf(false, true, true), ids.map { root.findViewById<View>(it).isEnabled })
        assertTrue("first appearance is still", transitions(root).none { it.isRunning })
        frame("0-anc")

        // The same update again (battery arrived, a refresh): nothing moves.
        val before = transitions(root)
        views(SonyProtocol.Mode.ANC).reapply(ctx, root)
        assertEquals("an identical update starts no transition", before, transitions(root))

        // Tapping Ambient: the optimistic update.
        views(SonyProtocol.Mode.ANC, pending = SonyProtocol.Mode.AMBIENT).reapply(ctx, root)
        assertEquals(listOf(true, false, true), ids.map { root.findViewById<View>(it).isEnabled })
        val changed = transitions(root) - before.toSet()
        // ANC's three segments morph back, Ambient's three morph into a pill; Off stays.
        assertEquals("the change animates both buttons", 6, changed.size)
        onUiThread(changed)
        // Seek every running animator to each moment and draw it (see [seek]).
        val content = listOf(R.id.w_anc, R.id.w_amb).flatMap { stateAnimated(root.findViewById(it)) }
        for (at in listOf(40L, 90L, 150L, 220L, 400L)) {
            seek(changed, content, at)
            frame("1-%03d".format(at))
        }
        looper.idleFor(Duration.ofMillis(600))
        // The earbuds confirm: the same state arrives again and nothing replays.
        val settled = transitions(root)
        views(SonyProtocol.Mode.AMBIENT).reapply(ctx, root)
        assertEquals("confirmation of the same mode starts no transition", settled, transitions(root))
        frame("2-ambient")
    }
}
