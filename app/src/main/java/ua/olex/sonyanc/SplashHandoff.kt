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
 * From the splash into the app: the splash shows the real case (drawable/splash_case.xml); as it
 * lifts, [Intro] picks the case up exactly where the splash drew it and brings it to life.
 */
object SplashHandoff {
    // drawable/splash_case.xml insets the photo by these fractions of the icon's square.
    private const val SIDE = 0.22f
    private const val TOP = 0.32f

    fun install(activity: Activity) {
        activity.splashScreen.setOnExitAnimationListener { splash -> lift(splash) }
    }

    private fun lift(splash: SplashScreenView) {
        val icon = splash.iconView
        val caseRect = icon?.let {
            val loc = IntArray(2).also { a -> it.getLocationInWindow(a) }
            val s = it.width.toFloat()
            Rect(loc[0] + s * SIDE, loc[1] + it.height * TOP, loc[0] + s * (1 - SIDE), loc[1] + it.height * (1 - TOP))
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
