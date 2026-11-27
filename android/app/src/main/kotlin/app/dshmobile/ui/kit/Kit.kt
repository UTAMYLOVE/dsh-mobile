package app.dshmobile.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The agency's building blocks (arch 1.1 `ui/kit`, 0.9.1 redesign): the white card with a soft shadow, the idol card (the pink →
 * lilac gradient with a thin gold edge), the pink capsule button (one per region) and the quieter outlined one, status pills,
 * the progress bar and the icon tile. Text is only ever [Sakura.Text] or [Sakura.TextSecondary].
 */
@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().shadow(4.dp, shape, ambientColor = Sakura.RoseBrown.copy(alpha = .10f), spotColor = Sakura.RoseBrown.copy(alpha = .10f))
            .background(Sakura.White, shape).padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** An idol piece (練習生證, 今天要做的事, 升級典禮, 小卡): the soft gradient, tinted by his colour, and a thin gold line. */
@Composable
fun IdolSurface(modifier: Modifier = Modifier, radius: Dp = 20.dp, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier.fillMaxWidth().shadow(4.dp, shape, ambientColor = Sakura.RoseBrown.copy(alpha = .10f), spotColor = Sakura.RoseBrown.copy(alpha = .10f))
            .background(Sakura.White, shape).background(Sakura.idol(LocalAccent.current), shape).border(1.dp, Sakura.Gold.copy(alpha = .8f), shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A card with a title (the settings sub-pages and the older panels): the same look as [Card]. */
@Composable
fun Panel(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(width = 4.dp, height = 16.dp).background(LocalAccent.current.fill, RoundedCornerShape(2.dp)))
                Text(title, style = Type.Label)
            }
        }
        content()
    }
}

enum class ButtonSize(val height: Dp, val font: Int, val pad: Dp) { Small(36.dp, 14, 14.dp), Regular(44.dp, 15, 20.dp), Large(56.dp, 17, 24.dp) }

@Composable
fun PinkButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null,
    size: ButtonSize = ButtonSize.Regular,
) {
    val a = LocalAccent.current
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = size.height),
        colors = ButtonDefaults.buttonColors(
            containerColor = a.fill, contentColor = a.onFill,
            disabledContainerColor = a.fill.copy(alpha = .35f), disabledContentColor = Sakura.TextSecondary,
        ),
        contentPadding = PaddingValues(horizontal = size.pad, vertical = 6.dp),
    ) {
        if (icon != null) {
            LineIcon(icon, tint = if (enabled) a.onFill else Sakura.TextSecondary, size = 20.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = size.font.sp, fontWeight = FontWeight.Bold, color = if (enabled) a.onFill else Sakura.TextSecondary)
    }
}

/** The quieter button beside a [PinkButton] (清除, 返回): white with a pink line. */
@Composable
fun SoftButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null,
    size: ButtonSize = ButtonSize.Regular,
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = size.height),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, LocalAccent.current.fill.copy(alpha = if (enabled) 1f else .4f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Sakura.White),
        contentPadding = PaddingValues(horizontal = size.pad, vertical = 6.dp),
    ) {
        if (icon != null) {
            LineIcon(icon, tint = Sakura.Text, size = 20.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = if (enabled) Sakura.Text else Sakura.TextSecondary, fontSize = size.font.sp)
    }
}

/** A text-only action (跳過, 修改). */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp)) {
        Text(text, color = Sakura.TextSecondary, fontSize = 15.sp)
    }
}

/** One choice out of a few, as capsules in a row; [selected] is the index of the chosen one. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, tagPrefix: String? = null) {
    val shape = RoundedCornerShape(16.dp)
    val accent = LocalAccent.current
    Row(modifier.fillMaxWidth().background(Sakura.Background, shape).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).background(if (on) accent.fill else Sakura.White, shape)
                    .semantics { this.selected = on }
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .then(if (tagPrefix != null) Modifier.testTag("$tagPrefix$i") else Modifier),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) accent.onFill else Sakura.Text, fontSize = 14.sp) }
        }
    }
}

/** A small rounded label (「練習生 C」, 「已證實」). */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Sakura.Text, fontSize = 12.sp,
        modifier = modifier.background(Sakura.Background, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

enum class Status { Done, Todo, Skipped, Pink }

/** A status label: 已完成 (aqua), 還沒做 (milk tea), 已跳過 / a pink note. */
@Composable
fun StatusPill(kind: Status, text: String, modifier: Modifier = Modifier) {
    val (bg, fg) = when (kind) {
        Status.Done -> Sakura.AquaSoft to Sakura.AquaInk
        Status.Todo -> Sakura.MilkTeaSoft to Sakura.TextSecondary
        Status.Skipped, Status.Pink -> Sakura.Background to Sakura.Text
    }
    Text(text, color = fg, fontSize = 13.sp, modifier = modifier.background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp))
}

/** 8 dp, the deep pink over milk tea. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.fillMaxWidth().height(8.dp).background(Sakura.MilkTeaSoft, shape)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Sakura.PrimaryDeep, Sakura.Lilac)), shape))
    }
}

/** A rounded square with a line icon (36–44 dp). */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tone: Color = Sakura.Background, size: Dp = 40.dp) {
    Box(modifier.size(size).background(tone, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { LineIcon(icon, size = size * 0.55f) }
}

/** A tappable choice (討論 options, quick chips). */
@Composable
fun Chip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, marked: Boolean = false, enabled: Boolean = true) {
    val shape = RoundedCornerShape(17.dp)
    val accent = LocalAccent.current
    Box(
        modifier.heightIn(min = 36.dp).background(if (marked) Sakura.Background else Sakura.White, shape)
            .border(1.dp, if (marked) accent.fill else Sakura.MilkTea, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Sakura.Text else Sakura.TextSecondary, fontSize = 14.sp) }
}
