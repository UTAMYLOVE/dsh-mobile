package app.dshmobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dshmobile.R
import app.dshmobile.chat.Helper
import app.dshmobile.chat.HelperState
import app.dshmobile.chat.HelperTiming
import app.dshmobile.chat.Helpers
import app.dshmobile.ui.fog.FogButton
import app.dshmobile.ui.fog.FogButtonKind
import app.dshmobile.ui.fog.FogFonts
import app.dshmobile.ui.theme.Sakura
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object HelperTags {
    const val PAGE = "helpers-page"
    const val ROW = "helper-row-"
    const val TOGGLE = "helper-toggle-"
    const val RECREATE = "helper-recreate-"
    const val NEW = "helper-new"
    const val NAME = "helper-name"
    const val TASK = "helper-task"
    const val TIME = "helper-time"
    const val KIND = "helper-kind-"
    const val CREATE = "helper-create"
    const val EXAMPLE = "helper-example-"
}

/** Ready-made helpers (mockup 7). */
val HELPER_EXAMPLES = listOf(
    Triple("晨報", "整理今天的天氣、台股盤前重點（美股收盤、台指期夜盤、外資期貨）和我今天的提醒，寫成一份短短的晨報。", HelperTiming.Daily("07:50")),
    Triple("盤後整理", "整理今天台股收盤：加權指數、成交量、三大法人買賣超、漲跌幅最大的類股，以及台積電和聯發科的表現。", HelperTiming.Weekly(5, "15:10")),
    Triple("新聞追蹤", "搜尋過去幾小時 AI 伺服器和半導體的重要新聞，挑 3 則最重要的，每則一句話加來源。", HelperTiming.Every(240)),
)

private val Pane = RoundedCornerShape(18.dp)
private val TIME_FMT = DateTimeFormatter.ofPattern("M/d HH:mm")

/** Mockup 7: the always-on helpers, a switch each, and the form to add one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HelpersPage(
    helpers: List<Helper>,
    stateOf: (Helper) -> HelperState,
    lastLineOf: (Helper) -> String?,
    now: Long,
    onOpen: (Helper) -> Unit,
    onToggle: (Helper, Boolean) -> Unit,
    onRecreate: (Helper) -> Unit,
    onDelete: (Helper) -> Unit,
    onCreate: (name: String, task: String, timing: HelperTiming) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var task by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableIntStateOf(0) } // 0 daily, 1 weekly, 2 every
    var time by rememberSaveable { mutableStateOf("08:00") }
    var day by rememberSaveable { mutableIntStateOf(1) }
    var minutes by rememberSaveable { mutableIntStateOf(60) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(HelperTags.PAGE), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.helpers_intro), color = Sakura.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        helpers.forEach { h ->
            val st = stateOf(h)
            Column(
                Modifier.fillMaxWidth().clip(Pane).background(Color.White.copy(alpha = .78f))
                    .border(1.dp, if (st == HelperState.LOST) Sakura.RoseBrown else Sakura.Primary.copy(alpha = .5f), Pane)
                    .clickable(role = Role.Button) { onOpen(h) }.testTag(HelperTags.ROW + h.id).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(h.name, color = Sakura.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val next = Helpers.nextRun(h, now)?.let { TIME_FMT.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), Helpers.ZONE)) }
                        Text(
                            h.timing.zh + (if (st == HelperState.ACTIVE && next != null) "・下次 $next" else ""),
                            color = Sakura.TextSecondary, fontSize = 12.sp, fontFamily = FogFonts.ChakraPetch,
                        )
                    }
                    Switch(
                        st != HelperState.PAUSED, { on -> onToggle(h, on) }, modifier = Modifier.testTag(HelperTags.TOGGLE + h.id),
                        colors = SwitchDefaults.colors(checkedTrackColor = Sakura.Primary, checkedThumbColor = Color.White, uncheckedTrackColor = Sakura.MilkTea, uncheckedThumbColor = Color.White),
                    )
                }
                Text(h.task, color = Sakura.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                lastLineOf(h)?.takeIf { it.isNotBlank() }?.let {
                    Text(stringResource(R.string.helpers_last, it), color = Sakura.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (st == HelperState.LOST) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.helpers_lost), color = Sakura.RoseBrown, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onRecreate(h) }, modifier = Modifier.testTag(HelperTags.RECREATE + h.id)) {
                            Text(stringResource(R.string.helpers_recreate), color = Sakura.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onDelete(h) }) { Text(stringResource(R.string.helpers_delete), color = Sakura.RoseBrown, fontSize = 12.sp) }
                }
            }
        }
        if (!adding) {
            FogButton(stringResource(R.string.helpers_new), FogButtonKind.AQUA, { adding = true }, Modifier.testTag(HelperTags.NEW))
            Text(stringResource(R.string.helpers_examples), color = Sakura.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            HELPER_EXAMPLES.forEachIndexed { i, (n, t, timing) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(Sakura.Background.copy(alpha = .6f))
                        .clickable(role = Role.Button) {
                            name = n; task = t
                            when (timing) {
                                is HelperTiming.Daily -> { kind = 0; time = timing.time }
                                is HelperTiming.Weekly -> { kind = 1; time = timing.time; day = timing.day }
                                is HelperTiming.Every -> { kind = 2; minutes = timing.minutes }
                            }
                            adding = true
                        }.testTag(HelperTags.EXAMPLE + i).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("＋", color = Sakura.RoseBrown, fontSize = 15.sp)
                    Column(Modifier.weight(1f)) {
                        Text("$n・${timing.zh}", color = Sakura.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(t, color = Sakura.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().clip(Pane).background(Color.White.copy(alpha = .85f)).border(1.dp, Sakura.Aqua, Pane).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.helpers_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(HelperTags.NAME))
                OutlinedTextField(task, { task = it }, label = { Text(stringResource(R.string.helpers_task)) }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag(HelperTags.TASK))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("每天", "每週", "每隔一段時間").forEachIndexed { i, label -> Chip(label, kind == i, HelperTags.KIND + i) { kind = i } }
                }
                if (kind == 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HelperTiming.DAYS.forEachIndexed { i, d -> Chip("週$d", day == i + 1, "helper-day-${i + 1}") { day = i + 1 } }
                    }
                }
                if (kind == 2) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(30, 60, 120, 240, 480).forEach { m -> Chip(HelperTiming.Every(m).zh, minutes == m, "helper-every-$m") { minutes = m } }
                    }
                } else {
                    OutlinedTextField(
                        time, { time = it.take(5) }, label = { Text(stringResource(R.string.helpers_time)) }, singleLine = true,
                        isError = !HelperTiming.validTime(time), modifier = Modifier.fillMaxWidth().testTag(HelperTags.TIME),
                    )
                }
                val timing = when (kind) {
                    0 -> HelperTiming.Daily(time)
                    1 -> HelperTiming.Weekly(day, time)
                    else -> HelperTiming.Every(minutes)
                }
                val valid = name.isNotBlank() && task.isNotBlank() && (kind == 2 || HelperTiming.validTime(time))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FogButton(stringResource(R.string.helpers_create), FogButtonKind.PINK, {
                        onCreate(name, task, timing)
                        adding = false; name = ""; task = ""
                    }, Modifier.testTag(HelperTags.CREATE), enabled = valid)
                    FogButton(stringResource(R.string.plan_back), FogButtonKind.GHOST, { adding = false })
                }
                Text(stringResource(R.string.helpers_note), color = Sakura.TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, color = Sakura.Text, maxLines = 1,
        modifier = Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(50))
            .background(if (on) Sakura.Aqua.copy(alpha = .6f) else Color.White.copy(alpha = .6f))
            .border(1.dp, if (on) Sakura.Aqua else Sakura.Primary.copy(alpha = .4f), RoundedCornerShape(50))
            .clickable(role = Role.RadioButton, onClick = onClick).testTag(tag).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
