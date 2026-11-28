package app.agency.core.remind

import app.agency.core.base.Days
import app.agency.core.lessons.LessonAway
import app.agency.core.plan.DayPlan
import app.agency.core.plan.ItemStatus
import app.agency.core.plan.waiting
import app.agency.core.store.Memo
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * What was already sent on logical [day]: key (a plan item id, `memo:<id>`, `lesson_away:<id>`, a spot-check key) to the
 * ISO instants it was sent. A value the caller persists; a log of another day counts as empty.
 */
@Serializable
data class ReminderLog(val day: String = "", val sent: Map<String, List<String>> = emptyMap()) {
    fun times(key: String): List<Instant> = sent[key].orEmpty().mapNotNull { runCatching { Instant.parse(it) }.getOrNull() }

    fun with(key: String, at: Instant): ReminderLog = copy(sent = sent + (key to (sent[key].orEmpty() + at.toString())))
}

/** One notification. [loud] = sound and heads-up; otherwise a silent update. */
data class Notice(val key: String, val title: String, val text: String, val loud: Boolean)

/**
 * When the reminders of an item without a time start (SPEC F, untimed items): a lesson at class end on a class day, the training
 * block half an hour after the ride back (two hours after sign-in on a day without riding), anything else three hours after
 * sign-in. [checkInAt] is the sign-in, or the day's wake time when signing in is not open. [quietFrom] is the rest-day
 * evening (dinner), from which nothing untimed is chased. All null = untimed items are never reminded.
 */
data class Anchors(
    val checkInAt: Instant? = null,
    val rideBackAt: Instant? = null,
    val rideDay: Boolean = false,
    val classEndAt: Instant? = null,
    val quietFrom: Instant? = null,
)

/** [log] is the new log to persist; [nextWakeAt] is when to look again (never more than six hours ahead). */
data class Decision(val notices: List<Notice>, val nextWakeAt: Instant, val log: ReminderLog)

/**
 * What notification is due now. Pure: all time comes in as parameters and everything it sends is recorded in the returned log,
 * so asking twice at the same instant with the new log gives nothing the second time.
 *
 * Rules: an item turning due gets one notice; while LATE and chased it repeats every 30 minutes until done, until check-out,
 * or until two hours after bedtime. While riding only the first notice of an item is loud. Nothing before check-in except the
 * wake item itself; nothing after check-out. Memos with a due time and a lesson he has not come back from get one notice each.
 */
object Reminders {
    val CHASE_EVERY: Duration = Duration.ofMinutes(30)
    val QUIET_AFTER_BED: Duration = Duration.ofHours(2)
    val MAX_SLEEP: Duration = Duration.ofHours(6)

    /** The plan item id of the morning sign-in, the only thing allowed before check-in. */
    const val WAKE_ITEM = "check_in"
    const val LESSON_AWAY_GRACE_MINUTES = 5L
    const val LESSON_AWAY_UNKNOWN_MINUTES = 20L

    /**
     * [extra] is the hook for notices decided elsewhere (the spot checks): they are added unchanged, once per key, and
     * recorded in the log. [memos] are `(entry id, memo)`; [lessonAway] is null when he is not out watching something.
     */
    fun decide(
        now: Instant,
        zone: ZoneId,
        plan: DayPlan,
        checkedIn: Boolean,
        checkedOut: Boolean,
        bedtime: LocalTime?,
        memos: List<Pair<Long, Memo>>,
        lessonAway: LessonAway?,
        log: ReminderLog,
        extra: List<Notice> = emptyList(),
        anchors: Anchors = Anchors(),
    ): Decision {
        val day = plan.day
        var log1 = if (log.day == day.toString()) log else ReminderLog(day.toString())
        val dayEnd = day.plusDays(1).atTime(Days.START_HOUR, 0).atZone(zone).toInstant()
        val cutoff = bedtime?.let { minOf(Days.at(day, it, zone).plus(QUIET_AFTER_BED), dayEnd) } ?: dayEnd
        val itemsOpen = !checkedOut && now < cutoff
        val notices = ArrayList<Notice>()
        val wake = ArrayList<Instant>()
        // Untimed items that want a notice now: only one goes out per wake (the next thing first), so they never flood.
        val untimedDue = ArrayList<Pair<app.agency.core.plan.PlanItem, Int>>()

        if (itemsOpen) {
            for (item in plan.items) {
                if (!checkedIn && item.id != WAKE_ITEM) continue
                if (item.status != ItemStatus.PENDING && item.status != ItemStatus.LATE) continue
                if (item.at == null && item.until == null) {
                    val start = untimedStart(item, anchors) ?: continue
                    if (anchors.quietFrom != null && now >= anchors.quietFrom) continue
                    if (start > now) {
                        wake += start
                        continue
                    }
                    val sent = log1.times(item.id)
                    val next = sent.maxOrNull()?.plus(CHASE_EVERY)
                    if (next == null || next <= now) untimedDue += item to sent.size else wake += next
                    continue
                }
                // A deadline item (water before leaving, leaving itself) turns due at its deadline.
                val at = item.at ?: item.until ?: continue
                val due = Days.at(day, at, zone)
                if (due > now) {
                    wake += due
                    continue
                }
                val sent = log1.times(item.id)
                if (sent.isEmpty()) {
                    notices += Notice(item.id, item.title, Lines.first(day, item.id, item.title), loud = true)
                    log1 = log1.with(item.id, now)
                    if (item.chase) wake += now.plus(CHASE_EVERY)
                } else if (item.chase) {
                    val next = sent.max().plus(CHASE_EVERY)
                    if (item.status == ItemStatus.LATE && next <= now) {
                        notices += Notice(item.id, item.title, Lines.chase(day, item.id, item.title, sent.size), loud = !plan.riding)
                        log1 = log1.with(item.id, now)
                        wake += now.plus(CHASE_EVERY)
                    } else {
                        wake += next
                    }
                }
            }
        }

        if (untimedDue.isNotEmpty()) {
            val (item, count) = untimedDue.firstOrNull { it.first.id == plan.next?.id } ?: untimedDue.first()
            val text = if (count == 0) Lines.first(day, item.id, item.title) else Lines.chase(day, item.id, item.title, count)
            notices += Notice(item.id, item.title, text, loud = count == 0 || !plan.riding)
            log1 = log1.with(item.id, now)
            wake += now.plus(CHASE_EVERY)
            // The others wait for the next wake a few minutes on.
            if (untimedDue.size > 1) wake += now.plus(UNTIMED_GAP)
        }

        if (!checkedOut) {
            for ((id, memo) in memos) {
                val key = "memo:$id"
                if (memo.done || memo.notified || log1.times(key).isNotEmpty()) continue
                val due = memo.dueAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: continue
                if (due <= now) {
                    notices += Notice(key, "備忘", Lines.memo(day, key, memo.text), loud = true)
                    log1 = log1.with(key, now)
                } else {
                    wake += due
                }
            }
            if (lessonAway != null) {
                val key = "lesson_away:${lessonAway.lessonId}"
                if (log1.times(key).isEmpty()) {
                    val wait = lessonAway.minutes?.let { it + LESSON_AWAY_GRACE_MINUTES } ?: LESSON_AWAY_UNKNOWN_MINUTES
                    val due = lessonAway.openedAt.plus(Duration.ofMinutes(wait))
                    if (due <= now) {
                        notices += Notice(key, "理論課", Lines.lessonAway(day, key, lessonAway.title), loud = true)
                        log1 = log1.with(key, now)
                    } else {
                        wake += due
                    }
                }
            }
        }

        for (n in extra) {
            if (log1.times(n.key).isEmpty()) {
                notices += n
                log1 = log1.with(n.key, now)
            }
        }

        if (now < dayEnd) wake += dayEnd
        val limit = now.plus(MAX_SLEEP)
        val next = wake.filter { it > now }.minOrNull()?.let { minOf(it, limit) } ?: limit
        return Decision(notices, next, log1)
    }

    /** Between two untimed notices in a row. */
    val UNTIMED_GAP: Duration = Duration.ofMinutes(5)

    private val TRAINING = setOf("task:CORE", "task:STRENGTH", "task:STRETCH", "task:CARDIO")

    /** When an untimed item starts being reminded, or null when it never is (waiting, not counted, not chased). */
    fun untimedStart(item: app.agency.core.plan.PlanItem, a: Anchors): Instant? {
        if (!item.chase || !item.counts || item.status != ItemStatus.PENDING) return null
        if (item.waiting) return null
        val sign = a.checkInAt ?: return null
        return when {
            item.id.startsWith("lesson:") -> a.classEndAt ?: sign.plus(Duration.ofHours(2))
            item.id in TRAINING -> if (a.rideDay) a.rideBackAt?.plus(Duration.ofMinutes(30)) else sign.plus(Duration.ofHours(2))
            else -> sign.plus(Duration.ofHours(3))
        }
    }

    /** The logical day a given instant falls on, for callers that need to pick the plan's day. */
    fun dayOf(now: Instant, zone: ZoneId): LocalDate = Days.of(now, zone)
}
