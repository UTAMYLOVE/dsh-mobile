package app.agency.core.extras

import app.agency.core.agent.Card
import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.plan.TaskId
import app.agency.core.rules.Rules
import app.agency.core.stage.Feature
import app.agency.core.stage.StageState
import app.agency.core.store.Body
import app.agency.core.store.Entry
import app.agency.core.store.Kind
import app.agency.core.store.Store
import app.agency.core.store.TaskDone
import app.agency.core.store.add
import app.agency.core.store.readDoc
import app.agency.core.store.writeDoc
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

enum class EvalType { WEEKLY, MONTHLY }

/** The five things scored 1..5. */
@Serializable
enum class EvalDim(val zh: String) {
    VOCAL("聲樂"),
    DANCE("舞蹈"),
    VISUAL("外貌與鏡頭"),
    STAR("明星特質"),
    ATTITUDE("態度"),
}

/** Who made a score: [CODE] computed it from records; [SUGGESTED] is a model's suggestion, shown as one. */
enum class ScoreSource { CODE, SUGGESTED }

@Serializable
data class EvalScore(val dim: EvalDim, val value: Int, val source: ScoreSource, val reason: String)

/** One thing handed in. [ref] is a [Kind.SONG] entry id or a media-vault id (the recording itself is never copied). */
@Serializable
data class EvalItem(val name: String, val ref: String)

/** What the monthly evaluation measured on his body. */
@Serializable
data class EvalBody(
    val photoId: String? = null,
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    val pushUps: Int? = null,
    val plankSeconds: Int? = null,
)

/**
 * A [Kind.EVAL] payload. [period] is the ISO date of the Monday of the week (WEEKLY) or "yyyy-MM" (MONTHLY). [scores] holds
 * the vocal and attitude scores (CODE) and, when a model's suggestion was usable, the other three (SUGGESTED).
 */
@Serializable
data class EvalRec(
    val type: EvalType,
    val period: String,
    val items: List<EvalItem>,
    val scores: List<EvalScore>,
    val notes: String = "",
    val body: EvalBody? = null,
) {
    fun score(dim: EvalDim): Int? = scores.firstOrNull { it.dim == dim }?.value
}

/** The month's hand-in. */
data class MonthlyInput(
    val songEntryId: Long,
    val danceRecordingId: String,
    /** A vault id (or a short note) for the rotating item, see [Evaluations.rotatingItem]. */
    val rotatingRef: String,
    val bodyPhotoId: String,
    val weightKg: Double,
    val waistCm: Double,
    val pushUps: Int,
    val plankSeconds: Int,
    val selfReport: String = "",
)

sealed interface EvalResult {
    data class Ok(val entryId: Long, val rec: EvalRec, val card: Card.Summary) : EvalResult

    /** Refused; [reason] is in his language. */
    data class Refused(val reason: String) : EvalResult
}

/** This month against last month. Deltas are this − last; only what both months have. */
data class MonthCompare(
    val scoreDeltas: Map<EvalDim, Int>,
    val weightKg: Double?,
    val waistCm: Double?,
    val pushUps: Int?,
    val plankSeconds: Int?,
    val progressed: Boolean,
) {
    fun lines(): List<String> = buildList {
        scoreDeltas.forEach { (d, x) -> add("${d.zh}：${if (x > 0) "進步 $x 分" else if (x < 0) "退步 ${-x} 分" else "持平"}") }
        weightKg?.let { add("體重：${if (it > 0) "多" else "少"} ${fmt(kotlin.math.abs(it))} 公斤") }
        waistCm?.let { add("腰圍：${if (it > 0) "多" else "少"} ${fmt(kotlin.math.abs(it))} 公分") }
        pushUps?.let { add("伏地挺身：${if (it >= 0) "多" else "少"} ${kotlin.math.abs(it)} 下") }
        plankSeconds?.let { add("棒式：${if (it >= 0) "多" else "少"} ${kotlin.math.abs(it)} 秒") }
    }

    private fun fmt(x: Double): String = ((x * 10).roundToInt() / 10.0).toString()
}

/** The one weakness to practise next month, [minutes] a day. */
@Serializable
data class PersonalTask(val month: String, val dim: EvalDim, val text: String, val minutes: Int = 10)

@Serializable
private data class PersonalTasks(val byMonth: Map<String, PersonalTask> = emptyMap())

/**
 * Weekly evaluation (Sundays) and monthly evaluation (last Sunday of the month). Vocal comes from [SongBook.vocalScore]; attitude
 * is computed from completion and violations; the other three are a model's suggestion (see [EvalSuggester]) and are labelled so.
 * A weekly evaluation is handed in once: a second try is refused. [stage] parameters are optional: when given, a closed feature
 * refuses.
 */
class Evaluations(
    private val store: Store,
    private val clock: Clock,
    private val rules: Rules,
    private val suggester: EvalSuggester? = null,
) {
    private val songs = SongBook(store, clock)

    // ---- when ----

    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun isLastSunday(day: LocalDate): Boolean = day.dayOfWeek == DayOfWeek.SUNDAY && day.plusDays(7).month != day.month

    private fun open(f: Feature, stage: StageState?) = stage == null || f.openAt(stage.stage)

    /** Weekly evaluation can be handed in on [day]: a Sunday, the feature open, not handed in yet. */
    fun weeklyDue(day: LocalDate, stage: StageState? = null): Boolean =
        day.dayOfWeek == DayOfWeek.SUNDAY && open(Feature.WEEKLY_EVAL, stage) && weekly(day) == null

    fun monthlyDue(day: LocalDate, stage: StageState? = null): Boolean =
        isLastSunday(day) && open(Feature.MONTHLY_EVAL, stage) && monthly(YearMonth.from(day)) == null

    // ---- records ----

    private fun all(type: EvalType): List<Pair<Entry, EvalRec>> =
        store.entries(LocalDate.of(2000, 1, 1), LocalDate.of(2200, 1, 1), Kind.EVAL)
            .mapNotNull { e -> runCatching { e.read<EvalRec>() }.getOrNull()?.takeIf { it.type == type }?.let { e to it } }

    fun weekly(day: LocalDate): EvalRec? = all(EvalType.WEEKLY).lastOrNull { it.second.period == weekStart(day).toString() }?.second

    fun monthly(month: YearMonth): EvalRec? = all(EvalType.MONTHLY).lastOrNull { it.second.period == month.toString() }?.second

    /** Every monthly evaluation, oldest first. */
    fun monthlyEvals(): List<EvalRec> = all(EvalType.MONTHLY).map { it.second }.sortedBy { it.period }

    // ---- facts ----

    /** What code counted from [from]..[to] (both included, capped at today). */
    fun facts(from: LocalDate, to: LocalDate): PeriodFacts {
        val today = Days.today(clock)
        val end = if (to > today) today else to
        val es = store.entries(from, end)
        val days = (java.time.temporal.ChronoUnit.DAYS.between(from, end).toInt() + 1).coerceAtLeast(1)
        val inDays = es.filter { it.kind == Kind.CHECK_IN }.map { it.day }.toSet()
        val outDays = es.filter { it.kind == Kind.CHECK_OUT }.map { it.day }.toSet()
        val tasks = es.filter { it.kind == Kind.TASK }.mapNotNull { runCatching { it.read<TaskDone>() }.getOrNull() }
        val practice = setOf(TaskId.DANCE, TaskId.VOCAL, TaskId.EXPRESSION, TaskId.VOCAL_BASICS, TaskId.PERSONAL_TASK).map { it.name }
        return PeriodFacts(
            days = days,
            daysCheckedIn = inDays.size,
            completionPct = (inDays intersect outDays).size * 100 / days,
            violations = es.count { it.kind == Kind.VIOLATION },
            practiceMinutes = tasks.filter { it.task in practice }.sumOf { it.minutes ?: 0 },
            tasksDone = tasks.size,
        )
    }

    /** Attitude is code's: completion first, two violations cost a point, four cost two. */
    fun attitude(facts: PeriodFacts): EvalScore {
        val base = when {
            facts.completionPct >= 95 -> 5
            facts.completionPct >= 85 -> 4
            facts.completionPct >= 70 -> 3
            facts.completionPct >= 50 -> 2
            else -> 1
        }
        val penalty = minOf(2, facts.violations / 2)
        return EvalScore(
            EvalDim.ATTITUDE, (base - penalty).coerceAtLeast(1), ScoreSource.CODE,
            "簽到又簽退 ${facts.completionPct}%，違規 ${facts.violations} 次",
        )
    }

    // ---- weekly ----

    suspend fun submitWeekly(
        songEntryId: Long,
        danceRecordingId: String,
        selfReport: String = "",
        stage: StageState? = null,
    ): EvalResult {
        val today = Days.today(clock)
        if (!open(Feature.WEEKLY_EVAL, stage)) return EvalResult.Refused("每週評鑑還沒開放")
        if (today.dayOfWeek != DayOfWeek.SUNDAY) return EvalResult.Refused("每週評鑑只在星期日")
        if (weekly(today) != null) return EvalResult.Refused("這週的評鑑已經交過了，每週只有一次機會，不能重來")
        val song = songs.song(songEntryId) ?: return EvalResult.Refused("找不到這首歌的紀錄，請先錄一首歌")
        if (danceRecordingId.isBlank()) return EvalResult.Refused("還沒有這週的舞蹈錄影")
        val start = weekStart(today)
        val facts = facts(start, today)
        val suggested = suggester?.suggest(facts, selfReport)
        // The model call is a suspension point: look again so two quick taps cannot both be kept.
        if (weekly(today) != null) return EvalResult.Refused("這週的評鑑已經交過了，每週只有一次機會，不能重來")
        val scores = scoresOf(song, facts, suggested)
        val rec = EvalRec(
            EvalType.WEEKLY, start.toString(),
            listOf(EvalItem("唱歌", songEntryId.toString()), EvalItem("舞蹈", danceRecordingId)),
            scores, selfReport.trim().take(300),
        )
        val e = store.add(today, clock.now(), Kind.EVAL, rec)
        return EvalResult.Ok(e.id, rec, card("每週評鑑", rec, suggested != null))
    }

    private fun scoresOf(song: SongRec, facts: PeriodFacts, suggested: List<EvalScore>?): List<EvalScore> {
        val vocal = EvalScore(
            EvalDim.VOCAL, songs.vocalScore(song), ScoreSource.CODE,
            "長音有 ${song.inTunePct}% 落在正確音高內" + (song.compare?.let { "，比對音準 ${it.pitchPct}%" } ?: ""),
        )
        val all = listOf(vocal, attitude(facts)) + suggested.orEmpty()
        return EvalDim.values().mapNotNull { d -> all.firstOrNull { it.dim == d } }
    }

    private fun card(title: String, rec: EvalRec, hadSuggestion: Boolean): Card.Summary {
        val lines = ArrayList<String>()
        rec.scores.forEach {
            val tag = if (it.source == ScoreSource.CODE) "程式算的" else "建議分數"
            lines += "${it.dim.zh}：${it.value} 分（$tag）${it.reason}"
        }
        if (!hadSuggestion) lines += "舞蹈、外貌與鏡頭、明星特質這次沒有建議分數"
        rec.body?.let { b ->
            b.weightKg?.let { lines += "體重：$it 公斤" }
            b.waistCm?.let { lines += "腰圍：$it 公分" }
            b.pushUps?.let { lines += "伏地挺身一口氣：$it 下" + if (rules.training.pushUps > 0) "（課表 ${rules.training.pushUps} 下）" else "" }
            b.plankSeconds?.let { lines += "棒式：$it 秒" + if (rules.training.plankSeconds > 0) "（課表 ${rules.training.plankSeconds} 秒）" else "" }
        }
        return Card.Summary(title, lines)
    }

    // ---- monthly ----

    /** The rotating third item of [month]: one of a built-in list, never random. */
    fun rotatingItem(month: YearMonth): String = ROTATING[Math.floorMod(month.year * 12 + month.monthValue, ROTATING.size)]

    suspend fun submitMonthly(input: MonthlyInput, stage: StageState? = null): EvalResult {
        val today = Days.today(clock)
        val month = YearMonth.from(today)
        if (!open(Feature.MONTHLY_EVAL, stage)) return EvalResult.Refused("月評還沒開放")
        if (!isLastSunday(today)) return EvalResult.Refused("月評只在每個月的最後一個星期日")
        if (monthly(month) != null) return EvalResult.Refused("這個月的月評已經交過了")
        val song = songs.song(input.songEntryId) ?: return EvalResult.Refused("找不到這首歌的紀錄，請先錄一首歌")
        if (input.danceRecordingId.isBlank()) return EvalResult.Refused("還沒有舞蹈錄影")
        if (input.rotatingRef.isBlank()) return EvalResult.Refused("還沒有「${rotatingItem(month)}」的紀錄")
        if (input.bodyPhotoId.isBlank()) return EvalResult.Refused("還沒有全身對比照")
        if (input.weightKg <= 0 || input.waistCm <= 0) return EvalResult.Refused("體重和腰圍都要量")
        if (input.pushUps < 0 || input.plankSeconds < 0) return EvalResult.Refused("體能測驗的數字不對")
        val start = month.atDay(1)
        val facts = facts(start, today)
        val suggested = suggester?.suggest(facts, input.selfReport)
        if (monthly(month) != null) return EvalResult.Refused("這個月的月評已經交過了")
        val rec = EvalRec(
            EvalType.MONTHLY, month.toString(),
            listOf(
                EvalItem("單曲", input.songEntryId.toString()),
                EvalItem("舞蹈", input.danceRecordingId),
                EvalItem(rotatingItem(month), input.rotatingRef),
            ),
            scoresOf(song, facts, suggested), input.selfReport.trim().take(300),
            EvalBody(input.bodyPhotoId, input.weightKg, input.waistCm, input.pushUps, input.plankSeconds),
        )
        val now = clock.now()
        val e = store.tx {
            // The body numbers also go into the ordinary body record, so the weight history and the photo timeline see them.
            store.add(today, now, Kind.BODY, Body(input.weightKg, input.waistCm, input.bodyPhotoId))
            store.add(today, now, Kind.EVAL, rec)
        }
        val lines = card("月評", rec, suggested != null).lines.toMutableList()
        monthlyEvals().filter { it.period < rec.period }.lastOrNull()?.let { last ->
            lines += "和上個月比："
            lines += compare(rec, last).lines()
        }
        if (stalled(2)) lines += "已經連續幾個月沒有進步，找個時間和小葵好好談一談"
        personalTask(month.plusMonths(1))?.let { lines += "下個月的個人任務：${it.text}" }
        return EvalResult.Ok(e.id, rec, Card.Summary("月評", lines))
    }

    fun compare(thisMonth: EvalRec, lastMonth: EvalRec): MonthCompare {
        val deltas = LinkedHashMap<EvalDim, Int>()
        EvalDim.values().forEach { d ->
            val a = thisMonth.score(d)
            val b = lastMonth.score(d)
            if (a != null && b != null) deltas[d] = a - b
        }
        fun dd(a: Double?, b: Double?) = if (a != null && b != null) ((a - b) * 10).roundToInt() / 10.0 else null
        fun di(a: Int?, b: Int?) = if (a != null && b != null) a - b else null
        val tb = thisMonth.body
        val lb = lastMonth.body
        val pushUps = di(tb?.pushUps, lb?.pushUps)
        val plank = di(tb?.plankSeconds, lb?.plankSeconds)
        return MonthCompare(
            deltas, dd(tb?.weightKg, lb?.weightKg), dd(tb?.waistCm, lb?.waistCm), pushUps, plank,
            progressed = deltas.values.sum() > 0 || (pushUps ?: 0) > 0 || (plank ?: 0) > 0,
        )
    }

    /**
     * True when the last [months] (2..3) month-to-month steps all show no progress: no higher total score on the same
     * dimensions and no better fitness test. Needs `months + 1` monthly evaluations. A talk with him is due.
     */
    fun stalled(months: Int = 2): Boolean {
        val n = months.coerceIn(2, 3)
        val list = monthlyEvals()
        if (list.size < n + 1) return false
        val tail = list.takeLast(n + 1)
        return (1..n).all { !compare(tail[it], tail[it - 1]).progressed }
    }

    // ---- the personal task ----

    private fun tasks(): PersonalTasks = store.readDoc<PersonalTasks>(TASKS_DOC) ?: PersonalTasks()

    /** What is stored for [month], without making one. */
    fun peekPersonalTask(month: YearMonth): PersonalTask? = tasks().byMonth[month.toString()]

    /**
     * The task for [month]: the weakest score of the evaluation of the month before (a tie goes to the first of the five),
     * ten minutes a day. Made once and stored; null when there was no monthly evaluation before.
     */
    fun personalTask(month: YearMonth): PersonalTask? {
        peekPersonalTask(month)?.let { return it }
        val prev = monthly(month.minusMonths(1)) ?: return null
        val weakest = prev.scores.minByOrNull { it.value } ?: return null
        val task = PersonalTask(month.toString(), weakest.dim, "每天練 10 分鐘「${weakest.dim.zh}」，把它補起來")
        store.writeDoc(TASKS_DOC, PersonalTasks(tasks().byMonth + (task.month to task)))
        return task
    }

    companion object {
        const val TASKS_DOC = "personal_tasks"

        val ROTATING: List<String> = listOf(
            "表情鏡頭一分鐘", "英文自我介紹", "站姿與走姿", "禮儀問候", "即興聊天一分鐘", "舞台走位",
        )
    }
}
