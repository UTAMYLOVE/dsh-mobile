package app.dshmobile.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.agency.core.engine.WaterPlan
import app.agency.core.rules.CoachRules
import app.dshmobile.ui.kit.PinkButton
import kotlinx.coroutines.launch
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.PlanStore
import app.dshmobile.R
import app.dshmobile.agency.StartState
import app.dshmobile.ui.agency.onMain
import app.dshmobile.ui.kit.Sakura
import java.time.Instant
import app.agency.core.time.AgencyClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val SCHEDULE_DAY = "ag-schedule-day-"
const val WATER_START = "ag-water-start"
const val WATER_START_SAVE = "ag-water-start-save"
const val WATER_START_NOTE = "ag-water-start-note"

/**
 * 設定 › 作息 (arch T11): the next seven days as the day planner builds them, read-only. Times change only by a proposal he
 * accepts (T12) or by him; the 「今天休息」, 休假餐 and 「早上很餓」 of a day show in its plan.
 */
@Composable
fun SchedulePage(ready: StartState.Ready) {
    val week by produceState<List<DayPlan>>(emptyList()) {
        onMain {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val p = ready.store.profile.get()
                    val today = CoachRules.coachDay(Instant.now(), AgencyClock.TAIPEI, p?.dayStartHour ?: 5)
                    val plans = PlanStore(ready.store)
                    (0L..6L).map { plans.plan(today.plusDays(it)) }
                }.getOrDefault(emptyList())
            }
        }
    }
    Section(stringResource(R.string.ag_set_schedule)) {
        Note(stringResource(R.string.ag_schedule_note))
        week.forEachIndexed { i, plan ->
            val kind = plan.type.zh + if (plan.rest) "・休息日" else ""
            Text("星期${CoachRules.weekdayZh(plan.day.dayOfWeek)}　$kind", color = Sakura.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag(SCHEDULE_DAY + i))
            Text(plan.slots.joinToString("、") { it.time + " " + it.label }, color = Sakura.TextSecondary, fontSize = 13.sp)
        }
    }
    WaterStart(ready)
}

/** 喝水計畫 (T17): the day the bottle plan counts from (he sets it here; empty: the day he joined) and today's target. */
@Composable
private fun WaterStart(ready: StartState.Ready) {
    val scope = rememberCoroutineScope()
    var changed by remember { mutableIntStateOf(0) }
    val current by produceState<Pair<String?, Int>?>(null, changed) {
        onMain {
            value = withContext(Dispatchers.IO) {
                runCatching { PlanStore(ready.store).specs().water.startDay to ready.brain.waterTarget() }.getOrNull()
            }
        }
    }
    var text by rememberSaveable { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val saved = stringResource(R.string.ag_area_saved)
    Section(stringResource(R.string.ag_water_start_title)) {
        Note(stringResource(R.string.ag_water_start_note))
        current?.let { Text(stringResource(R.string.ag_water_start_now, WaterPlan.bottles(it.second)), color = Sakura.Text, fontSize = 15.sp) }
        Field(stringResource(R.string.ag_water_start_field), text ?: current?.first.orEmpty(), WATER_START) { text = it.take(10) }
        note?.let { Text(it, color = Sakura.Text, fontSize = 13.sp, modifier = Modifier.testTag(WATER_START_NOTE)) }
        PinkButton(
            stringResource(R.string.ag_save),
            onClick = {
                val t = text ?: current?.first.orEmpty()
                scope.launch {
                    onMain {
                        val problem = withContext(Dispatchers.IO) { runCatching { ready.brain.setWaterStart(t) }.getOrElse { "沒有存到，再試一次。" } }
                        note = problem ?: saved
                        if (problem == null) { text = null; changed++ }
                    }
                }
            },
            modifier = Modifier.testTag(WATER_START_SAVE),
        )
    }
}
