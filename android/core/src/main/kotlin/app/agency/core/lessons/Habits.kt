package app.agency.core.lessons

import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.plan.HabitToday
import app.agency.core.store.Kind
import app.agency.core.store.Store
import app.agency.core.store.add
import app.agency.core.store.update
import java.time.LocalDate
import kotlinx.serialization.Serializable

/** The `Kind.HABIT` payload: one small daily habit 小葵 proposed after a lesson and he accepted. [doneDays] are ISO days. */
@Serializable
data class Habit(val name: String, val startedOn: String, val active: Boolean = true, val doneDays: List<String> = emptyList())

/**
 * Daily habits. A new one is allowed only when the previous one has stuck: done on at least [NEEDED] of the [WINDOW] days
 * before today. Habits are never created by the model directly; the tool asks [canAddNew] first.
 */
class Habits(private val store: Store, private val clock: Clock) {
    private class Row(val id: Long, val habit: Habit)

    private fun rows(upTo: LocalDate): List<Row> =
        store.entries(upTo.minusDays(LessonStore.WINDOW_DAYS), upTo, Kind.HABIT).mapNotNull { e ->
            runCatching { Row(e.id, e.read<Habit>()) }.getOrNull()
        }

    /** True when there is no previous habit, or the latest one was done on at least 12 of the 14 days before [day]. */
    fun canAddNew(day: LocalDate): Boolean {
        val last = rows(day).maxWithOrNull(compareBy({ it.habit.startedOn }, { it.id })) ?: return true
        val window = (1..WINDOW).map { day.minusDays(it.toLong()).toString() }.toSet()
        return last.habit.doneDays.count { it in window } >= NEEDED
    }

    /** Adds a habit starting [day] (default today); null when the gate says no or the name is blank. */
    fun add(name: String, day: LocalDate = Days.today(clock)): Long? {
        if (name.isBlank() || !canAddNew(day)) return null
        return store.add(day, clock.now(), Kind.HABIT, Habit(name.trim(), day.toString())).id
    }

    /** The active habits for [day], as plan items need them. */
    fun today(day: LocalDate): List<HabitToday> =
        rows(day).filter { it.habit.active && it.habit.startedOn <= day.toString() }.sortedBy { it.id }
            .map { HabitToday(it.id, it.habit.name, day.toString() in it.habit.doneDays) }

    /** Marks the habit done on [day]; false when it does not exist, is retired, or [day] is before it started. */
    fun markDone(id: Long, day: LocalDate): Boolean {
        val e = store.entry(id)?.takeIf { it.kind == Kind.HABIT } ?: return false
        val h = runCatching { e.read<Habit>() }.getOrNull() ?: return false
        if (!h.active || day.toString() < h.startedOn) return false
        if (day.toString() in h.doneDays) return true
        return store.update(id, h.copy(doneDays = (h.doneDays + day.toString()).sorted()))
    }

    /** Stops a habit (he dropped it). It still counts as the previous habit for the gate. */
    fun retire(id: Long): Boolean {
        val e = store.entry(id)?.takeIf { it.kind == Kind.HABIT } ?: return false
        val h = runCatching { e.read<Habit>() }.getOrNull() ?: return false
        return store.update(id, h.copy(active = false))
    }

    companion object {
        const val WINDOW = 14
        const val NEEDED = 12
    }
}
