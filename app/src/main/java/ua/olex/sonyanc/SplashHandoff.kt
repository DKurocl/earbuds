package ua.olex.sonyanc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.window.SplashScreenView
import androidx.core.graphics.ColorUtils
import java.time.Duration
import java.time.Instant

/**
 * From the splash into the app, as Google's apps do it. Android shows the launcher icon in its
 * circle and plays its little animation (drawable/splash_icon.xml from docs/icon/brand_icon.py:
 * the slab settles, the case lands on it, its lid lifts once). Then that same circle comes down onto Home's case ring, shrinking to the ring's disc on
 * an emphasized curve, as the splash dissolves; the earbuds' rings come in, the case ring takes
 * over as the circle lands, and the page fades up ([Intro]). Without the ring on screen (setup, a
 * page) the splash simply dissolves.
 */
object SplashHandoff {
    private val emphasized = android.view.animation.PathInterpolator(androidx.core.graphics.PathParser.createPathFromPathData(
        "M 0,0 C 0.05,0 0.133333,0.06 0.166666,0.4 C 0.208333,0.82 0.25,1 1,1"))

    fun install(activity: Activity) {
        activity.splashScreen.setOnExitAnimationListener { splash ->
            if (!ValueAnimator.areAnimatorsEnabled()) {
                Intro.finish()
                splash.remove()
                return@setOnExitAnimationListener
            }
            // Let the icon finish its gesture before moving it into Home.
            val end = splash.iconAnimationStart?.plus(splash.iconAnimationDuration ?: Duration.ZERO)
            val wait = end?.let { (it.toEpochMilli() - Instant.now().toEpochMilli()).coerceIn(0, 800) } ?: 0
            splash.postDelayed({ handOver(splash) }, wait)
        }
    }

    private fun span(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun decel(x: Float): Float { val u = 1 - x; return 1 - u * u * u * u }

    private fun handOver(splash: SplashScreenView) {
        Intro.start()
        val icon = splash.iconView
        val ring = Intro.targets["Case"]
        val background = (splash.background as? android.graphics.drawable.ColorDrawable)?.color ?: Color.TRANSPARENT
        val loc = IntArray(2).also { icon?.getLocationInWindow(it) }
        val w = icon?.width?.toFloat() ?: 0f
        val h = icon?.height?.toFloat() ?: 0f
        // The icon's circle is two thirds of its view (an icon with a background); the ring's disc
        // is its picture's box over 0.70 (BatteryRing's ART).
        val scale = if (ring != null && w > 0) (ring.width / 0.70f) / (w * 2f / 3f) else 1f
        val dx = ring?.let { it.center.x - (loc[0] + w / 2) } ?: 0f
        val dy = ring?.let { it.center.y - (loc[1] + h / 2) } ?: 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (ring != null) 520 else 280
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val e = emphasized.getInterpolation(t)
                splash.setBackgroundColor(ColorUtils.setAlphaComponent(background, ((1 - decel(span(t, 0f, 0.5f))) * Color.alpha(background)).toInt()))
                icon?.apply {
                    if (ring != null) {
                        translationX = dx * e; translationY = dy * e
                        val k = 1 + (scale - 1) * e
                        scaleX = k; scaleY = k
                        // The ring takes over as the circle settles on it.
                        alpha = 1 - span(t, 0.72f, 1f)
                    } else alpha = 1 - t
                }
                Intro.reveal(decel(span(t, 0.25f, 1f)))
                if (t > 0.2f) { Intro.land("Left"); Intro.land("Right") }
                if (t > 0.75f) Intro.land("Case")
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { Intro.finish(); splash.remove() }
            })
            start()
        }
    }
}
