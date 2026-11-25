@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import android.text.format.DateFormat
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import ua.olex.sonyanc.SonyProtocol.Mode
import java.util.Date
import java.util.TimeZone
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
                        val color = LocalContentColor.current
                        Canvas(Modifier.size(24.dp)) {
                            val tip = if (details) .4f else .6f
                            val sides = 1f - tip
                            val chevron = Path().apply {
                                moveTo(size.width * .28f, size.height * sides)
                                lineTo(size.width * .5f, size.height * tip)
                                lineTo(size.width * .72f, size.height * sides)
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
    Surface(shape = UiTokens.cardShape, color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (minutes != null) {
                Text(if (budsLive) "Listening left" else "Last reading", style = MaterialTheme.typography.labelLarge)
                Text(BatteryLife.short(minutes), style = MaterialTheme.typography.displaySmallEmphasized)
                Text(if (budsLive) source else "Estimated listening left · $source", style = MaterialTheme.typography.bodyMedium)
            }
            if (reserve != null) {
                if (minutes != null) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = cs.onPrimaryContainer.copy(alpha = .18f))
                val label = if (caseLive) "From the case" else "From the case · last reading"
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_case_mark), null, Modifier.size(20.dp))
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("+${BatteryLife.short(reserve)}", style = MaterialTheme.typography.titleMediumEmphasized)
                }
            }
        }
    }
}

private const val HOUR_MS = 60 * 60_000L
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
    val readings = remember(recent, part) {
        recent.mapNotNull { sample -> part.level(sample)?.let { Reading(sample.at, it, sample.session) } }
    }
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
            if (readings.isEmpty()) {
                Box(Modifier.fillMaxWidth().heightIn(min = if (recent.isEmpty()) 88.dp else 196.dp), contentAlignment = Alignment.Center) {
                    Text("No readings yet", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            } else {
                val end = recent.last().at
                val start = minOf(recent.first().at, end - 2 * HOUR_MS).coerceAtLeast(now - HISTORY_MS)
                key(part) { HistoryChart(part, readings, start, end.coerceAtLeast(start + 1)) }
            }
        }
    }
}

@Composable
private fun HistoryChart(part: Part, readings: List<Reading>, start: Long, end: Long) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val ink = cs.onSurfaceVariant
    val grid = cs.outlineVariant
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
    val shown = readings[index]
    val readableDate = "${dateFormat.format(Date(shown.at))} · ${timeFormat.format(Date(shown.at))}"
    val axisWidth = with(LocalDensity.current) {
        measurer.measure("100%", axisStyle).size.width.toDp() + 12.dp
    }
    fun pick(x: Float, width: Float, inset: Float, edge: Float) {
        val span = (width - inset - edge).coerceAtLeast(1f)
        val at = start + (((x - inset) / span).coerceIn(0f, 1f).toDouble() * (end - start)).toLong()
        pickedAt.value = readings.minByOrNull { abs(it.at - at) }?.at
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${shown.percent}%", style = MaterialTheme.typography.headlineSmallEmphasized, color = series)
        Text(readableDate, style = MaterialTheme.typography.bodySmall, color = ink)
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
                stateDescription = "${shown.percent}%, $readableDate"
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
                val area = Path()
                val dots = mutableListOf<Offset>()
                var previous: Reading? = null
                var segmentStart = 0f
                for (reading in readings) {
                    val position = Offset(x(reading.at), y(reading.percent))
                    val prior = previous
                    val gap = prior == null || reading.at - prior.at > 3 * HOUR_MS ||
                        (part != Part.CASE && prior.session != reading.session)
                    if (gap) {
                        if (prior != null) {
                            area.lineTo(x(prior.at), bottom)
                            area.lineTo(segmentStart, bottom)
                            area.close()
                        }
                        line.moveTo(position.x, position.y)
                        area.moveTo(position.x, position.y)
                        segmentStart = position.x
                        dots += position
                    } else {
                        line.lineTo(position.x, position.y)
                        area.lineTo(position.x, position.y)
                    }
                    previous = reading
                }
                area.lineTo(x(readings.last().at), bottom)
                area.lineTo(segmentStart, bottom)
                area.close()

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
                    drawPath(area, series.copy(alpha = .14f))
                    drawPath(line, series, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    for (dot in dots) drawCircle(series, 2.dp.toPx(), dot)
                    val selectedTime = pickedAt.value
                    val selectedReading = if (selectedTime == null) null else readings.firstOrNull { it.at == selectedTime }
                    val indicator = selectedReading ?: readings.last()
                    val mark = Offset(x(indicator.at), y(indicator.percent))
                    if (selectedReading != null) {
                        drawLine(series.copy(alpha = .48f), Offset(mark.x, top), Offset(mark.x, bottom), 1.dp.toPx())
                    }
                    drawCircle(series, 4.dp.toPx(), mark)
                }
            },
    )
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
        is BatteryHealth.Verdict.Declined ->
            "${verdict.percent}% less runtime · about ${BatteryLife.short(verdict.minutes)} per charge"
        is BatteryHealth.Verdict.Steady -> "Steady · about ${BatteryLife.short(verdict.minutes)} per charge"
        is BatteryHealth.Verdict.Tracking -> {
            val monday = (verdict.since * 7L - 3L) * 86_400_000L
            val date = DateFormat.format("MMM d", monday - TimeZone.getDefault().getOffset(monday)).toString()
            "Tracking since $date · about ${BatteryLife.short(verdict.minutes)} per charge"
        }
    }
    InfoRow(shape, Glyph.BATTERY, "Battery health", value)
}
