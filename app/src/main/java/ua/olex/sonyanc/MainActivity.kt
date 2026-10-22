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
import androidx.compose.material.icons.rounded.BatteryChargingFull
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
    val status by Headphones.status.collectAsState()
    val nc by Headphones.nc.collectAsState()
    val pending by Headphones.pending.collectAsState()
    val fresh by Headphones.fresh.collectAsState()
    val error by Headphones.error.collectAsState()
    val battery by Headphones.battery.collectAsState()
    val batteryRefreshing by Headphones.batteryRefreshing.collectAsState()
    val batteryFresh by Headphones.batteryFresh.collectAsState()
    val codec by Headphones.codec.collectAsState()
    val codecRefreshing by Headphones.codecRefreshing.collectAsState()
    var granted by remember { mutableStateOf(Headphones.hasPermission(ctx)) }
    var saved by remember { mutableStateOf(Prefs.deviceAddress(ctx)) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetParent by rememberSaveable { mutableStateOf<String?>(null) }
    fun openSheet(name:String) {sheetParent=sheet;sheet=name}
    fun closeSheet() {sheet=sheetParent;sheetParent=null}
    fun refreshInfo() {scope.launch {
        if(Headphones.status.value==Headphones.Status.CONNECTED || Headphones.connectAndSync()) {
            Headphones.queryCodec()
            Headphones.queryBattery()
        }
    }}
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
            Headphones.request((Headphones.nc.value ?: NcState(shortcutMode)).copy(mode=shortcutMode))
            onShortcutConsumed()
        }
    }
    HushScreen(status,nc,pending,fresh,error,battery,granted,saved!=null,
        batteryRefreshing=batteryRefreshing,batteryFresh=batteryFresh,
        codec=codec,codecRefreshing=codecRefreshing,onBattery={refreshInfo()},
        onConnection={openSheet("connection")},onEarbuds={openSheet("earbuds")},
        onMode={Headphones.request(it)},onSettings={openSheet("settings")},onEqualizer={openSheet("equalizer")},
        onConnect={scope.launch {Headphones.connectAndSync()}},
        onPermission={if(Build.VERSION.SDK_INT>=31) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)},
        onBluetooth={bluetooth()},onPick={openSheet("settings")},haptics=haptics)
    if(sheet!=null) ModalBottomSheet(onDismissRequest={sheet=null;sheetParent=null},containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
        if(sheet=="connection") ConnectionSheet(status,codec,codecRefreshing || batteryRefreshing,{refreshInfo()},{bluetooth()},::closeSheet)
        else if(sheet=="diagnostics") Diagnostics(::closeSheet)
        else if(sheet=="equalizer") EqualizerSheet(::closeSheet)
        else if(sheet=="earbuds") EarbudSettingsSheet(::closeSheet)
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
                    SettingsRow(Glyph.BLUETOOTH,"Bluetooth settings",onClick={bluetooth()})
                }
                GroupLabel("APPEARANCE & FEEDBACK")
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    SettingsRow(Glyph.PALETTE,"Wallpaper colors",trailing={Switch(checked=dynamic,onCheckedChange=onDynamic,modifier=Modifier.semantics{contentDescription="Wallpaper colors"})})
                    SettingsRow(Glyph.HAPTICS,"Haptics",trailing={Switch(checked=haptics,onCheckedChange={haptics=it;Prefs.setHaptics(ctx,it)},modifier=Modifier.semantics{contentDescription="Haptics"})})
                }
                GroupLabel("TOOLS")
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    if(widgetManager.isRequestPinAppWidgetSupported) SettingsRow(Glyph.WIDGET,"Add home screen widget",onClick={pinWidget()})
                    SettingsRow(Glyph.EQ,"Equalizer",onClick={openSheet("equalizer")})
                    SettingsRow(Glyph.TUNE,"Earbud settings",onClick={openSheet("earbuds")})
                    SettingsRow(Glyph.INFO,"Connection diagnostics",onClick={openSheet("diagnostics")})
                }
                Text("Hush 0.6.2",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=8.dp))
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun HushScreen(
    status:Headphones.Status,nc:NcState?,pending:NcState?,fresh:Boolean,error:String?,battery:SonyProtocol.Battery?,
    granted:Boolean,hasDevice:Boolean,onMode:(NcState)->Unit,onSettings:()->Unit,onConnect:()->Unit,
    onPermission:()->Unit,onBluetooth:()->Unit,onPick:()->Unit,onEqualizer:()->Unit={},onEarbuds:()->Unit={},onConnection:()->Unit={},haptics:Boolean=true,
    batteryRefreshing:Boolean=false,batteryFresh:Boolean=true,onBattery:()->Unit={},
    codec:CodecProtocol.State?=null,codecRefreshing:Boolean=false,
) {
    val cs=MaterialTheme.colorScheme
    val state=pending ?: nc
    val feedback=LocalHapticFeedback.current
    fun choose(value:NcState) {
        if(haptics) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onMode(value)
    }
    Scaffold(containerColor=cs.surface,topBar={
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=24.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
            HushMark(Modifier.size(36.dp));Spacer(Modifier.width(10.dp))
            Text("Hush",fontSize=28.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.8).sp,modifier=Modifier.weight(1f))
            FilledTonalIconButton(onClick=onSettings,modifier=Modifier.size(48.dp).semantics{contentDescription="Settings"}) {
                HushIcon(Glyph.TUNE,color=cs.onSecondaryContainer)
            }
        }
    }) {insets ->
        Column(Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            DeviceCard(status,codec,battery,hasDevice && granted,batteryFresh,batteryRefreshing || (codecRefreshing && codec==null),
                onConnection,if(status==Headphones.Status.CONNECTED) onBattery else onConnect)
            if(!granted) {
                SetupCard("Bluetooth access", "Allow Hush to connect to your WF-1000XM5.","Allow Bluetooth",Glyph.BLUETOOTH,onPermission)
            } else if(!hasDevice) {
                SetupCard("Connect your earbuds","Pair WF-1000XM5 in Android Bluetooth settings.","Select device",Glyph.EARBUDS,onPick)
            } else {
                Column {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("Noise control",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold,letterSpacing=(-1.1).sp,modifier=Modifier.weight(1f))
                        if(pending!=null) LoadingIndicator(Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth().selectableGroup(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Mode.entries.forEach {mode ->
                            ModeControl(mode,state?.mode==mode,Modifier.weight(1f)) {choose((state ?: NcState(mode)).copy(mode=mode))}
                        }
                    }

                AnimatedVisibility(state?.mode==Mode.AMBIENT,enter=expandVertically(animationSpec=MaterialTheme.motionScheme.defaultSpatialSpec(),expandFrom=Alignment.Top)+fadeIn(),exit=shrinkVertically(animationSpec=MaterialTheme.motionScheme.defaultSpatialSpec(),shrinkTowards=Alignment.Top)+fadeOut()) {
                    Column {
                        Spacer(Modifier.height(24.dp))
                        AmbientPanel((state ?: NcState(Mode.AMBIENT)).copy(mode=Mode.AMBIENT),::choose)
                    }
                }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    QuickControl(Glyph.EQ,"Equalizer",Modifier.weight(1f),onEqualizer)
                    QuickControl(Glyph.TUNE,"Earbud settings",Modifier.weight(1f),onEarbuds)
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
private fun DeviceCard(status:Headphones.Status,codec:CodecProtocol.State?,battery:SonyProtocol.Battery?,showBattery:Boolean,
    batteryFresh:Boolean,busy:Boolean,onDetails:()->Unit,onRefresh:()->Unit) {
    val cs=MaterialTheme.colorScheme
    val ready=status==Headphones.Status.CONNECTED
    Surface(onClick=onDetails,shape=RoundedCornerShape(28.dp),color=cs.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.wf1000xm5_silver),contentDescription="Silver WF-1000XM5 earbuds",contentScale=ContentScale.Fit,modifier=Modifier.width(64.dp).height(52.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("WF-1000XM5",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    Text(when(status){Headphones.Status.CONNECTED->"Connected";Headphones.Status.CONNECTING->"Connecting…";Headphones.Status.FAILED->"Connection failed";else->"Disconnected"},
                        style=MaterialTheme.typography.bodySmall,color=cs.onSurfaceVariant)
                    Text("Codec · "+if(ready) codec?.label.orEmpty().ifEmpty{"—"} else "—",
                        style=MaterialTheme.typography.labelMedium,color=if(ready && codec?.isKnown==true) cs.primary else cs.onSurfaceVariant,
                        modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                }
                IconButton(onClick=onRefresh,enabled=!busy && status!=Headphones.Status.CONNECTING,
                    modifier=Modifier.semantics{contentDescription=if(ready) "Refresh device information" else "Reconnect"}) {
                    if(busy || status==Headphones.Status.CONNECTING) LoadingIndicator(Modifier.size(26.dp))
                    else HushIcon(Glyph.REFRESH,color=cs.onSurfaceVariant)
                }
            }
            if(showBattery) {
                val b=battery ?: SonyProtocol.Battery()
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf(Triple("L",b.left,b.leftCharging),Triple("R",b.right,b.rightCharging),Triple("Case",b.case,b.caseCharging)).forEach {(name,value,charging)->
                        val label=when(name){"L"->"Left";"R"->"Right";else->name}
                        Surface(shape=RoundedCornerShape(16.dp),color=cs.surfaceContainerHigh,modifier=Modifier.weight(1f).semantics(mergeDescendants=true) {
                            contentDescription="$label battery";stateDescription=(value?.let{"$it percent"} ?: "Unavailable")+if(charging) ", charging" else if(!batteryFresh && value!=null) ", last known" else ""
                        }) {
                            Column(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text(name,style=MaterialTheme.typography.labelMedium,color=cs.onSurfaceVariant,modifier=Modifier.weight(1f))
                                    if(charging) Icon(Icons.Rounded.BatteryChargingFull,contentDescription=null,modifier=Modifier.size(14.dp),tint=cs.onSurfaceVariant)
                                }
                                Text(value?.let{"$it%"} ?: "—",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,
                                    color=if(value!=null && value<=20) cs.error else if(batteryFresh) cs.onSurface else cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickControl(glyph:Glyph,title:String,modifier:Modifier,onClick:()->Unit) {
    val cs=MaterialTheme.colorScheme
    Surface(onClick=onClick,shape=RoundedCornerShape(22.dp),color=cs.surfaceContainer,modifier=modifier) {
        Column(Modifier.padding(16.dp).heightIn(min=52.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            HushIcon(glyph,color=cs.onSurfaceVariant)
            Text(title,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Medium)
        }
    }
}

@Composable
private fun ModeControl(mode:Mode,selected:Boolean,modifier:Modifier,onClick:()->Unit) {
    val cs=MaterialTheme.colorScheme
    val contentColor by animateColorAsState(if(selected) cs.onPrimary else cs.onSurface,animationSpec=tween(180),label="mode content")
    ToggleButton(checked=selected,onCheckedChange={onClick()},modifier=modifier.heightIn(min=96.dp).semantics{role=Role.RadioButton;this.selected=selected;contentDescription=mode.label},
        shapes=ToggleButtonDefaults.shapes(shape=RoundedCornerShape(36.dp),pressedShape=RoundedCornerShape(16.dp),checkedShape=RoundedCornerShape(22.dp)),
        colors=ToggleButtonDefaults.toggleButtonColors(containerColor=cs.surfaceContainerHigh,contentColor=cs.onSurface,checkedContainerColor=cs.primary,checkedContentColor=cs.onPrimary),
        elevation=null,contentPadding=PaddingValues(horizontal=4.dp,vertical=16.dp)) {
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            HushIcon(when(mode){Mode.ANC->Glyph.QUIET;Mode.AMBIENT->Glyph.AMBIENT;Mode.OFF->Glyph.OFF},Modifier.size(28.dp),contentColor)
            Text(mode.short,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold,color=contentColor)
        }
    }
}

@Composable
private fun AmbientPanel(state:NcState,onChange:(NcState)->Unit) {
    val cs=MaterialTheme.colorScheme
    var level by remember {mutableFloatStateOf(state.level.toFloat())}
    var dragging by remember {mutableStateOf(false)}
    LaunchedEffect(state.level,dragging) {if(!dragging) level=state.level.toFloat()}
    Surface(shape=RoundedCornerShape(28.dp),color=cs.surfaceContainer,modifier=Modifier.fillMaxWidth()) {
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
private fun ConnectionSheet(status:Headphones.Status,codec:CodecProtocol.State?,busy:Boolean,onRefresh:()->Unit,onBluetooth:()->Unit,onBack:()->Unit) {
    val cs=MaterialTheme.colorScheme
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
        SettingsRow(Glyph.BLUETOOTH,"Bluetooth settings",onClick=onBluetooth)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Diagnostics(onBack:()->Unit) {
    val log by Headphones.log.collectAsState()
    val scope=rememberCoroutineScope()
    val clipboard=LocalClipboardManager.current
    var querying by remember {mutableStateOf(false)}
    Column(Modifier.padding(horizontal=24.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
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
private fun EqualizerSheet(onBack:()->Unit) {
    val eq by Headphones.equalizer.collectAsState()
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(true)}
    var message by remember {mutableStateOf<String?>(null)}
    var revision by remember {mutableIntStateOf(0)}
    fun load() {scope.launch {busy=true;message=null;try {if(Headphones.readEqualizer()==null) message="No equalizer response. Reconnect the earbuds and retry."} finally {busy=false;revision++}}}
    LaunchedEffect(Unit) {try {if(Headphones.readEqualizer()==null) message="No equalizer response. Reconnect the earbuds and retry."} finally {busy=false;revision++}}
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

@Composable
private fun EarbudSettingsSheet(onBack:()->Unit) {
    val values by Headphones.features.collectAsState()
    val scope=rememberCoroutineScope()
    var loading by remember {mutableStateOf(emptySet<DeviceSettingsProtocol.Feature>())}
    var message by remember {mutableStateOf<String?>(null)}
    suspend fun load() {
        for(feature in DeviceSettingsProtocol.Feature.entries) {
            loading=loading+feature
            try {Headphones.readFeature(feature)} finally {loading=loading-feature}
        }
    }
    LaunchedEffect(Unit) {load()}
    Column(Modifier.padding(horizontal=24.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SheetHeader("Earbud settings",onBack)
        DeviceSettingsProtocol.Feature.entries.forEach {feature ->
            val value=values[feature]
            SettingsRow(if(feature==DeviceSettingsProtocol.Feature.DSEE) Glyph.EQ else Glyph.EARBUDS,feature.title,
                subtitle=if(feature in loading) "Reading…" else if(value==null) "No device response" else null,
                trailing={
                    Switch(checked=value ?: false,enabled=value!=null && feature !in loading,
                        modifier=Modifier.semantics{contentDescription=feature.title},onCheckedChange={wanted->
                            scope.launch {
                                loading=loading+feature;message=null
                                try {if(!Headphones.writeFeature(feature,wanted)) message="The earbuds did not confirm the change."}
                                finally {loading=loading-feature}
                            }
                        })
                })
        }
        message?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)}
        OutlinedButton(enabled=loading.isEmpty(),onClick={scope.launch{load()}}) {Text("Refresh settings")}
        Spacer(Modifier.height(24.dp))
    }
}
