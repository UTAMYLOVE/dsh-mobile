package app.agency.core.week

import app.agency.core.agent.Card
import app.agency.core.rules.Rules
import app.agency.core.store.Body
import app.agency.core.store.Kind
import app.agency.core.store.ProposalRec
import app.agency.core.store.Store
import app.agency.core.store.add
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What the weight line says. [pause]: below the pause weight, 小葵 stops pushing loss and a review comes first. [floor]: at or
 * under the never-below weight, she never encourages going lower. [fastLoss]: two consecutive weeks each losing more than the
 * rule allows. [proposed]: this call wrote the kcal proposal.
 */
data class BodyFlags(
    val latestKg: Double?,
    val pause: Boolean,
    val floor: Boolean,
    val fastLoss: Boolean,
    val proposed: Boolean,
)

object BodyTrend {
    const val KCAL_UP = "kcal_up"
    const val KCAL_STEP = 100
    private const val QUIET_DAYS = 14L

    /** The last weigh-in of each of the last five calendar weeks, newest first (null = no weigh-in that week). */
    internal fun weekly(store: Store, day: LocalDate): List<Double?> {
        val monday = Weeks.monday(day)
        val rows = store.entries(monday.minusWeeks(4), day, Kind.BODY).mapNotNull { e ->
            runCatching { e.read<Body>().kg }.getOrNull()?.let { e.day to it }
        }
        return (0..4).map { w ->
            val from = monday.minusWeeks(w.toLong())
            val to = from.plusDays(6)
            rows.lastOrNull { !it.first.isBefore(from) && !it.first.isAfter(to) }?.second
        }
    }

    /**
     * Reads the weigh-ins and, when he lost too fast two weeks running, writes ONE open `kcal_up` proposal (+100) unless one is
     * open already or was made in the last two weeks (a "no" must not be asked again tomorrow). The entry is stamped [now].
     */
    fun check(
        store: Store,
        day: LocalDate,
        rules: Rules,
        now: Instant = day.atTime(12, 0).toInstant(ZoneOffset.UTC),
    ): BodyFlags {
        val b = rules.body
        val latest = store.entries(day.minusDays(30), day, Kind.BODY).mapNotNull { e ->
            runCatching { e.read<Body>().kg }.getOrNull()
        }.lastOrNull()
        val w = weekly(store, day)
        val limit = b.fastLossKgPerWeek
        val fast = limit > 0 && (0..1).any { k ->
            val a = w[k]
            val m = w[k + 1]
            val o = w[k + 2]
            a != null && m != null && o != null && (o - m) > limit && (m - a) > limit
        }
        var proposed = false
        if (fast) {
            val recent = store.entries(day.minusDays(120), day, Kind.PROPOSAL).mapNotNull { e ->
                runCatching { e.read<ProposalRec>() }.getOrNull()?.let { e to it }
            }.filter { it.second.kind == KCAL_UP }
            val open = recent.any { it.second.answer == Card.Answer.OPEN }
            val quiet = recent.any { !it.first.day.isBefore(day.minusDays(QUIET_DAYS)) }
            if (!open && !quiet) {
                store.add(
                    day, now, Kind.PROPOSAL,
                    ProposalRec(
                        kind = KCAL_UP,
                        title = "每天多吃 $KCAL_STEP 大卡",
                        body = "連續兩週瘦得比預定的快，建議把每日熱量加 $KCAL_STEP 大卡，你同意再改。",
                        value = KCAL_STEP,
                    ),
                )
                proposed = true
            }
        }
        return BodyFlags(
            latestKg = latest,
            pause = latest != null && b.pauseBelowKg > 0 && latest < b.pauseBelowKg,
            floor = latest != null && b.neverBelowKg > 0 && latest <= b.neverBelowKg,
            fastLoss = fast,
            proposed = proposed,
        )
    }
}
