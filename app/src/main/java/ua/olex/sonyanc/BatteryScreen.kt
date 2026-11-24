@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import ua.olex.sonyanc.SonyProtocol.Mode
import java.util.Calendar
import java.util.Date
import kotlin.math.abs

/**
 * Chart colors from the wallpaper, as Android's own Battery page draws its usage: the earbuds in
 * primary, the case in tertiary. The case line is also dashed, so identity never rests on color.
 */
private object ChartColors {
    val buds @Composable get() = MaterialTheme.colorScheme.primary
    val right @Composable get() = MaterialTheme.colorScheme.secondary
    val case @Composable get() = MaterialTheme.colorScheme.tertiary
}

/** The Battery page: a week of real readings and what a full charge lasts for the owner. */
@Composable
fun BatteryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val device by Headphones.state.collectAsState()
    val log = remember(device.batterySeenAt) { Prefs.batteryLog(ctx) }
    val health = remember(device.batterySeenAt) { Prefs.batteryHealth(ctx) }
    BatteryContent(log, System.currentTimeMillis(), onBack, health, device)
}

@Composable
fun BatteryContent(log: List<BatterySample>, now: Long, onBack: () -> Unit = {}, health: List<WeekHealth> = emptyList(), device: DeviceState? = null) {
    val cs = MaterialTheme.colorScheme
    PageScaffold("Battery", onBack) {
        // One line first, as Pixel's own battery page says it: until when the earbuds last.
        val heroBattery = device?.battery
        val heroMode = device?.nc?.mode
        val heroMinutes = heroBattery?.let { BatteryLife.budsMinutes(it, heroMode, BatteryLog.drain(log, heroMode)?.rate) }
        if (heroMinutes != null) {
            val ctx = LocalContext.current
            val until = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(now + heroMinutes * 60_000L))
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Until about $until", style = MaterialTheme.typography.headlineMediumEmphasized)
                Text(BatteryLife.short(heroMinutes) + " of listening" +
                    (BatteryLife.caseMinutes(heroBattery, heroMode, BatteryLog.drain(log, heroMode)?.rate)?.takeIf { it >= 10 }?.let { " · the case adds " + BatteryLife.short(it) } ?: ""),
                    style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            }
        }
        val week = log.filter { now - it.at in 0..7 * 24 * 60 * 60_000L }
        val separate = week.any { it.left != null || it.right != null }
        Surface(shape = UiTokens.cardShape, color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Last 7 days", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (!separate) {
                        Legend(ChartColors.buds, "Earbuds", dashed = false)
                        Spacer(Modifier.width(12.dp))
                        Legend(ChartColors.case, "Case", dashed = true)
                    }
                }
                if (separate) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend(ChartColors.buds, "Left", dashed = false)
                    Legend(ChartColors.right, "Right", dashed = true)
                    Legend(ChartColors.case, "Case", dashed = true)
                }
                if (week.size < 2) {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Text("Readings appear here as you use the earbuds.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                } else WeekChart(week, now, separate)
            }
        }
        // The week in the ears: a quiet summary, no reminder.
        val listened = remember(log, now) { BatteryLog.week(log, now) }
        if (listened.minutes >= 30) ListGroup("This week") {
            item {
                InfoRow(it, Glyph.INSIGHTS, "${BatteryLife.short(listened.minutes)} in your ears",
                    if (listened.anc >= 1) "${BatteryLife.short(listened.anc)} of it with noise cancelling" else "None of it with noise cancelling")
            }
        }
        val battery = device?.battery
        if (device != null && battery != null) {
            val mode = device.nc?.mode
            val measured = BatteryLog.drain(log, mode)
            val minutes = BatteryLife.budsMinutes(battery, mode, measured?.rate)
            if (minutes != null) ListGroup("Listening time left") {
                item {
                    val source = if (measured == null) "Sony rated runtime"
                        else "Based on ${BatteryLife.short(measured.minutes.toInt())} of recorded drain"
                    val stale = if (device.liveBatteryParts.isEmpty()) " · Last reading" else ""
                    InfoRow(it, Glyph.BATTERY, BatteryLife.short(minutes), source + stale)
                }
            }
        }
        HealthCard(BatteryHealth.verdict(health))
        ListGroup("A full charge lasts") {
            item { DrainRow(it, Glyph.QUIET, "Noise cancelling & ambient", log, Mode.ANC) }
            item { DrainRow(it, Glyph.OFF, "Noise control off", log, Mode.OFF) }
        }
        Text(
            "Measured from your own use while the earbuds are out of the case, so LDAC, volume and battery wear count. " +
                "Until there is enough, Sony's rating is shown.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun Legend(color: Color, label: String, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), StrokeCap.Round,
                if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DrainRow(shape: ListItemShapes, glyph: Glyph, title: String, log: List<BatterySample>, mode: Mode) {
    val drain = remember(log) { BatteryLog.drain(log, mode) }
    val rated = BatteryLife.fullChargeMinutes(mode, null)
    val value = if (drain == null) "Rated ${BatteryLife.format(rated)} · learning from your use"
    else {
        val measured = BatteryLife.fullChargeMinutes(mode, drain.rate)
        "About ${BatteryLife.format(measured)} · rated ${BatteryLife.format(rated)} · from ${BatteryLife.format(drain.minutes.toInt())} of use"
    }
    InfoRow(shape, glyph, title, value)
}

/** One week: earbuds (solid) and case (dashed) over a 0 to 100 % axis; drag or tap to read a moment. */
@Composable
private fun WeekChart(samples: List<BatterySample>, now: Long, separate: Boolean) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val buds = ChartColors.buds
    val rightColor = ChartColors.right
    val caseColor = ChartColors.case
    val grid = cs.outlineVariant.copy(alpha = .5f)
    val card = Surfaces.card
    val ink = cs.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = ink)
    val start = now - 7 * 24 * 60 * 60_000L
    var picked by remember { mutableStateOf<BatterySample?>(null) }
    val timeFormat = remember { DateFormat.getTimeFormat(ctx) }
    fun nearest(x: Float, width: Float, left: Float): BatterySample? {
        val t = start + ((x - left) / (width - left)).coerceIn(0f, 1f) * (now - start)
        return samples.minByOrNull { abs(it.at - t) }
    }
    fun levels(sample: BatterySample): String {
        val budsLevel = if (sample.left == null && sample.right == null) listOf("Earbuds ${sample.level} %")
            else listOfNotNull(sample.left?.let { "Left $it %" }, sample.right?.let { "Right $it %" })
        return (budsLevel + listOfNotNull(sample.case?.let { "Case $it %" })).joinToString(" · ")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The reading under the finger; the latest one otherwise.
        val shown = picked ?: samples.last()
        Text(
            "${dayName(shown.at)} ${timeFormat.format(Date(shown.at))} · ${levels(shown)}",
            style = MaterialTheme.typography.labelLarge, color = cs.onSurface,
        )
        Canvas(
            Modifier.fillMaxWidth().height(180.dp)
                .pointerInput(samples) {
                    detectDragGestures(onDragEnd = { picked = null }, onDragCancel = { picked = null }) { change, _ ->
                        picked = nearest(change.position.x, size.width.toFloat(), 32.dp.toPx())
                    }
                }
                .pointerInput(samples) { detectTapGestures { picked = nearest(it.x, size.width.toFloat(), 32.dp.toPx()) } }
                .semantics {
                    contentDescription = "Battery over the last 7 days. Latest: ${levels(samples.last())}"
                },
        ) {
            val left = 32.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val top = 6.dp.toPx()
            fun x(t: Long) = left + (t - start).toFloat() / (now - start) * (size.width - left)
            fun y(v: Int) = bottom - (bottom - top) * v / 100f
            // Recessive grid with three labels, and day ticks.
            for (v in listOf(0, 50, 100)) {
                drawLine(grid, Offset(left, y(v)), Offset(size.width, y(v)), 1.dp.toPx())
                val label = measurer.measure(if (v == 0) "0" else "$v%", axisStyle)
                drawText(label, topLeft = Offset(0f, y(v) - label.size.height / 2f))
            }
            val day = Calendar.getInstance().apply { timeInMillis = start; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_YEAR, 1) }
            while (day.timeInMillis < now) {
                val dx = x(day.timeInMillis)
                drawLine(grid, Offset(dx, top), Offset(dx, bottom), 1.dp.toPx())
                val label = measurer.measure(dayName(day.timeInMillis), axisStyle)
                drawText(label, topLeft = Offset(dx + 3.dp.toPx(), bottom + 3.dp.toPx()))
                day.add(Calendar.DAY_OF_YEAR, 1)
            }
            // The earbuds' line breaks between sessions (they recharge in the case) and across long
            // gaps; the case is one continuous line, since it keeps its charge between sessions.
            fun series(value: (BatterySample) -> Int?, color: Color, dashed: Boolean, perSession: Boolean) {
                val path = Path()
                var prev: BatterySample? = null
                for (s in samples) {
                    val v = value(s)
                    if (v == null) continue
                    val p = prev
                    val gap = if (perSession) 3 * 60 * 60_000L else 36 * 60 * 60_000L
                    if (p == null || (perSession && p.session != s.session) || s.at - p.at > gap) path.moveTo(x(s.at), y(v))
                    else path.lineTo(x(s.at), y(v))
                    prev = s
                }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null))
            }
            series({ it.case }, caseColor, dashed = true, perSession = false)
            if (separate) {
                series({ it.left }, buds, dashed = false, perSession = true)
                series({ it.right }, rightColor, dashed = true, perSession = true)
            } else {
                series({ it.level }, buds, dashed = false, perSession = true)
            }
            // Crosshair on the picked reading, or a dot on the latest one.
            val mark = picked ?: samples.last()
            if (picked != null) drawLine(ink.copy(alpha = .6f), Offset(x(mark.at), top), Offset(x(mark.at), bottom), 1.dp.toPx())
            mark.case?.let { drawCircle(caseColor, 4.dp.toPx(), Offset(x(mark.at), y(it))) }
            fun markEarbud(value: Int, color: Color) {
                drawCircle(card, 6.dp.toPx(), Offset(x(mark.at), y(value)))
                drawCircle(color, 4.dp.toPx(), Offset(x(mark.at), y(value)))
            }
            if (separate) {
                mark.left?.let { markEarbud(it, buds) }
                mark.right?.let { markEarbud(it, rightColor) }
            } else {
                markEarbud(mark.level, buds)
            }
        }
    }
}

private fun dayName(at: Long): String = android.text.format.DateFormat.format("EEE", at).toString()

/** A duration that never breaks across lines in running text ("7 h 10 min"). */
private fun keep(minutes: Int) = BatteryLife.format(minutes).replace(' ', '\u00A0')

/** First day (Monday) of a [BatteryHealth.weekOf] week, as "Mar 3". */
private fun weekStart(week: Long): String =
    android.text.format.DateFormat.format("MMM d", (week * 7 - 3) * 86_400_000L).toString()

/**
 * Months-long battery health, said quietly: tracking, steady, or a highlighted card when a full
 * charge lasts at least 10 % less than in the first weeks tracked.
 */
@Composable
private fun HealthCard(verdict: BatteryHealth.Verdict?) {
    verdict ?: return
    val cs = MaterialTheme.colorScheme
    when (verdict) {
        is BatteryHealth.Verdict.Declined -> Surface(shape = UiTokens.cardShape, color = cs.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HushIcon(Glyph.BATTERY, Modifier.size(22.dp), cs.onSecondaryContainer)
                    Text("Battery health", style = MaterialTheme.typography.titleMediumEmphasized, color = cs.onSecondaryContainer)
                }
                Text(
                    "A full charge now lasts about ${keep(verdict.minutes)}, ${verdict.percent}\u00A0% less than " +
                        "${keep(verdict.baseline)} in the week of ${weekStart(verdict.since)}. Normal wear for earbud batteries.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer,
                )
            }
        }
        is BatteryHealth.Verdict.Steady -> ListGroup {
            item { InfoRow(it, Glyph.BATTERY, "Battery health", "Steady · about ${BatteryLife.format(verdict.minutes)} per charge since ${weekStart(verdict.since)}") }
        }
        is BatteryHealth.Verdict.Tracking -> ListGroup {
            item { InfoRow(it, Glyph.BATTERY, "Battery health", "Tracking since ${weekStart(verdict.since)} · about ${BatteryLife.format(verdict.minutes)} per charge now") }
        }
    }
}
