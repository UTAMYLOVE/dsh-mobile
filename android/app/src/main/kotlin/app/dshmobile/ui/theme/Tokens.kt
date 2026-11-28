package app.dshmobile.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R

/** Colour tokens of the 小卡手帳 style (SPEC I). */
object Pal {
    val Paper = Color(0xFFFFF8EC)
    val Ink = Color(0xFF3A2E4D)
    val Lavender = Color(0xFFD9CCFF)
    val LavenderBack = Color(0xFFE4DAFF)
    val Pink = Color(0xFFFFD0DD)
    val PinkStrong = Color(0xFFFF8FB1)
    val Mint = Color(0xFFC9F0DC)
    val Butter = Color(0xFFFFE08A)
    val White = Color(0xFFFFFFFF)
    val Plum = Color(0xFF4A3560)
    val Skin = Color(0xFFFBE3D4)
    val Mouth = Color(0xFFC4556A)
    val Warn = Color(0xFFB4324F)
}

/** The bundled rounded handwriting-like face (LXGW WenKai TC, SIL OFL 1.1); the system font fills any missing glyph. */
val Kai = FontFamily(
    Font(R.font.lxgw_wenkai_tc_regular, FontWeight.Normal),
    Font(R.font.lxgw_wenkai_tc_bold, FontWeight.Bold),
)

/** The type scale. */
object Type {
    val Title = 25.sp
    val Heading = 19.sp
    val Body = 16.sp
    val Small = 14.sp
    val Tiny = 12.sp
}

/** All text of the app goes through this (no Material theme needed). */
@Composable
fun KText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = Type.Body,
    bold: Boolean = false,
    color: Color = Pal.Ink,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    alpha: Float = 1f,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = Kai,
            fontSize = size,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = color.copy(alpha = color.alpha * alpha),
            lineHeight = size * 1.45f,
            textAlign = align ?: TextAlign.Unspecified,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

val Corner14 = RoundedCornerShape(14.dp)
val Corner18 = RoundedCornerShape(18.dp)
val Corner22 = RoundedCornerShape(22.dp)
val PillShape = RoundedCornerShape(percent = 50)

/**
 * The sticker surface: a fill, a 2 dp ink outline and a hard offset shadow (no blur). The shadow is drawn into reserved
 * padding, so a clipping parent (a list) does not cut it.
 */
fun Modifier.sticker(
    fill: Color = Pal.White,
    shape: Shape = Corner18,
    shadow: Dp = 3.dp,
    outline: Dp = 2.dp,
): Modifier = this
    .padding(end = shadow, bottom = shadow)
    .drawBehind {
        if (shadow > 0.dp) {
            translate(shadow.toPx(), shadow.toPx()) {
                drawPath(Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }, Pal.Ink)
            }
        }
    }
    .background(fill, shape)
    .border(outline, Pal.Ink, shape)

/** A tap target without the grey ripple (the sticker look carries the feedback). */
@Composable
fun Modifier.tap(onClick: () -> Unit, role: Role = Role.Button): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = source, indication = null, role = role, onClick = onClick)
}

/** A pill button: [fill] with the ink outline and a small shadow. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Pal.PinkStrong,
    size: TextUnit = Type.Body,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .sticker(if (enabled) fill else Pal.Paper, PillShape, shadow = if (enabled) 3.dp else 0.dp)
            .tap({ if (enabled) onClick() })
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) { KText(text, size = size, bold = true, alpha = if (enabled) 1f else 0.4f) }
}

/** A small quick-reply style chip. */
@Composable
fun ChipPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, fill: Color = Pal.Butter) {
    Box(
        modifier = modifier
            .sticker(fill, PillShape, shadow = 2.dp)
            .tap(onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) { KText(text, size = 14.5.sp) }
}

/** A numbered dot (score 1..5). */
@Composable
fun ScoreDot(n: Int, picked: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val base = modifier.sticker(if (picked) Pal.PinkStrong else Pal.Paper, CircleShape, shadow = if (picked) 2.dp else 0.dp)
    Box(
        modifier = (if (onClick != null) base.tap(onClick) else base).padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) { KText(n.toString(), size = 16.sp, bold = picked) }
}

/** Faint dots on the cream paper. */
fun Modifier.paper(): Modifier = this.background(Pal.Paper).drawBehind {
    val step = 18.dp.toPx()
    val r = 1.2.dp.toPx()
    val dot = Pal.Ink.copy(alpha = 0.08f)
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) {
            drawCircle(dot, r, Offset(x, y))
            x += step
        }
        y += step
    }
}
