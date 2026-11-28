package app.agency.core.stage

import app.agency.core.agent.Card
import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.day.StageDays
import app.agency.core.day.Violations
import app.agency.core.plan.Adjustment
import app.agency.core.plan.ItemStatus
import app.agency.core.plan.PlanItem
import app.agency.core.rules.Rules
import app.agency.core.store.DayClose
import app.agency.core.store.Doc
import app.agency.core.store.Kind
import app.agency.core.store.Photocard
import app.agency.core.store.RealTask
import app.agency.core.store.Role
import app.agency.core.store.Store
import app.agency.core.store.WeekResult
import app.agency.core.store.add
import app.agency.core.store.readDoc
import app.agency.core.store.writeDoc
import app.agency.core.week.BodyFlags
import app.agency.core.week.BodyTrend
import app.agency.core.week.Progression
import app.agency.core.week.Streaks
import app.agency.core.week.WeekReport
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** What one [Rollover.run] did, so the caller can tell 小葵. Everything is empty when nothing happened. */
data class RolloverResult(
    /** Days closed by this run, oldest first. */
    val closedDays: List<LocalDate> = emptyList(),
    /** Every round judged by this run, passed or not. */
    val rounds: List<StageRound> = emptyList(),
    /** Stages opened by this run (a passed round opens the next one). */
    val stagesOpened: List<Int> = emptyList(),
    /** Trainee levels reached ("B", "A"). */
    val levelUps: List<String> = emptyList(),
    val photocards: List<Photocard> = emptyList(),
    val weeks: List<WeekResult> = emptyList(),
    /** Special days celebrated ("day30", "day100", "hol:<ISO date>"). */
    val specials: List<String> = emptyList(),
    /** Training raised today, or null. */
    val adjustment: Adjustment? = null,
    val body: BodyFlags? = null,
    /** The stage-4 `talk` flag as it stands after this run. */
    val talk: Boolean = false,
) {
    /** Rounds that did not pass, each with its plain reasons in [StageRound.short]. */
    val roundsFailed: List<StageRound> get() = rounds.filter { !it.passed }

    val changed: Boolean
        get() = closedDays.isNotEmpty() || specials.isNotEmpty() || adjustment != null || body?.proposed == true
}

/**
 * Closes finished days and does everything that hangs on them: round judgement, level, photocards, the weekly result, special
 * days, training progression and the weight trend. Idempotent: the stage document remembers `closedThrough`, so a second run
 * writes nothing; after missed days it closes each of them in order, judging a round the moment its seventh day is closed.
 */
object Rollover {
    private val HOLIDAYS = mapOf(
        "01-01" to "元旦",
        "02-28" to "和平紀念日",
        "04-04" to "兒童節",
        "05-01" to "勞動節",
        "10-10" to "國慶日",
        "12-25" to "聖誕節",
    )
    private val SPECIAL_DAYS = listOf(30, 100)
    private const val SPECIAL_WINDOW = 7
    private val LEVELS = listOf("C", "B", "A")

    fun run(
        store: Store,
        clock: Clock,
        rules: Rules,
        extra: (LocalDate) -> List<PlanItem> = { emptyList() },
    ): RolloverResult = store.tx { Run(store, clock, rules, extra).go() }

    private class Run(val store: Store, val clock: Clock, val rules: Rules, val extra: (LocalDate) -> List<PlanItem>) {
        val now: Instant = clock.now()
        val today: LocalDate = Days.today(clock)
        val closed = ArrayList<LocalDate>()
        val rounds = ArrayList<StageRound>()
        val opened = ArrayList<Int>()
        val levelUps = ArrayList<String>()
        val cards = ArrayList<Photocard>()
        val weeks = ArrayList<WeekResult>()
        val specials = ArrayList<String>()
        lateinit var st: StageState

        fun note(text: String, card: Card) {
            store.addMessage(now, Role.NOTE, text, listOf(card))
        }

        fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()

        fun save() = store.writeDoc(Doc.STAGE, st)

        fun go(): RolloverResult {
            st = store.readDoc<StageState>(Doc.STAGE) ?: return RolloverResult()
            val joined = date(st.joinedOn) ?: return RolloverResult()
            var d = date(st.closedThrough)?.plusDays(1) ?: joined
            if (d.isBefore(joined)) d = joined
            while (d.isBefore(today)) {
                close(d)
                d = d.plusDays(1)
            }

            val adjustment = Progression.maybeRaise(store, clock, rules, today)
            st = store.readDoc<StageState>(Doc.STAGE) ?: st
            celebrate()
            val body = BodyTrend.check(store, today, rules, now)
            return RolloverResult(
                closedDays = closed, rounds = rounds, stagesOpened = opened, levelUps = levelUps, photocards = cards,
                weeks = weeks, specials = specials, adjustment = adjustment, body = body, talk = st.talk,
            )
        }

        /** Closes logical day [d] (steps 1 and 3, then the week and the round when [d] ends them). */
        fun close(d: LocalDate) {
            Violations.onClose(store, rules, st, d, now, clock.zone)
            val end = Days.at(d.plusDays(1), LocalTime.of(Days.START_HOUR, 0), clock.zone)
            val plan = Today.planFor(store, clock, rules, st, d, end, extra(d))
            val violations = store.entries(d, Kind.VIOLATION).size
            val counted = plan.items.filter { it.counts && it.status != ItemStatus.OFF }
            val missed = counted.filter { it.status != ItemStatus.DONE }.associate { it.id to it.title }
            val perfect = plan.total > 0 && plan.done == plan.total && violations == 0
            if (store.entries(d, Kind.DAY_CLOSE).isEmpty()) {
                store.add(d, now, Kind.DAY_CLOSE, DayClose(plan.done, plan.total, violations, perfect, missed))
            }
            st = st.copy(closedThrough = d.toString())
            save()
            closed += d

            photocards(d)
            if (d.dayOfWeek == DayOfWeek.SUNDAY) weekResult(d)
            val started = date(st.startedOn)
            if (started != null && d.toEpochDay() - started.toEpochDay() >= StageState.ROUND_DAYS - 1) judge(started, d)
        }

        fun photocards(d: LocalDate) {
            val streak = Streaks.perfect(store, d)
            for (n in listOf(7, 30)) {
                if (streak != n) continue
                val kind = n.toString()
                val have = store.entries(d, Kind.CARD).any { runCatching { it.read<Photocard>() }.getOrNull()?.kind == kind }
                if (have) continue
                val card = Photocard(kind, d.toString())
                store.add(d, now, Kind.CARD, card)
                cards += card
                note(
                    "拿到一張 $n 天小卡",
                    Card.Ceremony("photocard", "拿到 $n 天小卡", "連續 $n 天全部完成、沒有違規，這張小卡是你的。"),
                )
            }
        }

        fun weekResult(sunday: LocalDate) {
            val monday = sunday.minusDays(6)
            val written = sunday.plusDays(1)
            val have = store.entries(written, Kind.WEEK).any { runCatching { it.read<WeekResult>() }.getOrNull()?.weekStart == monday.toString() }
            if (have) return
            val r = WeekReport.build(store, monday, rules)
            store.add(written, now, Kind.WEEK, r)
            weeks += r
            val lines = ArrayList<String>()
            lines += "完成率 ${r.completionPct}%"
            lines += "違規 ${r.violations} 次"
            lines += if (r.rideTarget > 0) "跑單收入 ${r.rideIncome}／${r.rideTarget} 元，跑了 ${r.rideDays} 天" else "跑單 ${r.rideDays} 天"
            r.weightChangeKg?.let { lines += "體重比前一週${if (it > 0) "多" else "少"} ${Math.abs(it)} 公斤" }
            lines += "通過 ${r.lessonsPassed} 堂課"
            lines += "全部完成的日子 ${r.perfectDays} 天"
            note("上週的成績出來了", Card.Summary("上週成績", lines))
        }

        /** Step 2: judges the round [from]..[to], then advances, repeats and updates the level. */
        fun judge(from: LocalDate, to: LocalDate) {
            val closes = store.entries(from, to, Kind.DAY_CLOSE).mapNotNull { runCatching { it.read<DayClose>() }.getOrNull() }
            val done = closes.sumOf { it.done }
            val total = closes.sumOf { it.total }
            val violations = closes.sumOf { it.violations }
            val pct = if (total <= 0) 100 else done * 100 / total
            val need = rules.level.passPct
            val short = ArrayList<String>()
            if (total > 0 && done * 100 < need * total) short += "完成率 $pct%，要 $need%"
            if (violations > 0) short += "有 $violations 次違規"
            val realWhole = Feature.REAL_TASK.openAt(st.stage) &&
                StageDays.openedOn(st, Feature.REAL_TASK.stage)?.let { !it.isAfter(from) } == true
            if (realWhole) {
                val doneTask = store.entries(from, to, Kind.REAL_TASK).any { runCatching { it.read<RealTask>().done }.getOrNull() == true }
                if (!doneTask) short += "這一輪的真人任務還沒完成"
            }
            val passed = short.isEmpty()
            val round = StageRound(st.stage, from.toString(), to.toString(), pct, violations, passed, short)
            rounds += round

            var next = st.copy(rounds = st.rounds + round, startedOn = to.plusDays(1).toString())
            if (st.stage >= StageState.LAST_STAGE) {
                val streak = if (passed) st.passStreak + 1 else 0
                val weeksPassed = st.passedWeeks + if (passed) 1 else 0
                val reached = when {
                    rules.level.weeksToA > 0 && weeksPassed >= rules.level.weeksToA -> "A"
                    rules.level.weeksToB > 0 && weeksPassed >= rules.level.weeksToB -> "B"
                    else -> "C"
                }
                val level = if (LEVELS.indexOf(reached) > LEVELS.indexOf(st.level)) reached else st.level
                next = next.copy(
                    passStreak = streak,
                    passedWeeks = weeksPassed,
                    level = level,
                    talk = rules.level.talkAtViolations > 0 && violations >= rules.level.talkAtViolations,
                )
                if (level != st.level) {
                    levelUps += level
                    note(
                        "升到 $level 級了",
                        Card.Ceremony("level", "升到 $level 級", "累積通過 $weeksPassed 週，現在是 $level 級練習生。"),
                    )
                }
            } else if (passed) {
                val n = st.stage + 1
                next = next.copy(stage = n)
                opened += n
                val fresh = Stages.opensAt(n).joinToString("、") { it.label }
                note(
                    "第 $n 階段開始了",
                    Card.Ceremony("stage", "進入第 $n 階段", "第 ${n - 1} 階段通過了。從今天起新開放：$fresh。"),
                )
            }
            st = next
            save()
        }

        /** Step 5: day 30 and day 100 since joining, and a few public holidays, each once. */
        fun celebrate() {
            val number = StageDays.dayNumber(st, today)
            val done = st.celebrated.toMutableList()
            for (n in SPECIAL_DAYS) {
                val key = "day$n"
                if (number in n until n + SPECIAL_WINDOW && key !in done) {
                    done += key
                    specials += key
                    note("今天是加入的第 $n 天", Card.Ceremony("special", "第 $n 天", "你已經在小雨娛樂待了 $n 天，這不是每個人都做得到。"))
                }
            }
            val holiday = HOLIDAYS["%02d-%02d".format(today.monthValue, today.dayOfMonth)]
            val hkey = "hol:$today"
            if (holiday != null && hkey !in done) {
                done += hkey
                specials += hkey
                note("今天是$holiday", Card.Ceremony("special", holiday, "今天是$holiday，照常把今天的事做完，晚上對自己好一點。"))
            }
            if (specials.isNotEmpty()) {
                st = st.copy(celebrated = done)
                save()
            }
        }
    }
}
