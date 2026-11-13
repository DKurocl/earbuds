package ua.olex.sonyanc

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * The page's side of the opening (see [SplashHandoff]). On a fresh start the page waits under the
 * splash; as the launcher icon's circle comes down onto Home's case ring, the earbuds' rings come
 * in, the case ring takes over from the icon, each ring filling as it does, and the rest of the
 * page fades up. Off (the default) everywhere else, renders included.
 */
object Intro {
    enum class Phase { OFF, WAITING, PLAYING }

    var phase by mutableStateOf(Phase.OFF)
        private set
    /** Where each ring's picture sits ("Left", "Case", "Right"), in root coordinates. */
    val targets = mutableStateMapOf<String, Rect>()
    private val landed = mutableStateMapOf<String, Boolean>()
    /** The page's own opacity while the opening plays. */
    var content by mutableFloatStateOf(1f)
        private set

    fun isLanded(label: String) = phase == Phase.OFF || landed[label] == true

    /** A fresh start: the page waits under the splash. Never waits more than 2 s. */
    fun prepare() {
        phase = Phase.WAITING; content = 0f; landed.clear()
        Handler(Looper.getMainLooper()).postDelayed({ if (phase == Phase.WAITING) finish() }, 2000)
    }

    fun start() { if (phase == Phase.WAITING) phase = Phase.PLAYING }

    fun finish() {
        listOf("Left", "Case", "Right").forEach { landed[it] = true }
        content = 1f
        phase = Phase.OFF
    }

    internal fun land(label: String) { landed[label] = true }
    internal fun reveal(alpha: Float) { content = alpha }
}
