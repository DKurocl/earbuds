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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A heads-up when the emptier earbud gets low while you listen. Android gives apps no earbud
 * battery in the background and Earbuds keeps no connection open, so it plans instead: after
 * each reading it predicts, from the owner's own measured drain, when the emptier earbud will
 * reach [THRESHOLD] and sets one inexact alarm a little before. The alarm takes a quick fresh
 * reading, which either notifies or plans the next check. Back in the case, everything stops.
 */
object LowBattery {
    const val THRESHOLD = 15
    private const val CHANNEL = "earbuds_battery"
    private const val ID = 15
    /** Check a little before the predicted moment; never sooner than this. */
    private const val EARLY = 0.85
    private const val MIN_WAIT_MIN = 5

    /**
     * Minutes until the emptier earbud reaches [THRESHOLD]: 0 when it already has, null when
     * there is nothing to plan (unknown levels, or charging in the case). Pure, unit-tested.
     */
    fun minutesToLow(b: SonyProtocol.Battery, mode: SonyProtocol.Mode?, rate: Double?): Int? {
        if (b.leftCharging || b.rightCharging) return null
        val level = listOfNotNull(b.left, b.right).minOrNull() ?: return null
        if (level <= THRESHOLD) return 0
        return BatteryLife.fullChargeMinutes(mode, rate) * (level - THRESHOLD) / 100
    }

    private fun alarm(ctx: Context) = PendingIntent.getBroadcast(
        ctx, ID, Intent(ctx, LowBatteryReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** After every battery reading. */
    fun onReading(ctx: Context, b: SonyProtocol.Battery, mode: SonyProtocol.Mode?) {
        val alarms = ctx.getSystemService(AlarmManager::class.java) ?: return
        val minutes = if (Prefs.budsReminder(ctx) && Prefs.linked(ctx) != false) minutesToLow(b, mode, Prefs.drainRate(ctx, mode)) else null
        when (minutes) {
            null -> alarms.cancel(alarm(ctx))
            0 -> { alarms.cancel(alarm(ctx)); notify(ctx, b, mode) }
            else -> {
                val wait = maxOf(MIN_WAIT_MIN, (minutes * EARLY).toInt())
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + wait * 60_000L, alarm(ctx))
            }
        }
    }

    /** Earbuds back in the case: nothing to watch, and the warning has done its job. */
    fun onAway(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(alarm(ctx))
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    /** Once per time out of the case. */
    private fun notify(ctx: Context, b: SonyProtocol.Battery, mode: SonyProtocol.Mode?) {
        val session = Prefs.session(ctx)
        if (Prefs.budsRemindedSession(ctx) == session) return
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Earbuds battery", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When an earbud is nearly empty while you listen"
        })
        val leftLow = (b.left ?: 101) <= (b.right ?: 101)
        val level = (if (leftLow) b.left else b.right) ?: return
        val left = BatteryLife.budsMinutes(b, mode, Prefs.drainRate(ctx, mode))
        val open = PendingIntent.getActivity(ctx, ID, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(if (leftLow) R.drawable.w_glyph_bud_left else R.drawable.w_glyph_bud_right)
            .setContentTitle("${if (leftLow) "Left" else "Right"} earbud $level%")
            .setContentText(left?.let { "About ${BatteryLife.format(it)} of listening left" } ?: "Put the earbuds in the case soon")
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
            .build()
        nm.notify(ID, n)
        Prefs.setBudsRemindedSession(ctx, session)
    }
}

/** The planned check: a quick fresh reading, which LowBattery.onReading then acts on. */
class LowBatteryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Prefs.linked(ctx) == false || !Headphones.hasPermission(ctx)) return
        Headphones.init(ctx)
        val pending = goAsync()
        scope.launch {
            Headphones.hold(foreground = false)
            try {
                withTimeoutOrNull(8000) { if (Headphones.connectAndSync()) Headphones.queryBattery() }
            } finally {
                Headphones.release(foreground = false)
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
