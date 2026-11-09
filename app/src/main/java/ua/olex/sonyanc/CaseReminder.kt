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
 * Opt-in reminder for a nearly empty case: when the earbuds come out (fresh reading from the
 * connect sync) or go back in (last reading before they left). One shared 12-hour quiet period,
 * so the two moments never double up. No background work: the ACL broadcasts decide.
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

    /** Earbuds back in the case: uses the last reading taken before they left. */
    fun onEarbudsAway(ctx: Context) = remind(ctx, "Put the case on the charger so the earbuds can recharge.")

    /**
     * Earbuds just taken out, right after the connect sync read the battery: the best moment,
     * since the case can charge while you listen.
     */
    fun onEarbudsOut(ctx: Context) = remind(ctx, "Charge the case while you listen, so the earbuds can recharge later.")

    private fun remind(ctx: Context, text: String) {
        val (battery, at) = Prefs.lastBattery(ctx) ?: return
        val now = System.currentTimeMillis()
        if (!shouldRemind(Prefs.caseReminder(ctx), battery.case, battery.caseCharging, at, Prefs.caseRemindedAt(ctx), now)) return
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Case battery", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When the case is nearly empty"
        })
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_case_mark)
            .setContentTitle("Case battery ${battery.case}%")
            .setContentText(text)
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
            .build()
        nm.notify(ID, n)
        Prefs.setCaseRemindedAt(ctx, now)
    }

    /** The earbuds are out again: the reminder has done its job. */
    fun clear(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
}
