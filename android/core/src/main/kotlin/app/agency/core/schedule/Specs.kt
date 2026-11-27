package app.agency.core.schedule

import app.agency.core.model.PlanSectionKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * His plan as data (plan v3 §4, §5, §7; arch 1.2 `plan_section`). Times are `HH:mm` on the coach day: an hour before the day
 * start (05:00) is after midnight, so 「02:30」 is the night after ([DayClock.minute]). A confirmed proposal (T12) or 設定 writes
 * a new version of one section; code reads them only through [PlanSpecs.decode], which falls back to these defaults.
 */
@Serializable
data class ScheduleSpec(
    /** ISO days of week with classes (一 = 1): 一、二、四、五. */
    val classDays: List<Int> = listOf(1, 2, 4, 5),
    val classWake: String = "08:30",
    /** No-class days: sign in by this time. */
    val noClassWake: String = "12:00",
    val classSleepLatest: String = "02:30",
    val noClassSleepLatest: String = "04:00",
    val rideOut: String = "18:00",
    val rideOutUntil: String = "19:00",
    val strengthLatestClass: String = "01:50",
    val strengthLatestNoClass: String = "03:20",
    val restDaysPerWeek: Int = 1,
    /**
     * 睡覺時間往前調 (owner 2026-11-26, from its rollout week): the latest sign-out moves [sleepStepMin] minutes earlier each
     * week, down to these targets; the training block and the 收工 reminder follow it.
     */
    val classSleepTarget: String = "01:30",
    val noClassSleepTarget: String = "02:30",
    val sleepStepMin: Int = 15,
    /**
     * 外送 (owner 2026-11-26): the week's income target is his own number (null until he sets it with 「外送週目標 N」: the code
     * ships none) over [deliveryDaysPerWeek] days; the week's total counts, not one day.
     */
    val weeklyIncomeTarget: Int? = null,
    val deliveryDaysPerWeek: Int = 6,
)

@Serializable
data class MealSpec(
    val classLunch: String = "12:10",
    val lunchProteinHintG: Int = 45,
    val noClassFirstMeal: String = "13:00",
    val dinner: String = "17:35",
    /**
     * Mandatory (on the run: riding safety): 雞胸 and 豆漿, or a swap of about the same protein (owner 2026-11-26: 福樂蛋白飲＋
     * 茶葉蛋, 義美高蛋白奶＋茶葉蛋, 低脂鮮奶), counted in the day's 1700; the 豆漿 moves to the morning on an 「早上很餓」 day. No
     * eating window (no 「168」) and no snack: three meals a day.
     */
    val rideMeal: String = "21:00",
    /** When he wakes late the first meal may move, at most to this many minutes before the next meal (link L1). */
    val firstMealGapMin: Int = 180,
    /** The 「早上很餓」 豆漿 is due this long after the wake slot. */
    val soyAfterWakeMin: Int = 30,
)

@Serializable
data class TrainingSpec(
    val core: String = "17:00",
    val coreFriday: String = "16:05",
    val strengthRounds: Int = 3,
    val stretchMin: Int = 10,
    val walkNoClass: String = "16:20",
    val walkMin: Int = 30,
    /** Steps of the progression rule already taken (0 = the three original moves; T18): the strength ladder. */
    val progressionStep: Int = 0,
    /** The core and the cardio ladders (owner 2026-11-26: 「加上體能與核心的階梯」), and the day of the last step taken. */
    val coreStep: Int = 0,
    val cardioStep: Int = 0,
    val lastStepDay: String? = null,
    /** After 收工 he puts the helmet down first: the block (core first) is due this long after the ride ends. */
    val strengthAfterRideMin: Int = 15,
    /** ISO days of the 10-minute 體能 (開合跳、波比跳、深蹲跳): three a week (owner 2026-11-26); 二、四、六 by default. */
    val cardioDays: List<Int> = listOf(2, 4, 6),
    val cardioMin: Int = 10,
    /**
     * A day without a run (a rest day: owner 2026-11-26 「休息日只是不跑單」): the core, the rounds (the cardio) and the stretch
     * still happen, any time after 17:00; due from this time (an hour after dinner), the rest of the block after it; the
     * evening after is free time. On a delivery day the block is after 收工 (riding with sore legs is unsafe).
     */
    val noRideTrainingAt: String = "18:30",
)

@Serializable
data class WaterSpec(
    /** The first day of the bottle plan (`yyyy-MM-dd`); null until he starts (T17). */
    val startDay: String? = null,
    val baseHalves: Int = 2,
    val maxHalves: Int = 4,
    val stepWeeks: Int = 2,
)

/** A small daily habit from a passed lesson (學了就用, owner 2026-11-23): added on his yes, one at a time. */
@Serializable
data class Habit(val id: String, val text: String, val category: String, val addedOn: String, val at: String, val lessonId: String? = null)

/**
 * The practice items of rollout weeks 3 and 4 (owner 2026-11-27 定稿 §3, §6, §10, §11) and what the evaluations set: times are
 * `HH:mm` on the coach day, days ISO (一 = 1). [personalTask] is the month's one weak point (code-set by the 月評, from
 * [personalTaskFrom]); [habits] are the 學了就用 habits he said yes to.
 */
@Serializable
data class PracticeSpec(
    val morningStretchMin: Int = 5,
    val morningStretchAfterWakeMin: Int = 20,
    val vocalBasicsMin: Int = 10,
    val vocalBasicsAfterWakeMin: Int = 40,
    val postureMin: Int = 3,
    val posture: String = "17:20",
    val danceMin: Int = 40,
    val vocalPracticeMin: Int = 20,
    val expressionMin: Int = 10,
    val expressionDays: List<Int> = listOf(3, 6),
    val evalDay: Int = 7,
    val evalAt: String = "15:30",
    val themeDay: Int = 6,
    val themeAt: String = "15:30",
    val checkWindowMin: Int = 10,
    val personalTaskMin: Int = 10,
    val personalTask: String? = null,
    val personalTaskFrom: String? = null,
    val habits: List<Habit> = emptyList(),
)

/** The sections together; [rollout] is 上線週數 (a store never seeded has none: everything open, [RolloutSpec.ALL_OPEN]). */
data class PlanSpecs(
    val schedule: ScheduleSpec = ScheduleSpec(),
    val meals: MealSpec = MealSpec(),
    val training: TrainingSpec = TrainingSpec(),
    val water: WaterSpec = WaterSpec(),
    val rollout: RolloutSpec = RolloutSpec.ALL_OPEN,
    val practice: PracticeSpec = PracticeSpec(),
    val levels: LevelSpec = LevelSpec(),
) {
    fun json(key: PlanSectionKey): String = when (key) {
        PlanSectionKey.SCHEDULE -> JSON.encodeToString(ScheduleSpec.serializer(), schedule)
        PlanSectionKey.MEALS -> JSON.encodeToString(MealSpec.serializer(), meals)
        PlanSectionKey.TRAINING -> JSON.encodeToString(TrainingSpec.serializer(), training)
        PlanSectionKey.WATER -> JSON.encodeToString(WaterSpec.serializer(), water)
        PlanSectionKey.ROLLOUT -> JSON.encodeToString(RolloutSpec.serializer(), rollout)
        PlanSectionKey.PRACTICE -> JSON.encodeToString(PracticeSpec.serializer(), practice)
        PlanSectionKey.LEVELS -> JSON.encodeToString(LevelSpec.serializer(), levels)
    }

    fun open(f: Feature): Boolean = rollout.open(f)

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Decodes stored sections; a missing or unreadable one is the default (never a crash, never a guess). */
        fun decode(sections: Map<PlanSectionKey, String>): PlanSpecs {
            fun <T> read(key: PlanSectionKey, d: T, f: (String) -> T): T = sections[key]?.let { runCatching { f(it) }.getOrNull() } ?: d
            return PlanSpecs(
                read(PlanSectionKey.SCHEDULE, ScheduleSpec()) { JSON.decodeFromString(ScheduleSpec.serializer(), it) },
                read(PlanSectionKey.MEALS, MealSpec()) { JSON.decodeFromString(MealSpec.serializer(), it) },
                read(PlanSectionKey.TRAINING, TrainingSpec()) { JSON.decodeFromString(TrainingSpec.serializer(), it) },
                read(PlanSectionKey.WATER, WaterSpec()) { JSON.decodeFromString(WaterSpec.serializer(), it) },
                // Missing: never seeded (everything open); unreadable: week 1 (the safe side).
                sections[PlanSectionKey.ROLLOUT]?.let { j -> runCatching { JSON.decodeFromString(RolloutSpec.serializer(), j) }.getOrDefault(RolloutSpec.SEED) }
                    ?: RolloutSpec.ALL_OPEN,
                read(PlanSectionKey.PRACTICE, PracticeSpec()) { JSON.decodeFromString(PracticeSpec.serializer(), it) },
                read(PlanSectionKey.LEVELS, LevelSpec()) { JSON.decodeFromString(LevelSpec.serializer(), it) },
            )
        }
    }
}

/**
 * 每科等級 (T19e, owner 2026-11-23): each subject's level on 入門 → 基礎 → 中級 → 進階 → 專業 ([app.agency.core.engine.Levels]),
 * by subject name, and the day it reached it. Never lower (no level-down); only code moves it up.
 */
@Serializable
data class SubjectLevel(val level: Int = 0, val since: String? = null)

@Serializable
data class LevelSpec(val subjects: Map<String, SubjectLevel> = emptyMap()) {
    fun of(subject: String): SubjectLevel = subjects[subject] ?: SubjectLevel()
}
