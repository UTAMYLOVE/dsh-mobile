package app.dshmobile.ui.agency.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.brain.handlers.LessonBoard
import app.agency.core.engine.IdolCardFacts
import app.agency.core.engine.SlotState
import app.agency.core.engine.SlotStatus
import app.agency.core.engine.WaterPlan
import app.agency.core.schedule.Feature
import app.agency.core.schedule.SlotKey
import app.agency.core.schedule.SlotKind
import app.dshmobile.R
import app.dshmobile.ui.agency.PracticeTags
import app.dshmobile.ui.agency.meal.MealTags
import app.dshmobile.ui.agency.sign.SignTags
import app.dshmobile.ui.agency.training.TrainingTags
import app.dshmobile.ui.kit.Icons
import app.dshmobile.ui.kit.IconTile
import app.dshmobile.ui.kit.IdolSurface
import app.dshmobile.ui.kit.LineIcon
import app.dshmobile.ui.kit.LocalAccent
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.ProgressBar
import app.dshmobile.ui.kit.Sakura
import app.dshmobile.ui.kit.ButtonSize
import app.dshmobile.ui.kit.StatusPill
import app.dshmobile.ui.kit.Status
import app.dshmobile.ui.kit.Type

/** Test tags of the 今天 card. */
object TodayTags {
    const val CARD = "ag-today"
    const val COUNT = "ag-today-count"
    const val NEXT = "ag-today-next"
    const val TILE = "ag-today-tile-"
    const val HINT = "ag-today-rollout"
    const val LESSON = "ag-today-lesson"
    const val LIST = "ag-today-list"
}

private const val ID_LATE = "LATE_OUT"
private const val ID_WATER = "WATER"
private const val ID_SING = "SINGING"
private const val SING_TAG = "SINGING"

/** What a tile or the next-up button does; the home screen wires each to the same action the old buttons had. */
class TodayActions(
    val say: (String) -> Unit = {},
    val mealPhoto: () -> Unit = {},
    val mealMore: () -> Unit = {},
    val training: (SlotKey) -> Unit = {},
    val walk: () -> Unit = {},
    val practice: (SlotKey) -> Unit = {},
    val singing: () -> Unit = {},
    val lessons: () -> Unit = {},
)

/** One entry of the card: a slot of today (or water, or last night's 簽退). */
private data class Item(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val status: SlotStatus,
    val time: String?,
    val tag: String?,
    val action: String,
    val onClick: () -> Unit,
    val meal: Boolean = false,
    val sub: String? = null,
)

/**
 * 今天要做的事 (redesign 01/02): in a week that has only 理論課 open, the lesson row with 去上課 and one 「… 第 N 週開放」 line;
 * from week 2 on, 完成 N／M 項 with its bar, the next thing with one button whose label is the action, and one tile per open
 * item (done aqua with a check, now pink-framed, later milk tea), each doing what it says. A feature not open renders nothing.
 */
@Composable
fun TodayCard(facts: IdolCardFacts, lessons: LessonBoard?, busy: Boolean, actions: TodayActions, onList: () -> Unit, modifier: Modifier = Modifier) {
    val daily = listOf(Feature.SIGN, Feature.MEALS, Feature.CORE, Feature.STRENGTH, Feature.WATER).any { it in facts.open }
    IdolSurface(modifier.testTag(TodayTags.CARD)) {
        if (!daily) LessonsOnly(facts, lessons, busy, actions) else Daily(facts, busy, actions, onList)
    }
}

@Composable
private fun LessonsOnly(facts: IdolCardFacts, lessons: LessonBoard?, busy: Boolean, actions: TodayActions) {
    val owed = facts.slots.firstOrNull { it.key == SlotKey.LESSONS }?.slot?.count ?: app.agency.core.lessons.LessonPlan.DAILY
    val passed = lessons?.passedToday ?: 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ag_today_title), style = Type.Label, modifier = Modifier.weight(1f))
        StatusPill(Status.Pink, stringResource(R.string.ag_today_lessons, passed, owed))
    }
    val current = lessons?.today?.firstOrNull { !it.locked && it.passedDay == null }
    Row(Modifier.fillMaxWidth().testTag(TodayTags.LESSON), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(Icons.Play, size = 44.dp)
        Column(Modifier.weight(1f)) {
            when {
                current != null -> {
                    Text(stringResource(R.string.ag_today_lesson_row, passed + 1, current.category.zh + " " + current.title), style = Type.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.ag_today_lesson_sub), style = Type.Support)
                }
                passed >= owed && passed > 0 -> Text(stringResource(R.string.ag_today_lessons_done), style = Type.Body)
                else -> Text(stringResource(R.string.ag_today_no_lesson, stringResource(R.string.ag_chip_learn)), style = Type.Support)
            }
        }
        PinkButton(stringResource(R.string.ag_today_go_lesson), onClick = actions.lessons, enabled = !busy, size = ButtonSize.Small)
    }
    RolloutHint(facts)
}

@Composable
private fun RolloutHint(facts: IdolCardFacts) {
    val week = facts.nextWeek ?: return
    if (facts.nextWeekFeatures.isEmpty()) return
    val names = facts.nextWeekFeatures.let { if (it.size <= 1) it.joinToString("") else it.dropLast(1).joinToString("、") + "和" + it.last() }
    Box(Modifier.fillMaxWidth().padding(top = 4.dp).background(Sakura.Divider, RoundedCornerShape(1.dp)).heightIn(min = 1.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LineIcon(Icons.Lock, tint = Sakura.TextSecondary, size = 16.dp)
        Text(stringResource(R.string.ag_rollout_hint, names, week), style = Type.Caption, modifier = Modifier.testTag(TodayTags.HINT))
    }
}

/** The items of today the card shows (open features only; 突擊檢查 only once it started). */
@Composable
private fun items(facts: IdolCardFacts, actions: TodayActions): List<Item> {
    val signIn = stringResource(R.string.ag_sign_in)
    val signOut = stringResource(R.string.ag_sign_out)
    val photo = stringResource(R.string.ag_meal_photo)
    val start = stringResource(R.string.ag_train_start)
    val done = stringResource(R.string.ag_status_done)
    val goLesson = stringResource(R.string.ag_today_go_lesson)
    val out = mutableListOf<Item>()
    if (facts.lateSignOut) {
        out += Item(ID_LATE, stringResource(R.string.ag_today_late_out), Icons.Moon, SlotStatus.OPEN, null, SignTags.SIGN_OUT, signOut, { actions.say(signOut) })
    }
    val ride = stringResource(R.string.ag_ride_start)
    facts.slots.forEach { s ->
        val key = s.key
        val item: Item? = when {
            key == SlotKey.WAKE -> Item(key.name, signIn, Icons.Sun, s.status, s.slot.time, SignTags.SIGN_IN, signIn, { actions.say(signIn) })
            key == SlotKey.SIGN_OUT -> if (facts.lateSignOut) null else Item(key.name, signOut, Icons.Moon, s.status, s.slot.time, SignTags.SIGN_OUT, signOut, { actions.say(signOut) })
            key.kind == SlotKind.MEAL -> Item(
                key.name, if (key == SlotKey.RIDE_MEAL) stringResource(R.string.ag_tile_ride_meal, s.slot.time) else key.zh, Icons.Bowl, s.status, s.slot.time,
                MealTags.PHOTO, photo, actions.mealPhoto, meal = true,
                sub = if (Feature.MEALS in facts.open) (if (facts.kcalLeft >= 0) stringResource(R.string.ag_today_left, facts.kcalLeft) else stringResource(R.string.ag_today_over, -facts.kcalLeft)) else null,
            )
            key == SlotKey.RIDE_OUT -> Item(key.name, ride, Icons.Pin, s.status, s.slot.time, null, ride, { actions.say(ride) })
            key == SlotKey.CORE -> Item(key.name, stringResource(R.string.ag_train_core_button), Icons.Heart, s.status, s.slot.time, TrainingTags.CORE, start, { actions.training(key) })
            key == SlotKey.STRENGTH -> Item(key.name, stringResource(R.string.ag_train_strength_button), Icons.Dumbbell, s.status, s.slot.time, TrainingTags.STRENGTH, start, { actions.training(key) })
            key == SlotKey.CARDIO -> Item(key.name, stringResource(R.string.ag_train_cardio_button), Icons.Bolt, s.status, s.slot.time, TrainingTags.CARDIO, start, { actions.training(key) })
            key == SlotKey.STRETCH -> Item(key.name, stringResource(R.string.ag_train_stretch_button), Icons.Sparkle, s.status, s.slot.time, TrainingTags.STRETCH, start, { actions.training(key) })
            key == SlotKey.WALK -> Item(key.name, stringResource(R.string.ag_train_walk_button), Icons.Pin, s.status, s.slot.time, TrainingTags.WALK, stringResource(R.string.ag_train_walk_button), actions.walk)
            key == SlotKey.LESSONS || key == SlotKey.LESSON_REVIEW -> Item(key.name, key.zh, Icons.Book, s.status, s.slot.time, null, goLesson, actions.lessons)
            key.kind == SlotKind.PRACTICE -> {
                // 突擊檢查 is a surprise until it starts; once missed it is gone.
                if (key == SlotKey.SURPRISE_CHECK && s.status != SlotStatus.OPEN && s.status != SlotStatus.DONE) null
                else Item(key.name, key.zh, Icons.Star, s.status, s.slot.time, PracticeTags.PRACTICE + key.name, if (s.status == SlotStatus.DONE) done else start, { actions.practice(key) })
            }
            else -> null
        }
        if (item != null) out += item
    }
    if (Feature.WATER in facts.open && facts.waterTargetHalves > 0) {
        val half = stringResource(R.string.ag_water_half)
        val status = if (facts.waterHalves >= facts.waterTargetHalves) SlotStatus.DONE else SlotStatus.LATER
        out += Item(ID_WATER, stringResource(R.string.ag_tile_water, WaterPlan.bottles(facts.waterHalves)), Icons.Cup, status, null, null, half, { actions.say(half) })
    }
    if (Feature.VOCAL_BASICS in facts.open) {
        out += Item(ID_SING, stringResource(R.string.ag_tile_sing), Icons.Mic, SlotStatus.LATER, null, PracticeTags.PRACTICE + SING_TAG, start, actions.singing)
    }
    return out
}

@Composable
private fun Daily(facts: IdolCardFacts, busy: Boolean, actions: TodayActions, onList: () -> Unit) {
    val all = items(facts, actions)
    val counted = all.filter { it.id != ID_SING && it.id != ID_WATER }
    val doneN = counted.count { it.status == SlotStatus.DONE }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ag_today_title), style = Type.Label, modifier = Modifier.weight(1f))
        Row(
            Modifier.heightIn(min = 44.dp).clickable(onClick = onList).testTag(TodayTags.COUNT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.ag_today_done, doneN, counted.size), style = Type.Support)
            LineIcon(Icons.Right, tint = Sakura.TextSecondary, size = 18.dp)
        }
    }
    ProgressBar(if (counted.isEmpty()) 0f else doneN.toFloat() / counted.size)
    // Next up: the late sign-out first, then the first item open now, then the first coming later.
    val next = all.firstOrNull { it.id == ID_LATE } ?: all.firstOrNull { it.status == SlotStatus.OPEN && it.id != ID_WATER && it.id != ID_SING }
        ?: all.firstOrNull { it.status == SlotStatus.LATER && it.id != ID_WATER && it.id != ID_SING }
    if (next != null) NextUp(next, busy) else Text(stringResource(R.string.ag_today_all_done), style = Type.Body)
    // One node per tag: the next-up button has it, else the first tile of that kind (a meal tile opens the camera too).
    val tagged = mutableSetOf<String>()
    next?.tag?.let { tagged += it }
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        all.filter { it.id != next?.id }.forEach { item ->
            val tag = item.tag?.takeIf { tagged.add(it) }
            Tile(item, now = item.status == SlotStatus.OPEN, busy = busy, tag = tag, onLong = if (item.meal) actions.mealMore else null)
        }
    }
    RolloutHint(facts)
}

@Composable
private fun NextUp(item: Item, busy: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().background(Sakura.Background.copy(alpha = .7f), shape).padding(12.dp).testTag(TodayTags.NEXT),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(item.icon, tone = Sakura.White, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Text(item.time?.let { stringResource(R.string.ag_today_next, it, item.label) } ?: item.label, style = Type.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.sub?.let { Text(it, style = Type.Support) }
        }
        PinkButton(item.action, onClick = item.onClick, enabled = !busy, size = ButtonSize.Small, modifier = item.tag?.let { Modifier.testTag(it) } ?: Modifier)
    }
}

@Composable
private fun Tile(item: Item, now: Boolean, busy: Boolean, tag: String?, onLong: (() -> Unit)?) {
    val shape = RoundedCornerShape(16.dp)
    val done = item.status == SlotStatus.DONE
    val bg = when {
        done -> Sakura.AquaSoft
        now -> Sakura.Background
        else -> Sakura.MilkTeaSoft
    }
    val state = stringResource(
        when (item.status) {
            SlotStatus.DONE -> R.string.ag_status_done
            SlotStatus.OPEN -> R.string.ag_status_now
            SlotStatus.LATER -> R.string.ag_status_later
            SlotStatus.MISSED -> R.string.ag_status_missed
        },
    )
    Box(
        Modifier.width(76.dp).heightIn(min = 72.dp).background(bg, shape)
            .then(if (now) Modifier.border(1.5.dp, LocalAccent.current.fill, shape) else Modifier)
            .combinedClickable(enabled = !busy, onLongClick = onLong, onClick = item.onClick)
            .semantics { contentDescription = "${item.label}，$state" }
            .then(tag?.let { Modifier.testTag(it) } ?: Modifier)
            .then(Modifier.testTag(TodayTags.TILE + item.id).takeIf { tag == null } ?: Modifier)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LineIcon(item.icon, tint = if (done) Sakura.AquaInk else Sakura.Text)
            Text(item.label, fontSize = 13.sp, color = if (done) Sakura.AquaInk else Sakura.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (done) {
            Box(Modifier.align(Alignment.TopEnd).size(18.dp).background(Sakura.Aqua, CircleShape), contentAlignment = Alignment.Center) {
                LineIcon(Icons.Check, tint = Sakura.AquaInk, size = 12.dp)
            }
        }
    }
}

/** 今天全部的項目: every open item with its time and state (a sheet from 完成 N／M 項 ›). */
@Composable
fun TodayList(facts: IdolCardFacts, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        app.dshmobile.ui.kit.Card(Modifier.testTag(TodayTags.LIST)) {
            Text(stringResource(R.string.ag_today_list_title), style = Type.Heading)
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                facts.slots.filter { !(it.key == SlotKey.SURPRISE_CHECK && it.status == SlotStatus.LATER) }.forEach { s -> ListRow(s) }
            }
            app.dshmobile.ui.kit.SoftButton(stringResource(R.string.ag_today_list_close), onClick = onClose)
        }
    }
}

@Composable
private fun ListRow(s: SlotState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(s.slot.time, style = Type.Support, modifier = Modifier.width(48.dp))
        Text(s.slot.label, style = Type.Body, modifier = Modifier.weight(1f))
        StatusPill(
            when (s.status) {
                SlotStatus.DONE -> Status.Done
                SlotStatus.OPEN -> Status.Pink
                else -> Status.Todo
            },
            s.status.zh,
        )
    }
}
