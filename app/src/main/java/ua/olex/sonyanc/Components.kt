@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

/** Shows a short message at the bottom of the screen; no-op outside the app shell (tests, previews). */
val LocalMessages = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * A full-screen page with a large title that collapses on scroll, like Android settings.
 * Content scrolls in a Column so every row stays reachable for tests and accessibility.
 */
@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // A page that fits the screen doesn't scroll at all: the title collapses only when there is
    // something below to scroll to.
    val column = rememberScrollState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(canScroll = { column.maxValue > 0 })
    val page = Surfaces.page
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = page,
        topBar = {
            // M3 Expressive's large flexible app bar: the title collapses into the bar on scroll.
            LargeFlexibleTopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) { HushIcon(Glyph.BACK) }
                },
                actions = actions,
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = page, scrolledContainerColor = page),
            )
        },
    ) { insets ->
        Column(
            Modifier.padding(insets).fillMaxSize().verticalScroll(column)
                .padding(horizontal = UiTokens.pageInset).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(UiTokens.pageSectionGap),
            content = { content(); Spacer(Modifier.height(12.dp)) },
        )
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
        text, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() }.padding(start = 12.dp, top = 6.dp),
    )
}

@Composable
private fun rowColors() = ListItemDefaults.segmentedColors(containerColor = Surfaces.card)

private val linkGlyphs = setOf(
    Glyph.BLUETOOTH, Glyph.LINK, Glyph.EARBUDS, Glyph.INFO, Glyph.WIDGET, Glyph.FIND, Glyph.COPY,
    Glyph.SWIPE_LEFT, Glyph.SWIPE_RIGHT, Glyph.FACE,
)
private val routineGlyphs = setOf(
    Glyph.HOME, Glyph.LOCATION, Glyph.TIMER, Glyph.BATTERY, Glyph.POWER, Glyph.UPCOMING, Glyph.BATTERY_LOW,
    Glyph.BATTERY_CHARGING, Glyph.BEDTIME, Glyph.WALK, Glyph.INSIGHTS, Glyph.CHAT, Glyph.VOICE_SETTINGS,
    Glyph.AUTOPLAY, Glyph.MIC, Glyph.CAMPAIGN, Glyph.VOLUME,
)

/**
 * A row's icon in a light circle coloured by what it is about, as Android's own Settings (and
 * Music): sound, power, connection, the app. The "fixed" tones of the wallpaper's palette read the
 * same in light and dark; a destructive row takes the error colour.
 */
private fun leading(glyph: Glyph?, danger: Boolean = false): (@Composable () -> Unit)? = glyph?.let { g -> {
    val cs = MaterialTheme.colorScheme
    val (container, glyphColor, shape) = when {
        danger -> Triple(cs.errorContainer, cs.onErrorContainer, RoundedCornerShape(12.dp))
        g in linkGlyphs -> Triple(cs.secondaryFixed, cs.onSecondaryFixed, RoundedCornerShape(13.dp))
        g in routineGlyphs -> Triple(cs.tertiaryFixedDim, cs.onTertiaryFixed, RoundedCornerShape(16.dp))
        else -> Triple(cs.primaryFixedDim, cs.onPrimaryFixed, CircleShape)
    }

    Surface(shape = shape, color = container, contentColor = glyphColor, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { HushIcon(g, Modifier.size(22.dp), glyphColor) }
    }
} }

@Composable
fun NavRow(
    shape: ListItemShapes, glyph: Glyph?, title: String, summary: String? = null,
    enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, danger: Boolean = false, note: RowNote? = null, onClick: () -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    SegmentedListItem(
        onClick = onClick, shapes = shape, enabled = enabled,
        colors = if (danger) ListItemDefaults.segmentedColors(containerColor = Surfaces.card, contentColor = error, leadingContentColor = error) else rowColors(),
        leadingContent = leading(glyph, danger), trailingContent = trailing,
        supportingContent = supporting(summary, note),
        modifier = Modifier.fillMaxWidth(),
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
    SegmentedListItem(
        onClick = {
            if (busy) return@SegmentedListItem
            // The same toggle feel as Android's own settings switches.
            haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onChange(!checked)
        },
        shapes = shape, enabled = enabled, colors = rowColors(),
        leadingContent = leading(glyph), supportingContent = supporting(summary, note),
        trailingContent = {
            Switch(
                checked = checked, onCheckedChange = null, enabled = enabled,
                thumbContent = if (checked) ({ HushIcon(Glyph.CHECK, Modifier.size(SwitchDefaults.IconSize), MaterialTheme.colorScheme.primary) }) else null,
            )
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
    var open by remember { mutableStateOf(false) }
    if (selected == null) {
        InfoRow(shape, glyph, title, if (busy) null else "Not available")
        return
    }
    // Same quiet rule as SwitchRow: the last known value, no spinner, taps wait for the answer.
    NavRow(shape, glyph, title, choices.firstOrNull { it.first == selected }?.second ?: "Device setting", enabled = enabled, note = note) {
        if (!busy) open = true
    }
    if (open) ChoiceDialog(title, choices, selected, onDismiss = { open = false }) { open = false; onSelect(it) }
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
