package app.dshmobile.ui.chat

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
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
    data class Sheet(val row: SessionRow) : Dialog
    data object NewMenu : Dialog
}

/** What the app knows about a row beyond dsh's summary (0.5.0): pin, lock, temporary, project. */
data class RowFlags(val pinned: Boolean = false, val locked: Boolean = false, val temporary: Boolean = false, val project: String? = null)

/** The conversation sheet's extra actions; a null one is left out of the sheet. */
class ListActions(
    val onPin: ((String, Boolean) -> Unit)? = null,
    val onLock: ((String, Boolean) -> Unit)? = null,
    val onMove: ((String) -> Unit)? = null,
    val onShelf: ((String) -> Unit)? = null,
    val onExport: ((String) -> Unit)? = null,
    val onNewTemporary: (() -> Unit)? = null,
    val onNewInProject: (() -> Unit)? = null,
)

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
    /** Pinned conversations, shown first under 釘選 (they are left out of [groups]). */
    pinned: List<SessionRow> = emptyList(),
    flags: (String) -> RowFlags = { RowFlags() },
    actions: ListActions = ListActions(),
    /** The tab row (全部・專案・小助理・小幫手) and, for any tab but 全部, its page instead of the list. */
    tabs: (@Composable () -> Unit)? = null,
    tabPage: (@Composable () -> Unit)? = null,
    /** 設定 under the header (0.6.0: Settings left the dock); null hides it. */
    onSettings: (() -> Unit)? = null,
) {
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    // Back closes a card or sheet before the shell's back leaves the list (final review B2-M6).
    BackHandler(enabled = dialog != null) { dialog = null }
    val closeLabel = stringResource(R.string.list_close)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.list_title), color = Sakura.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.em,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                FogButton(stringResource(R.string.list_new), FogButtonKind.PINK, onNew, Modifier.testTag(Tags.NEW_SESSION))
                if (actions.onNewTemporary != null || actions.onNewInProject != null) {
                    val moreLabel = stringResource(R.string.list_new_more)
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .5f))
                            .border(1.dp, Sakura.Primary.copy(alpha = .6f), RoundedCornerShape(50))
                            .clickable(role = Role.Button) { dialog = Dialog.NewMenu }.testTag(Tags.NEW_MENU)
                            .semantics { contentDescription = moreLabel },
                        contentAlignment = Alignment.Center,
                    ) { Text("▾", color = Sakura.Text, fontSize = 16.sp) }
                }
                // A round 48 dp button: FogButton's 20 dp side padding would leave the ✕ no room (it showed as "…").
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .5f))
                        .border(1.dp, Sakura.Primary.copy(alpha = .6f), RoundedCornerShape(50))
                        .clickable(role = Role.Button, onClick = onClose).testTag(Tags.SESSION_CLOSE).semantics { contentDescription = closeLabel },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Sakura.Text, fontSize = 16.sp) }
            }
            if (onSettings != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FogButton(stringResource(R.string.open_settings), FogButtonKind.GHOST, onSettings, Modifier.testTag(Tags.LIST_SETTINGS))
                }
            }
            tabs?.invoke()
            if (tabPage != null) {
                Box(Modifier.fillMaxWidth().weight(1f, fill = true)) { tabPage() }
                return@Column
            }
            SearchField(query, onQuery)
            if (groups.isEmpty() && pinned.isEmpty()) {
                Text(
                    stringResource(if (query.isBlank()) R.string.list_empty else R.string.list_no_match),
                    color = Sakura.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = true).testTag(Tags.SESSION_LIST)) {
                if (pinned.isNotEmpty()) {
                    item(key = "g-pinned") {
                        Text(
                            stringResource(R.string.list_pinned), style = FogText.Label.copy(letterSpacing = 0.2.em), maxLines = 1,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).testTag(Tags.SESSION_GROUP + "PINNED"),
                        )
                    }
                    items(pinned, key = { "p-" + it.id }) { row ->
                        ConversationRow(row, flags(row.id), onOpen = { onOpen(row.id) }, onMenu = { dialog = Dialog.Sheet(row) })
                    }
                }
                for ((group, rows) in groups) {
                    item(key = "g-" + group.name) {
                        Text(
                            group.zh, style = FogText.Label.copy(letterSpacing = 0.2.em), maxLines = 1,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).testTag(Tags.SESSION_GROUP + group.name),
                        )
                    }
                    items(rows, key = { it.id }) { row ->
                        ConversationRow(row, flags(row.id), onOpen = { onOpen(row.id) }, onMenu = { dialog = Dialog.Sheet(row) })
                    }
                }
            }
        }
        when (val d = dialog) {
            is Dialog.Rename -> RenameCard(d.row, { dialog = null }) { name -> dialog = null; onRename(d.row.id, name) }
            is Dialog.Delete -> DeleteCard({ dialog = null }) { dialog = null; onDelete(d.row.id) }
            is Dialog.Sheet -> RowSheet(d.row, flags(d.row.id), actions, onClose = { dialog = null }, onRename = { dialog = Dialog.Rename(d.row) }, onDelete = { dialog = Dialog.Delete(d.row) })
            Dialog.NewMenu -> NewMenu(actions, onNew) { dialog = null }
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
private fun ConversationRow(row: SessionRow, flags: RowFlags, onOpen: () -> Unit, onMenu: () -> Unit) {
    val dash = Sakura.RoseBrown.copy(alpha = .25f)
    val title = row.title.ifBlank { stringResource(R.string.list_untitled) }
    val model = AgentModels.displayName(row.nextModel ?: row.model)
    // A locked conversation shows only its title (mockup 9): never its last line.
    val last = if (flags.locked) stringResource(R.string.list_locked_hint) else row.lastLine?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
    val sub = listOfNotNull(last, model.takeUnless { flags.locked }).joinToString(" · ")
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (flags.pinned) Badge(stringResource(R.string.list_pinned), Sakura.Primary, Color.White, Tags.SESSION_ROW + row.id + "-pinned")
                        Text(title, color = Sakura.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (flags.locked) Badge(stringResource(R.string.list_badge_locked), Sakura.Text, Color.White, Tags.SESSION_ROW + row.id + "-locked")
                        if (flags.temporary) Badge(stringResource(R.string.list_badge_temporary), Sakura.Aqua.copy(alpha = .7f), Sakura.Text, Tags.SESSION_ROW + row.id + "-temp")
                        flags.project?.let { Badge(it, Sakura.Primary.copy(alpha = .35f), Sakura.Text, Tags.SESSION_ROW + row.id + "-project") }
                        // A conversation of the other mode: opening it is fine, sending asks to switch (DshLayout.otherModeOf).
                        app.dshmobile.runtime.DshLayout.otherModeOf(row.cwd)?.let { madeNative ->
                            Text(
                                stringResource(if (madeNative) R.string.list_mode_native else R.string.list_mode_proot),
                                color = Sakura.Text, fontSize = 10.sp,
                                modifier = Modifier.background(Sakura.MilkTea.copy(alpha = .55f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                                    .testTag(Tags.SESSION_ROW + row.id + "-mode"),
                            )
                        }
                    }
                    if (sub.isNotEmpty()) Text(sub, color = Sakura.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(
                Modifier.size(48.dp).clickable(role = Role.Button, onClick = onMenu).testTag(Tags.SESSION_MENU + row.id)
                    .semantics { contentDescription = menuLabel },
                contentAlignment = Alignment.Center,
            ) { Text("⋮", color = Sakura.Text, fontSize = 20.sp) }
        }
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color, tag: String) {
    Text(
        text, color = fg, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp).testTag(tag),
    )
}

/** A bottom sheet over a scrim; back or a tap on the scrim closes it. */
@Composable
private fun BottomSheet(onDismiss: () -> Unit, tag: String, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Sakura.Text.copy(alpha = .28f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Color.White)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState()).testTag(tag).padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
    }
}

@Composable
private fun SheetItem(label: String, tag: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick)
            .testTag(tag).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(label, color = if (danger) Sakura.RoseBrown else Sakura.Text, fontSize = 16.sp) }
}

/** The conversation sheet (mockup 5): 釘選・鎖定・移到專案・改名・加入書架・匯出・刪除. */
@Composable
private fun RowSheet(row: SessionRow, flags: RowFlags, a: ListActions, onClose: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    BottomSheet(onClose, Tags.ROW_SHEET) {
        Text(
            row.title.ifBlank { stringResource(R.string.list_untitled) }, color = Sakura.TextSecondary, fontSize = 13.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        a.onPin?.let { f -> SheetItem(stringResource(if (flags.pinned) R.string.list_unpin else R.string.list_pin), Tags.SHEET_PIN) { onClose(); f(row.id, !flags.pinned) } }
        a.onLock?.let { f -> SheetItem(stringResource(if (flags.locked) R.string.list_unlock else R.string.list_lock), Tags.SHEET_LOCK) { onClose(); f(row.id, !flags.locked) } }
        a.onMove?.let { f -> SheetItem(stringResource(R.string.list_move), Tags.SHEET_MOVE) { onClose(); f(row.id) } }
        SheetItem(stringResource(R.string.list_rename), Tags.RENAME, onClick = onRename)
        a.onShelf?.let { f -> SheetItem(stringResource(R.string.list_shelf), Tags.SHEET_SHELF) { onClose(); f(row.id) } }
        a.onExport?.let { f -> SheetItem(stringResource(R.string.list_export), Tags.SHEET_EXPORT) { onClose(); f(row.id) } }
        SheetItem(stringResource(R.string.list_delete), Tags.DELETE, danger = true, onClick = onDelete)
    }
}

/** 新對話 ▾: 一般對話・臨時對話・在專案裡開… */
@Composable
private fun NewMenu(a: ListActions, onNew: () -> Unit, onClose: () -> Unit) {
    BottomSheet(onClose, Tags.NEW_MENU_SHEET) {
        SheetItem(stringResource(R.string.list_new_normal), Tags.NEW_NORMAL) { onClose(); onNew() }
        a.onNewTemporary?.let { f -> SheetItem(stringResource(R.string.list_new_temporary), Tags.NEW_TEMPORARY) { onClose(); f() } }
        a.onNewInProject?.let { f -> SheetItem(stringResource(R.string.list_new_in_project), Tags.NEW_IN_PROJECT) { onClose(); f() } }
        Text(stringResource(R.string.list_temporary_note), color = Sakura.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

/**
 * A card over a scrim that fills the screen. The scrim swallows taps but no longer dismisses (stage 2 §F: on a small screen with the
 * keyboard up a tap meant for the card landed on the scrim and lost the edit); back or 取消 closes it. The card sits near the top,
 * above the keyboard, and scrolls when the space left is too short.
 */
@Composable
private fun ModalCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Sakura.Text.copy(alpha = .28f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .imePadding().padding(top = 24.dp, bottom = 12.dp).testTag(Tags.MODAL_SCRIM),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .glass(RoundedCornerShape(FogDimens.Sheet)).verticalScroll(rememberScrollState()).padding(20.dp),
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
