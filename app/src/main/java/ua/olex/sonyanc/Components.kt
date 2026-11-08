@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
            LargeTopAppBar(
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

/** Grouped list corners: large outside the group, small between items (Android 16 settings). */
fun groupShape(index: Int, count: Int): Shape {
    val big = 24.dp
    val small = 4.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small, topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small, bottomEnd = if (index == count - 1) big else small,
    )
}

class GroupScope {
    internal val items = mutableListOf<@Composable (Shape) -> Unit>()
    fun item(content: @Composable (Shape) -> Unit) { items += content }
}

@Composable
fun ListGroup(title: String? = null, build: GroupScope.() -> Unit) {
    val scope = GroupScope().apply(build)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { SectionTitle(it) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            scope.items.forEachIndexed { i, item -> item(groupShape(i, scope.items.size)) }
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

/** The common row body: optional icon, title, optional summary, optional trailing content. */
@Composable
private fun RowBody(glyph: Glyph?, title: String, summary: String?, enabled: Boolean, trailing: (@Composable () -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else .5f
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        glyph?.let { HushIcon(it, color = cs.onSurfaceVariant.copy(alpha = alpha)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface.copy(alpha = alpha))
            summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant.copy(alpha = alpha)) }
        }
        trailing?.invoke()
    }
}

@Composable
fun NavRow(
    shape: Shape, glyph: Glyph?, title: String, summary: String? = null,
    enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, shape = shape, color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
        RowBody(glyph, title, summary, enabled, trailing)
    }
}

@Composable
fun InfoRow(shape: Shape, glyph: Glyph?, title: String, value: String) {
    Surface(shape = shape, color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
        RowBody(glyph, title, value, true, null)
    }
}

/**
 * A setting switch. The whole row toggles. [checked] null means the earbuds did not report
 * the setting: the row then says why and is not toggleable at all.
 */
@Composable
fun SwitchRow(
    shape: Shape, glyph: Glyph?, title: String, summary: String?,
    checked: Boolean?, busy: Boolean = false, enabled: Boolean = true, onChange: (Boolean) -> Unit,
) {
    val usable = checked != null && !busy && enabled
    val shownSummary = when {
        busy && checked == null -> "Reading…"
        checked == null -> "Not available"
        else -> summary
    }
    val toggle = if (checked != null && !busy) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange) else Modifier
    Surface(shape = shape, color = Surfaces.card, modifier = Modifier.fillMaxWidth().then(toggle)) {
        RowBody(glyph, title, shownSummary, checked != null) {
            when {
                busy && checked != null -> LoadingIndicator(Modifier.size(32.dp))
                checked != null -> Switch(
                    checked = checked, onCheckedChange = null, enabled = usable,
                    thumbContent = if (checked) ({ HushIcon(Glyph.CHECK, Modifier.size(SwitchDefaults.IconSize), MaterialTheme.colorScheme.primary) }) else null,
                )
            }
        }
    }
}

/** A multiple-choice setting: shows the current value and opens a radio dialog, like Android settings. */
@Composable
fun ChoiceRow(
    shape: Shape, glyph: Glyph?, title: String, choices: List<Pair<Int, String>>, selected: Int?,
    busy: Boolean = false, enabled: Boolean = true, onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val summary = when {
        busy && selected == null -> "Reading…"
        selected == null -> "Not available"
        else -> choices.firstOrNull { it.first == selected }?.second ?: "Device setting"
    }
    Surface(
        onClick = { open = true }, enabled = selected != null && !busy && enabled,
        shape = shape, color = Surfaces.card, modifier = Modifier.fillMaxWidth(),
    ) {
        RowBody(glyph, title, summary, selected != null) { if (busy && selected != null) LoadingIndicator(Modifier.size(32.dp)) }
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
