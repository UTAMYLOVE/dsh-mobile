package app.dshmobile.ui.fog

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.ui.theme.Sakura

enum class FogButtonKind { PINK, AQUA, GHOST }

private val Pill = RoundedCornerShape(50)

/** A capsule button. Text is always [Sakura.Text]: never white on pink, never aqua as text. */
@Composable
fun FogButton(text: String, kind: FogButtonKind, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fill: Modifier = when (kind) {
        FogButtonKind.PINK -> Modifier
            .glow(Sakura.Primary.copy(alpha = .85f), 9.dp, Pill)
            .background(SizedRadial(.35f, .3f, Sakura.Background, Sakura.Primary), Pill)
            .border(1.dp, Color.White.copy(alpha = .9f), Pill)
        FogButtonKind.AQUA -> Modifier
            .glow(Sakura.Aqua.copy(alpha = .7f), 8.dp, Pill)
            .background(Sakura.Aqua.copy(alpha = .40f), Pill)
            .border(1.dp, Sakura.Aqua.copy(alpha = .95f), Pill)
        FogButtonKind.GHOST -> Modifier
            .background(Color.White.copy(alpha = .5f), Pill)
            .border(1.dp, Sakura.Primary.copy(alpha = .6f), Pill)
    }
    Box(
        modifier
            .alpha(if (enabled) 1f else .45f)
            .heightIn(min = 44.dp)
            .then(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.em, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Radial gradient centred at a fraction of the box, inner colour to outer, reaching the farthest corner. */
private class SizedRadial(val fx: Float, val fy: Float, val inner: Color, val outer: Color) : androidx.compose.ui.graphics.ShaderBrush() {
    override fun createShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader {
        val c = androidx.compose.ui.geometry.Offset(size.width * fx, size.height * fy)
        val r = maxOf(c.x, size.width - c.x).let { x -> maxOf(c.y, size.height - c.y).let { y -> kotlin.math.hypot(x, y) } }
        return androidx.compose.ui.graphics.RadialGradientShader(c, r.coerceAtLeast(1f), listOf(inner, outer), null)
    }
}

/** A three-way (or n-way) selector: capsule track, the chosen cell lifted in white. Cell [i] carries the test tag `tagPrefix + i`. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, tagPrefix: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = .45f), Pill)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val lift by animateFloatAsState(if (on) 1f else 0f, tween(FogMotion.SCRIM_MS, easing = FogMotion.EaseOut), label = "seg")
            Box(
                Modifier
                    .weight(1f)
                    .testTag(tagPrefix + i)
                    .semantics { this.selected = on }
                    .drawBehind {
                        if (lift > 0f) {
                            softShadow(Pill, Sakura.RoseBrown.copy(alpha = .18f * lift), 10.dp, dy = 3.dp)
                            drawRoundRect(
                                cssLinear(140f, size, 0f to Color.White.copy(alpha = lift), 1f to Color.White.copy(alpha = .6f * lift)),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                            )
                        }
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) Sakura.Text else Sakura.TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
