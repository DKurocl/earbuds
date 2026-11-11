@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
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
            Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = { content(); Spacer(Modifier.height(12.dp)) },
        )
    }
}

class GroupScope {
    internal val items = mutableListOf<@Composable (ListItemShapes) -> Unit>()
    fun item(content: @Composable (ListItemShapes) -> Unit) { items += content }
}

/**
 * A group of Material 3 segmented list items, as in Android 16 settings: 16 dp outside, 4 dp
 * between, 2 dp apart, and the pressed row morphs to fully rounded corners.
 */
@Composable
fun ListGroup(title: String? = null, build: GroupScope.() -> Unit) {
    val scope = GroupScope().apply(build)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { SectionTitle(it) }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            scope.items.forEachIndexed { i, item -> item(ListItemDefaults.segmentedShapes(index = i, count = scope.items.size)) }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 4.dp),
    )
}

@Composable
private fun rowColors() = ListItemDefaults.segmentedColors(containerColor = Surfaces.card)

private fun leading(glyph: Glyph?): (@Composable () -> Unit)? = glyph?.let { { HushIcon(it, color = LocalContentColor.current) } }

@Composable
fun NavRow(
    shape: ListItemShapes, glyph: Glyph?, title: String, summary: String? = null,
    enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick, shapes = shape, enabled = enabled, colors = rowColors(),
        leadingContent = leading(glyph), trailingContent = trailing,
        supportingContent = summary?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(title) }
}

/** A value to read, not to tap. */
@Composable
fun InfoRow(shape: ListItemShapes, glyph: Glyph?, title: String, value: String) {
    ListItem(
        headlineContent = { Text(title) }, supportingContent = { Text(value) }, leadingContent = leading(glyph),
        colors = ListItemDefaults.colors(containerColor = Surfaces.card),
        modifier = Modifier.fillMaxWidth().clip(shape.shape),
    )
}

/**
 * A setting switch. The whole row toggles; the row itself stays neutral and the switch shows
 * the state, as in Android settings. [checked] null means the earbuds did not report the
 * setting: the row then says why and is not toggleable at all.
 */
@Composable
fun SwitchRow(
    shape: ListItemShapes, glyph: Glyph?, title: String, summary: String?,
    checked: Boolean?, busy: Boolean = false, enabled: Boolean = true, onChange: (Boolean) -> Unit,
) {
    if (checked == null) {
        InfoRow(shape, glyph, title, if (busy) "Reading…" else "Not available")
        return
    }
    SegmentedListItem(
        onClick = { onChange(!checked) }, shapes = shape, enabled = enabled && !busy, colors = rowColors(),
        leadingContent = leading(glyph), supportingContent = summary?.let { { Text(it) } },
        trailingContent = {
            if (busy) LoadingIndicator(Modifier.size(32.dp))
            else Switch(
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
    busy: Boolean = false, enabled: Boolean = true, onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    if (selected == null) {
        InfoRow(shape, glyph, title, if (busy) "Reading…" else "Not available")
        return
    }
    NavRow(
        shape, glyph, title, choices.firstOrNull { it.first == selected }?.second ?: "Device setting",
        enabled = !busy && enabled, trailing = if (busy) ({ LoadingIndicator(Modifier.size(32.dp)) }) else null,
    ) { open = true }
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

/** Problem with the link, shown where the user is looking (under the device header). */
@Composable
fun ErrorBanner(message: String, onRetry: () -> Unit, onBluetooth: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = cs.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer)
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onBluetooth) { Text("Bluetooth settings", color = cs.onErrorContainer) }
                TextButton(onClick = onRetry) { Text("Retry", color = cs.onErrorContainer, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
