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
) {
    fun slot(key: SlotKey): Slot? = slots.firstOrNull { it.key == key }

    /** The first meal of the day: 午餐 on a class day, 第一餐 otherwise (the 早上豆漿 is not a meal slot to move). */
    val firstMeal: SlotKey get() = if (type == DayType.CLASS) SlotKey.LUNCH else SlotKey.FIRST_MEAL
    val delivery: Boolean get() = slot(SlotKey.RIDE_OUT) != null
}

/**
 * Builds a day's plan from the stored sections (arch 1.5, plan v3 §4): pure. Class day: 08:30 起床簽到, 12:10 午餐 (蛋白質至少
 * 45 克), 17:00 核心 (星期五 16:05), 17:15 晚餐, 18:00–19:00 出門, 21:00 雞胸加豆漿 on the run (mandatory), after 收工 肌力三輪
 * (latest start 01:50) and 伸展, latest sign-out 02:30. No-class day: wake by 12:00, 13:00 第一餐, 16:20 快走 30 分鐘, 17:00 核心,
 * 17:15 晚餐, delivery, 21:00, 肌力 and 伸展, sign-out by 04:00. A rest day keeps those times without the delivery; its core,
 * rounds and stretch are owed as on any day, due from [TrainingSpec.noRideTrainingAt] (not after a ride: there is none).
 */
object DayPlanner {
    /** On a day without a run: the rounds this long after the core, the stretch this long after the core. */
    const val NO_RIDE_STRENGTH_AFTER_MIN = 10
    const val NO_RIDE_STRETCH_AFTER_MIN = 35

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
        val soy = override?.soyMorning == true || adjust.any { it is DayAdjust.SoyMorning }
        val cls = type == DayType.CLASS
        val wake = DayClock.minute(if (cls) s.classWake else s.noClassWake)
        val sleep = DayClock.minute(if (cls) s.classSleepLatest else s.noClassSleepLatest)
        val strengthLatest = DayClock.minute(if (cls) s.strengthLatestClass else s.strengthLatestNoClass)
        val first = if (cls) Slot(SlotKey.LUNCH, DayClock.minute(m.classLunch), note = "蛋白質至少 ${m.lunchProteinHintG} 克")
        else Slot(SlotKey.FIRST_MEAL, DayClock.minute(m.noClassFirstMeal))
        val slots = buildList {
            add(Slot(SlotKey.WAKE, wake))
            if (soy) add(Slot(SlotKey.MORNING_SOY, wake + m.soyAfterWakeMin, note = "高纖豆漿"))
            add(first)
            if (!cls) add(Slot(SlotKey.WALK, DayClock.minute(t.walkNoClass), note = "${t.walkMin} 分鐘"))
            val noRide = DayClock.minute(t.noRideTrainingAt)
            val core = if (rest) noRide else DayClock.minute(if (day.dayOfWeek == DayOfWeek.FRIDAY) t.coreFriday else t.core)
            if (reduction == null) add(Slot(SlotKey.CORE, core, note = "半船式、半仰臥起坐、抱膝、單車捲腹、進階抬腿、側棒式左右"))
            add(Slot(SlotKey.DINNER, DayClock.minute(m.dinner)))
            if (!rest) add(Slot(SlotKey.RIDE_OUT, DayClock.minute(s.rideOut), untilMinute = DayClock.minute(s.rideOutUntil)))
            add(Slot(SlotKey.RIDE_MEAL, DayClock.minute(m.rideMeal), note = if (soy) "雞胸（豆漿早上喝過了）" else "雞胸加豆漿，一定要吃"))
            if (reduction == null) {
                val at = if (rest) noRide + NO_RIDE_STRENGTH_AFTER_MIN else strengthLatest
                add(Slot(SlotKey.STRENGTH, at, afterRide = !rest, note = app.agency.core.engine.Progression.note(t)))
            }
            val stretchNote = if (reduction == Reduction.INJURY) "${t.stretchMin} 分鐘，避開受傷的地方" else "${t.stretchMin} 分鐘"
            // It follows the rounds (on a reduced day it stays as the minimum); a rest day's is owed too, after its block.
            add(Slot(SlotKey.STRETCH, if (rest) noRide + NO_RIDE_STRETCH_AFTER_MIN else strengthLatest + 20, afterRide = !rest, note = stretchNote))
            add(Slot(SlotKey.SIGN_OUT, sleep))
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
        val moved = slots.map { sl -> adjust.filterIsInstance<DayAdjust.Move>().lastOrNull { it.key == sl.key }?.let { sl.copy(minute = it.minute) } ?: sl }
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
            strengthRounds = app.agency.core.engine.Progression.rounds(t),
            stretchMin = t.stretchMin,
            walkMin = t.walkMin,
        )
    }
}
