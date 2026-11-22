package app.agency.core.brain.handlers

import app.agency.core.engine.SignPolicy
import app.agency.core.engine.Signal
import app.agency.core.model.SignKind
import app.agency.core.model.SignLog
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.RuleEngineTime
import app.agency.core.schedule.SlotKey
import app.agency.core.store.AgencyStore
import java.time.Instant
import java.time.ZoneId

/**
 * Sign-in, sign-out and the rides (arch T11b/T13, the minimum the links and the scheduler need; T19 adds the score and the
 * bedtime review). Each writes its sign row and returns the line plus the signal for the nerve (the row id is the signal id,
 * so one row is one signal).
 */
class Sign(private val store: AgencyStore, private val zone: ZoneId, private val newId: () -> String) {
    data class Done(val line: String, val signal: Signal?)

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
        return Done(line, Signal.WokeUp(log.id, at))
    }

    suspend fun signOut(plan: DayPlan, at: Long): Done {
        val day = plan.day.toString()
        if (today(day).any { it.kind == SignKind.OUT }) return Done("今天已經簽退了。晚安。", null)
        val latest = plan.slot(SlotKey.SIGN_OUT)?.let { RuleEngineTime.at(plan.day, it.minute, zone) }
        val late = latest?.let { SignPolicy.lateSignOut(Instant.ofEpochMilli(at), it) } == true
        val log = SignLog(newId(), day, at, SignKind.OUT, onTime = !late)
        store.signs.insert(log)
        return Done(if (late) "簽退了，比最晚的時間晚了。明天早點睡。晚安。" else "簽退了。晚安，明天見。", Signal.SignedOut(log.id, at))
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
