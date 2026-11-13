package ua.olex.sonyanc

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The app's vocabulary of touch, one place for every pulse the earbuds' answers make.
 *
 * Each cue means one thing and feels like it: noise cancelling closes the world in (a deep,
 * soft press), Ambient opens it (a rise from deep to crisp), Off lets go (a short fade), a
 * setting accepted is one clean click, a refusal is two quick knocks. On the Pixel's actuator
 * (Android 16+ envelope effects) these are shaped in intensity and sharpness, the way Pixel's
 * haptic ringtones are; elsewhere they fall back to Android's primitives.
 *
 * Every cue has three takes; the owner picks one per cue in App settings › Vibrations, and
 * that choice is used everywhere (app, widget, tile, timer). Taps themselves use Android's own
 * feedback (HapticFeedbackType), which the system already tunes.
 *
 * Strength stays low (daily use, not alerts), follows Android's touch-feedback switch and,
 * in the app, the touch intensity setting (USAGE_TOUCH).
 */
object Haptics {
    enum class Cue(val title: String, val meaning: String) {
        ANC("Noise cancelling", "The world closes in"),
        AMBIENT("Ambient sound", "The world opens up"),
        OFF("Noise control off", "Letting go"),
        CONFIRM("Setting accepted", "One clean click"),
        REJECT("Not accepted", "Two quick knocks"),
    }

    /** One way a cue can feel: a shaped envelope on Android 16+, primitives otherwise. */
    class Take(
        val name: String,
        val envelope: (() -> VibrationEffect)? = null,
        val primitives: List<Triple<Int, Float, Int>> = emptyList(), // primitive, scale, delay ms
        val fallback: Int = VibrationEffect.EFFECT_CLICK,
    )

    /**
     * BasicEnvelope control points: intensity 0..1, sharpness 0..1 (deep..crisp), duration.
     * Only ever invoked from [effect] after its API 36 check (the takes hold it as a lambda).
     */
    @android.annotation.SuppressLint("NewApi")
    private fun envelope(initialSharpness: Float, vararg points: Triple<Float, Float, Long>): VibrationEffect =
        VibrationEffect.BasicEnvelopeBuilder().setInitialSharpness(initialSharpness).apply {
            points.forEach { (intensity, sharpness, ms) -> addControlPoint(intensity, sharpness, ms) }
        }.build()

    private fun p(primitive: Int, scale: Float, delay: Int = 0) = Triple(primitive, scale, delay)

    val takes: Map<Cue, List<Take>> = mapOf(
        Cue.ANC to listOf(
            Take("Deep press", envelope = { envelope(0.15f, Triple(0.65f, 0.15f, 30L), Triple(0f, 0.1f, 110L)) },
                primitives = listOf(p(Composition.PRIMITIVE_THUD, 0.6f)), fallback = VibrationEffect.EFFECT_HEAVY_CLICK),
            Take("Closing", envelope = { envelope(0.7f, Triple(0.45f, 0.6f, 20L), Triple(0.6f, 0.15f, 70L), Triple(0f, 0.1f, 90L)) },
                primitives = listOf(p(Composition.PRIMITIVE_QUICK_FALL, 0.5f), p(Composition.PRIMITIVE_THUD, 0.5f, 30))),
            Take("Thud", primitives = listOf(p(Composition.PRIMITIVE_THUD, 0.7f)), fallback = VibrationEffect.EFFECT_HEAVY_CLICK),
        ),
        Cue.AMBIENT to listOf(
            Take("Opening", envelope = { envelope(0.1f, Triple(0.35f, 0.2f, 40L), Triple(0.55f, 0.85f, 60L), Triple(0f, 0.9f, 40L)) },
                primitives = listOf(p(Composition.PRIMITIVE_QUICK_RISE, 0.55f))),
            Take("Two steps up", primitives = listOf(p(Composition.PRIMITIVE_TICK, 0.4f), p(Composition.PRIMITIVE_CLICK, 0.6f, 70))),
            Take("Quick rise", primitives = listOf(p(Composition.PRIMITIVE_QUICK_RISE, 0.6f))),
        ),
        Cue.OFF to listOf(
            Take("Fade", envelope = { envelope(0.6f, Triple(0.4f, 0.55f, 15L), Triple(0f, 0.3f, 90L)) },
                primitives = listOf(p(Composition.PRIMITIVE_QUICK_FALL, 0.4f)), fallback = VibrationEffect.EFFECT_TICK),
            Take("Quick fall", primitives = listOf(p(Composition.PRIMITIVE_QUICK_FALL, 0.5f)), fallback = VibrationEffect.EFFECT_TICK),
            Take("Tick", primitives = listOf(p(Composition.PRIMITIVE_TICK, 0.8f)), fallback = VibrationEffect.EFFECT_TICK),
        ),
        Cue.CONFIRM to listOf(
            Take("Click", primitives = listOf(p(Composition.PRIMITIVE_CLICK, 0.5f))),
            Take("Crisp", envelope = { envelope(0.9f, Triple(0.5f, 0.9f, 12L), Triple(0f, 0.9f, 25L)) },
                primitives = listOf(p(Composition.PRIMITIVE_TICK, 0.7f))),
            Take("Low tick", primitives = listOf(p(Composition.PRIMITIVE_LOW_TICK, 0.8f)), fallback = VibrationEffect.EFFECT_TICK),
        ),
        Cue.REJECT to listOf(
            Take("Two knocks", primitives = listOf(p(Composition.PRIMITIVE_CLICK, 0.8f), p(Composition.PRIMITIVE_CLICK, 0.8f, 90)),
                fallback = VibrationEffect.EFFECT_DOUBLE_CLICK),
            Take("Two low knocks", primitives = listOf(p(Composition.PRIMITIVE_LOW_TICK, 1f), p(Composition.PRIMITIVE_LOW_TICK, 1f, 110)),
                fallback = VibrationEffect.EFFECT_DOUBLE_CLICK),
            Take("Buzz", envelope = { envelope(0.4f, Triple(0.6f, 0.4f, 20L), Triple(0.6f, 0.4f, 60L), Triple(0f, 0.4f, 20L)) },
                primitives = listOf(p(Composition.PRIMITIVE_CLICK, 0.8f), p(Composition.PRIMITIVE_CLICK, 0.8f, 90)),
                fallback = VibrationEffect.EFFECT_DOUBLE_CLICK),
        ),
    )

    /**
     * Android's "Touch feedback" switch (Settings > Sound & vibration > Vibration & haptics). The
     * cues are custom effects, which the system does not silence on its own outside the app, so
     * they follow the switch here, as the platform's own feedback does.
     */
    private fun touchFeedbackOn(ctx: Context) = runCatching {
        android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    }.getOrDefault(true)

    private fun vibrator(ctx: Context) = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        ?.takeIf { it.hasVibrator() && touchFeedbackOn(ctx) }

    /** True when this phone can play the shaped takes (the Pixel 10 can). */
    fun shaped(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 36 &&
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator?.areEnvelopeEffectsSupported() == true

    // The primitive ids come from the takes above, all Composition.PRIMITIVE_* constants.
    @android.annotation.SuppressLint("WrongConstant")
    private fun effect(v: Vibrator, take: Take): VibrationEffect {
        if (Build.VERSION.SDK_INT >= 36 && take.envelope != null && v.areEnvelopeEffectsSupported()) return take.envelope.invoke()
        val ids = take.primitives.map { it.first }.toIntArray()
        if (take.primitives.isNotEmpty() && v.areAllPrimitivesSupported(*ids)) {
            return VibrationEffect.startComposition().apply {
                take.primitives.forEach { (id, scale, delay) -> addPrimitive(id, scale, delay) }
            }.compose()
        }
        return VibrationEffect.createPredefined(take.fallback)
    }

    /** The owner's take for [cue] (App settings › Vibrations). */
    fun takeFor(ctx: Context, cue: Cue): Take = takes.getValue(cue).let { it.getOrElse(Prefs.hapticTake(ctx, cue.name)) { _ -> it[0] } }

    /** Plays [cue]; [take] overrides the owner's choice (the Vibrations page). */
    fun play(ctx: Context, cue: Cue, take: Take = takeFor(ctx, cue)) {
        val v = vibrator(ctx) ?: return
        runCatching {
            // In the app: touch feedback, scaled by Android's touch intensity setting. From the widget,
            // tile or timer the app is in the background, where Android plays only default usage.
            val attributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
            if (inForeground()) v.vibrate(effect(v, take), attributes) else v.vibrate(effect(v, take))
        }
    }

    private fun inForeground() = runCatching {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    /** The earbuds accepted the change: the mode's own cue; null = same mode, settings only. */
    fun confirm(ctx: Context, mode: SonyProtocol.Mode?) = play(ctx, when (mode) {
        SonyProtocol.Mode.ANC -> Cue.ANC
        SonyProtocol.Mode.AMBIENT -> Cue.AMBIENT
        SonyProtocol.Mode.OFF -> Cue.OFF
        null -> Cue.CONFIRM
    })

    /** The command was not delivered. */
    fun reject(ctx: Context) = play(ctx, Cue.REJECT)
}
