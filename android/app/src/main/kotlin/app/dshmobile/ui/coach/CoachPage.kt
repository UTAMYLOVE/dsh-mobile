package app.dshmobile.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.coach.CoachEngine
import app.dshmobile.coach.CoachMessage
import app.dshmobile.coach.CoachRules
import app.dshmobile.coach.Speaker
import app.dshmobile.coach.Strip
import app.dshmobile.ui.shell.LocalPagePadding
import app.dshmobile.ui.shell.Tags
import app.dshmobile.ui.theme.Sakura

private val Bubble = RoundedCornerShape(16.dp)
private val StripShape = RoundedCornerShape(14.dp)

/** 教練: today's strip on top, then the conversation with the coach. The dock below sends to the coach. */
@Composable
fun CoachPage(engine: CoachEngine) {
    val ui by engine.ui.collectAsState()
    // A new coach day (05:00) shows the moment the page does.
    LaunchedEffect(Unit) { engine.refresh() }
    val pad = LocalPagePadding.current
    val list = rememberLazyListState()
    val count = ui.messages.size + if (ui.busy) 1 else 0
    LaunchedEffect(count) { if (count > 0) list.animateScrollToItem(count - 1) }
    Column(
        Modifier.fillMaxSize().testTag(Tags.COACH_PAGE).padding(top = pad.calculateTopPadding(), start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StripRow(ui.strip)
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = pad.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.messages.isEmpty()) item {
                Text(
                    stringResource(R.string.coach_empty), color = Sakura.TextSecondary, fontSize = 15.sp, lineHeight = 22.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                )
            }
            items(ui.messages, key = { it.id }) { m -> MessageBubble(m) }
            if (ui.busy) item {
                Text(stringResource(R.string.coach_busy), color = Sakura.TextSecondary, fontSize = 14.sp, modifier = Modifier.testTag(Tags.COACH_BUSY))
            }
        }
    }
}

@Composable
private fun StripRow(s: Strip) {
    val kcal = if (s.overBy > 0) stringResource(R.string.coach_strip_over, s.overBy) else stringResource(R.string.coach_strip_left, s.remainingKcal)
    val protein = stringResource(R.string.coach_strip_protein, s.proteinLeftG)
    val goal = s.nextGoalKg?.let { stringResource(R.string.coach_strip_goal, CoachRules.kg(it)) } ?: stringResource(R.string.coach_strip_no_goal)
    Text(
        "$kcal ・ $protein ・ $goal",
        color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(Sakura.White.copy(alpha = .78f), StripShape)
            .border(1.dp, Sakura.Primary.copy(alpha = .6f), StripShape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(Tags.COACH_STRIP),
    )
}

@Composable
private fun MessageBubble(m: CoachMessage) {
    val mine = m.who == Speaker.OWNER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            m.text, color = Sakura.Text, fontSize = 15.sp, lineHeight = 22.sp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(if (mine) Sakura.Primary.copy(alpha = .38f) else Sakura.White.copy(alpha = .88f), Bubble)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag(Tags.COACH_MSG + m.id),
        )
    }
}
