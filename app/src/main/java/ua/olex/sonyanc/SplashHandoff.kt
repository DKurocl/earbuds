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
 * From the splash into the app, calmly, the same in every app of the family (VPN's
 * docs/FAMILY-STYLE.md): Android shows the launcher icon on its white circle; then the icon settles
 * and the whole splash fades on the RenderThread while Home fades up in place. Nothing flies.
 */
object SplashHandoff {

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

    private fun handOver(splash: SplashScreenView) {
        Intro.start()
        // Home's rings are already where they belong: no piece flies in.
        listOf("Left", "Right", "Case").forEach(Intro::land)
        // The splash's own fades run as ViewPropertyAnimators: on hardware views Android plays
        // them on the RenderThread, so they stay at the screen's full rate while the main thread
        // builds Home. The whole splash fades, plate included: Android draws the icon's white
        // plate apart from the icon view, so fading the icon alone left the plate over Home.
        val decelerate = android.view.animation.PathInterpolator(0f, 0f, 0f, 1f)
        splash.iconView?.animate()?.scaleX(0.92f)?.scaleY(0.92f)?.alpha(0f)
            ?.setDuration(130)?.setInterpolator(decelerate)?.start()
        splash.animate().alpha(0f).setDuration(160).setInterpolator(decelerate)
            .withEndAction { splash.remove() }.start()
        // Home fades up under it, from a third of the way (docs: the family style, as in VPN).
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 110
            duration = 270
            interpolator = decelerate
            addUpdateListener { Intro.reveal(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { Intro.finish() } })
            start()
        }
    }
}
