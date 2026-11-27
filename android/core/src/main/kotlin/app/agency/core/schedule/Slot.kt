package app.agency.core.schedule

import kotlinx.serialization.Serializable

/** [PRACTICE]: the items of rollout weeks 3 and 4 (聲樂、站姿、防曬、舞蹈、評鑑、突擊檢查…; [app.agency.core.engine.Practice]). */
enum class SlotKind { SIGN, MEAL, TRAINING, RIDE, STUDY, PRACTICE }

/** The slots of a day (arch 1.5 / plan v3 §4); [zh] is the label he reads. */
enum class SlotKey(val zh: String, val kind: SlotKind) {
    WAKE("起床簽到", SlotKind.SIGN),
    MORNING_SOY("早上的豆漿", SlotKind.MEAL),
    LUNCH("午餐", SlotKind.MEAL),
    FIRST_MEAL("第一餐", SlotKind.MEAL),
    WALK("快走", SlotKind.TRAINING),
    CORE("核心七分鐘", SlotKind.TRAINING),
    DINNER("晚餐", SlotKind.MEAL),
    RIDE_OUT("出門跑單", SlotKind.RIDE),
    /** The 21:00 portion: 雞胸＋豆漿 or a swap of the same protein (on the run on a delivery day). */
    RIDE_MEAL("21:00 那份蛋白質", SlotKind.MEAL),
    STRENGTH("肌力三輪", SlotKind.TRAINING),
    /** 體能 10 分鐘 (開合跳、波比跳、深蹲跳), on [app.agency.core.schedule.TrainingSpec.cardioDays]. */
    CARDIO("體能十分鐘", SlotKind.TRAINING),
    STRETCH("伸展", SlotKind.TRAINING),
    SIGN_OUT("簽退", SlotKind.SIGN),

    /** 理論課 (T19c): done when [Slot.count] lessons passed today; in the plan once the course has started. */
    LESSONS("理論課", SlotKind.STUDY),

    /** 兩天後複習考 (T19c): on the days a review is due; recorded only, never chased. */
    LESSON_REVIEW("複習考", SlotKind.STUDY),

    // 分週上線 第 3 週起 and 第 4 週起 (owner 2026-11-27; [app.agency.core.engine.Practice] says how each is handed in).
    MORNING_STRETCH("起床伸展", SlotKind.PRACTICE),
    VOCAL_BASICS("聲樂基本功", SlotKind.PRACTICE),
    POSTURE("靠牆站姿", SlotKind.PRACTICE),
    SUNSCREEN("擦防曬", SlotKind.PRACTICE),
    DANCE("舞蹈練習", SlotKind.PRACTICE),
    VOCAL_PRACTICE("聲樂練習", SlotKind.PRACTICE),
    EXPRESSION("表情鏡頭", SlotKind.PRACTICE),
    PERSONAL_TASK("個人任務", SlotKind.PRACTICE),
    HABIT("小習慣", SlotKind.PRACTICE),
    THEME_TASK("主題週實作", SlotKind.PRACTICE),
    SURPRISE_CHECK("突擊檢查", SlotKind.PRACTICE),
    BODY_PHOTO("全身對比照", SlotKind.PRACTICE),
    WEEKLY_EVAL("每週評鑑", SlotKind.PRACTICE),
    MONTHLY_EVAL("月評", SlotKind.PRACTICE),
    ;

    companion object {
        fun parse(name: String?): SlotKey? = entries.firstOrNull { it.name == name }
        val MEALS = entries.filter { it.kind == SlotKind.MEAL }
    }
}

/**
 * One slot of a day plan. [minute] is minutes from 00:00 of the coach day's date (may pass 24 × 60: 02:30 that night is
 * 1590). [required] items become gaps when they are late; [afterRide] items are due when he gets home from the run (or at
 * [minute], their latest start, whichever is first). [note] is a code-written hint (「蛋白質至少 45 克」).
 */
@Serializable
data class Slot(
    val key: SlotKey,
    val minute: Int,
    val required: Boolean = true,
    val afterRide: Boolean = false,
    val untilMinute: Int? = null,
    val note: String? = null,
    val count: Int? = null,
    /** An [afterRide] item is due this long after 收工 (the block in order: core, rounds, cardio, stretch). */
    val afterRideMin: Int = 15,
    /** After this minute the slot is over (a 突擊檢查 gives ten minutes): not done by then is 沒做到, never chased. */
    val closesMinute: Int? = null,
) {
    val label: String get() = key.zh
    val time: String get() = DayClock.hhmm(minute)
}

/** What can be moved or placed in today's plan by a 今天微調 (links, T11b): never removal of a required item. */
@Serializable
sealed interface DayAdjust {
    /** Move [key] to [minute] (L1: the first meal after a late wake). */
    @Serializable data class Move(val key: SlotKey, val minute: Int) : DayAdjust

    /** 「早上很餓」: the 豆漿 moves to the morning, the chicken breast stays at 21:00 (L4). */
    @Serializable data object SoyMorning : DayAdjust

    /** A way today runs that is no slot: reminders sound once while riding (L8), no 宵夜 after the on-run portion (L9). */
    @Serializable data class Flag(val flag: DayFlag) : DayAdjust

    /**
     * L5: what is left today makes the next meal [key] smaller: at most [kcal] (never below the floor of the day, never a
     * skipped meal; [app.agency.core.rules.MealBudget]). The suggestion and the nearby search follow it.
     */
    @Serializable data class MealBudget(val key: SlotKey, val kcal: Int) : DayAdjust

    /** The first day after an illness (owner 2026-11-26): the strength rounds are [rounds] (2) today, full tomorrow. */
    @Serializable data class Recovery(val rounds: Int) : DayAdjust
}

/**
 * The 理論課 of one day (T19c) for the planner: [lessons] owed (0: the course has not started, no slot), and whether a
 * 複習考 is due ([review]).
 */
data class LessonDay(val lessons: Int = 0, val review: Boolean = false)

enum class DayFlag { RIDING_ONE_SOUND, LATE_SNACK_OFF }

/** Minutes on the coach day. */
object DayClock {
    const val DAY_START_HOUR = 5

    /** `HH:mm` → minutes from 00:00 of the coach day's date; hours before [dayStartHour] belong to the night after. */
    fun minute(hhmm: String, dayStartHour: Int = DAY_START_HOUR): Int {
        val (h, m) = hhmm.split(":").map { it.trim().toInt() }
        require(h in 0..23 && m in 0..59) { "bad time" }
        return (if (h < dayStartHour) h + 24 else h) * 60 + m
    }

    fun hhmm(minute: Int): String = "%02d:%02d".format((minute / 60) % 24, minute % 60)
}

/** A slot's minute on a coach day as an instant. */
object RuleEngineTime {
    fun at(day: java.time.LocalDate, minute: Int, zone: java.time.ZoneId): java.time.Instant =
        day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant()
}
