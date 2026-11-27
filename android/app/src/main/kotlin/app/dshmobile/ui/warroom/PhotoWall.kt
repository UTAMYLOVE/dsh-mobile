package app.dshmobile.ui.warroom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.brain.Photocard
import app.agency.core.model.TrainingKind
import app.dshmobile.R
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.collectOnMain
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.agency.training.MovePictogram
import app.dshmobile.ui.kit.LocalAccent
import app.dshmobile.ui.kit.Panel
import app.dshmobile.ui.kit.Sakura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PhotoWallTags {
    const val CARD = "ag-photo-wall"
    const val ITEM = "ag-photo-wall-"
    const val EMPTY = "ag-photo-wall-empty"
    const val ATTENDANCE = "ag-photo-wall-attendance-"
}

/**
 * 拍立得照片牆 (L11): one polaroid a day for the first core or strength done, newest first, drawn by code (a picture of the
 * move in his colour and the date). Only to collect; it buys nothing.
 */
@Composable
fun PhotoWallCard(ready: StartState.Ready) {
    val revision by ready.brain.revision.collectOnMain()
    val cards by produceState<List<Photocard>>(emptyList(), revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.photocards() }.getOrDefault(emptyList()) } }
    }
    val attendance by produceState<List<Pair<String, Int>>>(emptyList(), revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.attendanceCards() }.getOrDefault(emptyList()) } }
    }
    val levels by produceState<List<Triple<String, app.agency.core.model.LessonCategory, Int>>>(emptyList(), revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.levelCards() }.getOrDefault(emptyList()) } }
    }
    val bodies by produceState<List<Pair<String, String>>>(emptyList(), revision) {
        onMain { value = withContext(Dispatchers.IO) { runCatching { ready.brain.bodyTimeline() }.getOrDefault(emptyList()) } }
    }
    Panel(title = stringResource(R.string.ag_wall_title, cards.size), modifier = Modifier.testTag(PhotoWallTags.CARD)) {
        // 升級小卡 (T19e) and the Sunday 全身對比照 timeline.
        levels.forEach { (day, cat, l) ->
            Text(stringResource(R.string.ag_wall_level, day.substring(5).replace('-', '/'), cat.zh, app.agency.core.engine.Levels.name(l)), color = Sakura.Text, fontSize = 14.sp)
        }
        if (bodies.isNotEmpty()) {
            Text(stringResource(R.string.ag_wall_bodies, bodies.size, bodies.joinToString("、") { it.first.substring(5).replace('-', '/') }), color = Sakura.Text, fontSize = 14.sp)
        }
        // 全勤小卡 (L27): 7 and 30 days in a row.
        attendance.forEach { (day, n) ->
            Text(stringResource(R.string.ag_wall_attendance, day.substring(5).replace('-', '/'), n), color = Sakura.Text, fontSize = 14.sp, modifier = Modifier.testTag(PhotoWallTags.ATTENDANCE + day))
        }
        if (cards.isEmpty()) {
            Text(stringResource(R.string.ag_wall_empty), color = Sakura.TextSecondary, fontSize = 14.sp, modifier = Modifier.testTag(PhotoWallTags.EMPTY))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            cards.forEach { c -> Polaroid(c) }
        }
    }
}

@Composable
private fun Polaroid(c: Photocard) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.width(96.dp).background(Sakura.White, shape).border(1.dp, Sakura.MilkTea, shape).padding(6.dp).testTag(PhotoWallTags.ITEM + c.day),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(84.dp).background(LocalAccent.current.fill.copy(alpha = .18f), RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
            val icon = if (c.kind == TrainingKind.CORE7) app.agency.core.engine.MoveIcon.HALF_BOAT else app.agency.core.engine.MoveIcon.PUSH_UP
            MovePictogram(icon, LocalAccent.current.fill, size = 72.dp)
        }
        Text(c.day.substring(5).replace('-', '/'), color = Sakura.Text, fontSize = 13.sp)
        Text(c.zh, color = Sakura.TextSecondary, fontSize = 12.sp)
    }
}
