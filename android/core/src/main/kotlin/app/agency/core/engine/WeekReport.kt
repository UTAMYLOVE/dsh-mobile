package app.agency.core.engine

import app.agency.core.schedule.SlotKey
import java.time.Instant
import java.time.LocalDate

/**
 * 一週結束 (agency-links L20), pure: what code counts over the days of a Mon–Sun week (only the days, and the slots, after the
 * reminders started): the required items due and done, the training ones, the breaches, the polaroids added and the week's
 * real-person task. A week is on target (owner 2026-11-27 「達標」) when at least [TARGET_PCT]% of what was due is done, with no
 * breach and the real-person task done (once that task is open in the rollout). Only weeks on target in a row count toward
 * the level and open the next rollout week ([weeksOnTarget] is that streak); a week off target starts it again.
 */
object WeekReport {
    const val TARGET_PCT = 90
    val TRAINING = setOf(SlotKey.CORE, SlotKey.STRENGTH, SlotKey.CARDIO, SlotKey.STRETCH, SlotKey.WALK)

    /** One day of the week: its slots with their status (as the day ended), its breaches and polaroids. */
    data class Day(val day: LocalDate, val slots: List<SlotState>, val breaches: Int, val photocards: Int)

    fun of(
        id: String, at: Long, monday: LocalDate, days: List<Day>, since: Instant, realTask: Boolean, earlierOnTarget: Int, level: String,
        realTaskRequired: Boolean = true,
    ): Signal.WeekEnded {
        val due = days.flatMap { d -> d.slots.filter { it.slot.required && !it.dueAt.isBefore(since) } }
        val done = due.count { it.status == SlotStatus.DONE }
        val training = due.filter { it.key in TRAINING }
        val breaches = days.sumOf { it.breaches }
        val on = onTarget(done, due.size, breaches, realTask || !realTaskRequired)
        return Signal.WeekEnded(
            id, at, monday.toString(), due.size, done, training.size, training.count { it.status == SlotStatus.DONE }, breaches,
            days.sumOf { it.photocards }, realTask, on, if (on) earlierOnTarget + 1 else 0, level = level, realTaskRequired = realTaskRequired,
        )
    }

    /** The weeks on target in a row up to the last one counted ([weeks]: the earlier week ends, any order). */
    fun streak(weeks: List<Signal.WeekEnded>): Int {
        var n = 0
        for (w in weeks.sortedByDescending { it.monday }) if (w.onTarget) n++ else break
        return n
    }

    fun onTarget(done: Int, due: Int, breaches: Int, realTask: Boolean): Boolean = due > 0 && done * 100 >= due * TARGET_PCT && breaches == 0 && realTask

    fun pct(done: Int, due: Int): Int = if (due == 0) 0 else done * 100 / due
}

/**
 * The weekly real-person task (定稿 §11 「每週一個真人任務，從最簡單的開始（約認識的朋友吃飯），不逼我跟陌生人講話」, L20): one a
 * week, small, with people he knows — never more chat with her. From the easiest on, one step further each time he has done
 * one ([done]: the tasks done in the look-back), the last staying once reached.
 */
object RealTasks {
    val ALL = listOf(
        "約一個認識的朋友吃一頓飯（午餐、晚餐都算），吃飯時把手機收起來。",
        "傳訊息約一個朋友出來走走或看展。",
        "下課跟一個同學多聊兩句，不是只打招呼。",
        "打電話跟家人聊十分鐘，說說你最近在做什麼。",
        "上課時問老師一個問題，或下課去問。",
        "約兩個朋友一起做一件事，例如運動、唱歌或看電影。",
    )

    fun of(done: Int): String = ALL[done.coerceIn(0, ALL.size - 1)]

    const val DONE_WORDS = "真人任務完成"

    fun line(done: Int): String = "這週的真人任務：${of(done)}做完跟我說「$DONE_WORDS」。"
}

