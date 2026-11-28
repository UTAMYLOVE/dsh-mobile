package app.agency.core.day

import app.agency.core.base.Days
import app.agency.core.base.TestClock
import app.agency.core.rules.Fixtures
import app.agency.core.rules.Rules
import app.agency.core.stage.StageState
import app.agency.core.store.CheckIn
import app.agency.core.store.CheckOut
import app.agency.core.store.Drink
import app.agency.core.store.DrinkType
import app.agency.core.store.Entry
import app.agency.core.store.FoodSource
import app.agency.core.store.Kind
import app.agency.core.store.Meal
import app.agency.core.store.MealItem
import app.agency.core.store.MealSlot
import app.agency.core.store.MemStore
import app.agency.core.store.Ride
import app.agency.core.store.RidePhase
import app.agency.core.store.Status
import app.agency.core.store.StatusType
import app.agency.core.store.TaskDone
import app.agency.core.store.Water
import app.agency.core.store.add
import app.agency.core.plan.TaskId
import java.time.Instant
import java.time.LocalDate

/** Shared helpers for the day and plan tests. Everything here is invented data. */
object Kit {
    val rules: Rules = Fixtures.rules()
    val zone = TestClock.ZONE

    /** A Monday (class day). */
    val monday: LocalDate = TestClock.MONDAY
    val tuesday: LocalDate = monday.plusDays(1)
    val wednesday: LocalDate = monday.plusDays(2)
    val thursday: LocalDate = monday.plusDays(3)
    val friday: LocalDate = monday.plusDays(4)
    val saturday: LocalDate = monday.plusDays(5)
    val sunday: LocalDate = monday.plusDays(6)

    /** Stage [n] that opened three days before [day], joined the same day: day number 4, no weekly shifts yet. */
    fun stage(n: Int, day: LocalDate = monday): StageState =
        StageState(joinedOn = day.minusDays(3).toString(), stage = n, startedOn = day.minusDays(3).toString())

    fun at(day: LocalDate, hhmm: String): Instant = Days.at(day, java.time.LocalTime.parse(hhmm), zone)

    fun state(store: MemStore, day: LocalDate, stage: StageState, rules: Rules = Kit.rules): DayState =
        DayState.of(day, store.entries(day), store.entries(day.minusDays(14), day.minusDays(1)), rules, stage, zone)

    fun checkIn(s: MemStore, day: LocalDate, hhmm: String, score: Int = 4): Entry =
        s.add(day, at(day, hhmm), Kind.CHECK_IN, CheckIn(score))

    fun checkOut(s: MemStore, day: LocalDate, hhmm: String): Entry = s.add(day, at(day, hhmm), Kind.CHECK_OUT, CheckOut("好", "再早睡"))

    fun item(name: String, kcal: Int, protein: Double = 10.0, source: FoodSource = FoodSource.TABLE, banned: String? = null) =
        MealItem(name, kcal, protein, source, banned)

    fun meal(
        s: MemStore,
        day: LocalDate,
        hhmm: String,
        slot: MealSlot,
        kcal: Int,
        photo: Boolean = true,
        late: Boolean = false,
        name: String = "測試便當",
        banned: String? = null,
    ): Entry = s.add(
        day, at(day, hhmm), Kind.MEAL,
        Meal(slot, listOf(item(name, kcal, 30.0, banned = banned)), if (photo) "p1" else null, photoLate = late),
    )

    fun water(s: MemStore, day: LocalDate, hhmm: String, ml: Int): Entry = s.add(day, at(day, hhmm), Kind.WATER, Water(ml))

    fun ride(s: MemStore, day: LocalDate, hhmm: String, phase: RidePhase, income: Int? = null): Entry =
        s.add(day, at(day, hhmm), Kind.RIDE, Ride(phase, income))

    fun status(s: MemStore, day: LocalDate, hhmm: String, type: StatusType): Entry =
        s.add(day, at(day, hhmm), Kind.STATUS, Status(type))

    fun task(s: MemStore, day: LocalDate, hhmm: String, task: TaskId, rounds: Int? = null): Entry =
        s.add(day, at(day, hhmm), Kind.TASK, TaskDone(task.name, rounds = rounds))

    fun drink(s: MemStore, day: LocalDate, hhmm: String, type: DrinkType): Entry =
        s.add(day, at(day, hhmm), Kind.DRINK, Drink(type))
}
