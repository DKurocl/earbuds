package ua.olex.sonyanc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.text.DateFormat

/**
 * Left, case and right battery as Pixel-style rings. Shows the last reading the app, the widget
 * or the connect broadcast took; it never polls. The time is absolute ("Updated 14:32"), so the
 * text stays true however long the widget goes without an update. Tap opens the app.
 */
class BatteryWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx, mgr, ids)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        super.onAppWidgetOptionsChanged(ctx, mgr, id, options)
        render(ctx, mgr, intArrayOf(id))
    }

    companion object {
        /** Picker preview only. */
        internal val SAMPLE = SonyProtocol.Battery(82, 79, 56, caseCharging = true)
        private val FALLBACK = listOf(SizeF(110f, 110f), SizeF(250f, 110f), SizeF(250f, 150f), SizeF(340f, 200f))
        private const val STALE_ALPHA = 140

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BatteryWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val last = Prefs.lastBattery(ctx)
            val linked = Prefs.linked(ctx)
            val lastSeen = Prefs.lastSeen(ctx)
            for (id in ids) {
                val sizes = Widgets.sizes(mgr.getAppWidgetOptions(id), FALLBACK)
                mgr.updateAppWidget(id, Widgets.forSizes(sizes) {
                    viewsFor(ctx, it.width, it.height, last?.first, last?.second ?: 0L, linked, lastSeen)
                })
            }
        }

        private fun time(ctx: Context, at: Long) =
            DateUtils.formatSameDayTime(at, System.currentTimeMillis(), DateFormat.MEDIUM, DateFormat.SHORT).toString()

        /** "Updated 14:32", or when the earbuds left the phone; absolute, so it never goes stale. */
        internal fun status(ctx: Context, battery: SonyProtocol.Battery?, at: Long, linked: Boolean?, lastSeen: Long) = when {
            battery == null -> "Open Earbuds to read battery"
            linked == false && lastSeen > 0 -> "Disconnected " + time(ctx, lastSeen)
            at > 0 -> "Updated " + time(ctx, at)
            else -> ""
        }

        /**
         * One ring: level, color (green, red at 20 % or less unless charging, grey when unknown),
         * a bolt while charging, and dimmed when the earbuds are away. Returns the spoken text.
         */
        internal fun bindRing(
            ctx: Context, v: RemoteViews, box: Int, ring: Int, art: Int, value: Int, label: Int,
            name: String, level: Int?, charging: Boolean, size: Float?, stale: Boolean, showLabel: Boolean,
        ): String {
            val color = ctx.getColor(when {
                level == null -> R.color.w_unknown
                level <= 20 && !charging -> R.color.w_low
                else -> R.color.w_good
            })
            v.setProgressBar(ring, 100, level ?: 0, false)
            if (Build.VERSION.SDK_INT >= 31) {
                val tint = if (stale) (color and 0x00FFFFFF) or (STALE_ALPHA shl 24) else color
                v.setColorStateList(ring, "setProgressTintList", android.content.res.ColorStateList.valueOf(tint))
                if (size != null) {
                    v.setViewLayoutWidth(box, size, TypedValue.COMPLEX_UNIT_DIP)
                    v.setViewLayoutHeight(box, size, TypedValue.COMPLEX_UNIT_DIP)
                    val artSize = size * 0.78f
                    v.setViewLayoutWidth(art, artSize, TypedValue.COMPLEX_UNIT_DIP)
                    v.setViewLayoutHeight(art, artSize, TypedValue.COMPLEX_UNIT_DIP)
                    val inset = (artSize * 0.13f * ctx.resources.displayMetrics.density).toInt()
                    v.setViewPadding(art, inset, inset, inset, inset)
                }
            }
            if (size == null) {
                val inset = (34f * 0.13f * ctx.resources.displayMetrics.density).toInt()
                v.setViewPadding(art, inset, inset, inset, inset)
            }
            v.setInt(art, "setImageAlpha", if (stale) STALE_ALPHA else 255)
            v.setTextViewText(value, level?.let { "$it%" } ?: "—")
            val text = ctx.getColor(if (level != null && level <= 20 && !charging) R.color.w_low else R.color.w_off_text)
            v.setTextColor(value, if (stale) (text and 0x00FFFFFF) or (STALE_ALPHA shl 24) else text)
            v.setTextViewCompoundDrawablesRelative(value, 0, 0, if (charging && level != null) R.drawable.w_icon_bolt else 0, 0)
            v.setViewVisibility(label, if (showLabel) View.VISIBLE else View.GONE)
            return "$name " + (level?.let { "$it percent" + if (charging) " charging" else "" } ?: "unknown")
        }

        internal fun viewsFor(
            ctx: Context, width: Float, height: Float, battery: SonyProtocol.Battery?, at: Long,
            linked: Boolean?, lastSeen: Long, interactive: Boolean = true,
        ): RemoteViews {
            val spec = WidgetLayout.battery(width, height, value = Widgets.lineHeight(ctx, 15f, "google-sans-text-medium") , small = Widgets.lineHeight(ctx, 12f))
            val v = RemoteViews(ctx.packageName, if (spec.compact) R.layout.widget_battery_compact else R.layout.widget_battery)
            // Away from the phone the levels are a memory, not a reading: dim them.
            val stale = linked == false
            val b = battery ?: SonyProtocol.Battery()
            val spoken = mutableListOf<String>()

            fun part(box: Int, ring: Int, art: Int, value: Int, label: Int, name: String, level: Int?, charging: Boolean) {
                spoken += bindRing(ctx, v, box, ring, art, value, label, name, level, charging, spec.ring, stale, spec.labels)
            }

            if (spec.compact) {
                // The emptier earbud decides how long you can listen.
                val left = b.left; val right = b.right
                val useRight = right != null && (left == null || right < left)
                val level = if (useRight) right else left
                val charging = if (useRight) b.rightCharging else b.leftCharging
                part(R.id.w_left_box, R.id.w_left_ring, R.id.w_left_art, R.id.w_left_value, R.id.w_left_label, "Earbuds", level, charging)
                v.setTextViewText(R.id.w_status, "Case " + (b.case?.let { "$it%" } ?: "—"))
                spoken += "case " + (b.case?.let { "$it percent" } ?: "unknown")
            } else {
                part(R.id.w_left_box, R.id.w_left_ring, R.id.w_left_art, R.id.w_left_value, R.id.w_left_label, "Left", b.left, b.leftCharging)
                part(R.id.w_case_box, R.id.w_case_ring, R.id.w_case_art, R.id.w_case_value, R.id.w_case_label, "Case", b.case, b.caseCharging)
                part(R.id.w_right_box, R.id.w_right_ring, R.id.w_right_art, R.id.w_right_value, R.id.w_right_label, "Right", b.right, b.rightCharging)
                v.setTextViewText(R.id.w_status, status(ctx, battery, at, linked, lastSeen))
            }
            v.setViewVisibility(R.id.w_status, if (spec.status) View.VISIBLE else View.GONE)
            v.setContentDescription(android.R.id.background, "Earbuds battery: " + spoken.joinToString(", "))
            if (interactive) {
                val open = Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                v.setOnClickPendingIntent(android.R.id.background,
                    PendingIntent.getActivity(ctx, 100, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            return v
        }
    }
}
