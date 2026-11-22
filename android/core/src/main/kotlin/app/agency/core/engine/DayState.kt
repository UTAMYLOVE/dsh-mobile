package app.agency.core.engine

import app.agency.core.model.EffectKind
import app.agency.core.model.EffectStatus
import app.agency.core.model.NerveEffect
import app.agency.core.model.StoredSignal
import app.agency.core.model.TrainingKind
import app.agency.core.schedule.DayAdjust
import app.agency.core.schedule.DayFlag
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.Reduction
import app.agency.core.schedule.SlotKey
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * 今日狀態 (agency-links §2): recomputed from the signals every time (it can be replayed; nothing lives only in memory). [plan]
 * is today's plan with today's adjustments; history signals (the last [DayStateBuilder.LOOKBACK_DAYS] days) give last
 * night's sign-out, the illness streak, the last grooming dates and the weigh-ins.
 */
data class DayState(
    val day: LocalDate,
    val plan: DayPlan,
    val wokeAt: Long? = null,
    val signedOutAt: Long? = null,
    /** Last night's sign-out (the day before). */
    val lastNightOutAt: Long? = null,
    val ill: IllKind? = null,
    val illAsked: Boolean = false,
    val moodLow: Boolean = false,
    /** Days in a row (today included) with an illness or injury. */
    val illDaysInRow: Int = 0,
    val reduceAskedTimes: Int = 0,
    val hungryMorning: Boolean = false,
    val rideStartAt: Long? = null,
    val rideEndAt: Long? = null,
    val kcal: Int = 0,
    val proteinG: Double = 0.0,
    val mealSlots: Set<SlotKey> = emptySet(),
    val breaches: Int = 0,
    val waterHalves: Int = 0,
    val training: Set<TrainingKind> = emptySet(),
    val groomingLast: Map<GroomingItem, LocalDate> = emptyMap(),
    val weights: List<Pair<LocalDate, Double>> = emptyList(),
    /** Daily kcal of the lookback days that have meal reports (for L23's calibration). */
    val kcalByDay: Map<LocalDate, Int> = emptyMap(),
    val flags: Set<DayFlag> = emptySet(),
    /** A today-only illness change is proposed or accepted today. */
    val reductionProposed: Boolean = false,
    /** Per required slot, how many coach days in a row up to yesterday it ended undone (L14, from `day_missed`). */
    val missedInRow: Map<SlotKey, Int> = emptyMap(),
    /** The strength sessions of the lookback days (the first of each day), for L15. */
    val strengthSessions: Map<LocalDate, Progression.Session> = emptyMap(),
    /** Lookback days declared 「今天休息」. */
    val restDays: Set<LocalDate> = emptySet(),
    /** Lookback days he said he was ill or hurt. */
    val illDays: Set<LocalDate> = emptySet(),
    /** Daily protein of the lookback days that have meal reports (L15: the day before must reach the target). */
    val proteinByDay: Map<LocalDate, Double> = emptyMap(),
    /** A progression proposal (L15) waits for his answer (set by the nerve, from the proposals of every day). */
    val pendingProgression: Boolean = false,
    /** 撒嬌 already given today (L11: at most one a day). */
    val sweetToday: Int = 0,
    /** Photocards added to the wall today (L11). */
    val photocardsToday: Int = 0,
    /** This morning's 狀態打分 (T19), the last he gave. */
    val conditionScore: Int? = null,
    /** What he said last night to fix today, after his yes to the review's proposal (L19). */
    val fixToday: String? = null,
    /** The real-person task of this Mon–Sun week is done (L20). */
    val realTaskThisWeek: Boolean = false,
    /** 理論課 (T19c): lessons passed today. */
    val lessonsPassed: Int = 0,
    /** Lessons of 「課程清單」 still to pass (null: the course has not started), and 複習考 due today (set by the nerve). */
    val lessonsLeft: Int? = null,
    val reviewsDue: Int = 0,
) {
    val riding: Boolean get() = rideStartAt != null && (rideEndAt == null || rideEndAt < rideStartAt) && signedOutAt == null
    /** Ill, low, or a morning score of [LOW_SCORE] or less: she speaks gently (the plan stays, agency-links §3b). */
    val gentle: Boolean get() = ill != null || moodLow || (conditionScore != null && conditionScore <= LOW_SCORE)
    val rideMealEaten: Boolean get() = SlotKey.RIDE_MEAL in mealSlots

    /** Hours slept last night (sign-out → sign-in), when both are known. */
    val sleepHours: Double? get() = if (lastNightOutAt != null && wokeAt != null && wokeAt > lastNightOutAt) (wokeAt - lastNightOutAt) / 3_600_000.0 else null

    companion object {
        /** A morning score this low makes the day gentle (never lighter). */
        const val LOW_SCORE = 2
    }

    fun awakeHours(now: Instant): Double? = wokeAt?.let { Duration.between(Instant.ofEpochMilli(it), now).toMinutes() / 60.0 }
}

object DayStateBuilder {
    const val LOOKBACK_DAYS = 45L

    /** Today's adjustments in force: ACTIVE 今天微調 rows. */
    fun adjustments(effects: List<NerveEffect>): List<DayAdjust> = effects.filter { it.kind == EffectKind.ADJUST && it.status == EffectStatus.ACTIVE }
        .mapNotNull { (Nerve.decodeEffect(it.json) as? Effect.AdjustToday)?.change }

    /** The accepted illness change of today, if any. */
    fun reduction(effects: List<NerveEffect>): Reduction? = effects.filter { it.kind == EffectKind.PROPOSAL && it.status == EffectStatus.ACCEPTED }
        .firstNotNullOfOrNull { (Nerve.decodeEffect(it.json) as? Effect.Proposal)?.reduction }

    fun build(day: LocalDate, plan: DayPlan, signals: List<StoredSignal>, todayEffects: List<NerveEffect>): DayState {
        val d = day.toString()
        val decoded = signals.mapNotNull { s -> Signal.decode(s.json)?.let { s to it } }
        val today = decoded.filter { it.first.day == d }.map { it.second }
        val yesterday = day.minusDays(1).toString()
        var st = DayState(day, plan)
        today.forEach { s ->
            st = when (s) {
                is Signal.WokeUp -> if (st.wokeAt == null) st.copy(wokeAt = s.at) else st
                is Signal.SignedOut -> st.copy(signedOutAt = s.at)
                is Signal.Unwell -> when (s.kind) {
                    IllKind.UNKNOWN -> st.copy(illAsked = true)
                    else -> st.copy(ill = st.ill ?: s.kind)
                }
                is Signal.MoodLow -> st.copy(moodLow = true)
                is Signal.ReduceAsked -> st.copy(reduceAskedTimes = st.reduceAskedTimes + 1)
                is Signal.HungryMorning -> st.copy(hungryMorning = true)
                is Signal.RideStart -> st.copy(rideStartAt = s.at)
                is Signal.RideEnd -> st.copy(rideEndAt = s.at)
                is Signal.MealLogged -> st.copy(
                    kcal = st.kcal + s.kcal, proteinG = st.proteinG + s.proteinG, breaches = st.breaches + s.breaches.size,
                    mealSlots = st.mealSlots + listOfNotNull(s.slot),
                )
                is Signal.WaterLogged -> st.copy(waterHalves = st.waterHalves + s.halves)
                is Signal.TrainingDone -> st.copy(training = st.training + s.kind)
                is Signal.ConditionScored -> st.copy(conditionScore = s.score)
                is Signal.LessonPassed -> st.copy(lessonsPassed = st.lessonsPassed + 1)
                else -> st
            }
        }
        val lastOut = decoded.filter { it.first.day == yesterday && it.second is Signal.SignedOut }.maxOfOrNull { it.second.at }
        val illDays = decoded.filter { val x = it.second; x is Signal.Unwell && x.kind != IllKind.UNKNOWN }.map { LocalDate.parse(it.first.day) }.toSet()
        var streak = 0
        while (day.minusDays(streak.toLong()) in illDays) streak++
        val grooming = decoded.mapNotNull { (row, s) -> (s as? Signal.GroomingDone)?.let { it.item to LocalDate.parse(row.day) } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.max() }
        val weights = decoded.mapNotNull { (row, s) -> (s as? Signal.WeightLogged)?.let { LocalDate.parse(row.day) to it.kg } }
        val kcalByDay = decoded.mapNotNull { (row, s) -> (s as? Signal.MealLogged)?.let { LocalDate.parse(row.day) to it.kcal } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sum() }
        val missedDays = decoded.mapNotNull { (_, s) -> (s as? Signal.DayMissed)?.let { LocalDate.parse(it.day) to it.keys.toSet() } }.toMap()
        val missedInRow = missedDays.values.flatten().toSet().associateWith { key ->
            var n = 0
            while (missedDays[day.minusDays(n + 1L)]?.contains(key) == true) n++
            n
        }.filterValues { it > 0 }
        val sessions = decoded.mapNotNull { (row, s) -> (s as? Signal.TrainingDone)?.takeIf { it.kind == TrainingKind.STRENGTH }?.let { LocalDate.parse(row.day) to it } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.minBy { it.at }.let { Progression.Session(it.easy, it.step) } }
        val restDays = decoded.filter { it.second is Signal.RestDay }.map { LocalDate.parse(it.first.day) }.toSet()
        val proteinByDay = decoded.mapNotNull { (row, s) -> (s as? Signal.MealLogged)?.let { LocalDate.parse(row.day) to it.proteinG } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sum() }
        val fix = decoded.mapNotNull { (_, s) -> (s as? Signal.FixPlanned)?.takeIf { it.forDay == d } }.maxByOrNull { it.at }?.text
        val monday = day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val realTask = decoded.any { (row, s) -> s is Signal.RealTaskDone && !LocalDate.parse(row.day).isBefore(monday) && !LocalDate.parse(row.day).isAfter(day) }
        val l11 = todayEffects.filter { it.link == Links.L11Completion.id && it.kind == EffectKind.RECORD }
        val flags = adjustments(todayEffects).filterIsInstance<DayAdjust.Flag>().map { it.flag }.toSet()
        val proposed = todayEffects.any {
            it.kind == EffectKind.PROPOSAL && it.status in setOf(EffectStatus.PROPOSED, EffectStatus.ACCEPTED) &&
                (Nerve.decodeEffect(it.json) as? Effect.Proposal)?.reduction != null
        }
        return st.copy(
            lastNightOutAt = lastOut, illDaysInRow = streak, groomingLast = grooming, weights = weights, kcalByDay = kcalByDay,
            flags = flags, reductionProposed = proposed, missedInRow = missedInRow,
            strengthSessions = sessions, restDays = restDays, illDays = illDays, proteinByDay = proteinByDay,
            fixToday = fix, realTaskThisWeek = realTask,
            sweetToday = l11.count { it.because == Links.L11Completion.SWEET }, photocardsToday = l11.count { it.because == Links.L11Completion.PHOTOCARD },
        )
    }
}
