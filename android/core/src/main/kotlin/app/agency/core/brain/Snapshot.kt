package app.agency.core.brain

import app.agency.core.model.CompanyRules
import app.agency.core.model.Message
import app.agency.core.model.Profile
import app.agency.core.engine.RuleEngine
import app.agency.core.engine.SlotState
import app.agency.core.rules.CoachRules
import app.agency.core.schedule.DayAdjust
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.PlanStore
import app.agency.core.schedule.Reduction
import app.agency.core.store.AgencyStore
import app.agency.core.time.AgencyClock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Reads what a reply may know from the store, all computed by code: the rules (plan v3 defaults until he edits them), his
 * profile, today's records, the daily summaries of the last [FactsBuilder.MAX_SUMMARIES] days and his latest weight. Days are
 * coach days (a day starts at `profile.dayStartHour`, 05:00 by default, in [clock]'s zone).
 */
class SnapshotReader(
    private val store: AgencyStore,
    private val clock: AgencyClock,
    /** Today's 今天微調 and the accepted illness change (the nerve, T11b); none without it. */
    private val todayChanges: suspend (LocalDate) -> Pair<List<DayAdjust>, Reduction?> = { emptyList<DayAdjust>() to null },
) {
    val plans = PlanStore(store)

    /** [day]'s plan with its adjustments. */
    suspend fun plan(day: LocalDate): DayPlan {
        val (adjust, reduction) = todayChanges(day)
        return plans.plan(day, adjust, reduction)
    }

    /** [day]'s slots with their status now (arch 1.5 `RuleEngine`). */
    suspend fun slots(day: LocalDate, records: TodayRecords, profile: Profile): List<SlotState> =
        RuleEngine.status(plan(day), records, clock.now(), clock.zone, profile.dayStartHour)

    suspend fun rules(): CompanyRules = store.rules.current() ?: CompanyRules()
    suspend fun profile(): Profile = store.profile.get() ?: Profile()

    /** The coach day of [at] for [profile]. */
    fun dayOf(at: Instant, profile: Profile): LocalDate = CoachRules.coachDay(at, clock.zone, profile.dayStartHour)

    fun today(profile: Profile): LocalDate = dayOf(clock.now(), profile)

    suspend fun records(day: LocalDate): TodayRecords {
        val d = day.toString()
        return TodayRecords(
            day = d,
            meals = store.meals.onDay(d),
            water = store.water.onDay(d),
            training = store.training.onDay(d),
            signs = store.signs.onDay(d),
            condition = store.conditions.get(d),
            weights = store.weights.onDay(d),
        )
    }

    /** One summary per day before [today], newest first, [days] of them (days with nothing reported included). */
    suspend fun summaries(today: LocalDate, days: Int = FactsBuilder.MAX_SUMMARIES): List<DaySummary> {
        val from = today.minusDays(days.toLong())
        val meals = store.meals.between(from.toString(), today.minusDays(1).toString()).groupBy { it.day }
        return (1..days).map { back ->
            val d = today.minusDays(back.toLong()).toString()
            val m = meals[d].orEmpty()
            val breaches = m.sumOf { it.breaches.size }
            DaySummary(
                day = d,
                reported = m.isNotEmpty(),
                kcal = m.sumOf { it.kcal },
                proteinG = m.sumOf { meal -> meal.items.sumOf { it.proteinG ?: 0.0 } },
                breaches = breaches,
                onPlan = m.isNotEmpty() && breaches == 0,
            )
        }
    }

    /** His latest weight up to [today], else the start weight he gave. */
    suspend fun currentWeightKg(today: LocalDate, profile: Profile): Double? =
        store.weights.between(EARLIEST, today.toString()).maxByOrNull { it.at }?.kg ?: profile.startWeightKg

    /** The whole input of [FactsBuilder] for a reply now; [history] is the stored conversation before this turn. */
    suspend fun facts(history: List<Message>): FactsInput {
        val profile = profile()
        val today = today(profile)
        val records = records(today)
        return FactsInput(
            rules = rules(),
            profile = profile,
            currentWeightKg = currentWeightKg(today, profile),
            today = records,
            summaries = summaries(today),
            history = history,
            zone = clock.zone,
            slots = slots(today, records, profile).map { SlotFact(it.key.name.lowercase(), it.slot.label, it.slot.time, it.status.name.lowercase()) },
            waterTargetHalves = app.agency.core.engine.WaterPlan.target(today, plans.specs().water, profile.joinDate),
        )
    }

    /** The Monday that starts the week of [day]. */
    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun time(at: Long): String = HHMM.format(Instant.ofEpochMilli(at).atZone(clock.zone))

    companion object {
        const val EARLIEST = "0000-01-01"
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    }
}
