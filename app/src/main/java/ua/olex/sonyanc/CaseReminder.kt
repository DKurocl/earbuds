package ua.olex.sonyanc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Notification

/**
 * Opt-in reminder when the earbuds go back into a nearly empty case. Uses the last reading
 * taken before they left (the case is not reachable once they are inside it), so it needs no
 * background work: the disconnect broadcast decides.
 */
object CaseReminder {
    const val THRESHOLD = 25
    private const val QUIET_MS = 12 * 60 * 60 * 1000L
    private const val FRESH_MS = 12 * 60 * 60 * 1000L
    private const val CHANNEL = "case_battery"
    private const val ID = 25

    /** Pure decision, unit-tested: a recent, low, not-charging case and no reminder lately. */
    fun shouldRemind(enabled: Boolean, case: Int?, charging: Boolean, readAt: Long, lastReminded: Long, now: Long): Boolean =
        enabled && case != null && case <= THRESHOLD && !charging &&
            now - readAt in 0..FRESH_MS && now - lastReminded >= QUIET_MS

    fun onEarbudsAway(ctx: Context) {
        val (battery, at) = Prefs.lastBattery(ctx) ?: return
        val now = System.currentTimeMillis()
        if (!shouldRemind(Prefs.caseReminder(ctx), battery.case, battery.caseCharging, at, Prefs.caseRemindedAt(ctx), now)) return
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Case battery", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When the earbuds go into a nearly empty case"
        })
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_case_mark)
            .setContentTitle("Case battery ${battery.case}%")
            .setContentText("Put the case on the charger so the earbuds can recharge.")
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
            .build()
        nm.notify(ID, n)
        Prefs.setCaseRemindedAt(ctx, now)
    }

    /** The earbuds are out again: the reminder has done its job. */
    fun clear(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
}
