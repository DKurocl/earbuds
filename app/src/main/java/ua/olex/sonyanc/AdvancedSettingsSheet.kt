@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package ua.olex.sonyanc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** All earbud controls are visible on entry; only actual choices open a menu. */
@Composable
fun AdvancedSettingsSheet(onBack:()->Unit,onAppSettings:()->Unit={}) {
    val values by Headphones.advanced.collectAsState()
    val features by Headphones.features.collectAsState()
    val scope=rememberCoroutineScope()
    var loading by remember {mutableStateOf(AdvancedSettings.Key.entries.filter{it !in Headphones.advanced.value}.toSet())}
    var featureLoading by remember {mutableStateOf(DeviceSettingsProtocol.Feature.entries.filter{it !in Headphones.features.value}.toSet())}
    var writing by remember {mutableStateOf<AdvancedSettings.Key?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    val cs=MaterialTheme.colorScheme
    suspend fun load(force:Boolean=false) {
        val order=listOf(AdvancedSettings.Key.CHAT,AdvancedSettings.Key.CHAT_CONFIG,AdvancedSettings.Key.SIDETONE,
            AdvancedSettings.Key.TOUCH,AdvancedSettings.Key.CYCLE,AdvancedSettings.Key.GESTURES,
            AdvancedSettings.Key.POWER,AdvancedSettings.Key.QUALITY,AdvancedSettings.Key.GUIDANCE,AdvancedSettings.Key.GUIDANCE_VOLUME)
        val keys=order.filter{force || it !in Headphones.advanced.value}
        val basic=DeviceSettingsProtocol.Feature.entries.filter{force || it !in Headphones.features.value}
        loading=keys.toSet();featureLoading=basic.toSet()
        try {
            if(!Headphones.connectAndSync()) return
            for(feature in basic) try {Headphones.readFeature(feature)} finally {featureLoading=featureLoading-feature}
            for(key in keys) {
                if(Headphones.status.value!=Headphones.Status.CONNECTED) break
                try {Headphones.readAdvanced(key)} finally {loading=loading-key}
            }
        } finally {loading=emptySet();featureLoading=emptySet()}
    }
    LaunchedEffect(Unit) {load()}
    fun update(key:AdvancedSettings.Key,index:Int,code:Int) {scope.launch {
        writing=key;message=null
        try {if(!Headphones.writeAdvancedField(key,index,code)) message="Change not confirmed. Refresh this setting."}
        finally {writing=null}
    }}
    fun changeBasic(feature:DeviceSettingsProtocol.Feature,wanted:Boolean) {scope.launch {
        featureLoading=featureLoading+feature;message=null
        try {if(!Headphones.writeFeature(feature,wanted)) message="Change not confirmed. Refresh this setting."}
        finally {featureLoading=featureLoading-feature}
    }}
    EarbudSettingsContent(values,features,loading,featureLoading,writing,message,onBack,onAppSettings,
        onRefresh={scope.launch{load(true)}},onUpdate=::update,onBasic=::changeBasic)
}

/** UI-only state boundary: previews/tests never claim to emulate physical headphones. */
@Composable
fun EarbudSettingsContent(
    values:Map<AdvancedSettings.Key,List<Int>>,features:Map<DeviceSettingsProtocol.Feature,Boolean>,
    loading:Set<AdvancedSettings.Key> = emptySet(),featureLoading:Set<DeviceSettingsProtocol.Feature> = emptySet(),
    writing:AdvancedSettings.Key? = null,message:String? = null,onBack:()->Unit={},onAppSettings:()->Unit={},
    onRefresh:()->Unit={},onUpdate:(AdvancedSettings.Key,Int,Int)->Unit={_,_,_->},onBasic:(DeviceSettingsProtocol.Feature,Boolean)->Unit={_,_->}
) {
    val cs=MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack,modifier=Modifier.semantics{contentDescription="Back"}){HushIcon(Glyph.BACK)}
            Text("Earbud settings",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
            IconButton(enabled=loading.isEmpty() && featureLoading.isEmpty() && writing==null,onClick=onRefresh,modifier=Modifier.semantics{contentDescription="Refresh settings"}) {HushIcon(Glyph.REFRESH)}
            IconButton(onClick=onAppSettings,modifier=Modifier.semantics{contentDescription="App settings"}) {HushIcon(Glyph.SETTINGS)}
        }
        Group("Sound") {
            Basic(DeviceSettingsProtocol.Feature.DSEE,features[DeviceSettingsProtocol.Feature.DSEE],DeviceSettingsProtocol.Feature.DSEE in featureLoading,featureLoading.isEmpty()) {wanted->onBasic(DeviceSettingsProtocol.Feature.DSEE,wanted)}
            Control(AdvancedSettings.Key.CHAT,values[AdvancedSettings.Key.CHAT],AdvancedSettings.Key.CHAT in loading || writing==AdvancedSettings.Key.CHAT,writing==null,onUpdate)
            Control(AdvancedSettings.Key.CHAT_CONFIG,values[AdvancedSettings.Key.CHAT_CONFIG],AdvancedSettings.Key.CHAT_CONFIG in loading || writing==AdvancedSettings.Key.CHAT_CONFIG,writing==null,onUpdate)
            Control(AdvancedSettings.Key.SIDETONE,values[AdvancedSettings.Key.SIDETONE],AdvancedSettings.Key.SIDETONE in loading || writing==AdvancedSettings.Key.SIDETONE,writing==null,onUpdate)
        }
        Group("Controls") {
            Control(AdvancedSettings.Key.TOUCH,values[AdvancedSettings.Key.TOUCH],AdvancedSettings.Key.TOUCH in loading || writing==AdvancedSettings.Key.TOUCH,writing==null,onUpdate)
            Control(AdvancedSettings.Key.CYCLE,values[AdvancedSettings.Key.CYCLE],AdvancedSettings.Key.CYCLE in loading || writing==AdvancedSettings.Key.CYCLE,writing==null,onUpdate)
            Control(AdvancedSettings.Key.GESTURES,values[AdvancedSettings.Key.GESTURES],AdvancedSettings.Key.GESTURES in loading || writing==AdvancedSettings.Key.GESTURES,writing==null,onUpdate)
        }
        Group("Playback & connection") {
            Basic(DeviceSettingsProtocol.Feature.AUTO_PAUSE,features[DeviceSettingsProtocol.Feature.AUTO_PAUSE],DeviceSettingsProtocol.Feature.AUTO_PAUSE in featureLoading,featureLoading.isEmpty()) {wanted->onBasic(DeviceSettingsProtocol.Feature.AUTO_PAUSE,wanted)}
            Control(AdvancedSettings.Key.POWER,values[AdvancedSettings.Key.POWER],AdvancedSettings.Key.POWER in loading || writing==AdvancedSettings.Key.POWER,writing==null,onUpdate)
            Control(AdvancedSettings.Key.QUALITY,values[AdvancedSettings.Key.QUALITY],AdvancedSettings.Key.QUALITY in loading || writing==AdvancedSettings.Key.QUALITY,writing==null,onUpdate)
        }
        Group("Voice guidance") {
            Control(AdvancedSettings.Key.GUIDANCE,values[AdvancedSettings.Key.GUIDANCE],AdvancedSettings.Key.GUIDANCE in loading || writing==AdvancedSettings.Key.GUIDANCE,writing==null,onUpdate)
            Control(AdvancedSettings.Key.GUIDANCE_VOLUME,values[AdvancedSettings.Key.GUIDANCE_VOLUME],AdvancedSettings.Key.GUIDANCE_VOLUME in loading || writing==AdvancedSettings.Key.GUIDANCE_VOLUME,writing==null,onUpdate)
        }
        message?.let{Text(it,color=cs.error,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(12.dp))}
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun SettingRow(title:String,busy:Boolean,content:@Composable ()->Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=16.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f))
            if(busy) LoadingIndicator(Modifier.size(24.dp)) else content()
        }
    }
@Composable private fun Basic(feature:DeviceSettingsProtocol.Feature,value:Boolean?,busy:Boolean,enabled:Boolean,onChange:(Boolean)->Unit) {
        val cs=MaterialTheme.colorScheme
        SettingRow(feature.title,busy) {
            if(value==null) Text("Unavailable",style=MaterialTheme.typography.labelMedium,color=cs.onSurfaceVariant)
            else Switch(checked=value,enabled=enabled,modifier=Modifier.semantics{contentDescription=feature.title},onCheckedChange=onChange)
        }
    }
@Composable private fun Control(key:AdvancedSettings.Key,value:List<Int>?,busy:Boolean,enabled:Boolean,update:(AdvancedSettings.Key,Int,Int)->Unit) {
        val cs=MaterialTheme.colorScheme
        key.fields.forEach {field ->
            val title=when(key) {
                AdvancedSettings.Key.TOUCH -> "${field.label} touch control"
                AdvancedSettings.Key.CHAT_CONFIG -> if(field.index==0) "Conversation sensitivity" else "Resume after conversation"
                else -> key.title
            }
            SettingRow(title,busy) {
                if(value==null) Text("Unavailable",style=MaterialTheme.typography.labelMedium,color=cs.onSurfaceVariant)
                else {
                    val code=value[field.index].let{if(key==AdvancedSettings.Key.TOUCH && it==0x35) 0 else it}
                    if(field.choices.size==2 && field.choices[0].label=="On") {
                        Switch(checked=code==0,enabled=enabled,modifier=Modifier.semantics{contentDescription=title},onCheckedChange={checked->
                            update(key,field.index,if(checked) 0 else 1)
                        })
                    } else {
                        var menu by remember(key,field.index){mutableStateOf(false)}
                        Box {
                            TextButton(enabled=enabled,onClick={menu=true},modifier=Modifier.widthIn(max=172.dp).semantics{contentDescription=title}) {
                                Text(field.choices.firstOrNull{it.code==code}?.label ?: "Device setting",style=MaterialTheme.typography.labelLarge)
                            }
                            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                                field.choices.forEach {choice -> DropdownMenuItem(text={Text(choice.label)},onClick={
                                    menu=false
                                    update(key,field.index,choice.code)
                                })}
                            }
                        }
                    }
                }
            }
        }
    }
@Composable private fun Group(title:String,content:@Composable ColumnScope.()->Unit) {
        val cs=MaterialTheme.colorScheme
        Text(title,style=MaterialTheme.typography.titleSmall,color=cs.onSurfaceVariant,modifier=Modifier.padding(start=16.dp,top=12.dp))
        Surface(shape=RoundedCornerShape(24.dp),color=cs.surfaceContainer,modifier=Modifier.fillMaxWidth()) {
            Column(content=content)
        }
    }
