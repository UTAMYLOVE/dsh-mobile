package app.agency.core.extras

import app.agency.core.base.Clock
import app.agency.core.day.DayState
import app.agency.core.plan.ItemStatus
import app.agency.core.plan.PlanItem
import app.agency.core.plan.Screen
import app.agency.core.plan.TaskId
import app.agency.core.plan.Tap
import app.agency.core.remind.Notice
import app.agency.core.rules.Rules
import app.agency.core.stage.Feature
import app.agency.core.stage.StageState
import app.agency.core.store.Store
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The stage-4 plan items and reminder notices, ready for the day planner's `extra` and the reminders' `extra`. Each item carries
 * its own [Feature], so the planner can filter by stage; nothing is returned for a feature that is not open. Reading items never
 * writes anything.
 *
 * Whether an item counts: the evaluations, the Sunday photo, the theme practice and the personal task do; surprise checks do not
 * (a missed one is only recorded) and the real-person task does not either (the weekly stage judgement asks for it separately).
 */
object Extras {

    /** The first day of the round [day] belongs to (the real-person task is judged per round). */
    private fun roundStart(st: StageState, day: LocalDate): LocalDate =
        runCatching { LocalDate.parse(st.startedOn) }.getOrNull()?.takeIf { !it.isAfter(day) } ?: day

    private class At(private val at: Instant, override val zone: ZoneId) : Clock {
        override fun now(): Instant = at
    }

    fun items(
        day: LocalDate,
        now: Instant,
        store: Store,
        rules: Rules,
        stageState: StageState,
        state: DayState,
    ): List<PlanItem> {
        val stage = stageState.stage
        val clock = At(now, state.zone)
        val out = ArrayList<PlanItem>()
        val evals = Evaluations(store, clock, rules)

        if (Feature.WEEKLY_EVAL.openAt(stage) && day.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
            val done = evals.weekly(day) != null
            out += PlanItem(
                id = "eval", feature = Feature.WEEKLY_EVAL, title = "每週評鑑", at = null, until = null,
                status = if (done) ItemStatus.DONE else ItemStatus.PENDING, counts = true, tap = Tap.Open(Screen.EVAL),
                detail = "一首歌加一支舞，只有一次機會", chase = false,
            )
        }
        if (Feature.MONTHLY_EVAL.openAt(stage) && evals.isLastSunday(day)) {
            val done = evals.monthly(YearMonth.from(day)) != null
            out += PlanItem(
                id = "eval_monthly", feature = Feature.MONTHLY_EVAL, title = "月評", at = null, until = null,
                status = if (done) ItemStatus.DONE else ItemStatus.PENDING, counts = true,
                tap = Tap.Open(Screen.EVAL, "monthly"), detail = "三個項目、全身照、體重腰圍、體能測驗", chase = false,
            )
        }
        if (Feature.MONTHLY_EVAL.openAt(stage)) {
            evals.peekPersonalTask(YearMonth.from(day))?.let { t ->
                out += PlanItem(
                    id = "task:${TaskId.PERSONAL_TASK.name}", feature = Feature.MONTHLY_EVAL,
                    title = "個人任務：${t.dim.zh}", at = null, until = null,
                    status = when {
                        state.tasks.containsKey(TaskId.PERSONAL_TASK) -> ItemStatus.DONE
                        state.unwell -> ItemStatus.OFF
                        else -> ItemStatus.PENDING
                    },
                    counts = true, tap = Tap.Open(Screen.TIMER, TaskId.PERSONAL_TASK.name), detail = "${t.minutes} 分鐘",
                )
            }
        }
        if (Feature.SPOT_CHECK.openAt(stage)) {
            val spot = SpotChecks(store, clock, rules).todays(day).lastOrNull()?.second
            if (spot != null) {
                val asked = runCatching { Instant.parse(spot.askedAt) }.getOrNull()
                val late = asked == null || now > asked.plus(SpotChecks.ANSWER_WITHIN)
                val status = when (spot.status) {
                    SpotStatus.DONE -> ItemStatus.DONE
                    SpotStatus.MISSED -> ItemStatus.MISSED
                    SpotStatus.ASKED -> if (late) ItemStatus.MISSED else ItemStatus.PENDING
                }
                out += PlanItem(
                    id = "spot", feature = Feature.SPOT_CHECK, title = "突擊檢查：${spot.ask.zh}照片",
                    at = asked?.atZone(state.zone)?.toLocalTime()?.withSecond(0)?.withNano(0), until = null,
                    status = status, counts = false, tap = Tap.Open(Screen.CAMERA, "spot"), detail = "十分鐘內", chase = false,
                )
            }
        }
        if (Feature.REAL_TASK.openAt(stage)) {
            val real = RealTasks(store, clock)
            out += PlanItem(
                id = "real_task", feature = Feature.REAL_TASK, title = "真人任務", at = null, until = null,
                status = if (real.doneWithin(roundStart(stageState, day), day)) ItemStatus.DONE else ItemStatus.PENDING,
                counts = false,
                tap = Tap.Say("這週的真人任務是什麼？"), detail = "每週一次", chase = false,
            )
        }
        if (Feature.BODY_PHOTO.openAt(stage) && day.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
            val photos = BodyPhotos(store, clock)
            out += PlanItem(
                id = "body_photo", feature = Feature.BODY_PHOTO, title = "全身對比照", at = null, until = null,
                status = if (photos.doneOn(day)) ItemStatus.DONE else ItemStatus.PENDING, counts = true,
                tap = Tap.Open(Screen.BODY, "photo"), detail = "同一個角度", chase = false,
            )
        }
        if (Feature.THEME_WEEK.openAt(stage)) {
            ThemeWeeks.current(day, stageState)?.let { theme ->
                out += PlanItem(
                    id = "theme", feature = Feature.THEME_WEEK, title = "${theme.name}：${theme.practice}",
                    at = null, until = null,
                    status = when {
                        ThemeWeeks.doneOn(store, day) -> ItemStatus.DONE
                        state.unwell -> ItemStatus.OFF
                        else -> ItemStatus.PENDING
                    },
                    counts = true, tap = Tap.Say("我要做這週主題的小練習"), detail = "${theme.minutes} 分鐘",
                )
            }
        }
        return out
    }

    /**
     * Notices decided by this package, for the reminders' `extra`: the spot check that is due now (loud). Asking records the
     * check as asked, so a second look at the same moment returns nothing.
     */
    fun notices(now: Instant, store: Store, rules: Rules, stageState: StageState, state: DayState): List<Notice> {
        if (!Feature.SPOT_CHECK.openAt(stageState.stage)) return emptyList()
        val clock = At(now, state.zone)
        return listOfNotNull(SpotChecks(store, clock, rules).issue(now, state))
    }
}
