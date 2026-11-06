package app.dshmobile.ui.shell

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.theme.Sakura

/**
 * One segment of the chat box's status line (0.5.0, ccstatusline-style): [id] is one of [StatusLineConfig.ALL], [text] what it
 * says, [fill] a 0..1 mini bar (上下文). Plain values, so an unchanged line never recomposes the shell.
 */
data class StatusSeg(val id: String, val text: String, val fill: Float? = null, val description: String = text)

/** Which segments show, in what order; the owner sets it in Settings. */
data class StatusLineConfig(val order: List<String> = DEFAULT) {
    companion object {
        val ALL = listOf("work", "model", "effort", "review", "context", "speed", "tokens", "cost")
        val DEFAULT = listOf("work", "model", "effort", "review", "context", "speed", "cost")
        val NAMES = mapOf(
            "work" to "工作狀態", "model" to "模型", "effort" to "思考強度", "review" to "審查輪數",
            "context" to "上下文", "speed" to "速度 tok/s", "tokens" to "本對話用量", "cost" to "花費",
        )
        const val KEY = "status_line"

        fun load(prefs: SharedPreferences): StatusLineConfig = parse(prefs.getString(KEY, null))
        fun save(prefs: SharedPreferences, c: StatusLineConfig) { prefs.edit().putString(KEY, c.order.joinToString(",")).apply() }

        /** Unknown ids are dropped, duplicates kept once; null (never set) is the default; an empty string hides the line. */
        fun parse(s: String?): StatusLineConfig =
            if (s == null) StatusLineConfig() else StatusLineConfig(s.split(',').map { it.trim() }.filter { it in ALL }.distinct())
    }

    fun apply(segs: List<StatusSeg>): List<StatusSeg> {
        val byId = segs.associateBy { it.id }
        return order.mapNotNull { byId[it] }
    }

    fun toggled(id: String): StatusLineConfig = if (id in order) copy(order = order - id) else copy(order = order + id)

    fun moved(id: String, by: Int): StatusLineConfig {
        val i = order.indexOf(id)
        val j = (i + by).coerceIn(0, order.lastIndex)
        if (i < 0 || i == j) return this
        val m = order.toMutableList()
        m.add(j, m.removeAt(i))
        return copy(order = m)
    }
}

/** A powerline chevron: a rectangle whose right edge points right; every segment but the first is notched on the left. */
private class Chevron(private val notch: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tip = with(density) { 8.dp.toPx() }
        val p = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width - tip, 0f)
            lineTo(size.width, size.height / 2)
            lineTo(size.width - tip, size.height)
            lineTo(0f, size.height)
            if (notch) lineTo(tip, size.height / 2)
            close()
        }
        return Outline.Generic(p)
    }
}

/** Palette pairs (background, text), cycling along the line. */
private val SegColors = listOf(
    Sakura.Primary to Color.White,
    Sakura.Aqua to Sakura.Text,
    Sakura.MilkTea to Sakura.Text,
    Sakura.RoseBrown to Color.White,
    Sakura.Background to Sakura.Text,
    Sakura.TextSecondary to Color.White,
)

object StatusTags {
    const val LINE = "status-line"
    const val SEG = "status-seg-"
}

/** The status line above the input row; [onClick] gets a segment id (思考/審查 change, 上下文 opens 數據, 模型 the model menu). */
@Composable
fun StatusLine(segs: List<StatusSeg>, onClick: (String) -> Unit, modifier: Modifier = Modifier) {
    if (segs.isEmpty()) return
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(StatusTags.LINE),
        horizontalArrangement = Arrangement.spacedBy((-4).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segs.forEachIndexed { i, s ->
            val (bg, fg) = SegColors[i % SegColors.size]
            val shape = Chevron(notch = i > 0)
            Row(
                Modifier.heightIn(min = 30.dp).clip(shape).background(bg)
                    .clickable(role = Role.Button) { onClick(s.id) }
                    .semantics(mergeDescendants = true) { contentDescription = s.description }
                    .testTag(StatusTags.SEG + s.id)
                    .padding(start = if (i > 0) 14.dp else 10.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(s.text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FogFonts.ChakraPetch, maxLines = 1)
                s.fill?.let { f ->
                    Box(Modifier.width(28.dp).height(5.dp).clip(RoundedCornerShape(50)).background(fg.copy(alpha = .3f))) {
                        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().background(fg))
                    }
                }
            }
        }
    }
}
