package ua.olex.sonyanc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Small native vectors: no icon font, bitmap decode, network, or renderer startup. */
enum class Glyph { QUIET, AMBIENT, OFF, TUNE, BACK, REFRESH, CHECK, BLUETOOTH, ADD, WIDGET, COPY }

@Composable
fun HushIcon(glyph: Glyph, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(24.dp)) {
        val k = size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) {
            val stroke = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, Offset(x,y), Offset(a,b), 1.9f, StrokeCap.Round)
            when (glyph) {
                Glyph.QUIET -> {
                    val p = Path().apply { moveTo(12f,2.5f); cubicTo(9f,5f,5f,5f,5f,5f); lineTo(5f,12f); cubicTo(5f,17f,12f,21f,12f,21f); cubicTo(12f,21f,19f,17f,19f,12f); lineTo(19f,5f); cubicTo(19f,5f,15f,5f,12f,2.5f); close() }
                    drawPath(p,color,style=stroke); line(9f,12f,11f,14f); line(11f,14f,15f,10f)
                }
                Glyph.AMBIENT -> {
                    line(12f,8f,12f,16f); line(8f,10f,8f,14f); line(16f,10f,16f,14f)
                    drawArc(color,-60f,120f,false,Offset(2f,3f),Size(20f,18f),style=stroke)
                    drawArc(color,120f,120f,false,Offset(2f,3f),Size(20f,18f),style=stroke)
                }
                Glyph.OFF -> { drawArc(color,-48f,276f,false,Offset(4f,4f),Size(16f,16f),style=stroke); line(12f,2f,12f,11f) }
                Glyph.TUNE -> { for ((y,x) in listOf(6f to 8f,12f to 16f,18f to 10f)) { line(3f,y,21f,y); drawCircle(color,2.5f,Offset(x,y)); drawCircle(Color(0xFF24252A),1f,Offset(x,y)) } }
                Glyph.BACK -> { line(15f,5f,8f,12f); line(8f,12f,15f,19f) }
                Glyph.REFRESH -> { drawArc(color,-65f,290f,false,Offset(4f,4f),Size(16f,16f),style=stroke); line(20f,3f,20f,9f); line(20f,9f,14f,9f) }
                Glyph.CHECK -> { line(5f,12f,10f,17f); line(10f,17f,20f,7f) }
                Glyph.BLUETOOTH -> { line(12f,2f,12f,22f); line(12f,2f,18f,8f); line(18f,8f,6f,18f); line(6f,6f,18f,16f); line(18f,16f,12f,22f) }
                Glyph.ADD -> { line(12f,5f,12f,19f); line(5f,12f,19f,12f) }
                Glyph.WIDGET -> { for (x in listOf(3f,14f)) for(y in listOf(3f,14f)) drawRoundRect(color,Offset(x,y),Size(7f,7f),androidx.compose.ui.geometry.CornerRadius(2f),style=stroke) }
                Glyph.COPY -> { drawRoundRect(color,Offset(8f,8f),Size(12f,13f),androidx.compose.ui.geometry.CornerRadius(2f),style=stroke); line(4f,16f,4f,4f); line(4f,4f,15f,4f) }
            }
        }
    }
}

@Composable
fun HushMark(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(36.dp)) {
        val s = size.minDimension
        drawArc(color, 35f, 290f, false, Offset(s*.12f,s*.12f),Size(s*.76f,s*.76f),style=Stroke(s*.105f,cap=StrokeCap.Round))
        drawLine(color, Offset(s*.43f,s*.38f),Offset(s*.43f,s*.62f),s*.09f,StrokeCap.Round)
        drawLine(color, Offset(s*.61f,s*.29f),Offset(s*.61f,s*.71f),s*.09f,StrokeCap.Round)
    }
}

/** A shaded illustration, drawn only when Compose needs a frame. Not a fake 3D model. */
@Composable
fun EarbudsArt(mode: SonyProtocol.Mode?, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.surface.luminance() < .2f
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val c = Offset(w*.5f,h*.5f)
        val ring = if(mode == SonyProtocol.Mode.AMBIENT) accent.copy(alpha=.24f) else accent.copy(alpha=.1f)
        drawOval(ring,Offset(w*.12f,h*.08f),Size(w*.76f,h*.86f),style=Stroke(1.5.dp.toPx()))
        drawOval(ring.copy(alpha=ring.alpha*.55f),Offset(w*.04f,h*.0f),Size(w*.92f,h),style=Stroke(1.dp.toPx()))
        for(i in 0..23) {
            val a=i*PI/12
            val start=Offset(c.x+cos(a).toFloat()*w*.38f,c.y+sin(a).toFloat()*h*.43f)
            val end=Offset(c.x+cos(a).toFloat()*w*.395f,c.y+sin(a).toFloat()*h*.45f)
            drawLine(accent.copy(alpha=.2f),start,end,1.dp.toPx(),StrokeCap.Round)
        }
        fun bud(cx: Float, cy: Float, angle: Float) {
            val unit = minOf(w*.26f,h*.62f)
            rotate(angle,Offset(cx,cy)) {
                drawOval(Color.Black.copy(alpha=if(dark).23f else .10f),Offset(cx-unit*.47f,cy+unit*.34f),Size(unit,unit*.22f))
                // Foam tip and nozzle, under the glossy outer body.
                drawOval(Color(0xFF101215),Offset(cx-unit*.48f,cy-unit*.04f),Size(unit*.46f,unit*.65f))
                drawOval(Color(0xFF35373A),Offset(cx-unit*.52f,cy+unit*.01f),Size(unit*.25f,unit*.53f))
                val shell=Path().apply {
                    moveTo(cx-unit*.39f,cy-unit*.2f)
                    cubicTo(cx-unit*.37f,cy-unit*.65f,cx+unit*.31f,cy-unit*.64f,cx+unit*.49f,cy-unit*.17f)
                    cubicTo(cx+unit*.68f,cy+unit*.27f,cx+unit*.35f,cy+unit*.55f,cx-unit*.05f,cy+unit*.44f)
                    cubicTo(cx-unit*.31f,cy+unit*.39f,cx-unit*.48f,cy+unit*.15f,cx-unit*.39f,cy-unit*.2f); close()
                }
                drawPath(shell,Brush.linearGradient(listOf(Color(0xFF686A6D),Color(0xFF292B2E),Color(0xFF111315)),Offset(cx-unit*.4f,cy-unit*.6f),Offset(cx+unit*.4f,cy+unit*.5f)))
                drawPath(shell,Color.White.copy(alpha=.18f),style=Stroke(.7.dp.toPx()))
                drawOval(Brush.radialGradient(listOf(Color(0xFF4B4D50),Color(0xFF26282B),Color(0xFF191B1E)),Offset(cx+unit*.13f,cy-unit*.17f),unit*.6f),Offset(cx-unit*.21f,cy-unit*.40f),Size(unit*.64f,unit*.7f))
                drawArc(Color.White.copy(alpha=.18f),210f,105f,false,Offset(cx-unit*.2f,cy-unit*.39f),Size(unit*.62f,unit*.68f),style=Stroke(1.dp.toPx(),cap=StrokeCap.Round))
                drawCircle(Color(0xFFBBA582),unit*.039f,Offset(cx+unit*.38f,cy+unit*.13f))
                drawCircle(Color(0xFF0D0F12),unit*.021f,Offset(cx+unit*.38f,cy+unit*.13f))
            }
        }
        bud(w*.33f,h*.51f,-24f)
        bud(w*.68f,h*.46f,27f)
    }
}
