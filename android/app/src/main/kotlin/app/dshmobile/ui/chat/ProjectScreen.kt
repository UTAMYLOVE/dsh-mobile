package app.dshmobile.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.chat.FileKind
import app.dshmobile.chat.Project
import app.dshmobile.chat.ProjectStore
import app.dshmobile.chat.ProjectTemplate
import app.dshmobile.chat.SessionRow
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.theme.Sakura

object ProjectTags {
    const val TABS = "list-tabs"
    const val TAB = "list-tab-"
    const val TILE = "project-tile-"
    const val NEW = "project-new"
    const val NEW_NAME = "project-new-name"
    const val NEW_CREATE = "project-new-create"
    const val TEMPLATE = "project-template-"
    const val PAGE = "project-page"
    const val INSTRUCTIONS = "project-instructions"
    const val SAVE = "project-save"
    const val ASSISTANT = "project-assistant"
    const val ADD_FILE = "project-add-file"
    const val NEW_CHAT = "project-new-chat"
    const val CHAT = "project-chat-"
    const val PICK = "project-pick-"
}

/** The list's tabs (mockup 5). 小幫手 shows a count when it has helpers. */
enum class ListTab(val zh: String) { ALL("全部"), PROJECTS("專案"), ASSISTANTS("小助理"), HELPERS("小幫手") }

private val Pane = RoundedCornerShape(18.dp)
private val Pill = RoundedCornerShape(50)

@Composable
fun ListTabs(tab: ListTab, onTab: (ListTab) -> Unit, helpers: Int = 0) {
    Row(Modifier.fillMaxWidth().testTag(ProjectTags.TABS), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ListTab.values().forEach { t ->
            val on = t == tab
            val label = if (t == ListTab.HELPERS && helpers > 0) "${t.zh} $helpers" else t.zh
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(Pill)
                    .background(if (on) Sakura.Primary else Color.White.copy(alpha = .55f))
                    .border(1.dp, if (on) Sakura.Primary else Sakura.Primary.copy(alpha = .4f), Pill)
                    .clickable(role = Role.Tab) { onTab(t) }.testTag(ProjectTags.TAB + t.name),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) Color.White else Sakura.Text, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1) }
        }
    }
}

/** The 專案 / 小助理 tab: one tile per project, 新專案 and the five templates. */
@Composable
fun ProjectsTab(projects: List<Project>, assistantsOnly: Boolean, chatsOf: (String) -> Int, onOpen: (String) -> Unit, onCreate: (String, ProjectTemplate?) -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val shown = if (assistantsOnly) projects.filter { it.assistant } else projects
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (shown.isEmpty()) {
            Text(
                stringResource(if (assistantsOnly) R.string.project_no_assistants else R.string.project_none),
                color = Sakura.TextSecondary, fontSize = 13.sp, lineHeight = 20.sp,
            )
        }
        shown.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clip(Pane).background(Color.White.copy(alpha = .72f)).border(1.dp, Sakura.Primary.copy(alpha = .45f), Pane)
                    .clickable(role = Role.Button) { onOpen(p.slug) }.testTag(ProjectTags.TILE + p.slug).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Initial(p)
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = Sakura.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.project_meta, chatsOf(p.slug), p.files.size) + if (p.assistant) "・" + stringResource(R.string.project_is_assistant) else "",
                        color = Sakura.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("›", color = Sakura.RoseBrown, fontSize = 20.sp)
            }
        }
        if (naming) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.project_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(ProjectTags.NEW_NAME))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FogButton(stringResource(R.string.project_create), FogButtonKind.PINK, { naming = false; onCreate(name, null); name = "" }, Modifier.testTag(ProjectTags.NEW_CREATE), enabled = name.isNotBlank())
                FogButton(stringResource(R.string.plan_back), FogButtonKind.GHOST, { naming = false })
            }
        } else {
            FogButton(stringResource(R.string.project_new), FogButtonKind.AQUA, { naming = true }, Modifier.testTag(ProjectTags.NEW))
        }
        Text(stringResource(R.string.project_templates), color = Sakura.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        ProjectStore.templates.forEach { t ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(Sakura.Background.copy(alpha = .6f))
                    .clickable(role = Role.Button) { onCreate(t.name, t) }.testTag(ProjectTags.TEMPLATE + t.id).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("＋", color = Sakura.RoseBrown, fontSize = 15.sp)
                Column(Modifier.weight(1f)) {
                    Text(t.name, color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(t.instructions, color = Sakura.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Initial(p: Project, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Sakura.Background, Sakura.Primary))),
        contentAlignment = Alignment.Center,
    ) { Text(p.initial, color = Sakura.Text, fontSize = 18.sp, fontWeight = FontWeight.Black) }
}

/** Mockup 6: one project. */
@Composable
fun ProjectPage(
    p: Project,
    chats: List<SessionRow>,
    onBack: () -> Unit,
    onSave: (instructions: String) -> Unit,
    onAssistant: (Boolean) -> Unit,
    onAddFile: () -> Unit,
    onRemoveFile: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var text by rememberSaveable(p.slug) { mutableStateOf(p.instructions) }
    Column(Modifier.fillMaxSize().testTag(ProjectTags.PAGE)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .6f))
                        .border(1.dp, Sakura.Primary.copy(alpha = .6f), RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Text("‹", color = Sakura.Text, fontSize = 20.sp) }
                Initial(p)
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = Sakura.Text, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.project_meta, chats.size, p.files.size), color = Sakura.TextSecondary, fontSize = 11.sp)
                }
            }
            Section(stringResource(R.string.project_instructions), "INSTRUCTIONS") {
                OutlinedTextField(
                    text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag(ProjectTags.INSTRUCTIONS),
                    placeholder = { Text(stringResource(R.string.project_instructions_hint), fontSize = 13.sp) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.project_instructions_note), color = Sakura.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    if (text != p.instructions) TextButton(onClick = { onSave(text) }, modifier = Modifier.testTag(ProjectTags.SAVE)) {
                        Text(stringResource(R.string.project_save), color = Sakura.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Section(stringResource(R.string.project_files), "FILES") {
                p.files.forEach { f ->
                    val kind = FileKind.of(f)
                    val (bg, fg) = badgeColors(kind)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Sakura.Background.copy(alpha = .6f)).padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            kind.badge, color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FogFonts.ChakraPetch,
                            modifier = Modifier.background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                        Text(f, color = Sakura.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onRemoveFile(f) }) { Text(stringResource(R.string.project_remove_file), color = Sakura.RoseBrown, fontSize = 12.sp) }
                    }
                }
                TextButton(onClick = onAddFile, modifier = Modifier.testTag(ProjectTags.ADD_FILE)) {
                    Text(stringResource(R.string.project_add_file), color = Sakura.RoseBrown, fontSize = 13.sp)
                }
            }
            Section(stringResource(R.string.project_chats), "CHATS") {
                if (chats.isEmpty()) Text(stringResource(R.string.project_no_chats), color = Sakura.TextSecondary, fontSize = 12.sp)
                chats.forEach { c ->
                    Text(
                        c.title.ifBlank { stringResource(R.string.list_untitled) }, color = Sakura.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button) { onOpenChat(c.id) }
                            .testTag(ProjectTags.CHAT + c.id).padding(vertical = 10.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(Pane).background(Color.White.copy(alpha = .78f)).border(1.dp, Sakura.Primary.copy(alpha = .5f), Pane).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.project_assistant), color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.project_assistant_note), color = Sakura.TextSecondary, fontSize = 11.sp)
                }
                Switch(
                    p.assistant, onAssistant, modifier = Modifier.testTag(ProjectTags.ASSISTANT),
                    colors = SwitchDefaults.colors(checkedTrackColor = Sakura.Primary, checkedThumbColor = Color.White, uncheckedTrackColor = Sakura.MilkTea, uncheckedThumbColor = Color.White),
                )
            }
        }
        FogButton(stringResource(R.string.project_new_chat), FogButtonKind.PINK, onNewChat, Modifier.fillMaxWidth().padding(top = 10.dp).testTag(ProjectTags.NEW_CHAT))
    }
}

@Composable
private fun Section(title: String, en: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Pane).background(Color.White.copy(alpha = .78f)).border(1.dp, Sakura.Primary.copy(alpha = .5f), Pane).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Sakura.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(en, color = Sakura.RoseBrown, fontSize = 9.sp, fontFamily = FogFonts.ChakraPetch)
        }
        content()
    }
}

/** 移到專案 / 在專案裡開…: pick one of the projects. */
@Composable
fun ProjectPicker(projects: List<Project>, title: String, onPick: (String?) -> Unit, allowNone: Boolean) {
    BackHandler { onPick(null) }
    Box(Modifier.fillMaxSize().background(Sakura.Text.copy(alpha = .28f)).clickable(role = Role.Button) { onPick(null) }, contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Color.White)
                .clickable(enabled = false) {}.verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, color = Sakura.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
            if (projects.isEmpty()) Text(stringResource(R.string.project_none), color = Sakura.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(6.dp))
            projects.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { onPick(p.slug) }
                        .testTag(ProjectTags.PICK + p.slug).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) { Initial(p, 32); Text(p.name, color = Sakura.Text, fontSize = 15.sp) }
            }
            if (allowNone) {
                Text(
                    stringResource(R.string.project_take_out), color = Sakura.RoseBrown, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onPick("") }.testTag(ProjectTags.PICK + "none").padding(horizontal = 8.dp, vertical = 14.dp),
                )
            }
        }
    }
}
