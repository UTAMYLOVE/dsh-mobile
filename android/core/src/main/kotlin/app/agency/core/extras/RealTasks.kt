package app.agency.core.extras

import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.store.Entry
import app.agency.core.store.Kind
import app.agency.core.store.RealTask
import app.agency.core.store.Store
import app.agency.core.store.add
import app.agency.core.store.update
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * One real-person task a week, from a ladder that starts with the easiest rung and only ever involves people he already
 * knows (no strangers, ever). The rung is the number of tasks he has finished so far: an unfinished week repeats its rung.
 * Stored on the Monday of its week as [Kind.REAL_TASK].
 */
class RealTasks(private val store: Store, private val clock: Clock) {

    private fun monday(d: LocalDate) = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun entries(): List<Pair<Entry, RealTask>> =
        store.entries(LocalDate.of(2000, 1, 1), LocalDate.of(2200, 1, 1), Kind.REAL_TASK)
            .mapNotNull { e -> runCatching { e.read<RealTask>() }.getOrNull()?.let { e to it } }

    private fun ofWeek(week: LocalDate): Pair<Entry, RealTask>? {
        val start = monday(week)
        return entries().lastOrNull { it.first.day == start }
    }

    /** What the week's task is, without writing anything. */
    fun peek(week: LocalDate): RealTask {
        ofWeek(week)?.let { return it.second }
        val doneCount = entries().count { it.second.done }
        return RealTask(LADDER[doneCount.coerceAtMost(LADDER.lastIndex)])
    }

    /** The week's task; the first call of a week stores it. */
    fun current(week: LocalDate): RealTask {
        ofWeek(week)?.let { return it.second }
        val task = peek(week)
        store.add(monday(week), clock.now(), Kind.REAL_TASK, task)
        return task
    }

    /**
     * He did it. [text] names the task (the current one when blank). Returns the finished task, or null when no such open task
     * exists. Doing it twice changes nothing.
     */
    fun done(text: String = ""): RealTask? {
        val week = Days.today(clock)
        if (text.isBlank()) current(week)
        val target = if (text.isBlank()) ofWeek(week) else entries().lastOrNull { it.second.text == text.trim() && !it.second.done }
        val (entry, task) = target ?: return null
        if (task.done) return task
        val next = task.copy(done = true, doneOn = week.toString())
        store.update(entry.id, next)
        return next
    }

    /** The week's task is done. */
    fun weekDone(week: LocalDate): Boolean = ofWeek(week)?.second?.done == true

    /**
     * A task was finished on a logical day inside [from]..[to] (the round judgement and the home item both ask this, so they
     * agree). A task stored before [RealTask.doneOn] existed counts on the day of its entry.
     */
    fun doneWithin(from: LocalDate, to: LocalDate): Boolean = entries().any { (e, t) ->
        if (!t.done) return@any false
        val on = t.doneOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: e.day
        !on.isBefore(from) && !on.isAfter(to)
    }

    companion object {
        /** Easiest first. Every rung names people he already knows. */
        val LADDER: List<String> = listOf(
            "這週跟一位你已經認識的朋友一起吃一頓飯",
            "這週傳訊息約一位認識的朋友，見面聊十分鐘",
            "這週跟認識的朋友一起去一家你沒去過的店",
            "這週在認識的朋友面前自我介紹，看著對方的眼睛說完",
            "這週請認識的朋友幫你錄一段十秒的自我介紹，再一起看",
            "這週跟認識的朋友散步三十分鐘，主動問他三個問題",
            "這週把你練的歌或舞表演給認識的朋友看",
            "這週邀請兩位認識的朋友一起吃飯，由你來安排地點",
        )
    }
}
