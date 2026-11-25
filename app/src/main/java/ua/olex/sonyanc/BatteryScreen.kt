@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import ua.olex.sonyanc.SonyProtocol.Mode
import java.util.Date
import kotlin.math.abs

@Composable
fun BatteryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val device by Headphones.state.collectAsState()
    val log = remember(device.batterySeenAt) { Prefs.batteryLog(ctx) }
    val health = remember(log) { Prefs.batteryHealth(ctx) }
    val now = remember(log) { System.currentTimeMillis() }
    BatteryContent(log, now, onBack, health, device)
}

@Composable
fun BatteryContent(log: List<BatterySample>, now: Long, onBack: () -> Unit = {}, health: List<WeekHealth> = emptyList(), device: DeviceState? = null) {
    val recent = remember(log, now) { log.filter { now - it.at in 0..HISTORY_MS }.sortedBy { it.at } }
    val verdict = remember(health) { BatteryHealth.verdict(health) }
    var details by rememberSaveable { mutableStateOf(false) }
    val observed = if (details) remember(log, now) { BatteryLog.week(log, now) } else null
    PageScaffold("Battery", onBack) {
        if (device?.battery != null) ListeningEstimate(log, device)
        ChargeHistory(recent, now)
        ListGroup {
            if (verdict != null) item { HealthRow(it, verdict) }
            item {
                NavRow(
                    it, null, "Battery details",
                    trailing = {
                        val rotation by animateFloatAsState(
                            if (details) 180f else 0f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "Battery details",
                        )
                        val color = LocalContentColor.current
                        Canvas(Modifier.size(24.dp).rotate(rotation)) {
                            val chevron = Path().apply {
                                moveTo(size.width * .28f, size.height * .4f)
                                lineTo(size.width * .5f, size.height * .6f)
                                lineTo(size.width * .72f, size.height * .4f)
                            }
                            drawPath(chevron, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    },
                    modifier = Modifier.semantics { stateDescription = if (details) "Expanded" else "Collapsed" },
                ) { details = !details }
            }
            if (observed != null) {
                if (observed.minutes >= 30) item {
                    val anc = if (observed.anc > 0) "${BatteryLife.short(observed.anc)} with noise cancelling" else null
                    InfoRow(it, null, "This week", listOfNotNull("About ${BatteryLife.short(observed.minutes)} connected", anc).joinToString(" · "))
                }
                item { DrainRow(it, "Noise cancelling & ambient", log, Mode.ANC) }
                item { DrainRow(it, "Noise control off", log, Mode.OFF) }
            }
        }
    }
}

@Composable
private fun ListeningEstimate(log: List<BatterySample>, device: DeviceState) {
    val battery = BatteryLife.forEstimate(device.battery ?: return, device.liveBatteryParts)
    val mode = device.nc?.mode
    val measured = remember(log, mode) { BatteryLog.drain(log, mode) }
    val perEar = remember(log, mode) { BatteryLog.perEarbudDrain(log, mode) }
    val minutes = perEar?.minutesLeft(battery, mode) ?: BatteryLife.budsMinutes(battery, mode, measured?.rate)
    val reserve = BatteryLife.caseMinutes(battery, mode, measured?.rate)?.takeIf { it >= 10 }
    if (minutes == null && reserve == null) return
    val budsLive = 0 in device.liveBatteryParts || 1 in device.liveBatteryParts
    val caseLive = 2 in device.liveBatteryParts
    val cs = MaterialTheme.colorScheme
    val source = if (measured != null || perEar != null) "Based on your use" else "Sony estimate"
    Surface(shape = UiTokens.cardShape, color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (minutes != null) {
                Text(if (budsLive) "Listening left" else "Last reading", style = MaterialTheme.typography.titleMedium)
                Text(
                    BatteryLife.short(minutes), style = MaterialTheme.typography.displayLargeEmphasized.withTabularFigures(),
                    color = cs.primary,
                )
                Text(if (budsLive) source else "Listening estimate · $source", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
            if (reserve != null) {
                val label = if (caseLive) "From the case" else "From the case · last reading"
                Row(
                    Modifier.fillMaxWidth().padding(top = if (minutes != null) 14.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_case_mark), null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Text("+${BatteryLife.short(reserve)}", style = MaterialTheme.typography.labelLarge.withTabularFigures())
                }
            }
        }
    }
}

private const val HOUR_MS = 60 * 60_000L
private const val CHART_GAP_MS = 45 * 60_000L
private const val HISTORY_MS = 7 * 24 * HOUR_MS

private enum class Part(val label: String, val level: (BatterySample) -> Int?) {
    LEFT("Left", BatterySample::left),
    RIGHT("Right", BatterySample::right),
    CASE("Case", BatterySample::case),
    EARBUDS("Earbuds", BatterySample::level),
}

private data class Reading(val at: Long, val percent: Int, val session: Int)

@Composable
private fun ChargeHistory(recent: List<BatterySample>, now: Long) {
    val cs = MaterialTheme.colorScheme
    val parts = remember(recent) {
        if (recent.any { it.left != null || it.right != null }) listOf(Part.LEFT, Part.RIGHT, Part.CASE)
        else listOf(Part.EARBUDS, Part.CASE)
    }
    val defaultPart = remember(recent, parts) {
        parts.firstOrNull { part -> recent.any { part.level(it) != null } } ?: parts.first()
    }
    var chosen by rememberSaveable { mutableStateOf<Part?>(null) }
    val part = chosen?.takeIf { it in parts } ?: defaultPart
    Surface(shape = UiTokens.cardShape, color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Charge history", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.semantics { heading() })
            if (recent.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    parts.forEachIndexed { index, option ->
                        ToggleButton(
                            checked = part == option, onCheckedChange = { chosen = option },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                parts.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton; selected = part == option },
                            colors = ToggleButtonDefaults.colors(containerColor = cs.surfaceContainerHighest),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) { Text(option.label, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
            if (recent.isEmpty()) {
                Box(Modifier.fillMaxWidth().heightIn(min = 88.dp), contentAlignment = Alignment.Center) {
                    Text("No readings yet", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            } else {
                val end = recent.last().at
                val start = minOf(recent.first().at, end - 2 * HOUR_MS).coerceAtLeast(now - HISTORY_MS)
                Crossfade(part, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "Charge history") { shownPart ->
                    val readings = remember(recent, shownPart) {
                        recent.asReversed().mapNotNull { sample ->
                            shownPart.level(sample)?.let { Reading(sample.at, it, sample.session) }
                        }.distinctBy { it.at }.asReversed()
                    }
                    key(shownPart) { HistoryChart(shownPart, readings, start, end.coerceAtLeast(start + 1)) }
                }
            }
        }
    }
}

@Composable
private fun HistoryChart(part: Part, readings: List<Reading>, start: Long, end: Long) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val ink = cs.onSurfaceVariant
    val grid = cs.outlineVariant.copy(alpha = .6f)
    val series = if (part == Part.CASE) cs.tertiary else cs.primary
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = ink)
    val timeFormat = remember(ctx) { DateFormat.getTimeFormat(ctx) }
    val dateFormat = remember(ctx) { DateFormat.getMediumDateFormat(ctx) }
    val startDay = DateFormat.format("EEE d", start).toString()
    val endDay = DateFormat.format("EEE d", end).toString()
    val middleDay = if (end - start >= 4 * 24 * HOUR_MS) {
        DateFormat.format("EEE d", start + (end - start) / 2).toString()
    } else null
    val pickedAt = rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedTime = pickedAt.value
    val index = readings.indexOfFirst { it.at == selectedTime }.takeIf { it >= 0 } ?: readings.lastIndex
    val shown = readings.getOrNull(index)
    val readableDate = shown?.let { "${dateFormat.format(Date(it.at))} · ${timeFormat.format(Date(it.at))}" } ?: "No readings yet"
    val axisWidth = with(LocalDensity.current) {
        measurer.measure("100%", axisStyle).size.width.toDp() + 12.dp
    }
    fun pick(x: Float, width: Float, inset: Float, edge: Float) {
        val span = (width - inset - edge).coerceAtLeast(1f)
        val at = start + (((x - inset) / span).coerceIn(0f, 1f).toDouble() * (end - start)).toLong()
        pickedAt.value = readings.minByOrNull { abs(it.at - at) }?.at
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(shown?.let { "${it.percent}%" } ?: "–", style = MaterialTheme.typography.headlineSmallEmphasized.withTabularFigures(), color = if (shown != null) series else ink)
            Text(readableDate, style = MaterialTheme.typography.bodySmall, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(
            Modifier.fillMaxWidth().height(152.dp)
                .pointerInput(readings, start, end, axisWidth) {
                    detectTapGestures { pick(it.x, size.width.toFloat(), axisWidth.toPx(), 6.dp.toPx()) }
                }
                .pointerInput(readings, start, end, axisWidth) {
                    detectHorizontalDragGestures(
                        onDragStart = { pick(it.x, size.width.toFloat(), axisWidth.toPx(), 6.dp.toPx()) },
                    ) { change, _ -> pick(change.position.x, size.width.toFloat(), axisWidth.toPx(), 6.dp.toPx()) }
                }
                .semantics {
                    contentDescription = "${part.label} charge history"
                    stateDescription = shown?.let { "${it.percent}%, $readableDate" } ?: readableDate
                    customActions = buildList {
                        if (index > 0) add(CustomAccessibilityAction("Earlier reading") {
                            pickedAt.value = readings[index - 1].at
                            true
                        })
                        if (index < readings.lastIndex) add(CustomAccessibilityAction("Later reading") {
                            pickedAt.value = readings[index + 1].at
                            true
                        })
                    }
                }
                .drawWithCache {
                    val firstX = axisWidth.toPx()
                    val lastX = (size.width - 6.dp.toPx()).coerceAtLeast(firstX + 1f)
                    val bottomLabel = measurer.measure(startDay, axisStyle)
                    val endLabel = measurer.measure(endDay, axisStyle)
                    val middleLabel = middleDay?.let { measurer.measure(it, axisStyle) }
                    val top = bottomLabel.size.height / 2f + 2.dp.toPx()
                    val bottom = size.height - bottomLabel.size.height - 10.dp.toPx()
                    fun x(at: Long) = firstX + (at - start).toFloat() / (end - start) * (lastX - firstX)
                    fun y(value: Int) = bottom - (bottom - top) * value / 100f
                    val axis = listOf(100, 50, 0).map { measurer.measure("$it%", axisStyle) to y(it) }
                    val line = Path()
                    val dots = mutableListOf<Offset>()
                    var previous: Reading? = null
                    for (reading in readings) {
                        val position = Offset(x(reading.at), y(reading.percent))
                        val prior = previous
                        val gap = prior == null || reading.at - prior.at > CHART_GAP_MS ||
                            (part != Part.CASE && prior.session != reading.session)
                        if (gap) {
                            line.moveTo(position.x, position.y)
                            dots += position
                        } else {
                            line.lineTo(position.x, position.y)
                        }
                        previous = reading
                    }

                    onDrawBehind {
                        for ((label, pos) in axis) {
                            drawLine(grid, Offset(firstX, pos), Offset(lastX, pos), 1.dp.toPx())
                            drawText(label, topLeft = Offset(firstX - label.size.width - 8.dp.toPx(), pos - label.size.height / 2f))
                        }
                        val labelY = bottom + 7.dp.toPx()
                        if (startDay == endDay) {
                            drawText(bottomLabel, topLeft = Offset((firstX + lastX - bottomLabel.size.width) / 2, labelY))
                        } else {
                            drawText(bottomLabel, topLeft = Offset(firstX, labelY))
                            val endLabelX = lastX - endLabel.size.width
                            val startLabelEnd = firstX + bottomLabel.size.width + 8.dp.toPx()
                            if (endLabelX > startLabelEnd) drawText(endLabel, topLeft = Offset(endLabelX, labelY))
                            if (middleLabel != null) {
                                val middleLabelX = (firstX + lastX - middleLabel.size.width) / 2
                                if (middleLabelX > startLabelEnd && middleLabelX + middleLabel.size.width + 8.dp.toPx() < endLabelX) {
                                    drawText(middleLabel, topLeft = Offset(middleLabelX, labelY))
                                }
                            }
                        }
                        drawPath(line, series, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        for (dot in dots) drawCircle(series, 2.dp.toPx(), dot)
                        val selectedTime = pickedAt.value
                        val selectedReading = if (selectedTime == null) null else readings.firstOrNull { it.at == selectedTime }
                        val indicator = selectedReading ?: readings.lastOrNull() ?: return@onDrawBehind
                        val mark = Offset(x(indicator.at), y(indicator.percent))
                        if (selectedReading != null) {
                            drawLine(series.copy(alpha = .48f), Offset(mark.x, top), Offset(mark.x, bottom), 1.dp.toPx())
                        }
                        drawCircle(series, 4.dp.toPx(), mark)
                    }
                },
        )
    }
}

@Composable
private fun DrainRow(shape: ListItemShapes, title: String, log: List<BatterySample>, mode: Mode) {
    val drain = remember(log, mode) { BatteryLog.drain(log, mode) }
    val rated = BatteryLife.format(BatteryLife.fullChargeMinutes(mode))
    val value = if (drain == null) "Rated $rated per charge"
        else "About ${BatteryLife.format(BatteryLife.fullChargeMinutes(mode, drain.rate))} per charge · rated $rated"
    InfoRow(shape, null, title, value)
}

@Composable
private fun HealthRow(shape: ListItemShapes, verdict: BatteryHealth.Verdict) {
    val value = when (verdict) {
        is BatteryHealth.Verdict.Declined -> "Shorter runtime than before"
        is BatteryHealth.Verdict.Steady -> "Runtime is steady"
        is BatteryHealth.Verdict.Tracking -> "Learning from your use"
    }
    InfoRow(shape, Glyph.BATTERY, "Battery health", value)
}
