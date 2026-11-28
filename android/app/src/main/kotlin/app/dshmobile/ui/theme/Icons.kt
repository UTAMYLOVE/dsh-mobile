package app.dshmobile.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The few glyphs the app needs, drawn so they never depend on a font having them. */
enum class Glyph { ChevronDown, ChevronUp, ChevronLeft, ArrowRight, Gear, Mic, Plus, Send, Close, Check }

@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = Pal.Ink) {
    Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        val st = Stroke(2.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        when (glyph) {
            Glyph.ChevronDown -> drawPath(Path().apply { moveTo(6 * s, 9 * s); lineTo(12 * s, 15 * s); lineTo(18 * s, 9 * s) }, color, style = st)
            Glyph.ChevronUp -> drawPath(Path().apply { moveTo(6 * s, 15 * s); lineTo(12 * s, 9 * s); lineTo(18 * s, 15 * s) }, color, style = st)
            Glyph.ChevronLeft -> drawPath(Path().apply { moveTo(15 * s, 5 * s); lineTo(8 * s, 12 * s); lineTo(15 * s, 19 * s) }, color, style = st)
            Glyph.ArrowRight -> {
                drawLine(color, p(4f, 12f), p(19f, 12f), 2.2f * s, StrokeCap.Round)
                drawPath(Path().apply { moveTo(13 * s, 6 * s); lineTo(19.5f * s, 12 * s); lineTo(13 * s, 18 * s) }, color, style = st)
            }
            Glyph.Plus -> {
                drawLine(color, p(12f, 5f), p(12f, 19f), 2.4f * s, StrokeCap.Round)
                drawLine(color, p(5f, 12f), p(19f, 12f), 2.4f * s, StrokeCap.Round)
            }
            Glyph.Close -> {
                drawLine(color, p(6f, 6f), p(18f, 18f), 2.2f * s, StrokeCap.Round)
                drawLine(color, p(18f, 6f), p(6f, 18f), 2.2f * s, StrokeCap.Round)
            }
            Glyph.Check -> drawPath(Path().apply { moveTo(5 * s, 12.5f * s); lineTo(10 * s, 17.5f * s); lineTo(19 * s, 7 * s) }, color, style = st)
            Glyph.Send -> {
                drawPath(Path().apply { moveTo(4 * s, 4 * s); lineTo(21 * s, 12 * s); lineTo(4 * s, 20 * s); lineTo(7.5f * s, 12 * s); close() }, color, style = st)
            }
            Glyph.Mic -> {
                drawRoundRect(color, p(9f, 3f), Size(6f * s, 11f * s), CornerRadius(3f * s), style = st)
                drawPath(Path().apply { moveTo(5.5f * s, 11 * s); cubicTo(5.5f * s, 18 * s, 18.5f * s, 18 * s, 18.5f * s, 11 * s) }, color, style = st)
                drawLine(color, p(12f, 17f), p(12f, 21f), 2.2f * s, StrokeCap.Round)
            }
            Glyph.Gear -> {
                drawCircle(color, 3.2f * s, p(12f, 12f), style = st)
                drawCircle(color, 7f * s, p(12f, 12f), style = st)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Math.cos(a).toFloat()
                    val d = Math.sin(a).toFloat()
                    drawLine(color, p(12f + 7f * c, 12f + 7f * d), p(12f + 9.8f * c, 12f + 9.8f * d), 3.2f * s, StrokeCap.Round)
                }
            }
        }
    }
}
