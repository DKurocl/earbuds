package ua.olex.sonyanc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home-screen widget. A tap is handled entirely inside the broadcast (goAsync, < 10 s):
 * connect → send → let go. No service, no notification, nothing left running.
 */
class ModeWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx, mgr, ids)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != ACTION_SET) return
        val mode = intent.getStringExtra(EXTRA_MODE)
            ?.let { runCatching { SonyProtocol.Mode.valueOf(it) }.getOrNull() } ?: return

        Headphones.init(ctx)
        val pending = goAsync()
        val generation=latestTap.incrementAndGet()
        CoroutineScope(Dispatchers.IO).launch {
            Headphones.hold()
            try {
                delay(70)
                if(latestTap.get()!=generation) return@launch
                note = "Applying…"
                refreshAll(ctx)
                val base = Headphones.pending.value ?: Headphones.nc.value ?: SonyProtocol.NcState(mode)
                val receipt=Headphones.request(base.copy(mode=mode))
                val ok=withTimeoutOrNull(8_500) {receipt.await()} ?: false
                if(latestTap.get()==generation) {
                    note = if(ok) null else "Could not connect · tap a mode to retry"
                    refreshAll(ctx)
                }
            } catch (e: Exception) {
                if(latestTap.get()==generation) {
                    note = "Could not connect · tap a mode to retry"
                    refreshAll(ctx)
                }
            } finally {
                Headphones.release(graceMs = 1_500)
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_SET = "ua.olex.sonyanc.SET_MODE"
        private const val EXTRA_MODE = "mode"

        @Volatile private var note: String? = null
        private val latestTap=AtomicLong()

        private val tiles = listOf(
            SonyProtocol.Mode.ANC to R.id.w_anc,
            SonyProtocol.Mode.AMBIENT to R.id.w_amb,
            SonyProtocol.Mode.OFF to R.id.w_off,
        )

        fun clearNote(ctx:Context) {note=null;refreshAll(ctx)}

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ModeWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_modes)
            val current = Prefs.lastNc(ctx)?.mode
            v.setTextViewText(R.id.w_title, note ?: if (Prefs.deviceAddress(ctx) == null) "Hush · tap to set up" else "Hush · last used")

            for ((mode, id) in tiles) {
                val on = mode == current
                v.setInt(id, "setBackgroundResource", if (on) R.drawable.w_tile_on else R.drawable.w_tile_off)
                v.setTextColor(id, ctx.getColor(if (on) R.color.w_on_text else R.color.w_off_text))
                val i = Intent(ctx, ModeWidget::class.java).setAction(ACTION_SET).putExtra(EXTRA_MODE, mode.name)
                val action=if(Prefs.deviceAddress(ctx)==null || !Headphones.hasPermission(ctx)) {
                    val setup=Intent(ctx,MainActivity::class.java).putExtra("hush.mode",mode.name)
                    PendingIntent.getActivity(ctx,mode.ordinal,setup,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                } else PendingIntent.getBroadcast(ctx,mode.ordinal,i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                v.setOnClickPendingIntent(id,action)
            }
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(R.id.w_title, PendingIntent.getActivity(
                ctx, 100, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            mgr.updateAppWidget(ids, v)
        }
    }
}

