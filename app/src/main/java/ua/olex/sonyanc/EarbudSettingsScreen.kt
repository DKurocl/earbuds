@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.olex.sonyanc.AdvancedSettings.Key
import ua.olex.sonyanc.DeviceSettingsProtocol.Feature

/** Reads every setting on entry (and on return to the app), then writes one change at a time. */
@Composable
fun EarbudSettingsScreen(onBack: () -> Unit, onAppSettings: () -> Unit = {}, resumeTick: Int = 0) {
    val values by Headphones.advanced.collectAsState()
    val features by Headphones.features.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(Key.entries.filter { it !in Headphones.advanced.value }.toSet()) }
    var featureLoading by remember { mutableStateOf(Feature.entries.filter { it !in Headphones.features.value }.toSet()) }
    var writing by remember { mutableStateOf<Key?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun load(force: Boolean = false) {
        val keys = SETTINGS_ORDER.filter { force || it !in Headphones.advanced.value }
        val basic = Feature.entries.filter { force || it !in Headphones.features.value }
        loading = keys.toSet()
        featureLoading = basic.toSet()
        try {
            if (!Headphones.connectAndSync()) return
            for (feature in basic) try { Headphones.readFeature(feature) } finally { featureLoading = featureLoading - feature }
            for (key in keys) {
                if (!Headphones.state.value.connected) break
                try { Headphones.readAdvanced(key) } finally { loading = loading - key }
            }
        } finally {
            loading = emptySet()
            featureLoading = emptySet()
        }
    }
    LaunchedEffect(resumeTick) { load() }

    // The last known values show at once and stay (also when the earbuds are away); live answers
    // replace them. Rows ignore taps while their setting is being read, but never change shape.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cachedValues = remember { Prefs.cachedAdvanced(ctx) }
    val cachedFeatures = remember { Prefs.cachedFeatures(ctx) }
    val shownValues = cachedValues + values
    val shownFeatures = cachedFeatures + features
    EarbudSettingsContent(
        shownValues, shownFeatures, loading, featureLoading, writing, message, onBack, onAppSettings,
        onRefresh = { scope.launch { load(force = true) } },
        onUpdate = { key, index, code ->
            scope.launch {
                writing = key; message = null
                try { if (!Headphones.writeAdvancedField(key, index, code)) message = "Change not confirmed. Refresh to see the current setting." }
                finally { writing = null }
            }
        },
        onBasic = { feature, wanted ->
            scope.launch {
                featureLoading = featureLoading + feature; message = null
                try { if (!Headphones.writeFeature(feature, wanted)) message = "Change not confirmed. Refresh to see the current setting." }
                finally { featureLoading = featureLoading - feature }
            }
        },
    )
}

private val SETTINGS_ORDER = listOf(
    Key.CHAT, Key.CHAT_CONFIG, Key.SIDETONE, Key.TOUCH, Key.CYCLE, Key.GESTURES,
    Key.POWER, Key.QUALITY, Key.GUIDANCE, Key.GUIDANCE_VOLUME,
)

/** UI-only state boundary: previews and tests never claim to emulate physical headphones. */
@Composable
fun EarbudSettingsContent(
    values: Map<Key, List<Int>>, features: Map<Feature, Boolean>,
    loading: Set<Key> = emptySet(), featureLoading: Set<Feature> = emptySet(),
    writing: Key? = null, message: String? = null, onBack: () -> Unit = {}, onAppSettings: () -> Unit = {},
    onRefresh: () -> Unit = {}, onUpdate: (Key, Int, Int) -> Unit = { _, _, _ -> }, onBasic: (Feature, Boolean) -> Unit = { _, _ -> },
    firstTopic: Topic = Topic.CHAT,
) {
    // The card on top shows what a setting does. It goes through them by itself until a setting is
    // changed; then it shows that setting, playing it out.
    var topic by rememberSaveable { mutableStateOf(firstTopic) }
    var settled by rememberSaveable { mutableStateOf(false) }
    val update: (Key, Int, Int) -> Unit = { key, index, code -> topic = key.topic(); settled = true; onUpdate(key, index, code) }
    val basic: (Feature, Boolean) -> Unit = { feature, on -> topic = feature.topic(); settled = true; onBasic(feature, on) }
    var laps by remember { mutableIntStateOf(0) }

    val show = LocalMessages.current
    LaunchedEffect(message) { message?.let(show) }
    val idle = loading.isEmpty() && featureLoading.isEmpty() && writing == null

    // Helpers that bind a protocol field to a row.
    fun busy(key: Key) = key in loading || writing == key
    fun code(key: Key, index: Int): Int? = values[key]?.get(index)?.let { if (key == Key.TOUCH && it == 0x35) 0 else it }
    fun choices(key: Key, index: Int) = key.fields.first { it.index == index }.choices.map { it.code to it.label }

    PageScaffold("Earbud settings", onBack, actions = {
        if (loading.isNotEmpty() || featureLoading.isNotEmpty()) LoadingIndicator(Modifier.padding(horizontal = 8.dp).size(32.dp))
        else IconButton(enabled = idle, onClick = onRefresh, modifier = Modifier.semantics { contentDescription = "Refresh settings" }) { HushIcon(Glyph.REFRESH) }
        IconButton(onClick = onAppSettings, modifier = Modifier.semantics { contentDescription = "App settings" }) { HushIcon(Glyph.SETTINGS) }
    }) {
        IllustrationCard(
            topic,
            onLoop = { if (!settled && ++laps % 2 == 0) topic = Topic.entries[(topic.ordinal + 1) % Topic.entries.size] },
            onTap = { settled = true; topic = Topic.entries[(topic.ordinal + 1) % Topic.entries.size] },
        )
        ListGroup("Sound") {
            item { FeatureSwitch(it, Feature.DSEE, "Restores detail in compressed music", features, featureLoading, basic) }
            item { KeySwitch(it, Key.CHAT, "Speak-to-Chat", "Pauses music when you start talking", code(Key.CHAT, 0), busy(Key.CHAT), writing == null, update) }
            item { ChoiceRow(it, null, "Conversation sensitivity", choices(Key.CHAT_CONFIG, 0), code(Key.CHAT_CONFIG, 0), busy(Key.CHAT_CONFIG), writing == null) { c -> update(Key.CHAT_CONFIG, 0, c) } }
            item { ChoiceRow(it, null, "Resume after conversation", choices(Key.CHAT_CONFIG, 1), code(Key.CHAT_CONFIG, 1), busy(Key.CHAT_CONFIG), writing == null) { c -> update(Key.CHAT_CONFIG, 1, c) } }
            item { KeySwitch(it, Key.SIDETONE, "Hear my voice in calls", "Lets you hear yourself while talking", code(Key.SIDETONE, 0), busy(Key.SIDETONE), writing == null, update) }
        }
        ListGroup("Controls") {
            item { ChoiceRow(it, null, "Left touch control", choices(Key.TOUCH, 1), code(Key.TOUCH, 1), busy(Key.TOUCH), writing == null) { c -> update(Key.TOUCH, 1, c) } }
            item { ChoiceRow(it, null, "Right touch control", choices(Key.TOUCH, 2), code(Key.TOUCH, 2), busy(Key.TOUCH), writing == null) { c -> update(Key.TOUCH, 2, c) } }
            item { ChoiceRow(it, null, "Noise control cycle", choices(Key.CYCLE, 4), code(Key.CYCLE, 4), busy(Key.CYCLE), writing == null) { c -> update(Key.CYCLE, 4, c) } }
            item { KeySwitch(it, Key.GESTURES, "Head gestures", "Nod or shake your head to answer calls", code(Key.GESTURES, 0), busy(Key.GESTURES), writing == null, update) }
        }
        ListGroup("Playback & connection") {
            item { FeatureSwitch(it, Feature.AUTO_PAUSE, "Pauses playback when you take an earbud out", features, featureLoading, basic) }
            item { ChoiceRow(it, null, "Automatic power off", choices(Key.POWER, 0), code(Key.POWER, 0), busy(Key.POWER), writing == null) { c -> update(Key.POWER, 0, c) } }
            item { ChoiceRow(it, null, "Bluetooth quality", choices(Key.QUALITY, 0), code(Key.QUALITY, 0), busy(Key.QUALITY), writing == null) { c -> update(Key.QUALITY, 0, c) } }
        }
        ListGroup("Voice prompts") {
            item { KeySwitch(it, Key.GUIDANCE, "Voice guidance", "Spoken prompts for battery and mode changes", code(Key.GUIDANCE, 0), busy(Key.GUIDANCE), writing == null, update) }
            item { ChoiceRow(it, null, "Voice guidance volume", choices(Key.GUIDANCE_VOLUME, 0), code(Key.GUIDANCE_VOLUME, 0), busy(Key.GUIDANCE_VOLUME), writing == null) { c -> update(Key.GUIDANCE_VOLUME, 0, c) } }
        }
    }
}

/** Sony's two-state settings encode On as 0 and Off as 1. */
@Composable
private fun KeySwitch(
    shape: ListItemShapes, key: Key, title: String, summary: String,
    code: Int?, busy: Boolean, enabled: Boolean, onUpdate: (Key, Int, Int) -> Unit,
) {
    SwitchRow(shape, null, title, summary, code?.let { it == 0 }, busy, enabled) { on -> onUpdate(key, 0, if (on) 0 else 1) }
}

@Composable
private fun FeatureSwitch(
    shape: ListItemShapes, feature: Feature, summary: String,
    features: Map<Feature, Boolean>, loading: Set<Feature>, onBasic: (Feature, Boolean) -> Unit,
) {
    SwitchRow(shape, null, feature.title, summary, features[feature], feature in loading, loading.isEmpty()) { onBasic(feature, it) }
}
