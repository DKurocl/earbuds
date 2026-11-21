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
    // What a change did, shown in the row it happened to (and in the other touch row when that one
    // changed by itself), never in a bar over the page. Each note fades after a few seconds.
    var notes by remember { mutableStateOf<Map<String, RowNote>>(emptyMap()) }
    fun note(id: String, note: RowNote) {
        notes = notes + (id to note)
        scope.launch { kotlinx.coroutines.delay(5000); if (notes[id] == note) notes = notes - id }
    }

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
    val panelRefused by EarbudSettingsLink.panelRefused.collectAsState()
    val device by Headphones.state.collectAsState()
    EarbudSettingsContent(
        shownValues, shownFeatures, loading, featureLoading, writing, notes, onBack, onAppSettings, panelHidden = panelRefused, hideMissing = device.connected && loading.isEmpty(),
        onRefresh = { scope.launch { load(force = true) } },
        onUpdate = { key, index, code ->
            scope.launch {
                writing = key; notes = notes - rowId(key, index)
                // A touch change can move the other earbud's function too (a swap, or Playback, which
                // the earbuds keep on one side): that row says so, as it changes by itself.
                val before = shownValues[key]
                val planned = before?.let { AdvancedSettings.patch(key, it, index, code) }
                try {
                    if (!Headphones.writeAdvancedField(key, index, code)) note(rowId(key, index), RowNote("The earbuds didn't take this change", error = true))
                    else if (key == Key.TOUCH && before != null && planned != null && index in 1..2) {
                        val other = 3 - index
                        if (planned[other] != before[other]) note(rowId(key, other),
                            RowNote(if (planned[other] == AdvancedSettings.PLAYBACK) "Playback moved here: one earbud always keeps it" else "Swapped with the other earbud"))
                    }
                }
                finally { writing = null }
            }
        },
        onBasic = { feature, wanted ->
            scope.launch {
                featureLoading = featureLoading + feature; notes = notes - feature.name
                try { if (!Headphones.writeFeature(feature, wanted)) note(feature.name, RowNote("The earbuds didn't take this change", error = true)) }
                finally { featureLoading = featureLoading - feature }
            }
        },
    )
}

private fun rowId(key: Key, index: Int) = "${key.name}$index"

private val SETTINGS_ORDER = listOf(
    Key.CHAT, Key.CHAT_CONFIG, Key.SIDETONE, Key.PANEL, Key.TOUCH, Key.CYCLE, Key.GESTURES,
    Key.POWER, Key.QUALITY, Key.GUIDANCE, Key.GUIDANCE_VOLUME,
)

/** UI-only state boundary: previews and tests never claim to emulate physical headphones. */
@Composable
fun EarbudSettingsContent(
    values: Map<Key, List<Int>>, features: Map<Feature, Boolean>,
    loading: Set<Key> = emptySet(), featureLoading: Set<Feature> = emptySet(),
    writing: Key? = null, notes: Map<String, RowNote> = emptyMap(), onBack: () -> Unit = {}, onAppSettings: () -> Unit = {},
    onRefresh: () -> Unit = {}, onUpdate: (Key, Int, Int) -> Unit = { _, _, _ -> }, onBasic: (Feature, Boolean) -> Unit = { _, _ -> },
    panelHidden: Boolean = false,
    /** The earbuds are connected and were asked: a setting they did not answer is left out. */
    hideMissing: Boolean = false,
) {
    // As Android's own settings: each row says in one line what it does; no illustration on top.
    val update = onUpdate
    val basic = onBasic

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
        // Rows the earbuds don't answer (while connected) are left out once reading is done (a setting this model
        // lacks is not a row to read "Not available" on), and the settings that only matter when
        // another is on (Speak-to-Chat's detection, the prompts' volume) follow it.
        fun has(key: Key) = !hideMissing || values[key] != null || key in loading
        fun has(feature: Feature) = !hideMissing || features[feature] != null || feature in featureLoading
        val chatOn = code(Key.CHAT, 0) == 0
        val promptsOn = code(Key.GUIDANCE, 0) == 0
        ListGroup("Sound") {
            if (has(Feature.DSEE)) item { FeatureSwitch(it, Feature.DSEE, Glyph.HQ, "Restores detail in compressed music", features, featureLoading, basic, notes[Feature.DSEE.name]) }
            if (has(Key.QUALITY)) item { ChoiceRow(it, Glyph.BLUETOOTH, "Bluetooth quality", choices(Key.QUALITY, 0), code(Key.QUALITY, 0), busy(Key.QUALITY), writing == null, note = notes[rowId(Key.QUALITY, 0)]) { c -> update(Key.QUALITY, 0, c) } }
            if (has(Key.CYCLE)) item { ChoiceRow(it, Glyph.NOISE_AWARE, "Noise control cycle", choices(Key.CYCLE, 4), code(Key.CYCLE, 4), busy(Key.CYCLE), writing == null, note = notes[rowId(Key.CYCLE, 4)]) { c -> update(Key.CYCLE, 4, c) } }
        }
        if (has(Key.CHAT)) ListGroup("Conversation") {
            item { KeySwitch(it, Key.CHAT, Glyph.CHAT, "Speak-to-Chat", "Pauses music when you start talking", code(Key.CHAT, 0), busy(Key.CHAT), writing == null, update, notes[rowId(Key.CHAT, 0)]) }
            if (chatOn && has(Key.CHAT_CONFIG)) {
                item { ChoiceRow(it, Glyph.VOICE_SETTINGS, "Voice detection", choices(Key.CHAT_CONFIG, 0), code(Key.CHAT_CONFIG, 0), busy(Key.CHAT_CONFIG), writing == null, note = notes[rowId(Key.CHAT_CONFIG, 0)]) { c -> update(Key.CHAT_CONFIG, 0, c) } }
                item { ChoiceRow(it, Glyph.AUTOPLAY, "Music comes back", choices(Key.CHAT_CONFIG, 1), code(Key.CHAT_CONFIG, 1), busy(Key.CHAT_CONFIG), writing == null, note = notes[rowId(Key.CHAT_CONFIG, 1)]) { c -> update(Key.CHAT_CONFIG, 1, c) } }
            }
        }
        if (has(Key.TOUCH) || has(Key.GESTURES)) ListGroup("Touch and gestures") {
            // Shown only where the earbuds report the panel and have not refused turning it off.
            if (!panelHidden && values[Key.PANEL] != null) item {
                val panel = values[Key.PANEL]
                SwitchRow(it, Glyph.EARBUDS, "Touch controls", "Turn off to ignore every tap on the earbuds", panel?.let(AdvancedSettings::panelOn), busy(Key.PANEL), writing == null, note = notes[rowId(Key.PANEL, 1)]) { on ->
                    panel?.let { v -> update(Key.PANEL, 1, AdvancedSettings.panelCode(v, on)) }
                }
            }
            if (has(Key.TOUCH)) {
                item { ChoiceRow(it, Glyph.SWIPE_LEFT, "Left earbud", choices(Key.TOUCH, 1), code(Key.TOUCH, 1), busy(Key.TOUCH), writing == null, note = notes[rowId(Key.TOUCH, 1)]) { c -> update(Key.TOUCH, 1, c) } }
                item { ChoiceRow(it, Glyph.SWIPE_RIGHT, "Right earbud", choices(Key.TOUCH, 2), code(Key.TOUCH, 2), busy(Key.TOUCH), writing == null, note = notes[rowId(Key.TOUCH, 2)]) { c -> update(Key.TOUCH, 2, c) } }
            }
            if (has(Key.GESTURES)) item { KeySwitch(it, Key.GESTURES, Glyph.FACE, "Head gestures", "Nod or shake your head to answer calls", code(Key.GESTURES, 0), busy(Key.GESTURES), writing == null, update, notes[rowId(Key.GESTURES, 0)]) }
        }
        if (has(Key.SIDETONE)) ListGroup("Calls") {
            item { KeySwitch(it, Key.SIDETONE, Glyph.MIC, "Hear my voice", "Your own voice in the earbuds while you talk", code(Key.SIDETONE, 0), busy(Key.SIDETONE), writing == null, update, notes[rowId(Key.SIDETONE, 0)]) }
        }
        if (has(Feature.AUTO_PAUSE) || has(Key.POWER)) ListGroup("Wearing") {
            if (has(Feature.AUTO_PAUSE)) item { FeatureSwitch(it, Feature.AUTO_PAUSE, Glyph.PAUSE_CIRCLE, "Pauses playback when you take an earbud out", features, featureLoading, basic, notes[Feature.AUTO_PAUSE.name]) }
            if (has(Key.POWER)) item { ChoiceRow(it, Glyph.POWER, "Automatic power off", choices(Key.POWER, 0), code(Key.POWER, 0), busy(Key.POWER), writing == null, note = notes[rowId(Key.POWER, 0)]) { c -> update(Key.POWER, 0, c) } }
        }
        if (has(Key.GUIDANCE)) ListGroup("Spoken prompts") {
            item { KeySwitch(it, Key.GUIDANCE, Glyph.CAMPAIGN, "Voice prompts", "Spoken battery and mode changes", code(Key.GUIDANCE, 0), busy(Key.GUIDANCE), writing == null, update, notes[rowId(Key.GUIDANCE, 0)]) }
            if (promptsOn && has(Key.GUIDANCE_VOLUME)) item { ChoiceRow(it, Glyph.VOLUME, "Prompt volume", choices(Key.GUIDANCE_VOLUME, 0), code(Key.GUIDANCE_VOLUME, 0), busy(Key.GUIDANCE_VOLUME), writing == null, note = notes[rowId(Key.GUIDANCE_VOLUME, 0)]) { c -> update(Key.GUIDANCE_VOLUME, 0, c) } }
        }
    }
}

/** Sony's two-state settings encode On as 0 and Off as 1. */
@Composable
private fun KeySwitch(
    shape: ListItemShapes, key: Key, glyph: Glyph, title: String, summary: String,
    code: Int?, busy: Boolean, enabled: Boolean, onUpdate: (Key, Int, Int) -> Unit, note: RowNote? = null,
) {
    SwitchRow(shape, glyph, title, summary, code?.let { it == 0 }, busy, enabled, note) { on -> onUpdate(key, 0, if (on) 0 else 1) }
}

@Composable
private fun FeatureSwitch(
    shape: ListItemShapes, feature: Feature, glyph: Glyph, summary: String,
    features: Map<Feature, Boolean>, loading: Set<Feature>, onBasic: (Feature, Boolean) -> Unit, note: RowNote? = null,
) {
    SwitchRow(shape, glyph, feature.title, summary, features[feature], feature in loading, loading.isEmpty(), note) { onBasic(feature, it) }
}
