@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.olex.sonyanc.AdvancedSettings.Key
import ua.olex.sonyanc.DeviceSettingsProtocol.Feature

/** Shows the remembered settings immediately and reads any values missing on this device. */
@Composable
fun EarbudSettingsScreen(onBack: () -> Unit, onAppSettings: () -> Unit = {}) {
    val ctx = LocalContext.current
    val values by Headphones.advanced.collectAsState()
    val features by Headphones.features.collectAsState()
    val device by Headphones.state.collectAsState()
    val cachedValues by remember(ctx) { Prefs.cachedAdvancedChanges(ctx) }
        .collectAsState(remember(ctx) { Prefs.cachedAdvanced(ctx) })
    val cachedFeatures by remember(ctx) { Prefs.cachedFeatureChanges(ctx) }
        .collectAsState(remember(ctx) { Prefs.cachedFeatures(ctx) })
    val shownValues = cachedValues + values
    val shownFeatures = cachedFeatures + features
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(emptySet<Key>()) }
    var featureLoading by remember { mutableStateOf(emptySet<Feature>()) }
    var scannedGeneration by remember { mutableStateOf<Long?>(null) }
    val scanLock = remember { Mutex() }
    var writing by remember { mutableStateOf<Key?>(null) }
    // What a change did, shown in the row it happened to (and in the other touch row when that one
    // changed by itself), never in a bar over the page. Each note fades after a few seconds.
    var notes by remember { mutableStateOf<Map<String, RowNote>>(emptyMap()) }
    fun note(id: String, note: RowNote) {
        notes = notes + (id to note)
        scope.launch { kotlinx.coroutines.delay(5000); if (notes[id] == note) notes = notes - id }
    }

    suspend fun load(force: Boolean = false) = scanLock.withLock {
        if (force && !Headphones.connectAndSync()) return@withLock
        val generation = Headphones.conn.generation
        if (!force && scannedGeneration == generation) return@withLock
        val knownKeys = Prefs.cachedAdvanced(ctx).keys + Headphones.advanced.value.keys
        val knownFeatures = Prefs.cachedFeatures(ctx).keys + Headphones.features.value.keys
        val keys = SETTINGS_ORDER.filter { force || it !in knownKeys }
        val basic = Feature.entries.filter { force || it !in knownFeatures }
        loading = keys.toSet()
        featureLoading = basic.toSet()
        try {
            for (feature in basic) {
                if (!Headphones.state.value.connected) break
                try { Headphones.readFeature(feature) } finally { featureLoading = featureLoading - feature }
            }
            for (key in keys) {
                if (!Headphones.state.value.connected) break
                try { Headphones.readAdvanced(key) } finally { loading = loading - key }
            }
            if (Headphones.state.value.connected && Headphones.conn.generation == generation) scannedGeneration = generation
        } finally {
            loading = emptySet()
            featureLoading = emptySet()
        }
    }
    LaunchedEffect(device.connected) { if (device.connected) load() }

    val panelRefused by EarbudSettingsLink.panelRefused.collectAsState()
    EarbudSettingsContent(
        shownValues, shownFeatures, loading, featureLoading, writing, notes, onBack, onAppSettings, panelHidden = panelRefused, hideMissing = scannedGeneration != null && (shownValues.isNotEmpty() || shownFeatures.isNotEmpty()),
        onRefresh = { scope.launch { load(force = true) } },
        onUpdate = { key, index, code ->
            if (writing == null && key !in loading) scope.launch {
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
            if (feature !in featureLoading) scope.launch {
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
    fun busy(key: Key) = key in loading || writing != null
    fun code(key: Key, index: Int): Int? = values[key]?.get(index)?.let { if (key == Key.TOUCH && it == 0x35) 0 else it }
    fun choices(key: Key, index: Int) = key.fields.first { it.index == index }.choices.map { it.code to it.label }

    PageScaffold("Earbud settings", onBack, actions = {
        HeaderActionPair(
            HeaderAction(Glyph.REFRESH, "Refresh settings", { if (idle) onRefresh() }),
            HeaderAction(Glyph.SETTINGS, "App settings", onAppSettings),
        )
    }) {
        // Rows the earbuds don't answer (while connected) are left out once reading is done (a setting this model
        // lacks is not a row to read "Not available" on), and the settings that only matter when
        // another is on (Speak-to-Chat's detection, the prompts' volume) follow it.
        fun has(key: Key) = !hideMissing || values[key] != null
        fun has(feature: Feature) = !hideMissing || features[feature] != null
        val chatOn = code(Key.CHAT, 0) == 0
        val promptsOn = code(Key.GUIDANCE, 0) == 0
        if (has(Key.TOUCH) || has(Key.GESTURES) || has(Key.CYCLE) || values[Key.PANEL] != null) {
            var side by rememberSaveable { mutableIntStateOf(1) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Touch and gestures")
                if (has(Key.TOUCH)) {
                    TouchAssignments(
                        side, { side = it }, choices(Key.TOUCH, side), code(Key.TOUCH, side),
                        busy(Key.TOUCH), notes[rowId(Key.TOUCH, side)],
                    ) { update(Key.TOUCH, side, it) }
                }
                ListGroup {
                    if (!panelHidden && values[Key.PANEL] != null) item {
                        val panel = values[Key.PANEL]
                        SwitchRow(it, Glyph.EARBUDS, "Touch controls", "Enable taps on the earbuds",
                            panel?.let(AdvancedSettings::panelOn), busy(Key.PANEL), note = notes[rowId(Key.PANEL, 1)]) { on ->
                            panel?.let { v -> update(Key.PANEL, 1, AdvancedSettings.panelCode(v, on)) }
                        }
                    }
                    if (has(Key.CYCLE)) item {
                        ChoiceRow(it, Glyph.NOISE_AWARE, "Noise control cycle", choices(Key.CYCLE, 4), code(Key.CYCLE, 4),
                            busy(Key.CYCLE), note = notes[rowId(Key.CYCLE, 4)]) { update(Key.CYCLE, 4, it) }
                    }
                    if (has(Key.GESTURES)) item {
                        KeySwitch(it, Key.GESTURES, Glyph.FACE, "Head gestures", "Nod or shake your head to answer calls",
                            code(Key.GESTURES, 0), busy(Key.GESTURES), true, update, notes[rowId(Key.GESTURES, 0)])
                    }
                }
            }
        }
        ListGroup("Sound") {
            if (has(Feature.DSEE)) item { FeatureSwitch(it, Feature.DSEE, Glyph.HQ, "Restores detail in compressed music", features, featureLoading, basic, notes[Feature.DSEE.name]) }
            if (has(Key.QUALITY)) item { ChoiceRow(it, Glyph.BLUETOOTH, "Bluetooth quality", choices(Key.QUALITY, 0), code(Key.QUALITY, 0), busy(Key.QUALITY), note = notes[rowId(Key.QUALITY, 0)]) { c -> update(Key.QUALITY, 0, c) } }
        }
        if (has(Key.CHAT)) ListGroup("Conversation") {
            item { KeySwitch(it, Key.CHAT, Glyph.CHAT, "Speak-to-Chat", "Pauses music when you start talking", code(Key.CHAT, 0), busy(Key.CHAT), true, update, notes[rowId(Key.CHAT, 0)]) }
            if (chatOn && has(Key.CHAT_CONFIG)) {
                item { ChoiceRow(it, Glyph.VOICE_SETTINGS, "Voice detection", choices(Key.CHAT_CONFIG, 0), code(Key.CHAT_CONFIG, 0), busy(Key.CHAT_CONFIG), note = notes[rowId(Key.CHAT_CONFIG, 0)]) { c -> update(Key.CHAT_CONFIG, 0, c) } }
                item { ChoiceRow(it, Glyph.AUTOPLAY, "Music comes back", choices(Key.CHAT_CONFIG, 1), code(Key.CHAT_CONFIG, 1), busy(Key.CHAT_CONFIG), note = notes[rowId(Key.CHAT_CONFIG, 1)]) { c -> update(Key.CHAT_CONFIG, 1, c) } }
            }
        }
        if (has(Key.SIDETONE)) ListGroup("Calls") {
            item { KeySwitch(it, Key.SIDETONE, Glyph.MIC, "Hear my voice", "Hear your voice during calls", code(Key.SIDETONE, 0), busy(Key.SIDETONE), true, update, notes[rowId(Key.SIDETONE, 0)]) }
        }
        if (has(Feature.AUTO_PAUSE) || has(Key.POWER)) ListGroup("Wearing") {
            if (has(Feature.AUTO_PAUSE)) item { FeatureSwitch(it, Feature.AUTO_PAUSE, Glyph.PAUSE_CIRCLE, "Pause when you remove an earbud", features, featureLoading, basic, notes[Feature.AUTO_PAUSE.name]) }
            if (has(Key.POWER)) item { ChoiceRow(it, Glyph.POWER, "Automatic power off", choices(Key.POWER, 0), code(Key.POWER, 0), busy(Key.POWER), note = notes[rowId(Key.POWER, 0)]) { c -> update(Key.POWER, 0, c) } }
        }
        if (has(Key.GUIDANCE)) ListGroup("Spoken prompts") {
            item { KeySwitch(it, Key.GUIDANCE, Glyph.CAMPAIGN, "Voice prompts", "Spoken battery and mode changes", code(Key.GUIDANCE, 0), busy(Key.GUIDANCE), true, update, notes[rowId(Key.GUIDANCE, 0)]) }
            if (promptsOn && has(Key.GUIDANCE_VOLUME)) item { ChoiceRow(it, Glyph.VOLUME, "Prompt volume", choices(Key.GUIDANCE_VOLUME, 0), code(Key.GUIDANCE_VOLUME, 0), busy(Key.GUIDANCE_VOLUME), note = notes[rowId(Key.GUIDANCE_VOLUME, 0)]) { c -> update(Key.GUIDANCE_VOLUME, 0, c) } }
        }
    }
}

@Composable
private fun TouchAssignments(
    side: Int, onSide: (Int) -> Unit, choices: List<Pair<Int, String>>, current: Int?,
    busy: Boolean, note: RowNote?, onChoose: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        for (ear in 1..2) {
            val chosen = side == ear
            ToggleButton(
                checked = chosen, onCheckedChange = { onSide(ear) },
                shapes = if (ear == 1) ButtonGroupDefaults.connectedLeadingButtonShapes()
                    else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.weight(1f).heightIn(min = UiTokens.minTouchTarget).semantics {
                    role = Role.RadioButton
                    selected = chosen
                },
            ) {
                Text(if (ear == 1) "Left earbud" else "Right earbud", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (current == null) {
        Text("Connect to read touch controls", style = MaterialTheme.typography.bodyMedium)
    } else {
        Column(Modifier.selectableGroup()) {
            ListGroup {
                for ((code, label) in choices) item { shape ->
                    val chosen = current == code
                    NavRow(
                        shape, null, label, note = note.takeIf { chosen },
                        modifier = Modifier.semantics { role = Role.RadioButton; selected = chosen },
                        trailing = { RadioButton(selected = chosen, onClick = null) },
                    ) { if (!busy && !chosen) onChoose(code) }
                }
            }
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
    SwitchRow(shape, glyph, feature.title, summary, features[feature], feature in loading, true, note) { onBasic(feature, it) }
}
