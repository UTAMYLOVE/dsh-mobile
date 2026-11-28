package app.agency.core.day

import app.agency.core.plan.TaskId
import app.agency.core.rules.Rules
import app.agency.core.stage.StageState
import app.agency.core.store.Body
import app.agency.core.store.CheckIn
import app.agency.core.store.CheckOut
import app.agency.core.store.DrinkType
import app.agency.core.store.Drink
import app.agency.core.store.Entry
import app.agency.core.store.Kind
import app.agency.core.store.Meal
import app.agency.core.store.MealSlot
import app.agency.core.store.Mood
import app.agency.core.store.Ride
import app.agency.core.store.RidePhase
import app.agency.core.store.Status
import app.agency.core.store.StatusType
import app.agency.core.store.TaskDone
import app.agency.core.store.Violation
import app.agency.core.store.Water
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One logged meal with its totals worked out. */
data class MealRecord(val entryId: Long, val meal: Meal, val kcal: Int, val proteinG: Double, val at: Instant)

/**
 * Everything that happened on one logical day, derived from entries (never stored). The planner, reminders, the context block and
 * the violation checks all read this one shape, so they cannot disagree.
 */
data class DayState(
    val day: LocalDate,
    val zone: ZoneId,
    val rules: Rules,
    val stage: StageState,
    val checkIn: CheckIn?,
    val checkInAt: Instant?,
    val checkOut: CheckOut?,
    val checkOutAt: Instant?,
    val meals: List<MealRecord>,
    val waterMl: Int,
    val rideOutAt: Instant?,
    val rideBackAt: Instant?,
    val income: Int,
    /** Out and not back yet. */
    val riding: Boolean,
    /** Last record of each routine item wins. */
    val tasks: Map<TaskId, TaskDone>,
    val drinks: List<DrinkType>,
    val mood: Mood?,
    val body: Body?,
    val restDay: Boolean,
    val sick: Boolean,
    val injured: Boolean,
    /** Days since the illness began, today included (0 when not sick). */
    val sickDays: Int,
    /** Recovered yesterday or today after being unwell. */
    val firstDayBack: Boolean,
    val examWeek: Boolean,
    /** From yesterday's check-out to today's check-in, or null when either is missing. */
    val sleepHours: Double?,
    val violations: List<Violation>,
) {
    val kcal: Int get() = meals.sumOf { it.kcal }
    val proteinG: Double get() = meals.sumOf { it.proteinG }
    val leftKcal: Int get() = rules.diet.kcal - kcal
    val rode: Boolean get() = rideOutAt != null
    val unwell: Boolean get() = sick || injured

    /** Days since he joined, 1-based. */
    val dayNumber: Int get() = StageDays.dayNumber(stage, day)

    fun mealsOf(slot: MealSlot): List<MealRecord> = meals.filter { it.meal.slot == slot }

    /**
     * What the next meal of [slot] may be: the day's remaining kcal minus what the meals still to come are planned to take, capped
     * per meal. A big first meal therefore shrinks dinner by itself. Never below 0.
     */
    fun budget(slot: MealSlot): Int {
        val d = rules.diet
        fun planned(s: MealSlot, kcal: Int) = if (mealsOf(s).isEmpty()) kcal else 0
        val reserve = when (slot) {
            MealSlot.FIRST -> planned(MealSlot.DINNER, d.dinnerKcal) + planned(MealSlot.NIGHT, d.nightMealKcal)
            MealSlot.DINNER -> planned(MealSlot.NIGHT, d.nightMealKcal)
            MealSlot.NIGHT, MealSlot.EXTRA -> 0
        }
        val room = d.kcal - kcal - reserve
        return (if (d.mealCapKcal > 0) minOf(d.mealCapKcal, room) else room).coerceAtLeast(0)
    }

    /**
     * The water he owes today: [dayNumber] counts from joining. The first `rampAfterDays` days are `bottlesAtStart` bottles; after
     * that a quarter bottle more each week up to `bottlesGoal`.
     */
    fun waterTargetMl(dayNumber: Int): Int {
        val w = rules.water
        if (w.bottleMl <= 0 || w.bottlesAtStart <= 0) return 0
        val weeks = if (dayNumber <= w.rampAfterDays) 0 else (dayNumber - w.rampAfterDays - 1) / 7 + 1
        val goal = maxOf(w.bottlesGoal, w.bottlesAtStart)
        val bottles = minOf(goal.toDouble(), w.bottlesAtStart + 0.25 * weeks)
        return Math.round(bottles * w.bottleMl).toInt()
    }

    companion object {
        private inline fun <reified T> Entry.readOrNull(): T? = runCatching { read<T>() }.getOrNull()

        /**
         * [entries] are that logical day's entries, [recent] the previous 14 days' (for statuses that last and last night's sleep).
         */
        fun of(day: LocalDate, entries: List<Entry>, recent: List<Entry>, rules: Rules, stage: StageState, zone: ZoneId): DayState {
            val today = entries.filter { it.day == day }.sortedWith(compareBy({ it.at }, { it.id }))

            val checkIns = today.filter { it.kind == Kind.CHECK_IN }
            val checkOuts = today.filter { it.kind == Kind.CHECK_OUT }
            // An imported meal or weight is history only: it never makes today's item done.
            val meals = today.filter { it.kind == Kind.MEAL }.mapNotNull { e ->
                e.readOrNull<Meal>()?.takeIf { it.batch == null }?.let { m ->
                    MealRecord(e.id, m, m.items.sumOf { it.kcal }, m.items.sumOf { it.proteinG }, e.at)
                }
            }
            val water = today.filter { it.kind == Kind.WATER }.sumOf { e -> e.readOrNull<Water>()?.ml ?: 0 }
            val rides = today.filter { it.kind == Kind.RIDE }.mapNotNull { e -> e.readOrNull<Ride>()?.let { e to it } }
            val tasks = LinkedHashMap<TaskId, TaskDone>()
            for (e in today.filter { it.kind == Kind.TASK }) {
                val t = e.readOrNull<TaskDone>() ?: continue
                val id = TaskId.values().firstOrNull { it.name == t.task } ?: continue
                tasks[id] = t
            }
            val bodies = today.filter { it.kind == Kind.BODY }.mapNotNull { it.readOrNull<Body>() }.filter { it.batch == null }
            val body = if (bodies.isEmpty()) null else Body(
                kg = bodies.mapNotNull { it.kg }.lastOrNull(),
                waistCm = bodies.mapNotNull { it.waistCm }.lastOrNull(),
                photoId = bodies.mapNotNull { it.photoId }.lastOrNull(),
            )

            // Illness is read from the whole window: oldest first, a RECOVERED clears whatever came before it.
            val statuses = (recent + entries).filter { it.kind == Kind.STATUS && it.day <= day }
                .sortedWith(compareBy({ it.at }, { it.id }))
                .mapNotNull { e -> e.readOrNull<Status>()?.let { e to it.type } }
            var sickFrom: LocalDate? = null
            var injured = false
            var recoveredDay: LocalDate? = null
            for ((e, type) in statuses) {
                when (type) {
                    StatusType.SICK -> if (sickFrom == null) sickFrom = e.day
                    StatusType.INJURED -> injured = true
                    StatusType.RECOVERED -> if (sickFrom != null || injured) {
                        sickFrom = null
                        injured = false
                        recoveredDay = e.day
                    }
                    else -> Unit
                }
            }
            val sick = sickFrom != null
            val firstDayBack = !sick && !injured && recoveredDay != null &&
                (recoveredDay == day || recoveredDay == day.minusDays(1))
            val examWeek = statuses.any { (e, t) -> t == StatusType.EXAM_WEEK && !e.day.isBefore(day.minusDays(6)) }
            val restDay = statuses.any { (e, t) -> t == StatusType.REST_DAY && e.day == day }

            val lastNightOut = recent.filter { it.kind == Kind.CHECK_OUT && it.day == day.minusDays(1) }
                .maxWithOrNull(compareBy({ it.at }, { it.id }))?.at
            val thisMorning = checkIns.firstOrNull()?.at
            val sleep = if (lastNightOut != null && thisMorning != null && !thisMorning.isBefore(lastNightOut)) {
                Duration.between(lastNightOut, thisMorning).toMinutes() / 60.0
            } else {
                null
            }

            val lastRide = rides.lastOrNull()?.second
            return DayState(
                day = day,
                zone = zone,
                rules = rules,
                stage = stage,
                checkIn = checkIns.lastOrNull()?.readOrNull<CheckIn>(),
                checkInAt = checkIns.firstOrNull()?.at,
                checkOut = checkOuts.lastOrNull()?.readOrNull<CheckOut>(),
                checkOutAt = checkOuts.lastOrNull()?.at,
                meals = meals,
                waterMl = water,
                rideOutAt = rides.firstOrNull { it.second.phase == RidePhase.OUT }?.first?.at,
                rideBackAt = rides.lastOrNull { it.second.phase == RidePhase.BACK }?.first?.at,
                income = rides.sumOf { it.second.income ?: 0 },
                riding = lastRide?.phase == RidePhase.OUT,
                tasks = tasks,
                drinks = today.filter { it.kind == Kind.DRINK }.mapNotNull { it.readOrNull<Drink>()?.type },
                mood = today.filter { it.kind == Kind.MOOD }.lastOrNull()?.readOrNull<Mood>(),
                body = body,
                restDay = restDay,
                sick = sick,
                injured = injured,
                sickDays = sickFrom?.let { (java.time.temporal.ChronoUnit.DAYS.between(it, day).toInt() + 1).coerceAtLeast(1) } ?: 0,
                firstDayBack = firstDayBack,
                examWeek = examWeek,
                sleepHours = sleep,
                violations = today.filter { it.kind == Kind.VIOLATION }.mapNotNull { it.readOrNull<Violation>() },
            )
        }
    }
}
