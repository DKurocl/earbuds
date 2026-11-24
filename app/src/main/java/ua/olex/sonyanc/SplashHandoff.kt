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
 * From the splash into the app, calmly, as Google's own apps open: Android shows the launcher
 * icon on its white circle; then the icon settles back a little and fades while the splash
 * dissolves and Home fades up in place. Nothing travels across the screen.
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
        // Home's rings are already where they belong: no piece flies in.
        listOf("Left", "Right", "Case").forEach(Intro::land)
        val icon = splash.iconView
        val background = (splash.background as? android.graphics.drawable.ColorDrawable)?.color ?: Color.TRANSPARENT
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val e = emphasized.getInterpolation(t)
                icon?.apply {
                    val k = 1f - 0.08f * e
                    scaleX = k; scaleY = k
                    alpha = 1f - decel(span(t, 0f, 0.6f))
                }
                splash.setBackgroundColor(ColorUtils.setAlphaComponent(background, ((1 - decel(span(t, 0.15f, 1f))) * Color.alpha(background)).toInt()))
                Intro.reveal(decel(span(t, 0.2f, 1f)))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { Intro.finish(); splash.remove() }
            })
            start()
        }
    }
}
