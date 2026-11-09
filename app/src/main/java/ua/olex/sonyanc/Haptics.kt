package ua.olex.sonyanc

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Two-stage feedback tuned for the Pixel actuator: a light tick on touch, then a
 * mode-specific primitive once the earbuds acknowledge, or a reject pattern on failure.
 * Devices without composition support fall back to predefined effects.
 */
object Haptics {
    private fun vibrator(ctx: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    private fun play(ctx: Context, primitive: Int?, scale: Float, fallback: Int) {
        val v = vibrator(ctx)?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            val effect = if (primitive != null && Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(primitive))
                VibrationEffect.startComposition().addPrimitive(primitive, scale).compose()
            else if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(fallback)
            else VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
            v.vibrate(effect)
        }
    }

    /** Immediate response to a touch, before anything is sent. */
    fun tap(ctx: Context) = play(ctx, if (Build.VERSION.SDK_INT >= 31) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else null, 0.6f, VibrationEffect.EFFECT_TICK)

    /** The earbuds accepted the change. Each mode has its own character. */
    fun confirm(ctx: Context, mode: SonyProtocol.Mode?) {
        if (Build.VERSION.SDK_INT < 31) return play(ctx, null, 1f, VibrationEffect.EFFECT_CLICK)
        when (mode) {
            SonyProtocol.Mode.ANC -> play(ctx, VibrationEffect.Composition.PRIMITIVE_THUD, 0.7f, VibrationEffect.EFFECT_HEAVY_CLICK)
            SonyProtocol.Mode.AMBIENT -> play(ctx, VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.6f, VibrationEffect.EFFECT_CLICK)
            SonyProtocol.Mode.OFF -> play(ctx, VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, VibrationEffect.EFFECT_TICK)
            null -> play(ctx, VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, VibrationEffect.EFFECT_CLICK)
        }
    }

    /** The command was not delivered. */
    fun reject(ctx: Context) {
        val v = vibrator(ctx)?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            val effect = if (Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK))
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, 90)
                    .compose()
            else if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            else VibrationEffect.createWaveform(longArrayOf(0, 20, 90, 20), -1)
            v.vibrate(effect)
        }
    }
}
