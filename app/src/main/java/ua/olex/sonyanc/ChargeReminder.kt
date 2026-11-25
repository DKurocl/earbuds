package ua.olex.sonyanc

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Calendar

/**
 * "Charge before you go": learns when the earbuds usually come out of the case first each day
 * (weekdays and weekends apart) and, 30 minutes before, if the case was last seen under
 * [THRESHOLD] % and not charging, says so once. One inexact alarm a day; nothing else runs.
 */
object ChargeReminder {
    const val THRESHOLD = 30
    private const val LEAD_MIN = 30
    private const val MIN_DAYS = 5
    private const val KEEP = 28
    private const val CHANNEL = "charge_before"
    private const val ID = 31

    /** The first time the earbuds came out today (minutes since midnight), kept for 4 weeks. */
    fun recordOut(ctx: Context, now: Long = System.currentTimeMillis()) {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val day = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        val past = Prefs.outTimes(ctx)
        if (past.any { it.first == day }) return reschedule(ctx)
        val weekend = c.get(Calendar.DAY_OF_WEEK) in setOf(Calendar.SATURDAY, Calendar.SUNDAY)
        val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        Prefs.setOutTimes(ctx, (past + Triple(day, weekend, minute)).takeLast(KEEP))
        reschedule(ctx)
    }

    /** Pure, unit-tested: the usual first time out for this kind of day, or null if not learned. */
    fun usualMinute(times: List<Triple<Int, Boolean, Int>>, weekend: Boolean): Int? {
        val same = times.filter { it.second == weekend }.map { it.third }.sorted()
        if (same.size < MIN_DAYS) return null
        return same[same.size / 2]
    }

    /** The next reminder time after [now], or null when nothing is learned yet. */
    fun next(times: List<Triple<Int, Boolean, Int>>, now: Long): Long? {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        for (ahead in 0..7) {
            val day = (c.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, ahead) }
            val weekend = day.get(Calendar.DAY_OF_WEEK) in setOf(Calendar.SATURDAY, Calendar.SUNDAY)
            val usual = usualMinute(times, weekend) ?: continue
            val at = (usual - LEAD_MIN).coerceAtLeast(0)
            day.set(Calendar.HOUR_OF_DAY, at / 60); day.set(Calendar.MINUTE, at % 60); day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0)
            if (day.timeInMillis > now) return day.timeInMillis
        }
        return null
    }

    private fun alarm(ctx: Context) = PendingIntent.getBroadcast(ctx, 31, Intent(ctx, ChargeReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(alarm(ctx))
        if (!Prefs.chargeReminder(ctx)) return
        val at = next(Prefs.outTimes(ctx), System.currentTimeMillis()) ?: return
        // A quarter-hour window: the reminder need not be to the minute, and Android can batch it.
        am.setWindow(AlarmManager.RTC_WAKEUP, at, 15 * 60_000L, alarm(ctx))
    }

    internal fun fire(ctx: Context) {
        reschedule(ctx)
        if (!Prefs.chargeReminder(ctx) || Prefs.linked(ctx) == true) return // already out: too late to help
        val (battery, at) = Prefs.lastBattery(ctx) ?: return
        // A reading older than a day may miss a charge since (the case alone tells the phone nothing).
        if (System.currentTimeMillis() - at > 24 * 60 * 60_000L) return
        val case = battery.case ?: return
        if (case >= THRESHOLD || battery.caseCharging) return
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Charge before you go", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Before you usually take the earbuds out, when the case is low"
        })
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_case_mark)
            .setContentTitle("Case battery $case%")
            .setContentText("You usually take the earbuds out soon. A little time on the charger helps.")
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
            .build())
    }
}

class ChargeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ChargeReminder.reschedule(ctx)
                LiveLink.ensureObserving(ctx)
            }
            else -> ChargeReminder.fire(ctx)
        }
    }
}
