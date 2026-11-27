package app.agency.core.schedule

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * What switches on week by week (owner 2026-11-27 「分週上線」): everything is built, and opens on his phone in the week of the
 * table. [defaultWeek] is the owner's table; the stored [RolloutSpec.table] may say otherwise (data, not code). Chat, 設定,
 * the old-data import and 入社建檔 (each item skippable) are always there.
 */
enum class Feature(val zh: String, val defaultWeek: Int) {
    LESSONS("理論課和考試", 1),
    EXAM_WEEK("考試週", 1),
    SIGN("簽到簽退", 2),
    MEALS("三餐拍照", 2),
    WATER("喝水", 2),
    CORE("核心", 2),
    STRENGTH("肌力和伸展", 2),
    RIDE("出門跑單", 2),
    WEATHER("天氣提醒", 2),
    INCOME("外送收入", 2),
    ATTENDANCE("全勤小卡", 2),
    MORNING_STRETCH("起床伸展", 3),
    VOCAL_BASICS("聲樂基本功", 3),
    CARDIO("體能", 3),
    WALK("快走", 3),
    POSTURE("站姿", 3),
    SUNSCREEN("防曬", 3),
    MOOD("心情", 3),
    GROOMING("儀容", 3),
    CAFFEINE("晚上不喝咖啡和茶", 3),
    DANCE("舞蹈", 4),
    WEEKLY_EVAL("每週評鑑", 4),
    REAL_TASK("真人任務", 4),
    SURPRISE("突擊檢查", 4),
    THEME_WEEK("主題週", 4),
    EARLIER_BEDTIME("睡覺時間往前調", 4),
    MONTHLY_EVAL("月評", 4),
    ;

    companion object {
        fun parse(name: String?): Feature? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 上線週數, stored as a plan section in the encrypted database ([app.agency.core.model.PlanSectionKey.ROLLOUT]): the week he is
 * in ([week], 1 to [LAST]), the day each week opened ([openedOn], week → `yyyy-MM-dd`), and the table ([table]: feature name →
 * the week it opens). A week opens only on his yes after a week on target (or when he sets it in 設定). What is not open yet is
 * not planned, not chased and never counts as a breach; the screens hide it or say 「第 N 週開放」.
 */
@Serializable
data class RolloutSpec(
    val week: Int = 1,
    val openedOn: Map<String, String> = emptyMap(),
    val table: Map<String, Int> = Feature.entries.associate { it.name to it.defaultWeek },
) {
    fun weekOf(f: Feature): Int = table[f.name] ?: f.defaultWeek

    fun open(f: Feature): Boolean = week >= weekOf(f)

    /** What opens in week [w] (by the table). */
    fun opensIn(w: Int): List<Feature> = Feature.entries.filter { weekOf(it) == w }

    /** The day week [w] opened, when it is known. */
    fun openedDay(w: Int): LocalDate? = openedOn[w.toString()]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** The next week, opened on [day]. */
    fun next(day: LocalDate): RolloutSpec = at(week + 1, day)

    /** Week [w] (1..[LAST]), set on [day]: the opening days of the weeks after it are forgotten, its own is [day] when new. */
    fun at(w: Int, day: LocalDate): RolloutSpec {
        val n = w.coerceIn(1, LAST)
        val kept = openedOn.filterKeys { (it.toIntOrNull() ?: 0) <= n }
        return copy(week = n, openedOn = if (kept.containsKey(n.toString())) kept else kept + (n.toString() to day.toString()))
    }

    /** 「第 N 週開放」 for a feature not open yet. */
    fun closedLine(f: Feature): String = "${f.zh}第 ${weekOf(f)} 週開放，現在是第 $week 週。這週先把開放的做好。"

    companion object {
        /** The weeks of the table: from week 4 on everything is open. */
        const val LAST = 4

        /** What a new install starts with (written on the first open): week 1, only lessons. */
        val SEED = RolloutSpec(week = 1)

        /**
         * No stored section at all (a store that was never seeded: tests and previews): everything open, as before the rollout.
         * An unreadable stored section is read as [SEED] (the safe side).
         */
        val ALL_OPEN = RolloutSpec(week = LAST)
    }
}

/** The feature a slot belongs to (a slot of a feature not open is not planned). */
fun SlotKey.feature(): Feature = when (this) {
    SlotKey.WAKE, SlotKey.SIGN_OUT -> Feature.SIGN
    SlotKey.MORNING_SOY, SlotKey.LUNCH, SlotKey.FIRST_MEAL, SlotKey.DINNER, SlotKey.RIDE_MEAL -> Feature.MEALS
    SlotKey.WALK -> Feature.WALK
    SlotKey.CORE -> Feature.CORE
    SlotKey.STRENGTH, SlotKey.STRETCH -> Feature.STRENGTH
    SlotKey.CARDIO -> Feature.CARDIO
    SlotKey.RIDE_OUT -> Feature.RIDE
    SlotKey.LESSONS, SlotKey.LESSON_REVIEW -> Feature.LESSONS
}
