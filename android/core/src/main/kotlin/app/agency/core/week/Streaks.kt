package app.agency.core.week

import app.agency.core.store.CheckOut
import app.agency.core.store.DayClose
import app.agency.core.store.Kind
import app.agency.core.store.Mood
import app.agency.core.store.Store
import java.time.LocalDate

/** One counted item that was missed on [days] closed days in a row. */
data class MissedRun(val id: String, val title: String, val days: Int)

/** The three streaks 小葵 may bring up (her `talk` flags). */
data class StreakReport(val perfect: Int, val lowMood: Int, val missed: List<MissedRun>) {
    val lowMoodTalk: Boolean get() = lowMood >= Streaks.TALK_AT
}

/** Runs of days, counted backwards. Closed days come from `day_close` entries, so only days rollover has closed count. */
object Streaks {
    const val TALK_AT = 3
    private const val LOOKBACK = 120L
    private const val LOW_MOOD = 2

    private fun closes(store: Store, upTo: LocalDate): Map<LocalDate, DayClose> =
        store.entries(upTo.minusDays(LOOKBACK), upTo, Kind.DAY_CLOSE).mapNotNull { e ->
            runCatching { e.read<DayClose>() }.getOrNull()?.let { e.day to it }
        }.toMap()

    /** Consecutive perfect closed days ending at [upTo] (inclusive). */
    fun perfect(store: Store, upTo: LocalDate): Int {
        val c = closes(store, upTo)
        var n = 0
        var d = upTo
        while (c[d]?.perfect == true) { n++; d = d.minusDays(1) }
        return n
    }

    /** Consecutive days ending at [upTo] with a mood of 2 or less. A [upTo] without any mood record yet is skipped, not a break. */
    fun lowMood(store: Store, upTo: LocalDate): Int {
        val from = upTo.minusDays(LOOKBACK)
        val score = HashMap<LocalDate, Int>()
        for (e in store.entries(from, upTo, Kind.CHECK_OUT)) {
            runCatching { e.read<CheckOut>().mood }.getOrNull()?.let { score[e.day] = it }
        }
        for (e in store.entries(from, upTo, Kind.MOOD)) {
            runCatching { e.read<Mood>().score }.getOrNull()?.let { score[e.day] = it }
        }
        var d = if (upTo in score) upTo else upTo.minusDays(1)
        var n = 0
        while ((score[d] ?: Int.MAX_VALUE) <= LOW_MOOD) { n++; d = d.minusDays(1) }
        return n
    }

    /** Counted items missed on [TALK_AT] or more closed days in a row, ending at [upTo]; longest first. */
    fun missed(store: Store, upTo: LocalDate): List<MissedRun> {
        val c = closes(store, upTo)
        val last = c[upTo] ?: return emptyList()
        return last.missed.mapNotNull { (id, title) ->
            var n = 0
            var d = upTo
            while (c[d]?.missed?.containsKey(id) == true) { n++; d = d.minusDays(1) }
            if (n >= TALK_AT) MissedRun(id, title, n) else null
        }.sortedByDescending { it.days }
    }

    fun report(store: Store, upTo: LocalDate): StreakReport =
        StreakReport(perfect(store, upTo), lowMood(store, upTo), missed(store, upTo))
}
