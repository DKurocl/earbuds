@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.animation.togetherWith
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.selected

/** Shows a short message at the bottom of the screen; no-op outside the app shell (tests, previews). */
val LocalMessages = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Scrollable device page with a single title row and stable touch targets. */
@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = Surfaces.page,
        topBar = { DeviceTopBar(title, onBack, actions = actions) },
    ) { insets ->
        Column(
            Modifier.padding(insets).consumeWindowInsets(insets).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = UiTokens.pageInset),
            verticalArrangement = Arrangement.spacedBy(UiTokens.pageSectionGap),
            content = { content(); Spacer(Modifier.height(12.dp)) },
        )
    }
}

@Composable
internal fun DeviceTopBar(title: String, onBack: (() -> Unit)? = null, subtitle: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit) {
    val page = Surfaces.page
    TopAppBar(
        title = {
            Column(Modifier.padding(start = if (onBack != null) 12.dp else 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // As Android 17's Settings: one regular-weight 28 sp title on every page, Home too.
                val titleStyle = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, lineHeight = 36.sp)
                Text(title, style = titleStyle, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                subtitle?.invoke()
            }
        },
        navigationIcon = {
            if (onBack != null) HeaderActionButton(HeaderAction(Glyph.BACK, "Back", onBack), Modifier.padding(start = 8.dp))
        },
        actions = { Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = page, scrolledContainerColor = page),
    )
}

internal data class HeaderAction(val glyph: Glyph, val label: String, val onClick: () -> Unit, val enabled: Boolean = true)

@Composable
internal fun HeaderActionButton(action: HeaderAction, modifier: Modifier = Modifier, leading: Boolean? = null) {
    val cs = MaterialTheme.colorScheme
    // A plain tonal circle, as Android 17's Settings draws its back button; actions sit apart, never joined.
    FilledTonalIconButton(
        onClick = action.onClick, enabled = action.enabled,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = cs.surfaceContainerHigh, contentColor = cs.onSurfaceVariant,
        ),
        shapes = IconButtonDefaults.shapes(shape = CircleShape, pressedShape = RoundedCornerShape(16.dp)),
        modifier = modifier.size(UiTokens.minTouchTarget).semantics { contentDescription = action.label },
    ) { HushIcon(action.glyph, Modifier.size(22.dp)) }
}

@Composable
internal fun HeaderActionPair(start: HeaderAction, end: HeaderAction, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeaderActionButton(start)
        HeaderActionButton(end)
    }
}

class GroupScope {
    internal val items = mutableListOf<@Composable (ListItemShapes) -> Unit>()
    fun item(content: @Composable (ListItemShapes) -> Unit) { items += content }
}

/**
 * A group of Material 3 segmented list items with the library's connected row shapes and gap.
 */
@Composable
fun ListGroup(title: String? = null, build: GroupScope.() -> Unit) {
    val scope = GroupScope().apply(build)
    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.sectionLabelGap)) {
        title?.let { SectionTitle(it) }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            scope.items.forEachIndexed { i, item -> item(ListItemDefaults.segmentedShapes(index = i, count = scope.items.size)) }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() }.padding(start = 16.dp, top = 8.dp),
    )
}

@Composable
private fun rowColors() = ListItemDefaults.segmentedColors(containerColor = Surfaces.card, disabledContainerColor = Surfaces.card)


/**
 * A row's icon in a light circle coloured by what it is about, as Android's own Settings (and
 * Music): sound, power, connection, the app. The "fixed" tones of the wallpaper's palette read the
 * same in light and dark; a destructive row takes the error colour.
 */
/**
 * The top level of Android Settings marks each entry with a bright disc of a fixed hue and a filled
 * symbol in a deep tone of the same hue (sound pink, connections cyan, battery green...). Only the
 * pages that lead somewhere get one; settings inside a page keep the plain symbol.
 */
enum class Accent(val disc: Long, val ink: Long) {
    SOUND(0xFFFFAFD8, 0xFF5F1145), CONNECTION(0xFF7DD3F7, 0xFF003548), APPS(0xFFA8C7FA, 0xFF0B305F),
    BATTERY(0xFF86D993, 0xFF003916), DISPLAY(0xFFFFB68A, 0xFF552100), STORAGE(0xFFFDC424, 0xFF3F2E00), SYSTEM(0xFFC4C7C5, 0xFF2D3133),
}

@Composable
private fun accentDisc(glyph: Glyph, accent: Accent) {
    Surface(shape = CircleShape, color = Color(accent.disc), modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { HushIcon(glyph, Modifier.size(24.dp), Color(accent.ink), filled = true) }
    }
}

// Inside a page Android Settings draws no row icons at all (Battery and charging, Battery health):
// only entries that open a section carry one, in a coloured disc (see Accent). Rows here are text.
@Suppress("UNUSED_PARAMETER")
private fun leading(glyph: Glyph?, danger: Boolean = false): (@Composable () -> Unit)? = null

@Composable
fun NavRow(
    shape: ListItemShapes, glyph: Glyph?, title: String, summary: String? = null,
    enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, danger: Boolean = false, note: RowNote? = null,
    modifier: Modifier = Modifier, accent: Accent? = null, onClick: () -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    SegmentedListItem(
        onClick = onClick, shapes = shape, enabled = enabled,
        colors = if (danger) ListItemDefaults.segmentedColors(containerColor = Surfaces.card, contentColor = error, leadingContentColor = error) else rowColors(),
        leadingContent = if (accent != null && glyph != null) ({ accentDisc(glyph, accent) }) else leading(glyph, danger), trailingContent = trailing,
        supportingContent = supporting(summary, note),
        modifier = modifier.fillMaxWidth(),
    ) { Text(title) }
}

/**
 * What a change just did, said in the row it happened to: in the primary colour, or the error
 * colour when the earbuds refused it. It replaces the row's summary for a few seconds and fades
 * back, so nothing floats over the page and nothing has to be dismissed.
 */
data class RowNote(val text: String, val error: Boolean = false)

private fun supporting(summary: String?, note: RowNote?): (@Composable () -> Unit)? {
    if (summary == null && note == null) return null
    return {
        val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        androidx.compose.animation.AnimatedContent(
            note to summary, label = "row note",
            transitionSpec = { androidx.compose.animation.fadeIn(enter) togetherWith androidx.compose.animation.fadeOut(exit) },
        ) { (n, text) ->
            if (n != null) Text(n.text, color = if (n.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            else if (text != null) Text(text)
        }
    }
}

/**
 * A value to read, not to tap. A null [value] is still being read: a quiet placeholder bar holds
 * its place (as Google's apps do while loading), so the row does not jump when the answer comes.
 */
@Composable
fun InfoRow(shape: ListItemShapes, glyph: Glyph?, title: String, value: String?) {
    ListItem(
        supportingContent = { if (value != null) Text(value) else ValuePlaceholder() },
        leadingContent = leading(glyph),
        colors = ListItemDefaults.colors(containerColor = Surfaces.card),
        modifier = Modifier.fillMaxWidth().clip(shape.shape),
    ) { Text(title) }
}

/** A static bar the height of a line of supporting text: no animation, so nothing redraws while it waits. */
@Composable
private fun ValuePlaceholder() {
    androidx.compose.foundation.layout.Box(
        Modifier.padding(vertical = 4.dp).size(width = 96.dp, height = 12.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics { contentDescription = "Reading" },
    )
}

/**
 * A setting switch. The whole row toggles; the row itself stays neutral and the switch shows
 * the state, as in Android settings. While [busy] (the earbuds are being asked, or a change is
 * on its way) the row looks exactly the same and ignores taps: the last known value is shown
 * and quietly corrected if the earbuds answer otherwise, so nothing jumps. [checked] null means
 * the setting was never read: the row says so and is not toggleable.
 */
@Composable
fun SwitchRow(
    shape: ListItemShapes, glyph: Glyph?, title: String, summary: String?,
    checked: Boolean?, busy: Boolean = false, enabled: Boolean = true, note: RowNote? = null, onChange: (Boolean) -> Unit,
) {
    if (checked == null) {
        InfoRow(shape, glyph, title, if (busy) null else "Not available")
        return
    }
    val haptics = LocalHapticFeedback.current
    // One press for the row and the switch: the thumb grows under the finger, as in Android Settings.
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    SegmentedListItem(
        interactionSource = press,
        onClick = {
            if (busy) return@SegmentedListItem
            // The same toggle feel as Android's own settings switches.
            haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onChange(!checked)
        },
        shapes = shape, enabled = enabled, colors = rowColors(),
        leadingContent = leading(glyph), supportingContent = supporting(summary, note),
        trailingContent = {
            // Android's own switch slides without overshoot; the expressive scheme's bouncy spring
            // made the thumb wobble as it landed, so the switch alone keeps the standard motion.
            MaterialTheme(motionScheme = MotionScheme.standard()) {
                Switch(
                    checked = checked, onCheckedChange = null, enabled = enabled, interactionSource = press,
                    thumbContent = if (checked) ({ HushIcon(Glyph.CHECK, Modifier.size(SwitchDefaults.IconSize), MaterialTheme.colorScheme.primary) }) else null,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Switch; toggleableState = ToggleableState(checked) },
    ) { Text(title) }
}

/** A multiple-choice setting: shows the current value and opens a radio dialog, like Android settings. */
@Composable
fun ChoiceRow(
    shape: ListItemShapes, glyph: Glyph?, title: String, choices: List<Pair<Int, String>>, selected: Int?,
    busy: Boolean = false, enabled: Boolean = true, note: RowNote? = null, onSelect: (Int) -> Unit,
) {
    if (selected == null) {
        InfoRow(shape, glyph, title, if (busy) null else "Not available")
        return
    }
    val haptics = LocalHapticFeedback.current
    // As Android Settings: the row shows the current value; a tap opens a page of its own with the
    // options as radio rows (Screen timeout, Dark theme). Choosing one goes straight back.
    var open by remember { mutableStateOf(false) }
    val chosen = choices.firstOrNull { it.first == selected }?.second ?: "Device setting"
    NavRow(shape, glyph, title, chosen, enabled = enabled, note = note) { if (!busy) open = true }
    if (open) ChoicePage(title, choices, selected, onDismiss = { open = false }) { code ->
        open = false
        if (code != selected) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onSelect(code)
        }
    }
}

@Composable
private fun ChoicePage(title: String, choices: List<Pair<Int, String>>, selected: Int?, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        PageScaffold(title, onDismiss) {
            ListGroup {
                choices.forEach { (code, label) ->
                    item { shape ->
                        SegmentedListItem(
                            onClick = { onSelect(code) }, shapes = shape, colors = rowColors(),
                            leadingContent = { RadioButton(selected = code == selected, onClick = null) },
                            modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton; this.selected = code == selected },
                        ) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
fun ChoiceDialog(title: String, choices: List<Pair<Int, String>>, selected: Int?, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                choices.forEach { (code, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(24.dp))
                            .selectable(selected = code == selected, role = Role.RadioButton) { onSelect(code) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RadioButton(selected = code == selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Text whose digits roll when they change, each on its own: up when the digit grows, down when
 * it falls, with Material's fast spatial spring. Digits are tabular, so nothing beside them moves.
 * At the largest font sizes the whole line scales down to fit its width instead of being cut.
 */
@Composable
fun RollingText(
    text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val base = style.withTabularFigures().copy(color = if (color.isSpecified) color else style.color)
    // No BoxWithConstraints: this sits in rows sized by intrinsics. A layout modifier scales the
    // finished line down when it is wider than its space.
    Row(
        modifier.clearAndSetSemantics { contentDescription = text }.layout { measurable, constraints ->
            val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
            val scale = if (constraints.hasBoundedWidth && p.width > constraints.maxWidth) constraints.maxWidth.toFloat() / p.width else 1f
            val w = (p.width * scale).toInt().coerceIn(constraints.minWidth, constraints.maxWidth)
            layout(w, (p.height * scale).toInt()) {
                p.placeWithLayer(0, 0) { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
            }
        },
    ) {
            text.forEachIndexed { i, ch ->
                // Keyed from the right, so units stay units when the number gains a digit.
                key(text.length - i) {
                    androidx.compose.animation.AnimatedContent(
                        ch,
                        transitionSpec = {
                            val digits = initialState.isDigit() && targetState.isDigit()
                            val up = targetState > initialState
                            if (!digits) androidx.compose.animation.fadeIn(effects) togetherWith androidx.compose.animation.fadeOut(effects)
                            else (androidx.compose.animation.slideInVertically(spatial) { h -> if (up) h else -h } + androidx.compose.animation.fadeIn(effects)) togetherWith
                                (androidx.compose.animation.slideOutVertically(spatial) { h -> if (up) -h else h } + androidx.compose.animation.fadeOut(effects)) using
                                androidx.compose.animation.SizeTransform(clip = true)
                        },
                        label = "digit",
                    ) { c -> Text(c.toString(), style = base, maxLines = 1, softWrap = false) }
                }
            }
    }
}

/**
 * Android's main switch: the one on/off that the whole page depends on ("Use charging
 * optimisation"), a tall pill in the primary container colour above the page's other settings.
 */
@Composable
fun MainSwitchBar(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = {
            haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onChange(!checked)
        },
        enabled = enabled, shape = RoundedCornerShape(36.dp), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer,
        interactionSource = press,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics { role = Role.Switch; toggleableState = ToggleableState(checked) },
    ) {
        Row(Modifier.padding(start = 28.dp, end = 20.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), modifier = Modifier.weight(1f))
            MaterialTheme(motionScheme = MotionScheme.standard()) {
                Switch(
                    checked = checked, onCheckedChange = null, enabled = enabled, interactionSource = press,
                    thumbContent = if (checked) ({ HushIcon(Glyph.CHECK, Modifier.size(SwitchDefaults.IconSize), cs.primary) }) else null,
                )
            }
        }
    }
}
