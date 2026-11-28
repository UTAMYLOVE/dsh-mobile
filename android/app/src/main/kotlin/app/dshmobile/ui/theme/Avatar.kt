package app.dshmobile.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 小葵, drawn (never a photograph): a pink round background, long dark-plum hair falling past the shoulders, a soft face with
 * a fringe, blush, a calm closed-eye smile. Drawn on a 100 x 100 canvas and scaled, so it is a vector at any size.
 */
@Composable
fun XiaokuiAvatar(size: Dp = 72.dp, modifier: Modifier = Modifier, outlined: Boolean = true) {
    val shape = CircleShape
    val base = modifier.size(size)
    Canvas(modifier = if (outlined) base.sticker(Pal.Pink, shape, shadow = 3.dp).clip(shape) else base.clip(shape)) {
        drawXiaokui()
    }
}

/** The drawing itself, usable on any canvas (the launcher icon is a separate vector drawable). */
fun DrawScope.drawXiaokui() {
    val k = minOf(this.size.width, this.size.height) / 100f
    scale(k, k, pivot = Offset.Zero) {
        drawRect(Pal.Pink, size = Size(100f, 100f))
        // back hair: long, past the shoulders
        val back = Path().apply {
            moveTo(18f, 100f)
            cubicTo(12f, 80f, 14f, 50f, 20f, 36f)
            cubicTo(26f, 16f, 40f, 9f, 50f, 9f)
            cubicTo(60f, 9f, 74f, 16f, 80f, 36f)
            cubicTo(86f, 50f, 88f, 80f, 82f, 100f)
            close()
        }
        drawPath(back, Pal.Plum)
        // shoulders and collar
        val shoulders = Path().apply {
            moveTo(14f, 100f)
            cubicTo(16f, 84f, 32f, 78f, 50f, 78f)
            cubicTo(68f, 78f, 84f, 84f, 86f, 100f)
            close()
        }
        drawPath(shoulders, Color(0xFFFFFFFF))
        drawPath(shoulders, Pal.Ink.copy(alpha = 0.0f))
        val collar = Path().apply {
            moveTo(40f, 79f); lineTo(50f, 92f); lineTo(60f, 79f)
        }
        drawPath(collar, Pal.Ink.copy(alpha = 0.35f), style = Stroke(1.4f, cap = StrokeCap.Round))
        // neck
        drawRect(Pal.Skin, Offset(44.5f, 66f), Size(11f, 14f))
        // hair strands in front of the shoulders
        val left = Path().apply {
            moveTo(20f, 100f); cubicTo(17f, 84f, 19f, 62f, 24f, 50f); lineTo(33f, 56f)
            cubicTo(31f, 72f, 33f, 88f, 36f, 100f); close()
        }
        val right = Path().apply {
            moveTo(80f, 100f); cubicTo(83f, 84f, 81f, 62f, 76f, 50f); lineTo(67f, 56f)
            cubicTo(69f, 72f, 67f, 88f, 64f, 100f); close()
        }
        drawPath(left, Pal.Plum)
        drawPath(right, Pal.Plum)
        // face
        drawOval(Pal.Skin, Offset(31f, 24f), Size(38f, 46f))
        // ears hidden by hair; fringe with a soft side parting
        val fringe = Path().apply {
            moveTo(28f, 46f)
            cubicTo(26f, 26f, 38f, 17f, 51f, 17f)
            cubicTo(64f, 17f, 74f, 26f, 72f, 46f)
            cubicTo(66f, 40f, 61f, 33f, 59f, 27f)
            cubicTo(54f, 36f, 42f, 42f, 28f, 46f)
            close()
        }
        drawPath(fringe, Pal.Plum)
        // hair shine
        drawPath(
            Path().apply { moveTo(36f, 24f); cubicTo(42f, 19f, 50f, 18f, 57f, 20f) },
            Color.White.copy(alpha = 0.25f),
            style = Stroke(1.6f, cap = StrokeCap.Round),
        )
        // blush
        drawCircle(Pal.PinkStrong.copy(alpha = 0.45f), 4.6f, Offset(38.5f, 55f))
        drawCircle(Pal.PinkStrong.copy(alpha = 0.45f), 4.6f, Offset(61.5f, 55f))
        // calm closed eyes: two soft downward arcs
        val eye = Stroke(1.9f, cap = StrokeCap.Round)
        drawPath(Path().apply { moveTo(39f, 50f); quadraticTo(43.5f, 54f, 48f, 50f) }, Pal.Ink, style = eye)
        drawPath(Path().apply { moveTo(52f, 50f); quadraticTo(56.5f, 54f, 61f, 50f) }, Pal.Ink, style = eye)
        // small nose and smile
        drawPath(Path().apply { moveTo(50f, 55f); quadraticTo(51.4f, 57f, 50f, 58f) }, Pal.Ink.copy(alpha = 0.3f), style = Stroke(1.2f, cap = StrokeCap.Round))
        drawPath(Path().apply { moveTo(44.5f, 62f); quadraticTo(50f, 66.5f, 55.5f, 62f) }, Pal.Mouth, style = Stroke(1.9f, cap = StrokeCap.Round))
    }
}
