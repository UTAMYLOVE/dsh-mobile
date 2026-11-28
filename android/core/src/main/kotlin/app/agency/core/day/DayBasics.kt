package app.agency.core.day

import app.agency.core.base.Days
import app.agency.core.plan.DayType
import app.agency.core.rules.DayTimes
import app.agency.core.rules.Rules
import app.agency.core.stage.Feature
import app.agency.core.stage.StageState
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Class day or free day, from the weekdays in the rules. */
object DayTypes {
    fun of(rules: Rules, day: LocalDate): DayType =
        if (day.dayOfWeek.value in rules.classWeekdays) DayType.CLASS else DayType.FREE

    fun times(rules: Rules, type: DayType): DayTimes = if (type == DayType.CLASS) rules.classDay else rules.freeDay
}

/** Dates derived from the stored stage document. */
object StageDays {
    private fun parse(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()

    /**
     * The logical day [level] opened on, or null when it is not open yet. A repeated round resets `startedOn`, so the opening day
     * is the day after the last passed round of the stage before; with no such record the current round's start is used.
     */
    fun openedOn(state: StageState, level: Int): LocalDate? {
        if (level > state.stage) return null
        val joined = parse(state.joinedOn) ?: return null
        if (level <= 1) return joined
        val passed = state.rounds.lastOrNull { it.stage == level - 1 && it.passed }
        if (passed != null) parse(passed.to)?.let { return it.plusDays(1) }
        return if (level == state.stage) parse(state.startedOn) ?: joined else null
    }

    /** Days since joining, 1-based (the joining day is day 1). */
    fun dayNumber(state: StageState, day: LocalDate): Int {
        val joined = parse(state.joinedOn) ?: return 1
        return (ChronoUnit.DAYS.between(joined, day).toInt() + 1).coerceAtLeast(1)
    }
}

/** Today's sign-out deadline, which creeps earlier once the earlier-bedtime feature is open. */
object Bedtime {
    fun of(rules: Rules, stage: StageState, day: LocalDate): LocalTime? {
        val times = DayTypes.times(rules, DayTypes.of(rules, day))
        val base = Days.parse(times.bedtime) ?: return null
        val shift = rules.sleep.shiftMinutesPerWeek
        if (!Feature.EARLY_BED.openAt(stage.stage) || shift <= 0) return base
        val opened = StageDays.openedOn(stage, Feature.EARLY_BED.stage) ?: return base
        val weeks = (ChronoUnit.DAYS.between(opened, day) / 7).coerceAtLeast(0).toInt()
        val baseMin = Days.minuteOfDay(base)
        var m = baseMin - weeks * shift
        Days.parse(times.bedtimeGoal)?.let { m = maxOf(m, Days.minuteOfDay(it)) }
        return fromMinute(m.coerceIn(0, baseMin))
    }

    /** Inverse of [Days.minuteOfDay]. */
    fun fromMinute(m: Int): LocalTime = LocalTime.of(((m / 60) + Days.START_HOUR) % 24, m % 60)
}
