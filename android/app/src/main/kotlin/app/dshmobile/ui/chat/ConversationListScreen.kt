package app.dshmobile.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.chat.DayGroup
import app.dshmobile.chat.SessionRow
import app.dshmobile.config.AgentModels
import app.dshmobile.config.displayName
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.FogDimens
import app.dshmobile.ui.fog.FogText
import app.dshmobile.ui.fog.glass
import app.dshmobile.ui.fog.glow
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura

private sealed interface Dialog {
    data class Rename(val row: SessionRow) : Dialog
    data class Delete(val row: SessionRow) : Dialog
}

/**
 * All conversations, grouped by day, with search, 「新對話」, and per-row rename and delete. Stateless apart from which row's
 * menu is open and which dialog is showing. Laid out for a 412 dp portrait width: titles and last lines are one line and
 * ellipsize; the row menu opens inline under its row and the dialogs are cards in the screen itself, so nothing floats over
 * another control.
 */
@Composable
fun ConversationListScreen(
    groups: List<Pair<DayGroup, List<SessionRow>>>,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    // Back closes a card, then an open row menu, before the shell's back leaves the list (final review B2-M6).
    BackHandler(enabled = dialog != null || menuFor != null) {
        if (dialog != null) dialog = null else menuFor = null
    }
    val closeLabel = stringResource(R.string.list_close)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.list_title), color = Sakura.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.em,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                FogButton(stringResource(R.string.list_new), FogButtonKind.PINK, onNew, Modifier.testTag(Tags.NEW_SESSION))
                // A round 48 dp button: FogButton's 20 dp side padding would leave the ✕ no room (it showed as "…").
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .5f))
                        .border(1.dp, Sakura.Primary.copy(alpha = .6f), RoundedCornerShape(50))
                        .clickable(role = Role.Button, onClick = onClose).testTag(Tags.SESSION_CLOSE).semantics { contentDescription = closeLabel },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Sakura.Text, fontSize = 16.sp) }
            }
            SearchField(query, onQuery)
            if (groups.isEmpty()) {
                Text(
                    stringResource(if (query.isBlank()) R.string.list_empty else R.string.list_no_match),
                    color = Sakura.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = true).testTag(Tags.SESSION_LIST)) {
                for ((group, rows) in groups) {
                    item(key = "g-" + group.name) {
                        Text(
                            group.zh, style = FogText.Label.copy(letterSpacing = 0.2.em), maxLines = 1,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).testTag(Tags.SESSION_GROUP + group.name),
                        )
                    }
                    items(rows, key = { it.id }) { row ->
                        ConversationRow(
                            row,
                            expanded = menuFor == row.id,
                            onOpen = { onOpen(row.id) },
                            onMenu = { menuFor = if (menuFor == row.id) null else row.id },
                            onRename = { menuFor = null; dialog = Dialog.Rename(row) },
                            onDelete = { menuFor = null; dialog = Dialog.Delete(row) },
                        )
                    }
                }
            }
        }
        when (val d = dialog) {
            is Dialog.Rename -> RenameCard(d.row, { dialog = null }) { name -> dialog = null; onRename(d.row.id, name) }
            is Dialog.Delete -> DeleteCard({ dialog = null }) { dialog = null; onDelete(d.row.id) }
            null -> Unit
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val shape = RoundedCornerShape(50)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = TextStyle(color = Sakura.Text, fontSize = 15.sp),
        cursorBrush = SolidColor(Sakura.Text),
        modifier = Modifier.fillMaxWidth().testTag(Tags.SESSION_SEARCH),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).background(Color.White.copy(alpha = .6f), shape)
                    .border(1.dp, Color.White.copy(alpha = .9f), shape).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) Text(stringResource(R.string.list_search_hint), color = Sakura.TextSecondary, fontSize = 15.sp, maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
private fun ConversationRow(
    row: SessionRow, expanded: Boolean, onOpen: () -> Unit, onMenu: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
) {
    val dash = Sakura.RoseBrown.copy(alpha = .25f)
    val title = row.title.ifBlank { stringResource(R.string.list_untitled) }
    val model = AgentModels.displayName(row.nextModel ?: row.model)
    val sub = listOfNotNull(row.lastLine?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }, model).joinToString(" · ")
    val running = stringResource(R.string.list_running)
    val menuLabel = stringResource(R.string.list_menu)
    Column(
        Modifier.fillMaxWidth().drawBehind {
            drawLine(dash, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
        },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onOpen).padding(vertical = 12.dp).testTag(Tags.SESSION_ROW + row.id),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val dot = if (row.running) Sakura.Aqua else Sakura.Primary
                val round = RoundedCornerShape(50)
                Box(Modifier.size(8.dp).glow(dot, 8.dp, round).background(dot, round).then(if (row.running) Modifier.semantics { contentDescription = running } else Modifier))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, color = Sakura.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sub.isNotEmpty()) Text(sub, color = Sakura.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(
                Modifier.size(48.dp).clickable(role = Role.Button, onClick = onMenu).testTag(Tags.SESSION_MENU + row.id)
                    .semantics { contentDescription = menuLabel },
                contentAlignment = Alignment.Center,
            ) { Text("⋮", color = Sakura.Text, fontSize = 20.sp) }
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FogButton(stringResource(R.string.list_rename), FogButtonKind.GHOST, onRename, Modifier.weight(1f).testTag(Tags.RENAME))
                FogButton(stringResource(R.string.list_delete), FogButtonKind.GHOST, onDelete, Modifier.weight(1f).testTag(Tags.DELETE))
            }
        }
    }
}

/** A card over a scrim that fills the screen; the scrim swallows taps so nothing behind it can be hit. */
@Composable
private fun ModalCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Sakura.Text.copy(alpha = .28f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .glass(RoundedCornerShape(FogDimens.Sheet)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

@Composable
private fun RenameCard(row: SessionRow, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(row.title) }
    val valid = name.isNotBlank()
    ModalCard(onCancel) {
        Text(stringResource(R.string.list_rename_title), color = Sakura.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.em, maxLines = 1)
        val shape = RoundedCornerShape(12.dp)
        BasicTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            textStyle = TextStyle(color = Sakura.Text, fontSize = 16.sp), cursorBrush = SolidColor(Sakura.Text),
            modifier = Modifier.fillMaxWidth().testTag(Tags.RENAME_FIELD),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(Color.White.copy(alpha = .6f), shape).border(1.dp, Sakura.Primary.copy(alpha = .6f), shape).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) { inner() }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FogButton(stringResource(R.string.list_cancel), FogButtonKind.GHOST, onCancel, Modifier.weight(1f).testTag(Tags.RENAME_CANCEL))
            FogButton(stringResource(R.string.list_rename_save), FogButtonKind.PINK, { onSave(name.trim()) }, Modifier.weight(1f).testTag(Tags.RENAME_SAVE), enabled = valid)
        }
    }
}

@Composable
private fun DeleteCard(onCancel: () -> Unit, onConfirm: () -> Unit) {
    ModalCard(onCancel) {
        Text(stringResource(R.string.list_delete_title), color = Sakura.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.em)
        Text(stringResource(R.string.list_delete_body), color = Sakura.TextSecondary, fontSize = 14.sp, lineHeight = 22.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FogButton(stringResource(R.string.list_cancel), FogButtonKind.GHOST, onCancel, Modifier.weight(1f).testTag(Tags.DELETE_CANCEL))
            FogButton(stringResource(R.string.list_delete), FogButtonKind.PINK, onConfirm, Modifier.weight(1f).testTag(Tags.DELETE_CONFIRM))
        }
    }
}
