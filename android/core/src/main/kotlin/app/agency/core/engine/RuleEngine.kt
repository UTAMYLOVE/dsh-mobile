package app.agency.core.engine

import app.agency.core.brain.TodayRecords
import app.agency.core.model.SignKind
import app.agency.core.model.TrainingKind
import app.agency.core.schedule.DayPlan
import app.agency.core.schedule.Slot
import app.agency.core.schedule.SlotKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** done: a record is there; later: not due yet; open: due and still missing; missed: the day is over or he signed out. */
enum class SlotStatus(val zh: String) { DONE("完成"), LATER("還沒到"), OPEN("還沒回報"), MISSED("沒做到") }

/** One slot of today with its status; [dueAt] is when it became (or becomes) due; [recordIds] what filled it. */
data class SlotState(val slot: Slot, val status: SlotStatus, val dueAt: Instant, val recordIds: List<String> = emptyList()) {
    val key: SlotKey get() = slot.key

    /** A required slot that is due and missing: a gap (reminders and chasing, T13). */
    val isGap: Boolean get() = slot.required && status == SlotStatus.OPEN
}

/**
 * Slot status from today's records (arch 1.5 `RuleEngine`), pure. A meal fills the slot it was recorded for; sign-in, rides and
 * sign-out come from the sign log; training from its kind. Strength and stretch are due when he gets home (收工) or at their
 * latest start. After sign-out, or once the coach day is over, what is missing is missed (no gap: nothing is chased then).
 */
object RuleEngine {
    fun instant(day: LocalDate, minute: Int, zone: ZoneId): Instant = day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant()

    fun status(plan: DayPlan, r: TodayRecords, now: Instant, zone: ZoneId, dayStartHour: Int = 5): List<SlotState> {
        val dayEnd = plan.day.plusDays(1).atStartOfDay(zone).plusHours(dayStartHour.toLong()).toInstant()
        val signedOut = r.signs.filter { it.kind == SignKind.OUT }.minByOrNull { it.at }?.let { Instant.ofEpochMilli(it.at) }
        val rideEnd = r.signs.filter { it.kind == SignKind.RIDE_END }.maxByOrNull { it.at }?.let { Instant.ofEpochMilli(it.at) }
        val strength = r.training.filter { it.kind == TrainingKind.STRENGTH }.minByOrNull { it.at }
        val cardio = r.training.filter { it.kind == TrainingKind.CARDIO }.minByOrNull { it.at }
        val cardioPlanned = plan.slot(SlotKey.CARDIO) != null
        return plan.slots.map { sl ->
            // A window slot (the run: 18:00–19:00) is due at its end: by 19:00 he is out, or the chase starts (owner 2026-11-27).
            val latest = instant(plan.day, sl.untilMinute ?: sl.minute, zone)
            val due = when {
                // The stretch closes the block: due a while after its last item (the rounds, or the cardio on its days).
                sl.key == SlotKey.STRETCH && cardioPlanned && cardio != null -> Instant.ofEpochMilli(cardio.at).plusSeconds(15L * 60)
                sl.key == SlotKey.STRETCH && !cardioPlanned && strength != null -> Instant.ofEpochMilli(strength.at).plusSeconds(30L * 60)
                sl.afterRide && rideEnd != null -> minOf(rideEnd.plusSeconds(sl.afterRideMin * 60L), latest)
                else -> latest
            }
            val ids = records(sl.key, r)
            val status = when {
                ids.size >= (sl.count ?: 1) -> SlotStatus.DONE
                now >= dayEnd || (signedOut != null && sl.key != SlotKey.SIGN_OUT) -> SlotStatus.MISSED
                now < due -> SlotStatus.LATER
                else -> SlotStatus.OPEN
            }
            SlotState(sl, status, due, ids)
        }
    }

    /** The records that fill [key] today. */
    fun records(key: SlotKey, r: TodayRecords): List<String> = when (key) {
        SlotKey.WAKE -> r.signs.filter { it.kind == SignKind.IN }.map { it.id }
        SlotKey.SIGN_OUT -> r.signs.filter { it.kind == SignKind.OUT }.map { it.id }
        SlotKey.RIDE_OUT -> r.signs.filter { it.kind == SignKind.RIDE_START }.map { it.id }
        SlotKey.CORE -> r.training.filter { it.kind == TrainingKind.CORE7 }.map { it.id }
        SlotKey.STRENGTH -> r.training.filter { it.kind == TrainingKind.STRENGTH }.map { it.id }
        SlotKey.STRETCH -> r.training.filter { it.kind == TrainingKind.STRETCH }.map { it.id }
        SlotKey.WALK -> r.training.filter { it.kind == TrainingKind.WALK }.map { it.id }
        SlotKey.CARDIO -> r.training.filter { it.kind == TrainingKind.CARDIO }.map { it.id }
        SlotKey.LESSONS -> r.lessonsPassed.map { it.id }
        SlotKey.LESSON_REVIEW -> r.lessonsReviewed.map { it.id }
        else -> r.meals.filter { it.slotKey == key.name }.map { it.id }
    }

    /** The required slots that are due and missing now. */
    fun gaps(states: List<SlotState>): List<SlotState> = states.filter { it.isGap }
}
