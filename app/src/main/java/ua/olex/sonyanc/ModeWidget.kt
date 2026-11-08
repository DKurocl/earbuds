package ua.olex.sonyanc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
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
    override fun onAppWidgetOptionsChanged(ctx:Context,mgr:AppWidgetManager,id:Int,options:Bundle) {
        super.onAppWidgetOptionsChanged(ctx,mgr,id,options)
        render(ctx,mgr,intArrayOf(id))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx,intent)
        if(intent.action!=ACTION_SET) return
        val mode=intent.getStringExtra(EXTRA_MODE)?.let {runCatching{SonyProtocol.Mode.valueOf(it)}.getOrNull()} ?: return
        Headphones.init(ctx)
        val pending=goAsync()
        val generation=latestTap.incrementAndGet()
        CoroutineScope(Dispatchers.IO).launch {
            Headphones.hold(foreground=false)
            try {
                delay(70)
                if(latestTap.get()!=generation) return@launch
                busyMode=mode
                refreshAll(ctx)
                val base=Headphones.state.value.shownNc ?: SonyProtocol.NcState(mode)
                val receipt=Headphones.request(base.copy(mode=mode))
                val ok=withTimeoutOrNull(8500){receipt.await()} ?: false
                if(latestTap.get()==generation && !ok) showFailure(ctx)
            } catch(e:Exception) {
                if(latestTap.get()==generation) showFailure(ctx)
            } finally {
                if(latestTap.get()==generation) {busyMode=null;refreshAll(ctx)}
                Headphones.release(foreground=false)
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_SET="ua.olex.sonyanc.SET_MODE"
        private const val EXTRA_MODE="mode"
        private val latestTap=AtomicLong()
        @Volatile private var busyMode:SonyProtocol.Mode?=null
        private data class Tile(val mode:SonyProtocol.Mode,val id:Int,val icon:Int,val label:Int,val normal:Int,val selectedIcon:Int,val normalIcon:Int)
        private val tiles=listOf(
            Tile(SonyProtocol.Mode.ANC,R.id.w_anc,R.id.w_anc_icon,R.id.w_anc_label,R.drawable.w_tile_start,R.drawable.w_icon_noise_control_off_on,R.drawable.w_icon_noise_control_off),
            Tile(SonyProtocol.Mode.AMBIENT,R.id.w_amb,R.id.w_amb_icon,R.id.w_amb_label,R.drawable.w_tile_middle,R.drawable.w_icon_hearing_on,R.drawable.w_icon_hearing),
            Tile(SonyProtocol.Mode.OFF,R.id.w_off,R.id.w_off_icon,R.id.w_off_label,R.drawable.w_tile_end,R.drawable.w_icon_power_settings_new_on,R.drawable.w_icon_power_settings_new)
        )
        private fun mediumWidth(ctx:Context)=maxOf(240f,(54f*ctx.resources.configuration.fontScale+16f)*3f+16f)

        private fun showFailure(ctx:Context) {
            Handler(Looper.getMainLooper()).post {Toast.makeText(ctx,"Could not connect. Tap a mode to retry.",Toast.LENGTH_SHORT).show()}
        }

        fun refreshAll(ctx:Context) {
            val mgr=AppWidgetManager.getInstance(ctx)
            val ids=mgr.getAppWidgetIds(ComponentName(ctx,ModeWidget::class.java))
            if(ids.isNotEmpty()) render(ctx,mgr,ids)
        }

        private fun render(ctx:Context,mgr:AppWidgetManager,ids:IntArray) {
            val current=Prefs.lastNc(ctx)?.mode
            for(id in ids) {
                val views=if(Build.VERSION.SDK_INT>=31) {
                    RemoteViews(mapOf(
                        SizeF(180f,64f) to viewsForSize(ctx,180f,current,busyMode),
                        SizeF(mediumWidth(ctx),64f) to viewsForSize(ctx,mediumWidth(ctx),current,busyMode)
                    ))
                } else {
                    val width=mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,300)
                    viewsForSize(ctx,width.toFloat(),current,busyMode)
                }
                mgr.updateAppWidget(id,views)
            }
        }

        /** The debug harness inflates these same RemoteViews at real launcher sizes. */
        internal fun viewsForSize(ctx:Context,width:Float,current:SonyProtocol.Mode?,pending:SonyProtocol.Mode?=null,interactive:Boolean=true):RemoteViews {
            val labels=width>=mediumWidth(ctx)
            val v=RemoteViews(ctx.packageName,R.layout.widget_modes)
            val density=ctx.resources.displayMetrics.density
            for(tile in tiles) {
                val mode=tile.mode
                val on=mode==current
                val applying=mode==pending
                v.setInt(tile.id,"setBackgroundResource",if(on) R.drawable.w_tile_on else tile.normal)
                v.setImageViewResource(tile.icon,if(on) tile.selectedIcon else tile.normalIcon)
                v.setTextColor(tile.label,ctx.getColor(if(on) R.color.w_on_text else R.color.w_off_text))
                v.setTextViewText(tile.label,mode.short+if(applying) "…" else "")
                v.setViewVisibility(tile.label,if(labels) View.VISIBLE else View.GONE)
                v.setViewPadding(tile.label,0,(4*density).toInt(),0,0)
                v.setContentDescription(tile.id,mode.label+if(applying) ", applying" else if(on) ", last used" else "")
                if(interactive) {
                    val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    val action=if(Prefs.deviceAddress(ctx)==null || !Headphones.hasPermission(ctx)) {
                        val setup=Intent(ctx,MainActivity::class.java).putExtra("hush.mode",mode.name)
                        PendingIntent.getActivity(ctx,mode.ordinal,setup,flags)
                    } else {
                        val set=Intent(ctx,ModeWidget::class.java).setAction(ACTION_SET).putExtra(EXTRA_MODE,mode.name)
                        PendingIntent.getBroadcast(ctx,mode.ordinal,set,flags)
                    }
                    v.setOnClickPendingIntent(tile.id,action)
                }
            }
            return v
        }
    }
}
