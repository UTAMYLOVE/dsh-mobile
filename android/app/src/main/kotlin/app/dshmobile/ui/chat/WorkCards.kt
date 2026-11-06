package app.dshmobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.agents.AgentStatus
import app.dshmobile.agents.Mood
import app.dshmobile.chat.Todo
import app.dshmobile.chat.TodoStatus
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.theme.Sakura

object WorkTags {
    const val TODO_BAR = "todo-bar"
    const val TODO_ITEM = "todo-item-"
    const val CONTEMPLATE = "contemplate-card"
    const val STANCE = "stance-"
    const val PLAN_CARD = "plan-card"
    const val PLAN_APPROVE = "plan-approve"
    const val PLAN_REVISE = "plan-revise"
    const val PLAN_FEEDBACK = "plan-feedback"
    const val PLAN_SEND = "plan-send"
}

/** The three stances of 沉思 (dshm-contemplate names its subagents after them). */
val STANCES = listOf("樂觀派", "保守派", "數據派")

private val Card = RoundedCornerShape(16.dp)

/** 「進度 3 / 7」, the item in progress and an aqua→pink bar; tapping shows every item. Nothing when there are no todos. */
@Composable
fun TodoBar(todos: List<Todo>, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    if (todos.isEmpty()) return
    val done = todos.count { it.status == TodoStatus.DONE }
    val current = todos.firstOrNull { it.status == TodoStatus.IN_PROGRESS } ?: todos.firstOrNull { it.status == TodoStatus.PENDING }
    val fraction = done.toFloat() / todos.size
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(Card)
            .background(Color.White.copy(alpha = .72f))
            .border(1.dp, Sakura.Primary.copy(alpha = .45f), Card)
            .clickable(role = Role.Button, onClick = onToggle)
            .testTag(WorkTags.TODO_BAR)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.work_progress), color = Sakura.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("$done / ${todos.size}", color = Sakura.Text, fontSize = 13.sp, fontFamily = FogFonts.ChakraPetch, fontWeight = FontWeight.SemiBold)
            Text(
                current?.text ?: stringResource(R.string.work_all_done), color = Sakura.Text, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "︿" else "﹀", color = Sakura.RoseBrown, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Sakura.Background)) {
            if (fraction > 0f) {
                Box(
                    Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(Sakura.Aqua, Sakura.Primary))),
                )
            }
        }
        if (expanded) {
            todos.forEachIndexed { i, t ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 28.dp).testTag("${WorkTags.TODO_ITEM}$i"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val (mark, tint) = when (t.status) {
                        TodoStatus.DONE -> "✓" to Sakura.RoseBrown
                        TodoStatus.IN_PROGRESS -> "▸" to Sakura.Primary
                        TodoStatus.PENDING -> "○" to Sakura.TextSecondary
                    }
                    Text(mark, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        t.text, fontSize = 13.sp, lineHeight = 19.sp,
                        color = if (t.status == TodoStatus.DONE) Sakura.TextSecondary else Sakura.Text,
                        fontWeight = if (t.status == TodoStatus.IN_PROGRESS) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** The stance subagents of 沉思 among [team] (in stance order), or empty when this conversation is not contemplating. */
fun stanceAgents(team: List<AgentStatus>): List<AgentStatus> =
    STANCES.mapNotNull { s -> team.lastOrNull { !it.isLead && it.label.contains(s) } }

/** Three stance avatars with 想好了／思考中, then the line saying the lead merges them. */
@Composable
fun ContemplateCard(children: List<AgentStatus>, modifier: Modifier = Modifier) {
    if (children.isEmpty()) return
    val allDone = children.all { it.mood == Mood.DONE || it.mood == Mood.IDLE }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(Card)
            .background(Color.White.copy(alpha = .72f))
            .border(1.dp, Sakura.Aqua, Card)
            .testTag(WorkTags.CONTEMPLATE)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.work_contemplate_title), color = Sakura.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            children.forEach { a ->
                val stance = STANCES.firstOrNull { a.label.contains(it) } ?: a.label
                val done = a.mood == Mood.DONE || a.mood == Mood.IDLE
                val failed = a.mood == Mood.ERROR
                val state = stringResource(
                    when {
                        failed -> R.string.work_stance_failed
                        done -> R.string.work_stance_done
                        else -> R.string.work_stance_thinking
                    },
                )
                Column(
                    Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$stance $state" }
                        .testTag("${WorkTags.STANCE}$stance"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (done) Brush.linearGradient(listOf(Sakura.Background, Sakura.Primary)) else Brush.linearGradient(listOf(Color.White, Sakura.Aqua))),
                        contentAlignment = Alignment.Center,
                    ) { Text(stance.take(1), color = Sakura.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    Text(stance, color = Sakura.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(state, color = if (failed) Sakura.RoseBrown else Sakura.TextSecondary, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Text(
            stringResource(if (allDone) R.string.work_contemplate_merging else R.string.work_contemplate_waiting),
            color = Sakura.RoseBrown, fontSize = 12.sp,
        )
    }
}
