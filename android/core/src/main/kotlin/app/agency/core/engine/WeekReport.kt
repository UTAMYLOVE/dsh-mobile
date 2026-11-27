package app.agency.core.engine

import app.agency.core.schedule.SlotKey
import java.time.Instant
import java.time.LocalDate

/**
 * 一週結束 (agency-links L20), pure: what code counts over the days of a Mon–Sun week (only the days, and the slots, after the
 * reminders started): the required items due and done, the training ones, the breaches, the polaroids added and the week's
 * real-person task. A week is on target when at least [TARGET_PCT]% of what was due is done, with no breach and the
 * real-person task done. The level itself moves with the month-end evaluation (stage 4); this only counts the weeks.
 */
object WeekReport {
    const val TARGET_PCT = 80
    val TRAINING = setOf(SlotKey.CORE, SlotKey.STRENGTH, SlotKey.CARDIO, SlotKey.STRETCH, SlotKey.WALK)

    /** One day of the week: its slots with their status (as the day ended), its breaches and polaroids. */
    data class Day(val day: LocalDate, val slots: List<SlotState>, val breaches: Int, val photocards: Int)

    fun of(id: String, at: Long, monday: LocalDate, days: List<Day>, since: Instant, realTask: Boolean, earlierOnTarget: Int, level: String): Signal.WeekEnded {
        val due = days.flatMap { d -> d.slots.filter { it.slot.required && !it.dueAt.isBefore(since) } }
        val done = due.count { it.status == SlotStatus.DONE }
        val training = due.filter { it.key in TRAINING }
        val breaches = days.sumOf { it.breaches }
        val on = onTarget(done, due.size, breaches, realTask)
        return Signal.WeekEnded(
            id, at, monday.toString(), due.size, done, training.size, training.count { it.status == SlotStatus.DONE }, breaches,
            days.sumOf { it.photocards }, realTask, on, earlierOnTarget + if (on) 1 else 0, level = level,
        )
    }

    fun onTarget(done: Int, due: Int, breaches: Int, realTask: Boolean): Boolean = due > 0 && done * 100 >= due * TARGET_PCT && breaches == 0 && realTask

    fun pct(done: Int, due: Int): Int = if (due == 0) 0 else done * 100 / due
}

/**
 * The weekly real-person task (plan v3 §3 「會主動推我去找真人」, agency-links L20): one a week, small, with a real person — never
 * more chat with her. Lunch with friends does not count (he does that every day; owner 2026-11-22). Picked by the week.
 */
object RealTasks {
    val ALL = listOf(
        "下課跟一個同學多聊兩句，不是只打招呼。",
        "買東西時跟店員講一句真心的話，例如謝謝他，或問他推薦什麼。",
        "傳訊息約一個朋友出來走走或看展（吃飯不算）。",
        "打電話跟家人聊十分鐘，說說你最近在做什麼。",
        "上課時問老師一個問題，或下課去問。",
    )

    fun of(monday: LocalDate): String = ALL[Math.floorMod(monday.toEpochDay() / 7, ALL.size.toLong()).toInt()]

    const val DONE_WORDS = "真人任務完成"

    fun line(monday: LocalDate): String = "這週的真人任務：${of(monday)}做完跟我說「$DONE_WORDS」。"
}
