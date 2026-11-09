package ua.olex.sonyanc

import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.VibratorManager

/**
 * Two-stage feedback tuned for the Pixel actuator: a light tick on touch (HushScreen), then a
 * mode-specific primitive once the earbuds acknowledge, or a double click on failure.
 * Predefined effects stand in when an actuator lacks a primitive.
 */
object Haptics {
    private fun vibrator(ctx: Context) = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }

    private fun play(ctx: Context, primitive: Int, scale: Float, fallback: Int) {
        val v = vibrator(ctx) ?: return
        runCatching {
            v.vibrate(
                if (v.areAllPrimitivesSupported(primitive)) VibrationEffect.startComposition().addPrimitive(primitive, scale).compose()
                else VibrationEffect.createPredefined(fallback),
            )
        }
    }

    /** The earbuds accepted the change. Each mode has its own character; null = same mode, settings only. */
    fun confirm(ctx: Context, mode: SonyProtocol.Mode?) = when (mode) {
        SonyProtocol.Mode.ANC -> play(ctx, Composition.PRIMITIVE_THUD, 0.7f, VibrationEffect.EFFECT_HEAVY_CLICK)
        SonyProtocol.Mode.AMBIENT -> play(ctx, Composition.PRIMITIVE_QUICK_RISE, 0.6f, VibrationEffect.EFFECT_CLICK)
        SonyProtocol.Mode.OFF -> play(ctx, Composition.PRIMITIVE_TICK, 0.8f, VibrationEffect.EFFECT_TICK)
        null -> play(ctx, Composition.PRIMITIVE_CLICK, 0.5f, VibrationEffect.EFFECT_CLICK)
    }

    /** The command was not delivered. */
    fun reject(ctx: Context) {
        val v = vibrator(ctx) ?: return
        runCatching {
            v.vibrate(
                if (v.areAllPrimitivesSupported(Composition.PRIMITIVE_CLICK)) VibrationEffect.startComposition()
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 0.8f)
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 0.8f, 90)
                    .compose()
                else VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK),
            )
        }
    }
}
