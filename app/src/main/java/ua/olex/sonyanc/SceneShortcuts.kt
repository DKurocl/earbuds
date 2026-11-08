package ua.olex.sonyanc

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/** Long-press the app icon to apply a scene, next to the static ANC / Ambient / Off shortcuts. */
object SceneShortcuts {
    const val EXTRA = "hush.scene"
    private const val STATIC = 3
    private const val SHOWN = 2 // launchers show about five shortcuts in total

    fun update(ctx: Context, scenes: List<Scene>) {
        val manager = ctx.getSystemService(ShortcutManager::class.java) ?: return
        val room = minOf(SHOWN, manager.maxShortcutCountPerActivity - STATIC).coerceAtLeast(0)
        val shortcuts = scenes.take(room).mapIndexed { i, scene ->
            ShortcutInfo.Builder(ctx, "scene_${scene.id}")
                .setShortLabel(scene.name)
                .setLongLabel(scene.name)
                .setIcon(Icon.createWithResource(ctx, icon(scene)))
                .setIntent(Intent(Intent.ACTION_VIEW).setClass(ctx, MainActivity::class.java).putExtra(EXTRA, scene.id))
                .setRank(i)
                .build()
        }
        runCatching { manager.dynamicShortcuts = shortcuts }
    }

    /** Lets the launcher rank the scenes the user actually uses. */
    fun reportUsed(ctx: Context, scene: Scene) {
        runCatching { ctx.getSystemService(ShortcutManager::class.java)?.reportShortcutUsed("scene_${scene.id}") }
    }

    private fun icon(scene: Scene) = when (scene.nc.mode) {
        SonyProtocol.Mode.ANC -> R.drawable.w_icon_noise_control_off
        SonyProtocol.Mode.AMBIENT -> R.drawable.w_icon_hearing
        SonyProtocol.Mode.OFF -> R.drawable.w_icon_power_settings_new
    }
}
