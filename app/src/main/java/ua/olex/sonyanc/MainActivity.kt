@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.BatteryUnknown
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import ua.olex.sonyanc.SonyProtocol.Mode
import ua.olex.sonyanc.SonyProtocol.NcState

class MainActivity : ComponentActivity() {
    private val startTick = mutableIntStateOf(0)
    private val shortcutMode=mutableStateOf<Mode?>(null)
    private fun consumeIntent(intent:Intent?) {
        shortcutMode.value=intent?.getStringExtra("hush.mode")?.let {name->runCatching{Mode.valueOf(name)}.getOrNull()}
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);consumeIntent(intent)}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Headphones.init(this)
        consumeIntent(intent)
        enableEdgeToEdge()
        setContent {
            var dynamic by remember { mutableStateOf(Prefs.dynamicColor(this)) }
            AppTheme(dynamic) { App(startTick.intValue,dynamic,shortcutMode.value,{shortcutMode.value=null}) { dynamic=it; Prefs.setDynamicColor(this,it) } }
        }
    }
    override fun onStart() { super.onStart(); Headphones.hold(); startTick.intValue++ }
    override fun onStop() { Headphones.release(); super.onStop() }
}

@Composable
fun AppTheme(dynamic:Boolean=true,content:@Composable ()->Unit) {
    val ctx=LocalContext.current
    val dark=isSystemInDarkTheme()
    val colors=when {
        dynamic && Build.VERSION.SDK_INT>=31 -> if(dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(
            primary=Color(0xFFD4EF9C),onPrimary=Color(0xFF243500),primaryContainer=Color(0xFF354D12),onPrimaryContainer=Color(0xFFE1F6BD),
            surface=Color(0xFF11140F),surfaceContainerLowest=Color(0xFF0C0F0A),surfaceContainerLow=Color(0xFF191C16),surfaceContainer=Color(0xFF1D211A),surfaceContainerHigh=Color(0xFF282C24),
            onSurface=Color(0xFFE4E7DB),onSurfaceVariant=Color(0xFFC3C9B7),secondaryContainer=Color(0xFF353F29),onSecondaryContainer=Color(0xFFDFEACD),outlineVariant=Color(0xFF414936))
        else -> lightColorScheme(
            primary=Color(0xFF416516),onPrimary=Color.White,primaryContainer=Color(0xFFD0EFA5),onPrimaryContainer=Color(0xFF1D3500),
            surface=Color(0xFFF8FAF0),surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF1F4E8),surfaceContainer=Color(0xFFECEFE3),surfaceContainerHigh=Color(0xFFE6EADB),
            onSurface=Color(0xFF191D15),onSurfaceVariant=Color(0xFF444C39),secondaryContainer=Color(0xFFE1E9D2),onSecondaryContainer=Color(0xFF27331B),outlineVariant=Color(0xFFC4CDB7))
    }
    MaterialExpressiveTheme(colorScheme=colors,motionScheme=MotionScheme.expressive(),content=content)
}

@SuppressLint("MissingPermission")
@Composable
fun App(startTick:Int,dynamic:Boolean,shortcutMode:Mode?=null,onShortcutConsumed:()->Unit={},onDynamic:(Boolean)->Unit) {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    val device by Headphones.state.collectAsStateWithLifecycle()
    val status=device.status
    var granted by remember { mutableStateOf(Headphones.hasPermission(ctx)) }
    var saved by remember { mutableStateOf(Prefs.deviceAddress(ctx)) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetParent by rememberSaveable { mutableStateOf<String?>(null) }
    fun openSheet(name:String) {sheetParent=sheet;sheet=name}
    fun closeSheet() {sheet=sheetParent;sheetParent=null}
    fun refreshInfo() {scope.launch {Headphones.refreshInfo()}}
    var displayName by rememberSaveable { mutableStateOf(Prefs.displayName(ctx) ?: Prefs.deviceName(ctx) ?: "WF-1000XM5") }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var nameDraft by rememberSaveable { mutableStateOf("") }
    if(renaming) AlertDialog(onDismissRequest={renaming=false},title={Text("Device name")},
        text={OutlinedTextField(value=nameDraft,onValueChange={nameDraft=it.take(40)},singleLine=true,label={Text("Name")})},
        confirmButton={TextButton(enabled=nameDraft.isNotBlank(),onClick={displayName=nameDraft.trim();Prefs.setDisplayName(ctx,displayName);renaming=false}) {Text("Save")}},
        dismissButton={TextButton(onClick={renaming=false}) {Text("Cancel")}})
    var haptics by remember { mutableStateOf(Prefs.haptics(ctx)) }
    var devices by remember { mutableStateOf(emptyList<android.bluetooth.BluetoothDevice>()) }
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted=it}
    val widgetManager=remember {AppWidgetManager.getInstance(ctx)}
    fun pinWidget() { if(widgetManager.isRequestPinAppWidgetSupported) widgetManager.requestPinAppWidget(ComponentName(ctx,ModeWidget::class.java),null,null) }
    fun bluetooth() {ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))}
    LaunchedEffect(startTick,granted,saved) {
        granted=Headphones.hasPermission(ctx)
        if(granted) {
            devices=Headphones.bondedDevices(ctx)
            if(saved==null && devices.size==1) {
                val dev=devices.single()
                Headphones.selectDevice(dev.address,dev.name)
                saved=dev.address
            } else if(saved!=null) Headphones.connectAndSync()
        }
    }
    LaunchedEffect(shortcutMode,granted,saved) {
        if(shortcutMode!=null && granted && saved!=null) {
            Headphones.request((Headphones.state.value.nc ?: NcState(shortcutMode)).copy(mode=shortcutMode))
            onShortcutConsumed()
        }
    }
    val codec=device.codec
    val codecRefreshing=device.codecRefreshing
    val batteryRefreshing=device.batteryRefreshing
    HushScreen(status,device.nc,device.pending,device.ncConfirmed,device.error,device.battery,granted,saved!=null,
        batteryRefreshing=batteryRefreshing,batteryFresh=device.liveBatteryParts.isNotEmpty(),batteryParts=device.liveBatteryParts,
        codec=codec,codecRefreshing=codecRefreshing,onBattery={refreshInfo()},
        onConnection={openSheet("connection")},onEarbuds={openSheet("earbuds")},
        onMode={Headphones.request(it)},onSettings={openSheet("earbuds")},onEqualizer={openSheet("equalizer")},
        onConnect={scope.launch {Headphones.connectAndSync()}},
        onPermission={if(Build.VERSION.SDK_INT>=31) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)},
        onBluetooth={bluetooth()},onPick={openSheet("settings")},haptics=haptics,
        onAppSettings={openSheet("settings")},deviceName=displayName,onRename={nameDraft=displayName;renaming=true},onDisconnect={Headphones.disconnect()})
    if(sheet!=null) ModalBottomSheet(sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),onDismissRequest={sheet=null;sheetParent=null},containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
        if(sheet=="connection") ConnectionSheet(status,codec,codecRefreshing || batteryRefreshing,{refreshInfo()},{bluetooth()},::closeSheet,startTick)
        else if(sheet=="deviceControls" || sheet=="earbuds") AdvancedSettingsSheet(::closeSheet,{openSheet("settings")},startTick)
        else if(sheet=="diagnostics") Diagnostics(::closeSheet)
        else if(sheet=="equalizer") EqualizerSheet(::closeSheet,startTick)
        else {
            Column(Modifier.padding(horizontal=24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                SheetHeader("Settings",::closeSheet)
                GroupLabel("DEVICE")
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    devices.forEach {device ->
                        SettingsRow(Glyph.EARBUDS,device.name ?: "WF-1000XM5",if(saved==device.address) "Selected" else "Paired",onClick={
                            scope.launch {Headphones.selectDevice(device.address,device.name);saved=device.address;sheet=null;Headphones.connectAndSync()}
                        },trailing={if(saved==device.address) HushIcon(Glyph.CHECK,color=MaterialTheme.colorScheme.primary) else HushIcon(Glyph.ARROW)})
                    }
                    SettingsRow(Glyph.EARBUDS,"Device name",displayName,onClick={nameDraft=displayName;renaming=true})
                    SettingsRow(Glyph.BLUETOOTH,"Bluetooth settings",onClick={bluetooth()})
                }
                GroupLabel("APPEARANCE & FEEDBACK")
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    SettingsRow(Glyph.PALETTE,"Wallpaper colors",trailing={Switch(checked=dynamic,onCheckedChange=onDynamic,modifier=Modifier.semantics{contentDescription="Wallpaper colors"})})
                    SettingsRow(Glyph.HAPTICS,"Haptics",trailing={Switch(checked=haptics,onCheckedChange={haptics=it;Prefs.setHaptics(ctx,it)},modifier=Modifier.semantics{contentDescription="Haptics"})})
                }
                GroupLabel("TOOLS")
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    if(Build.VERSION.SDK_INT>=33) SettingsRow(Glyph.QUIET,"Add Quick Settings tile",onClick={
                        ctx.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                            ComponentName(ctx,NoiseTileService::class.java),"Noise control",
                            android.graphics.drawable.Icon.createWithResource(ctx,R.drawable.w_icon_noise_control_off),ctx.mainExecutor) {}
                    })
                    if(widgetManager.isRequestPinAppWidgetSupported) SettingsRow(Glyph.WIDGET,"Add home screen widget",onClick={pinWidget()})
                    SettingsRow(Glyph.EQ,"Equalizer",onClick={openSheet("equalizer")})
                    SettingsRow(Glyph.TUNE,"Earbud settings",onClick={openSheet("earbuds")})
                    SettingsRow(Glyph.INFO,"Connection diagnostics",onClick={openSheet("diagnostics")})
                }
                Text("Hush 0.9.0",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=8.dp))
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Pixel-Buds-style screen: centered device header, battery rings, big actions, connected mode group, grouped list. */
@Composable
fun HushScreen(
    status:Headphones.Status,nc:NcState?,pending:NcState?,fresh:Boolean,error:String?,battery:SonyProtocol.Battery?,
    granted:Boolean,hasDevice:Boolean,onMode:(NcState)->Unit,onSettings:()->Unit,onConnect:()->Unit,
    onPermission:()->Unit,onBluetooth:()->Unit,onPick:()->Unit,onEqualizer:()->Unit={},onEarbuds:()->Unit={},onConnection:()->Unit={},haptics:Boolean=true,
    batteryRefreshing:Boolean=false,batteryFresh:Boolean=true,onBattery:()->Unit={},
    codec:CodecProtocol.State?=null,codecRefreshing:Boolean=false,
    deviceName:String="WF-1000XM5",onRename:()->Unit={},onDisconnect:()->Unit={},onAppSettings:()->Unit=onSettings,batteryParts:Set<Int> = if(batteryFresh) setOf(0,1,2) else emptySet(),
) {
    val cs=MaterialTheme.colorScheme
    val dark=isSystemInDarkTheme()
    val background=if(dark) cs.surfaceContainerLow else cs.surfaceContainer
    val card=if(dark) cs.surfaceContainerHigh else cs.surfaceContainerLowest
    val state=pending ?: nc
    val ready=status==Headphones.Status.CONNECTED
    val feedback=LocalHapticFeedback.current
    fun choose(value:NcState) {
        if(haptics) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onMode(value)
    }
    Scaffold(containerColor=background,topBar={
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start=24.dp,end=12.dp,top=8.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
            HushMark(Modifier.size(28.dp));Spacer(Modifier.width(10.dp))
            Text("Hush",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            IconButton(onClick=onSettings,modifier=Modifier.size(48.dp).semantics{contentDescription="Settings"}) {
                HushIcon(Glyph.SETTINGS,color=cs.onSurfaceVariant)
            }
        }
    }) {insets ->
        Column(Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp),
            verticalArrangement=Arrangement.spacedBy(22.dp)) {

            // ---- header: name + status ----
            Column(Modifier.fillMaxWidth().padding(top=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(deviceName,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Medium,textAlign=TextAlign.Center,modifier=Modifier.weight(1f,false))
                    IconButton(onClick=onRename,modifier=Modifier.size(40.dp).semantics{contentDescription="Rename device"}) {Icon(Icons.Rounded.Edit,contentDescription=null,modifier=Modifier.size(18.dp),tint=cs.onSurfaceVariant)}
                }
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    val line=when(status) {
                        Headphones.Status.CONNECTED -> "Active"+(codec?.takeIf{it.isKnown}?.let{" · ${it.label}"} ?: "")
                        Headphones.Status.CONNECTING -> "Connecting…"
                        Headphones.Status.FAILED -> "Not connected"
                        Headphones.Status.DISCONNECTED -> "Disconnected"
                    }
                    Text(line,style=MaterialTheme.typography.bodyLarge,color=cs.onSurfaceVariant,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                    if(status==Headphones.Status.CONNECTING || batteryRefreshing) LoadingIndicator(Modifier.size(20.dp))
                }
            }

            if(!granted) {
                SetupCard("Bluetooth access","Allow Hush to connect to your WF-1000XM5.","Allow Bluetooth",Glyph.BLUETOOTH,onPermission)
            } else if(!hasDevice) {
                SetupCard("Connect your earbuds","Pair WF-1000XM5 in Android Bluetooth settings.","Select device",Glyph.EARBUDS,onPick)
            } else {
                // ---- battery rings ----
                val b=battery ?: SonyProtocol.Battery()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable(enabled=ready,onClick=onBattery)
                        .semantics{contentDescription="Battery. Tap to refresh"},
                    horizontalArrangement=Arrangement.SpaceEvenly) {
                    BatteryRing("Left",b.left,b.leftCharging,0 in batteryParts) {EarbudArt(Alignment.CenterStart)}
                    BatteryRing("Case",b.case,b.caseCharging,2 in batteryParts) {CaseArt()}
                    BatteryRing("Right",b.right,b.rightCharging,1 in batteryParts) {EarbudArt(Alignment.CenterEnd)}
                }

                // ---- big actions ----
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                    BigAction(Glyph.BLUETOOTH,"Bluetooth",onClick=onBluetooth)
                    if(ready) BigAction(Glyph.CLOSE,"Disconnect",onClick=onDisconnect)
                    else BigAction(Glyph.REFRESH,if(status==Headphones.Status.CONNECTING) "Connecting…" else "Connect",
                        enabled=status!=Headphones.Status.CONNECTING,onClick=onConnect)
                }

                // ---- active noise control ----
                Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    SectionLabel("Active noise control")
                    Row(Modifier.fillMaxWidth().selectableGroup(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        Mode.entries.forEachIndexed {index,mode ->
                            ModeSegment(mode,index,Mode.entries.size,state?.mode==mode,pending?.mode==mode,Modifier.weight(1f)) {
                                choose((state ?: NcState(mode)).copy(mode=mode))
                            }
                        }
                    }
                    AnimatedVisibility(state?.mode==Mode.AMBIENT,
                        enter=expandVertically(animationSpec=MaterialTheme.motionScheme.defaultSpatialSpec(),expandFrom=Alignment.Top)+fadeIn(),
                        exit=shrinkVertically(animationSpec=MaterialTheme.motionScheme.defaultSpatialSpec(),shrinkTowards=Alignment.Top)+fadeOut()) {
                        Column {
                            Spacer(Modifier.height(4.dp))
                            AmbientPanel((state ?: NcState(Mode.AMBIENT)).copy(mode=Mode.AMBIENT),card,::choose)
                        }
                    }
                }

                // ---- more controls ----
                Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    SectionLabel("More controls")
                    val rows=listOf(
                        Triple(Glyph.EQ,"Equalizer","") to onEqualizer,
                        Triple(Glyph.TUNE,"Earbud settings","") to onEarbuds,
                        Triple(Glyph.BLUETOOTH,"Connection","Codec · "+(if(ready) codec?.label ?: if(codecRefreshing) "reading…" else "—" else "—")) to onConnection,
                        Triple(Glyph.SETTINGS,"App settings","") to onAppSettings,
                    )
                    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        rows.forEachIndexed {i,(info,action)->
                            ListCard(info.first,info.second,info.third,i,rows.size,card,action)
                        }
                    }
                }
            }

            AnimatedVisibility(error!=null,enter=fadeIn()+expandVertically(),exit=fadeOut()+shrinkVertically()) {
                Surface(shape=RoundedCornerShape(24.dp),color=cs.errorContainer) {
                    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(error.orEmpty(),style=MaterialTheme.typography.bodyMedium,color=cs.onErrorContainer,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick=onConnect){Text("Retry",color=cs.onErrorContainer)}
                            TextButton(onClick=onBluetooth){Text("Bluetooth settings",color=cs.onErrorContainer)}
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionLabel(text:String) {
    Text(text,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(start=8.dp))
}

/** Ring with a status chip in the top gap, like Pixel's device details. */
@Composable
private fun BatteryRing(label:String,value:Int?,charging:Boolean,fresh:Boolean,art:@Composable ()->Unit) {
    val cs=MaterialTheme.colorScheme
    val dark=isSystemInDarkTheme()
    val good=if(dark) Color(0xFF43C465) else Color(0xFF1E8E3E)
    val onGood=if(dark) Color(0xFF0B5427) else Color.White
    val (ringColor,chipContent)=when {
        value==null -> cs.outlineVariant to cs.onSurfaceVariant
        value<=20 -> cs.error to cs.onError
        else -> good to onGood
    }
    val color=if(fresh || value==null) ringColor else ringColor.copy(alpha=.55f)
    val track=cs.surfaceContainerHighest
    val progress by animateFloatAsState((value ?: 0)/100f,animationSpec=tween(700,easing=FastOutSlowInEasing),label="battery $label")
    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp),
        modifier=Modifier.semantics(mergeDescendants=true) {
            contentDescription="$label battery"
            stateDescription=(value?.let{"$it percent"} ?: "Unavailable")+if(charging) ", charging" else if(!fresh && value!=null) ", last known" else ""
        }) {
        Box(Modifier.size(100.dp),contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(top=6.dp,start=6.dp,end=6.dp,bottom=6.dp)) {
                val stroke=6.dp.toPx()
                val gap=44f
                val start=-90f+gap/2
                val sweep=360f-gap
                val inset=Offset(stroke/2,stroke/2)
                val arcSize=Size(size.width-stroke,size.height-stroke)
                drawArc(track,start,sweep,false,topLeft=inset,size=arcSize,style=Stroke(stroke,cap=StrokeCap.Round))
                if(progress>0f) drawArc(color,start,sweep*progress,false,topLeft=inset,size=arcSize,style=Stroke(stroke,cap=StrokeCap.Round))
            }
            Box(Modifier.size(54.dp),contentAlignment=Alignment.Center){art()}
            Surface(shape=CircleShape,color=if(value==null) track else color,modifier=Modifier.align(Alignment.TopCenter).size(26.dp)) {
                Box(contentAlignment=Alignment.Center) {
                    Icon(when {
                        value==null -> Icons.Rounded.BatteryUnknown
                        charging -> Icons.Rounded.BatteryChargingFull
                        value<=20 -> Icons.Rounded.BatteryAlert
                        else -> Icons.Rounded.BatteryFull
                    },contentDescription=null,tint=chipContent,modifier=Modifier.size(16.dp))
                }
            }
        }
        Text("$label: "+(value?.let{"$it%"} ?: "—"),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium,
            color=if(value!=null && value<=20) cs.error else cs.onSurface)
    }
}

/** One bud cropped out of the product shot (left or right half). */
@Composable
private fun EarbudArt(alignment:Alignment) {
    Image(painterResource(R.drawable.wf1000xm5_silver),contentDescription=null,contentScale=ContentScale.Crop,alignment=Alignment.CenterEnd,
        modifier=Modifier.fillMaxSize().graphicsLayer{scaleX=if(alignment==Alignment.CenterStart) 1f else -1f}.clip(RoundedCornerShape(4.dp)))
}

/** Transparent product artwork; shares the earbuds' silver finish. */
@Composable
private fun CaseArt() {
    Image(painterResource(R.drawable.wf1000xm5_case),contentDescription=null,
        contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize())
}

@Composable
private fun BigAction(glyph:Glyph,label:String,enabled:Boolean=true,onClick:()->Unit) {
    val cs=MaterialTheme.colorScheme
    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Surface(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(50),color=cs.secondaryContainer,contentColor=cs.onSecondaryContainer,
            modifier=Modifier.size(width=76.dp,height=56.dp).semantics{contentDescription=label}) {
            Box(contentAlignment=Alignment.Center){HushIcon(glyph,Modifier.size(26.dp),if(enabled) cs.onSecondaryContainer else cs.onSecondaryContainer.copy(alpha=.5f))}
        }
        Text(label,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium)
    }
}

/** Connected button group segment: outer corners round, inner corners tight; selected morphs to a full pill. */
@Composable
private fun ModeSegment(mode:Mode,index:Int,count:Int,selected:Boolean,applying:Boolean,modifier:Modifier,onClick:()->Unit) {
    val cs=MaterialTheme.colorScheme
    val interaction=remember {MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val spec=spring<androidx.compose.ui.unit.Dp>(dampingRatio=Spring.DampingRatioMediumBouncy,stiffness=Spring.StiffnessMediumLow)
    val outer=32.dp; val inner=10.dp
    fun target(isOuter:Boolean)=when {pressed -> 14.dp; selected -> outer; isOuter -> outer; else -> inner}
    val start by animateDpAsState(target(index==0),spec,label="start")
    val end by animateDpAsState(target(index==count-1),spec,label="end")
    val bg by animateColorAsState(if(selected) cs.primary else cs.surfaceContainerHighest,tween(200),label="bg")
    val fg by animateColorAsState(if(selected) cs.onPrimary else cs.onSurfaceVariant,tween(200),label="fg")
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(64.dp)
            .clip(RoundedCornerShape(topStart=start,bottomStart=start,topEnd=end,bottomEnd=end))
            .background(bg)
            .selectable(selected=selected,interactionSource=interaction,indication=ripple(),role=Role.RadioButton,onClick=onClick)
            .semantics{contentDescription=mode.label},
            contentAlignment=Alignment.Center) {
            if(applying) LoadingIndicator(Modifier.size(28.dp),color=fg)
            else HushIcon(when(mode){Mode.ANC->Glyph.QUIET;Mode.AMBIENT->Glyph.AMBIENT;Mode.OFF->Glyph.OFF},Modifier.size(26.dp),fg)
        }
        Text(mode.short,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium,textAlign=TextAlign.Center,maxLines=2)
    }
}

/** Grouped list item: big radius on the outside of the group, small radius between items. */
@Composable
private fun ListCard(glyph:Glyph,title:String,subtitle:String,index:Int,count:Int,color:Color,onClick:()->Unit) {
    val cs=MaterialTheme.colorScheme
    val big=24.dp; val small=4.dp
    val shape=RoundedCornerShape(
        topStart=if(index==0) big else small,topEnd=if(index==0) big else small,
        bottomStart=if(index==count-1) big else small,bottomEnd=if(index==count-1) big else small)
    Surface(onClick=onClick,shape=shape,color=color,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal=20.dp,vertical=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            HushIcon(glyph,color=cs.onSurface)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Medium)
                if(subtitle.isNotBlank()) Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AmbientPanel(state:NcState,color:Color,onChange:(NcState)->Unit) {
    val cs=MaterialTheme.colorScheme
    var level by remember {mutableFloatStateOf(state.level.toFloat())}
    var dragging by remember {mutableStateOf(false)}
    LaunchedEffect(state.level,dragging) {if(!dragging) level=state.level.toFloat()}
    Surface(shape=RoundedCornerShape(24.dp),color=color,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Ambient level",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                Text("${level.roundToInt()} / 20",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Medium)
            }
            Slider(value=level,onValueChange={dragging=true;level=it.roundToInt().toFloat()},valueRange=0f..20f,
                onValueChangeFinished={onChange(state.copy(level=level.roundToInt()));dragging=false},
                modifier=Modifier.fillMaxWidth().semantics{contentDescription="Ambient sound level";stateDescription="${level.roundToInt()} of 20"})
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Low" to 5,"Medium" to 12,"High" to 20).forEachIndexed {index,(label,value)->
                    SegmentedButton(selected=state.level==value,onClick={onChange(state.copy(level=value))},shape=SegmentedButtonDefaults.itemShape(index,3),icon={}) {Text(label)}
                }
            }
            HorizontalDivider(color=cs.outlineVariant.copy(alpha=.5f))
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Voice focus",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                Switch(checked=state.voice,onCheckedChange={onChange(state.copy(voice=it))},thumbContent=if(state.voice) ({HushIcon(Glyph.CHECK,Modifier.size(16.dp),cs.primary)}) else null,
                    modifier=Modifier.semantics{contentDescription="Voice focus"})
            }
        }
    }
}

@Composable
private fun SetupCard(title:String,body:String,action:String,glyph:Glyph,onClick:()->Unit) {
    val cs=MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Surface(shape=RoundedCornerShape(28.dp),color=cs.primaryContainer,modifier=Modifier.size(80.dp)) {
            Box(contentAlignment=Alignment.Center){HushIcon(glyph,Modifier.size(36.dp),cs.onPrimaryContainer)}
        }
        Text(title,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
        Text(body,style=MaterialTheme.typography.bodyLarge,color=cs.onSurfaceVariant)
        Button(onClick=onClick,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Text(action)}
    }
}

@Composable
private fun GroupLabel(text:String) {
    Text(text,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.SemiBold,letterSpacing=1.5.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(start=4.dp,top=4.dp))
}

@Composable
private fun SettingsRow(glyph:Glyph,title:String,subtitle:String?=null,onClick:(()->Unit)?=null,trailing:(@Composable ()->Unit)?=null) {
    val cs=MaterialTheme.colorScheme
    val content:@Composable ()->Unit={
        Row(Modifier.padding(horizontal=20.dp,vertical=16.dp).heightIn(min=32.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            HushIcon(glyph,color=cs.onSurfaceVariant)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(title,style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.Medium)
                subtitle?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=cs.onSurfaceVariant)}
            }
            if(trailing!=null) trailing() else if(onClick!=null) HushIcon(Glyph.ARROW,color=cs.onSurfaceVariant)
        }
    }
    if(onClick!=null) Surface(onClick=onClick,shape=RoundedCornerShape(22.dp),color=cs.surfaceContainer,modifier=Modifier.fillMaxWidth(),content=content)
    else Surface(shape=RoundedCornerShape(22.dp),color=cs.surfaceContainer,modifier=Modifier.fillMaxWidth(),content=content)
}

@Composable
private fun SheetHeader(title:String,onBack:()->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        IconButton(onClick=onBack,modifier=Modifier.semantics{contentDescription="Back"}) {HushIcon(Glyph.BACK)}
        Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
    }
}

@Composable
private fun ConnectionSheet(status:Headphones.Status,codec:CodecProtocol.State?,busy:Boolean,onRefresh:()->Unit,onBluetooth:()->Unit,onBack:()->Unit,resumeTick:Int=0) {
    val cs=MaterialTheme.colorScheme
    val firmware=Headphones.state.collectAsStateWithLifecycle().value.firmware
    val scope=rememberCoroutineScope()
    var turningOff by remember {mutableStateOf(false)}
    var confirmOff by remember {mutableStateOf(false)}
    LaunchedEffect(resumeTick) {Headphones.readFirmware()}
    if(confirmOff) AlertDialog(onDismissRequest={confirmOff=false},title={Text("Turn off earbuds?")},
        text={Text("Put them in the case and take them out to turn them on again.")},
        confirmButton={TextButton(onClick={confirmOff=false;scope.launch{turningOff=true;try{Headphones.powerOff()}finally{turningOff=false}}}) {Text("Turn off")}},
        dismissButton={TextButton(onClick={confirmOff=false}) {Text("Cancel")}})
    Column(Modifier.padding(horizontal=24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SheetHeader("Connection",onBack)
        Surface(shape=RoundedCornerShape(24.dp),color=cs.surfaceContainer,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("WF-1000XM5",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                Text(when(status){Headphones.Status.CONNECTED->"Connected";Headphones.Status.CONNECTING->"Connecting…";Headphones.Status.FAILED->"Connection failed";else->"Disconnected"},style=MaterialTheme.typography.bodyMedium,color=cs.onSurfaceVariant)
                HorizontalDivider(color=cs.outlineVariant)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Audio codec",style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f))
                    Text(if(status==Headphones.Status.CONNECTED) codec?.label ?: if(busy) "Reading…" else "Not reported" else "—",
                        style=MaterialTheme.typography.titleMedium,color=cs.primary,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                }
            }
        }
        FilledTonalButton(onClick=onRefresh,enabled=!busy && status!=Headphones.Status.CONNECTING,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            if(busy) LoadingIndicator(Modifier.size(24.dp)) else HushIcon(Glyph.REFRESH,color=cs.onSecondaryContainer)
            Spacer(Modifier.width(8.dp));Text(if(busy) "Refreshing…" else if(status==Headphones.Status.CONNECTED) "Refresh" else "Reconnect")
        }
        firmware?.let {SettingsRow(Glyph.INFO,"Firmware",it)}
        SettingsRow(Glyph.BLUETOOTH,"Android Bluetooth settings","Multipoint · spatial audio · LE Audio",onClick=onBluetooth)
        if(status==Headphones.Status.CONNECTED) OutlinedButton(enabled=!turningOff,onClick={confirmOff=true},modifier=Modifier.fillMaxWidth()) {Text("Turn off earbuds")}
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Diagnostics(onBack:()->Unit) {
    val log by Headphones.log.collectAsState()
    val scope=rememberCoroutineScope()
    val clipboard=LocalClipboardManager.current
    var querying by remember {mutableStateOf(false)}
    Column(Modifier.padding(horizontal=24.dp).verticalScroll(rememberScrollState()).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SheetHeader("Diagnostics",onBack)
        Text("Close Sony Sound Connect if it is using the Bluetooth control connection.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick={clipboard.setText(AnnotatedString(log.joinToString("\n")))}) {
                HushIcon(Glyph.COPY,color=MaterialTheme.colorScheme.onSecondaryContainer);Spacer(Modifier.width(8.dp));Text("Copy log")
            }
            TextButton(onClick={Headphones.clearLog()}){Text("Clear")}
        }
        OutlinedButton(enabled=!querying,onClick={scope.launch {querying=true;try{Headphones.queryBattery()}finally{querying=false}}}) {Text(if(querying) "Querying…" else "Refresh battery")}
        Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth().height(260.dp)) {
            val scroll=rememberScrollState()
            LaunchedEffect(log.size){scroll.scrollTo(scroll.maxValue)}
            Text(log.joinToString("\n").ifEmpty{"No messages"},fontFamily=FontFamily.Monospace,fontSize=11.sp,modifier=Modifier.verticalScroll(scroll).padding(16.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EqualizerSheet(onBack:()->Unit,resumeTick:Int=0) {
    val eq by Headphones.equalizer.collectAsState()
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(true)}
    var message by remember {mutableStateOf<String?>(null)}
    var revision by remember {mutableIntStateOf(0)}
    fun load() {scope.launch {busy=true;message=null;try {if(Headphones.readEqualizer()==null) message="No equalizer response. Reconnect the earbuds and retry."} finally {busy=false;revision++}}}
    LaunchedEffect(resumeTick) {busy=true;message=null;try {if(Headphones.readEqualizer()==null) message="No equalizer response. Reconnect the earbuds and retry."} finally {busy=false;revision++}}
    fun update(preset:Int?=null,bands:List<Int>?=null) {scope.launch {busy=true;message=null;try {if(!Headphones.writeEqualizer(preset,bands)) message="Change not confirmed. Your earbuds may still use the previous setting."} finally {busy=false;revision++}}}
    Column(Modifier.padding(horizontal=24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {SheetHeader("Equalizer",onBack)}
            if(busy) LoadingIndicator(Modifier.size(32.dp))
        }
        message?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)}
        if(eq==null) {
            if(!busy) OutlinedButton(onClick={load()}){Text("Retry")}
        } else {
            var expanded by remember {mutableStateOf(false)}
            ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={if(!busy) expanded=it}) {
                OutlinedTextField(value=EqualizerProtocol.presets[eq!!.preset].orEmpty(),onValueChange={},readOnly=true,label={Text("Preset")},
                    trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)},shape=RoundedCornerShape(20.dp),
                    modifier=Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                    EqualizerProtocol.presets.forEach {(code,name)->
                        DropdownMenuItem(text={Text(name)},onClick={expanded=false;update(preset=code)})
                    }
                }
            }
            if(eq!!.bands.size==6) {
                OutlinedButton(enabled=!busy,onClick={update(bands=List(6){0})}) {Text("Reset bands")}
                var bands by remember(eq,revision){mutableStateOf(eq!!.bands)}
                Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        listOf("Clear Bass","400 Hz","1 kHz","2.5 kHz","6.3 kHz","16 kHz").forEachIndexed {index,label->
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(label,style=MaterialTheme.typography.labelLarge,modifier=Modifier.width(76.dp))
                                Slider(value=bands[index].toFloat(),onValueChange={v->bands=bands.toMutableList().also{it[index]=v.roundToInt()}},valueRange=-10f..10f,enabled=!busy,
                                    onValueChangeFinished={update(bands=bands)},modifier=Modifier.weight(1f).semantics{contentDescription=label})
                                Text(if(bands[index]>0) "+${bands[index]}" else bands[index].toString(),style=MaterialTheme.typography.labelLarge,textAlign=TextAlign.End,modifier=Modifier.width(32.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
