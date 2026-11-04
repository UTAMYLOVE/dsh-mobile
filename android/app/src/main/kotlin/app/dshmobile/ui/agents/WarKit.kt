package app.dshmobile.ui.agents

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.fog.LocalEffects
import app.dshmobile.ui.fog.LocalPageActive
import app.dshmobile.ui.theme.Sakura

/** The pink war room's colours (FOGCITY only) and text styles (owner-approved A+B+C mockup, 2026-11-04). */
object War {
    val ink = Sakura.Text
    val ink2 = Sakura.TextSecondary
    val faint = Color(0xFFA98F90)
    val aqua = Sakura.Aqua
    val pink = Sakura.Primary
    val milk = Sakura.MilkTea
    val rose = Sakura.RoseBrown
    val white = Color.White
    val line = Sakura.Primary.copy(alpha = .65f)
    val glass = Brush.verticalGradient(listOf(Color.White.copy(alpha = .74f), Color.White.copy(alpha = .52f)))
    val shape = RoundedCornerShape(12.dp)

    val lab = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.sp, letterSpacing = .1.em, color = ink2)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = ink)
    val ttl = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .1.em, color = ink)
    val zh = TextStyle(fontWeight = FontWeight.Bold, color = ink)
    fun num(size: TextUnit, color: Color = ink) =
        TextStyle(fontFamily = FogFonts.Orbitron, fontWeight = FontWeight.Bold, fontSize = size, color = color, fontFeatureSettings = "tnum")
}

/** A white glass panel with a pink rim. */
@Composable
fun WarPanel(
    modifier: Modifier = Modifier,
    border: Color = War.line,
    background: Brush = War.glass,
    padding: PaddingValues = PaddingValues(10.dp),
    gap: Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().background(background, War.shape).border(1.dp, border, War.shape).padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** 「◆ TITLE」 left, a note right, a dashed line under both. */
@Composable
fun WarHeader(title: String, right: String? = null, rightColor: Color = War.ink2) {
    Row(
        Modifier.fillMaxWidth().drawBehind {
            drawLine(
                War.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
        }.padding(bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = War.ttl, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (right != null) Text(right, style = War.lab.copy(color = rightColor), maxLines = 1)
    }
}

/** An outlined label (status badge); [fill] paints it solid. */
@Composable
fun Pill(text: String, color: Color = War.ink, fill: Color? = null, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Text(
        text,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .08.em, color = War.ink),
        maxLines = 1,
        modifier = modifier.then(if (fill != null) Modifier.background(fill, shape) else Modifier).border(1.dp, fill ?: color, shape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** A small solid tag (log kind). */
@Composable
fun Chip(text: String, bg: Color, fg: Color = War.ink) {
    Text(
        text,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, color = fg),
        maxLines = 1,
        modifier = Modifier.background(bg, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/**
 * Milliseconds since the war room appeared, advancing every frame only while ambient motion is allowed and the page shows (SAVER
 * and reduced motion: frozen at 0). Uses the infinite-animation clock, so tests and the idle governor treat it as decoration.
 */
@Composable
fun rememberWarTime(): LongState {
    val animate = LocalEffects.current.ambient && LocalPageActive.current
    val time = remember { mutableLongStateOf(0L) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = withInfiniteAnimationFrameMillis { it } - time.longValue
            while (true) withInfiniteAnimationFrameMillis { time.longValue = it - start }
        }
    }
    return time
}

/** SVG path data (18×18 icons from the mockup) as Compose paths. */
object WarIcons {
    private fun p(d: String) = PathParser().parsePathString(d).toPath()
    val plan = p("M3 3h5v5H3zM10 3h5v5h-5zM3 10h5v5H3zM10 10h5v5h-5z")
    val scan = p("M8 3a5 5 0 1 0 0.01 0M12 12l4 4")
    val wait = p("M9 2l6 3v4c0 4-3 6-6 7c-3-1-6-3-6-7V5zM7 9h4")
    val write = p("M3 15l1-4l8-8l3 3l-8 8zM10 5l3 3")
    val check = p("M9 2l7 13H2zM9 7v4M9 13v.5")
    val page = p("M4 2h7l3 3v11H4zM7 7h5M7 10h5M7 13h3")
    val split = p("M6 9a3 3 0 1 0 .01 0M13 5a2 2 0 1 0 .01 0M13 13a2 2 0 1 0 .01 0M8.5 8l3-2M8.5 10l3 2")
    val shield = p("M9 2l6 3v4c0 4-3 6-6 7c-3-1-6-3-6-7V5z")
    val eye = p("M2 9c2-4 5-6 7-6s5 2 7 6c-2 4-5 6-7 6s-5-2-7-6zM9 7a2 2 0 1 0 .01 0")
    val done = p("M3 9l4 4l8-9")
}
