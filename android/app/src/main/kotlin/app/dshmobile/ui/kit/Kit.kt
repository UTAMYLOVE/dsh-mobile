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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The agency's small set of building blocks on the Sakura palette (arch 1.1 `ui/kit`): a white panel with a title, the pink
 * capsule button, a segmented choice, a pill and a chip. Text is only ever [Sakura.Text] or [Sakura.TextSecondary].
 */
@Composable
fun Panel(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().background(Sakura.White.copy(alpha = .86f), shape).border(1.dp, Sakura.MilkTea, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = 1.dp).background(Sakura.Primary, RoundedCornerShape(2.dp)).padding(3.5.dp))
                Text(title, color = Sakura.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        content()
    }
}

@Composable
fun PinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Sakura.Primary, contentColor = Sakura.Text),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    ) { Text(text, fontSize = 15.sp) }
}

/** The quieter button beside a [PinkButton] (清除, 返回). */
@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp)) {
        Text(text, color = Sakura.Text, fontSize = 15.sp)
    }
}

/** One choice out of a few, as capsules in a row; [selected] is the index of the chosen one. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, tagPrefix: String? = null) {
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.fillMaxWidth().background(Sakura.Background, shape).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).background(if (on) Sakura.Primary else Sakura.White, shape)
                    .semantics { this.selected = on }
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .then(if (tagPrefix != null) Modifier.testTag("$tagPrefix$i") else Modifier),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = Sakura.Text, fontSize = 14.sp) }
        }
    }
}

/** A small rounded label (「練習生 C」, 「已證實」). */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Sakura.Text, fontSize = 12.sp,
        modifier = modifier.background(Sakura.Background, RoundedCornerShape(12.dp)).border(1.dp, Sakura.Primary, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** A tappable choice (later: 討論 options). */
@Composable
fun Chip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, marked: Boolean = false) {
    val shape = RoundedCornerShape(16.dp)
    Text(
        text, color = Sakura.Text, fontSize = 14.sp,
        modifier = modifier.background(if (marked) Sakura.Primary else Sakura.White, shape).border(1.dp, Sakura.Primary, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
