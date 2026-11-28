package app.agency.core.extras

import app.agency.core.base.Clock
import app.agency.core.base.Days
import app.agency.core.day.DayState
import app.agency.core.remind.Notice
import app.agency.core.rules.Rules
import app.agency.core.store.Entry
import app.agency.core.store.Kind
import app.agency.core.store.Store
import app.agency.core.store.add
import app.agency.core.store.update
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random
import kotlinx.serialization.Serializable

/** What a surprise check asks a photo of. */
@Serializable
enum class SpotAsk(val zh: String) { POSTURE("站姿"), BOTTLE("水壺"), OUTFIT("穿搭") }

@Serializable
enum class SpotStatus { ASKED, DONE, MISSED }

/** A [Kind.SPOT_CHECK] payload. A missed check is recorded, never a violation. */
@Serializable
data class SpotCheckRec(
    val key: String,
    val ask: SpotAsk,
    val askedAt: String,
    val status: SpotStatus = SpotStatus.ASKED,
    val photoId: String? = null,
    val answeredAt: String? = null,
)

/** One planned check: the day, the earliest time it may be asked, and what it asks. */
data class SpotSlot(val day: LocalDate, val at: LocalTime, val ask: SpotAsk)

/** A check that is due now. [key] is also the notice key. */
data class SpotCheck(val key: String, val day: LocalDate, val ask: SpotAsk, val plannedAt: Instant)

/**
 * Surprise checks: two or three a calendar week at moments that look random to him but are fixed by a seed (week start plus a
 * per-install salt kept in a document), so they can be tested and never change between two looks.
 *
 * A planned moment is only a "not before": the check is asked at the first moment from then on that is allowed ([allowed]):
 * class days between class end and leave-by; after he is back from the ride; free days in the daytime after check-in and before
 * leave-by. Never while riding, before check-in or after check-out, and never late in the evening. If he left before the planned
 * moment, the check waits for his return.
 */
class SpotChecks(
    private val store: Store,
    private val clock: Clock,
    private val rules: Rules,
    private val newSalt: () -> Long = { clock.now().toEpochMilli() },
) {

    private fun classDay(day: LocalDate) = day.dayOfWeek.value in rules.classWeekdays

    private fun times(day: LocalDate) = if (classDay(day)) rules.classDay else rules.freeDay

    private fun minute(hhmm: String): Int? = Days.parse(hhmm)?.let { Days.minuteOfDay(it) }

    /** The static window of [day] in minutes of the logical day (start inclusive, end exclusive) the planner draws from. */
    private fun window(day: LocalDate): Pair<Int, Int>? {
        val t = times(day)
        val leave = minute(t.leaveBy) ?: return null
        val first = (if (classDay(day)) minute(t.classEnd) else minute(t.wake)?.plus(FREE_AFTER_WAKE)) ?: return null
        val start = first
        val end = leave - BEFORE_LEAVE
        return if (end - start >= MIN_WINDOW) start to end else null
    }

    fun salt(): Long {
        store.doc(SALT_DOC)?.trim()?.toLongOrNull()?.let { return it }
        return newSalt().also { store.putDoc(SALT_DOC, it.toString()) }
    }

    /** The checks of the calendar week starting [weekStart] (a Monday): 2 or 3, on different days, oldest first. */
    fun plan(weekStart: LocalDate): List<SpotSlot> {
        val start = weekStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val rnd = Random(start.toEpochDay() * 1_000_003L + salt() * 7919L)
        val count = 2 + rnd.nextInt(2)
        val eligible = (0L..6L).map { start.plusDays(it) }.filter { window(it) != null }
        return eligible.shuffled(rnd).take(count).sorted().map { d ->
            val (s, e) = window(d)!!
            val at = LocalTime.of(Days.START_HOUR, 0).plusMinutes((s + rnd.nextInt(e - s)).toLong())
            SpotSlot(d, at, SpotAsk.values()[rnd.nextInt(SpotAsk.values().size)])
        }
    }

    /** Whether a check may be asked at [now] given what [state] shows of that day. */
    fun allowed(now: Instant, state: DayState): Boolean {
        val zone = state.zone
        if (Days.of(now, zone) != state.day) return false
        val checkIn = state.checkInAt ?: return false
        if (now < checkIn) return false
        val out = state.checkOutAt
        if (out != null && now >= out) return false
        if (state.riding) return false
        val t = times(state.day)
        val nowMin = Days.minuteOfDay(now, zone)
        val bed = minute(t.bedtime)
        if (bed != null && nowMin > bed - LAST_BEFORE_BED) return false
        val leave = minute(t.leaveBy)
        val left = state.rideOutAt?.let { it <= now } == true
        val back = state.rideBackAt?.let { it <= now } == true
        if (back) return true
        if (left) return false
        if (leave == null || nowMin >= leave) return false
        if (classDay(state.day)) {
            val end = minute(t.classEnd) ?: return false
            return nowMin >= end
        }
        return true
    }

    fun todays(day: LocalDate): List<Pair<Entry, SpotCheckRec>> =
        store.entries(day, Kind.SPOT_CHECK).mapNotNull { e -> runCatching { e.read<SpotCheckRec>() }.getOrNull()?.let { e to it } }

    private fun keyOf(day: LocalDate) = "spot:$day"

    /** The check to ask now, or null. Pure: asking again before [issue] records it gives the same answer. */
    fun due(now: Instant, state: DayState): SpotCheck? {
        val day = state.day
        val slot = plan(day).firstOrNull { it.day == day } ?: return null
        if (todays(day).any { it.second.key == keyOf(day) }) return null
        val at = Days.at(day, slot.at, state.zone)
        if (now < at || !allowed(now, state)) return null
        return SpotCheck(keyOf(day), day, slot.ask, at)
    }

    /** The loud notice that asks for the photo. */
    fun notice(check: SpotCheck): Notice = Notice(
        check.key, "突擊檢查", "現在拍一張你的${check.ask.zh}照片傳給我，十分鐘內要交喔", loud = true,
    )

    /** Marks checks asked more than ten minutes ago and still unanswered as missed. */
    fun expire(now: Instant) {
        val day = Days.of(now, clock.zone)
        store.entries(day.minusDays(1), day, Kind.SPOT_CHECK).forEach { e ->
            val rec = runCatching { e.read<SpotCheckRec>() }.getOrNull() ?: return@forEach
            if (rec.status == SpotStatus.ASKED && expired(rec, now)) store.update(e.id, rec.copy(status = SpotStatus.MISSED))
        }
    }

    private fun expired(rec: SpotCheckRec, now: Instant): Boolean {
        val asked = runCatching { Instant.parse(rec.askedAt) }.getOrNull() ?: return true
        return now > asked.plus(ANSWER_WITHIN)
    }

    /** Asks the check that is due now: records it and returns its notice; null when nothing is due. */
    fun issue(now: Instant, state: DayState): Notice? {
        expire(now)
        val check = due(now, state) ?: return null
        store.add(state.day, now, Kind.SPOT_CHECK, SpotCheckRec(check.key, check.ask, now.toString()))
        return notice(check)
    }

    /**
     * His photo arrived. In time (within ten minutes of the ask) the latest open check is DONE; too late it is MISSED. Null when
     * there is no check to answer (nothing was asked, or it was already answered).
     */
    fun answer(photoId: String): SpotCheckRec? {
        val now = clock.now()
        val day = Days.of(now, clock.zone)
        val latest = (todays(day.minusDays(1)) + todays(day)).lastOrNull() ?: return null
        val (entry, rec) = latest
        return when (rec.status) {
            SpotStatus.DONE -> null
            SpotStatus.MISSED -> rec
            SpotStatus.ASKED -> {
                val next = if (expired(rec, now)) rec.copy(status = SpotStatus.MISSED)
                else rec.copy(status = SpotStatus.DONE, photoId = photoId, answeredAt = now.toString())
                store.update(entry.id, next)
                next
            }
        }
    }

    companion object {
        const val SALT_DOC = "spot_salt"
        val ANSWER_WITHIN: Duration = Duration.ofMinutes(10)
        private const val FREE_AFTER_WAKE = 30
        private const val BEFORE_LEAVE = 15
        private const val MIN_WINDOW = 15
        private const val LAST_BEFORE_BED = 60
    }
}
