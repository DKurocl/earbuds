package ua.olex.sonyanc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.window.SplashScreenView
import androidx.compose.ui.geometry.Rect
import androidx.core.graphics.ColorUtils

/**
 * From the splash into the app: the splash shows the launcher's case (ic_launcher_foreground); as
 * it lifts, [Intro] picks the case up exactly where the splash drew it and brings it to life.
 */
object SplashHandoff {
    // Where the case sits in the icon's 108-unit square (ic_launcher_foreground): 30..78 x 37..71.
    private const val X0 = 30f / 108; private const val X1 = 78f / 108
    private const val Y0 = 37f / 108; private const val Y1 = 71f / 108

    fun install(activity: Activity) {
        activity.splashScreen.setOnExitAnimationListener { splash -> lift(splash) }
    }

    private fun lift(splash: SplashScreenView) {
        val icon = splash.iconView
        val caseRect = icon?.let {
            val loc = IntArray(2).also { a -> it.getLocationInWindow(a) }
            val w = it.width.toFloat(); val h = it.height.toFloat()
            Rect(loc[0] + w * X0, loc[1] + h * Y0, loc[0] + w * X1, loc[1] + h * Y1)
        }
        // The intro draws the same case in the same place underneath; the splash dissolves over it.
        Intro.start(caseRect)
        val background = (splash.background as? android.graphics.drawable.ColorDrawable)?.color ?: Color.TRANSPARENT
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                splash.setBackgroundColor(ColorUtils.setAlphaComponent(background, ((1 - t) * Color.alpha(background)).toInt()))
                icon?.alpha = 1 - t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = splash.remove()
            })
            start()
        }
    }
}
