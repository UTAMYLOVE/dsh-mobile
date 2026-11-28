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
 * The trainee himself, drawn (never a photograph and never 小葵): a gender-neutral figure with short hair, a calm face and a
 * plain top, on a circle in his member colour. A 100 x 100 vector scaled to any size.
 */
@Composable
fun TraineeAvatar(size: Dp = 72.dp, member: Color = Pal.Lavender, modifier: Modifier = Modifier, outlined: Boolean = true) {
    val base = modifier.size(size)
    Canvas(modifier = if (outlined) base.sticker(member, CircleShape, shadow = 3.dp).clip(CircleShape) else base.clip(CircleShape)) {
        drawTrainee(member)
    }
}

fun DrawScope.drawTrainee(member: Color) {
    val k = minOf(this.size.width, this.size.height) / 100f
    scale(k, k, pivot = Offset.Zero) {
        drawRect(member, size = Size(100f, 100f))
        // shoulders in a plain top
        val top = Path().apply {
            moveTo(12f, 100f)
            cubicTo(14f, 82f, 32f, 77f, 50f, 77f)
            cubicTo(68f, 77f, 86f, 82f, 88f, 100f)
            close()
        }
        drawPath(top, Color.White)
        drawPath(Path().apply { moveTo(41f, 78f); quadraticTo(50f, 87f, 59f, 78f) }, Pal.Ink.copy(alpha = 0.35f), style = Stroke(1.4f, cap = StrokeCap.Round))
        // neck and face
        drawRect(Pal.Skin, Offset(44.5f, 64f), Size(11f, 15f))
        drawOval(Pal.Skin, Offset(31f, 24f), Size(38f, 46f))
        // short hair: a cap over the head with a short side sweep, ears left bare
        val hair = Path().apply {
            moveTo(29f, 48f)
            cubicTo(25f, 24f, 38f, 14f, 50f, 14f)
            cubicTo(63f, 14f, 76f, 24f, 71f, 48f)
            cubicTo(68f, 40f, 64f, 34f, 60f, 31f)
            cubicTo(52f, 37f, 40f, 38f, 29f, 48f)
            close()
        }
        drawPath(hair, Pal.Ink)
        drawPath(
            Path().apply { moveTo(36f, 22f); cubicTo(42f, 17f, 50f, 16f, 57f, 18f) },
            Color.White.copy(alpha = 0.25f), style = Stroke(1.6f, cap = StrokeCap.Round),
        )
        drawOval(Pal.Skin, Offset(26.5f, 47f), Size(6f, 9f))
        drawOval(Pal.Skin, Offset(67.5f, 47f), Size(6f, 9f))
        // eyes and a small smile
        drawCircle(Pal.Ink, 1.9f, Offset(42f, 51f))
        drawCircle(Pal.Ink, 1.9f, Offset(58f, 51f))
        drawPath(Path().apply { moveTo(50f, 54f); quadraticTo(51.4f, 57f, 50f, 58f) }, Pal.Ink.copy(alpha = 0.3f), style = Stroke(1.2f, cap = StrokeCap.Round))
        drawPath(Path().apply { moveTo(44.5f, 62f); quadraticTo(50f, 66f, 55.5f, 62f) }, Pal.Mouth, style = Stroke(1.9f, cap = StrokeCap.Round))
    }
}
