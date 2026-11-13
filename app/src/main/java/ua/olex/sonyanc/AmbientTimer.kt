package ua.olex.sonyanc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Ambient for a moment": Ambient now, the previous mode back after a few minutes (a till, a
 * coffee, an announcement). One exact alarm exists only while a timer runs; any manual mode
 * change cancels it. Nothing else runs in the background.
 */
object AmbientTimer {
    val CHOICES = listOf(2, 5, 10)

    /** End time in wall-clock ms, or null when no timer is running. */
    fun until(ctx: Context): Long? = Prefs.timerUntil(ctx).takeIf { it > System.currentTimeMillis() }
    fun returnMode(ctx: Context): SonyProtocol.Mode = Prefs.timerReturn(ctx) ?: SonyProtocol.Mode.ANC

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, AmbientTimerReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Remembers where to return, arms the alarm and switches to Ambient with the user's level.
     * USE_EXACT_ALARM is held and canScheduleExactAlarms() is checked; lint only knows SCHEDULE_EXACT_ALARM.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun start(ctx: Context, minutes: Int, from: SonyProtocol.NcState) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        val back = if (from.mode == SonyProtocol.Mode.AMBIENT) SonyProtocol.Mode.ANC else from.mode
        Prefs.setTimer(ctx, until, back)
        val alarms = ctx.getSystemService(AlarmManager::class.java)
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, intent(ctx))
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, intent(ctx))
        Headphones.request(from.copy(mode = SonyProtocol.Mode.AMBIENT))
        LiveTimer.show(ctx, until, back)
        Widgets.refreshAll(ctx)
    }

    /** The user chose a mode themselves (or wants to stay in Ambient): forget the return. */
    fun cancel(ctx: Context) {
        if (Prefs.timerUntil(ctx) == 0L) return
        ctx.getSystemService(AlarmManager::class.java).cancel(intent(ctx))
        Prefs.clearTimer(ctx)
        LiveTimer.hide(ctx)
        Widgets.refreshAll(ctx)
    }

    /** "4:12" for the countdown pill. */
    fun countdown(msLeft: Long): String {
        val s = ((msLeft + 999) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/**
 * Fires once when the timer ends, or from the Live Update's buttons: "Stay in Ambient" forgets
 * the return; "ANC now" (or the alarm) returns to the remembered mode under a short lease.
 */
class AmbientTimerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Prefs.timerUntil(ctx) == 0L) { LiveTimer.hide(ctx); return } // cancelled meanwhile
        if (intent.action == LiveTimer.ACTION_STAY) { AmbientTimer.cancel(ctx); return }
        val back = AmbientTimer.returnMode(ctx)
        ctx.getSystemService(android.app.AlarmManager::class.java).cancel(
            android.app.PendingIntent.getBroadcast(ctx, 7, Intent(ctx, AmbientTimerReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
        Prefs.clearTimer(ctx)
        LiveTimer.hide(ctx)
        // Earbuds already in the case: nothing to switch back, and no error buzz in the pocket.
        if (Prefs.linked(ctx) == false) { Widgets.refreshAll(ctx); return }
        Headphones.init(ctx)
        val pending = goAsync()
        scope.launch {
            Headphones.hold(foreground = false)
            try {
                val base = Headphones.state.value.shownNc ?: SonyProtocol.NcState(back)
                val ok = withTimeoutOrNull(8500) { Headphones.request(base.copy(mode = back)).await() } ?: false
                if (Prefs.haptics(ctx)) if (ok) Haptics.confirm(ctx, back) else Haptics.reject(ctx)
            } finally {
                Widgets.refreshAll(ctx)
                Headphones.release(foreground = false)
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * The running timer as an Android 16+ Live Update: a status-bar chip counting down, and on the
 * lock screen "Stay in Ambient" and "<mode> now". Shown only when notifications are allowed;
 * the timer itself works without it.
 */
object LiveTimer {
    const val ACTION_STAY = "ua.olex.sonyanc.TIMER_STAY"
    const val ACTION_NOW = "ua.olex.sonyanc.TIMER_NOW"
    private const val CHANNEL = "ambient_timer"
    private const val ID = 7

    private fun action(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, AmbientTimerReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun show(ctx: Context, until: Long, back: SonyProtocol.Mode) {
        if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Ambient for a moment", android.app.NotificationManager.IMPORTANCE_LOW).apply {
            description = "Counts down while Ambient is on for a moment"
        })
        val open = PendingIntent.getActivity(ctx, 8, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val at = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(until))
        val b = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.w_icon_ambient)
            .setContentTitle("Ambient for a moment")
            .setContentText("Back to ${back.label.lowercase()} at $at")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(android.app.Notification.CATEGORY_STATUS)
            .setWhen(until).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setTimeoutAfter(until - System.currentTimeMillis() + 5_000)
            .setContentIntent(open)
            .addAction(android.app.Notification.Action.Builder(null, "Stay in Ambient", action(ctx, ACTION_STAY, 9)).build())
            .addAction(android.app.Notification.Action.Builder(null, "${back.short} now", action(ctx, ACTION_NOW, 10)).build())
        // Android 16 QPR2 (36.1) and later: promote it to a Live Update (status-bar chip, top of
        // the lock screen). SDK_INT_FULL itself only exists from 36.
        if (android.os.Build.VERSION.SDK_INT >= 36 &&
            android.os.Build.VERSION.SDK_INT_FULL >= android.os.Build.VERSION_CODES_FULL.BAKLAVA_1) b.setRequestPromotedOngoing(true)
        nm.notify(ID, b.build())
    }

    fun hide(ctx: Context) { ctx.getSystemService(android.app.NotificationManager::class.java)?.cancel(ID) }
}
