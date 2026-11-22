package app.agency.core.brain.handlers

import app.agency.core.engine.SignPolicy
import app.agency.core.engine.Signal
import app.agency.core.model.SignKind
import app.agency.core.model.SignLog
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.RuleEngineTime
import app.agency.core.schedule.SlotKey
import app.agency.core.store.AgencyStore
import app.agency.core.model.PlanSectionKey
import app.agency.core.model.Proposal
import app.agency.core.model.ProposalKind
import app.agency.core.model.ProposalStatus
import app.agency.core.model.RoleKey
import java.time.Instant
import java.time.ZoneId

/**
 * Sign-in, sign-out and the rides (arch T11b/T13, the minimum the links and the scheduler need; T19 adds the score and the
 * bedtime review). Each writes its sign row and returns the line plus the signal for the nerve (the row id is the signal id,
 * so one row is one signal).
 */
class Sign(
    private val store: AgencyStore,
    private val zone: ZoneId,
    private val newId: () -> String,
    private val plans: app.agency.core.schedule.PlanStore = app.agency.core.schedule.PlanStore(store),
) {
    /** [proposal]: the 「提早起床」 proposal made with this sign-in (T19), with its line. */
    data class Done(val line: String, val signal: Signal?, val proposal: Pair<String, String>? = null)

    private suspend fun today(day: String) = store.signs.onDay(day)

    suspend fun signIn(plan: DayPlan, at: Long): Done {
        val day = plan.day.toString()
        if (today(day).any { it.kind == SignKind.IN }) return Done("今天已經簽到了。", null)
        val wake = plan.slot(SlotKey.WAKE)?.let { RuleEngineTime.at(plan.day, it.minute, zone) }
        val now = Instant.ofEpochMilli(at)
        val onTime = wake?.let { SignPolicy.onTime(now, it) }
        val log = SignLog(newId(), day, at, SignKind.IN, onTime = onTime)
        store.signs.insert(log)
        val late = wake?.let { SignPolicy.minutesLate(now, it) } ?: 0
        val line = if (onTime == false) "簽到了，比表定晚 $late 分鐘。" else "簽到了，準時。"
        return Done(line, Signal.WokeUp(log.id, at), if (onTime == true) earlier(plan, at) else null)
    }

    /**
     * Two weeks of on-time class-day sign-ins (T19): a WAKE_EARLIER proposal, the class-day wake 10 minutes earlier, checked
     * like any plan change and applied only on his yes. Once per run: only sign-ins after the last such proposal count.
     */
    private suspend fun earlier(plan: DayPlan, at: Long): Pair<String, String>? {
        if (plan.type != app.agency.core.schedule.DayType.CLASS) return null
        val specs = plans.specs()
        val run = SignPolicy.earlierRun(specs.schedule.classDays.size)
        val asked = ProposalStatus.entries.flatMap { store.proposals.withStatus(it) }.filter { it.kind == ProposalKind.WAKE_EARLIER }
            .maxOfOrNull { it.createdAt }?.let { app.agency.core.rules.CoachRules.coachDay(Instant.ofEpochMilli(it), zone, store.profile.get()?.dayStartHour ?: 5) }
        val history = mutableListOf<Boolean?>()
        var d = plan.day
        while (history.size <= run && (asked == null || d.isAfter(asked)) && d.isAfter(plan.day.minusDays(LOOK_BACK_DAYS))) {
            if (app.agency.core.schedule.Timetable.type(d, specs.schedule) == app.agency.core.schedule.DayType.CLASS) {
                history += store.signs.onDay(d.toString()).firstOrNull { it.kind == SignKind.IN }?.onTime
            }
            d = d.minusDays(1)
        }
        if (!SignPolicy.earlierDue(history, run)) return null
        val now = specs.schedule.classWake
        val to = app.agency.core.schedule.DayClock.hhmm(app.agency.core.schedule.DayClock.minute(now) - SignPolicy.EARLIER_MIN)
        val checked = app.agency.core.schedule.PlanChanges.check(specs, PlanSectionKey.SCHEDULE, listOf(app.agency.core.schedule.PlanPath.CLASS_WAKE.key to to), plan.day)
            as? app.agency.core.schedule.PlanChanges.Checked.Ok ?: return null
        val id = newId()
        val reason = "因為連續兩週上課日都準時簽到，所以問你要不要每天提早 ${SignPolicy.EARLIER_MIN} 分鐘起床。"
        store.proposals.insert(
            Proposal(
                id, at, RoleKey.MANAGER, ProposalKind.WAKE_EARLIER, PlanSectionKey.SCHEDULE.name,
                app.agency.core.brain.ProposalApplier.JSON.encodeToString(app.agency.core.schedule.PlanChange.serializer(), checked.change), reason,
            ),
        )
        return id to "連續兩週上課日都準時簽到了。要不要每天提早 ${SignPolicy.EARLIER_MIN} 分鐘起床：$now → $to？你說「好」才改。"
    }

    suspend fun signOut(plan: DayPlan, at: Long): Done {
        val day = plan.day.toString()
        if (today(day).any { it.kind == SignKind.OUT }) return Done("今天已經簽退了。晚安。", null)
        val latest = plan.slot(SlotKey.SIGN_OUT)?.let { RuleEngineTime.at(plan.day, it.minute, zone) }
        val late = latest?.let { SignPolicy.lateSignOut(Instant.ofEpochMilli(at), it) } == true
        val log = SignLog(newId(), day, at, SignKind.OUT, onTime = !late)
        store.signs.insert(log)
        return Done(if (late) "簽退了，比最晚的時間晚了。明天早點睡。" else "簽退了。", Signal.SignedOut(log.id, at))
    }

    companion object {
        /** How far back the 「提早起床」 run is looked for. */
        const val LOOK_BACK_DAYS = 40L
    }

    suspend fun rideStart(plan: DayPlan, at: Long): Done {
        val signs = today(plan.day.toString())
        val lastStart = signs.filter { it.kind == SignKind.RIDE_START }.maxOfOrNull { it.at }
        val lastEnd = signs.filter { it.kind == SignKind.RIDE_END }.maxOfOrNull { it.at }
        if (lastStart != null && (lastEnd == null || lastEnd < lastStart)) return Done("已經在跑單了。騎車小心。", null)
        val log = SignLog(newId(), plan.day.toString(), at, SignKind.RIDE_START)
        store.signs.insert(log)
        val meal = plan.slot(SlotKey.RIDE_MEAL)?.let { "${it.time} 記得吃雞胸。" } ?: ""
        return Done("出門了。騎車小心，提醒只響一聲。$meal", Signal.RideStart(log.id, at))
    }

    suspend fun rideEnd(plan: DayPlan, at: Long): Done {
        val log = SignLog(newId(), plan.day.toString(), at, SignKind.RIDE_END)
        store.signs.insert(log)
        val strength = plan.slot(SlotKey.STRENGTH) != null && store.training.onDay(plan.day.toString()).none { it.kind == app.agency.core.model.TrainingKind.STRENGTH }
        return Done(if (strength) "收工了。放下安全帽，接著肌力。" else "收工了。", Signal.RideEnd(log.id, at))
    }
}
