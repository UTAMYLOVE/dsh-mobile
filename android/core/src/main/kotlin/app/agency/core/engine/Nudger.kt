package app.agency.core.engine

import app.agency.core.model.EffectStatus
import app.agency.core.model.Message
import app.agency.core.model.NerveEffect
import app.agency.core.model.Nudge
import app.agency.core.model.RoleKey
import app.agency.core.model.Who
import app.agency.core.model.WordedBy
import app.agency.core.schedule.RuleEngineTime
import app.agency.core.schedule.SlotKey
import app.agency.core.schedule.SlotKind
import java.time.Duration
import java.time.Instant

/** A required slot due and not reported (arch 1.5 `Gaps`): only today's; [minutesLate] since it became due. */
data class Gap(val key: SlotKey, val dueAt: Instant, val minutesLate: Long)

object Gaps {
    fun of(states: List<SlotState>, now: Instant): List<Gap> =
        states.filter { it.isGap }.map { Gap(it.key, it.dueAt, Duration.between(it.dueAt, now).toMinutes().coerceAtLeast(0)) }
}

/** setAlarmClock for a slot (shown as the next alarm, exempt from Doze); exact-while-idle for a chase or a link's reminder. */
enum class FireKind { SLOT, CHASE, NOTE }

data class Fire(val at: Instant, val kind: FireKind)

/** Which notification channel a post goes to (arch 1.6): 行程提醒 (prominent), 騎車中 (sound once, no pop-up). */
enum class PostChannel { SCHEDULE, RIDING }

/**
 * One notification to post now. [key] groups it (one notification per key: the gaps are one, each link reminder its own);
 * [alert] sounds and pops up, false updates silently; [word] asks the model for this push's wording (afterwards, within the
 * daily cap); [slot] is where tapping it leads.
 */
data class Post(
    val key: String,
    val nudgeId: String,
    val text: String,
    val channel: PostChannel,
    val alert: Boolean,
    val gapKeys: List<SlotKey> = emptyList(),
    val slot: SlotKey? = null,
    val word: Boolean = false,
)

/** What one alarm fire does: the posts, whether the gap notification should go (nothing is missing), and the next fire. */
data class Tick(val posts: List<Post>, val gapsCleared: Boolean, val next: Fire?)

/**
 * When to remind and what (arch 1.6, links L8/L12): pure.
 * - [next]: the earliest of today's required slots still to come, the next 30-minute chase while something is missing, a link's
 *   reminder waiting, and tomorrow's wake slot. After sign-out nothing fires until tomorrow's wake slot (quiet time).
 * - The chase repeats every [CHASE_MIN] minutes until he reports or signs out (or the coach day ends).
 */
object NextFire {
    const val CHASE_MIN = 30L

    fun next(
        now: Instant,
        states: List<SlotState>,
        signedOut: Boolean,
        lastGapPushAt: Instant?,
        noteAt: Instant?,
        tomorrowWake: Instant?,
    ): Fire? {
        val candidates = mutableListOf<Fire>()
        tomorrowWake?.takeIf { it.isAfter(now) }?.let { candidates += Fire(it, FireKind.SLOT) }
        if (!signedOut) {
            states.filter { it.slot.required && it.status == SlotStatus.LATER && it.dueAt.isAfter(now) }.forEach { candidates += Fire(it.dueAt, FireKind.SLOT) }
            if (states.any { it.isGap }) {
                val chase = (lastGapPushAt?.plus(Duration.ofMinutes(CHASE_MIN)) ?: now).let { if (it.isAfter(now)) it else now.plusSeconds(60) }
                candidates += Fire(chase, FireKind.CHASE)
            }
            noteAt?.takeIf { it.isAfter(now) }?.let { candidates += Fire(it, FireKind.NOTE) }
        }
        return candidates.minWithOrNull(compareBy({ it.at }, { it.kind.ordinal }))
    }
}

/**
 * The proactive side (arch 1.6, links L8/L12): code decides whether to speak; the model may word it later.
 * One fire handles every gap that is due now as ONE notification (a missed run is merged: 「還缺：午餐、喝水」); a gap that is
 * still missing 30 minutes after the last push is chased again (until he reports or signs out); while riding the gaps go to
 * 騎車中 and sound once per ride, later pushes are silent, and other reminders wait for 收工 (warnings go at once). Gaps of an
 * earlier day are never notified.
 */
object Nudger {
    const val GAP_KEY = "gaps"
    const val KIND = "nudge"

    /** At most this many model wordings a day (arch 1.6); the template stays after that. */
    const val DAILY_WORDING_CAP = 16

    /** The role that speaks for a gap: meals and training are the body teacher's, the rest the manager's. */
    fun roleOf(keys: List<SlotKey>): RoleKey =
        if (keys.isNotEmpty() && keys.all { it.kind == SlotKind.MEAL || it.kind == SlotKind.TRAINING }) RoleKey.BODY else RoleKey.MANAGER

    /** Whether a gap push is due now: none yet, a new item became due, or the last one was [NextFire.CHASE_MIN] ago. */
    fun gapPushDue(gaps: List<Gap>, last: Nudge?, now: Instant): Boolean {
        if (gaps.isEmpty()) return false
        if (last == null) return true
        if (gaps.any { it.key.name !in last.gapKeys }) return true
        return !Instant.ofEpochMilli(last.at).plus(Duration.ofMinutes(NextFire.CHASE_MIN)).minusSeconds(60).isAfter(now)
    }

    /** Link reminders that may go out now: while riding only warnings and gap pushes. */
    fun postable(e: NerveEffect, riding: Boolean): Boolean {
        val n = Nerve.decodeEffect(e.json) as? Effect.Notify ?: return false
        return !riding || n.kind != NotifyKind.REMINDER
    }

    /** The gap posts of a ride sound once: only when no riding gap push went out since the ride started. */
    fun ridingAlert(nudgesToday: List<Nudge>, rideStartAt: Long?): Boolean =
        nudgesToday.none { it.channel == PostChannel.RIDING.name && it.gapKeys.isNotEmpty() && (rideStartAt == null || it.at >= rideStartAt) }

    /** The nudge row and the company line of a post. */
    fun record(p: Post, at: Long, role: RoleKey, newMessage: Boolean): Pair<Nudge, Message?> {
        val n = Nudge(p.nudgeId, at, p.gapKeys.map { it.name }, p.channel.name, null, p.text, WordedBy.TEMPLATE)
        val m = if (newMessage) Message(messageId(p.nudgeId), at, Who.COMPANY, role = role, kind = KIND, text = p.text, wordedBy = WordedBy.TEMPLATE) else null
        return n to m
    }

    fun messageId(nudgeId: String) = "$nudgeId:m"

    fun at(day: java.time.LocalDate, minute: Int, zone: java.time.ZoneId): Instant = RuleEngineTime.at(day, minute, zone)

    val SENT = EffectStatus.SENT
}
