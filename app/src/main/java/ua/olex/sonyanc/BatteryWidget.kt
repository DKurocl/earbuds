package ua.olex.sonyanc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
        /** The device glyph sits inside its tonal disc with this margin, as in Pixel's battery widget. */
        private const val GLYPH_INSET = 0.2f
        private val FALLBACK = listOf(SizeF(110f, 110f), SizeF(250f, 110f), SizeF(250f, 150f), SizeF(340f, 200f))

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
         * One ring: level, the wallpaper's accent (red at 20 % or less unless charging, grey when
         * unknown) and a bolt while charging. Away from the phone it keeps its colors: the last
         * reading is still the truth, and the status line says when it was taken. Returns the
         * spoken text.
         */
        internal fun bindRing(
            ctx: Context, v: RemoteViews, box: Int, ring: Int, art: Int, value: Int, label: Int, charge: Int,
            name: String, level: Int?, charging: Boolean, size: Float?, showLabel: Boolean,
        ): String {
            val color = ctx.getColor(when {
                level == null -> R.color.w_unknown
                level <= 20 && !charging -> R.color.w_low
                else -> R.color.w_ring
            })
            v.setProgressBar(ring, 100, level ?: 0, false)
            v.setColorStateList(ring, "setProgressTintList", android.content.res.ColorStateList.valueOf(color))
            if (size != null) {
                v.setViewLayoutWidth(box, size, TypedValue.COMPLEX_UNIT_DIP)
                v.setViewLayoutHeight(box, size, TypedValue.COMPLEX_UNIT_DIP)
                val artSize = size * 0.78f
                v.setViewLayoutWidth(art, artSize, TypedValue.COMPLEX_UNIT_DIP)
                v.setViewLayoutHeight(art, artSize, TypedValue.COMPLEX_UNIT_DIP)
                val inset = (artSize * GLYPH_INSET * ctx.resources.displayMetrics.density).toInt()
                v.setViewPadding(art, inset, inset, inset, inset)
            }
            if (size == null) {
                val inset = (34f * GLYPH_INSET * ctx.resources.displayMetrics.density).toInt()
                v.setViewPadding(art, inset, inset, inset, inset)
            }
            v.setTextViewText(value, level?.let { "$it%" } ?: "\u2014")
            val text = ctx.getColor(if (level != null && level <= 20 && !charging) R.color.w_low else R.color.w_off_text)
            v.setTextColor(value, text)
            // Charging shows as a badge on the ring (like the app's chip), not as a glyph after the number.
            v.setViewVisibility(charge, if (charging && level != null) View.VISIBLE else View.GONE)
            val badge = ((size ?: 44f) * 0.38f).coerceIn(16f, 26f)
            v.setViewLayoutWidth(charge, badge, TypedValue.COMPLEX_UNIT_DIP)
            v.setViewLayoutHeight(charge, badge, TypedValue.COMPLEX_UNIT_DIP)
            val pad = (badge * 0.2f * ctx.resources.displayMetrics.density).toInt()
            v.setViewPadding(charge, pad, pad, pad, pad)
            v.setViewVisibility(label, if (showLabel) View.VISIBLE else View.GONE)
            return "$name " + (level?.let { "$it percent" + if (charging) " charging" else "" } ?: "unknown")
        }

        internal fun viewsFor(
            ctx: Context, width: Float, height: Float, battery: SonyProtocol.Battery?, at: Long,
            linked: Boolean?, lastSeen: Long, interactive: Boolean = true,
        ): RemoteViews {
            val spec = WidgetLayout.battery(width, height, value = Widgets.lineHeight(ctx, 15f, "google-sans-text-medium") , small = Widgets.lineHeight(ctx, 12f))
            val v = RemoteViews(ctx.packageName, if (spec.compact) R.layout.widget_battery_compact else R.layout.widget_battery)
            val b = battery ?: SonyProtocol.Battery()
            val spoken = mutableListOf<String>()

            fun part(box: Int, ring: Int, art: Int, value: Int, label: Int, charge: Int, name: String, level: Int?, charging: Boolean) {
                spoken += bindRing(ctx, v, box, ring, art, value, label, charge, name, level, charging, spec.ring, spec.labels)
            }

            if (spec.compact) {
                // The emptier earbud decides how long you can listen.
                val left = b.left; val right = b.right
                val useRight = right != null && (left == null || right < left)
                val level = if (useRight) right else left
                val charging = if (useRight) b.rightCharging else b.leftCharging
                part(R.id.w_left_box, R.id.w_left_ring, R.id.w_left_art, R.id.w_left_value, R.id.w_left_label, R.id.w_left_charge, "Earbuds", level, charging)
                v.setImageViewResource(R.id.w_left_art, if (useRight) R.drawable.w_glyph_bud_right else R.drawable.w_glyph_bud_left)
                v.setTextViewText(R.id.w_status, "Case " + (b.case?.let { "$it%" } ?: "\u2014"))
                spoken += "case " + (b.case?.let { "$it percent" } ?: "unknown")
            } else {
                part(R.id.w_left_box, R.id.w_left_ring, R.id.w_left_art, R.id.w_left_value, R.id.w_left_label, R.id.w_left_charge, "Left", b.left, b.leftCharging)
                part(R.id.w_case_box, R.id.w_case_ring, R.id.w_case_art, R.id.w_case_value, R.id.w_case_label, R.id.w_case_charge, "Case", b.case, b.caseCharging)
                part(R.id.w_right_box, R.id.w_right_ring, R.id.w_right_art, R.id.w_right_value, R.id.w_right_label, R.id.w_right_charge, "Right", b.right, b.rightCharging)
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
