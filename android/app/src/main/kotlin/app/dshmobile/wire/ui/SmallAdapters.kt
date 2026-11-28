package app.dshmobile.wire.ui

import android.content.Context
import app.agency.core.agent.Attachment
import app.agency.core.agent.CompanyAdmin
import app.agency.core.agent.CompanyApi
import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.plan.TaskId
import app.agency.core.rules.TrainingRule
import app.agency.core.store.Body
import app.agency.core.store.Feel
import app.agency.core.store.Kind
import app.agency.core.store.Store
import app.agency.core.week.Progression
import app.dshmobile.R
import app.dshmobile.data.Settings
import app.dshmobile.ui.BodyUi
import app.dshmobile.ui.DiaryDay
import app.dshmobile.ui.MemoRow
import app.dshmobile.ui.MemoUi
import app.dshmobile.ui.MoneyUi
import app.dshmobile.ui.PanelPrefs
import app.dshmobile.ui.Photocard
import app.dshmobile.ui.TimerMove
import app.dshmobile.ui.TimerPlan
import app.dshmobile.ui.TimerUi
import app.dshmobile.ui.TraineeCard
import app.dshmobile.ui.TraineeUi
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds the move list of the timer from the training rules in force (pure; the numbers are his rules, never invented here). */
object TimerPlans {
    private const val SECONDS_PER_REP = 3
    private const val REST = 30
    private const val DEFAULT_MINUTES = 5

    fun of(task: String, t: TrainingRule): TimerPlan {
        val id = TaskId.entries.firstOrNull { it.name == task }
        val title = id?.title ?: task
        return when (id) {
            TaskId.STRENGTH -> TimerPlan(
                task, title, t.strengthRounds.coerceAtLeast(1),
                listOfNotNull(
                    t.pushUps.takeIf { it > 0 }?.let { TimerMove("伏地挺身 $it 下", (it * SECONDS_PER_REP).coerceAtLeast(10)) },
                    t.squats.takeIf { it > 0 }?.let { TimerMove("深蹲 $it 下", (it * SECONDS_PER_REP).coerceAtLeast(10)) },
                    t.plankSeconds.takeIf { it > 0 }?.let { TimerMove("棒式", it) },
                ).ifEmpty { listOf(TimerMove(title, DEFAULT_MINUTES * 60)) },
                REST,
            )
            TaskId.CORE -> {
                val seconds = minutes(t.coreMinutes) * 60
                val moves = listOf(TimerMove("棒式", 40), TimerMove("側棒式", 40), TimerMove("仰臥抬腿", 40))
                val round = moves.sumOf { it.seconds }
                TimerPlan(task, title, ((seconds + round - 1) / round).coerceAtLeast(1), moves, 15)
            }
            else -> TimerPlan(task, title, 1, listOf(TimerMove(title, minutes(minutesOf(id, t)) * 60)), 0)
        }
    }

    private fun minutes(m: Int) = if (m > 0) m else DEFAULT_MINUTES

    private fun minutesOf(id: TaskId?, t: TrainingRule): Int = when (id) {
        TaskId.STRETCH -> t.stretchMinutes
        TaskId.MORNING_STRETCH -> t.morningStretchMinutes
        TaskId.VOCAL_BASICS -> t.vocalBasicsMinutes
        TaskId.CARDIO -> t.cardioMinutes
        TaskId.POSTURE -> t.postureMinutes
        TaskId.WALK -> t.walkMinutes
        TaskId.DANCE -> t.danceMinutes
        TaskId.VOCAL -> t.vocalMinutes
        TaskId.EXPRESSION -> t.expressionMinutes
        else -> 0
    }
}

/** The training timer: the plan comes from the rules in force (levels included), and finishing writes the task through the company. */
class TimerAdapter(private val admin: CompanyAdmin, private val store: Store) : TimerUi {
    override fun plan(task: String): TimerPlan {
        val training = Progression.effective(admin.rules(), app.agency.core.stage.Today.levels(store))
        return TimerPlans.of(task, training)
    }

    override fun finish(task: String, rounds: Int, minutes: Int, feel: String, awaySeconds: Int) {
        admin.timerDone(task, rounds, minutes, Feel.entries.firstOrNull { it.name == feel.uppercase() }, awaySeconds)
    }
}

class MemoAdapter(private val admin: CompanyAdmin, private val clock: () -> Clock) : MemoUi {
    private val fmt = DateTimeFormatter.ofPattern("M/d HH:mm")

    override fun list(): List<MemoRow> = admin.memos().map { m ->
        MemoRow(m.id, m.text, m.dueAt?.atZone(clock().zone)?.format(fmt), m.done)
    }

    /** A memo can be finished, not reopened (the company has no such step). */
    override fun setDone(id: Long, done: Boolean) {
        if (done) admin.memoDone(id)
    }

    override fun remove(id: Long) {
        admin.memoRemove(id)
    }

    override fun add(text: String, dueAt: String?) {
        admin.memoAdd(text, dueAt)
    }
}

class BodyAdapter(
    private val context: Context,
    private val company: CompanyApi,
    private val admin: CompanyAdmin,
    private val store: Store,
    private val clock: () -> Clock,
) : BodyUi {
    override fun last(): List<String> {
        val day = Days.today(clock())
        val bodies = store.entries(day.minusDays(120), day, Kind.BODY).mapNotNull { runCatching { it.read<Body>() }.getOrNull() }
        val kg = bodies.mapNotNull { it.kg }.lastOrNull()
        val waist = bodies.mapNotNull { it.waistCm }.lastOrNull()
        return listOfNotNull(
            kg?.let { context.getString(R.string.body_last_kg, num(it)) },
            waist?.let { context.getString(R.string.body_last_waist, num(it)) },
        )
    }

    override suspend fun log(kg: Double?, waistCm: Double?, photo: Attachment.Photo?): String = withContext(Dispatchers.IO) {
        val lines = ArrayList<String>()
        if (kg != null || waistCm != null) {
            lines += company.direct("log_body", buildJsonObject { put("kg", kg); put("waist_cm", waistCm) })
        }
        if (photo != null) lines += admin.bodyPhoto(photo.photoId)
        lines.ifEmpty { listOf(context.getString(R.string.body_nothing)) }.joinToString("\n")
    }

    private fun num(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}

class MoneyAdapter(private val context: Context, private val company: CompanyApi, private val admin: CompanyAdmin) : MoneyUi {
    override fun summary(): List<String> {
        val v = admin.moneyView()
        return listOf(
            context.getString(R.string.money_week, v.week.living, v.week.fun_, v.week.income),
            context.getString(R.string.money_month, v.month.living, v.month.fun_, v.month.income),
        )
    }

    override fun add(amount: Int, bucket: String, note: String): String =
        company.direct("log_money", buildJsonObject { put("amount", amount); put("bucket", bucket.lowercase()); put("note", note) })
}

class TraineeAdapter(private val admin: CompanyAdmin, private val clock: () -> Clock) : TraineeUi {
    override fun card(): TraineeCard {
        val v = admin.traineeCard()
        val c = clock()
        val today = Days.today(c)
        val perfect = v.perfectDays.toSet()
        val days = minOf(v.dayNumber.coerceAtLeast(1), DIARY_DAYS)
        return TraineeCard(
            stageName = v.stageName,
            colorHex = v.memberColor,
            level = v.level,
            stageLabel = v.stageLabel,
            daysSinceJoin = v.dayNumber,
            photocards = v.photocards.map { Photocard("連續 ${it.kind} 天滿分", shortDate(it.earnedOn), null) }.reversed(),
            diary = (days - 1 downTo 0).map { back ->
                val d = today.minusDays(back.toLong())
                DiaryDay("${d.monthValue}/${d.dayOfMonth}", d in perfect)
            },
        )
    }

    private fun shortDate(iso: String): String = runCatching { java.time.LocalDate.parse(iso).let { "${it.monthValue}/${it.dayOfMonth}" } }.getOrDefault(iso)

    private companion object {
        const val DIARY_DAYS = 28
    }
}

/** Which of the three home panels are open, kept in the settings document (so a backup carries it). */
class SettingsPanelPrefs(private val settings: Settings) : PanelPrefs {
    override fun get(name: String, default: Boolean): Boolean = runCatching { settings.get().folded[name]?.not() }.getOrNull() ?: default

    override fun set(name: String, open: Boolean) {
        runCatching { settings.setFolded(name, !open) }
    }
}
