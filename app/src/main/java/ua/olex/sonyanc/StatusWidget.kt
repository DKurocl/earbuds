package ua.olex.sonyanc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * A 2×2 glance, offered beside the mode and battery widgets (it replaces neither): one big ring
 * for the emptier earbud, the current mode as a pill under it, and how long listening lasts.
 * From cached state only; it never polls. Tap opens the app.
 */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx, mgr, ids)

    companion object {
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val views = viewsFor(ctx, Prefs.lastNc(ctx)?.mode, Prefs.lastBattery(ctx), Prefs.linked(ctx))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views)
        }

        internal fun viewsFor(ctx: Context, mode: SonyProtocol.Mode?, last: Pair<SonyProtocol.Battery, Long>?, linked: Boolean?,
                              interactive: Boolean = true): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_status)
            val b = last?.first
            val (level, charging) = listOf(b?.left to (b?.leftCharging == true), b?.right to (b?.rightCharging == true))
                .filter { it.first != null }.minByOrNull { it.first!! } ?: (null to false)
            val spoken = BatteryWidget.bindRing(ctx, v, R.id.w_left_box, R.id.w_left_ring, R.id.w_left_art, R.id.w_left_value,
                R.id.w_left_label, R.id.w_left_charge, "Earbuds", level, charging, 88f, true)
            if (mode != null) {
                v.setViewVisibility(R.id.w_mode, View.VISIBLE)
                v.setImageViewResource(R.id.w_mode_icon, when (mode) {
                    SonyProtocol.Mode.ANC -> R.drawable.w_icon_anc_bold
                    SonyProtocol.Mode.AMBIENT -> R.drawable.w_icon_ambient_bold
                    else -> R.drawable.w_icon_off_bold
                })
                v.setTextViewText(R.id.w_mode_text, mode.short)
            } else v.setViewVisibility(R.id.w_mode, View.GONE)
            val rate = Prefs.drainRate(ctx, mode)
            val buds = b?.let { BatteryLife.budsMinutes(it, mode, rate) }
            v.setTextViewText(R.id.w_status, when {
                linked == false -> "Not connected"
                buds != null -> BatteryLife.short(buds) + " left"
                else -> ""
            })
            v.setContentDescription(R.id.w_group, spoken + (mode?.let { ", ${it.label}" } ?: ""))
            if (interactive) {
                val open = Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                v.setOnClickPendingIntent(android.R.id.background,
                    PendingIntent.getActivity(ctx, 103, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            return v
        }
    }
}
