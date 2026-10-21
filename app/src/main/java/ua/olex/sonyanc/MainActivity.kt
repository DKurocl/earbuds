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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import ua.olex.sonyanc.SonyProtocol.Mode
import ua.olex.sonyanc.SonyProtocol.NcState

class MainActivity : ComponentActivity() {
    private val startTick = mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Headphones.init(this)
        enableEdgeToEdge()
        setContent { AppTheme { App(startTick.intValue) } }
    }
    override fun onStart() { super.onStart(); Headphones.hold(); startTick.intValue++ }
    override fun onStop() { Headphones.release(); super.onStop() }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if(dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary=Color(0xFFCEEAA3),onPrimary=Color(0xFF233516),primaryContainer=Color(0xFF344B25),onPrimaryContainer=Color(0xFFE1F5C4),surface=Color(0xFF121510),surfaceContainer=Color(0xFF1D211A),secondaryContainer=Color(0xFF303729))
        else -> lightColorScheme(primary=Color(0xFF46672F),onPrimary=Color.White,primaryContainer=Color(0xFFD4EDB8),onPrimaryContainer=Color(0xFF182D0B),surface=Color(0xFFF8FAF0),surfaceContainer=Color(0xFFEEF1E5),secondaryContainer=Color(0xFFE1E8D5))
    }
    MaterialTheme(colorScheme=colors, content=content)
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(startTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Headphones.status.collectAsState()
    val nc by Headphones.nc.collectAsState()
    val pending by Headphones.pending.collectAsState()
    val fresh by Headphones.fresh.collectAsState()
    val error by Headphones.error.collectAsState()
    val battery by Headphones.battery.collectAsState()
    var granted by remember { mutableStateOf(Headphones.hasPermission(ctx)) }
    var saved by remember { mutableStateOf(Prefs.deviceAddress(ctx)) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var devices by remember { mutableStateOf(emptyList<android.bluetooth.BluetoothDevice>()) }
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted=it }

    LaunchedEffect(startTick, granted, saved) {
        granted=Headphones.hasPermission(ctx)
        if(granted) {
            devices=Headphones.bondedDevices(ctx)
            if(saved==null && devices.size==1) {
                val d=devices.single()
                Headphones.selectDevice(d.address,d.name)
                saved=d.address
            } else if(saved!=null) Headphones.connectAndSync()
        }
    }
    HushScreen(status,nc,pending,fresh,error,battery,granted,saved!=null,
        onMode={ Headphones.request(it) },
        onSettings={ settings=true },
        onConnect={ scope.launch { Headphones.connectAndSync() } },
        onPermission={ if(Build.VERSION.SDK_INT>=31) launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
        onBluetooth={ ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
        onPick={ settings=true })

    if(settings) ModalBottomSheet(onDismissRequest={settings=false}) {
        Column(Modifier.padding(horizontal=24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Твої навушники",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text("WF-1000XM5",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            devices.forEach { d ->
                ListItem(headlineContent={Text(d.name ?: "WF-1000XM5")},
                    supportingContent={Text(if(d.address==saved) "Вибрано" else "Спарені з телефоном")},
                    trailingContent={if(d.address==saved) HushIcon(Glyph.CHECK)},
                    modifier=Modifier.clip(RoundedCornerShape(24.dp)).clickable {
                        scope.launch { Headphones.selectDevice(d.address,d.name); saved=d.address; settings=false; Headphones.connectAndSync() }
                    })
            }
            if(devices.isEmpty()) Text("Спар WF-1000XM5 у налаштуваннях Bluetooth, потім повернись сюди.")
            FilledTonalButton(onClick={ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
                HushIcon(Glyph.BLUETOOTH,color=MaterialTheme.colorScheme.onSecondaryContainer); Spacer(Modifier.width(10.dp)); Text("Налаштування Bluetooth")
            }
            val mgr=remember { AppWidgetManager.getInstance(ctx) }
            if(mgr.isRequestPinAppWidgetSupported) OutlinedButton(onClick={ mgr.requestPinAppWidget(ComponentName(ctx,ModeWidget::class.java),null,null) },modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
                HushIcon(Glyph.WIDGET); Spacer(Modifier.width(10.dp)); Text("Додати віджет")
            }
            Text("Колір підлаштовується під шпалери. З’єднання активне, поки ти користуєшся Hush, і ще 12 секунд після виходу.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick={settings=false;diagnostics=true}) { Text("Діагностика з’єднання") }
            Spacer(Modifier.height(24.dp))
        }
    }
    if(diagnostics) ModalBottomSheet(onDismissRequest={diagnostics=false}) { Diagnostics() }
}

@Composable
fun HushScreen(
    status: Headphones.Status, nc: NcState?, pending: NcState?, fresh: Boolean,
    error: String?, battery: SonyProtocol.Battery?, granted: Boolean, hasDevice: Boolean,
    onMode: (NcState)->Unit, onSettings: ()->Unit, onConnect: ()->Unit,
    onPermission: ()->Unit, onBluetooth: ()->Unit, onPick: ()->Unit,
) {
    val cs=MaterialTheme.colorScheme
    val state=pending ?: nc
    val haptic=LocalHapticFeedback.current
    fun choose(s: NcState) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onMode(s) }
    Scaffold(containerColor=cs.surface,topBar={
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=24.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
            HushMark(); Spacer(Modifier.width(10.dp))
            Text("hush",fontSize=30.sp,fontWeight=FontWeight.Black,letterSpacing=(-1).sp,modifier=Modifier.weight(1f))
            FilledTonalIconButton(onClick=onSettings,modifier=Modifier.size(48.dp).semantics { contentDescription="Налаштування" }) { HushIcon(Glyph.TUNE) }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("WF–1000XM5",style=MaterialTheme.typography.labelLarge,color=cs.onSurfaceVariant,modifier=Modifier.weight(1f))
                Surface(shape=RoundedCornerShape(50),color=cs.surfaceContainerHigh) {
                    Row(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).background(if(status==Headphones.Status.CONNECTED) cs.primary else cs.outline,RoundedCornerShape(50)))
                        Text(when(status) {
                            Headphones.Status.CONNECTED -> "На зв’язку"
                            Headphones.Status.CONNECTING -> "Підключення…"
                            else -> "Поза зв’язком"
                        },style=MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Surface(shape=RoundedCornerShape(topStart=36.dp,topEnd=36.dp,bottomStart=16.dp,bottomEnd=36.dp),color=cs.surfaceContainer,modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(top=24.dp,bottom=20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    AnimatedContent(targetState=state?.mode,label="mode headline") { mode ->
                        Text(when(mode) { Mode.ANC->"Твоя тиша."; Mode.AMBIENT->"Світ поруч."; Mode.OFF->"Просто музика."; null->"Твій простір." },style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp,textAlign=TextAlign.Center,modifier=Modifier.padding(horizontal=16.dp))
                    }
                    EarbudsArt(state?.mode,Modifier.fillMaxWidth().height(176.dp).semantics { contentDescription="Ілюстрація пари WF-1000XM5" })
                    Text(when {
                        pending!=null -> "Застосовую режим…"
                        status==Headphones.Status.CONNECTING -> "Можеш уже вибрати режим нижче"
                        status!=Headphones.Status.CONNECTED && nc!=null -> "Останній відомий режим"
                        !fresh && nc!=null -> "Уточнюю стан навушників…"
                        state?.mode==Mode.ANC -> "Менше шуму. Більше свого."
                        state?.mode==Mode.AMBIENT -> "Чуй те, що відбувається навколо."
                        state?.mode==Mode.OFF -> "Без обробки навколишнього звуку."
                        else -> "Три режими. Один дотик."
                    },style=MaterialTheme.typography.bodyMedium,color=cs.onSurfaceVariant,textAlign=TextAlign.Center,modifier=Modifier.padding(horizontal=16.dp))
                }
            }
            if(!granted) {
                ActionCard("Доступ до Bluetooth","Hush підключається лише до твоїх WF-1000XM5.","Дозволити",onPermission)
            } else if(!hasDevice) {
                ActionCard("Підключімо навушники","Спочатку спар WF-1000XM5 з телефоном.","Вибрати навушники",onPick)
            } else {
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("Керування звуком",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                        if(pending!=null) CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
                    }
                    Row(Modifier.fillMaxWidth().selectableGroup(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Mode.entries.forEach { m ->
                            ModeControl(m,state?.mode==m,Modifier.weight(1f)) { choose((state ?: NcState(m)).copy(mode=m)) }
                        }
                    }
                }
                AnimatedVisibility(state?.mode==Mode.AMBIENT,enter=fadeIn()+expandVertically(),exit=fadeOut()+shrinkVertically()) {
                    AmbientControls(state ?: NcState(Mode.AMBIENT),::choose)
                }
            }
            AnimatedVisibility(error!=null) {
                Surface(shape=RoundedCornerShape(24.dp),color=cs.errorContainer) {
                    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(error.orEmpty(),color=cs.onErrorContainer,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                        Row {
                            TextButton(onClick=onConnect) { Text("Повторити",color=cs.onErrorContainer) }
                            TextButton(onClick=onBluetooth) { Text("Bluetooth",color=cs.onErrorContainer) }
                        }
                    }
                }
            }
            if(hasDevice && status==Headphones.Status.DISCONNECTED && error==null) FilledTonalButton(onClick=onConnect,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
                HushIcon(Glyph.REFRESH,color=cs.onSecondaryContainer); Spacer(Modifier.width(8.dp)); Text("Підключити навушники")
            }
            battery?.let { BatteryRow(it) }
            Row(Modifier.fillMaxWidth().padding(top=4.dp,bottom=24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                HushMark(Modifier.size(16.dp),cs.outline); Spacer(Modifier.width(8.dp))
                Text("Лише ти й твій звук",style=MaterialTheme.typography.labelMedium,color=cs.outline)
            }
        }
    }
}

@Composable
private fun ActionCard(title:String,body:String,action:String,onClick:()->Unit) {
    Surface(shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text(body,style=MaterialTheme.typography.bodyMedium)
            Button(onClick=onClick,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text(action) }
        }
    }
}

@Composable
private fun ModeControl(mode:Mode,selected:Boolean,modifier:Modifier,onClick:()->Unit) {
    val cs=MaterialTheme.colorScheme
    val radius by animateDpAsState(if(selected) 32.dp else 20.dp,spring(dampingRatio=.72f,stiffness=550f),label="tile shape")
    val color by animateColorAsState(if(selected) cs.primary else cs.surfaceContainerHigh,label="tile color")
    val fg=if(selected) cs.onPrimary else cs.onSurface
    Surface(color=color,contentColor=fg,shape=RoundedCornerShape(radius),modifier=modifier
        .semantics { role=Role.RadioButton; this.selected=selected; contentDescription=mode.label }
        .clip(RoundedCornerShape(radius)).clickable(onClick=onClick)) {
        Column(Modifier.padding(horizontal=4.dp,vertical=20.dp).heightIn(min=68.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp,Alignment.CenterVertically)) {
            HushIcon(when(mode) {Mode.ANC->Glyph.QUIET;Mode.AMBIENT->Glyph.AMBIENT;Mode.OFF->Glyph.OFF},Modifier.size(30.dp),fg)
            Text(when(mode) { Mode.ANC->"ANC";Mode.AMBIENT->"Прозорість";Mode.OFF->"Вимкнено" },style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center)
        }
    }
}

@Composable
private fun AmbientControls(state:NcState,onChange:(NcState)->Unit) {
    val cs=MaterialTheme.colorScheme
    var level by remember(state.level) { mutableFloatStateOf(state.level.toFloat()) }
    Surface(shape=RoundedCornerShape(28.dp),color=cs.secondaryContainer) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Скільки світу чути?",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                Text("${level.toInt()}",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            }
            Slider(value=level,onValueChange={level=it},valueRange=0f..20f,steps=19,
                onValueChangeFinished={onChange(state.copy(level=level.toInt()))},
                modifier=Modifier.semantics { contentDescription="Рівень прозорості"; stateDescription="${level.toInt()} із 20" })
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=state.level==12 && !state.voice,onClick={onChange(state.copy(level=12,voice=false))},label={Text("Вулиця")})
                FilterChip(selected=state.level==20 && state.voice,onClick={onChange(state.copy(level=20,voice=true))},label={Text("Розмова")})
            }
            HorizontalDivider(color=cs.onSecondaryContainer.copy(alpha=.12f))
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Фокус на голосі",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                    Text("Мовлення — на першому плані",style=MaterialTheme.typography.bodySmall)
                }
                Switch(checked=state.voice,onCheckedChange={onChange(state.copy(voice=it))},modifier=Modifier.semantics { contentDescription="Фокус на голосі" })
            }
        }
    }
}

@Composable
private fun BatteryRow(b:SonyProtocol.Battery) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("Лівий" to b.left,"Правий" to b.right,"Кейс" to b.case).filter {it.second!=null}.forEach { (label,value)->
            Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(label,style=MaterialTheme.typography.labelMedium)
                    Text("$value%",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
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
    Column(Modifier.padding(horizontal=24.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Діагностика",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("Якщо немає з’єднання, закрий Sony Sound Connect та перевір, чи навушники підключені до телефона.",style=MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={clipboard.setText(AnnotatedString(log.joinToString("\n")))}) { Text("Копіювати лог") }
            TextButton(onClick={Headphones.clearLog()}) { Text("Очистити") }
        }
        OutlinedButton(enabled=!querying,onClick={scope.launch { querying=true; try {Headphones.queryBattery()} finally {querying=false} }}) { Text(if(querying) "Запитую…" else "Перевірити дані батареї") }
        Text("Запит батареї експериментальний і не запускається при відкритті.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth().height(240.dp)) {
            val scroll=rememberScrollState()
            LaunchedEffect(log.size) {scroll.scrollTo(scroll.maxValue)}
            Text(log.joinToString("\n").ifEmpty {"Лог порожній"},fontFamily=FontFamily.Monospace,fontSize=11.sp,modifier=Modifier.verticalScroll(scroll).padding(16.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}
