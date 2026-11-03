package app.dshmobile.ui.fog

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.ui.theme.Sakura

/** A glass panel with the mockup's heading: glowing square dot, Chinese title, English label, fading rule, right-hand note. */
@Composable
fun FogPanel(
    title: String?,
    en: String? = null,
    meta: String? = null,
    ice: Boolean = false,
    index: Int = 0,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val effects = LocalEffects.current
    // Rise: from 26dp below, 98% size, transparent, one panel after another. Only with depth transitions (off in saver / reduced motion).
    val rise = remember { Animatable(if (effects.depthTransitions) 0f else 1f) }
    LaunchedEffect(effects.depthTransitions) {
        if (!effects.depthTransitions) {
            rise.snapTo(1f)
        } else if (rise.value < 1f) {
            val delay = FogMotion.STAGGER_BASE_MS + FogMotion.STAGGER_MS * index.coerceIn(0, FogMotion.STAGGER_MAX)
            rise.animateTo(1f, tween(FogMotion.RISE_MS, delay, FogMotion.EaseOut))
        }
    }
    // Read through derivedStateOf: only the flip at the end of the rise recomposes this, not each frame of it.
    val settled by remember { derivedStateOf { rise.value >= 1f } }
    val shape = RoundedCornerShape(FogDimens.Panel)
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = rise.value
                alpha = p
                // Each draw of the panel takes the alpha, instead of an offscreen layer per rising panel.
                compositingStrategy = CompositingStrategy.ModulateAlpha
                translationY = (1f - p) * 26.dp.toPx()
                val sc = .98f + .02f * p
                scaleX = sc
                scaleY = sc
            }
            .glass(shape, settled)
            .padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 18.dp),
    ) {
        if (title != null || en != null || meta != null) {
            PanelHeading(title, en, meta, ice)
            Box(Modifier.height(14.dp))
        }
        content()
    }
}

@Composable
private fun PanelHeading(title: String?, en: String?, meta: String?, ice: Boolean) {
    val dot = if (ice) Sakura.Aqua else Sakura.Primary
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val square = RoundedCornerShape(2.dp)
        Box(Modifier.size(7.dp).glow(dot, 6.dp, square).background(dot, square))
        if (title != null) {
            Text(title, color = Sakura.Text, fontSize = 13.sp, letterSpacing = 0.12.em, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (en != null) {
            Text(en, style = FogText.Label.copy(letterSpacing = 0.2.em), maxLines = 1)
        }
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Sakura.RoseBrown.copy(alpha = .45f), Sakura.RoseBrown.copy(alpha = 0f)))),
        )
        if (meta != null) {
            Text(meta, style = FogText.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
