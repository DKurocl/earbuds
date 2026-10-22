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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun AdvancedSettingsSheet(onBack:()->Unit) {
    val values by Headphones.advanced.collectAsState()
    val scope=rememberCoroutineScope()
    var expanded by remember {mutableStateOf<AdvancedSettings.Key?>(null)}
    var busy by remember {mutableStateOf<AdvancedSettings.Key?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    val cs=MaterialTheme.colorScheme
    fun read(key:AdvancedSettings.Key) {scope.launch {
        busy=key;message=null
        try {if(Headphones.readAdvanced(key)==null) message="No response for ${key.title.lowercase()}."}
        finally {busy=null}
    }}
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack){HushIcon(Glyph.BACK)}
            Text("Device controls",style=MaterialTheme.typography.headlineSmall)
        }
        AdvancedSettings.Key.entries.forEach {key ->
            val value=values[key]
            Surface(shape=RoundedCornerShape(22.dp),color=cs.surfaceContainer,modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    TextButton(enabled=busy==null,onClick={
                        expanded=if(expanded==key) null else key
                        if(expanded==key && value==null) read(key)
                    },modifier=Modifier.fillMaxWidth()) {
                        Text(key.title,style=MaterialTheme.typography.titleMedium,color=cs.onSurface,modifier=Modifier.weight(1f))
                        if(busy==key) LoadingIndicator(Modifier.size(24.dp))
                        else HushIcon(Glyph.ARROW,color=cs.onSurfaceVariant)
                    }
                    if(expanded==key) {
                        if(value!=null) key.fields.forEach {field ->
                            var menu by remember(key,field.index){mutableStateOf(false)}
                            val code=value[field.index].let{if(key==AdvancedSettings.Key.TOUCH && it==0x35) 0 else it}
                            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(field.label,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f))
                                Box {
                                    TextButton(enabled=busy==null,onClick={menu=true}) {Text(field.choices.firstOrNull{it.code==code}?.label ?: "Device setting")}
                                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                                        field.choices.forEach {choice -> DropdownMenuItem(text={Text(choice.label)},onClick={
                                            menu=false
                                            val wanted=value.toMutableList().apply{this[field.index]=choice.code}
                                            scope.launch {
                                                busy=key;message=null
                                                try {if(!Headphones.writeAdvanced(key,wanted)) message="Change not confirmed. Refresh this setting."}
                                                finally {busy=null}
                                            }
                                        })}
                                    }
                                }
                            }
                        }
                        if(busy==null) TextButton(onClick={read(key)}) {Text(if(value==null) "Read setting" else "Refresh")}
                    }
                }
            }
        }
        message?.let{Text(it,color=cs.error,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(12.dp))}
        Spacer(Modifier.height(24.dp))
    }
}
