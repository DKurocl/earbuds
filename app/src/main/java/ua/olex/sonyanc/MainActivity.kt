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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Headphones.init(this)
        enableEdgeToEdge()
        setContent {
            var dynamic by remember { mutableStateOf(Prefs.dynamicColor(this)) }
            AppTheme(dynamic) { App(startTick.intValue,dynamic) { dynamic=it; Prefs.setDynamicColor(this,it) } }
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
fun App(startTick:Int,dynamic:Boolean,onDynamic:(Boolean)->Unit) {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    val status by Headphones.status.collectAsState()
    val nc by Headphones.nc.collectAsState()
    val pending by Headphones.pending.collectAsState()
    val fresh by Headphones.fresh.collectAsState()
    val error by Headphones.error.collectAsState()
    val battery by Headphones.battery.collectAsState()
    var granted by remember { mutableStateOf(Headphones.hasPermission(ctx)) }
    var saved by remember { mutableStateOf(Prefs.deviceAddress(ctx)) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
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
    HushScreen(status,nc,pending,fresh,error,battery,granted,saved!=null,
        onMode={Headphones.request(it)},onSettings={sheet="settings"},onEqualizer={sheet="equalizer"},
        onConnect={scope.launch {Headphones.connectAndSync()}},
        onPermission={if(Build.VERSION.SDK_INT>=31) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)},
        onBluetooth={bluetooth()},onPick={sheet="settings"},onWidget={pinWidget()},
        canPin=widgetManager.isRequestPinAppWidgetSupported,haptics=haptics)
    if(sheet!=null) ModalBottomSheet(onDismissRequest={sheet=null},containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
        if(sheet=="diagnostics") Diagnostics() else if(sheet=="equalizer") EqualizerSheet() else {
            Column(Modifier.padding(horizontal=24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("Settings",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
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
                    SettingsRow(Glyph.EQ,"Equalizer",onClick={sheet="equalizer"})
                    SettingsRow(Glyph.INFO,"Connection diagnostics",onClick={sheet="diagnostics"})
                }
                Text("Firmware updates require Sony Sound Connect. Everyday controls work independently.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Hush 0.3.0",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=8.dp))
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun HushScreen(
    status:Headphones.Status,nc:NcState?,pending:NcState?,fresh:Boolean,error:String?,battery:SonyProtocol.Battery?,
    granted:Boolean,hasDevice:Boolean,onMode:(NcState)->Unit,onSettings:()->Unit,onConnect:()->Unit,
    onPermission:()->Unit,onBluetooth:()->Unit,onPick:()->Unit,onEqualizer:()->Unit={},onWidget:()->Unit={},canPin:Boolean=true,haptics:Boolean=true,
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
        Column(Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            DeviceCard(status,onConnect)
            if(!granted) {
                SetupCard("Bluetooth access", "Allow Hush to connect to your WF-1000XM5.","Allow Bluetooth",Glyph.BLUETOOTH,onPermission)
            } else if(!hasDevice) {
                SetupCard("Connect your earbuds","Pair WF-1000XM5 in Android Bluetooth settings.","Select device",Glyph.EARBUDS,onPick)
            } else {
                Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("Noise control",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold,letterSpacing=(-1.1).sp,modifier=Modifier.weight(1f))
                        if(pending!=null) LoadingIndicator(Modifier.size(32.dp))
                    }
                    Row(Modifier.fillMaxWidth().selectableGroup(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Mode.entries.forEach {mode ->
                            val weight by animateFloatAsState(if(state?.mode==mode) 1.16f else 1f,MaterialTheme.motionScheme.fastSpatialSpec(),label="mode width")
                            ModeControl(mode,state?.mode==mode,Modifier.weight(weight)) {choose((state ?: NcState(mode)).copy(mode=mode))}
                        }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        HushIcon(if(fresh && pending==null) Glyph.CHECK else Glyph.INFO,Modifier.size(16.dp),cs.onSurfaceVariant)
                        val label=when {
                            pending!=null -> "Applying ${pending.mode.short.lowercase()}…"
                            !fresh && nc!=null -> "${nc.mode.label} · last known"
                            state!=null -> state.mode.label
                            status==Headphones.Status.CONNECTING -> "Choose a mode while connecting"
                            else -> "Select a mode"
                        }
                        Text(label,style=MaterialTheme.typography.bodyMedium,color=cs.onSurfaceVariant,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                    }
                }
                AnimatedVisibility(state?.mode==Mode.AMBIENT,enter=expandVertically(animationSpec=MaterialTheme.motionScheme.defaultSpatialSpec())+fadeIn(),exit=shrinkVertically(animationSpec=MaterialTheme.motionScheme.fastSpatialSpec())+fadeOut()) {
                    AmbientPanel(state ?: NcState(Mode.AMBIENT),::choose)
                }
                if(state?.mode==Mode.ANC) SelectedModePanel(Glyph.QUIET,"Noise cancelling")
                if(state?.mode==Mode.OFF) SelectedModePanel(Glyph.OFF,"Noise control off")
                SettingsRow(Glyph.EQ,"Equalizer",onClick=onEqualizer)
                if(canPin) SettingsRow(Glyph.WIDGET,"Home screen widget",onClick=onWidget)
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
            if(hasDevice && granted) BatteryRow(battery ?: SonyProtocol.Battery())
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DeviceCard(status:Headphones.Status,onConnect:()->Unit) {
    val cs=MaterialTheme.colorScheme
    val ready=status==Headphones.Status.CONNECTED
    Surface(onClick=onConnect,enabled=status!=Headphones.Status.CONNECTING,shape=RoundedCornerShape(28.dp),color=cs.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Image(painterResource(R.drawable.wf1000xm5_silver),contentDescription="Silver WF-1000XM5 earbuds",contentScale=ContentScale.Fit,modifier=Modifier.width(88.dp).height(64.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("WF-1000XM5",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).background(if(ready) cs.primary else cs.outline,RoundedCornerShape(50)))
                    Text(when(status){Headphones.Status.CONNECTED->"Connected";Headphones.Status.CONNECTING->"Connecting…";Headphones.Status.FAILED->"Connection failed";else->"Disconnected"},style=MaterialTheme.typography.bodySmall,color=cs.onSurfaceVariant)
                }
            }
            if(status==Headphones.Status.CONNECTING) LoadingIndicator(Modifier.size(30.dp))
            else HushIcon(if(ready) Glyph.BLUETOOTH else Glyph.REFRESH,Modifier.size(22.dp),cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModeControl(mode:Mode,selected:Boolean,modifier:Modifier,onClick:()->Unit) {
    val cs=MaterialTheme.colorScheme
    ToggleButton(checked=selected,onCheckedChange={onClick()},modifier=modifier.heightIn(min=112.dp).semantics{role=Role.RadioButton;this.selected=selected;contentDescription=mode.label},
        shapes=ToggleButtonDefaults.shapes(shape=RoundedCornerShape(36.dp),pressedShape=RoundedCornerShape(16.dp),checkedShape=RoundedCornerShape(22.dp)),
        colors=ToggleButtonDefaults.toggleButtonColors(containerColor=cs.surfaceContainerHigh,contentColor=cs.onSurface,checkedContainerColor=cs.primary,checkedContentColor=cs.onPrimary),
        elevation=null,contentPadding=PaddingValues(horizontal=4.dp,vertical=20.dp)) {
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            HushIcon(when(mode){Mode.ANC->Glyph.QUIET;Mode.AMBIENT->Glyph.AMBIENT;Mode.OFF->Glyph.OFF},Modifier.size(28.dp),if(selected)cs.onPrimary else cs.onSurface)
            Text(mode.short,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AmbientPanel(state:NcState,onChange:(NcState)->Unit) {
    val cs=MaterialTheme.colorScheme
    var level by remember(state.level){mutableFloatStateOf(state.level.toFloat())}
    Surface(shape=RoundedCornerShape(28.dp),color=cs.surfaceContainer,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    HushIcon(Glyph.AMBIENT,color=cs.primary)
                    Text("Ambient sound",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                }
                Row(verticalAlignment=Alignment.Bottom) {
                    Text(level.roundToInt().toString(),fontSize=44.sp,lineHeight=48.sp,fontWeight=FontWeight.Medium,letterSpacing=(-2).sp)
                    Text(" / 20",style=MaterialTheme.typography.bodyMedium,color=cs.onSurfaceVariant,modifier=Modifier.padding(bottom=7.dp))
                }
            }
            Slider(value=level,onValueChange={level=it.roundToInt().toFloat()},valueRange=0f..20f,
                onValueChangeFinished={onChange(state.copy(level=level.roundToInt()))},
                modifier=Modifier.fillMaxWidth().semantics{contentDescription="Ambient sound level";stateDescription="${level.roundToInt()} of 20"})
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Low" to 5,"Medium" to 12,"High" to 20).forEachIndexed {index,(label,value)->
                    SegmentedButton(selected=state.level==value,onClick={onChange(state.copy(level=value))},shape=SegmentedButtonDefaults.itemShape(index,3),icon={}) {Text(label)}
                }
            }
            HorizontalDivider(color=cs.outlineVariant.copy(alpha=.5f))
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Voice focus",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                Switch(checked=state.voice,onCheckedChange={onChange(state.copy(voice=it))},thumbContent=if(state.voice) ({HushIcon(Glyph.CHECK,Modifier.size(16.dp),cs.onPrimary)}) else null,
                    modifier=Modifier.semantics{contentDescription="Voice focus"})
            }
        }
    }
}

@Composable
private fun SelectedModePanel(glyph:Glyph,title:String) {
    val cs=MaterialTheme.colorScheme
    Surface(shape=RoundedCornerShape(28.dp),color=cs.primaryContainer,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            HushIcon(glyph,Modifier.size(32.dp),cs.onPrimaryContainer)
            Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Medium,color=cs.onPrimaryContainer)
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
private fun BatteryRow(b:SonyProtocol.Battery) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("Left" to b.left,"Right" to b.right,"Case" to b.case).forEach {(label,value)->
            Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value?.let{"$it%"} ?: "—",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun Diagnostics() {
    val log by Headphones.log.collectAsState()
    val scope=rememberCoroutineScope()
    val clipboard=LocalClipboardManager.current
    var querying by remember {mutableStateOf(false)}
    Column(Modifier.padding(horizontal=24.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Diagnostics",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
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
private fun EqualizerSheet() {
    val eq by Headphones.equalizer.collectAsState()
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(true)}
    var message by remember {mutableStateOf<String?>(null)}
    fun load() {scope.launch {busy=true;message=null;try {if(Headphones.readEqualizer()==null) message="No equalizer response. Reconnect the earbuds and retry."} finally {busy=false}}}
    LaunchedEffect(Unit) {try {if(Headphones.readEqualizer()==null) message="No equalizer response. Reconnect the earbuds and retry."} finally {busy=false}}
    fun update(preset:Int?=null,bands:List<Int>?=null) {scope.launch {busy=true;message=null;try {if(!Headphones.writeEqualizer(preset,bands)) message="Change not confirmed. Your earbuds may still use the previous setting."} finally {busy=false}}}
    Column(Modifier.padding(horizontal=24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Equalizer",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
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
                var bands by remember(eq){mutableStateOf(eq!!.bands)}
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
