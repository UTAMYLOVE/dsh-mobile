package app.dshmobile.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.chat.ComposeMode
import app.dshmobile.ui.fog.glass
import app.dshmobile.ui.theme.Sakura

private val Card = RoundedCornerShape(16.dp)
private val Pill = RoundedCornerShape(50)

/**
 * The ＋ sheet (0.5.0, mockup 2), floating above the dock: 附件 (相片・拍照問・檔案), 這次要怎麼做 (five modes, the chosen one
 * marked) and 找小助理 (projects shown as assistants). At most 60 % of the screen high; it scrolls inside.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlusSheet(
    mode: ComposeMode,
    assistants: List<AssistantChip>,
    onImage: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onMode: (ComposeMode) -> Unit,
    onAssistant: (String) -> Unit,
    onNewAssistant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val max = (LocalConfiguration.current.screenHeightDp * .6f).dp
    Column(
        modifier.fillMaxWidth().heightIn(max = max).glass(RoundedCornerShape(24.dp)).verticalScroll(rememberScrollState())
            .padding(14.dp).testTag(Tags.PLUS_SHEET),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section(stringResource(R.string.plus_attach), "ATTACH")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(stringResource(R.string.shell_attach_image), Tags.ATTACH_IMAGE, Modifier.weight(1f), onImage)
            Tile(stringResource(R.string.shell_camera), Tags.ATTACH_CAMERA, Modifier.weight(1f), onCamera, highlight = true)
            Tile(stringResource(R.string.shell_attach_file), Tags.ATTACH_FILE, Modifier.weight(1f), onFile)
        }
        Section(stringResource(R.string.plus_mode), "MODE")
        ComposeMode.entries.forEach { m ->
            val on = m == mode
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Color.White.copy(alpha = if (on) .95f else .6f), Card)
                    .border(if (on) 2.dp else 1.dp, if (on) Sakura.Primary else Sakura.Primary.copy(alpha = .4f), Card)
                    .clickable(role = Role.Button) { onMode(m) }.testTag(Tags.MODE + m.name).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.zh, color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(m.hint, color = Sakura.TextSecondary, fontSize = 11.sp)
                }
                if (on) Text("✓", color = Sakura.RoseBrown, fontSize = 16.sp)
            }
        }
        Section(stringResource(R.string.plus_assistants), "ASSISTANTS")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            assistants.forEach { a -> Chip(a.name, Tags.ASSISTANT + a.id, dashed = false) { onAssistant(a.id) } }
            Chip(stringResource(R.string.plus_new_assistant), Tags.ASSISTANT + "new", dashed = true, onClick = onNewAssistant)
        }
    }
}

@Composable
private fun Section(zh: String, en: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(zh, color = Sakura.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .1.em)
        Text(en, color = Sakura.RoseBrown, fontSize = 9.sp, letterSpacing = .2.em)
    }
}

@Composable
private fun Tile(label: String, tag: String, modifier: Modifier, onClick: () -> Unit, highlight: Boolean = false) {
    Box(
        modifier.heightIn(min = 64.dp).background(if (highlight) Sakura.Aqua.copy(alpha = .3f) else Color.White.copy(alpha = .75f), Card)
            .border(1.dp, if (highlight) Sakura.Aqua else Sakura.Primary.copy(alpha = .55f), Card)
            .clickable(role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Sakura.Text, fontSize = 13.sp) }
}

@Composable
private fun Chip(label: String, tag: String, dashed: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 40.dp).background(Color.White.copy(alpha = .75f), Pill)
            .border(1.dp, if (dashed) Sakura.Primary else Sakura.Aqua, Pill)
            .clickable(role = Role.Button, onClick = onClick).testTag(tag).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (dashed) Sakura.RoseBrown else Sakura.Text, fontSize = 13.sp) }
}
