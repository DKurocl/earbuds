package ua.olex.sonyanc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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

    internal const val EXTRA_UNTIL = "expires_at"
    private var startVersion = 0L

    internal fun alarmIntent(ctx: Context, until: Long) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, AmbientTimerReceiver::class.java)
            .setData(android.net.Uri.parse("earbuds://ambient-alarm/$until"))
            .putExtra(EXTRA_UNTIL, until),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelAlarm(ctx: Context, alarms: AlarmManager, until: Long) {
        alarms.cancel(alarmIntent(ctx, until))
        PendingIntent.getBroadcast(ctx, 7, Intent(ctx, AmbientTimerReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let(alarms::cancel)
    }

    /** Start timing once the earbuds confirm Ambient. */
    suspend fun start(ctx: Context, minutes: Int, from: SonyProtocol.NcState): SonyProtocol.Mode? {
        val (version, receipt) = requestStart(from)
        if (withTimeoutOrNull(8500) { receipt.await() } != true) return null
        return armConfirmed(ctx, minutes, from.mode, version, receipt)
    }

    @Synchronized private fun requestStart(from: SonyProtocol.NcState) =
        ++startVersion to Headphones.request(from.copy(mode = SonyProtocol.Mode.AMBIENT))

    @Synchronized
    @android.annotation.SuppressLint("MissingPermission")
    private fun armConfirmed(
        ctx: Context, minutes: Int, from: SonyProtocol.Mode, version: Long, receipt: Deferred<Boolean>,
    ): SonyProtocol.Mode? {
        if (version != startVersion || !ModeControl.isCurrent(receipt)) return null
        val previous = Prefs.timerUntil(ctx)
        val now = System.currentTimeMillis()
        val until = now + minutes * 60_000L
        val back = when {
            from != SonyProtocol.Mode.AMBIENT -> from
            previous > now -> returnMode(ctx)
            else -> SonyProtocol.Mode.ANC
        }
        val alarms = ctx.getSystemService(AlarmManager::class.java)
        if (previous != 0L) cancelAlarm(ctx, alarms, previous)
        Prefs.setTimer(ctx, until, back)
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, alarmIntent(ctx, until))
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, alarmIntent(ctx, until))
        LiveTimer.show(ctx, until, back)
        Widgets.refreshAll(ctx)
        return back
    }

    /** A manual choice or Stay also invalidates a start waiting for hardware confirmation. */
    @Synchronized fun cancel(ctx: Context, expectedUntil: Long? = null) {
        val until = Prefs.timerUntil(ctx)
        if (expectedUntil != null && until != expectedUntil) return
        startVersion++
        if (until != 0L) finish(ctx, until)
    }

    @Synchronized internal fun finish(ctx: Context, expectedUntil: Long): SonyProtocol.Mode? {
        if (expectedUntil == 0L || Prefs.timerUntil(ctx) != expectedUntil) return null
        val back = returnMode(ctx)
        cancelAlarm(ctx, ctx.getSystemService(AlarmManager::class.java), expectedUntil)
        Prefs.clearTimer(ctx)
        LiveTimer.hide(ctx)
        Widgets.refreshAll(ctx)
        return back
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
        val revision = ModeControl.revision
        val activeUntil = Prefs.timerUntil(ctx)
        if (activeUntil == 0L) { LiveTimer.hide(ctx); return }
        if (intent.getLongExtra(AmbientTimer.EXTRA_UNTIL, 0) != activeUntil) return
        if (intent.action == null && activeUntil > System.currentTimeMillis()) return
        if (intent.action == LiveTimer.ACTION_STAY) {
            AmbientTimer.cancel(ctx, expectedUntil = activeUntil)
            return
        }
        val back = AmbientTimer.finish(ctx, activeUntil) ?: return
        // Earbuds already in the case: nothing to switch back, and no error buzz in the pocket.
        if (Prefs.linked(ctx) == false) return
        Headphones.init(ctx)
        val pending = goAsync()
        scope.launch {
            Headphones.hold(foreground = false)
            try {
                val base = Headphones.state.value.shownNc ?: SonyProtocol.NcState(back)
                val receipt = ModeControl.requestIfUnchanged(base.copy(mode = back), revision) {
                    Prefs.timerUntil(ctx) == 0L && Prefs.linked(ctx) != false
                } ?: return@launch
                val ok = withTimeoutOrNull(8500) { receipt.await() } ?: false
                if (Prefs.haptics(ctx) && ModeControl.isCurrent(receipt)) {
                    if (ok) Haptics.confirm(ctx, back) else Haptics.reject(ctx)
                }
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

    private fun action(ctx: Context, action: String, code: Int, until: Long) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, AmbientTimerReceiver::class.java).setAction(action)
            .setData(android.net.Uri.parse("earbuds://ambient-timer/$until"))
            .putExtra(AmbientTimer.EXTRA_UNTIL, until),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun show(ctx: Context, until: Long, back: SonyProtocol.Mode) {
        if (until <= System.currentTimeMillis() || Prefs.timerUntil(ctx) != until) return
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
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .setWhen(until).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setTimeoutAfter(until - System.currentTimeMillis() + 5_000)
            .setContentIntent(open)
            .addAction(android.app.Notification.Action.Builder(null, "Stay in Ambient", action(ctx, ACTION_STAY, 9, until)).build())
            .addAction(android.app.Notification.Action.Builder(null, "${back.short} now", action(ctx, ACTION_NOW, 10, until)).build())
        // Android 16 QPR2 (36.1) and later: promote it to a Live Update (status-bar chip, top of
        // the lock screen). SDK_INT_FULL itself only exists from 36.
        if (android.os.Build.VERSION.SDK_INT >= 36 &&
            android.os.Build.VERSION.SDK_INT_FULL >= android.os.Build.VERSION_CODES_FULL.BAKLAVA_1) b.setRequestPromotedOngoing(true).setShortCriticalText("Ambient")
        nm.notify(ID, b.build())
    }

    fun hide(ctx: Context) { ctx.getSystemService(android.app.NotificationManager::class.java)?.cancel(ID) }
}
