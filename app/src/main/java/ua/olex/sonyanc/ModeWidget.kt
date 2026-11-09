package ua.olex.sonyanc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
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

/**
 * Three controls only: ANC, Ambient, Off. A tap reconnects on demand; no persistent service or
 * notification. Each launcher size gets an exact layout from [WidgetLayout.modes]: a row with
 * labels under icons, a wide row with labels beside icons, icons only, or a column for tall slots.
 */
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
                // Felt from the pocket or lock screen: the same pulse as in the app once the
                // earbuds accept, a double click when they do not. Only the newest tap reports.
                if(latestTap.get()==generation && Prefs.haptics(ctx)) {
                    if(ok) Haptics.confirm(ctx,mode) else Haptics.reject(ctx)
                }
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
        private const val DIM_ALPHA=97 // 38 %, Material's disabled-content opacity
        private val latestTap=AtomicLong()
        @Volatile private var busyMode:SonyProtocol.Mode?=null
        private data class Tile(val mode:SonyProtocol.Mode,val id:Int,val icon:Int,val label:Int,val selectedIcon:Int,val normalIcon:Int)
        private val tiles=listOf(
            Tile(SonyProtocol.Mode.ANC,R.id.w_anc,R.id.w_anc_icon,R.id.w_anc_label,R.drawable.w_icon_noise_control_off_on,R.drawable.w_icon_noise_control_off),
            Tile(SonyProtocol.Mode.AMBIENT,R.id.w_amb,R.id.w_amb_icon,R.id.w_amb_label,R.drawable.w_icon_hearing_on,R.drawable.w_icon_hearing),
            Tile(SonyProtocol.Mode.OFF,R.id.w_off,R.id.w_off_icon,R.id.w_off_label,R.drawable.w_icon_power_settings_new_on,R.drawable.w_icon_power_settings_new)
        )
        /** Used before the launcher reports sizes: the system picks the best fit. */
        private val FALLBACK=listOf(SizeF(180f,64f),SizeF(300f,64f),SizeF(340f,100f),SizeF(420f,100f),SizeF(180f,220f),SizeF(420f,220f))

        private fun showFailure(ctx:Context) {
            val text=if(Prefs.linked(ctx)==false) "Earbuds aren't connected to this phone." else "Couldn't reach the earbuds. Tap a mode to retry."
            Handler(Looper.getMainLooper()).post {Toast.makeText(ctx,text,Toast.LENGTH_SHORT).show()}
        }

        fun refreshAll(ctx:Context) {
            val mgr=AppWidgetManager.getInstance(ctx)
            val ids=mgr.getAppWidgetIds(ComponentName(ctx,ModeWidget::class.java))
            if(ids.isNotEmpty()) render(ctx,mgr,ids)
        }

        private fun render(ctx:Context,mgr:AppWidgetManager,ids:IntArray) {
            val current=Prefs.lastNc(ctx)?.mode
            val linked=Prefs.linked(ctx)
            for(id in ids) {
                val sizes=Widgets.sizes(mgr.getAppWidgetOptions(id),FALLBACK)
                mgr.updateAppWidget(id,Widgets.forSizes(sizes){viewsFor(ctx,it.width,it.height,current,busyMode,linked=linked)})
            }
        }

        /** The exact views for one widget size. The debug gallery and the picker preview use this too. */
        internal fun viewsFor(ctx:Context,width:Float,height:Float,current:SonyProtocol.Mode?,pending:SonyProtocol.Mode?=null,
                              interactive:Boolean=true,linked:Boolean?=Prefs.linked(ctx),
                              battery:Pair<SonyProtocol.Battery,Long>?=Prefs.lastBattery(ctx)):RemoteViews {
        WidgetLayout.dashboard(width,height,Widgets.lineHeight(ctx,16f,"google-sans-text-medium"),
            Widgets.lineHeight(ctx,15f,"google-sans-text-medium"),Widgets.lineHeight(ctx,12f),
            SonyProtocol.Mode.entries.maxOf{Widgets.textWidth(ctx,it.short,14f)})?.let {
            return dashboard(ctx,it,current,pending,interactive,linked,battery)
        }
            val spec=WidgetLayout.modes(width,height,
                label=SonyProtocol.Mode.entries.maxOf{Widgets.textWidth(ctx,it.short,14f)},line=Widgets.lineHeight(ctx,14f))
            val layout=when(spec.variant) {
                WidgetLayout.ModeVariant.ROW -> R.layout.widget_modes
                WidgetLayout.ModeVariant.INLINE -> R.layout.widget_modes_inline
                WidgetLayout.ModeVariant.COLUMN -> R.layout.widget_modes_column
            }
            val column=spec.variant==WidgetLayout.ModeVariant.COLUMN
            val shapes=if(column) listOf(R.drawable.w_tile_top,R.drawable.w_tile_middle,R.drawable.w_tile_bottom)
                else listOf(R.drawable.w_tile_start,R.drawable.w_tile_middle,R.drawable.w_tile_end)
            val v=RemoteViews(ctx.packageName,layout)
            // Earbuds known to be in the case: controls stay tappable but read as unavailable.
            val away=linked==false
            v.setViewLayoutHeight(R.id.w_group,spec.group,TypedValue.COMPLEX_UNIT_DIP)
            bindTiles(ctx,v,shapes,current,pending,away,spec.labels,spec.icon,interactive)
            return v
        }

        private fun bindTiles(ctx:Context,v:RemoteViews,shapes:List<Int>,current:SonyProtocol.Mode?,pending:SonyProtocol.Mode?,
                              away:Boolean,labels:Boolean,icon:Float,interactive:Boolean) {
            tiles.forEachIndexed {index,tile ->
                val mode=tile.mode
                val on=mode==current
                val applying=mode==pending
                v.setInt(tile.id,"setBackgroundResource",if(on) R.drawable.w_tile_on else shapes[index])
                v.setImageViewResource(tile.icon,if(on) tile.selectedIcon else tile.normalIcon)
                v.setInt(tile.icon,"setImageAlpha",if(away) DIM_ALPHA else 255)
                v.setViewLayoutWidth(tile.icon,icon,TypedValue.COMPLEX_UNIT_DIP)
                v.setViewLayoutHeight(tile.icon,icon,TypedValue.COMPLEX_UNIT_DIP)
                val text=ctx.getColor(if(on) R.color.w_on_text else R.color.w_off_text)
                v.setTextColor(tile.label,if(away) (text and 0x00FFFFFF) or (DIM_ALPHA shl 24) else text)
                v.setTextViewText(tile.label,mode.short+if(applying) "…" else "")
                v.setViewVisibility(tile.label,if(labels) View.VISIBLE else View.GONE)
                v.setContentDescription(tile.id,mode.label+(if(applying) ", applying" else if(on) ", last used" else "")+if(away) ", earbuds not connected" else "")
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
        }

        /** "5 h 30 min left · case +12 h 20 min": the numbers bright and medium weight, the words quiet. */
        private fun listening(ctx:Context,b:SonyProtocol.Battery,mode:SonyProtocol.Mode?):CharSequence? {
            val buds=BatteryLife.budsMinutes(b,mode) ?: return null
            val case=BatteryLife.caseMinutes(b,mode)?.takeIf{it>=10}
            val strong=ctx.getColor(if(buds<60) R.color.w_low else R.color.w_off_text)
            val out=android.text.SpannableStringBuilder()
            fun number(text:String,color:Int) {
                val start=out.length
                out.append(text)
                out.setSpan(android.text.style.ForegroundColorSpan(color),start,out.length,0)
                out.setSpan(android.text.style.TypefaceSpan("google-sans-text-medium"),start,out.length,0)
            }
            number(BatteryLife.short(buds),strong)
            out.append(" left")
            if(case!=null) {out.append("   ·   case ");number("+"+BatteryLife.short(case),ctx.getColor(R.color.w_off_text))}
            return out
        }

        /**
         * Lock-screen hub and large home slots: name and status, three battery rings, the
         * listening time and the mode group, all from cached state.
         */
        private fun dashboard(ctx:Context,spec:WidgetLayout.DashboardSpec,current:SonyProtocol.Mode?,pending:SonyProtocol.Mode?,
                              interactive:Boolean,linked:Boolean?,last:Pair<SonyProtocol.Battery,Long>?):RemoteViews {
            val v=RemoteViews(ctx.packageName,R.layout.widget_dashboard)
            val away=linked==false
            val b=last?.first
            v.setTextViewText(R.id.w_title,Prefs.displayName(ctx) ?: Prefs.deviceName(ctx) ?: "WF-1000XM5")
            // One centered line: "WF-1000XM5 · Updated 14:32".
            v.setTextViewText(R.id.w_status,BatteryWidget.status(ctx,b,last?.second ?: 0L,linked,Prefs.lastSeen(ctx)).takeIf{it.isNotEmpty()}?.let{"·  $it"} ?: "")
            val bb=b ?: SonyProtocol.Battery()
            val spoken=listOf(
                BatteryWidget.bindRing(ctx,v,R.id.w_left_box,R.id.w_left_ring,R.id.w_left_art,R.id.w_left_value,R.id.w_left_label,R.id.w_left_charge,"Left",bb.left,bb.leftCharging,null,away,true),
                BatteryWidget.bindRing(ctx,v,R.id.w_case_box,R.id.w_case_ring,R.id.w_case_art,R.id.w_case_value,R.id.w_case_label,R.id.w_case_charge,"Case",bb.case,bb.caseCharging,null,away,true),
                BatteryWidget.bindRing(ctx,v,R.id.w_right_box,R.id.w_right_ring,R.id.w_right_art,R.id.w_right_value,R.id.w_right_label,R.id.w_right_charge,"Right",bb.right,bb.rightCharging,null,away,true),
            )
            v.setContentDescription(R.id.w_batteries,"Battery: "+spoken.joinToString(", "))
            val estimate=b?.let{listening(ctx,it,current)}
            v.setTextViewText(R.id.w_estimate,estimate ?: "")
            v.setViewVisibility(R.id.w_estimate,if(spec.estimate && estimate!=null) View.VISIBLE else View.GONE)
            bindTiles(ctx,v,listOf(R.drawable.w_tile_start,R.drawable.w_tile_middle,R.drawable.w_tile_end),current,pending,away,spec.labels,24f,interactive)
            if(interactive) {
                val open=Intent(ctx,MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val openApp=PendingIntent.getActivity(ctx,101,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                v.setOnClickPendingIntent(R.id.w_header,openApp)
                v.setOnClickPendingIntent(R.id.w_batteries,openApp)
            }
            return v
        }
    }
}
