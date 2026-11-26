@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
import java.util.Calendar
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
                item { DrainRow(it, "Noise cancelling & ambient", log, Mode.ANC, device?.codec?.code) }
                item { DrainRow(it, "Noise control off", log, Mode.OFF, device?.codec?.code) }
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
    val codec = device.codec?.code
    val minutes = perEar?.minutesLeft(battery, mode) ?: BatteryLife.budsMinutes(battery, mode, measured?.rate, codec)
    val reserve = BatteryLife.caseMinutes(battery, mode, measured?.rate, codec)?.takeIf { it >= 10 }
    if (minutes == null && reserve == null) return
    val budsLive = 0 in device.liveBatteryParts || 1 in device.liveBatteryParts
    val caseLive = 2 in device.liveBatteryParts
    val cs = MaterialTheme.colorScheme
    val source = when {
        measured != null || perEar != null -> "Based on your use"
        codec == CodecProtocol.LDAC -> "Sony rating · LDAC"
        else -> "Sony rating"
    }
    Surface(shape = UiTokens.cardShape, color = Surfaces.estimate, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (minutes != null) {
                Text(if (budsLive) "Estimated listening left" else "Last reading", style = MaterialTheme.typography.titleMedium)
                Text(
                    BatteryLife.short(minutes), style = MaterialTheme.typography.displayMediumEmphasized.withTabularFigures(),
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
                    Icon(painterResource(R.drawable.ic_case_mark), null, Modifier.size(18.dp), tint = cs.secondary)
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Text("+${BatteryLife.short(reserve)}", color = cs.secondary, style = MaterialTheme.typography.labelLarge.withTabularFigures())
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
                val days = remember(recent) { batteryDays(recent) }
                var pickedDay by rememberSaveable { mutableStateOf<Long?>(null) }
                val readings = remember(recent, part) {
                    recent.asReversed().mapNotNull { sample ->
                        part.level(sample)?.let { Reading(sample.at, it, sample.session) }
                    }.distinctBy { it.at }.asReversed()
                }
                val daily = remember(days, readings) {
                    days.associateWith { date -> readings.filter { it.at in date.start until date.end } }
                }
                val day = days.firstOrNull { it.start == pickedDay }
                    ?: days.lastOrNull { daily[it].orEmpty().isNotEmpty() } ?: days.last()
                if (days.size > 1) {
                    HistoryDays(days, daily, part, day) { pickedDay = it.start }
                }
                val samples = daily[day].orEmpty()
                val end = day.last ?: day.end - 1
                val start = minOf(day.first ?: day.start, end - 2 * HOUR_MS).coerceAtLeast(maxOf(day.start, now - HISTORY_MS))
                key(part, day.start) { HistoryChart(part, samples, start, end.coerceAtLeast(start + 1)) }
            }
        }
    }
}

private data class BatteryDay(val start: Long, val end: Long, val first: Long?, val last: Long?)

private fun batteryDays(log: List<BatterySample>): List<BatteryDay> {
    val cal = Calendar.getInstance().apply {
        timeInMillis = log.first().at
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return buildList {
        while (cal.timeInMillis <= log.last().at) {
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val end = cal.timeInMillis
            val day = log.filter { it.at in start until end }
            add(BatteryDay(start, end, day.firstOrNull()?.at, day.lastOrNull()?.at))
        }
    }
}

private fun historySegments(part: Part, readings: List<Reading>): List<List<Reading>> {
    val segments = mutableListOf<MutableList<Reading>>()
    var previous: Reading? = null
    for (reading in readings) {
        val prior = previous
        if (prior == null || reading.at - prior.at > CHART_GAP_MS ||
            (part != Part.CASE && prior.session != reading.session)
        ) segments.add(mutableListOf())
        segments.last().add(reading)
        previous = reading
    }
    return segments
}

@Composable
private fun HistoryDays(
    days: List<BatteryDay>, daily: Map<BatteryDay, List<Reading>>, part: Part, selected: BatteryDay,
    onSelect: (BatteryDay) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (day in days) {
            val active = day == selected
            val readings = daily[day].orEmpty()
            val segments = remember(part, readings) { historySegments(part, readings) }
            val label = DateFormat.format("EEE", day.start).toString()
            val title = DateFormat.format("EEEE d MMMM", day.start).toString()
            val ink = if (active) cs.primary else cs.onSurfaceVariant
            val surface = if (active) cs.primary.copy(alpha = .09f) else cs.surfaceContainerHigh
            Surface(
                modifier = Modifier.weight(1f).selectable(active, role = Role.Tab) { onSelect(day) }
                    .semantics { contentDescription = if (readings.isEmpty()) "$title, no readings" else title },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = surface,
            ) {
                Column(Modifier.padding(horizontal = 3.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                        val base = size.height - 1.dp.toPx()
                        for (segment in segments) {
                            val points = segment.map {
                                Offset(
                                    ((it.at - day.start).toFloat() / (day.end - day.start) * size.width).coerceIn(0f, size.width),
                                    base - base * it.percent / 100f,
                                )
                            }
                            if (points.size == 1) drawCircle(ink, 1.5.dp.toPx(), points.first())
                            else {
                                val fill = Path().apply {
                                    moveTo(points.first().x, base)
                                    points.forEach { lineTo(it.x, it.y) }
                                    lineTo(points.last().x, base)
                                    close()
                                }
                                drawPath(fill, ink)
                            }
                        }
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1, overflow = TextOverflow.Clip)
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
    val series = cs.primary
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = ink)
    val timeFormat = remember(ctx) { DateFormat.getTimeFormat(ctx) }
    val dateFormat = remember(ctx) { DateFormat.getMediumDateFormat(ctx) }
    val showHours = end - start <= 25 * HOUR_MS &&
        DateFormat.format("yyyy-MM-dd", start) == DateFormat.format("yyyy-MM-dd", end)
    fun tick(at: Long) = if (showHours) timeFormat.format(Date(at)) else DateFormat.format("EEE d", at).toString()
    val startLabel = tick(start)
    val endLabel = tick(end)
    val middleTick = if (showHours || end - start >= 4 * 24 * HOUR_MS) tick(start + (end - start) / 2) else null
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
            Text(shown?.let { "${it.percent}%" } ?: "-", style = MaterialTheme.typography.headlineSmallEmphasized.withTabularFigures(), color = if (shown != null) series else ink)
            Text(readableDate, style = MaterialTheme.typography.bodySmall, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(
            Modifier.fillMaxWidth().height(152.dp)
                .pointerInput(readings, start, end, axisWidth) {
                    detectTapGestures { pick(it.x, size.width.toFloat(), 4.dp.toPx(), axisWidth.toPx()) }
                }
                .pointerInput(readings, start, end, axisWidth) {
                    detectHorizontalDragGestures(
                        onDragStart = { pick(it.x, size.width.toFloat(), 4.dp.toPx(), axisWidth.toPx()) },
                    ) { change, _ -> pick(change.position.x, size.width.toFloat(), 4.dp.toPx(), axisWidth.toPx()) }
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
                    val firstX = 4.dp.toPx()
                    val lastX = (size.width - axisWidth.toPx()).coerceAtLeast(firstX + 1f)
                    val bottomLabel = measurer.measure(startLabel, axisStyle)
                    val lastLabel = measurer.measure(endLabel, axisStyle)
                    val middleLabel = middleTick?.let { measurer.measure(it, axisStyle) }
                    val top = bottomLabel.size.height / 2f + 2.dp.toPx()
                    val bottom = size.height - bottomLabel.size.height - 10.dp.toPx()
                    fun x(at: Long) = firstX + (at - start).toFloat() / (end - start) * (lastX - firstX)
                    fun y(value: Int) = bottom - (bottom - top) * value / 100f
                    val axis = (100 downTo 0 step 25).map { level ->
                        val label = if (level % 50 == 0) measurer.measure("$level%", axisStyle) else null
                        label to y(level)
                    }
                    val segments = historySegments(part, readings).map { segment ->
                        segment.map { Offset(x(it.at), y(it.percent)) }
                    }
                    val areas = segments.map { points ->
                        if (points.size == 1) null else Path().apply {
                            moveTo(points.first().x, bottom)
                            points.forEach { lineTo(it.x, it.y) }
                            lineTo(points.last().x, bottom)
                            close()
                        }
                    }
                    val areaPaint = Paint().apply { pathEffect = PathEffect.cornerPathEffect(2.dp.toPx()) }
                    val inactive = androidx.compose.ui.graphics.lerp(cs.surfaceContainerHighest, series, .42f)

                    onDrawBehind {
                        for ((label, pos) in axis) {
                            drawLine(
                                if (label == null) grid.copy(alpha = .3f) else grid,
                                Offset(firstX, pos), Offset(lastX, pos), 1.dp.toPx(),
                            )
                            if (label != null) {
                                drawText(label, topLeft = Offset(lastX + 6.dp.toPx(), pos - label.size.height / 2f))
                            }
                        }
                        val labelY = bottom + 7.dp.toPx()
                        if (startLabel == endLabel) {
                            drawText(bottomLabel, topLeft = Offset((firstX + lastX - bottomLabel.size.width) / 2, labelY))
                        } else {
                            drawText(bottomLabel, topLeft = Offset(firstX, labelY))
                            val endLabelX = lastX - lastLabel.size.width
                            val startLabelEnd = firstX + bottomLabel.size.width + 8.dp.toPx()
                            if (endLabelX > startLabelEnd) drawText(lastLabel, topLeft = Offset(endLabelX, labelY))
                            if (middleLabel != null) {
                                val middleLabelX = (firstX + lastX - middleLabel.size.width) / 2
                                if (middleLabelX > startLabelEnd && middleLabelX + middleLabel.size.width + 8.dp.toPx() < endLabelX) {
                                    drawText(middleLabel, topLeft = Offset(middleLabelX, labelY))
                                }
                            }
                        }
                        val selectedTime = pickedAt.value
                        val selectedReading = if (selectedTime == null) null else readings.firstOrNull { it.at == selectedTime }
                        val indicator = selectedReading ?: readings.lastOrNull() ?: return@onDrawBehind
                        val mark = Offset(x(indicator.at), y(indicator.percent))
                        segments.forEachIndexed { i, points ->
                            val fill = if (mark.x in points.first().x..points.last().x) series else inactive
                            val area = areas[i]
                            when {
                                area == null -> drawCircle(fill, 2.dp.toPx(), points.single())
                                points.all { it.y == bottom } -> drawLine(fill, points.first(), points.last(), 2.dp.toPx())
                                else -> drawIntoCanvas {
                                    areaPaint.color = fill
                                    it.drawPath(area, areaPaint)
                                }
                            }
                        }
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
private fun DrainRow(shape: ListItemShapes, title: String, log: List<BatterySample>, mode: Mode, codec: Int?) {
    val drain = remember(log, mode) { BatteryLog.drain(log, mode) }
    val rated = BatteryLife.format(BatteryLife.fullChargeMinutes(mode, codec = codec))
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
