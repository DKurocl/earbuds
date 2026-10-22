package ua.olex.sonyanc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Three controls only. A tap reconnects on demand; no persistent service or notification. */
class ModeWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx,mgr,ids)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx,intent)
        if(intent.action!=ACTION_SET) return
        val mode=intent.getStringExtra(EXTRA_MODE)?.let {runCatching{SonyProtocol.Mode.valueOf(it)}.getOrNull()} ?: return
        Headphones.init(ctx)
        val pending=goAsync()
        val generation=latestTap.incrementAndGet()
        CoroutineScope(Dispatchers.IO).launch {
            Headphones.hold()
            try {
                delay(70)
                if(latestTap.get()!=generation) return@launch
                busyMode=mode
                refreshAll(ctx)
                val base=Headphones.pending.value ?: Headphones.nc.value ?: SonyProtocol.NcState(mode)
                val receipt=Headphones.request(base.copy(mode=mode))
                val ok=withTimeoutOrNull(8500){receipt.await()} ?: false
                if(latestTap.get()==generation && !ok) showFailure(ctx)
            } catch(e:Exception) {
                if(latestTap.get()==generation) showFailure(ctx)
            } finally {
                if(latestTap.get()==generation) {busyMode=null;refreshAll(ctx)}
                Headphones.release(graceMs=1500)
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_SET="ua.olex.sonyanc.SET_MODE"
        private const val EXTRA_MODE="mode"
        private val latestTap=AtomicLong()
        @Volatile private var busyMode:SonyProtocol.Mode?=null
        private val tiles=listOf(SonyProtocol.Mode.ANC to R.id.w_anc,SonyProtocol.Mode.AMBIENT to R.id.w_amb,SonyProtocol.Mode.OFF to R.id.w_off)

        private fun showFailure(ctx:Context) {
            Handler(Looper.getMainLooper()).post {Toast.makeText(ctx,"Could not connect. Tap a mode to retry.",Toast.LENGTH_SHORT).show()}
        }

        fun refreshAll(ctx:Context) {
            val mgr=AppWidgetManager.getInstance(ctx)
            val ids=mgr.getAppWidgetIds(ComponentName(ctx,ModeWidget::class.java))
            if(ids.isNotEmpty()) render(ctx,mgr,ids)
        }

        private fun render(ctx:Context,mgr:AppWidgetManager,ids:IntArray) {
            val v=RemoteViews(ctx.packageName,R.layout.widget_modes)
            val current=Prefs.lastNc(ctx)?.mode
            for((mode,id) in tiles) {
                val on=mode==current
                val applying=mode==busyMode
                v.setInt(id,"setBackgroundResource",if(on) R.drawable.w_tile_on else R.drawable.w_tile_off)
                v.setTextColor(id,ctx.getColor(if(on) R.color.w_on_text else R.color.w_off_text))
                v.setTextViewText(id,mode.short+if(applying) "…" else "")
                v.setContentDescription(id,mode.label+if(applying) ", applying" else if(on) ", last used" else "")
                val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val action=if(Prefs.deviceAddress(ctx)==null || !Headphones.hasPermission(ctx)) {
                    val setup=Intent(ctx,MainActivity::class.java).putExtra("hush.mode",mode.name)
                    PendingIntent.getActivity(ctx,mode.ordinal,setup,flags)
                } else {
                    val set=Intent(ctx,ModeWidget::class.java).setAction(ACTION_SET).putExtra(EXTRA_MODE,mode.name)
                    PendingIntent.getBroadcast(ctx,mode.ordinal,set,flags)
                }
                v.setOnClickPendingIntent(id,action)
            }
            mgr.updateAppWidget(ids,v)
        }
    }
}
