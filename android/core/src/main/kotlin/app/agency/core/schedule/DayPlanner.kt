package app.agency.core.schedule

import app.agency.core.model.DayOverride
import java.time.DayOfWeek
import java.time.LocalDate

/** Why today's training is lighter: only illness or injury, after his yes to the manager's proposal (agency-links §3b). */
enum class Reduction(val zh: String) { SICK("生病"), INJURY("受傷") }

/**
 * Today's plan. [type] is the day's kind; [rest] says 「今天休息」 was declared: only no delivery (owner 2026-11-26); classes,
 * the core, the rounds, the stretch and the 理論課 stay, in the no-run block ([TrainingSpec.noRideTrainingAt]); 1700 unchanged.
 * [cheatSlot] is the declared 休假餐; [soyMorning] the 「早上很餓」 swap. [reduction] is the accepted
 * illness/injury change of today (no core and no strength; stretch stays, avoiding an injured part).
 */
data class DayPlan(
    val day: LocalDate,
    val type: DayType,
    val rest: Boolean,
    val slots: List<Slot>,
    val cheatSlot: SlotKey? = null,
    val soyMorning: Boolean = false,
    val reduction: Reduction? = null,
    /** Today's smaller meals (L5): slot → at most this many kcal. */
    val budgets: Map<SlotKey, Int> = emptyMap(),
    /** The strength progression step (T18) and the rounds it means. */
    val progressionStep: Int = 0,
    val strengthRounds: Int = 3,
    val stretchMin: Int = 10,
    val walkMin: Int = 30,
    val cardioMin: Int = 10,
    /** The core and the cardio ladders' steps (訓練升級). */
    val coreStep: Int = 0,
    val cardioStep: Int = 0,
) {
    fun slot(key: SlotKey): Slot? = slots.firstOrNull { it.key == key }

    /** The first meal of the day: 午餐 on a class day, 第一餐 otherwise (the 早上豆漿 is not a meal slot to move). */
    val firstMeal: SlotKey get() = if (type == DayType.CLASS) SlotKey.LUNCH else SlotKey.FIRST_MEAL
    val delivery: Boolean get() = slot(SlotKey.RIDE_OUT) != null
}

/**
 * Builds a day's plan from the stored sections (arch 1.5, plan v3 §4): pure. Class day: 08:30 起床簽到, 12:10 午餐 (蛋白質至少
 * 45 克), 17:35 晚餐, 18:00–19:00 出門, 21:00 雞胸加豆漿 on the run (mandatory), after 收工 the training block: 核心, 肌力三輪
 * (latest start 01:50), 體能 on its days, 伸展; latest sign-out 02:30. No-class day: wake by 12:00, 13:00 第一餐, 16:20 快走 30
 * 分鐘, 17:35 晚餐, delivery, 21:00, the block after 收工, sign-out by 04:00. A rest day keeps those times without the delivery; its core,
 * rounds and stretch are owed as on any day, due from [TrainingSpec.noRideTrainingAt] (not after a ride: there is none).
 */
object DayPlanner {
    /** On a day without a run: the rounds this long after the core, the stretch this long after the core. */
    const val NO_RIDE_STRENGTH_AFTER_MIN = 10
    const val NO_RIDE_STRETCH_AFTER_MIN = 35

    const val CORE_NOTE = "半船式、半仰臥起坐、抱膝、單車捲腹、進階抬腿、側棒式左右"
    const val CARDIO_NOTE = "開合跳、波比跳、深蹲跳"

    /**
     * The latest minute of each item of the training block: on a delivery day counted back from the rounds' latest start
     * ([strengthLatest]; the cardio days start earlier so the stretch still ends before bed), on a day without a run from
     * [noRide] on.
     */
    fun block(rest: Boolean, noRide: Int, strengthLatest: Int, cardio: Boolean): Map<SlotKey, Int> = when {
        rest -> mapOf(
            SlotKey.CORE to noRide, SlotKey.STRENGTH to noRide + NO_RIDE_STRENGTH_AFTER_MIN, SlotKey.CARDIO to noRide + NO_RIDE_STRETCH_AFTER_MIN,
            SlotKey.STRETCH to noRide + NO_RIDE_STRETCH_AFTER_MIN + if (cardio) 15 else 0,
        )
        cardio -> mapOf(SlotKey.CORE to strengthLatest - 25, SlotKey.STRENGTH to strengthLatest - 15, SlotKey.CARDIO to strengthLatest + 5, SlotKey.STRETCH to strengthLatest + 20)
        else -> mapOf(SlotKey.CORE to strengthLatest - 10, SlotKey.STRENGTH to strengthLatest, SlotKey.STRETCH to strengthLatest + 20)
    }

    /**
     * How many minutes earlier bed is on [day] (睡覺時間往前調): [ScheduleSpec.sleepStepMin] for each week from the day its
     * rollout week opened (that week included); 0 before it opens or when the day is not known.
     */
    fun bedtimeShift(day: LocalDate, specs: PlanSpecs): Int {
        if (!specs.open(Feature.EARLIER_BEDTIME)) return 0
        val from = specs.rollout.openedDay(specs.rollout.weekOf(Feature.EARLIER_BEDTIME)) ?: return 0
        if (day.isBefore(from)) return 0
        val weeks = java.time.temporal.ChronoUnit.DAYS.between(from, day) / 7 + 1
        return (weeks * specs.schedule.sleepStepMin).toInt()
    }

    /** The 21:00 portion and its swaps (owner 2026-11-26), one 雞胸 a day at most. */
    const val RIDE_MEAL_NOTE = "雞胸加豆漿，一定要吃；可以換成福樂蛋白飲加茶葉蛋、義美高蛋白奶加茶葉蛋或低脂鮮奶（雞胸一天最多一包）"

    /**
     * The practice items of rollout weeks 3 and 4 ([app.agency.core.engine.Practice]): 起床伸展 and 聲樂基本功 after the wake,
     * 靠牆站姿, 防曬 before the run, on no-class days 舞蹈 and 聲樂 before the first meal (表情鏡頭 on its two days), Sunday's
     * 全身對比照 and 每週評鑑 (the last Sunday of a month: 月評), Saturday's 主題週 practical, the week's 突擊檢查 (ten minutes),
     * the month's 個人任務 and the 學了就用 habits. Features not open yet are filtered out by the caller like any slot.
     */
    fun practice(day: LocalDate, specs: PlanSpecs, wake: Int, firstMeal: Int, sleep: Int, cls: Boolean, noRun: Boolean, reduced: Boolean): List<Slot> = buildList {
        val p = specs.practice
        val dow = day.dayOfWeek.value
        add(Slot(SlotKey.MORNING_STRETCH, wake + p.morningStretchAfterWakeMin, note = "${p.morningStretchMin} 分鐘"))
        add(Slot(SlotKey.VOCAL_BASICS, wake + p.vocalBasicsAfterWakeMin, note = "${p.vocalBasicsMin} 分鐘：彈唇、滑音、音階、咬字"))
        add(Slot(SlotKey.POSTURE, DayClock.minute(p.posture), note = "${p.postureMin} 分鐘"))
        if (!noRun) add(Slot(SlotKey.SUNSCREEN, DayClock.minute(specs.schedule.rideOut), untilMinute = DayClock.minute(specs.schedule.rideOutUntil), note = "出門前擦好"))
        if (!cls) {
            // An illness change takes the dance off with the rest of the training (only illness or injury lightens it).
            if (!reduced) add(Slot(SlotKey.DANCE, firstMeal, note = "${p.danceMin} 分鐘，第一餐前"))
            add(Slot(SlotKey.VOCAL_PRACTICE, firstMeal, note = "${p.vocalPracticeMin} 分鐘，第一餐前"))
            if (dow in p.expressionDays) add(Slot(SlotKey.EXPRESSION, firstMeal, note = "${p.expressionMin} 分鐘"))
        }
        if (dow == p.evalDay) {
            val at = DayClock.minute(p.evalAt)
            add(Slot(SlotKey.BODY_PHOTO, at, note = "同一個位置、同一個角度"))
            if (app.agency.core.engine.Practice.monthEnd(day)) {
                add(Slot(SlotKey.MONTHLY_EVAL, at, note = "獨唱一首、舞一支、輪替一項、全身照、體重腰圍、體能測試"))
            } else {
                add(Slot(SlotKey.WEEKLY_EVAL, at, note = "這週的歌和舞各一次，不能重來"))
            }
        }
        if (dow == p.themeDay) app.agency.core.engine.Themes.of(day).let { add(Slot(SlotKey.THEME_TASK, DayClock.minute(p.themeAt), note = "${it.name}：${it.task}")) }
        app.agency.core.engine.SurpriseChecks.on(day, specs.schedule)?.let {
            add(Slot(SlotKey.SURPRISE_CHECK, it.minute, closesMinute = it.minute + p.checkWindowMin, note = "${p.checkWindowMin} 分鐘內拍：${it.what}"))
        }
        val taskFrom = p.personalTaskFrom?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (p.personalTask != null && taskFrom != null && !day.isBefore(taskFrom)) {
            add(Slot(SlotKey.PERSONAL_TASK, sleep - 40, note = "${p.personalTaskMin} 分鐘：${app.agency.core.engine.MonthlyEval.taskText(p.personalTask)}"))
        }
        val habits = p.habits.filter { !LocalDate.parse(it.addedOn).isAfter(day) }
        if (habits.isNotEmpty()) {
            add(Slot(SlotKey.HABIT, habits.maxOf { DayClock.minute(it.at) }, note = habits.joinToString("；") { it.text }, count = habits.size))
        }
    }

    fun build(
        day: LocalDate,
        specs: PlanSpecs = PlanSpecs(),
        override: DayOverride? = null,
        adjust: List<DayAdjust> = emptyList(),
        reduction: Reduction? = null,
        lessons: LessonDay = LessonDay(),
    ): DayPlan {
        val s = specs.schedule
        val m = specs.meals
        val t = specs.training
        val type = Timetable.type(day, s)
        val rest = override?.restDay == true
        // 分週上線 (owner 2026-11-27): a day without the run open is planned like a day without a run.
        val noRun = rest || !specs.open(Feature.RIDE)
        val soy = override?.soyMorning == true || adjust.any { it is DayAdjust.SoyMorning }
        val cls = type == DayType.CLASS
        val wake = DayClock.minute(if (cls) s.classWake else s.noClassWake)
        val baseSleep = DayClock.minute(if (cls) s.classSleepLatest else s.noClassSleepLatest)
        // 睡覺時間往前調: earlier each week from its rollout week, never past the target; the block moves with it.
        val sleep = maxOf(DayClock.minute(if (cls) s.classSleepTarget else s.noClassSleepTarget), baseSleep - bedtimeShift(day, specs)).coerceAtMost(baseSleep)
        val strengthLatest = DayClock.minute(if (cls) s.strengthLatestClass else s.strengthLatestNoClass) - (baseSleep - sleep)
        val first = if (cls) Slot(SlotKey.LUNCH, DayClock.minute(m.classLunch), note = "蛋白質至少 ${m.lunchProteinHintG} 克")
        else Slot(SlotKey.FIRST_MEAL, DayClock.minute(m.noClassFirstMeal))
        val slots = buildList {
            add(Slot(SlotKey.WAKE, wake))
            if (soy) add(Slot(SlotKey.MORNING_SOY, wake + m.soyAfterWakeMin, note = "高纖豆漿"))
            add(first)
            if (!cls) add(Slot(SlotKey.WALK, DayClock.minute(t.walkNoClass), note = "${t.walkMin} 分鐘"))
            val noRide = DayClock.minute(t.noRideTrainingAt)
            val cardio = day.dayOfWeek.value in t.cardioDays
            val block = block(noRun, noRide, strengthLatest, cardio)
            add(Slot(SlotKey.DINNER, DayClock.minute(m.dinner)))
            if (!rest) add(Slot(SlotKey.RIDE_OUT, DayClock.minute(s.rideOut), untilMinute = DayClock.minute(s.rideOutUntil)))
            add(Slot(SlotKey.RIDE_MEAL, DayClock.minute(m.rideMeal), note = if (soy) "雞胸（豆漿早上喝過了）" else RIDE_MEAL_NOTE))
            // The training block (owner 2026-11-26): after 收工 on a delivery day (never before the ride: sore legs, unsafe),
            // in the no-run block otherwise; core, rounds, cardio (its days), then the stretch.
            val after = t.strengthAfterRideMin
            val recovery = adjust.filterIsInstance<DayAdjust.Recovery>().lastOrNull()
            if (reduction == null) {
                add(Slot(SlotKey.CORE, block.getValue(SlotKey.CORE), afterRide = !noRun, afterRideMin = after, note = CORE_NOTE))
                add(Slot(SlotKey.STRENGTH, block.getValue(SlotKey.STRENGTH), afterRide = !noRun, afterRideMin = after + 10, note = recovery?.let { "${it.rounds} 輪（病好第一天，明天全量）" } ?: app.agency.core.engine.Progression.note(t)))
                if (cardio) add(Slot(SlotKey.CARDIO, block.getValue(SlotKey.CARDIO), afterRide = !noRun, afterRideMin = after + 35, note = "${app.agency.core.engine.Routine.cardioMinutes(t.cardioStep, t.cardioMin)} 分鐘：$CARDIO_NOTE"))
            }
            val stretchNote = if (reduction == Reduction.INJURY) "${t.stretchMin} 分鐘，避開受傷的地方" else "${t.stretchMin} 分鐘"
            // It closes the block (on a reduced day it stays as the minimum).
            add(Slot(SlotKey.STRETCH, block.getValue(SlotKey.STRETCH), afterRide = !noRun, afterRideMin = after + if (cardio) 50 else 35, note = stretchNote))
            add(Slot(SlotKey.SIGN_OUT, sleep))
            addAll(practice(day, specs, wake, first.minute, sleep, cls, noRun, reduction != null))
            // 理論課 (T19c): class days in class (Friday's classes end at 16:00), no-class days before the walk; rest days too.
            if (lessons.lessons > 0) {
                val due = when {
                    !cls -> app.agency.core.lessons.LessonPlan.NO_CLASS_DUE
                    day.dayOfWeek == DayOfWeek.FRIDAY -> app.agency.core.lessons.LessonPlan.FRIDAY_DUE
                    else -> app.agency.core.lessons.LessonPlan.CLASS_DUE
                }
                add(Slot(SlotKey.LESSONS, DayClock.minute(due), note = "${lessons.lessons} 支，看完考完寫筆記", count = lessons.lessons))
            }
            if (lessons.review) {
                val at = if (cls) app.agency.core.lessons.LessonPlan.REVIEW_CLASS else app.agency.core.lessons.LessonPlan.REVIEW_NO_CLASS
                add(Slot(SlotKey.LESSON_REVIEW, DayClock.minute(at), required = false, note = "兩天前的課，三題"))
            }
        }
        val moved = slots.filter { specs.open(it.key.feature()) }.map { sl -> adjust.filterIsInstance<DayAdjust.Move>().lastOrNull { it.key == sl.key }?.let { sl.copy(minute = it.minute) } ?: sl }
        return DayPlan(
            day = day,
            type = type,
            rest = rest,
            slots = moved.sortedWith(compareBy({ it.minute }, { it.key.ordinal })),
            cheatSlot = SlotKey.parse(override?.cheatSlot),
            soyMorning = soy,
            reduction = reduction,
            budgets = adjust.filterIsInstance<DayAdjust.MealBudget>().associate { it.key to it.kcal },
            progressionStep = t.progressionStep,
            strengthRounds = adjust.filterIsInstance<DayAdjust.Recovery>().lastOrNull()?.rounds?.coerceAtMost(app.agency.core.engine.Progression.rounds(t))
                ?: app.agency.core.engine.Progression.rounds(t),
            stretchMin = t.stretchMin,
            walkMin = t.walkMin,
            cardioMin = app.agency.core.engine.Routine.cardioMinutes(t.cardioStep, t.cardioMin),
            coreStep = t.coreStep,
            cardioStep = t.cardioStep,
        )
    }
}
